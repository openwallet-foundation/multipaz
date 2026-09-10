package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Null
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
 * An RSA Public Key.
 *
 * @param modulus the modulus (n) as an unsigned big-endian byte array.
 * @param publicExponent the public exponent (e) as an unsigned big-endian byte array.
 */
@CborSerializationImplemented(schemaId = "")
data class RsaPublicKey(
    val modulus: ByteArray,
    val publicExponent: ByteArray
) : PublicKey() {

    /**
     * The modulus (n) as an unsigned big-endian byte array.
     */
    val n: ByteArray get() = modulus

    /**
     * The public exponent (e) as an unsigned big-endian byte array.
     */
    val e: ByteArray get() = publicExponent

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey =
        CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_RSA.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_N.toCoseLabel, modulus.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_E.toCoseLabel, publicExponent.toDataItem())
            ) + additionalLabels
        )

    override suspend fun toJwk(additionalClaims: JsonObject?): JsonObject =
        buildJsonObject {
            put("e", publicExponent.toBase64Url())
            put("kty", "RSA")
            put("n", modulus.toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }

    override suspend fun toJwkThumbprint(digestAlgorithm: Algorithm): ByteString {
        // According to RFC 7638 Section 3.2, RSA keys use "e", "kty", "n" in lexicographic order.
        val jsonStr = buildJsonObject {
            put("e", publicExponent.toBase64Url())
            put("kty", "RSA")
            put("n", modulus.toBase64Url())
        }.toString()
        return ByteString(
            Crypto.digest(
                algorithm = digestAlgorithm,
                message = jsonStr.encodeToByteArray()
            )
        )
    }

    /**
     * Encode this public key as a DER-encoded PKCS#1 RSAPublicKey sequence.
     */
    fun toPkcs1(): ByteArray = ASN1.encode(
        ASN1Sequence(
            listOf(
                toPositiveAsn1Integer(modulus),
                toPositiveAsn1Integer(publicExponent)
            )
        )
    )

    /**
     * Encode this public key as a DER-encoded SubjectPublicKeyInfo sequence.
     */
    fun toSubjectPublicKeyInfo(): ByteArray = ASN1.encode(
        ASN1Sequence(
            listOf(
                ASN1Sequence(
                    listOf(
                        ASN1ObjectIdentifier(OID.RSA_ENCRYPTION.oid),
                        ASN1Null()
                    )
                ),
                ASN1BitString(0, toPkcs1())
            )
        )
    )

    @OptIn(ExperimentalEncodingApi::class)
    override fun toPem(): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN PUBLIC KEY-----\n")
        sb.append(Base64.Mime.encode(toSubjectPublicKeyInfo()))
        sb.append("\n-----END PUBLIC KEY-----\n")
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RsaPublicKey

        if (!modulus.contentEquals(other.modulus)) return false
        if (!publicExponent.contentEquals(other.publicExponent)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = modulus.contentHashCode()
        result = 31 * result + publicExponent.contentHashCode()
        return result
    }

    companion object {
        /**
         * Parses an RSA public key from a DER-encoded PKCS#1 `RSAPublicKey` sequence.
         *
         * @param derEncoding the DER-encoded bytes.
         * @return the parsed [RsaPublicKey].
         */
        fun fromPkcs1(derEncoding: ByteArray): RsaPublicKey {
            val seq = ASN1.decode(derEncoding) as ASN1Sequence
            val nInt = seq.elements[0] as ASN1Integer
            val eInt = seq.elements[1] as ASN1Integer
            return RsaPublicKey(stripLeadingZero(nInt.value), stripLeadingZero(eInt.value))
        }

        /**
         * Parses an RSA public key from a DER-encoded X.509 `SubjectPublicKeyInfo` sequence.
         *
         * @param derEncoding the DER-encoded bytes.
         * @return the parsed [RsaPublicKey].
         */
        fun fromSubjectPublicKeyInfo(derEncoding: ByteArray): RsaPublicKey {
            val seq = ASN1.decode(derEncoding) as ASN1Sequence
            val algorithmIdentifier = seq.elements[0] as ASN1Sequence
            val algorithmOid = (algorithmIdentifier.elements[0] as ASN1ObjectIdentifier).oid
            require(algorithmOid == OID.RSA_ENCRYPTION.oid) {
                "Expected OID ${OID.RSA_ENCRYPTION.oid} but found $algorithmOid"
            }
            val subjectPublicKey = (seq.elements[1] as ASN1BitString).value
            return fromPkcs1(subjectPublicKey)
        }

        /**
         * Parses an RSA public key from a PEM-encoded SubjectPublicKeyInfo string.
         *
         * @param pemEncoding the PEM-encoded string.
         * @return the parsed [RsaPublicKey].
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String): RsaPublicKey {
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .trim()
            )
            return fromSubjectPublicKeyInfo(encoded)
        }

        /**
         * Parses an RSA public key from a [CoseKey].
         *
         * @param coseKey the COSE_Key with RSA parameters.
         * @return the parsed [RsaPublicKey].
         */
        fun fromCoseKey(coseKey: CoseKey): RsaPublicKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_RSA.toDataItem()) {
                "Expected RSA key type in COSE Key"
            }
            val n = coseKey.labels[Cose.COSE_KEY_PARAM_N.toCoseLabel]!!.asBstr
            val e = coseKey.labels[Cose.COSE_KEY_PARAM_E.toCoseLabel]!!.asBstr
            return RsaPublicKey(n, e)
        }

        /**
         * Parses an RSA public key from a JSON Web Key (JWK) object.
         *
         * @param jwk the JSON object representing the RSA JWK.
         * @return the parsed [RsaPublicKey].
         */
        fun fromJwk(jwk: JsonObject): RsaPublicKey {
            require(jwk["kty"]?.jsonPrimitive?.content == "RSA") { "Expected kty=RSA" }
            val n = jwk["n"]!!.jsonPrimitive.content.fromBase64Url()
            val e = jwk["e"]!!.jsonPrimitive.content.fromBase64Url()
            return RsaPublicKey(n, e)
        }

        /**
         * Parses an RSA public key from a CBOR [DataItem] encoding a COSE_Key.
         *
         * @param dataItem the CBOR data item.
         * @return the parsed [RsaPublicKey].
         */
        fun fromDataItem(dataItem: DataItem): RsaPublicKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
