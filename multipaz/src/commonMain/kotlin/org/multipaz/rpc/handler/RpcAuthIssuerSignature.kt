package org.multipaz.rpc.handler

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cose.Cose
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.device.AssertionRpcAuth
import org.multipaz.device.DeviceAssertion
import org.multipaz.device.toCbor
import kotlin.time.Clock

/**
 * [RpcAuthIssuer] implementation that authorizes each call with using [AssertionRpcAuth] object
 * wrapped in cose sign1 object signed by a private key. In addition to `payload` field in in
 * authorization Cbor map it adds a `sign1` field that holds signed data.
 */
class RpcAuthIssuerSignature(
    private val clientId: String,
    private val signingKey: AsymmetricKey
): RpcAuthIssuer {
    override suspend fun auth(target: String, method: String, payload: Bstr): DataItem {
        val sessionContext = currentCoroutineContext()[RpcAuthClientSession.Key]
            ?: throw IllegalStateException("RpcAuthClientSession must be provided")
        val assertion = AssertionRpcAuth(
            target = target,
            method = method,
            clientId = clientId,
            nonce = sessionContext.nonce,
            timestamp = Clock.System.now(),
            payloadHash = ByteString(Crypto.digest(Algorithm.SHA256, payload.value))
        )
        val sign1 = Cose.coseSign1Sign(
            signingKey = signingKey,
            message = assertion.toCbor(),
            includeMessageInPayload = true,
            protectedHeaders = mapOf(),
            unprotectedHeaders = mapOf()
        )
        return buildCborMap {
            put("payload", payload)
            put("sign1", sign1.toDataItem())
        }
    }
}