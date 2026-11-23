package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.asn1.OID
import org.multipaz.util.fromHex

/**
 * Shared combiner, wire-format codec, and cryptographic helpers for
 * `draft-ietf-jose-pq-composite-sigs-07` (Composite ML-DSA signatures).
 */
internal object HybridSigning {

    private const val ML_DSA_SEED_SIZE = 32
    private const val ED25519_PRIVATE_KEY_SIZE = 32
    private const val ED25519_PUBLIC_KEY_SIZE = 32
    private const val ED25519_SIGNATURE_SIZE = 64

    private val PREFIX = "CompositeAlgorithmSignatures2025".encodeToByteArray()

    // RFC 5915 / draft-ietf-jose-pq-composite-sigs-07 §4.2.2 Table 3 exact DER templates (no publicKey [1])
    private val P256_EC_PRIV_PREFIX = byteArrayOf(0x30, 0x31, 0x02, 0x01, 0x01, 0x04, 0x20)
    private val P256_EC_PRIV_SUFFIX =
        byteArrayOf(0xA0.toByte(), 0x0A, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)
    private val P384_EC_PRIV_PREFIX = byteArrayOf(0x30, 0x3E, 0x02, 0x01, 0x01, 0x04, 0x30)
    private val P384_EC_PRIV_SUFFIX =
        byteArrayOf(0xA0.toByte(), 0x07, 0x06, 0x05, 0x2B, 0x81.toByte(), 0x04, 0x00, 0x22)

    private val P256_ORDER = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551".fromHex()
    private val P384_ORDER =
        "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52973".fromHex()

    internal data class CompositeAlgorithmSpec(
        val algorithm: Algorithm,
        val oid: String,
        val pqAlgorithm: Algorithm,
        val pqPublicKeySize: Int,
        val pqSignatureSize: Int,
        val traditionalCurve: EcCurve,
        val traditionalAlgorithm: Algorithm,
        val traditionalPublicKeySize: Int,
        val preHashAlgorithm: Algorithm,
        val label: String,
    ) {
        val labelBytes: ByteArray = label.encodeToByteArray()
        val totalPublicKeySize: Int = pqPublicKeySize + traditionalPublicKeySize
        val traditionalCoordinateSize: Int = (traditionalCurve.bitSize + 7) / 8
    }

    internal val specs: List<CompositeAlgorithmSpec> = listOf(
        CompositeAlgorithmSpec(
            algorithm = Algorithm.ML_DSA_44_ES256,
            oid = OID.ML_DSA_44_ECDSA_P256_SHA256.oid,
            pqAlgorithm = Algorithm.ML_DSA_44,
            pqPublicKeySize = 1312,
            pqSignatureSize = 2420,
            traditionalCurve = EcCurve.P256,
            traditionalAlgorithm = Algorithm.ESP256,
            traditionalPublicKeySize = 65,
            preHashAlgorithm = Algorithm.SHA256,
            label = "COMPSIG-MLDSA44-ECDSA-P256-SHA256",
        ),
        CompositeAlgorithmSpec(
            algorithm = Algorithm.ML_DSA_65_ES256,
            oid = OID.ML_DSA_65_ECDSA_P256_SHA512.oid,
            pqAlgorithm = Algorithm.ML_DSA_65,
            pqPublicKeySize = 1952,
            pqSignatureSize = 3309,
            traditionalCurve = EcCurve.P256,
            traditionalAlgorithm = Algorithm.ESP256,
            traditionalPublicKeySize = 65,
            preHashAlgorithm = Algorithm.SHA512,
            label = "COMPSIG-MLDSA65-ECDSA-P256-SHA512",
        ),
        CompositeAlgorithmSpec(
            algorithm = Algorithm.ML_DSA_65_ES384,
            oid = OID.ML_DSA_65_ECDSA_P384_SHA512.oid,
            pqAlgorithm = Algorithm.ML_DSA_65,
            pqPublicKeySize = 1952,
            pqSignatureSize = 3309,
            traditionalCurve = EcCurve.P384,
            traditionalAlgorithm = Algorithm.ESP384,
            traditionalPublicKeySize = 97,
            preHashAlgorithm = Algorithm.SHA512,
            label = "COMPSIG-MLDSA65-ECDSA-P384-SHA512",
        ),
        CompositeAlgorithmSpec(
            algorithm = Algorithm.ML_DSA_87_ES384,
            oid = OID.ML_DSA_87_ECDSA_P384_SHA512.oid,
            pqAlgorithm = Algorithm.ML_DSA_87,
            pqPublicKeySize = 2592,
            pqSignatureSize = 4627,
            traditionalCurve = EcCurve.P384,
            traditionalAlgorithm = Algorithm.ESP384,
            traditionalPublicKeySize = 97,
            preHashAlgorithm = Algorithm.SHA512,
            label = "COMPSIG-MLDSA87-ECDSA-P384-SHA512",
        ),
        CompositeAlgorithmSpec(
            algorithm = Algorithm.ML_DSA_44_ED25519,
            oid = OID.ML_DSA_44_ED25519_SHA512.oid,
            pqAlgorithm = Algorithm.ML_DSA_44,
            pqPublicKeySize = 1312,
            pqSignatureSize = 2420,
            traditionalCurve = EcCurve.ED25519,
            traditionalAlgorithm = Algorithm.ED25519,
            traditionalPublicKeySize = ED25519_PUBLIC_KEY_SIZE,
            preHashAlgorithm = Algorithm.SHA512,
            label = "COMPSIG-MLDSA44-Ed25519-SHA512",
        ),
    )

