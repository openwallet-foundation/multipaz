package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
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
 * An ML-KEM (FIPS 203) private key.
 *
 * @param algorithm the ML-KEM algorithm ([Algorithm.ML_KEM_512], [Algorithm.ML_KEM_768], or [Algorithm.ML_KEM_1024]).
 * @param encoded the encoded private key bytes (either seed-only or expanded key).
 * @param publicKey the corresponding public key.
 */
@CborSerializationImplemented(schemaId = "")
data class MlKemPrivateKey(
    val algorithm: Algorithm,
    val encoded: ByteString,
    override val publicKey: MlKemPublicKey
) : PrivateKey() {

    init {
        require(algorithm in listOf(Algorithm.ML_KEM_512, Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024)) {
            "Algorithm $algorithm is not an ML-KEM algorithm"
        }
        require(publicKey.algorithm == algorithm) {
            "Public key algorithm ${publicKey.algorithm} does not match private key algorithm $algorithm"
        }
    }

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey =
        CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_AKP.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel, publicKey.encoded.toByteArray().toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_PRIV_KEY.toCoseLabel, encoded.toByteArray().toDataItem()),
            ) + additionalLabels
        )

    override fun toJwk(additionalClaims: JsonObject?): JsonObject =
        buildJsonObject {
            put("kty", "AKP")
            put("alg", algorithm.joseAlgorithmIdentifier!!)
            put("pub", publicKey.encoded.toByteArray().toBase64Url())
            put("priv", encoded.toByteArray().toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }

    /**
     * Encodes this private key as a DER-encoded PKCS#8 OneAsymmetricKey sequence.
     */
    fun toPkcs8(): ByteArray {
        val oid = when (algorithm) {
            Algorithm.ML_KEM_512 -> OID.ML_KEM_512.oid
            Algorithm.ML_KEM_768 -> OID.ML_KEM_768.oid
            Algorithm.ML_KEM_1024 -> OID.ML_KEM_1024.oid
            else -> throw IllegalArgumentException()
        }
        return ASN1.encode(
            ASN1Sequence(
                listOf(
                    ASN1Integer(0),
                    ASN1Sequence(listOf(ASN1ObjectIdentifier(oid))),
                    ASN1OctetString(encoded.toByteArray())
                )
            )
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun toPem(): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN PRIVATE KEY-----\n")
        sb.append(Base64.Mime.encode(toPkcs8()))
        sb.append("\n-----END PRIVATE KEY-----\n")
        return sb.toString()
    }

    /**
     * Encodes this private key as a DER-encoded PKCS#8 OneAsymmetricKey (PrivateKeyInfo) sequence.
     */
    fun toPrivateKeyInfo(): ByteArray = toPkcs8()

    companion object {
        /**
         * Decodes a [MlKemPrivateKey] from PKCS#8 bytes.
         *
         * @param pkcs8Bytes the PKCS#8 encoded bytes.
         * @param publicKey the public key if known, or null if to be reconstructed.
         */
        fun fromPkcs8(pkcs8Bytes: ByteArray, publicKey: MlKemPublicKey? = null): MlKemPrivateKey {
            val seq = ASN1.decode(pkcs8Bytes) as ASN1Sequence
            val algId = seq.elements[1] as ASN1Sequence
            val oid = (algId.elements[0] as ASN1ObjectIdentifier).oid
            val algorithm = when (oid) {
                OID.ML_KEM_512.oid -> Algorithm.ML_KEM_512
                OID.ML_KEM_768.oid -> Algorithm.ML_KEM_768
                OID.ML_KEM_1024.oid -> Algorithm.ML_KEM_1024
                else -> throw IllegalArgumentException("Unsupported ML-KEM OID: $oid")
            }
            val privKeyOctets = (seq.elements[2] as ASN1OctetString).value
            val effectivePublicKey = publicKey
                ?: throw IllegalArgumentException("publicKey is required to deserialize ML-KEM private key")
            return MlKemPrivateKey(algorithm, ByteString(privKeyOctets), effectivePublicKey)
        }

        /**
         * Decodes a [MlKemPrivateKey] from DER-encoded PrivateKeyInfo bytes.
         */
        fun fromPrivateKeyInfo(pkcs8Bytes: ByteArray, publicKey: MlKemPublicKey? = null): MlKemPrivateKey =
            fromPkcs8(pkcs8Bytes, publicKey)

        /**
         * Decodes a [MlKemPrivateKey] from a PEM encoded string.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String, publicKey: MlKemPublicKey? = null): MlKemPrivateKey {
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .trim()
            )
            return fromPkcs8(encoded, publicKey)
        }

        /**
         * Decodes a [MlKemPrivateKey] from a COSE Key.
         */
        fun fromCoseKey(coseKey: CoseKey): MlKemPrivateKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_AKP.toDataItem()) {
                "Key type must be AKP (7), got ${coseKey.keyType}"
            }
            val privBytes = coseKey.labels[Cose.COSE_KEY_PARAM_PRIV_KEY.toCoseLabel]?.asBstr
                ?: throw IllegalArgumentException("Missing priv key in COSE Key")
            val pubKey = MlKemPublicKey.fromCoseKey(coseKey)
            return MlKemPrivateKey(pubKey.algorithm, ByteString(privBytes), pubKey)
        }

        /**
         * Decodes a [MlKemPrivateKey] from a JSON Web Key.
         */
        fun fromJwk(jwk: JsonObject): MlKemPrivateKey {
            val pubKey = MlKemPublicKey.fromJwk(jwk)
            val privBase64 = jwk["priv"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing priv in JWK")
            return MlKemPrivateKey(pubKey.algorithm, ByteString(privBase64.fromBase64Url()), pubKey)
        }

        /**
         * Decodes a [MlKemPrivateKey] from a CBOR data item.
         */
        fun fromDataItem(dataItem: DataItem): MlKemPrivateKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
