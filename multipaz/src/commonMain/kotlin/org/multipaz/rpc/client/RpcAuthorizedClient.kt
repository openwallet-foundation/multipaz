package org.multipaz.rpc.client

import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.device.DeviceCheck
import org.multipaz.rpc.auth.ClientCheckStub
import org.multipaz.rpc.auth.ClientRegistrationStub
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcAuthError
import org.multipaz.rpc.handler.RpcAuthException
import org.multipaz.rpc.handler.RpcAuthIssuerAssertion
import org.multipaz.rpc.handler.RpcDispatcher
import org.multipaz.rpc.handler.RpcDispatcherAuth
import org.multipaz.rpc.handler.RpcDispatcherHttp
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.rpc.handler.RpcNotifierPoll
import org.multipaz.rpc.handler.RpcPollHttp
import org.multipaz.rpc.transport.HttpTransport
import org.multipaz.rpc.transport.KtorHttpTransport
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec

/**
 * Helper class to create an RPC connection to a server that authenticates the app that makes
 * a connection.
 *
 * Once a connection is created, [dispatcher] and [notifier] can be used to create client
 * RPC stubs. [rpcClientId] uniquely identifies this app instance to an RPC server.
 */
class RpcAuthorizedClient private constructor(
    val dispatcher: RpcDispatcher,
    val notifier: RpcNotifier,
    val rpcClientId: String,
    private val notificationJob: Job,
) {
    fun shutdown() {
        notificationJob.cancel()
    }

    companion object {
        /**
         * Connects to a secure RPC server using ktor HTTP client.
         *
         * @param exceptionMap contains exceptions that are used in this RPC connection
         * @param httpClientEngine ktor HTTP client engine to use
         * @param url RPC server endpoint
         * @param secureArea [SecureArea] that stores private key that identifies this client
         * @param storage [Storage] that holds information identifying this client across sessions
         * @return object that can be used to create stub objects for RPC interfaces
         */
        suspend fun connect(
            exceptionMap: RpcExceptionMap,
            httpClientEngine: HttpClientEngineFactory<*>,
            url: String,
            secureArea: SecureArea,
            storage: Storage
        ): RpcAuthorizedClient = connect(
            exceptionMap,
            KtorHttpTransport(httpClientEngine, url),
            url,
            secureArea,
            storage
        )

        /**
         * Connects to a secure RPC server using given [HttpTransport] implementation.
         */
        suspend fun connect(
            exceptionMap: RpcExceptionMap,
            httpTransport: HttpTransport,
            transportUri: String,
            secureArea: SecureArea,
            storage: Storage
        ): RpcAuthorizedClient {
            val poll = RpcPollHttp(httpTransport)
            val notifier = RpcNotifierPoll(poll)
            val notificationJob = CoroutineScope(Dispatchers.IO).launch {
                notifier.loop()
            }
            val dispatcher = RpcDispatcherHttp(httpTransport, exceptionMap)
            val hostsTable = storage.getTable(hostsTableSpec)

            val (clientId, authorizedDispatcher) = createAuthorizedDispatcher(
                dispatcher, notifier, transportUri, secureArea, hostsTable)

            return RpcAuthorizedClient(
                dispatcher = authorizedDispatcher,
                notifier = notifier,
                rpcClientId = clientId,
                notificationJob = notificationJob
            )
        }

        private suspend fun createAuthorizedDispatcher(
            dispatcher: RpcDispatcher,
            notifier: RpcNotifier,
            baseUrl: String,
            secureArea: SecureArea,
            hostsTable: StorageTable
        ): Pair<String, RpcDispatcher> {
            val connectionDataBytes = hostsTable.get(key = baseUrl)
            val connectionData = if (connectionDataBytes == null) {
                // RPC entry point that does not require authorization, it is used to set up
                // authorization parameters with the server (so these parameters can be used for subsequent
                // RPC communication).
                registerClient(dispatcher, notifier, baseUrl, secureArea, hostsTable)
            } else {
                HostData.fromCbor(connectionDataBytes.toByteArray())
            }

            val authorizedDispatcher = RpcDispatcherAuth(
                base = dispatcher,
                rpcAuthIssuer = RpcAuthIssuerAssertion(
                    clientId = connectionData.clientId,
                    secureArea = secureArea,
                    deviceAttestationId = connectionData.deviceAttestationId
                )
            )

            if (connectionDataBytes == null) {
                return Pair(connectionData.clientId, authorizedDispatcher)
            }

            // Existing client, need freshness check.
            val authCheck = ClientCheckStub(
                endpoint = "client_check",
                dispatcher = authorizedDispatcher,
                notifier = notifier
            )

            try {
                withContext(RpcAuthClientSession()) {
                    check(authCheck.ping("multipaz") == "multipaz")
                }
            } catch (err: RpcAuthException) {
                if (err.rpcAuthError != RpcAuthError.UNKNOWN_CLIENT_ID) {
                    throw err;
                }
                // Client was removed/purged by the server. Remove the record for this
                // server and try again.
                hostsTable.delete(key = baseUrl)
                return createAuthorizedDispatcher(
                    dispatcher, notifier, baseUrl, secureArea, hostsTable)
            }
            return Pair(connectionData.clientId, authorizedDispatcher)
        }

        private suspend fun registerClient(
            dispatcher: RpcDispatcher,
            notifier: RpcNotifier,
            baseUrl: String,
            secureArea: SecureArea,
            hostsTable: StorageTable
        ): HostData {
            val clientRegistration = ClientRegistrationStub(
                endpoint = "client_registration",
                dispatcher = dispatcher,
                notifier = notifier
            )
            val (attestationId, attestation) = DeviceCheck.generateAttestation(
                secureArea = secureArea,
                challenge = clientRegistration.challenge()
            )
            val clientId = clientRegistration.register(attestation)
            val hostData = HostData(
                clientId = clientId,
                deviceAttestationId = attestationId
            )
            hostsTable.insert(
                data = ByteString(hostData.toCbor()),
                key = baseUrl
            )
            return hostData
        }

        private val hostsTableSpec = StorageTableSpec(
            name = "Hosts",
            supportExpiration = false,
            supportPartitions = false
        )
    }

    @CborSerializable
    internal data class HostData(val clientId: String, val deviceAttestationId: String) {
        companion object Companion
    }


}