package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.OID
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An asymmetric public key.
 */
@CborSerializationImplemented(schemaId = "")
sealed class PublicKey {

    /**
     * Creates a [CoseKey] object for the key.
     *
     * @param additionalLabels additional labels to include.
     */
    abstract fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem> = emptyMap()): CoseKey

    /**
     * Encode this key in PEM format.
     *
     * @return a PEM encoded string.
     */
    abstract fun toPem(): String

    /**
     * Encodes the public key as a JSON Web Key according to
     * [RFC 7517](https://datatracker.ietf.org/doc/html/rfc7517).
     *
     * @param additionalClaims additional claims to include or `null`.
     * @return a JSON Web Key.
     */
    abstract suspend fun toJwk(
        additionalClaims: JsonObject? = null,
    ): JsonObject

    /**
     * Gets the Json Web Key Thumbprint.
     *
     * This is defined in [RFC 7638](https://datatracker.ietf.org/doc/html/rfc7638)
     *
     * @param digestAlgorithm the digest algorithm to use for creating the thumbprint.
     */
    abstract suspend fun toJwkThumbprint(digestAlgorithm: Algorithm): ByteString

    /**
     * Serializes this public key as a CBOR [DataItem] encoding a COSE_Key.
     *
     * @return the [DataItem] representation.
     */
    fun toDataItem(): DataItem = toCoseKey().toDataItem()

    /**
     * The [EcCurve] for this key if it is an elliptic curve key, or `null` if not an EC key.
     */
    open val curve: EcCurve?
        get() = null


    companion object {
        /**
         * Creates a [PublicKey] from a PEM encoded string.
         *
         * @param pemEncoding the PEM encoded string.
         * @return a new [PublicKey].
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String): PublicKey {
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .trim()
            )
            val subjectPublicKeyInfo = ASN1.decode(encoded) as ASN1Sequence
            val algorithmIdentifier = subjectPublicKeyInfo.elements[0] as ASN1Sequence
            val algorithmOid = (algorithmIdentifier.elements[0] as ASN1ObjectIdentifier).oid
            return when (algorithmOid) {
                OID.RSA_ENCRYPTION.oid -> {
                    RsaPublicKey.fromSubjectPublicKeyInfo(encoded)
                }
                OID.EC_PUBLIC_KEY.oid,
                "1.3.101.110",
                "1.3.101.111",
                "1.3.101.112",
                "1.3.101.113" -> {
                    EcPublicKey.fromPem(pemEncoding)
                }
                else -> throw IllegalArgumentException("Unsupported algorithm OID $algorithmOid")
            }
        }

        /**
         * Gets a [PublicKey] from a COSE Key.
         *
         * @param coseKey the COSE Key.
         * @return the public key.
         */
        fun fromCoseKey(coseKey: CoseKey): PublicKey =
            when (coseKey.keyType) {
                Cose.COSE_KEY_TYPE_EC2.toDataItem(),
                Cose.COSE_KEY_TYPE_OKP.toDataItem() -> {
                    EcPublicKey.fromCoseKey(coseKey)
                }
                Cose.COSE_KEY_TYPE_RSA.toDataItem() -> {
                    RsaPublicKey.fromCoseKey(coseKey)
                }
                else -> {
                    throw IllegalArgumentException("Unknown key type ${coseKey.keyType}")
                }
            }

        /**
         * Creates a [PublicKey] from a JSON Web Key according to
         * [RFC 7517](https://datatracker.ietf.org/doc/html/rfc7517).
         *
         * @param jwk the JSON Web Key.
         * @return the public key.
         */
        fun fromJwk(jwk: JsonObject): PublicKey {
            return when (val kty = jwk["kty"]?.jsonPrimitive?.content) {
                "OKP", "EC" -> EcPublicKey.fromJwk(jwk)
                "RSA" -> RsaPublicKey.fromJwk(jwk)
                else -> throw IllegalArgumentException("Unsupported key type $kty")
            }
        }

        /**
         * Parses a [PublicKey] from a CBOR [DataItem] encoding a COSE_Key.
         *
         * @param dataItem the [DataItem] encoding the key.
         * @return the parsed [PublicKey].
         */
        fun fromDataItem(dataItem: DataItem): PublicKey {
            return CoseKey.fromDataItem(dataItem).publicKey
        }
    }
}