    private val byAlgorithm: Map<Algorithm, CompositeAlgorithmSpec> = specs.associateBy { it.algorithm }
    private val byOid: Map<String, CompositeAlgorithmSpec> = specs.associateBy { it.oid }
    private val byPublicKeySize: Map<Int, CompositeAlgorithmSpec> = specs.associateBy { it.totalPublicKeySize }

    fun specFor(algorithm: Algorithm): CompositeAlgorithmSpec =
        byAlgorithm[algorithm] ?: throw IllegalArgumentException("Unsupported hybrid signing algorithm: $algorithm")

    fun specForOid(oid: String): CompositeAlgorithmSpec =
        byOid[oid] ?: throw IllegalArgumentException("Unsupported composite OID: $oid")

    fun specForPublicKeySizeOrNull(size: Int): CompositeAlgorithmSpec? = byPublicKeySize[size]

    fun isCompositeOid(oid: String): Boolean = byOid.containsKey(oid)

    fun supportedAlgorithmsFor(
        supportedMlDsa: Set<Algorithm>,
        supportedCurves: Set<EcCurve>
    ): Set<Algorithm> = specs
        .filter { it.pqAlgorithm in supportedMlDsa && it.traditionalCurve in supportedCurves }
        .map { it.algorithm }
        .toSet()

    fun deserializePublicKey(
        algorithm: Algorithm,
        encoded: ByteArray
    ): Pair<MlDsaPublicKey, EcPublicKey> {
        val spec = specFor(algorithm)
        require(encoded.size == spec.totalPublicKeySize) {
            "Expected ${spec.totalPublicKeySize} bytes for $algorithm public key, got ${encoded.size}"
        }
        val pqBytes = encoded.copyOfRange(0, spec.pqPublicKeySize)
        val tradBytes = encoded.copyOfRange(spec.pqPublicKeySize, encoded.size)
        val pqKey = MlDsaPublicKey(spec.pqAlgorithm, ByteString(pqBytes))
        val tradKey = when (spec.traditionalCurve) {
            EcCurve.ED25519 -> {
                require(tradBytes.size == ED25519_PUBLIC_KEY_SIZE) {
                    "Expected $ED25519_PUBLIC_KEY_SIZE bytes for Ed25519 public key, got ${tradBytes.size}"
                }
                EcPublicKeyOkp(EcCurve.ED25519, tradBytes)
            }
            EcCurve.P256, EcCurve.P384 -> {
                val coordSize = spec.traditionalCoordinateSize
                require(tradBytes.size == 1 + 2 * coordSize && tradBytes[0] == 0x04.toByte()) {
                    "Expected uncompressed X9.62 public key of size ${1 + 2 * coordSize} for ${spec.traditionalCurve}"
                }
                val x = tradBytes.copyOfRange(1, 1 + coordSize)
                val y = tradBytes.copyOfRange(1 + coordSize, tradBytes.size)
                EcPointDecompression.checkPointOnCurve(spec.traditionalCurve, x, y)
                EcPublicKeyDoubleCoordinate(spec.traditionalCurve, x, y)
            }
            else -> throw IllegalArgumentException("Unsupported traditional curve ${spec.traditionalCurve}")
        }
        return pqKey to tradKey
    }

