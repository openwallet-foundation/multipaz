package org.multipaz.device

import kotlinx.coroutines.CancellationException
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.validateAndroidKeyAttestation
import kotlin.time.Instant

/**
 * On Android we create a private key in secure area and use its key attestation as the
 * device attestation.
 *
 * @property certificateChain the certificate chain.
 */
data class DeviceAttestationAndroid(
    val certificateChain: X509CertChain
): DeviceAttestation() {
    override suspend fun validate(
        validationData: DeviceAttestationValidationData,
        validateAt: Instant
    ) {
        try {
            validateAndroidKeyAttestation(
                chain = certificateChain,
                challenge = validationData.attestationChallenge,
                requireGmsAttestation = validationData.androidGmsAttestation,
                requireVerifiedBootGreen = validationData.androidVerifiedBootGreen,
                requireKeyMintSecurityLevel = validationData.androidRequiredKeyMintSecurityLevel,
                requireAppSignatureCertificateDigests = validationData.androidAppSignatureCertificateDigests,
                requireAppPackages = validationData.androidAppPackageNames,
                validateAt = validateAt
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw DeviceAttestationException("Failed Android device attestation: ${e.message}", e)
        }
    }

    override suspend fun validateAssertion(assertion: DeviceAssertion) {
        val signature =
            EcSignature.fromCoseEncoded(assertion.platformAssertion.toByteArray())
        try {
            Crypto.checkSignature(
                publicKey = certificateChain.certificates.first().ecPublicKey,
                message = assertion.assertionData.toByteArray(),
                algorithm = Algorithm.ES256,
                signature = signature
            )
        } catch (e: SignatureVerificationException) {
            throw DeviceAssertionException("DeviceAssertion signature validation failed", e)
        }
    }
}