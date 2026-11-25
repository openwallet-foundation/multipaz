package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url

/**
 * A PQ/T hybrid key encapsulation mechanism (KEM) public key as specified in
 * `draft-irtf-cfrg-concrete-hybrid-kems-04` and `draft-ietf-hpke-pq-05`.
 *
 * The raw key bytes consist of the FIPS 203 ML-KEM encapsulation key immediately followed by
 * the serialized traditional group element (`ek_PQ || ek_T`).
 *
 * @property algorithm the hybrid KEM [Algorithm] ([Algorithm.MLKEM768_P256],
 *   [Algorithm.MLKEM1024_P384], or [Algorithm.MLKEM768_X25519]).
 * @property encoded the raw concatenated encapsulation key bytes.
 */
@CborSerializationImplemented(schemaId = "")
class HybridKemPublicKey(
    val algorithm: Algorithm,
    val encoded: ByteString
) : PublicKey() {

    /** The ML-KEM public key component. */
    val pqPublicKey: MlKemPublicKey

    /** The traditional ECDH public key component. */
    val traditionalPublicKey: EcPublicKey

    init {
        require(algorithm.isHybridKem) {
            "Algorithm $algorithm is not a hybrid KEM algorithm"
        }
        val (pq, trad) = HybridKem.deserializePublicKey(algorithm, encoded.toByteArray())
        pqPublicKey = pq
        traditionalPublicKey = trad
    }

    /**
     * Encodes this public key as a COSE `AKP` (Algorithm Key Pair) [CoseKey].
     *
     * @param additionalLabels optional additional COSE labels to include.
     * @return the encoded [CoseKey].
     */
    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey =
        CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_AKP.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel, encoded.toByteArray().toDataItem()),
            ) + additionalLabels
        )

    /**
     * Throws [UnsupportedOperationException] because X.509/PEM OIDs are not defined for
     * CFRG hybrid KEMs (`draft-irtf-cfrg-concrete-hybrid-kems-04`).
     *
     * @throws UnsupportedOperationException always.
     */
    @Throws(UnsupportedOperationException::class)
    override fun toPem(): String {
        throw UnsupportedOperationException(
            "PEM encoding is not defined for CFRG hybrid KEM public keys ($algorithm)"
        )
    }

    /**
     * Encodes this public key as an `AKP` JSON Web Key.
     *
     * @param additionalClaims optional additional JWK claims to include.
     * @return the encoded [JsonObject].
     */
    override suspend fun toJwk(additionalClaims: JsonObject?): JsonObject {
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
        other as HybridKemPublicKey
        if (algorithm != other.algorithm) return false
        return encoded == other.encoded
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + encoded.hashCode()
        return result
    }

    override fun toString(): String = "HybridKemPublicKey(algorithm=$algorithm, size=${encoded.size})"

    companion object {
        /**
         * Decodes a [HybridKemPublicKey] from a COSE `AKP` [CoseKey].
         *
         * @param coseKey the COSE Key to decode.
         * @return the decoded [HybridKemPublicKey].
         * @throws IllegalArgumentException if [coseKey] is malformed or not a valid hybrid KEM AKP key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromCoseKey(coseKey: CoseKey): HybridKemPublicKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_AKP.toDataItem()) {
                "Key type must be AKP (7), got ${coseKey.keyType}"
            }
            val pubBytes = coseKey.labels[Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel]?.asBstr
                ?: throw IllegalArgumentException("Missing pub key in COSE Key")
            val algNum = coseKey.labels[Cose.COSE_KEY_ALG.toCoseLabel]?.asNumber?.toInt()
            val algorithm = if (algNum != null) {
                Algorithm.fromCoseAlgorithmIdentifier(algNum).also {
                    require(it.isHybridKem) { "Not a hybrid KEM algorithm: $it" }
                }
            } else {
                HybridKem.specForPublicKeySizeOrNull(pubBytes.size)?.algorithm
                    ?: throw IllegalArgumentException(
                        "Cannot determine hybrid KEM algorithm from public key size ${pubBytes.size}"
                    )
            }
            return HybridKemPublicKey(algorithm, ByteString(pubBytes))
        }

        /**
         * Decodes a [HybridKemPublicKey] from an `AKP` JSON Web Key.
         *
         * @param jwk the JSON Web Key object.
         * @return the decoded [HybridKemPublicKey].
         * @throws IllegalArgumentException if [jwk] is malformed or not a hybrid KEM AKP key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromJwk(jwk: JsonObject): HybridKemPublicKey {
            val kty = jwk["kty"]?.jsonPrimitive?.content
            require(kty == "AKP") { "Key type must be AKP, got $kty" }
            val algStr = jwk["alg"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing alg in JWK")
            val algorithm = Algorithm.fromJoseAlgorithmIdentifier(algStr)
            require(algorithm.isHybridKem) { "Not a hybrid KEM algorithm: $algStr" }
            val pubBase64 = jwk["pub"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing pub in JWK")
            return HybridKemPublicKey(algorithm, ByteString(pubBase64.fromBase64Url()))
        }

        /**
         * Decodes a [HybridKemPublicKey] from a CBOR [DataItem] encoding a COSE Key.
         *
         * @param dataItem the CBOR data item.
         * @return the decoded [HybridKemPublicKey].
         * @throws IllegalArgumentException if [dataItem] does not encode a valid hybrid KEM AKP key.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): HybridKemPublicKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