    fun serializePublicKey(
        pqPublicKey: MlDsaPublicKey,
        traditionalPublicKey: EcPublicKey,
        spec: CompositeAlgorithmSpec
    ): ByteArray {
        require(pqPublicKey.algorithm == spec.pqAlgorithm) {
            "Expected ${spec.pqAlgorithm} public key, got ${pqPublicKey.algorithm}"
        }
        require(traditionalPublicKey.curve == spec.traditionalCurve) {
            "Expected ${spec.traditionalCurve} public key, got ${traditionalPublicKey.curve}"
        }
        val tradBytes = when (traditionalPublicKey) {
            is EcPublicKeyOkp -> traditionalPublicKey.x
            is EcPublicKeyDoubleCoordinate -> traditionalPublicKey.asUncompressedPointEncoding
        }
        val pqBytes = pqPublicKey.encoded.toByteArray()
        require(pqBytes.size == spec.pqPublicKeySize)
        require(tradBytes.size == spec.traditionalPublicKeySize)
        return pqBytes + tradBytes
    }

    fun deserializePrivateKey(
        algorithm: Algorithm,
        encoded: ByteArray,
        publicKey: HybridPublicKey?
    ): Triple<MlDsaPrivateKey, EcPrivateKey, HybridPublicKey> {
        val spec = specFor(algorithm)
        require(publicKey != null) {
            "publicKey is required to deserialize HybridPrivateKey"
        }
        require(publicKey.algorithm == algorithm) {
            "Public key algorithm ${publicKey.algorithm} does not match private key algorithm $algorithm"
        }
        val pqSeed = encoded.copyOfRange(0, minOf(ML_DSA_SEED_SIZE, encoded.size))
        require(pqSeed.size == ML_DSA_SEED_SIZE) {
            "Expected at least $ML_DSA_SEED_SIZE bytes for ML-DSA seed in $algorithm private key"
        }
        val tradSk = encoded.copyOfRange(ML_DSA_SEED_SIZE, encoded.size)
        return try {
            val tradKey = when (spec.traditionalCurve) {
                EcCurve.ED25519 -> {
                    require(tradSk.size == ED25519_PRIVATE_KEY_SIZE) {
                        "Expected $ED25519_PRIVATE_KEY_SIZE bytes for Ed25519 private key, got ${tradSk.size}"
                    }
                    val pubOkp = publicKey.traditionalPublicKey as EcPublicKeyOkp
                    EcPrivateKeyOkp(EcCurve.ED25519, tradSk.copyOf(), pubOkp.x)
                }
                EcCurve.P256, EcCurve.P384 -> {
                    val d = decodeTable3EcPrivateKey(spec.traditionalCurve, tradSk)
                    val pubEc = publicKey.traditionalPublicKey as EcPublicKeyDoubleCoordinate
                    EcPrivateKeyDoubleCoordinate(spec.traditionalCurve, d, pubEc.x, pubEc.y)
                }
                else -> throw IllegalArgumentException("Unsupported traditional curve ${spec.traditionalCurve}")
            }
            val pqKey = MlDsaPrivateKey(spec.pqAlgorithm, ByteString(pqSeed), publicKey.pqPublicKey)
            Triple(pqKey, tradKey, publicKey)
        } finally {
            pqSeed.secureZero()
            tradSk.secureZero()
        }
    }

