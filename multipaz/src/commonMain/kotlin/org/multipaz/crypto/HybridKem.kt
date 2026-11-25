package org.multipaz.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.util.fromHex

/**
 * Combiner, wire-format codec, and cryptographic helpers for PQ/T hybrid KEMs
 * (`draft-irtf-cfrg-concrete-hybrid-kems-04` and `draft-ietf-hpke-pq-05` §4).
 */
internal object HybridKem {

    internal const val SEED_SIZE = 32
    internal const val ML_KEM_SEED_SIZE = 64
    internal const val ML_KEM_ENCAPS_RANDOMNESS_SIZE = 32
    internal const val SHARED_SECRET_SIZE = 32

    private val P256_ORDER =
        "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551".fromHex()
    private val P384_ORDER =
        "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52973".fromHex()

    internal data class HybridKemSpec(
        val algorithm: Algorithm,
        val pqAlgorithm: Algorithm,
        val pqPublicKeySize: Int,
        val pqCiphertextSize: Int,
        val traditionalCurve: EcCurve,
        val traditionalElementSize: Int,
        val traditionalScalarSize: Int,
        val traditionalSeedSize: Int,
        val labelBytes: ByteArray,
        val hpkeKemId: Int,
    ) {
        val totalPublicKeySize: Int = pqPublicKeySize + traditionalElementSize
        val totalCiphertextSize: Int = pqCiphertextSize + traditionalElementSize
    }

    internal val specs: List<HybridKemSpec> = listOf(
        HybridKemSpec(
            algorithm = Algorithm.MLKEM768_P256,
            pqAlgorithm = Algorithm.ML_KEM_768,
            pqPublicKeySize = 1184,
            pqCiphertextSize = 1088,
            traditionalCurve = EcCurve.P256,
            traditionalElementSize = 65,
            traditionalScalarSize = 32,
            traditionalSeedSize = 128,
            labelBytes = "MLKEM768-P256".encodeToByteArray(),
            hpkeKemId = 0x0050,
        ),
        HybridKemSpec(
            algorithm = Algorithm.MLKEM1024_P384,
            pqAlgorithm = Algorithm.ML_KEM_1024,
            pqPublicKeySize = 1568,
            pqCiphertextSize = 1568,
            traditionalCurve = EcCurve.P384,
            traditionalElementSize = 97,
            traditionalScalarSize = 48,
            traditionalSeedSize = 48,
            labelBytes = "MLKEM1024-P384".encodeToByteArray(),
            hpkeKemId = 0x0051,
        ),
        HybridKemSpec(
            algorithm = Algorithm.MLKEM768_X25519,
            pqAlgorithm = Algorithm.ML_KEM_768,
            pqPublicKeySize = 1184,
            pqCiphertextSize = 1088,
            traditionalCurve = EcCurve.X25519,
            traditionalElementSize = 32,
            traditionalScalarSize = 32,
            traditionalSeedSize = 32,
            // "\.//^\" (hex: 5c2e2f2f5e5c)
            labelBytes = byteArrayOf(0x5c, 0x2e, 0x2f, 0x2f, 0x5e, 0x5c),
            hpkeKemId = 0x647A,
        ),
    )

    private val byAlgorithm: Map<Algorithm, HybridKemSpec> = specs.associateBy { it.algorithm }
    private val byPublicKeySize: Map<Int, HybridKemSpec> = specs.associateBy { it.totalPublicKeySize }

    fun specFor(algorithm: Algorithm): HybridKemSpec =
        byAlgorithm[algorithm]
            ?: throw IllegalArgumentException("Unsupported hybrid KEM algorithm: $algorithm")

    fun specForPublicKeySizeOrNull(size: Int): HybridKemSpec? = byPublicKeySize[size]

    fun supportedAlgorithmsFor(
        supportedMlKem: Set<Algorithm>,
        supportedCurves: Set<EcCurve>
    ): Set<Algorithm> = specs
        .filter { it.pqAlgorithm in supportedMlKem && it.traditionalCurve in supportedCurves }
        .map { it.algorithm }
        .toSet()

    fun serializeTraditionalPublicKey(publicKey: EcPublicKey): ByteArray =
        when (publicKey) {
            is EcPublicKeyOkp -> publicKey.x
            is EcPublicKeyDoubleCoordinate -> publicKey.asUncompressedPointEncoding
        }

