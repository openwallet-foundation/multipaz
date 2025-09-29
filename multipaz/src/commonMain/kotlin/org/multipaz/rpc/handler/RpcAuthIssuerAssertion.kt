package org.multipaz.rpc.handler

import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.device.AssertionRpcAuth
import org.multipaz.device.DeviceCheck
import org.multipaz.device.DeviceAssertion
import org.multipaz.device.toDataItem
import org.multipaz.securearea.SecureArea
import kotlin.coroutines.coroutineContext

/**
 * [RpcAuthIssuer] implementation that authorizes each call with using [AssertionRpcAuth] object
 * signed by a secure device key. In addition to `payload` field in in authorization Cbor map it
 * adds an `assertion` field that holds [DeviceAssertion].
 */
class RpcAuthIssuerAssertion(
    private val clientId: String,
    private val secureArea: SecureArea,
    private val deviceAttestationId: String
): RpcAuthIssuer {
    override suspend fun auth(target: String, method: String, payload: Bstr): DataItem {
        val sessionContext = coroutineContext[RpcAuthClientSession.Key]
            ?: throw IllegalStateException("RpcAuthClientSessionContext must be provided")
        val assertion = AssertionRpcAuth(
            target = target,
            method = method,
            clientId = clientId,
            nonce = sessionContext.nonce,
            timestamp = Clock.System.now(),
            payloadHash = ByteString(Crypto.digest(Algorithm.SHA256, payload.value))
        )
        val deviceAssertion = DeviceCheck.generateAssertion(
            secureArea = secureArea,
            deviceAttestationId = deviceAttestationId,
            assertion = assertion
        )
        return buildCborMap {
            put("payload", payload)
            put("assertion", deviceAssertion.toDataItem())
        }
    }
}