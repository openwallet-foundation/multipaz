package org.multipaz.rpc.handler

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.device.Assertion
import org.multipaz.device.AssertionRpcAuth
import org.multipaz.device.fromCbor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Implementation of [RpcAuthInspector] that requires each RPC call to be authorized with
 * [AssertionRpcAuth] object signed by a trusted well-known public key. Authorization
 * is only trusted for [timeout] duration. Nonce [AssertionRpcAuth.nonce] uniqueness is checked
 * by [nonceChecker].
 */
class RpcAuthInspectorSignature(
    val timeout: Duration = 10.minutes,
    val nonceChecker: suspend (
        clientId: String,
        nonce: ByteString,
        expiration: Instant
    ) -> RpcNonceAndSession = RpcNonceAndSession::checkNonce,
    val keyLookup: suspend (String) -> EcPublicKey
): RpcAuthInspector {
    override suspend fun authCheck(
        target: String,
        method: String,
        payload: Bstr,
        authMessage: DataItem
    ): RpcAuthContext {
        val sign1 = authMessage["sign1"].asCoseSign1
        val assertion = Assertion.fromCbor(sign1.payload!!) as AssertionRpcAuth
        val algId = sign1.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        val publicKey = keyLookup(assertion.clientId)
        try {
            Cose.coseSign1Check(
                publicKey = publicKey,
                detachedData = null,
                signature = sign1,
                signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(algId)
            )
        } catch (err: SignatureVerificationException) {
            throw RpcAuthException(
                message = "RpcAuthInspectorSignature: signature verification failed: $err",
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
        private const val TAG = "RpcAuthInspectorSignature"
    }
}