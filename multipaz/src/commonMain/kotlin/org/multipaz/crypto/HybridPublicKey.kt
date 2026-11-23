package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Sequence
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
 * A PQ/T hybrid composite signature public key as specified in
 * `draft-ietf-jose-pq-composite-sigs-07` and RFC 10073.
 *
 * The raw key bytes consist of the raw ML-DSA public key immediately followed by the
 * traditional public key (`mlDsaPublicKey || traditionalPublicKey`).
 *
 * @property algorithm the composite signature [Algorithm].
 * @property encoded the raw concatenated public key bytes.
 */
@CborSerializationImplemented(schemaId = "")
class HybridPublicKey(
    val algorithm: Algorithm,
    val encoded: ByteString
) : PublicKey() {

    /** The ML-DSA public key component. */
    val pqPublicKey: MlDsaPublicKey

    /** The traditional ECDSA or EdDSA public key component. */
    val traditionalPublicKey: EcPublicKey

    init {
        val (pq, trad) = HybridSigning.deserializePublicKey(algorithm, encoded.toByteArray())
        pqPublicKey = pq
        traditionalPublicKey = trad
    }

    /**
     * Encodes this public key as a DER-encoded X.509 `SubjectPublicKeyInfo` structure.
     *
     * @return the DER-encoded `SubjectPublicKeyInfo` bytes.
     */
    fun toSubjectPublicKeyInfo(): ByteArray {
        val spec = HybridSigning.specFor(algorithm)
        return ASN1.encode(
            ASN1Sequence(
                listOf(
                    ASN1Sequence(listOf(ASN1ObjectIdentifier(spec.oid))),
                    ASN1BitString(0, encoded.toByteArray())
                )
            )
        )
    }

    /**
     * Encodes this public key in PEM format.
     *
     * @return the PEM-encoded `PUBLIC KEY` string.
     */
    @OptIn(ExperimentalEncodingApi::class)
    override fun toPem(): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN PUBLIC KEY-----\n")
        sb.append(Base64.Mime.encode(toSubjectPublicKeyInfo()))
        sb.append("\n-----END PUBLIC KEY-----\n")
        return sb.toString()
    }

    /**
     * Encodes this public key as a COSE `AKP` (Algorithm Key Pair) [CoseKey].
     *
     * @param additionalLabels optional additional COSE labels to include.
     * @return the encoded [CoseKey].
     */
    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey {
        val coseAlgId = algorithm.coseAlgorithmIdentifier
            ?: throw IllegalStateException("No COSE algorithm identifier for $algorithm")
        return CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_AKP.toDataItem()),
                Pair(Cose.COSE_KEY_ALG.toCoseLabel, coseAlgId.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel, encoded.toByteArray().toDataItem())
            ) + additionalLabels
        )
    }

    /**
     * Encodes this public key as an `AKP` JSON Web Key.
     *
     * @param additionalClaims optional additional JWK claims to include.
     * @return the encoded [JsonObject].
     */
    override suspend fun toJwk(
        additionalClaims: JsonObject?
    ): JsonObject {
        val joseAlgId = algorithm.joseAlgorithmIdentifier
            ?: throw IllegalStateException("No JOSE algorithm identifier for $algorithm")
        return buildJsonObject {
            put("kty", "AKP")
            put("alg", joseAlgId)
            put("pub", encoded.toByteArray().toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }
    }

    /**
     * Computes the RFC 7638 / RFC 9964 JWK Thumbprint for this `AKP` key.
     *
     * @param digestAlgorithm the hash algorithm to apply (for example [Algorithm.SHA256]).
     * @return the digest bytes of the canonical JWK thumbprint input.
     */
    override suspend fun toJwkThumbprint(digestAlgorithm: Algorithm): ByteString {
        val joseAlgId = algorithm.joseAlgorithmIdentifier
            ?: throw IllegalStateException("No JOSE algorithm identifier for $algorithm")
        val json = """{"alg":"$joseAlgId","kty":"AKP","pub":"${encoded.toByteArray().toBase64Url()}"}"""
        return ByteString(Crypto.digest(digestAlgorithm, json.encodeToByteArray()))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as HybridPublicKey
        if (algorithm != other.algorithm) return false
        return encoded == other.encoded
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + encoded.hashCode()
        return result
    }

    override fun toString(): String = "HybridPublicKey(algorithm=$algorithm, size=${encoded.size})"

    companion object {
        /**
         * Decodes a [HybridPublicKey] from DER-encoded `SubjectPublicKeyInfo` bytes.
         *
         * @param spkiBytes the DER-encoded `SubjectPublicKeyInfo` bytes.
         * @return the decoded [HybridPublicKey].
         * @throws IllegalArgumentException if [spkiBytes] is malformed or uses an unsupported OID.
         */
        @Throws(IllegalArgumentException::class)
        fun fromSubjectPublicKeyInfo(spkiBytes: ByteArray): HybridPublicKey {
            val spki = ASN1.decode(spkiBytes) as ASN1Sequence
            val algId = spki.elements[0] as ASN1Sequence
            val oid = (algId.elements[0] as ASN1ObjectIdentifier).oid
            val spec = HybridSigning.specForOid(oid)
            val pubKeyBits = (spki.elements[1] as ASN1BitString)
            require(pubKeyBits.numUnusedBits == 0) {
                "Unexpected unused bits in composite SubjectPublicKeyInfo: ${pubKeyBits.numUnusedBits}"
            }
            return HybridPublicKey(spec.algorithm, ByteString(pubKeyBits.value))
        }

        /**
         * Decodes a [HybridPublicKey] from a PEM-encoded `PUBLIC KEY` string.
         *
         * @param pemEncoding the PEM-encoded public key string.
         * @return the decoded [HybridPublicKey].
         * @throws IllegalArgumentException if [pemEncoding] is malformed or uses an unsupported OID.
         */
        @Throws(IllegalArgumentException::class)
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String): HybridPublicKey {
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .trim()
            )
            return fromSubjectPublicKeyInfo(encoded)
        }

        /**
         * Decodes a [HybridPublicKey] from a COSE `AKP` [CoseKey].
         *
         * @param coseKey the COSE Key to decode.
         * @return the decoded [HybridPublicKey].
         * @throws IllegalArgumentException if [coseKey] is not a valid composite AKP key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromCoseKey(coseKey: CoseKey): HybridPublicKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_AKP.toDataItem()) {
                "Key type must be AKP (7), got ${coseKey.keyType}"
            }
            val pubBytes = coseKey.labels[Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel]?.asBstr
                ?: throw IllegalArgumentException("Missing pub key in COSE Key")
            val algNum = coseKey.labels[Cose.COSE_KEY_ALG.toCoseLabel]?.asNumber?.toInt()
            val algorithm = if (algNum != null) {
                Algorithm.fromCoseAlgorithmIdentifier(algNum).also {
                    require(it.isHybridSigning) { "Not a composite signature algorithm: $it" }
                }
            } else {
                HybridSigning.specForPublicKeySizeOrNull(pubBytes.size)?.algorithm
                    ?: throw IllegalArgumentException(
                        "Cannot determine composite algorithm from public key size ${pubBytes.size}"
                    )
            }
            return HybridPublicKey(algorithm, ByteString(pubBytes))
        }

        /**
         * Decodes a [HybridPublicKey] from an `AKP` JSON Web Key.
         *
         * @param jwk the JSON Web Key object.
         * @return the decoded [HybridPublicKey].
         * @throws IllegalArgumentException if [jwk] is malformed or not a composite AKP key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromJwk(jwk: JsonObject): HybridPublicKey {
            val kty = jwk["kty"]?.jsonPrimitive?.content
            require(kty == "AKP") { "Key type must be AKP, got $kty" }
            val algStr = jwk["alg"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing alg in JWK")
            val algorithm = Algorithm.fromJoseAlgorithmIdentifier(algStr)
            require(algorithm.isHybridSigning) { "Not a composite signature algorithm: $algStr" }
            val pubBase64 = jwk["pub"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing pub in JWK")
            return HybridPublicKey(algorithm, ByteString(pubBase64.fromBase64Url()))
        }

        /**
         * Decodes a [HybridPublicKey] from a CBOR [DataItem] encoding a COSE Key.
         *
         * @param dataItem the CBOR data item.
         * @return the decoded [HybridPublicKey].
         * @throws IllegalArgumentException if [dataItem] does not encode a valid composite AKP key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): HybridPublicKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
