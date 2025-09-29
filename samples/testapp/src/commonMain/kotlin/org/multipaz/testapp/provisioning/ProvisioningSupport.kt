package org.multipaz.testapp.provisioning

import io.ktor.http.Url
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendStub
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.client.RpcAuthorizedClient
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.transport.HttpTransport
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.testapp.platformHttpClientEngineFactory
import org.multipaz.util.Logger

/**
 * Imitate OpenID4VCI wallet back-end for the test app and provide support for the app links.
 *
 * In a real wallet app, the app should call its back-end server to implement [OpenID4VCIBackend]
 * interface, as keys that are used to sign various attestations and assertions must be kept
 * secret. For testing purposes the keys are embedded into the app itself - but such app can be
 * easily impersonated and therefore can never be trusted by a real-life provisioning server.
 */
class ProvisioningSupport(
    val storage: Storage,
    val secureArea: SecureArea,
) {
    companion object {
        const val APP_LINK_SERVER = "https://apps.multipaz.org"
        const val APP_LINK_BASE_URL = "$APP_LINK_SERVER/landing/"

        const val TAG = "ProvisioningSupport"

        val BACKEND_SERVER_URL: String? = null
    }

    private val lock = Mutex()
    private val pendingLinksByState = mutableMapOf<String, SendChannel<String>>()

    private lateinit var backend: OpenID4VCIBackend
    private lateinit var preferences: OpenID4VCIClientPreferences

    suspend fun init() {
        var backend: OpenID4VCIBackend? = null
        if (BACKEND_SERVER_URL != null) {
            try {
                val rpcAuthorizedClient = RpcAuthorizedClient.connect(
                    exceptionMap = RpcExceptionMap.Builder().build(),
                    httpClientEngine = platformHttpClientEngineFactory(),
                    url = BACKEND_SERVER_URL,
                    secureArea = secureArea,
                    storage = storage
                )
                backend = OpenID4VCIBackendStub(
                    endpoint = "openid4vci_backend",
                    dispatcher = rpcAuthorizedClient.dispatcher,
                    notifier = rpcAuthorizedClient.notifier
                )
            } catch (err: HttpTransport.ConnectionException) {
                Logger.e(TAG, "Error connecting to back-end", err)
            }
        }
        if (backend == null) {
            backend = OpenID4VCILocalBackend()
        }
        this.backend = backend
        preferences = OpenID4VCIClientPreferences(
            clientId = withContext(RpcAuthClientSession()) {
                backend.getClientId()
            },
            redirectUrl = APP_LINK_BASE_URL,
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256, Algorithm.ESP384, Algorithm.ESP512)
        )
    }

    suspend fun processAppLinkInvocation(url: String) {
        val state = Url(url).parameters["state"] ?: ""
        lock.withLock {
            pendingLinksByState.remove(state)?.send(url)
        }
    }

    suspend fun waitForAppLinkInvocation(state: String): String {
        val channel = Channel<String>(Channel.RENDEZVOUS)
        lock.withLock {
            pendingLinksByState[state] = channel
        }
        return channel.receive()
    }

    fun getOpenID4VCIClientPreferences(): OpenID4VCIClientPreferences = preferences

    fun getOpenID4VCIBackend(): OpenID4VCIBackend = backend
}