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
import org.multipaz.asn1.OID
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
 * An ML-DSA (FIPS 204) public key.
 *
 * @param algorithm the ML-DSA algorithm ([Algorithm.ML_DSA_44], [Algorithm.ML_DSA_65], or [Algorithm.ML_DSA_87]).
 * @param encoded the raw encoded public key bytes as specified in FIPS 204.
 */
@CborSerializationImplemented(schemaId = "")
data class MlDsaPublicKey(
    val algorithm: Algorithm,
    val encoded: ByteString
) : PublicKey() {

    init {
        require(algorithm in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            "Algorithm $algorithm is not an ML-DSA algorithm"
        }
        val expectedSize = when (algorithm) {
            Algorithm.ML_DSA_44 -> 1312
            Algorithm.ML_DSA_65 -> 1952
            Algorithm.ML_DSA_87 -> 2592
            else -> throw IllegalArgumentException()
        }
        require(encoded.size == expectedSize) {
            "Invalid public key size ${encoded.size} for $algorithm, expected $expectedSize"
        }
    }

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey =
        CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_AKP.toDataItem()),
                Pair(Cose.COSE_KEY_ALG.toCoseLabel, algorithm.coseAlgorithmIdentifier!!.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel, encoded.toByteArray().toDataItem()),
            ) + additionalLabels
        )

    override suspend fun toJwk(additionalClaims: JsonObject?): JsonObject =
        buildJsonObject {
            put("kty", "AKP")
            put("alg", algorithm.joseAlgorithmIdentifier!!)
            put("pub", encoded.toByteArray().toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }

    override suspend fun toJwkThumbprint(digestAlgorithm: Algorithm): ByteString {
        val jsonStr = buildJsonObject {
            put("alg", algorithm.joseAlgorithmIdentifier!!)
            put("kty", "AKP")
            put("pub", encoded.toByteArray().toBase64Url())
        }.toString()
        return ByteString(
            Crypto.digest(
                algorithm = digestAlgorithm,
                message = jsonStr.encodeToByteArray()
            )
        )
    }

    /**
     * Encodes this public key as a DER-encoded SubjectPublicKeyInfo sequence.
     */
    fun toSubjectPublicKeyInfo(): ByteArray {
        val oid = when (algorithm) {
            Algorithm.ML_DSA_44 -> OID.ML_DSA_44.oid
            Algorithm.ML_DSA_65 -> OID.ML_DSA_65.oid
            Algorithm.ML_DSA_87 -> OID.ML_DSA_87.oid
            else -> throw IllegalArgumentException()
        }
        return ASN1.encode(
            ASN1Sequence(
                listOf(
                    ASN1Sequence(listOf(ASN1ObjectIdentifier(oid))),
                    ASN1BitString(0, encoded.toByteArray())
                )
            )
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun toPem(): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN PUBLIC KEY-----\n")
        sb.append(Base64.Mime.encode(toSubjectPublicKeyInfo()))
        sb.append("\n-----END PUBLIC KEY-----\n")
        return sb.toString()
    }

    companion object {
        /**
         * Decodes a [MlDsaPublicKey] from SubjectPublicKeyInfo bytes.
         */
        fun fromSubjectPublicKeyInfo(spkiBytes: ByteArray): MlDsaPublicKey {
            val seq = ASN1.decode(spkiBytes) as ASN1Sequence
            val algId = seq.elements[0] as ASN1Sequence
            val oid = (algId.elements[0] as ASN1ObjectIdentifier).oid
            val algorithm = when (oid) {
                OID.ML_DSA_44.oid -> Algorithm.ML_DSA_44
                OID.ML_DSA_65.oid -> Algorithm.ML_DSA_65
                OID.ML_DSA_87.oid -> Algorithm.ML_DSA_87
                else -> throw IllegalArgumentException("Unsupported ML-DSA OID: $oid")
            }
            val subjectPublicKey = (seq.elements[1] as ASN1BitString).value
            return MlDsaPublicKey(algorithm, ByteString(subjectPublicKey))
        }

        /**
         * Decodes a [MlDsaPublicKey] from a PEM encoded string.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String): MlDsaPublicKey {
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .trim()
            )
            return fromSubjectPublicKeyInfo(encoded)
        }

        /**
         * Decodes a [MlDsaPublicKey] from a COSE Key.
         */
        fun fromCoseKey(coseKey: CoseKey): MlDsaPublicKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_AKP.toDataItem()) {
                "Key type must be AKP (7), got ${coseKey.keyType}"
            }
            val pubBytes = coseKey.labels[Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel]?.asBstr
                ?: throw IllegalArgumentException("Missing pub key in COSE Key")
            val algNum = coseKey.labels[Cose.COSE_KEY_ALG.toCoseLabel]?.asNumber?.toInt()
            val algorithm = if (algNum != null) {
                Algorithm.fromCoseAlgorithmIdentifier(algNum)
            } else {
                when (pubBytes.size) {
                    1312 -> Algorithm.ML_DSA_44
                    1952 -> Algorithm.ML_DSA_65
                    2592 -> Algorithm.ML_DSA_87
                    else -> throw IllegalArgumentException("Cannot determine ML-DSA algorithm from key size ${pubBytes.size}")
                }
            }
            return MlDsaPublicKey(algorithm, ByteString(pubBytes))
        }

        /**
         * Decodes a [MlDsaPublicKey] from a JSON Web Key.
         */
        fun fromJwk(jwk: JsonObject): MlDsaPublicKey {
            val kty = jwk["kty"]?.jsonPrimitive?.content
            require(kty == "AKP") { "Expected kty to be AKP, got $kty" }
            val algStr = jwk["alg"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing alg in JWK")
            val algorithm = Algorithm.fromJoseAlgorithmIdentifier(algStr)
            val pubBase64 = jwk["pub"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing pub in JWK")
            return MlDsaPublicKey(algorithm, ByteString(pubBase64.fromBase64Url()))
        }

        /**
         * Decodes a [MlDsaPublicKey] from a CBOR data item.
         */
        fun fromDataItem(dataItem: DataItem): MlDsaPublicKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