    fun deserializeTraditionalPublicKey(curve: EcCurve, encoded: ByteArray): EcPublicKey {
        return when (curve) {
            EcCurve.X25519 -> {
                require(encoded.size == 32) {
                    "Expected 32 bytes for X25519 public key, got ${encoded.size}"
                }
                EcPublicKeyOkp(EcCurve.X25519, encoded)
            }
            EcCurve.P256, EcCurve.P384 -> {
                val coordSize = (curve.bitSize + 7) / 8
                val expectedSize = 1 + 2 * coordSize
                require(encoded.size == expectedSize && encoded[0] == 0x04.toByte()) {
                    "Expected uncompressed X9.62 point of size $expectedSize for $curve"
                }
                val x = encoded.copyOfRange(1, 1 + coordSize)
                val y = encoded.copyOfRange(1 + coordSize, encoded.size)
                EcPointDecompression.checkPointOnCurve(curve, x, y)
                EcPublicKeyDoubleCoordinate(curve, x, y)
            }
            else -> throw IllegalArgumentException("Unsupported traditional curve $curve")
        }
    }

    fun deserializePublicKey(
        algorithm: Algorithm,
        encoded: ByteArray
    ): Pair<MlKemPublicKey, EcPublicKey> {
        val spec = specFor(algorithm)
        require(encoded.size == spec.totalPublicKeySize) {
            "Expected ${spec.totalPublicKeySize} bytes for $algorithm public key, got ${encoded.size}"
        }
        val pqBytes = encoded.copyOfRange(0, spec.pqPublicKeySize)
        val tradBytes = encoded.copyOfRange(spec.pqPublicKeySize, encoded.size)
        val pqKey = MlKemPublicKey(spec.pqAlgorithm, ByteString(pqBytes))
        val tradKey = deserializeTraditionalPublicKey(spec.traditionalCurve, tradBytes)
        return pqKey to tradKey
    }

    internal fun randomScalar(curve: EcCurve, seed: ByteArray): ByteArray {
        return when (curve) {
            EcCurve.X25519 -> {
                require(seed.size == 32) {
                    "Expected 32-byte seed for Curve25519 RandomScalar, got ${seed.size}"
                }
                seed.copyOf()
            }
            EcCurve.P256, EcCurve.P384 -> {
                val order = if (curve == EcCurve.P256) P256_ORDER else P384_ORDER
                val nScalar = order.size
                var start = 0
                var end = nScalar
                while (end <= seed.size) {
                    val candidate = seed.copyOfRange(start, end)
                    if (isValidEcScalar(candidate, order)) {
                        return candidate
                    }
                    candidate.secureZero()
                    start = end
                    end += nScalar
                }
                throw IllegalStateException("Rejection sampling failed for $curve")
            }
            else -> throw IllegalArgumentException("Unsupported traditional curve $curve")
        }
    }

    private fun isValidEcScalar(scalar: ByteArray, order: ByteArray): Boolean {
        if (scalar.size != order.size) {
            return false
        }
        var nonZero = false
        for (b in scalar) {
            if (b != 0x00.toByte()) {
                nonZero = true
                break
            }
        }
        if (!nonZero) {
            return false
        }
        for (i in scalar.indices) {
            val a = scalar[i].toInt() and 0xFF
            val b = order[i].toInt() and 0xFF
            if (a < b) return true
            if (a > b) return false
        }
        return false
    }

