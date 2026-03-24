package org.multipaz.securearea.software

import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.config.SecureAreaConfigurationSoftware
import kotlin.time.Instant;
import kotlinx.io.bytestring.buildByteString
import org.multipaz.crypto.EcPrivateKey

/**
 * Class used to indicate key creation settings for software-backed keys.
 *
 * @property privateKey private key material to use for the key or `null`.
 */
class SoftwareCreateKeySettings internal constructor(
    val passphraseRequired: Boolean,
    val passphrase: String?,
    val passphraseConstraints: PassphraseConstraints?,
    algorithm: Algorithm,
    val subject: String?,
    validFrom: Instant?,
    validUntil: Instant?,
    val privateKey: EcPrivateKey?
) : CreateKeySettings(
    algorithm = algorithm,
    nonce = buildByteString {},
    validFrom = validFrom,
    validUntil = validUntil
) {
    /**
     * A builder for [SoftwareCreateKeySettings].
     */
    class Builder {
        private var algorithm: Algorithm = Algorithm.ESP256
        private var passphraseRequired: Boolean = false
        private var passphrase: String? = null
        private var passphraseConstraints: PassphraseConstraints? = null
        private var subject: String? = null
        private var validFrom: Instant? = null
        private var validUntil: Instant? = null
        private var privateKey: EcPrivateKey? = null

        /**
         * Apply settings from configuration object.
         *
         * @param configuration configuration from a CBOR map.
         * @return the builder.
         */
        fun applyConfiguration(configuration: SecureAreaConfigurationSoftware) = apply {
            setAlgorithm(Algorithm.fromName(configuration.algorithm))
            setPassphraseRequired(
                required = configuration.passphrase != null,
                passphrase = configuration.passphrase,
                constraints = configuration.passphraseConstraints
            )
        }

        /**
         * Sets the algorithm for the key.
         *
         * By default [Algorithm.ESP256] is used.
         *
         * @param algorithm a fully specified algorithm.
         * @return the builder.
         */
        fun setAlgorithm(algorithm: Algorithm) = apply {
            this.algorithm = algorithm
        }

        /**
         * Sets the passphrase required to use a key.
         *
         * @param required whether a passphrase is required.
         * @param passphrase the passphrase to use, must not be `null` if `required` is `true`.
         * @param constraints constraints for the passphrase or `null` if not constrained.
         * @return the builder.
         */
        fun setPassphraseRequired(
            required: Boolean,
            passphrase: String?,
            constraints: PassphraseConstraints?
        ) = apply {
            check(!passphraseRequired || passphrase != null) {
                "Passphrase cannot be null if passphrase is required"
            }
            passphraseRequired = required
            this.passphrase = passphrase
            this.passphraseConstraints = constraints
        }

        /**
         * Sets the subject of the key, to be included in the attestation.
         *
         * @param subject subject field
         * @return the builder.
         */
        fun setSubject(subject: String?) = apply {
            this.subject = subject
        }

        /**
         * Sets the key validity period.
         *
         * By default the key validity period is unbounded.
         *
         * @param validFrom the point in time before which the key is not valid.
         * @param validUntil the point in time after which the key is not valid.
         * @return the builder.
         */
        fun setValidityPeriod(validFrom: Instant, validUntil: Instant) = apply {
            this.validFrom = validFrom
            this.validUntil = validUntil
        }

        /**
         * Sets the private key material.
         *
         * @param privateKey the private key material.
         * @return the builder.
         */
        fun setPrivateKey(
            privateKey: EcPrivateKey
        ) = apply {
            this.privateKey = privateKey
        }

        /**
         * Builds the [SoftwareCreateKeySettings].
         *
         * @return a new [SoftwareCreateKeySettings].
         */
        fun build(): SoftwareCreateKeySettings {
            return SoftwareCreateKeySettings(
                passphraseRequired, passphrase, passphraseConstraints, algorithm, subject, validFrom, validUntil, privateKey
            )
        }
    }
}