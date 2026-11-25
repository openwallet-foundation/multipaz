package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import org.multipaz.util.appendInt16
import org.multipaz.util.appendInt32
import org.multipaz.util.appendInt64
import org.multipaz.util.appendString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.xor

/**
 * Hybrid Public Key Encryption according to [RFC 9180](https://datatracker.ietf.org/doc/rfc9180/)
 * and `draft-ietf-hpke-pq-05`.
 */
object Hpke {

    /**
     * Key encapsulation mechanisms to use for HPKE.
     *
     * Reference: RFC 9180 Table 2 and `draft-ietf-hpke-pq-05`.
     */
    enum class Kem(
        internal val curve: EcCurve?,
        internal val kemAlgorithm: Algorithm?,
        internal val algId: Int,
        internal val nEnc: Int,
        internal val nPk: Int,
        internal val nSk: Int,
        internal val supportsAuth: Boolean,
    ) {
        /** Key encapsulation mechanism using P-256 EC key agreement and SHA-2 hashing with a 256-bit digest. */
        DHKEM_P256_HKDF_SHA256(
            curve = EcCurve.P256,
            kemAlgorithm = null,
            algId = 0x0010,
            nEnc = 65,
            nPk = 65,
            nSk = 32,
            supportsAuth = true,
        ),

        /** Key encapsulation mechanism using P-384 EC key agreement and SHA-2 hashing with a 384-bit digest. */
        DHKEM_P384_HKDF_SHA384(
            curve = EcCurve.P384,
            kemAlgorithm = null,
            algId = 0x0011,
            nEnc = 97,
            nPk = 97,
            nSk = 48,
            supportsAuth = true,
        ),

        /** Key encapsulation mechanism using P-521 EC key agreement and SHA-2 hashing with a 512-bit digest. */
        DHKEM_P521_HKDF_SHA512(
            curve = EcCurve.P521,
            kemAlgorithm = null,
            algId = 0x0012,
            nEnc = 133,
            nPk = 133,
            nSk = 66,
            supportsAuth = true,
        ),

        /** Key encapsulation mechanism using X25519 EC key agreement and SHA-2 hashing with a 256-bit digest. */
        DHKEM_X25519_HKDF_SHA256(
            curve = EcCurve.X25519,
            kemAlgorithm = null,
            algId = 0x0020,
            nEnc = 32,
            nPk = 32,
            nSk = 32,
            supportsAuth = true,
        ),

        /** Key encapsulation mechanism using X448 EC key agreement and SHA-2 hashing with a 512-bit digest. */
        DHKEM_X448_HKDF_SHA512(
            curve = EcCurve.X448,
            kemAlgorithm = null,
            algId = 0x0021,
            nEnc = 56,
            nPk = 56,
            nSk = 56,
            supportsAuth = true,
        ),

        /** Pure post-quantum KEM using ML-KEM-512 (`draft-ietf-hpke-pq-05`). */
        ML_KEM_512(
            curve = null,
            kemAlgorithm = Algorithm.ML_KEM_512,
            algId = 0x0040,
            nEnc = 768,
            nPk = 800,
            nSk = 64,
            supportsAuth = false,
        ),

        /** Pure post-quantum KEM using ML-KEM-768 (`draft-ietf-hpke-pq-05`). */
        ML_KEM_768(
            curve = null,
            kemAlgorithm = Algorithm.ML_KEM_768,
            algId = 0x0041,
            nEnc = 1088,
            nPk = 1184,
            nSk = 64,
            supportsAuth = false,
        ),

        /** Pure post-quantum KEM using ML-KEM-1024 (`draft-ietf-hpke-pq-05`). */
        ML_KEM_1024(
            curve = null,
            kemAlgorithm = Algorithm.ML_KEM_1024,
            algId = 0x0042,
            nEnc = 1568,
            nPk = 1568,
            nSk = 64,
            supportsAuth = false,
        ),

        /** PQ/T hybrid KEM combining ML-KEM-768 and P-256 (`draft-ietf-hpke-pq-05`). */
        MLKEM768_P256(
            curve = null,
            kemAlgorithm = Algorithm.MLKEM768_P256,
            algId = 0x0050,
            nEnc = 1153,
            nPk = 1249,
            nSk = 32,
            supportsAuth = false,
        ),

        /** PQ/T hybrid KEM combining ML-KEM-1024 and P-384 (`draft-ietf-hpke-pq-05`). */
        MLKEM1024_P384(
            curve = null,
            kemAlgorithm = Algorithm.MLKEM1024_P384,
            algId = 0x0051,
            nEnc = 1665,
            nPk = 1665,
            nSk = 32,
            supportsAuth = false,
        ),

        /** PQ/T hybrid KEM combining ML-KEM-768 and X25519 (`draft-ietf-hpke-pq-05`). */
        MLKEM768_X25519(
            curve = null,
            kemAlgorithm = Algorithm.MLKEM768_X25519,
            algId = 0x647A,
            nEnc = 1120,
            nPk = 1216,
            nSk = 32,
            supportsAuth = false,
        ),
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

        /** ChaCha20 with Poly1305 AEAD */
        CHACHA20_POLY1305(alg = Algorithm.CHACHA20_POLY1305, algId = 0x0003, nk = 32, nn = 12),
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

            /**
             * A cipher suite for HPKE using [Kem.DHKEM_X25519_HKDF_SHA256] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.CHACHA20_POLY1305] as the AEAD.
             */
            val DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305 = CipherSuite(
                kem = Kem.DHKEM_X25519_HKDF_SHA256,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.CHACHA20_POLY1305,
            )

            /**
             * A cipher suite for HPKE using [Kem.DHKEM_P256_HKDF_SHA256] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.CHACHA20_POLY1305] as the AEAD.
             */
            val DHKEM_P256_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305 = CipherSuite(
                kem = Kem.DHKEM_P256_HKDF_SHA256,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.CHACHA20_POLY1305,
            )

            /**
             * A cipher suite for HPKE using [Kem.ML_KEM_512] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_128_GCM] as the AEAD.
             */
            val ML_KEM_512_HKDF_SHA256_AES_128_GCM = CipherSuite(
                kem = Kem.ML_KEM_512,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_128_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.ML_KEM_768] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_128_GCM] as the AEAD.
             */
            val ML_KEM_768_HKDF_SHA256_AES_128_GCM = CipherSuite(
                kem = Kem.ML_KEM_768,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_128_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.ML_KEM_768] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_256_GCM] as the AEAD.
             */
            val ML_KEM_768_HKDF_SHA256_AES_256_GCM = CipherSuite(
                kem = Kem.ML_KEM_768,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_256_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.ML_KEM_768] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.CHACHA20_POLY1305] as the AEAD.
             */
            val ML_KEM_768_HKDF_SHA256_CHACHA20_POLY1305 = CipherSuite(
                kem = Kem.ML_KEM_768,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.CHACHA20_POLY1305,
            )

            /**
             * A cipher suite for HPKE using [Kem.ML_KEM_1024] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_256_GCM] as the AEAD.
             */
            val ML_KEM_1024_HKDF_SHA256_AES_256_GCM = CipherSuite(
                kem = Kem.ML_KEM_1024,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_256_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.MLKEM768_P256] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_128_GCM] as the AEAD.
             */
            val MLKEM768_P256_HKDF_SHA256_AES_128_GCM = CipherSuite(
                kem = Kem.MLKEM768_P256,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_128_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.MLKEM768_P256] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.CHACHA20_POLY1305] as the AEAD.
             */
            val MLKEM768_P256_HKDF_SHA256_CHACHA20_POLY1305 = CipherSuite(
                kem = Kem.MLKEM768_P256,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.CHACHA20_POLY1305,
            )

            /**
             * A cipher suite for HPKE using [Kem.MLKEM1024_P384] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_256_GCM] as the AEAD.
             */
            val MLKEM1024_P384_HKDF_SHA256_AES_256_GCM = CipherSuite(
                kem = Kem.MLKEM1024_P384,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_256_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.MLKEM768_X25519] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_128_GCM] as the AEAD.
             */
            val MLKEM768_X25519_HKDF_SHA256_AES_128_GCM = CipherSuite(
                kem = Kem.MLKEM768_X25519,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_128_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.MLKEM768_X25519] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.AES_256_GCM] as the AEAD.
             */
            val MLKEM768_X25519_HKDF_SHA256_AES_256_GCM = CipherSuite(
                kem = Kem.MLKEM768_X25519,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.AES_256_GCM,
            )

            /**
             * A cipher suite for HPKE using [Kem.MLKEM768_X25519] as the KEM,
             * [Kdf.HKDF_SHA256] as the KDF, and [Aead.CHACHA20_POLY1305] as the AEAD.
             */
            val MLKEM768_X25519_HKDF_SHA256_CHACHA20_POLY1305 = CipherSuite(
                kem = Kem.MLKEM768_X25519,
                kdf = Kdf.HKDF_SHA256,
                aead = Aead.CHACHA20_POLY1305,
            )
        }
    }

    /**
     * Derives a KEM key pair deterministically from [ikm] for pure PQ or PQ/T hybrid KEMs
     * according to `draft-ietf-hpke-pq-05`.
     *
     * @param kem the target post-quantum or PQ/T hybrid [Kem].
     * @param ikm input keying material of at least `Nsk` bytes (`64` for ML-KEM, `32` for hybrid KEMs).
     * @return the derived [PrivateKey] ([MlKemPrivateKey] or [HybridKemPrivateKey]).
     * @throws IllegalArgumentException if [ikm] is too short or invalid for key derivation.
     * @throws UnsupportedOperationException if [kem] does not support deterministic derivation.
     */
    @Throws(
        IllegalArgumentException::class,
        UnsupportedOperationException::class,
        CancellationException::class
    )
    suspend fun deriveKeyPair(kem: Kem, ikm: ByteArray): PrivateKey {
        require(ikm.size >= kem.nSk) {
            "Expected at least ${kem.nSk}-byte ikm for $kem, got ${ikm.size}"
        }
        val kemAlg = kem.kemAlgorithm
            ?: throw UnsupportedOperationException("deriveKeyPair is only supported for PQ and hybrid KEMs")
        val seedLength = when {
            kemAlg.isMlKem -> HybridKem.ML_KEM_SEED_SIZE
            kemAlg.isHybridKem -> HybridKem.SEED_SIZE
            else -> throw UnsupportedOperationException("Unsupported KEM algorithm $kemAlg")
        }
        val seed = labeledDeriveShake256(
            suiteId = getKemSuiteId(kem),
            ikm = ikm,
            label = "DeriveKeyPair",
            context = byteArrayOf(),
            length = seedLength
        )
        return try {
            when {
                kemAlg.isMlKem -> Crypto.createMlKemPrivateKeyFromSeed(kemAlg, seed)
                else -> HybridKem.createPrivateKeyFromSeed(kemAlg, seed)
            }
        } finally {
            seed.secureZero()
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
    private fun getKdfFromKem(kem: Kem): Kdf = when (kem) {
        Kem.DHKEM_P256_HKDF_SHA256,
        Kem.DHKEM_X25519_HKDF_SHA256 -> Kdf.HKDF_SHA256

        Kem.DHKEM_P384_HKDF_SHA384 -> Kdf.HKDF_SHA384

        Kem.DHKEM_P521_HKDF_SHA512,
        Kem.DHKEM_X448_HKDF_SHA512 -> Kdf.HKDF_SHA512

        else -> throw IllegalArgumentException("$kem does not use a DHKEM KDF")
    }

    private fun getKemSuiteId(kem: Kem): ByteArray {
        return buildByteString {
            appendString("KEM")
            appendInt16(kem.algId)
        }.toByteArray()
    }

    private fun getKemSuiteId(cipherSuite: CipherSuite): ByteArray =
        getKemSuiteId(cipherSuite.kem)

    private fun labeledDeriveShake256(
        suiteId: ByteArray,
        ikm: ByteArray,
        label: String,
        context: ByteArray,
        length: Int
    ): ByteArray {
        val labelBytes = label.encodeToByteArray()
        val domainHeader = buildByteString {
            appendString("HPKE-v1")
            append(suiteId)
            appendInt16(labelBytes.size)
            append(labelBytes)
            appendInt16(length)
        }.toByteArray()
        return try {
            Sha3.shake256(ikm, domainHeader, context, outputLength = length)
        } finally {
            domainHeader.secureZero()
        }
    }

    private fun getHpkeSuiteId(cipherSuite: CipherSuite): ByteArray {
        return buildByteString {
            appendString("HPKE")
            appendInt16(cipherSuite.kem.algId)
            appendInt16(cipherSuite.kdf.algId)
            appendInt16(cipherSuite.aead.algId)
        }.toByteArray()
    }

    private suspend fun labeledExtract(
        suiteId: ByteArray,
        kdf: Kdf,
        salt: ByteArray?, // Can be null for empty string salt
        label: String,
        ikm: ByteArray
    ): ByteArray {
        val labelBytes = label.encodeToByteArray()
        val labeledIkm = "HPKE-v1".encodeToByteArray() + suiteId + labelBytes + ikm
        return SecretKey(labeledIkm).use {
            Hkdf.extract(kdf.alg, it, salt).use { prk -> prk.encoded }
        }
    }

    private suspend fun labeledExpand(
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
        return SecretKey(prk).use {
            Hkdf.expand(kdf.alg, it, labeledInfo, length).use { okm -> okm.encoded }
        }
    }

    private suspend fun extractAndExpand(
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
        return try {
            labeledExpand(
                suiteId = suiteId,
                kdf = kdf,
                prk = eaePrk,
                label = "shared_secret",
                info = kemContext,
                length = length
            )
        } finally {
            eaePrk.secureZero()
        }
    }

    internal data class HpkeContext(
        val cipherSuite: CipherSuite,
        val key: SecretKey,
        val baseNonce: ByteArray,
        val exporterSecret: ByteArray
    )

    private fun resolveMode(
        kem: Kem,
        psk: ByteArray?,
        pskId: ByteArray?,
        authPresent: Boolean
    ): Mode {
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
        if (authPresent) {
            require(kem.supportsAuth) {
                "Auth mode is not supported for KEM $kem"
            }
            return if (psk != null) Mode.AuthPsk else Mode.Auth
        }
        return if (psk != null) Mode.Psk else Mode.Base
    }

    private suspend fun keyScheduleFromSharedSecret(
        mode: Mode,
        cipherSuite: CipherSuite,
        sharedSecret: ByteArray,
        info: ByteArray,
        psk: ByteArray?,
        pskId: ByteArray?
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

        val hpkeSuiteId = getHpkeSuiteId(cipherSuite)
        val pskIdHash = labeledExtract(
            suiteId = hpkeSuiteId,
            kdf = cipherSuite.kdf,
            salt = null,
            label = "psk_id_hash",
            ikm = pskId ?: byteArrayOf()
        )
        val infoHash = labeledExtract(
            suiteId = hpkeSuiteId,
            kdf = cipherSuite.kdf,
            salt = null,
            label = "info_hash",
            ikm = info
        )
        val keyScheduleContext = byteArrayOf(mode.value.toByte()) + pskIdHash + infoHash
        val secret = labeledExtract(
            suiteId = hpkeSuiteId,
            kdf = cipherSuite.kdf,
            salt = sharedSecret,
            label = "secret",
            ikm = psk ?: byteArrayOf()
        )
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = labeledExpand(
                suiteId = hpkeSuiteId,
                kdf = cipherSuite.kdf,
                prk = secret,
                label = "key",
                info = keyScheduleContext,
                length = cipherSuite.aead.nk
            )
            val key = SecretKey(keyBytes)
            val baseNonce = labeledExpand(
                suiteId = hpkeSuiteId,
                kdf = cipherSuite.kdf,
                prk = secret,
                label = "base_nonce",
                info = keyScheduleContext,
                length = cipherSuite.aead.nn
            )
            val exporterSecret = labeledExpand(
                suiteId = hpkeSuiteId,
                kdf = cipherSuite.kdf,
                prk = secret,
                label = "exp",
                info = keyScheduleContext,
                length = cipherSuite.kdf.nh
            )
            HpkeContext(
                cipherSuite = cipherSuite,
                key = key,
                baseNonce = baseNonce,
                exporterSecret = exporterSecret,
            )
        } finally {
            keyBytes?.secureZero()
            secret.secureZero()
        }
    }

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
        val kdf = getKdfFromKem(cipherSuite.kem)

        val sharedSecret = if (authKey != null) {
            val dhSum = authKey.keyAgreement(receiverKeyPub!!).use { dh + it.encoded }
            try {
                extractAndExpand(
                    suiteId = getKemSuiteId(cipherSuite),
                    kdf = kdf,
                    dh = dhSum,
                    kemContext = kemContext + authKey.ecPublicKey.serialize(),
                    length = kdf.nh
                )
            } finally {
                dhSum.secureZero()
            }
        } else if (authKeyPub != null) {
            val dhSum = receiverKey!!.keyAgreement(authKeyPub).use { dh + it.encoded }
            try {
                extractAndExpand(
                    suiteId = getKemSuiteId(cipherSuite),
                    kdf = kdf,
                    dh = dhSum,
                    kemContext = kemContext + authKeyPub.serialize(),
                    length = kdf.nh
                )
            } finally {
                dhSum.secureZero()
            }
        } else {
            extractAndExpand(
                suiteId = getKemSuiteId(cipherSuite),
                kdf = kdf,
                dh = dh,
                kemContext = kemContext,
                length = kdf.nh
            )
        }
        return try {
            keyScheduleFromSharedSecret(
                mode = mode,
                cipherSuite = cipherSuite,
                sharedSecret = sharedSecret,
                info = info,
                psk = psk,
                pskId = pskId,
            )
        } finally {
            sharedSecret.secureZero()
        }
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
    ) : AutoCloseable {
        internal var seq: Long = 0

        override fun close() {
            hpkeContext.key.close()
            hpkeContext.baseNonce.secureZero()
            hpkeContext.exporterSecret.secureZero()
        }

        /**
         * Encrypts a message to the receiver.
         *
         * @param plaintext the message to encrypt.
         * @param aad additional authenticated data.
         * @return the encrypted message, including the authentication tag at the end.
         */
        suspend fun encrypt(
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
        suspend fun exportSecret(
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
     * Creates an [Encrypter] object for HPKE for the given [cipherSuite].
     *
     * @param cipherSuite the cipher suite to use, for example [Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM].
     * @param receiverPublicKey the public key to encrypt the data against, must be compatible with [cipherSuite].
     * @param info data which can be used to influence the generation of keys (e.g., to fold in identity information).
     * @param psk pre-shared key to use or `null`.
     * @param pskId the identifier for the optional pre-shared key, must be non-`null` exactly when [psk] is non-`null`.
     * @param authKey optional authentication key or `null` (only supported for DHKEM cipher suites).
     * @return an [Encrypter] object which can be used to encrypt messages to the receiver.
     * @throws IllegalArgumentException if [receiverPublicKey], [psk]/[pskId], or [authKey] are invalid for [cipherSuite].
     */
    @Throws(
        IllegalArgumentException::class,
        CancellationException::class
    )
    suspend fun getEncrypter(
        cipherSuite: CipherSuite,
        receiverPublicKey: PublicKey,
        info: ByteArray,
        psk: ByteArray? = null,
        pskId: ByteArray? = null,
        authKey: AsymmetricKey? = null
    ): Encrypter {
        val mode = resolveMode(cipherSuite.kem, psk, pskId, authPresent = authKey != null)
        val curve = cipherSuite.kem.curve
        if (curve != null) {
            require(receiverPublicKey is EcPublicKey) {
                "Expected EcPublicKey for ${cipherSuite.kem}, got ${receiverPublicKey::class.simpleName}"
            }
            require(receiverPublicKey.curve == curve) {
                "Receiver public key curve ${receiverPublicKey.curve} does not match $curve"
            }
            return Crypto.createEcPrivateKey(curve).use { encapsulatedPublicKey ->
                getEncrypterInternal(
                    cipherSuite = cipherSuite,
                    receiverPublicKey = receiverPublicKey,
                    info = info,
                    encapsulatedKey = encapsulatedPublicKey,
                    psk = psk,
                    pskId = pskId,
                    authKey = authKey
                )
            }
        }
        val kemAlgorithm = cipherSuite.kem.kemAlgorithm!!
        return when {
            kemAlgorithm.isMlKem -> {
                require(receiverPublicKey is MlKemPublicKey) {
                    "Expected MlKemPublicKey for ${cipherSuite.kem}, got ${receiverPublicKey::class.simpleName}"
                }
                require(receiverPublicKey.algorithm == kemAlgorithm) {
                    "Receiver public key algorithm ${receiverPublicKey.algorithm} does not match $kemAlgorithm"
                }
                Crypto.kemEncapsulate(receiverPublicKey).use { kemResult ->
                    encrypterFromKemResult(mode, cipherSuite, kemResult, info, psk, pskId)
                }
            }
            kemAlgorithm.isHybridKem -> {
                require(receiverPublicKey is HybridKemPublicKey) {
                    "Expected HybridKemPublicKey for ${cipherSuite.kem}, got ${receiverPublicKey::class.simpleName}"
                }
                require(receiverPublicKey.algorithm == kemAlgorithm) {
                    "Receiver public key algorithm ${receiverPublicKey.algorithm} does not match $kemAlgorithm"
                }
                Crypto.kemEncapsulate(receiverPublicKey).use { kemResult ->
                    encrypterFromKemResult(mode, cipherSuite, kemResult, info, psk, pskId)
                }
            }
            else -> throw IllegalArgumentException("Unsupported KEM ${cipherSuite.kem}")
        }
    }

    internal suspend fun getEncrypterDerand(
        cipherSuite: CipherSuite,
        receiverPublicKey: PublicKey,
        info: ByteArray,
        ikmE: ByteArray,
        psk: ByteArray? = null,
        pskId: ByteArray? = null,
    ): Encrypter {
        val mode = resolveMode(cipherSuite.kem, psk, pskId, authPresent = false)
        val kemAlgorithm = cipherSuite.kem.kemAlgorithm
            ?: throw UnsupportedOperationException("getEncrypterDerand is only supported for PQ and hybrid KEMs")
        return when {
            kemAlgorithm.isMlKem -> {
                require(receiverPublicKey is MlKemPublicKey) {
                    "Expected MlKemPublicKey for ${cipherSuite.kem}, got ${receiverPublicKey::class.simpleName}"
                }
                require(receiverPublicKey.algorithm == kemAlgorithm) {
                    "Receiver public key algorithm ${receiverPublicKey.algorithm} does not match $kemAlgorithm"
                }
                Crypto.kemEncapsulateDerand(receiverPublicKey, ikmE).use { kemResult ->
                    encrypterFromKemResult(mode, cipherSuite, kemResult, info, psk, pskId)
                }
            }
            kemAlgorithm.isHybridKem -> {
                require(receiverPublicKey is HybridKemPublicKey) {
                    "Expected HybridKemPublicKey for ${cipherSuite.kem}, got ${receiverPublicKey::class.simpleName}"
                }
                require(receiverPublicKey.algorithm == kemAlgorithm) {
                    "Receiver public key algorithm ${receiverPublicKey.algorithm} does not match $kemAlgorithm"
                }
                HybridKem.encapsulateDerand(receiverPublicKey, ikmE).use { kemResult ->
                    encrypterFromKemResult(mode, cipherSuite, kemResult, info, psk, pskId)
                }
            }
            else -> throw IllegalArgumentException("Unsupported KEM ${cipherSuite.kem}")
        }
    }

    private suspend fun encrypterFromKemResult(
        mode: Mode,
        cipherSuite: CipherSuite,
        kemResult: KemResult,
        info: ByteArray,
        psk: ByteArray?,
        pskId: ByteArray?,
    ): Encrypter {
        val sharedSecretBytes = kemResult.sharedSecret.encoded
        val context = try {
            keyScheduleFromSharedSecret(
                mode = mode,
                cipherSuite = cipherSuite,
                sharedSecret = sharedSecretBytes,
                info = info,
                psk = psk,
                pskId = pskId,
            )
        } finally {
            sharedSecretBytes.secureZero()
        }
        return Encrypter(
            encapsulatedKey = ByteString(kemResult.ciphertext),
            hpkeContext = context
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
        val dh = Crypto.keyAgreement(encapsulatedKey, receiverPublicKey).use { it.encoded }
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

        val context = try {
            calcContext(
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
        } finally {
            dh.secureZero()
        }

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
    ) : AutoCloseable {
        internal var seq: Long = 0

        override fun close() {
            hpkeContext.key.close()
            hpkeContext.baseNonce.secureZero()
            hpkeContext.exporterSecret.secureZero()
        }

        /**
         * Decrypts an encrypted message from the sender.
         *
         * @param ciphertext the encrypted message to decrypt, including the authentication tag at the end.
         * @param aad additional authenticated data.
         * @return the decrypted message.
         */
        suspend fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
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
        suspend fun exportSecret(
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
     *   be compatible with [cipherSuite] and support key agreement or decapsulation.
     * @param encapsulatedKey the encapsulated key, received from the sender.
     * @param info data which can be used to influence the generation of keys (e.g., to fold in identity information).
     * @param psk pre-shared key to use or `null`.
     * @param pskId the identifier for the optional pre-shared key, must be non-`null` exactly when [psk] is non-`null`.
     * @param authKey optional authentication key or `null` (only supported for DHKEM cipher suites).
     * @return a [Decrypter] object which can be used to decrypt messages from the sender.
     * @throws IllegalArgumentException if inputs or [receiverPrivateKey] are incompatible with [cipherSuite].
     */
    @Throws(
        IllegalArgumentException::class,
        CancellationException::class
    )
    suspend fun getDecrypter(
        cipherSuite: CipherSuite,
        receiverPrivateKey: AsymmetricKey,
        encapsulatedKey: ByteArray,
        info: ByteArray,
        psk: ByteArray? = null,
        pskId: ByteArray? = null,
        authKey: EcPublicKey? = null
    ): Decrypter {
        val mode = resolveMode(cipherSuite.kem, psk, pskId, authPresent = authKey != null)
        require(encapsulatedKey.size == cipherSuite.kem.nEnc) {
            "Expected ${cipherSuite.kem.nEnc}-byte encapsulatedKey for ${cipherSuite.kem}, " +
                "got ${encapsulatedKey.size}"
        }
        val curve = cipherSuite.kem.curve
        if (curve != null) {
            val receiverPub = receiverPrivateKey.publicKey
            require(receiverPub is EcPublicKey && receiverPub.curve == curve) {
                "Receiver private key is not an EC key on curve $curve"
            }
            val encapsulatedPublicKey = EcPublicKey.fromSerialized(
                curve = curve,
                encoded = encapsulatedKey
            )

            val dh = receiverPrivateKey.keyAgreement(encapsulatedPublicKey).use { it.encoded }
            try {
                val kemContext = encapsulatedKey + receiverPub.serialize()
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
            } finally {
                dh.secureZero()
            }
        }

        val kemAlgorithm = cipherSuite.kem.kemAlgorithm!!
        val receiverPub = receiverPrivateKey.publicKey
        when {
            kemAlgorithm.isMlKem -> {
                require(receiverPub is MlKemPublicKey && receiverPub.algorithm == kemAlgorithm) {
                    "Receiver private key does not match $kemAlgorithm"
                }
            }
            kemAlgorithm.isHybridKem -> {
                require(receiverPub is HybridKemPublicKey && receiverPub.algorithm == kemAlgorithm) {
                    "Receiver private key does not match $kemAlgorithm"
                }
            }
            else -> throw IllegalArgumentException("Unsupported KEM ${cipherSuite.kem}")
        }

        return receiverPrivateKey.kemDecapsulate(encapsulatedKey).use { sharedSecret ->
            val sharedSecretBytes = sharedSecret.encoded
            val context = try {
                keyScheduleFromSharedSecret(
                    mode = mode,
                    cipherSuite = cipherSuite,
                    sharedSecret = sharedSecretBytes,
                    info = info,
                    psk = psk,
                    pskId = pskId,
                )
            } finally {
                sharedSecretBytes.secureZero()
            }
            Decrypter(hpkeContext = context)
        }
    }
}