package org.multipaz.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.OID
import org.multipaz.securearea.KeyInfo
import org.multipaz.securearea.KeyInvalidatedException
import org.multipaz.securearea.KeyLockedException
import org.multipaz.prompt.Reason
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository

/**
 * Private key that can be used to sign messages or used for key agreement, optionally with
 * some kind of identification.
 *
 * A private key can either be a software key ([EcPrivateKey] or [RsaPrivateKey]) or reside in
 * a [SecureArea]. Keys can either be anonymous or identified either by a certificate chain or
 * using a key id. When reading a key from settings, all six possible variants are potentially
 * useful, yet it makes very little difference for the rest of the code which variant is actually
 * used. [AsymmetricKey] class encapsulates these variants so the code can be written in more
 * generic way.
 *
 * Although strictly speaking not a signing operation, [AsymmetricKey] can also be used for
 * key exchange operation, provided it was created with that capability.
 */
sealed class AsymmetricKey : AutoCloseable {
    /** Signature algorithm */
    abstract val algorithm: Algorithm
    /** Public key that corresponds to the private key used for signing */
    abstract val publicKey: PublicKey

    /**
     * Closes the key, zeroing any underlying private key material if this is an explicit key.
     */
    override fun close() {
        if (this is Explicit) {
            privateKey.close()
        }
    }

    /** Public key as [EcPublicKey] */
    val ecPublicKey: EcPublicKey
        get() = publicKey as? EcPublicKey
            ?: throw IllegalStateException("Key is not an EC key")

    /** Public key as [RsaPublicKey] */
    val rsaPublicKey: RsaPublicKey
        get() = publicKey as? RsaPublicKey
            ?: throw IllegalStateException("Key is not an RSA key")

    /** Public key as [MlDsaPublicKey] */
    val mlDsaPublicKey: MlDsaPublicKey
        get() = publicKey as? MlDsaPublicKey
            ?: throw IllegalStateException("Key is not an ML-DSA key")

    /**
     * Entity to which the key belongs; key id for named key, common name for the keys with
     * the certificate chain.
     *
     * @throws IllegalStateException when called on [Anonymous] key.
     */
    abstract val subject: String

    /**
     * Signs [message] with this key.
     *
     * If the key needs unlocking before use (for example user authentication
     * in any shape or form) and `keyUnlockData` isn't set or doesn't contain
     * what's needed, [KeyLockedException] is thrown.
     *
     * @param message the data to sign.
     * @return the signature.
     * @throws IllegalArgumentException if there is no key with the given alias
     *      or the key wasn't created with purpose [KeyPurpose.SIGN].
     * @throws IllegalArgumentException if the signature algorithm isn’t compatible with the key.
     * @throws KeyLockedException if the key needs unlocking.
     * @throws KeyInvalidatedException if the key is no longer usable.
     */
    abstract suspend fun sign(message: ByteArray): Signature

    /**
     * Convenience method to sign [message] when this is an EC key.
     *
     * @throws IllegalStateException if the key is not an EC key.
     */
    suspend fun signEc(message: ByteArray): EcSignature =
        sign(message) as? EcSignature ?: throw IllegalStateException("Not an EC signature")

    /**
     * Convenience method to sign [message] when this is an RSA key.
     *
     * @throws IllegalStateException if the key is not an RSA key.
     */
    suspend fun signRsa(message: ByteArray): RsaSignature =
        sign(message) as? RsaSignature ?: throw IllegalStateException("Not an RSA signature")

    /**
     * Convenience method to sign [message] when this is an ML-DSA key.
     *
     * @throws IllegalStateException if the key is not an ML-DSA key.
     */
    suspend fun signMlDsa(message: ByteArray): MlDsaSignature =
        sign(message) as? MlDsaSignature ?: throw IllegalStateException("Not an ML-DSA signature")

