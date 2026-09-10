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
 * An ML-KEM (FIPS 203) public key.
 *
 * @param algorithm the ML-KEM algorithm ([Algorithm.ML_KEM_512], [Algorithm.ML_KEM_768], or [Algorithm.ML_KEM_1024]).
 * @param encoded the raw encoded public key bytes as specified in FIPS 203.
 */
@CborSerializationImplemented(schemaId = "")
data class MlKemPublicKey(
    val algorithm: Algorithm,
    val encoded: ByteString
) : PublicKey() {

    init {
        require(algorithm in listOf(Algorithm.ML_KEM_512, Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024)) {
            "Algorithm $algorithm is not an ML-KEM algorithm"
        }
        val expectedSize = when (algorithm) {
            Algorithm.ML_KEM_512 -> 800
            Algorithm.ML_KEM_768 -> 1184
            Algorithm.ML_KEM_1024 -> 1568
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
            Algorithm.ML_KEM_512 -> OID.ML_KEM_512.oid
            Algorithm.ML_KEM_768 -> OID.ML_KEM_768.oid
            Algorithm.ML_KEM_1024 -> OID.ML_KEM_1024.oid
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
         * Decodes a [MlKemPublicKey] from SubjectPublicKeyInfo bytes.
         */
        fun fromSubjectPublicKeyInfo(spkiBytes: ByteArray): MlKemPublicKey {
            val seq = ASN1.decode(spkiBytes) as ASN1Sequence
            val algId = seq.elements[0] as ASN1Sequence
            val oid = (algId.elements[0] as ASN1ObjectIdentifier).oid
            val algorithm = when (oid) {
                OID.ML_KEM_512.oid -> Algorithm.ML_KEM_512
                OID.ML_KEM_768.oid -> Algorithm.ML_KEM_768
                OID.ML_KEM_1024.oid -> Algorithm.ML_KEM_1024
                else -> throw IllegalArgumentException("Unsupported ML-KEM OID: $oid")
            }
            val subjectPublicKey = (seq.elements[1] as ASN1BitString).value
            return MlKemPublicKey(algorithm, ByteString(subjectPublicKey))
        }

        /**
         * Decodes a [MlKemPublicKey] from a PEM encoded string.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String): MlKemPublicKey {
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .trim()
            )
            return fromSubjectPublicKeyInfo(encoded)
        }

        /**
         * Decodes a [MlKemPublicKey] from a COSE Key.
         */
        fun fromCoseKey(coseKey: CoseKey): MlKemPublicKey {
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
                    800 -> Algorithm.ML_KEM_512
                    1184 -> Algorithm.ML_KEM_768
                    1568 -> Algorithm.ML_KEM_1024
                    else -> throw IllegalArgumentException("Cannot determine ML-KEM algorithm from key size ${pubBytes.size}")
                }
            }
            return MlKemPublicKey(algorithm, ByteString(pubBytes))
        }

        /**
         * Decodes a [MlKemPublicKey] from a JSON Web Key.
         */
        fun fromJwk(jwk: JsonObject): MlKemPublicKey {
            val kty = jwk["kty"]?.jsonPrimitive?.content
            require(kty == "AKP") { "Expected kty to be AKP, got $kty" }
            val algStr = jwk["alg"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing alg in JWK")
            val algorithm = Algorithm.fromJoseAlgorithmIdentifier(algStr)
            val pubBase64 = jwk["pub"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing pub in JWK")
            return MlKemPublicKey(algorithm, ByteString(pubBase64.fromBase64Url()))
        }

        /**
         * Decodes a [MlKemPublicKey] from a CBOR data item.
         */
        fun fromDataItem(dataItem: DataItem): MlKemPublicKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
