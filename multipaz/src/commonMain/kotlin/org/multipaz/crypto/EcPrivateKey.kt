package org.multipaz.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.X509SignedBuilder.Companion.getCurveAlgorithmSeq
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toHex
import kotlin.io.encoding.Base64

/**
 * An EC private key.
 *
 * @param curve the curve of the key.
 * @param d the private value of the key.
 */
@CborSerializationImplemented(schemaId = "SKWtVGTV5zyQis4cbfJ9Llls7qIMkcth6Fb3jnTael8")
sealed class EcPrivateKey(
    open val curve: EcCurve,
    open val d: ByteArray,
) {

    /**
     * Creates a [CoseKey] object for the key.
     *
     * The resulting object contains [Cose.COSE_KEY_KTY], [Cose.COSE_KEY_PARAM_CRV],
     * [Cose.COSE_KEY_PARAM_D], [Cose.COSE_KEY_PARAM_X] and also [Cose.COSE_KEY_PARAM_Y]
     * in case of a double-coordinate curve.
     *
     * @param additionalLabels additional labels to include.
     */
    abstract fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem> = emptyMap()): CoseKey

    /**
     * Encode this key in PEM format
     *
     * @return a PEM encoded string.
     */
    fun toPem(): String {
        // Generates this according to https://datatracker.ietf.org/doc/html/rfc5208
        //
        val privateKey = when (this) {
            is EcPrivateKeyDoubleCoordinate -> {
                val curveOid = when (curve) {
                    EcCurve.P256 -> OID.EC_CURVE_P256
                    EcCurve.P384 -> OID.EC_CURVE_P384
                    EcCurve.P521 -> OID.EC_CURVE_P521
                    EcCurve.BRAINPOOLP256R1 -> OID.EC_CURVE_BRAINPOOLP256R1
                    EcCurve.BRAINPOOLP320R1 -> OID.EC_CURVE_BRAINPOOLP320R1
                    EcCurve.BRAINPOOLP384R1 -> OID.EC_CURVE_BRAINPOOLP384R1
                    EcCurve.BRAINPOOLP512R1 -> OID.EC_CURVE_BRAINPOOLP512R1
                    else -> throw IllegalStateException("Unexpected curve $curve")
                }
                ASN1Sequence(listOf(
                    ASN1Integer(1L),
                    ASN1OctetString(d),
                    ASN1TaggedObject(
                        cls = ASN1TagClass.CONTEXT_SPECIFIC,
                        enc = ASN1Encoding.CONSTRUCTED,
                        tag = 0,
                        content = ASN1.encode(ASN1ObjectIdentifier(curveOid.oid))
                    )
                ))
            }
            is EcPrivateKeyOkp -> {
                ASN1OctetString(d)
            }
        }
        val privateKeyInfoSeq = ASN1Sequence(listOf(
            ASN1Integer(0),
            curve.getCurveAlgorithmSeq(),
            ASN1OctetString(ASN1.encode(privateKey))
        ))
        val sb = StringBuilder()
        sb.append("-----BEGIN PRIVATE KEY-----\n")
        sb.append(Base64.Mime.encode(ASN1.encode(privateKeyInfoSeq)))
        sb.append("\n-----END PRIVATE KEY-----\n")
        return sb.toString()
    }

    fun toDataItem(): DataItem = toCoseKey().toDataItem()

    /**
     * Encodes the private key as a JSON Web Key according to
     * [RFC 7517](https://datatracker.ietf.org/doc/html/rfc7517).
     *
     * By default this only includes the `kty`, `crv`, `d`, `x`, `y` (if double-coordinate) claims,
     * use [additionalClaims] to include other claims.
     *
     * @param additionalClaims additional claims to include or `null`.
     * @return a JSON Web Key.
     */
    abstract fun toJwk(
        additionalClaims: JsonObject? = null
    ): JsonObject

    /**
     * The public part of the key.
     */
    abstract val publicKey: EcPublicKey

    companion object {
        /**
         * Creates an [EcPrivateKey] from a PEM encoded string.
         *
         * @param pemEncoding the PEM encoded string.
         * @param publicKey the corresponding public key.
         * @return a new [EcPrivateKey]
         */
        fun fromPem(pemEncoding: String, publicKey: EcPublicKey): EcPrivateKey {
            // Parses this according to https://datatracker.ietf.org/doc/html/rfc5208
            //
            val encoded = Base64.Mime.decode(pemEncoding
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .trim())
            val privateKeyInfo = ASN1.decode(encoded) as ASN1Sequence

            val version = (privateKeyInfo.elements[0] as ASN1Integer).toLong()
            if (version != 0L) {
                throw IllegalArgumentException("Unexpected version $version")
            }
            val privateKeyAlgorithm = privateKeyInfo.elements[1] as ASN1Sequence
            val algorithm = privateKeyAlgorithm.elements[0] as ASN1ObjectIdentifier
            val curve = when (algorithm.oid) {
                OID.EC_PUBLIC_KEY.oid -> {
                    val ecCurveString = privateKeyAlgorithm.elements[1] as ASN1ObjectIdentifier
                    when (ecCurveString.oid) {
                        "1.2.840.10045.3.1.7" -> EcCurve.P256
                        "1.3.132.0.34" -> EcCurve.P384
                        "1.3.132.0.35" -> EcCurve.P521
                        "1.3.36.3.3.2.8.1.1.7" -> EcCurve.BRAINPOOLP256R1
                        "1.3.36.3.3.2.8.1.1.9" -> EcCurve.BRAINPOOLP320R1
                        "1.3.36.3.3.2.8.1.1.11" -> EcCurve.BRAINPOOLP384R1
                        "1.3.36.3.3.2.8.1.1.13" -> EcCurve.BRAINPOOLP512R1
                        else -> throw IllegalStateException("Unexpected curve OID ${ecCurveString.oid}")
                    }
                }
                "1.3.101.110" -> EcCurve.X25519
                "1.3.101.111" -> EcCurve.X448
                "1.3.101.112" -> EcCurve.ED25519
                "1.3.101.113" -> EcCurve.ED448
                else -> throw IllegalStateException("Unexpected OID ${algorithm.oid}")
            }
            return when (curve) {
                EcCurve.P256,
                EcCurve.P384,
                EcCurve.P521,
                EcCurve.BRAINPOOLP256R1,
                EcCurve.BRAINPOOLP320R1,
                EcCurve.BRAINPOOLP384R1,
                EcCurve.BRAINPOOLP512R1 -> {
                    val privateKeyOctetString = privateKeyInfo.elements[2] as ASN1OctetString
                    val privateKey = ASN1.decode(privateKeyOctetString.value) as ASN1Sequence
                    val emVersion = (privateKey.elements[0] as ASN1Integer).toLong()
                    if (emVersion != 1L) {
                        throw IllegalArgumentException("Unexpected version $emVersion")
                    }
                    val keyMaterial = (privateKey.elements[1] as ASN1OctetString).value
                    publicKey as EcPublicKeyDoubleCoordinate
                    EcPrivateKeyDoubleCoordinate(
                        curve = curve,
                        d = keyMaterial,
                        x = publicKey.x,
                        y = publicKey.y
                    )
                }
                EcCurve.ED25519,
                EcCurve.X25519,
                EcCurve.ED448,
                EcCurve.X448 -> {
                    val privateKeyOctetString = privateKeyInfo.elements[2] as ASN1OctetString
                    val keyMaterial = (ASN1.decode(privateKeyOctetString.value) as ASN1OctetString).value
                    publicKey as EcPublicKeyOkp
                    EcPrivateKeyOkp(
                        curve = curve,
                        d = keyMaterial,
                        x = publicKey.x,
                    )
                }
            }
        }

        /**
         * Gets a [EcPrivateKey] from a COSE Key.
         *
         * @param coseKey the COSE Key.
         * @return the private key.
         */
        fun fromCoseKey(coseKey: CoseKey): EcPrivateKey =
            when (coseKey.keyType) {
                Cose.COSE_KEY_TYPE_EC2.toDataItem() -> {
                    val curve = EcCurve.fromInt(
                        coseKey.labels[Cose.COSE_KEY_PARAM_CRV.toCoseLabel]!!.asNumber.toInt()
                    )
                    val keySizeOctets = (curve.bitSize + 7) / 8
                    val x = coseKey.labels[Cose.COSE_KEY_PARAM_X.toCoseLabel]!!.asBstr
                    val y = coseKey.labels[Cose.COSE_KEY_PARAM_Y.toCoseLabel]!!.asBstr
                    val d = coseKey.labels[Cose.COSE_KEY_PARAM_D.toCoseLabel]!!.asBstr
                    check(x.size == keySizeOctets)
                    check(y.size == keySizeOctets)
                    EcPrivateKeyDoubleCoordinate(curve, d, x, y)
                }

                Cose.COSE_KEY_TYPE_OKP.toDataItem() -> {
                    val curve = EcCurve.fromInt(
                        coseKey.labels[Cose.COSE_KEY_PARAM_CRV.toCoseLabel]!!.asNumber.toInt()
                    )
                    val x = coseKey.labels[Cose.COSE_KEY_PARAM_X.toCoseLabel]!!.asBstr
                    val d = coseKey.labels[Cose.COSE_KEY_PARAM_D.toCoseLabel]!!.asBstr
                    EcPrivateKeyOkp(curve, d, x)
                }

                else -> {
                    throw IllegalArgumentException("Unknown key type ${coseKey.keyType}")
                }
            }

        /**
         * Creates a [EcPrivateKey] from a JSON Web Key according to
         * [RFC 7517](https://datatracker.ietf.org/doc/html/rfc7517).
         *
         * @param jwk the JSON Web Key.
         * @return the private key.
         */
        fun fromJwk(jwk: JsonObject): EcPrivateKey {
            return when (val kty = jwk["kty"]!!.jsonPrimitive.content) {
                "OKP" -> {
                    EcPrivateKeyOkp(
                        EcCurve.fromJwkName(jwk["crv"]!!.jsonPrimitive.content),
                        jwk["d"]!!.jsonPrimitive.content.fromBase64Url(),
                        jwk["x"]!!.jsonPrimitive.content.fromBase64Url()
                    )
                }
                "EC" -> {
                    EcPrivateKeyDoubleCoordinate(
                        EcCurve.fromJwkName(jwk["crv"]!!.jsonPrimitive.content),
                        jwk["d"]!!.jsonPrimitive.content.fromBase64Url(),
                        jwk["x"]!!.jsonPrimitive.content.fromBase64Url(),
                        jwk["y"]!!.jsonPrimitive.content.fromBase64Url()
                    )
                }
                else -> throw IllegalArgumentException("Unsupported key type $kty")
            }
        }

        fun fromDataItem(dataItem: DataItem): EcPrivateKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
