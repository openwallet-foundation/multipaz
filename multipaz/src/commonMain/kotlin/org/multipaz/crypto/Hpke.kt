package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import org.multipaz.util.appendInt16
import org.multipaz.util.appendInt32
import org.multipaz.util.appendInt64
import org.multipaz.util.appendString
import kotlin.experimental.xor

/**
 * Hybrid Public Key Encryption according to [RFC 9180](https://datatracker.ietf.org/doc/rfc9180/).
 */
object Hpke {

    /**
     * Key encapsulation mechanisms to use for HPKE.
     *
     * Reference: RFC 9180 Table 2.
     */
    enum class Kem(
        internal val curve: EcCurve,
        internal val algId: Int     // According to RFC 9180 Table 2
    ) {
        /** Key encapsulation mechanism using P-256 EC key agreement and SHA-2 hashing with a 256-bit digest. */
        DHKEM_P256_HKDF_SHA256(curve = EcCurve.P256, algId = 0x0010),

        /** Key encapsulation mechanism using P-384 EC key agreement and SHA-2 hashing with a 384-bit digest. */
        DHKEM_P384_HKDF_SHA384(curve = EcCurve.P384, algId = 0x0011),

        /** Key encapsulation mechanism using P-521 EC key agreement and SHA-2 hashing with a 512-bit digest. */
        DHKEM_P521_HKDF_SHA512(curve = EcCurve.P521, algId = 0x0012),

        /** Key encapsulation mechanism using X25519 EC key agreement and SHA-2 hashing with a 256-bit digest. */
        DHKEM_X25519_HKDF_SHA256(curve = EcCurve.X25519, algId = 0x0020),

        /** Key encapsulation mechanism using X448 EC key agreement and SHA-2 hashing with a 512-bit digest. */
        DHKEM_X448_HKDF_SHA512(curve = EcCurve.X448, algId = 0x0021)
    }

    /**
     * Key-derivation functions to use for HPKE.
     *
     * Reference: RFC 9180 Table 3.
     */
    enum class Kdf(
        internal val alg: Algorithm,
        internal val algId: Int,       // According to RFC 9180 Table 3
        internal val nh: Int,          // KDF hash output size in bytes
    ) {
        /** HMAC-based key derivation function that uses SHA-2 hashing with a 256-bit digest. */
        HKDF_SHA256(alg = Algorithm.HMAC_SHA256, algId = 0x0001, nh = 32),

        /** HMAC-based key derivation function that uses SHA-2 hashing with a 256-bit digest. */
        HKDF_SHA384(alg = Algorithm.HMAC_SHA384, algId = 0x0002, nh = 48),

        /** HMAC-based key derivation function that uses SHA-2 hashing with a 256-bit digest. */
        HKDF_SHA512(alg = Algorithm.HMAC_SHA512, algId = 0x0003, nh = 64)
    }

    /**
     * AEAD (Authenticated encryption with authenticated data) algorithms to use for HPKE.
     *
     * Reference: RFC 9180 Table 5.
     */
    enum class Aead(
        internal val alg: Algorithm,
        internal val algId: Int,      // According to RFC 9180 Table 5
        internal val nk: Int,         // key size in bytes
        internal val nn: Int,         // nonce size in bytes
    ) {
        /**
         * A mode where HPKE is used only for generating secrets which the sender and received can
         * obtain via [Encrypter.exportSecret] and [Decrypter.exportSecret].
         */
        EXPORT_ONLY(alg = Algorithm.UNSET, algId = 0xffff, nk = 0, nn = 0),

        /** AES in Galois Counter Mode with 128-bit keys */
        AES_128_GCM(alg = Algorithm.A128GCM, algId = 0x0001, nk = 16, nn = 12),

        /** AES in Galois Counter Mode with 256-bit keys */
        AES_256_GCM(alg = Algorithm.A256GCM, algId = 0x0002, nk = 32, nn = 12),
    }