    /**
     * Performs Key Agreement using this key and [otherKey].
     *
     * If the key needs unlocking before use (for example user authentication
     * in any shape or form) and `keyUnlockData` isn't set or doesn't contain
     * what's needed, [KeyLockedException] is thrown.
     *
     * @param otherKey The public EC key from the other party
     * @return The shared secret.
     * @throws IllegalArgumentException if the other key isn't the same curve.
     * @throws IllegalArgumentException if there is no key with the given alias
     *     or the key wasn't created with purpose [KeyPurpose.AGREE_KEY].
     * @throws UnsupportedOperationException if this key does not support key agreement (e.g. RSA).
     * @throws KeyLockedException if the key needs unlocking.
     * @throws KeyInvalidatedException if the key is no longer usable.
     */
    abstract suspend fun keyAgreement(otherKey: EcPublicKey): ByteArray

    /**
     * Performs Key Agreement using this key and [otherKey].
     *
     * @throws IllegalArgumentException if [otherKey] is not an [EcPublicKey].
     * @throws UnsupportedOperationException if this key does not support key agreement (e.g. RSA).
     */
    suspend fun keyAgreement(otherKey: PublicKey): ByteArray =
        when (otherKey) {
            is EcPublicKey -> keyAgreement(otherKey)
            is RsaPublicKey -> throw IllegalArgumentException("Key agreement is not supported with RSA public key")
            is MlDsaPublicKey -> throw IllegalArgumentException("Key agreement is not supported with ML-DSA public key")
            is MlKemPublicKey -> throw IllegalArgumentException("Key agreement is not supported with ML-KEM public key")
        }

    /**
     * Implemented by [AsymmetricKey] where the private key is explicitly given.
     *
     * Keys of this type are vulnerable to copying.
     */
    interface Explicit {
        /** Private key that is used for signing. */
        val privateKey: PrivateKey
        /** Signature algorithm */
        val algorithm: Algorithm

        /** Private key as [EcPrivateKey] */
        val ecPrivateKey: EcPrivateKey
            get() = privateKey as? EcPrivateKey
                ?: throw IllegalStateException("Key is not an EC key")

        /** Private key as [RsaPrivateKey] */
        val rsaPrivateKey: RsaPrivateKey
            get() = privateKey as? RsaPrivateKey
                ?: throw IllegalStateException("Key is not an RSA key")

        /** Private key as [MlDsaPrivateKey] */
        val mlDsaPrivateKey: MlDsaPrivateKey
            get() = privateKey as? MlDsaPrivateKey
                ?: throw IllegalStateException("Key is not an ML-DSA key")
    }

    /** Implemented by [AsymmetricKey] where the private key resides in [SecureArea] */
    interface SecureAreaBased {
        /** Alias of the private key that is used for signing */
        val alias: String
        /** [SecureArea] that holds the private key */
        val secureArea: SecureArea
        /** [Reason] that should be used to generate a signature */
        val unlockReason: Reason
        /** Key data */
        val keyInfo: KeyInfo
    }

    /**
     * Keys that are (potentially) compatible with X509-certificate-based workflows.
     *
     * Anonymous keys are compatible with X509 workflows if the identity of the key is clear
     * from the context: one example is a newly-minted key for a self-signed certificate before
     * the certificate is actually created.
     */
    sealed class X509Compatible: AsymmetricKey() {
        /**
         * X509 certificate chain for the key, corresponds to `x5c` header value in JWT.
         *
         * Public key in the first certificate chain must correspond to the private key
         * used for signing. Certificate chain must be valid.
         *
         * [Anonymous] keys have `null` certificate chain, [X509Certified] keys always have
         * non-`null` not-empty certificate chains.
         */
        abstract val certChain: X509CertChain?
    }

    /**
     * Key without identification, typically used when it is clear from the context which key
     * must be employed.
     */
    sealed class Anonymous: X509Compatible() {
        override val certChain: X509CertChain? get() = null
        override val subject: String get() = throw IllegalStateException("anonymous key")
    }

    /**
     * Key identified by a key id which is somehow known to other parties.
     *
     * [Named] keys must never be used in X509-certificate based workflows. Use [Anonymous]
     * keys instead.
     */
    sealed class Named: AsymmetricKey() {
        /** Key identifier, corresponds to `kid` header value in JWT */
        abstract val keyId: String
        override val subject: String get() = keyId
    }

    /**
     * A key which is identified by a X509 certificate chain.
     */
    sealed class X509Certified: X509Compatible() {
        abstract override val certChain: X509CertChain
        override val subject: String get() = commonName(certChain)

