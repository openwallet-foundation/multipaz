package org.multipaz.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Null
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
 * An RSA Private Key.
 *
 * @param publicKey the corresponding public key.
 * @param privateExponent the private exponent (d) as an unsigned big-endian byte array.
 * @param p the first prime factor (optional).
 * @param q the second prime factor (optional).
 * @param dp the first factor CRT exponent d mod (p-1) (optional).
 * @param dq the second factor CRT exponent d mod (q-1) (optional).
 * @param qInv the CRT coefficient (inverse of q) mod p (optional).
 */
@CborSerializationImplemented(schemaId = "")
data class RsaPrivateKey(
    override val publicKey: RsaPublicKey,
    val privateExponent: ByteArray,
    val p: ByteArray? = null,
    val q: ByteArray? = null,
    val dp: ByteArray? = null,
    val dq: ByteArray? = null,
    val qInv: ByteArray? = null
) : PrivateKey() {

    constructor(
        modulus: ByteArray,
        publicExponent: ByteArray,
        privateExponent: ByteArray,
        p: ByteArray? = null,
        q: ByteArray? = null,
        dp: ByteArray? = null,
        dq: ByteArray? = null,
        qInv: ByteArray? = null
    ) : this(
        publicKey = RsaPublicKey(modulus, publicExponent),
        privateExponent = privateExponent,
        p = p,
        q = q,
        dp = dp,
        dq = dq,
        qInv = qInv
    )

    /**
     * The modulus (n) of the public key as an unsigned big-endian byte array.
     */
    val modulus: ByteArray get() = publicKey.modulus

    /**
     * The public exponent (e) of the public key as an unsigned big-endian byte array.
     */
    val publicExponent: ByteArray get() = publicKey.publicExponent

    /**
     * The private exponent (d) as an unsigned big-endian byte array.
     */
    val d: ByteArray get() = privateExponent

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey {
        val labels = mutableMapOf<CoseLabel, DataItem>(
            Cose.COSE_KEY_KTY.toCoseLabel to Cose.COSE_KEY_TYPE_RSA.toDataItem(),
            Cose.COSE_KEY_PARAM_N.toCoseLabel to modulus.toDataItem(),
            Cose.COSE_KEY_PARAM_E.toCoseLabel to publicExponent.toDataItem(),
            Cose.COSE_KEY_PARAM_D_RSA.toCoseLabel to privateExponent.toDataItem()
        )
        p?.let { labels[Cose.COSE_KEY_PARAM_P.toCoseLabel] = it.toDataItem() }
        q?.let { labels[Cose.COSE_KEY_PARAM_Q.toCoseLabel] = it.toDataItem() }
        dp?.let { labels[Cose.COSE_KEY_PARAM_DP.toCoseLabel] = it.toDataItem() }
        dq?.let { labels[Cose.COSE_KEY_PARAM_DQ.toCoseLabel] = it.toDataItem() }
        qInv?.let { labels[Cose.COSE_KEY_PARAM_QINV.toCoseLabel] = it.toDataItem() }
        for ((k, v) in additionalLabels) {
            labels[k] = v
        }
        return CoseKey(labels)
    }

    override fun toJwk(additionalClaims: JsonObject?): JsonObject =
        buildJsonObject {
            put("kty", "RSA")
            put("n", modulus.toBase64Url())
            put("e", publicExponent.toBase64Url())
            put("d", privateExponent.toBase64Url())
            p?.let { put("p", it.toBase64Url()) }
            q?.let { put("q", it.toBase64Url()) }
            dp?.let { put("dp", it.toBase64Url()) }
            dq?.let { put("dq", it.toBase64Url()) }
            qInv?.let { put("qi", it.toBase64Url()) }
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }

    /**
     * Encode this private key as a DER-encoded PKCS#1 RSAPrivateKey sequence.
     * Requires CRT parameters to be present.
     */
    fun toPkcs1(): ByteArray {
        val prime1 = p ?: throw IllegalStateException("CRT parameter 'p' is required for PKCS#1 encoding")
        val prime2 = q ?: throw IllegalStateException("CRT parameter 'q' is required for PKCS#1 encoding")
        val exp1 = dp ?: throw IllegalStateException("CRT parameter 'dp' is required for PKCS#1 encoding")
        val exp2 = dq ?: throw IllegalStateException("CRT parameter 'dq' is required for PKCS#1 encoding")
        val coeff = qInv ?: throw IllegalStateException("CRT parameter 'qInv' is required for PKCS#1 encoding")

        return ASN1.encode(
            ASN1Sequence(
                listOf(
                    ASN1Integer(0L), // version
                    toPositiveAsn1Integer(modulus),
                    toPositiveAsn1Integer(publicExponent),
                    toPositiveAsn1Integer(privateExponent),
                    toPositiveAsn1Integer(prime1),
                    toPositiveAsn1Integer(prime2),
                    toPositiveAsn1Integer(exp1),
                    toPositiveAsn1Integer(exp2),
                    toPositiveAsn1Integer(coeff)
                )
            )
        )
    }

    /**
     * Encode this private key as a DER-encoded PKCS#8 PrivateKeyInfo sequence.
     */
    fun toPrivateKeyInfo(): ByteArray = ASN1.encode(
        ASN1Sequence(
            listOf(
                ASN1Integer(0L),
                ASN1Sequence(
                    listOf(
                        ASN1ObjectIdentifier(OID.RSA_ENCRYPTION.oid),
                        ASN1Null()
                    )
                ),
                ASN1OctetString(toPkcs1())
            )
        )
    )

    @OptIn(ExperimentalEncodingApi::class)
    override fun toPem(): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN PRIVATE KEY-----\n")
        sb.append(Base64.Mime.encode(toPrivateKeyInfo()))
        sb.append("\n-----END PRIVATE KEY-----\n")
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RsaPrivateKey

        if (publicKey != other.publicKey) return false
        if (!privateExponent.contentEquals(other.privateExponent)) return false
        if (p != null) {
            if (other.p == null || !p.contentEquals(other.p)) return false
        } else if (other.p != null) return false

        if (q != null) {
            if (other.q == null || !q.contentEquals(other.q)) return false
        } else if (other.q != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = publicKey.hashCode()
        result = 31 * result + privateExponent.contentHashCode()
        result = 31 * result + (p?.contentHashCode() ?: 0)
        result = 31 * result + (q?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Parses an RSA private key from a DER-encoded PKCS#1 `RSAPrivateKey` sequence.
         *
         * @param derEncoding the DER-encoded bytes.
         * @param publicKey optional known [RsaPublicKey].
         * @return the parsed [RsaPrivateKey].
         */
        fun fromPkcs1(derEncoding: ByteArray, publicKey: RsaPublicKey? = null): RsaPrivateKey {
            val seq = ASN1.decode(derEncoding) as ASN1Sequence
            val version = (seq.elements[0] as ASN1Integer).toLong()
            require(version == 0L) { "Unsupported RSAPrivateKey version $version" }
            val n = stripLeadingZero((seq.elements[1] as ASN1Integer).value)
            val e = stripLeadingZero((seq.elements[2] as ASN1Integer).value)
            val d = stripLeadingZero((seq.elements[3] as ASN1Integer).value)
            val p = if (seq.elements.size > 4) stripLeadingZero((seq.elements[4] as ASN1Integer).value) else null
            val q = if (seq.elements.size > 5) stripLeadingZero((seq.elements[5] as ASN1Integer).value) else null
            val dp = if (seq.elements.size > 6) stripLeadingZero((seq.elements[6] as ASN1Integer).value) else null
            val dq = if (seq.elements.size > 7) stripLeadingZero((seq.elements[7] as ASN1Integer).value) else null
            val qInv = if (seq.elements.size > 8) stripLeadingZero((seq.elements[8] as ASN1Integer).value) else null

            val pub = publicKey ?: RsaPublicKey(n, e)
            return RsaPrivateKey(pub, d, p, q, dp, dq, qInv)
        }

        /**
         * Parses an RSA private key from a DER-encoded PKCS#8 `PrivateKeyInfo` sequence.
         *
         * @param derEncoding the DER-encoded bytes.
         * @param publicKey optional known [RsaPublicKey].
         * @return the parsed [RsaPrivateKey].
         */
        fun fromPrivateKeyInfo(derEncoding: ByteArray, publicKey: RsaPublicKey? = null): RsaPrivateKey {
            val seq = ASN1.decode(derEncoding) as ASN1Sequence
            val version = (seq.elements[0] as ASN1Integer).toLong()
            require(version == 0L) { "Unsupported PrivateKeyInfo version $version" }
            val algorithmIdentifier = seq.elements[1] as ASN1Sequence
            val algorithmOid = (algorithmIdentifier.elements[0] as ASN1ObjectIdentifier).oid
            require(algorithmOid == OID.RSA_ENCRYPTION.oid) {
                "Expected algorithm OID ${OID.RSA_ENCRYPTION.oid} but found $algorithmOid"
            }
            val privateKeyOctetString = (seq.elements[2] as ASN1OctetString).value
            return fromPkcs1(privateKeyOctetString, publicKey)
        }

        /**
         * Parses an RSA private key from a PEM-encoded string (either PKCS#1 or PKCS#8).
         *
         * @param pemEncoding the PEM-encoded string.
         * @param publicKey optional known [RsaPublicKey].
         * @return the parsed [RsaPrivateKey].
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String, publicKey: RsaPublicKey? = null): RsaPrivateKey {
            if (pemEncoding.contains("-----BEGIN RSA PRIVATE KEY-----")) {
                val encoded = Base64.Mime.decode(
                    pemEncoding
                        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                        .replace("-----END RSA PRIVATE KEY-----", "")
                        .trim()
                )
                return fromPkcs1(encoded, publicKey)
            }
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .trim()
            )
            return fromPrivateKeyInfo(encoded, publicKey)
        }

        /**
         * Parses an RSA private key from a [CoseKey].
         *
         * @param coseKey the COSE_Key with RSA parameters.
         * @return the parsed [RsaPrivateKey].
         */
        fun fromCoseKey(coseKey: CoseKey): RsaPrivateKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_RSA.toDataItem()) {
                "Expected RSA key type in COSE Key"
            }
            val n = coseKey.labels[Cose.COSE_KEY_PARAM_N.toCoseLabel]!!.asBstr
            val e = coseKey.labels[Cose.COSE_KEY_PARAM_E.toCoseLabel]!!.asBstr
            val d = coseKey.labels[Cose.COSE_KEY_PARAM_D_RSA.toCoseLabel]!!.asBstr
            val p = coseKey.labels[Cose.COSE_KEY_PARAM_P.toCoseLabel]?.asBstr
            val q = coseKey.labels[Cose.COSE_KEY_PARAM_Q.toCoseLabel]?.asBstr
            val dp = coseKey.labels[Cose.COSE_KEY_PARAM_DP.toCoseLabel]?.asBstr
            val dq = coseKey.labels[Cose.COSE_KEY_PARAM_DQ.toCoseLabel]?.asBstr
            val qInv = coseKey.labels[Cose.COSE_KEY_PARAM_QINV.toCoseLabel]?.asBstr
            return RsaPrivateKey(RsaPublicKey(n, e), d, p, q, dp, dq, qInv)
        }

        /**
         * Parses an RSA private key from a JSON Web Key (JWK) object.
         *
         * @param jwk the JSON object representing the RSA private JWK.
         * @return the parsed [RsaPrivateKey].
         */
        fun fromJwk(jwk: JsonObject): RsaPrivateKey {
            require(jwk["kty"]?.jsonPrimitive?.content == "RSA") { "Expected kty=RSA" }
            val n = jwk["n"]!!.jsonPrimitive.content.fromBase64Url()
            val e = jwk["e"]!!.jsonPrimitive.content.fromBase64Url()
            val d = jwk["d"]!!.jsonPrimitive.content.fromBase64Url()
            val p = jwk["p"]?.jsonPrimitive?.content?.fromBase64Url()
            val q = jwk["q"]?.jsonPrimitive?.content?.fromBase64Url()
            val dp = jwk["dp"]?.jsonPrimitive?.content?.fromBase64Url()
            val dq = jwk["dq"]?.jsonPrimitive?.content?.fromBase64Url()
            val qInv = jwk["qi"]?.jsonPrimitive?.content?.fromBase64Url()
            return RsaPrivateKey(RsaPublicKey(n, e), d, p, q, dp, dq, qInv)
        }

        /**
         * Parses an RSA private key from a CBOR [DataItem] encoding a COSE_Key.
         *
         * @param dataItem the CBOR data item.
         * @return the parsed [RsaPrivateKey].
         */
        fun fromDataItem(dataItem: DataItem): RsaPrivateKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