    private fun isAllZeroConstantTime(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) {
            acc = acc or (b.toInt() and 0xFF)
        }
        return acc == 0
    }

    internal suspend fun expandDecapsKey(
        algorithm: Algorithm,
        seed: ByteArray
    ): Triple<MlKemPrivateKey, EcPrivateKey, HybridKemPublicKey> {
        val spec = specFor(algorithm)
        require(seed.size == SEED_SIZE) {
            "Expected $SEED_SIZE-byte hybrid KEM seed for $algorithm, got ${seed.size}"
        }
        val seedFull = Sha3.shake256(seed, outputLength = ML_KEM_SEED_SIZE + spec.traditionalSeedSize)
        val seedPq = seedFull.copyOfRange(0, ML_KEM_SEED_SIZE)
        val seedT = seedFull.copyOfRange(ML_KEM_SEED_SIZE, seedFull.size)
        var skT: ByteArray? = null
        var pqPriv: MlKemPrivateKey? = null
        var tradPriv: EcPrivateKey? = null
        var ok = false
        return try {
            pqPriv = Crypto.createMlKemPrivateKeyFromSeed(spec.pqAlgorithm, seedPq)
            skT = randomScalar(spec.traditionalCurve, seedT)
            tradPriv = Crypto.createEcPrivateKeyFromScalar(spec.traditionalCurve, skT)
            val pubBytes = pqPriv.publicKey.encoded.toByteArray() +
                serializeTraditionalPublicKey(tradPriv.publicKey)
            val pubKey = HybridKemPublicKey(algorithm, ByteString(pubBytes))
            ok = true
            Triple(pqPriv, tradPriv, pubKey)
        } finally {
            seedFull.secureZero()
            seedPq.secureZero()
            seedT.secureZero()
            skT?.secureZero()
            if (!ok) {
                pqPriv?.close()
                tradPriv?.close()
            }
        }
    }

    suspend fun createPrivateKeyFromSeed(
        algorithm: Algorithm,
        seed: ByteArray
    ): HybridKemPrivateKey {
        val (pqPriv, tradPriv, pubKey) = expandDecapsKey(algorithm, seed)
        pqPriv.close()
        tradPriv.close()
        return HybridKemPrivateKey(algorithm, ByteString(seed.copyOf()), pubKey)
    }

    suspend fun createPrivateKey(algorithm: Algorithm): HybridKemPrivateKey {
        val seed = Crypto.secureRandom.nextBytes(SEED_SIZE)
        return try {
            createPrivateKeyFromSeed(algorithm, seed)
        } finally {
            seed.secureZero()
        }
    }

    suspend fun createPrivateKeyFromComponents(algorithm: Algorithm): HybridKemPrivateKey {
        val spec = specFor(algorithm)
        var pqPriv: MlKemPrivateKey? = null
        var tradPriv: EcPrivateKey? = null
        var ok = false
        return try {
            pqPriv = Crypto.createMlKemPrivateKey(spec.pqAlgorithm)
            tradPriv = Crypto.createEcPrivateKey(spec.traditionalCurve)
            val pubBytes = pqPriv.publicKey.encoded.toByteArray() +
                serializeTraditionalPublicKey(tradPriv.publicKey)
            val pubKey = HybridKemPublicKey(algorithm, ByteString(pubBytes))
            ok = true
            HybridKemPrivateKey.fromComponents(algorithm, pqPriv, tradPriv, pubKey)
        } finally {
            if (!ok) {
                pqPriv?.close()
                tradPriv?.close()
            }
        }
    }

    internal suspend fun c2priCombiner(
        ssPq: ByteArray,
        ssT: ByteArray,
        ctT: ByteArray,
        ekT: ByteArray,
        labelBytes: ByteArray
    ): ByteArray {
        val totalLen = ssPq.size + ssT.size + ctT.size + ekT.size + labelBytes.size
        val input = ByteArray(totalLen)
        var offset = 0
        try {
            ssPq.copyInto(input, destinationOffset = offset)
            offset += ssPq.size
            ssT.copyInto(input, destinationOffset = offset)
            offset += ssT.size
            ctT.copyInto(input, destinationOffset = offset)
            offset += ctT.size
            ekT.copyInto(input, destinationOffset = offset)
            offset += ekT.size
            labelBytes.copyInto(input, destinationOffset = offset)
            return Crypto.digest(Algorithm.SHA3_256, input)
        } finally {
            input.secureZero()
        }
    }

    private suspend fun keyAgreementChecked(
        curve: EcCurve,
        privateKey: EcPrivateKey,
        publicKey: EcPublicKey
    ): SecureByteString {
        val ssT = try {
            Crypto.keyAgreement(privateKey, publicKey)
        } catch (e: IllegalStateException) {
            if (curve == EcCurve.X25519) {
                throw IllegalArgumentException("Non-contributory X25519 key agreement", e)
            }
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
        if (curve == EcCurve.X25519) {
            val raw = ssT.encoded
            try {
                if (isAllZeroConstantTime(raw)) {
                    ssT.close()
                    throw IllegalArgumentException("Non-contributory X25519 shared secret")
                }
            } finally {
                raw.secureZero()
            }
        }
        return ssT
    }

    suspend fun encapsulate(recipientPublicKey: HybridKemPublicKey): KemResult {
        val spec = specFor(recipientPublicKey.algorithm)
        return Crypto.kemEncapsulate(recipientPublicKey.pqPublicKey).use { pqRes ->
            Crypto.createEcPrivateKey(spec.traditionalCurve).use { ephemeralEc ->
                encapsulateWithComponents(spec, recipientPublicKey, pqRes, ephemeralEc)
            }
        }
    }

    internal suspend fun encapsulateDerand(
        recipientPublicKey: HybridKemPublicKey,
        ikmE: ByteArray
    ): KemResult {
        val spec = specFor(recipientPublicKey.algorithm)
        val expectedSize = ML_KEM_ENCAPS_RANDOMNESS_SIZE + spec.traditionalSeedSize
        require(ikmE.size == expectedSize) {
            "Expected $expectedSize-byte encapsulation randomness for ${spec.algorithm}, got ${ikmE.size}"
        }
        val mPq = ikmE.copyOfRange(0, ML_KEM_ENCAPS_RANDOMNESS_SIZE)
        val seedT = ikmE.copyOfRange(ML_KEM_ENCAPS_RANDOMNESS_SIZE, ikmE.size)
        var skE: ByteArray? = null
        return try {
            skE = randomScalar(spec.traditionalCurve, seedT)
            Crypto.kemEncapsulateDerand(recipientPublicKey.pqPublicKey, mPq).use { pqRes ->
                Crypto.createEcPrivateKeyFromScalar(spec.traditionalCurve, skE).use { ephemeralEc ->
                    encapsulateWithComponents(spec, recipientPublicKey, pqRes, ephemeralEc)
                }
            }
        } finally {
            mPq.secureZero()
            seedT.secureZero()
            skE?.secureZero()
        }
    }

    private suspend fun encapsulateWithComponents(
        spec: HybridKemSpec,
        recipientPublicKey: HybridKemPublicKey,
        pqRes: KemResult,
        ephemeralEc: EcPrivateKey
    ): KemResult {
        return keyAgreementChecked(
            curve = spec.traditionalCurve,
            privateKey = ephemeralEc,
            publicKey = recipientPublicKey.traditionalPublicKey
        ).use { ssT ->
            val ctT = serializeTraditionalPublicKey(ephemeralEc.publicKey)
            val ekT = serializeTraditionalPublicKey(recipientPublicKey.traditionalPublicKey)
            val ssPqBytes = pqRes.sharedSecret.encoded
            val ssTBytes = ssT.encoded
            var ssH: ByteArray? = null
            try {
                ssH = c2priCombiner(
                    ssPq = ssPqBytes,
                    ssT = ssTBytes,
                    ctT = ctT,
                    ekT = ekT,
                    labelBytes = spec.labelBytes
                )
                KemResult(
                    sharedSecret = SecureByteString(ssH),
                    ciphertext = pqRes.ciphertext + ctT
                )
            } finally {
                ssPqBytes.secureZero()
                ssTBytes.secureZero()
                ssH?.secureZero()
            }
        }
    }

    suspend fun decapsulate(
        key: HybridKemPrivateKey,
        ciphertext: ByteArray
    ): SecureByteString {
        key.ensureNotDestroyed()
        val spec = specFor(key.algorithm)
        require(ciphertext.size == spec.totalCiphertextSize) {
            "Expected ${spec.totalCiphertextSize}-byte ciphertext for ${key.algorithm}, got ${ciphertext.size}"
        }
        val ctPq = ciphertext.copyOfRange(0, spec.pqCiphertextSize)
        val ctTBytes = ciphertext.copyOfRange(spec.pqCiphertextSize, ciphertext.size)
        val ctT = deserializeTraditionalPublicKey(spec.traditionalCurve, ctTBytes)

        val components = key.componentKeys
        val pqPriv: MlKemPrivateKey
        val tradPriv: EcPrivateKey
        val shouldCloseComponents: Boolean
        if (components != null) {
            pqPriv = components.first
            tradPriv = components.second
            shouldCloseComponents = false
        } else {
            val (expandedPq, expandedTrad, derivedPub) = expandDecapsKey(key.algorithm, key.encodedKeyMaterial)
            if (derivedPub != key.publicKey) {
                expandedPq.close()
                expandedTrad.close()
                throw IllegalArgumentException("Hybrid KEM private key does not match publicKey")
            }
            pqPriv = expandedPq
            tradPriv = expandedTrad
            shouldCloseComponents = true
        }

        return try {
            Crypto.kemDecapsulate(pqPriv, ctPq).use { ssPq ->
                keyAgreementChecked(spec.traditionalCurve, tradPriv, ctT).use { ssT ->
                    val ekT = serializeTraditionalPublicKey(key.publicKey.traditionalPublicKey)
                    val ssPqBytes = ssPq.encoded
                    val ssTBytes = ssT.encoded
                    var ssH: ByteArray? = null
                    try {
                        ssH = c2priCombiner(
                            ssPq = ssPqBytes,
                            ssT = ssTBytes,
                            ctT = ctTBytes,
                            ekT = ekT,
                            labelBytes = spec.labelBytes
                        )
                        SecureByteString(ssH)
                    } finally {
                        ssPqBytes.secureZero()
                        ssTBytes.secureZero()
                        ssH?.secureZero()
                    }
                }
            }
        } finally {
            if (shouldCloseComponents) {
                pqPriv.close()
                tradPriv.close()
            }
        }
    }
}
