package org.multipaz.device

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import kotlinx.io.bytestring.isNotEmpty
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.Hkdf
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.device.DeviceAttestationSoftware.Companion.generateAttestation
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.util.Logger
import kotlin.time.Instant

/**
 * A [DeviceAttestation] for environments that do not support platform-backed attestations.
 *
 * This format includes a key pair which reside on the device to be used for generating
 * one or more [DeviceAssertion]. It doesn't actually prove anything - it can't - so this
 * attestation type is normally used together with proving that the client possesses a
 * secret. This proof is computed as follows
 * ```
 * K = HKDF(
 *   ikm = secretUtf8Encoded,
 *   salt = challenge,
 *   info = "MpzAttestationWithSecret1",
 *   length = 32
 * )
 *
 * proofOfSecret = HMAC(
 *   key = K,
 *   message = challenge || secretUtf8Encoded
 * )
 * ```
 * using the server-provided challenge and SHA-256 as the hash function. This mechanism
 * should be used carefully since in most cases embedding a secret in the client binary
 * is inherently insecure due to the risk of attackers exfiltrating the secret.
 *
 * @property publicKey the public part of the device-bound key.
 * @property proofOfSecret optional proof of possession of a secret.
 */
data class DeviceAttestationSoftware(
    val publicKey: EcPublicKey,
    val proofOfSecret: ByteString? = null
): DeviceAttestation() {
    override suspend fun validate(
        validationData: DeviceAttestationValidationData,
        validateAt: Instant
    ) {
        if (!validationData.softwareAccepted) {
            throw DeviceAttestationException("DeviceAttestationSoftware is not accepted")
        }
        validateProofOfSecret(
            challenge = validationData.attestationChallenge,
            proofOfSecret = proofOfSecret,
            secrets = validationData.softwareSecrets,
        )
    }

    override suspend fun validateAssertion(assertion: DeviceAssertion) {
        val signature =
            EcSignature.fromCoseEncoded(assertion.platformAssertion.toByteArray())
        try {
            Crypto.checkSignature(
                publicKey = publicKey,
                message = assertion.assertionData.toByteArray(),
                algorithm = Algorithm.ES256,
                signature = signature
            )
        } catch (e: SignatureVerificationException) {
            throw DeviceAssertionException("DeviceAssertion signature validation failed", e)
        }
    }

    companion object {
        private const val TAG = "DeviceAttestationSoftware"

        internal suspend fun calculateProofOfSecret(
            secret: String,
            challenge: ByteString
        ): ByteString {
            val info = "MpzAttestationWithSecret1".encodeToByteArray()
            if (challenge.isEmpty()) {
                Logger.w(TAG, "Generating proofOfSecret with an empty challenge is not secure")
            }
            val key = Hkdf.deriveKey(
                algorithm = Algorithm.HMAC_SHA256,
                ikm = secret.encodeToByteArray(),
                salt = if (challenge.isNotEmpty()) challenge.toByteArray() else null,
                info = info,
                length = 32
            )
            val mac = Crypto.mac(
                algorithm = Algorithm.HMAC_SHA256,
                key = key,
                message = challenge.toByteArray() + secret.encodeToByteArray()
            )
            return ByteString(mac)
        }

        internal suspend fun validateProofOfSecret(
            challenge: ByteString,
            proofOfSecret: ByteString?,
            secrets: Set<String>
        ) {
            if (secrets.isEmpty()) {
                return
            }
            if (proofOfSecret == null) {
                throw DeviceAttestationException("Secret is required but not set")
            }
            for (secret in secrets) {
                val proof = calculateProofOfSecret(
                    secret = secret,
                    challenge = challenge
                )
                if (proof == proofOfSecret) {
                    return
                }
            }
            throw DeviceAttestationException("Secret validation failed")
        }

        /**
         * An implementation of [DeviceCheck.generateAttestation] for [DeviceAttestationSoftware].
         *
         * @param secureArea a platform-specific [SecureArea] to store the key.
         * @param challenge should come from the party requesting the attestation, for freshness.
         * @param secret optional secret for identifying the client.
         * @return a [DeviceAttestationResult] containing a [DeviceAttestation] which can be sent to the party
         *   requesting the attestation.
         */
        suspend fun generateAttestation(
            secureArea: SecureArea,
            challenge: ByteString,
            secret: String? = null
        ): DeviceAttestationResult {
            val keySettings = CreateKeySettings(nonce = challenge)
            val keyInfo = secureArea.createKey(null, keySettings)
            return DeviceAttestationResult(
                deviceAttestationId = keyInfo.alias,
                deviceAttestation = DeviceAttestationSoftware(
                    publicKey = keyInfo.publicKey,
                    proofOfSecret = secret?.let {
                        calculateProofOfSecret(
                            secret = it,
                            challenge = challenge,
                        )
                    }
                )
            )
        }

        /**
         * An implementation of [DeviceCheck.generateAssertion] for [DeviceAttestationSoftware].
         *
         * @param secureArea must be the same value as was passed to [generateAttestation] method.
         * @param deviceAttestationId the attestation id from the [DeviceAttestationResult] obtained
         *   from the [generateAttestation] call.
         * @param assertion the assertion of make, e.g. [AssertionNonce].
         * @return a [DeviceAssertion] which contains proof of [assertion] which can be sent to the
         *   party requesting the assertion.
         */
        suspend fun generateAssertion(
            secureArea: SecureArea,
            deviceAttestationId: String,
            assertion: Assertion
        ): DeviceAssertion {
            val assertionData = assertion.toCbor()
            val signature = secureArea.sign(
                alias = deviceAttestationId,
                dataToSign = assertionData
            )
            return DeviceAssertion(
                assertionData = ByteString(assertionData),
                platformAssertion = ByteString(signature.toCoseEncoded())
            )
        }
    }
}