    fun serializePrivateKey(
        pqPrivateKey: MlDsaPrivateKey,
        traditionalPrivateKey: EcPrivateKey,
        spec: CompositeAlgorithmSpec
    ): ByteArray {
        require(pqPrivateKey.algorithm == spec.pqAlgorithm) {
            "Expected ${spec.pqAlgorithm} private key, got ${pqPrivateKey.algorithm}"
        }
        require(traditionalPrivateKey.curve == spec.traditionalCurve) {
            "Expected ${spec.traditionalCurve} private key, got ${traditionalPrivateKey.curve}"
        }
        val pqSeed = pqPrivateKey.encoded.toByteArray()
        require(pqSeed.size == ML_DSA_SEED_SIZE) {
            "Composite private keys require a 32-byte ML-DSA seed, got ${pqSeed.size} bytes"
        }
        val tradSk = when (spec.traditionalCurve) {
            EcCurve.ED25519 -> {
                val okp = traditionalPrivateKey as EcPrivateKeyOkp
                require(okp.d.size == ED25519_PRIVATE_KEY_SIZE) {
                    "Expected $ED25519_PRIVATE_KEY_SIZE-byte Ed25519 private key, got ${okp.d.size}"
                }
                okp.d.copyOf()
            }
            EcCurve.P256, EcCurve.P384 -> encodeTable3EcPrivateKey(spec.traditionalCurve, traditionalPrivateKey.d)
            else -> throw IllegalArgumentException("Unsupported traditional curve ${spec.traditionalCurve}")
        }
        return try {
            pqSeed + tradSk
        } finally {
            pqSeed.secureZero()
            tradSk.secureZero()
        }
    }

    suspend fun createPrivateKey(algorithm: Algorithm): HybridPrivateKey {
        val spec = specFor(algorithm)
        val pqPriv = Crypto.createMlDsaPrivateKey(spec.pqAlgorithm)
        var tradPriv: EcPrivateKey? = null
        var privBytes: ByteArray? = null
        return try {
            tradPriv = Crypto.createEcPrivateKey(spec.traditionalCurve)
            val pubBytes = serializePublicKey(pqPriv.publicKey, tradPriv.publicKey, spec)
            val pubKey = HybridPublicKey(algorithm, ByteString(pubBytes))
            privBytes = serializePrivateKey(pqPriv, tradPriv, spec)
            HybridPrivateKey(algorithm, ByteString(privBytes), pubKey)
        } finally {
            privBytes?.secureZero()
            pqPriv.close()
            tradPriv?.close()
        }
    }

    suspend fun sign(
        key: HybridPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): HybridSignature {
        key.ensureNotDestroyed()
        require(signatureAlgorithm == key.algorithm) {
            "Signature algorithm $signatureAlgorithm doesn't match key algorithm ${key.algorithm}"
        }
        val spec = specFor(key.algorithm)
        val mPrime = buildMPrime(spec, message)
        val pqSig = Crypto.sign(key.pqPrivateKey, spec.pqAlgorithm, mPrime, spec.labelBytes)
        val tradSig = Crypto.sign(key.traditionalPrivateKey, spec.traditionalAlgorithm, mPrime)
        val tradSigBytes = when (spec.traditionalCurve) {
            EcCurve.ED25519 -> tradSig.r + tradSig.s
            EcCurve.P256, EcCurve.P384 -> encodeMinimalDerEcdsaSignature(tradSig)
            else -> throw IllegalArgumentException("Unsupported traditional curve ${spec.traditionalCurve}")
        }
        return HybridSignature(pqSig.signature + tradSigBytes)
    }

    suspend fun checkSignature(
        publicKey: HybridPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: HybridSignature
    ) {
        require(algorithm == publicKey.algorithm) {
            "Signature algorithm $algorithm doesn't match key algorithm ${publicKey.algorithm}"
        }
        val spec = specFor(publicKey.algorithm)
        val raw = signature.signature
        require(raw.size > spec.pqSignatureSize) {
            "Hybrid signature too short: ${raw.size} <= ${spec.pqSignatureSize}"
        }
        val pqSigBytes = raw.copyOfRange(0, spec.pqSignatureSize)
        val tradSigBytes = raw.copyOfRange(spec.pqSignatureSize, raw.size)
        val tradEcSig = when (spec.traditionalCurve) {
            EcCurve.ED25519 -> {
                require(tradSigBytes.size == ED25519_SIGNATURE_SIZE) {
                    "Expected $ED25519_SIGNATURE_SIZE-byte Ed25519 signature, got ${tradSigBytes.size}"
                }
                EcSignature(
                    tradSigBytes.copyOfRange(0, 32),
                    tradSigBytes.copyOfRange(32, ED25519_SIGNATURE_SIZE)
                )
            }
            EcCurve.P256, EcCurve.P384 -> decodeMinimalDerEcdsaSignature(tradSigBytes, spec.traditionalCurve)
            else -> throw IllegalArgumentException("Unsupported traditional curve ${spec.traditionalCurve}")
        }
        val mPrime = buildMPrime(spec, message)
        Crypto.checkSignature(
            publicKey = publicKey.pqPublicKey,
            message = mPrime,
            algorithm = spec.pqAlgorithm,
            signature = MlDsaSignature(pqSigBytes),
            context = spec.labelBytes
        )
        Crypto.checkSignature(
            publicKey = publicKey.traditionalPublicKey,
            message = mPrime,
            algorithm = spec.traditionalAlgorithm,
            signature = tradEcSig
        )
    }

