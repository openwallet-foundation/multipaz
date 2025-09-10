package org.multipaz.securearea

import android.os.Build
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.config.SecureAreaConfigurationAndroidKeystore
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Class for holding Android Keystore-specific settings related to key creation.
 */
class AndroidKeystoreCreateKeySettings private constructor(
    algorithm: Algorithm,

    /**
     * The attestation challenge.
     */
    val attestationChallenge: ByteString,

    userAuthenticationRequired: Boolean,

    userAuthenticationTimeout: Duration,

    /**
     * User authentication types for the key.
     *
     * @return a set of [UserAuthenticationType]
     */
    val userAuthenticationTypes: Set<UserAuthenticationType>,

    /**
     * Whether StrongBox is used.
     */
    val useStrongBox: Boolean,

    /**
     * The attest key alias, if any.
     */
    val attestKeyAlias: String?,

    validFrom: Instant?,

    validUntil: Instant?

) : CreateKeySettings(algorithm, attestationChallenge, userAuthenticationRequired, userAuthenticationTimeout, validFrom, validUntil) {

    /**
     * A builder for [CreateKeySettings].
     *
     * @param attestationChallenge challenge to include in attestation for the key.
     */
    class Builder(private val attestationChallenge: ByteString) {
        private var algorithm: Algorithm = Algorithm.ESP256
        private var userAuthenticationRequired = false
        private var userAuthenticationTimeout: Duration = 0.seconds

        private var userAuthenticationTypes = emptySet<UserAuthenticationType>()
        private var useStrongBox = false
        private var attestKeyAlias: String? = null
        private var validFrom: Instant? = null
        private var validUntil: Instant? = null

        /**
         * Apply settings from configuration object.
         *
         * @param configuration configuration from a CBOR map.
         * @return the builder.
         */
        fun applyConfiguration(configuration: SecureAreaConfigurationAndroidKeystore) = apply {
            setAlgorithm(Algorithm.fromName(configuration.algorithm))
            setUseStrongBox(configuration.useStrongBox)
            setUserAuthenticationRequired(
                configuration.userAuthenticationRequired,
                 configuration.userAuthenticationTimeoutMillis.milliseconds,
                UserAuthenticationType.decodeSet(configuration.userAuthenticationTypes)
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
         * Method to specify if user authentication is required to use the key.
         *
         * On devices with prior to API 30, `userAuthenticationType` must be
         * [.USER_AUTHENTICATION_TYPE_LSKF] combined with
         * [.USER_AUTHENTICATION_TYPE_BIOMETRIC]. On API 30 and later
         * either flag may be used independently. The value cannot be zero if user
         * authentication is required.
         *
         * By default, no user authentication is required.
         *
         * @param required True if user authentication is required, false otherwise.
         * @param timeout If 0, user authentication is required for every use of
         *   the key, otherwise it's required within the given amount of time.
         * @param userAuthenticationTypes a combination of [UserAuthenticationType] flags.
         * @return the builder.
         */
        fun setUserAuthenticationRequired(
            required: Boolean,
            timeout: Duration,
            userAuthenticationTypes: Set<UserAuthenticationType>
        ): Builder {
            if (required) {
                require(UserAuthenticationType.encodeSet(userAuthenticationTypes) != 0L) {
                    "userAuthenticationType must be set when user authentication is required" }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    require(
                        UserAuthenticationType.encodeSet(userAuthenticationTypes) ==
                            UserAuthenticationType.LSKF.flagValue or
                            UserAuthenticationType.BIOMETRIC.flagValue) {
                        "Only LSKF and Strong Biometric supported on this API level" }
                }
            }
            userAuthenticationRequired = required
            userAuthenticationTimeout = timeout
            this.userAuthenticationTypes = userAuthenticationTypes
            return this
        }

        /**
         * Method to specify if StrongBox Android Keystore should be used, if available.
         *
         * By default StrongBox isn't used.
         *
         * @param useStrongBox Whether to use StrongBox.
         * @return the builder.
         */
        fun setUseStrongBox(useStrongBox: Boolean): Builder {
            this.useStrongBox = useStrongBox
            return this
        }

        /**
         * Method to specify if an attest key should be used.
         *
         * By default no attest key is used. See
         * [setAttestKeyAlias() method](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setAttestKeyAlias(java.lang.String))
         * for more information about attest keys.
         *
         * When an attest key is used, the certificate chain for the created key will include the certificate
         * for the key as its leaf certificate (signed by the attest key) followed by the attestation for the
         * attest key.
         *
         * @param attestKeyAlias the Android Keystore alias of the attest key or `null` to not use an attest key.
         * @return the builder.
         */
        fun setAttestKeyAlias(attestKeyAlias: String?): Builder {
            this.attestKeyAlias = attestKeyAlias
            return this
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
        fun setValidityPeriod(
            validFrom: Instant,
            validUntil: Instant
        ): Builder {
            this.validFrom = validFrom
            this.validUntil = validUntil
            return this
        }

        /**
         * Builds the [CreateKeySettings].
         *
         * @return a new [CreateKeySettings].
         */
        fun build(): AndroidKeystoreCreateKeySettings {
            return AndroidKeystoreCreateKeySettings(
                algorithm,
                attestationChallenge,
                userAuthenticationRequired,
                userAuthenticationTimeout,
                userAuthenticationTypes,
                useStrongBox,
                attestKeyAlias,
                validFrom,
                validUntil
            )
        }
    }
}