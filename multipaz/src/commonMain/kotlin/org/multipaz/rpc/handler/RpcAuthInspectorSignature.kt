package org.multipaz.rpc.handler

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.device.Assertion
import org.multipaz.device.AssertionRpcAuth
import org.multipaz.device.fromCbor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Implementation of [RpcAuthInspector] that requires each RPC call to be authorized with
 * [AssertionRpcAuth] object signed by a trusted well-known public key.
 *
 * @property timeout authorization is only trusted for this duration
 * @property nonceChecker function that validates nonce [AssertionRpcAuth.nonce]
 * @property certificateLookup function that returns certificate with the public key that is
 *  used to validate message signature (directly or through the certificate chain, if it is
 *  included in the message).
 */
class RpcAuthInspectorSignature(
    val timeout: Duration = 10.minutes,
    val nonceChecker: suspend (
        clientId: String,
        nonce: ByteString,
        expiration: Instant
    ) -> RpcNonceAndSession = RpcNonceAndSession::checkNonce,
    val certificateLookup: suspend (String) -> X509Cert
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
        val certChain = sign1.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]
        val cert = if (certChain == null) {
            certificateLookup(assertion.clientId)
        } else {
            val x5c = certChain.asX509CertChain
            val root = certificateLookup(x5c.certificates.last().issuer.name)
            X509CertChain(x5c.certificates + listOf(root)).validate()
            x5c.certificates.first()
        }
        try {
            Cose.coseSign1Check(
                publicKey = cert.ecPublicKey,
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