    internal suspend fun buildMPrime(
        spec: CompositeAlgorithmSpec,
        message: ByteArray
    ): ByteArray {
        val digest = Crypto.digest(spec.preHashAlgorithm, message)
        val builder = ByteStringBuilder(PREFIX.size + spec.labelBytes.size + 1 + digest.size)
        builder.append(PREFIX)
        builder.append(spec.labelBytes)
        builder.append(0x00)
        builder.append(digest)
        return builder.toByteString().toByteArray()
    }

    private fun decodeTable3EcPrivateKey(curve: EcCurve, encoded: ByteArray): ByteArray {
        val (prefix, suffix, order) = when (curve) {
            EcCurve.P256 -> Triple(P256_EC_PRIV_PREFIX, P256_EC_PRIV_SUFFIX, P256_ORDER)
            EcCurve.P384 -> Triple(P384_EC_PRIV_PREFIX, P384_EC_PRIV_SUFFIX, P384_ORDER)
            else -> throw IllegalArgumentException("Unsupported curve: $curve")
        }
        val coordSize = (curve.bitSize + 7) / 8
        val expectedSize = prefix.size + coordSize + suffix.size
        require(encoded.size == expectedSize) {
            "Expected $expectedSize-byte Table 3 ECPrivateKey for $curve, got ${encoded.size}"
        }
        for (i in prefix.indices) {
            require(encoded[i] == prefix[i]) {
                "Invalid Table 3 ECPrivateKey header for $curve"
            }
        }
        val suffixStart = prefix.size + coordSize
        for (i in suffix.indices) {
            require(encoded[suffixStart + i] == suffix[i]) {
                "Invalid Table 3 ECPrivateKey parameters for $curve"
            }
        }
        val d = encoded.copyOfRange(prefix.size, suffixStart)
        if (!isValidScalar(d, order)) {
            d.secureZero()
            throw IllegalArgumentException("EC private scalar out of range for $curve")
        }
        return d
    }

    private fun encodeTable3EcPrivateKey(curve: EcCurve, rawD: ByteArray): ByteArray {
        val (prefix, suffix, order) = when (curve) {
            EcCurve.P256 -> Triple(P256_EC_PRIV_PREFIX, P256_EC_PRIV_SUFFIX, P256_ORDER)
            EcCurve.P384 -> Triple(P384_EC_PRIV_PREFIX, P384_EC_PRIV_SUFFIX, P384_ORDER)
            else -> throw IllegalArgumentException("Unsupported curve: $curve")
        }
        val coordSize = (curve.bitSize + 7) / 8
        val dFixed = normalizeFixedWidthUnsigned(rawD, coordSize)
        return try {
            require(isValidScalar(dFixed, order)) {
                "EC private scalar out of range for $curve"
            }
            prefix + dFixed + suffix
        } finally {
            dFixed.secureZero()
        }
    }

    private fun normalizeFixedWidthUnsigned(bytes: ByteArray, targetSize: Int): ByteArray {
        return when {
            bytes.size == targetSize -> bytes.copyOf()
            bytes.size < targetSize -> {
                val out = ByteArray(targetSize)
                bytes.copyInto(out, destinationOffset = targetSize - bytes.size)
                out
            }
            else -> {
                val extra = bytes.size - targetSize
                for (i in 0 until extra) {
                    require(bytes[i] == 0x00.toByte()) {
                        "Scalar exceeds $targetSize bytes"
                    }
                }
                bytes.copyOfRange(extra, bytes.size)
            }
        }
    }

