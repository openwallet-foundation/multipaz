package org.multipaz.rpc.handler

import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.device.AssertionRpcAuth
import org.multipaz.device.DeviceAssertion
import org.multipaz.device.DeviceAssertionException
import org.multipaz.device.DeviceAttestation
import org.multipaz.device.fromCbor
import org.multipaz.device.fromDataItem
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Implementation of [RpcAuthInspector] that requires each RPC call to be authorized with
 * [AssertionRpcAuth] object signed by a secure device key (see [DeviceAssertion]). Authorization
 * is only trusted by [timeout] duration. Nonce [AssertionRpcAuth.nonce] uniqueness is checked
 * by [nonceChecker] and [DeviceAttestation] that is used to validate [AssertionRpcAuth] is looked
 * up by the client id using [clientLookup].
 */
class RpcAuthInspectorAssertion(
    val timeout: Duration = 10.minutes,
    val nonceChecker: suspend (
            clientId: String,
            nonce: ByteString,
            expiration: Instant
        ) -> RpcNonceAndSession = RpcNonceAndSession::checkNonce,
    val clientLookup: suspend (clientId: String) -> DeviceAttestation?
            = Companion::getClientDeviceAttestation
): RpcAuthInspector {
    override suspend fun authCheck(
        target: String,
        method: String,
        payload: Bstr,
        authMessage: DataItem
    ): RpcAuthContext {
        val deviceAssertion = DeviceAssertion.fromDataItem(authMessage["assertion"])
        val assertion = deviceAssertion.assertion as AssertionRpcAuth
        val attestation = clientLookup(assertion.clientId)
            ?: throw RpcAuthException(
                message = "Client '${assertion.clientId}' is unknown",
                rpcAuthError = RpcAuthError.UNKNOWN_CLIENT_ID
            )
        try {
            attestation.validateAssertion(deviceAssertion)
        } catch (err: DeviceAssertionException) {
            throw RpcAuthException(
                message = "Assertion validation: ${err.message}",
                rpcAuthError = RpcAuthError.FAILED
            )
        }
        val nonceAndSession = RpcNonceAndSession.validateAndExtractNonceAndSession(
            assertion = assertion,
            target = target,
            method = method,
            payload = payload,
            timeout = timeout,
            nonceChecker = nonceChecker
        )
        return RpcAuthContext(
            assertion.clientId,
            nonceAndSession.sessionId,
            nonceAndSession.nextNonce
        )
    }

    companion object {
        /**
         * [RpcAuthIssuerAssertion] instance that uses defaults for all its parameters.
         */
        val Default = RpcAuthInspectorAssertion()

        val rpcClientTableSpec = StorageTableSpec(
            name = "RpcClientAttestations",
            supportPartitions = false,
            supportExpiration = false
        )

        suspend fun getClientDeviceAttestation(
            clientId: String
        ): DeviceAttestation? {
            val storage = BackendEnvironment.getInterface(Storage::class)!!
            val table = storage.getTable(rpcClientTableSpec)
            val record = table.get(key = clientId) ?: return null
            return DeviceAttestation.fromCbor(record.toByteArray())
        }
    }
}