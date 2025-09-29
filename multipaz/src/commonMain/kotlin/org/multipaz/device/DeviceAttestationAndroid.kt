package org.multipaz.device

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.validateAndroidKeyAttestation
import org.multipaz.crypto.SignatureVerificationException

/**
 * On Android we create a private key in secure area and use its key attestation as the
 * device attestation.
 */
data class DeviceAttestationAndroid(
    val certificateChain: X509CertChain
) : DeviceAttestation() {
    override fun validate(validationData: DeviceAttestationValidationData) {
        try {
            validateAndroidKeyAttestation(
                chain = certificateChain,
                challenge = validationData.attestationChallenge,
                requireGmsAttestation = validationData.androidGmsAttestation,
                requireVerifiedBootGreen = validationData.androidVerifiedBootGreen,
                requireKeyMintSecurityLevel = validationData.androidRequiredKeyMintSecurityLevel,
                requireAppSignatureCertificateDigests = validationData.androidAppSignatureCertificateDigests,
                requireAppPackages = validationData.androidAppPackageNames
            )
        } catch (err: Exception) {
            throw DeviceAttestationException("Failed Android device attestation", err)
        }
    }

    override fun validateAssertion(assertion: DeviceAssertion) {
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