    /**
     * Cipher suite to use for HPKE.
     *
     * Also see common combinations in [Hpke.CipherSuite.Companion] such as
     * [DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM].
     *
     * @property kem the key encapsulation mechanisms to use.
     * @property kdf the key-derivation functions to use.
     * @property aead the AEAD algorithm to use.
     */
    data class CipherSuite(
        val kem: Kem,
        val kdf: Kdf,
        val aead: Aead
    ) {
        companion object {
            /**
             * A cipher suite for HPKE using [Kem.DHKEM_X25519_HKDF_SHA256] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_128_GCM] as the AEAD.
             */
            val DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM = CipherSuite(
                kem = Kem.DHKEM_X25519_HKDF_SHA256,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_128_GCM
            )

            /**
             * A cipher suite for HPKE using [Kem.DHKEM_P256_HKDF_SHA256] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_128_GCM] as the AEAD.
             */
            val DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM = CipherSuite(
                kem = Kem.DHKEM_P256_HKDF_SHA256,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_128_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.DHKEM_P521_HKDF_SHA512] as the KEM,
             * [Kdf.HKDF_SHA512] as the KDF, and [Aead.AES_256_GCM] as the AEAD.
             */
            val DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_256_GCM = CipherSuite(
                kem = Kem.DHKEM_P521_HKDF_SHA512,
                kdf = Kdf.HKDF_SHA512,
                aead = Aead.AES_256_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.DHKEM_P256_HKDF_SHA256] as the KEM,
             * [Kdf.HKDF_SHA512] as the KDF, and [Aead.AES_128_GCM] as the AEAD.
             */
            val DHKEM_P256_HKDF_SHA256_HKDF_SHA512_AES_128_GCM = CipherSuite(
                kem = Kem.DHKEM_P256_HKDF_SHA256,
                kdf = Kdf.HKDF_SHA512,
                aead = Aead.AES_128_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.DHKEM_X25519_HKDF_SHA256] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.EXPORT_ONLY] as the AEAD.
             */
            val DHKEM_X25519_HKDF_SHA256_EXPORT_ONLY = CipherSuite(
                kem = Kem.DHKEM_X25519_HKDF_SHA256,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.EXPORT_ONLY,
            )
        }
    }

    /**
     * Returns the KDF algorithm associated with the given KEM
     * This is distinct from the KDF defined in the overall HPKE cipher suite
     *
     * According to [RCF 9180 Section 4.1](https://www.rfc-editor.org/rfc/rfc9180.html#name-dh-based-kem-dhkem)
     * Each KEM defines a single KDF that must be used with that KEM.
     * When performing the DH-based key agreement in the KEM layer, we must use the KDF mandated by the KEM
     *
     */
    private fun getKdfFromKem(kem: Kem): Kdf = when(kem) {
        Kem.DHKEM_P256_HKDF_SHA256,
        Kem.DHKEM_X25519_HKDF_SHA256 -> Kdf.HKDF_SHA256

        Kem.DHKEM_P384_HKDF_SHA384 -> Kdf.HKDF_SHA384

        Kem.DHKEM_P521_HKDF_SHA512,
        Kem.DHKEM_X448_HKDF_SHA512 -> Kdf.HKDF_SHA512
    }

    private fun getKemSuiteId(cipherSuite: CipherSuite): ByteArray {
        return buildByteString {
            appendString("KEM")
            appendInt16(cipherSuite.kem.algId)
        }.toByteArray()
    }

    private fun getHpkeSuiteId(cipherSuite: CipherSuite): ByteArray {
        return buildByteString {
            appendString("HPKE")
            appendInt16(cipherSuite.kem.algId)
            appendInt16(cipherSuite.kdf.algId)
            appendInt16(cipherSuite.aead.algId)
        }.toByteArray()
    }

    private fun labeledExtract(
        suiteId: ByteArray,
        kdf: Kdf,
        salt: ByteArray?, // Can be null for empty string salt
        label: String,
        ikm: ByteArray
    ): ByteArray {
        val labelBytes = label.encodeToByteArray()
        val labeledIkm = "HPKE-v1".encodeToByteArray() + suiteId + labelBytes + ikm
        return Hkdf.extract(kdf.alg, labeledIkm, salt)
    }

    private fun labeledExpand(
        suiteId: ByteArray,
        kdf: Kdf,
        prk: ByteArray,
        label: String,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val labelBytes = label.encodeToByteArray()
        val lengthBytes = buildByteString { appendInt16(length) }.toByteArray()
        val labeledInfo = lengthBytes + "HPKE-v1".encodeToByteArray() + suiteId + labelBytes + info
        return Hkdf.expand(kdf.alg, prk, labeledInfo, length)
    }

    private fun extractAndExpand(
        suiteId: ByteArray,
        kdf: Kdf,
        dh: ByteArray,
        kemContext: ByteArray,
        length: Int
    ): ByteArray {
        val eaePrk = labeledExtract(
            suiteId = suiteId,
            kdf = kdf,
            salt = null,
            label = "eae_prk",
            ikm = dh
        )
        val sharedSecret = labeledExpand(
            suiteId = suiteId,
            kdf = kdf,
            prk = eaePrk,
            label = "shared_secret",
            info = kemContext,
            length = length
        )
        return sharedSecret
    }

    internal data class HpkeContext(
        val cipherSuite: CipherSuite,
        val key: ByteArray,
        val baseNonce: ByteArray,
        val exporterSecret: ByteArray
    )

    private suspend fun calcContext(
        mode: Mode,
        cipherSuite: CipherSuite,
        dh: ByteArray,
        info: ByteArray,
        kemContext: ByteArray,
        receiverKey: AsymmetricKey?,
        receiverKeyPub: EcPublicKey?,
        psk: ByteArray?,
        pskId: ByteArray?,
        authKey: AsymmetricKey?,
        authKeyPub: EcPublicKey?
    ): HpkeContext {
        if (psk != null) {
            require(pskId != null) {
                "psk is non-null but pskId is null"
            }
        }
        if (pskId != null) {
            require(psk != null) {
                "pskId is non-null but psk is null"
            }
        }

        val kdf = getKdfFromKem(cipherSuite.kem)

        val sharedSecret = if (authKey != null) {
            val dhSum = dh + authKey.keyAgreement(receiverKeyPub!!)
            extractAndExpand(
                suiteId = getKemSuiteId(cipherSuite),
                kdf = kdf,
                dh = dhSum,
                kemContext = kemContext + authKey.publicKey.serialize(),
                length = kdf.nh
            )
        } else if (authKeyPub != null) {
            val dhSum = dh + receiverKey!!.keyAgreement(authKeyPub)
            extractAndExpand(
                suiteId = getKemSuiteId(cipherSuite),
                kdf = kdf,
                dh = dhSum,
                kemContext = kemContext + authKeyPub.serialize(),
                length = kdf.nh
            )
        } else {
            extractAndExpand(
                suiteId = getKemSuiteId(cipherSuite),
                kdf = kdf,
                dh = dh,
                kemContext = kemContext,
                length = kdf.nh
            )
        }
        //println("sharedSecret = ${sharedSecret.toHex()}")

        val pskIdHash = labeledExtract(
            suiteId = getHpkeSuiteId(cipherSuite),
            kdf = cipherSuite.kdf,
            salt = null,
            label = "psk_id_hash",
            ikm = pskId ?: byteArrayOf()
        )

        val infoHash = labeledExtract(
            suiteId = getHpkeSuiteId(cipherSuite),
            kdf = cipherSuite.kdf,
            salt = null,
            label = "info_hash",
            ikm = info
        )

        val keyScheduleContext = byteArrayOf(mode.value.toByte()) + pskIdHash + infoHash
        //println("keyScheduleContext: ${keyScheduleContext.toHex()}")

        val secret = labeledExtract(
            suiteId = getHpkeSuiteId(cipherSuite),
            kdf = cipherSuite.kdf,
            salt = sharedSecret,
            label = "secret",
            ikm = psk ?: byteArrayOf()
        )
        //println("secret: ${secret.toHex()}")

        val key = labeledExpand(
            suiteId = getHpkeSuiteId(cipherSuite),
            kdf = cipherSuite.kdf,
            prk = secret,
            label = "key",
            info = keyScheduleContext,
            length = cipherSuite.aead.nk
        )
        //println("key: ${key.toHex()}")

        val baseNonce = labeledExpand(
            suiteId = getHpkeSuiteId(cipherSuite),
            kdf = cipherSuite.kdf,
            prk = secret,
            label = "base_nonce",
            info = keyScheduleContext,
            length = cipherSuite.aead.nn
        )
        //println("baseNonce: ${baseNonce.toHex()}")

        val exporterSecret = labeledExpand(
            suiteId = getHpkeSuiteId(cipherSuite),
            kdf = cipherSuite.kdf,
            prk = secret,
            label = "exp",
            info = keyScheduleContext,
            length = cipherSuite.kdf.nh
        )
        //println("exporterSecret: ${exporterSecret.toHex()}")

        return HpkeContext(
            cipherSuite = cipherSuite,
            key = key,
            baseNonce = baseNonce,
            exporterSecret = exporterSecret,
        )
    }

    /**
     * An object which can be used for HPKE encryption.
     *
     * Use [getEncrypter] to create an instance.
     *
     * This can be used for either single-shot operation or for encrypting multiple messages.
     * When used to encrypt multiple messages the sequence counter is automatically managed
     * by this object.
     *
     * @property encapsulatedKey the encapsulated key which should be sent to the receiver.
     */
    @ConsistentCopyVisibility
    data class Encrypter internal constructor(
        val encapsulatedKey: ByteString,
        private val hpkeContext: HpkeContext
    ) {
        internal var seq: Long = 0

        /**
         * Encrypts a message to the receiver.
         *
         * @param plaintext the message to encrypt.
         * @param aad additional authenticated data.
         * @return the encrypted message, including the authentication tag at the end.
         */
        fun encrypt(
            plaintext: ByteArray,
            aad: ByteArray
        ): ByteArray {
            require(hpkeContext.cipherSuite.aead != Aead.EXPORT_ONLY) {
                "Cipher suite is configured for Aead.EXPORT_ONLY"
            }

            val encodedSeq = buildByteString {
                appendInt32(0)
                appendInt64(seq)
            }.toByteArray()
            require(hpkeContext.baseNonce.size == 12)
            val effectiveNonce = encodedSeq.mapIndexed { n, value -> value xor hpkeContext.baseNonce[n] }.toByteArray()

            val ciphertext = Crypto.encrypt(
                algorithm = hpkeContext.cipherSuite.aead.alg,
                key = hpkeContext.key,
                nonce = effectiveNonce,
                messagePlaintext = plaintext,
                aad = aad
            )

            seq += 1
            return ciphertext
        }

        /**
         * Exports a secret.
         *
         * This generates a secret according to Section 5.3 of RFC 9180.
         *
         * @param context domain-specific context.
         * @param length length of the secret to generate.
         */
        fun exportSecret(
            context: ByteArray,
            length: Int,
        ): ByteArray {
            return labeledExpand(
                suiteId = getHpkeSuiteId(hpkeContext.cipherSuite),
                kdf = hpkeContext.cipherSuite.kdf,
                prk = hpkeContext.exporterSecret,
                label = "sec",
                info = context,
                length = length
            )
        }
    }

    internal enum class Mode(
        val value: Int,  // According to RFC 9180 Table 1
    ) {
        Base(0x00),
        Psk(0x01),
        Auth(0x02),
        AuthPsk(0x03),
    }

    /**
     * Creates a [Encrypter] object for HPKE in for the given [cipherSuite].
     *
     * @param cipherSuite the cipher suite to use, for example [Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM].
     * @param receiverPublicKey the public key to encrypt the data against, must be compatible with [cipherSuite].
     * @param info data which can be used to influence the generation of keys (e.g., to fold in identity information).
     * @param psk pre-shared key to use or `null`.
     * @param pskId the identifier for the optional pre-shared key, must be non-`null` exactly when [psk] is non-`null`.
     * @param authKey optional authentication key or `null`.
     * @return a [Encrypter] object which can be used to encrypt messages to the receiver.
     */
    suspend fun getEncrypter(
        cipherSuite: CipherSuite,
        receiverPublicKey: EcPublicKey,
        info: ByteArray,
        psk: ByteArray? = null,
        pskId: ByteArray? = null,
        authKey: AsymmetricKey? = null
    ): Encrypter {
        val encapsulatedPublicKey = Crypto.createEcPrivateKey(cipherSuite.kem.curve)
        return getEncrypterInternal(
            cipherSuite = cipherSuite,
            receiverPublicKey = receiverPublicKey,
            info = info,
            encapsulatedKey = encapsulatedPublicKey,
            psk = psk,
            pskId = pskId,
            authKey = authKey
        )
    }

    internal fun EcPublicKey.serialize(): ByteArray {
        return when (curve) {
            EcCurve.X448,
            EcCurve.X25519 -> {
                this as EcPublicKeyOkp
                x
            }
            else -> {
                this as EcPublicKeyDoubleCoordinate
                asUncompressedPointEncoding
            }
        }
    }

    internal fun EcPublicKey.Companion.fromSerialized(
        curve: EcCurve,
        encoded: ByteArray
    ): EcPublicKey {
        return when (curve) {
            EcCurve.X448,
            EcCurve.X25519 -> {
                EcPublicKeyOkp(curve = curve, x = encoded)
            }
            else -> {
                EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(curve, encoded)
            }
        }
    }

    internal suspend fun getEncrypterInternal(
        cipherSuite: CipherSuite,
        receiverPublicKey: EcPublicKey,
        info: ByteArray,
        encapsulatedKey: EcPrivateKey,
        psk: ByteArray?,
        pskId: ByteArray?,
        authKey: AsymmetricKey?
    ): Encrypter {
        val dh = Crypto.keyAgreement(encapsulatedKey, receiverPublicKey)
        val enc = encapsulatedKey.publicKey.serialize()
        val kemContext = enc + receiverPublicKey.serialize()

        val mode = if (authKey != null) {
            if (psk != null) {
                Mode.AuthPsk
            } else {
                Mode.Auth
            }
        } else {
            if (psk != null) {
                Mode.Psk
            } else {
                Mode.Base
            }
        }

        val context = calcContext(
            mode = mode,
            cipherSuite = cipherSuite,
            dh = dh,
            info = info,
            kemContext = kemContext,
            receiverKey = null,
            receiverKeyPub = receiverPublicKey,
            psk = psk,
            pskId = pskId,
            authKey = authKey,
            authKeyPub = null,
        )

        return Encrypter(
            encapsulatedKey = ByteString(enc),
            hpkeContext = context
        )
    }

    /**
     * An object which can be used for HPKE decryption.
     *
     * Use [getDecrypter] to create an instance.
     *
     * This can be used for either single-shot operation or for decrypting multiple messages.
     * When used to decrypt multiple messages the sequence counter is automatically managed
     * by this object.
     */
    @ConsistentCopyVisibility
    data class Decrypter internal constructor(
        private val hpkeContext: HpkeContext
    ) {
        internal var seq: Long = 0

        /**
         * Decrypts an encrypted message from the sender.
         *
         * @param ciphertext the encrypted message to decrypt, including the authentication tag at the end.
         * @param aad additional authenticated data.
         * @return the decrypted message.
         */
        fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
            require(hpkeContext.cipherSuite.aead != Aead.EXPORT_ONLY) {
                "Cipher suite is configured for Aead.EXPORT_ONLY"
            }

            val encodedSeq = buildByteString {
                appendInt32(0)
                appendInt64(seq)
            }.toByteArray()
            require(hpkeContext.baseNonce.size == 12)
            val effectiveNonce = encodedSeq.mapIndexed { n, value -> value xor hpkeContext.baseNonce[n] }.toByteArray()

            val plaintext = Crypto.decrypt(
                algorithm = hpkeContext.cipherSuite.aead.alg,
                key = hpkeContext.key,
                nonce = effectiveNonce,
                messageCiphertext = ciphertext,
                aad = aad
            )

            seq += 1
            return plaintext
        }

        /**
         * Exports a secret.
         *
         * This generates a secret according to Section 5.3 of RFC 9180.
         *
         * @param context domain-specific context.
         * @param length length of the secret to generate.
         */
        fun exportSecret(
            context: ByteArray,
            length: Int,
        ): ByteArray {
            return labeledExpand(
                suiteId = getHpkeSuiteId(hpkeContext.cipherSuite),
                kdf = hpkeContext.cipherSuite.kdf,
                prk = hpkeContext.exporterSecret,
                label = "sec",
                info = context,
                length = length
            )
        }
    }

    /**
     * Creates a [Decrypter] object for HPKE for the given [cipherSuite].
     *
     * @param cipherSuite the cipher suite to use, for example [Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM].
     * @param receiverPrivateKey an [AsymmetricKey] for the key the data is encrypted against, must
     *   be compatible with the curve in [cipherSuite] and must support key agreement.
     * @param encapsulatedKey the encapsulated key, received from the sender.
     * @param info data which can be used to influence the generation of keys (e.g., to fold in identity information).
     * @param psk pre-shared key to use or `null`.
     * @param pskId the identifier for the optional pre-shared key, must be non-`null` exactly when [psk] is non-`null`.
     * @param authKey optional authentication key or `null`.
     * @return a [Decrypter] object which can be used to decrypt messages from the sender.
     */
    suspend fun getDecrypter(
        cipherSuite: CipherSuite,
        receiverPrivateKey: AsymmetricKey,
        encapsulatedKey: ByteArray,
        info: ByteArray,
        psk: ByteArray? = null,
        pskId: ByteArray? = null,
        authKey: EcPublicKey? = null
    ): Decrypter {
        val encapsulatedPublicKey = EcPublicKey.fromSerialized(
            curve = cipherSuite.kem.curve,
            encoded = encapsulatedKey
        )

        val mode = if (authKey != null) {
            if (psk != null) {
                Mode.AuthPsk
            } else {
                Mode.Auth
            }
        } else {
            if (psk != null) {
                Mode.Psk
            } else {
                Mode.Base
            }
        }

        val dh = receiverPrivateKey.keyAgreement(encapsulatedPublicKey)
        val kemContext = encapsulatedKey + receiverPrivateKey.publicKey.serialize()
        val context = calcContext(
            mode = mode,
            cipherSuite = cipherSuite,
            dh = dh,
            info = info,
            kemContext = kemContext,
            receiverKey = receiverPrivateKey,
            receiverKeyPub = null,
            psk = psk,
            pskId = pskId,
            authKey = null,
            authKeyPub = authKey,
        )

        return Decrypter(
            hpkeContext = context
        )
    }
}