        /** Returns key's certificate subject as X500Name. */
        fun getX500Subject(): X500Name = certChain.certificates.first().subject
    }

    /** [AsymmetricKey] which is both [AsymmetricKey.X509Certified] and [AsymmetricKey.Explicit]. */
    data class X509CertifiedExplicit(
        override val certChain: X509CertChain,
        override val privateKey: PrivateKey,
        override val algorithm: Algorithm = when (privateKey) {
            is EcPrivateKey -> privateKey.curve.defaultSigningAlgorithmFullySpecified
            is RsaPrivateKey -> Algorithm.RS256
            is MlDsaPrivateKey -> privateKey.algorithm
            is MlKemPrivateKey -> privateKey.algorithm
        }
    ): X509Certified(), Explicit {
        override val publicKey: PublicKey get() = privateKey.publicKey
        override suspend fun sign(message: ByteArray): Signature = sign(this, message)
        override suspend fun keyAgreement(otherKey: EcPublicKey): ByteArray = keyAgreement(this, otherKey)
    }

    /** [AsymmetricKey] which is both [AsymmetricKey.Named] and [AsymmetricKey.Explicit]. */
    data class NamedExplicit(
        override val keyId: String,
        override val privateKey: PrivateKey,
        override val algorithm: Algorithm = when (privateKey) {
            is EcPrivateKey -> privateKey.curve.defaultSigningAlgorithmFullySpecified
            is RsaPrivateKey -> Algorithm.RS256
            is MlDsaPrivateKey -> privateKey.algorithm
            is MlKemPrivateKey -> privateKey.algorithm
        }
    ): Named(), Explicit {
        override val publicKey: PublicKey get() = privateKey.publicKey
        override suspend fun sign(message: ByteArray): Signature = sign(this, message)
        override suspend fun keyAgreement(otherKey: EcPublicKey): ByteArray = keyAgreement(this, otherKey)
    }

    /** [AsymmetricKey] which is both [AsymmetricKey.Anonymous] and [AsymmetricKey.Explicit]. */
    data class AnonymousExplicit(
        override val privateKey: PrivateKey,
        override val algorithm: Algorithm = when (privateKey) {
            is EcPrivateKey -> privateKey.curve.defaultSigningAlgorithmFullySpecified
            is RsaPrivateKey -> Algorithm.RS256
            is MlDsaPrivateKey -> privateKey.algorithm
            is MlKemPrivateKey -> privateKey.algorithm
        }
    ): Anonymous(), Explicit {
        override val publicKey: PublicKey get() = privateKey.publicKey
        override suspend fun sign(message: ByteArray): Signature = sign(this, message)
        override suspend fun keyAgreement(otherKey: EcPublicKey): ByteArray = keyAgreement(this, otherKey)
    }

    /** [AsymmetricKey] which is both [AsymmetricKey.X509Certified] and [AsymmetricKey.SecureAreaBased]. */
    data class X509CertifiedSecureAreaBased(
        override val certChain: X509CertChain,
        override val secureArea: SecureArea,
        override val keyInfo: KeyInfo,
        override val unlockReason: Reason = Reason.Unspecified,
        override val algorithm: Algorithm = keyInfo.algorithm
    ): X509Certified(),
        SecureAreaBased {
        override val alias: String = keyInfo.alias
        override val publicKey: PublicKey get() = keyInfo.publicKey
        override suspend fun sign(message: ByteArray): Signature = sign(this, message)
        override suspend fun keyAgreement(otherKey: EcPublicKey): ByteArray = keyAgreement(this, otherKey)
    }

    /** [AsymmetricKey] which is both [AsymmetricKey.Named] and [AsymmetricKey.SecureAreaBased]. */
    data class NamedSecureAreaBased(
        override val keyId: String,
        override val alias: String,
        override val secureArea: SecureArea,
        override val keyInfo: KeyInfo,
        override val unlockReason: Reason = Reason.Unspecified,
        override val algorithm: Algorithm = keyInfo.algorithm
    ): Named(), SecureAreaBased {
        override val publicKey: PublicKey get() = keyInfo.publicKey
        override suspend fun sign(message: ByteArray): Signature = sign(this, message)
        override suspend fun keyAgreement(otherKey: EcPublicKey): ByteArray = keyAgreement(this, otherKey)
    }