    private fun isValidScalar(scalar: ByteArray, order: ByteArray): Boolean {
        if (scalar.size != order.size || scalar.all { it == 0x00.toByte() }) {
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

    internal fun encodeMinimalDerEcdsaSignature(signature: EcSignature): ByteArray {
        val rDer = encodeMinimalDerPositiveInt(signature.r)
        val sDer = encodeMinimalDerPositiveInt(signature.s)
        val bodySize = rDer.size + sDer.size
        check(bodySize < 0x80) { "Unexpectedly large ECDSA DER body size: $bodySize" }
        val builder = ByteStringBuilder(2 + bodySize)
        builder.append(0x30)
        builder.append(bodySize.toByte())
        builder.append(rDer)
        builder.append(sDer)
        return builder.toByteString().toByteArray()
    }

    private fun encodeMinimalDerPositiveInt(unsignedBytes: ByteArray): ByteArray {
        var start = 0
        while (start < unsignedBytes.size - 1 && unsignedBytes[start] == 0x00.toByte()) {
            start++
        }
        val needsLeadingZero = (unsignedBytes[start].toInt() and 0x80) != 0
        val valueLen = unsignedBytes.size - start
        val totalLen = valueLen + if (needsLeadingZero) 1 else 0
        val out = ByteArray(2 + totalLen)
        out[0] = 0x02
        out[1] = totalLen.toByte()
        var dst = 2
        if (needsLeadingZero) {
            out[dst++] = 0x00
        }
        unsignedBytes.copyInto(out, destinationOffset = dst, startIndex = start)
        return out
    }

    internal fun decodeMinimalDerEcdsaSignature(der: ByteArray, curve: EcCurve): EcSignature {
        val coordSize = (curve.bitSize + 7) / 8
        val maxDerSize = if (curve == EcCurve.P256) 72 else 104
        require(der.size in 8..maxDerSize) {
            "ECDSA DER signature size ${der.size} out of bounds [8, $maxDerSize] for $curve"
        }
        require(der[0] == 0x30.toByte()) { "Expected ASN.1 SEQUENCE tag 0x30 in ECDSA signature" }
        val seqLen = der[1].toInt() and 0xFF
        require(seqLen < 0x80 && 2 + seqLen == der.size) {
            "Non-minimal or invalid DER SEQUENCE length in ECDSA signature"
        }
        val (rOffsetNext, rBytes) = readMinimalDerPositiveInt(der, 2, coordSize)
        val (sOffsetNext, sBytes) = readMinimalDerPositiveInt(der, rOffsetNext, coordSize)
        require(sOffsetNext == der.size) {
            "Trailing bytes in ECDSA DER signature"
        }
        return EcSignature(rBytes, sBytes)
    }

    private fun readMinimalDerPositiveInt(
        der: ByteArray,
        offset: Int,
        coordSize: Int
    ): Pair<Int, ByteArray> {
        require(offset + 2 < der.size && der[offset] == 0x02.toByte()) {
            "Expected ASN.1 INTEGER tag 0x02 in ECDSA signature"
        }
        val intLen = der[offset + 1].toInt() and 0xFF
        require(intLen in 1..127 && offset + 2 + intLen <= der.size) {
            "Invalid or non-minimal DER INTEGER length in ECDSA signature"
        }
        val first = der[offset + 2]
        require((first.toInt() and 0x80) == 0) {
            "ECDSA signature integer must be positive"
        }
        var magnitudeStart = offset + 2
        var magnitudeLen = intLen
        if (first == 0x00.toByte()) {
            require(intLen > 1 && (der[offset + 3].toInt() and 0x80) != 0) {
                "Non-minimal DER INTEGER padding in ECDSA signature"
            }
            magnitudeStart++
            magnitudeLen--
        }
        require(magnitudeLen in 1..coordSize) {
            "ECDSA signature integer size $magnitudeLen exceeds coordinate size $coordSize"
        }
        val out = ByteArray(coordSize)
        der.copyInto(
            destination = out,
            destinationOffset = coordSize - magnitudeLen,
            startIndex = magnitudeStart,
            endIndex = magnitudeStart + magnitudeLen
        )
        require(out.any { it != 0x00.toByte() }) {
            "ECDSA signature integer must not be zero"
        }
        return (offset + 2 + intLen) to out
    }
}
