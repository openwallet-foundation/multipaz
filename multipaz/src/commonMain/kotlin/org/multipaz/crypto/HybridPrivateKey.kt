package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A PQ/T hybrid composite signature private key as specified in
 * `draft-ietf-jose-pq-composite-sigs-07` and RFC 10073.
 *
 * The raw private key bytes consist of the 32-byte FIPS 204 ML-DSA seed immediately followed by
 * the traditional private key (`mlDsaSeed || traditionalPrivateKey`). Component private keys
 * are kept internal to prevent standalone component key reuse or export outside the combiner.
 *
 * Call [close] when done with this key to zero key material in memory.
 *
 * @property algorithm the composite signature [Algorithm].
 * @param encoded the raw concatenated private key bytes.
 * @property publicKey the corresponding [HybridPublicKey].
 */
@CborSerializationImplemented(schemaId = "")
class HybridPrivateKey(
    val algorithm: Algorithm,
    encoded: ByteString,
    override val publicKey: HybridPublicKey
) : PrivateKey() {

    private val _encoded: ByteArray = encoded.toByteArray()
    private var _isDestroyed: Boolean = false

    internal val pqPrivateKey: MlDsaPrivateKey
    internal val traditionalPrivateKey: EcPrivateKey
    private val disposer: KeyDisposer

    init {
        var ok = false
        val (pq, trad, _) = try {
            HybridSigning.deserializePrivateKey(algorithm, _encoded, publicKey).also {
                ok = true
            }
        } finally {
            if (!ok) {
                _encoded.secureZero()
            }
        }
        pqPrivateKey = pq
        traditionalPrivateKey = trad
        val rawBuffer = _encoded
        val pqRef = pq
        val tradRef = trad
        disposer = KeyDisposer.register(this) {
            rawBuffer.secureZero()
            pqRef.close()
            tradRef.close()
        }
    }

    /** Whether this private key has been closed and zeroed in memory. */
    override val isDestroyed: Boolean
        get() = _isDestroyed

    /**
     * The raw concatenated private key bytes (`mlDsaSeed || traditionalPrivateKey`).
     *
     * @throws IllegalStateException if this key has already been destroyed.
     */
    val encoded: ByteString
        get() {
            checkNotDestroyed()
            return ByteString(_encoded)
        }

    internal fun ensureNotDestroyed() = checkNotDestroyed()

    internal val encodedKeyMaterial: ByteArray
        get() {
            checkNotDestroyed()
            return _encoded
        }

    override fun close() {
        if (!_isDestroyed) {
            _isDestroyed = true
            _encoded.secureZero()
            pqPrivateKey.close()
            traditionalPrivateKey.close()
        }
    }

    /**
     * Creates an independent duplicate of this private key with its own memory buffer.
     *
     * @return a new [HybridPrivateKey] instance containing the same key material.
     * @throws IllegalStateException if this key has already been destroyed.
     */
    override fun duplicate(): HybridPrivateKey {
        checkNotDestroyed()
        return HybridPrivateKey(algorithm, ByteString(_encoded), publicKey)
    }

    /**
     * Encodes this private key as a DER-encoded COSE/COSE-style COSE_Key.
     *
     * @param additionalLabels optional additional COSE labels to include.
     * @return the encoded `AKP` [CoseKey] containing both public and private key bytes.
     * @throws IllegalStateException if this key has already been destroyed.
     */
    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey {
        checkNotDestroyed()
        return publicKey.toCoseKey(
            mapOf(Pair(Cose.COSE_KEY_PARAM_PRIV_KEY.toCoseLabel, _encoded.copyOf().toDataItem())) +
                additionalLabels
        )
    }

    /**
     * Encodes this private key as an `AKP` JSON Web Key.
     *
     * @param additionalClaims optional additional JWK claims to include.
     * @return the encoded [JsonObject].
     * @throws IllegalStateException if this key has already been destroyed.
     */
    override fun toJwk(
        additionalClaims: JsonObject?
    ): JsonObject {
        checkNotDestroyed()
        val joseAlgId = algorithm.joseAlgorithmIdentifier
            ?: throw IllegalStateException("No JOSE algorithm identifier for $algorithm")
        return buildJsonObject {
            put("kty", "AKP")
            put("alg", joseAlgId)
            put("pub", publicKey.encoded.toByteArray().toBase64Url())
            put("priv", _encoded.toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }
    }

    /**
     * Encodes this private key as a DER-encoded PKCS#8 `OneAsymmetricKey` sequence.
     *
     * @return the DER-encoded PKCS#8 bytes.
     * @throws IllegalStateException if this key has already been destroyed.
     */
    fun toPkcs8(): ByteArray {
        checkNotDestroyed()
        val spec = HybridSigning.specFor(algorithm)
        val privCopy = _encoded.copyOf()
        return try {
            ASN1.encode(
                ASN1Sequence(
                    listOf(
                        ASN1Integer(0),
                        ASN1Sequence(listOf(ASN1ObjectIdentifier(spec.oid))),
                        ASN1OctetString(privCopy)
                    )
                )
            )
        } finally {
            privCopy.secureZero()
        }
    }

    /**
     * Encodes this private key as a DER-encoded PKCS#8 `OneAsymmetricKey` (`PrivateKeyInfo`) sequence.
     *
     * @return the DER-encoded PKCS#8 bytes.
     * @throws IllegalStateException if this key has already been destroyed.
     */
    fun toPrivateKeyInfo(): ByteArray = toPkcs8()

    /**
     * Encodes this private key in PEM format (`PRIVATE KEY`).
     *
     * @return the PEM-encoded private key string.
     * @throws IllegalStateException if this key has already been destroyed.
     */
    @OptIn(ExperimentalEncodingApi::class)
    override fun toPem(): String {
        checkNotDestroyed()
        val pkcs8 = toPkcs8()
        return try {
            val sb = StringBuilder()
            sb.append("-----BEGIN PRIVATE KEY-----\n")
            sb.append(Base64.Mime.encode(pkcs8))
            sb.append("\n-----END PRIVATE KEY-----\n")
            sb.toString()
        } finally {
            pkcs8.secureZero()
        }
    }

    override fun toString(): String = "HybridPrivateKey(algorithm=$algorithm, publicKey=$publicKey)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as HybridPrivateKey
        if (algorithm != other.algorithm) return false
        if (publicKey != other.publicKey) return false
        if (isDestroyed || other.isDestroyed) {
            return isDestroyed == other.isDestroyed
        }
        return _encoded.contentEquals(other._encoded)
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + publicKey.hashCode()
        if (!isDestroyed) {
            result = 31 * result + _encoded.contentHashCode()
        }
        return result
    }

    companion object {
        /**
         * Decodes a [HybridPrivateKey] from DER-encoded PKCS#8 `OneAsymmetricKey` bytes.
         *
         * @param pkcs8Bytes the DER-encoded PKCS#8 bytes.
         * @param publicKey the corresponding [HybridPublicKey], or `null` if embedded as an
         *   optional `OneAsymmetricKey` v1 `[1]` publicKey BIT STRING.
         * @return the decoded [HybridPrivateKey].
         * @throws IllegalArgumentException if [pkcs8Bytes] is malformed or missing the public key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromPkcs8(pkcs8Bytes: ByteArray, publicKey: HybridPublicKey? = null): HybridPrivateKey {
            val seq = ASN1.decode(pkcs8Bytes) as ASN1Sequence
            val version = (seq.elements[0] as ASN1Integer).toLong()
            require(version == 0L || version == 1L) { "Unsupported PKCS#8 version $version" }
            val algId = seq.elements[1] as ASN1Sequence
            val oid = (algId.elements[0] as ASN1ObjectIdentifier).oid
            val spec = HybridSigning.specForOid(oid)
            val privKeyOctets = (seq.elements[2] as ASN1OctetString).value
            val resolvedPubKey = publicKey ?: run {
                val pubTag = seq.elements.drop(3).filterIsInstance<ASN1TaggedObject>()
                    .firstOrNull { it.cls == ASN1TagClass.CONTEXT_SPECIFIC && it.tag == 1 }
                if (pubTag != null) {
                    val bitString = if (pubTag.enc == ASN1Encoding.PRIMITIVE) {
                        require(pubTag.content.isNotEmpty()) { "Empty PKCS#8 publicKey bit string" }
                        val numUnused = pubTag.content[0].toInt() and 0xFF
                        require(numUnused == 0) { "Unexpected unused bits in PKCS#8 publicKey: $numUnused" }
                        pubTag.content.copyOfRange(1, pubTag.content.size)
                    } else {
                        val bs = ASN1.decode(pubTag.content) as ASN1BitString
                        require(bs.numUnusedBits == 0) {
                            "Unexpected unused bits in PKCS#8 publicKey: ${bs.numUnusedBits}"
                        }
                        bs.value
                    }
                    HybridPublicKey(spec.algorithm, ByteString(bitString))
                } else {
                    throw IllegalArgumentException(
                        "publicKey is required to deserialize HybridPrivateKey"
                    )
                }
            }
            return try {
                HybridPrivateKey(spec.algorithm, ByteString(privKeyOctets), resolvedPubKey)
            } finally {
                privKeyOctets.secureZero()
            }
        }

        /**
         * Decodes a [HybridPrivateKey] from DER-encoded `PrivateKeyInfo` bytes.
         *
         * @param pkcs8Bytes the DER-encoded `PrivateKeyInfo` bytes.
         * @param publicKey the corresponding [HybridPublicKey], if known.
         * @return the decoded [HybridPrivateKey].
         * @throws IllegalArgumentException if [pkcs8Bytes] is malformed or missing the public key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromPrivateKeyInfo(pkcs8Bytes: ByteArray, publicKey: HybridPublicKey? = null): HybridPrivateKey =
            fromPkcs8(pkcs8Bytes, publicKey)

        /**
         * Decodes a [HybridPrivateKey] from a PEM-encoded `PRIVATE KEY` string.
         *
         * @param pemEncoding the PEM-encoded private key string.
         * @param publicKey the corresponding [HybridPublicKey], if known.
         * @return the decoded [HybridPrivateKey].
         * @throws IllegalArgumentException if [pemEncoding] is malformed or missing the public key.
         */
        @Throws(IllegalArgumentException::class)
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String, publicKey: HybridPublicKey? = null): HybridPrivateKey {
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .trim()
            )
            return try {
                fromPkcs8(encoded, publicKey)
            } finally {
                encoded.secureZero()
            }
        }

        /**
         * Decodes a [HybridPrivateKey] from a COSE `AKP` [CoseKey].
         *
         * @param coseKey the COSE Key containing public and private key parameters.
         * @return the decoded [HybridPrivateKey].
         * @throws IllegalArgumentException if [coseKey] is malformed or missing key parameters.
         */
        @Throws(IllegalArgumentException::class)
        fun fromCoseKey(coseKey: CoseKey): HybridPrivateKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_AKP.toDataItem()) {
                "Key type must be AKP (7), got ${coseKey.keyType}"
            }
            val privBytes = coseKey.labels[Cose.COSE_KEY_PARAM_PRIV_KEY.toCoseLabel]?.asBstr
                ?: throw IllegalArgumentException("Missing priv key in COSE Key")
            val pubKey = HybridPublicKey.fromCoseKey(coseKey)
            return HybridPrivateKey(pubKey.algorithm, ByteString(privBytes), pubKey)
        }

        /**
         * Decodes a [HybridPrivateKey] from an `AKP` JSON Web Key.
         *
         * @param jwk the JSON Web Key object containing `kty`, `alg`, `pub`, and `priv`.
         * @return the decoded [HybridPrivateKey].
         * @throws IllegalArgumentException if [jwk] is malformed or missing fields.
         */
        @Throws(IllegalArgumentException::class)
        fun fromJwk(jwk: JsonObject): HybridPrivateKey {
            val pubKey = HybridPublicKey.fromJwk(jwk)
            val privBase64 = jwk["priv"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing priv in JWK")
            val privBytes = privBase64.fromBase64Url()
            return try {
                HybridPrivateKey(pubKey.algorithm, ByteString(privBytes), pubKey)
            } finally {
                privBytes.secureZero()
            }
        }

        /**
         * Decodes a [HybridPrivateKey] from a CBOR [DataItem] encoding a COSE Key.
         *
         * @param dataItem the CBOR data item.
         * @return the decoded [HybridPrivateKey].
         * @throws IllegalArgumentException if [dataItem] is malformed or missing key parameters.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): HybridPrivateKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