    /**
     * [AsymmetricKey] which is both [AsymmetricKey.Anonymous] and [AsymmetricKey.SecureAreaBased].
     */
    class AnonymousSecureAreaBased(
        override val alias: String,
        override val secureArea: SecureArea,
        override val keyInfo: KeyInfo,
        override val unlockReason: Reason = Reason.Unspecified,
        override val algorithm: Algorithm = keyInfo.algorithm
    ): Anonymous(), SecureAreaBased {
        override val publicKey: PublicKey get() = keyInfo.publicKey
        override suspend fun sign(message: ByteArray): Signature = sign(this, message)
        override suspend fun keyAgreement(otherKey: EcPublicKey): ByteArray = keyAgreement(this, otherKey)
    }

    companion object Companion {
        private suspend fun sign(explicit: Explicit, message: ByteArray): Signature =
            Crypto.sign(explicit.privateKey, explicit.algorithm, message)

        private suspend fun sign(
            secureAreaBased: SecureAreaBased,
            message: ByteArray
        ): Signature =
            secureAreaBased.secureArea.sign(
                alias = secureAreaBased.alias,
                dataToSign = message,
                unlockReason = secureAreaBased.unlockReason
            )

        private suspend fun keyAgreement(explicit: Explicit, otherKey: EcPublicKey): ByteArray =
            when (val priv = explicit.privateKey) {
                is EcPrivateKey -> Crypto.keyAgreement(key = priv, otherKey = otherKey).use { it.encoded }
                is RsaPrivateKey -> throw UnsupportedOperationException("RSA keys do not support key agreement")
                is MlDsaPrivateKey -> throw UnsupportedOperationException("ML-DSA keys do not support key agreement")
                is MlKemPrivateKey -> throw UnsupportedOperationException("ML-KEM keys do not support key agreement")
            }

        private suspend fun keyAgreement(
            secureAreaBased: SecureAreaBased,
            otherKey: EcPublicKey
        ): ByteArray =
            secureAreaBased.secureArea.keyAgreement(
                alias = secureAreaBased.alias,
                otherKey = otherKey,
                unlockReason = secureAreaBased.unlockReason
            )

        /**
         * Parses json string that describes the private key.
         *
         * Private key can either be given as JWK object or as a reference to a
         * [SecureArea]-resident key using `secure_area` and `alias` fields. In either case, key
         * identification must be specified using `kid` or `x5c` fields.
         */
        suspend fun parse(
            json: String,
            secureAreaRepository: SecureAreaRepository?,
            unlockReason: Reason = Reason.Unspecified
        ): AsymmetricKey = parse(
            json = Json.parseToJsonElement(json),
            secureAreaRepository = secureAreaRepository,
            unlockReason = unlockReason
        )

        /**
         * Parses json object that describes the private key.
         */
        suspend fun parse(
            json: JsonElement,
            secureAreaRepository: SecureAreaRepository?,
            unlockReason: Reason = Reason.Unspecified
        ): AsymmetricKey {
            if (json !is JsonObject) {
                throw IllegalArgumentException("expected json object")
            }
            val secureArea = json["secure_area"]?.jsonPrimitive?.content?.let {
                secureAreaRepository?.getImplementation(it)
                    ?: throw IllegalStateException(
                        "SecureArea '$it' requested, but SecureAreaRepository is not provided or that secure area is not registered")
            }
            return if (secureArea != null) {
                val (kid, x5c) = parseIdentifier(json)
                val alias = json["alias"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("'alias' is required")
                val keyInfo = secureArea.getKeyInfo(alias)
                if (kid != null) {
                    NamedSecureAreaBased(kid, alias, secureArea, keyInfo, unlockReason)
                } else {
                    X509CertifiedSecureAreaBased(x5c!!, secureArea, keyInfo, unlockReason)
                }
            } else {
                parseExplicit(json)
            }
        }

        /**
         * Parses json string that describes the private key for software-based private key.
         *
         * Similar to [parse], but does not handle [SecureArea]-based keys. It is suitable for
         * calling in non-coroutine contexts.
         */
        fun parseExplicit(json: String): AsymmetricKey =
            parseExplicit(Json.parseToJsonElement(json))

        /**
         * Parses json object that describes the private key for software-based private key.
         *
         * Similar to [parse], but does not handle [SecureArea]-based keys. It is suitable for
         * calling in non-coroutine contexts.
         */
        fun parseExplicit(json: JsonElement): AsymmetricKey {
            if (json !is JsonObject) {
                throw IllegalArgumentException("expected json object")
            }
            val (kid, x5c) = parseIdentifier(json)
            val privateKey = PrivateKey.fromJwk(json)
            if (kid != null) {
                return NamedExplicit(kid, privateKey)
            }
            if (x5c!!.certificates.first().publicKey != privateKey.publicKey) {
                throw IllegalArgumentException("certificate chain does not certify the key")
            }
            return X509CertifiedExplicit(x5c, privateKey)
        }

        suspend fun anonymous(
            secureArea: SecureArea,
            alias: String,
            unlockReason: Reason = Reason.Unspecified,
            algorithm: Algorithm? = null
        ): AsymmetricKey {
            val keyInfo = secureArea.getKeyInfo(alias)
            return AnonymousSecureAreaBased(
                secureArea = secureArea,
                alias = alias,
                keyInfo = keyInfo,
                unlockReason = unlockReason,
                algorithm = algorithm ?: keyInfo.algorithm
            )
        }

        /**
         * Creates an anonymous [AsymmetricKey] wrapping a software [PrivateKey].
         *
         * @param privateKey the private key to wrap.
         * @param algorithm the signing algorithm to use.
         * @return an anonymous [AsymmetricKey].
         */
        fun anonymous(
            privateKey: PrivateKey,
            algorithm: Algorithm = when (privateKey) {
                is EcPrivateKey -> privateKey.curve.defaultSigningAlgorithmFullySpecified
                is RsaPrivateKey -> Algorithm.RS256
                is MlDsaPrivateKey -> privateKey.algorithm
                is MlKemPrivateKey -> privateKey.algorithm
            }
        ): AsymmetricKey = AnonymousExplicit(privateKey, algorithm)

        /**
         * Generates an ephemeral software-based [AsymmetricKey].
         *
         * @param algorithm the signing algorithm to use.
         * @param keySizeBits the RSA key size in bits (only used if [algorithm] is an RSA algorithm).
         * @return an ephemeral anonymous [AsymmetricKey].
         */
        suspend fun ephemeral(
            algorithm: Algorithm = Algorithm.ESP256,
            keySizeBits: Int = 2048
        ): AsymmetricKey =
            when {
                algorithm.keySizeBits != null ->
                    AnonymousExplicit(Crypto.createRsaPrivateKey(algorithm.keySizeBits!!), algorithm)
                algorithm in listOf(
                    Algorithm.RS256, Algorithm.RS384, Algorithm.RS512,
                    Algorithm.PS256, Algorithm.PS384, Algorithm.PS512
                ) ->
                    AnonymousExplicit(Crypto.createRsaPrivateKey(keySizeBits), algorithm)
                algorithm in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87) ->
                    AnonymousExplicit(Crypto.createMlDsaPrivateKey(algorithm), algorithm)
                algorithm in listOf(Algorithm.ML_KEM_512, Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024) ->
                    AnonymousExplicit(Crypto.createMlKemPrivateKey(algorithm), algorithm)
                else ->
                    AnonymousExplicit(Crypto.createEcPrivateKey(algorithm.curve!!), algorithm)
            }

        private fun parseIdentifier(json: JsonObject): Pair<String?, X509CertChain?> {
            val kid = json["kid"]?.jsonPrimitive?.content
            if (kid != null) {
                return Pair(kid, null)
            }
            val x5c = json["x5c"] ?:
                throw IllegalArgumentException("either 'kid' or 'x5c' must be given")
            return Pair(null, X509CertChain.fromX5c(x5c))
        }

        private fun commonName(certChain: X509CertChain): String =
            certChain.certificates.first().subject.components[OID.COMMON_NAME.oid]?.value
                ?: throw IllegalStateException("No common name in certificate's subject")
    }
}