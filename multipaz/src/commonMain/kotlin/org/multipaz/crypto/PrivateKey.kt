package org.multipaz.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An asymmetric private key.
 */
@CborSerializationImplemented(schemaId = "")
sealed class PrivateKey : AutoCloseable {

    /**
     * `true` if this key has been closed/destroyed, `false` otherwise.
     */
    abstract val isDestroyed: Boolean

    /**
     * Securely zeroes out the private key material in memory and marks this key as destroyed.
     * Subsequent cryptographic operations or property accesses on this instance will throw [IllegalStateException].
     */
    abstract override fun close()

    /**
     * Cryptographic alias for [close].
     */
    fun destroy() = close()

    /**
     * Throws [IllegalStateException] if this key has already been destroyed.
     */
    protected fun checkNotDestroyed() {
        check(!isDestroyed) { "PrivateKey has already been destroyed." }
    }

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
     * Serializes this private key as a CBOR [DataItem] encoding a COSE_Key.
     *
     * @return the [DataItem] representation.
     */
    fun toDataItem(): DataItem = toCoseKey().toDataItem()

    /**
     * Encodes the private key as a JSON Web Key according to
     * [RFC 7517](https://datatracker.ietf.org/doc/html/rfc7517).
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
    abstract val publicKey: PublicKey

    companion object {
        /**
         * Creates a [PrivateKey] from a PEM encoded string.
         *
         * @param pemEncoding the PEM encoded string.
         * @param publicKey the corresponding public key or null.
         * @return a new [PrivateKey]
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String, publicKey: PublicKey? = null): PrivateKey {
            if (pemEncoding.contains("-----BEGIN RSA PRIVATE KEY-----")) {
                return RsaPrivateKey.fromPem(pemEncoding, publicKey as? RsaPublicKey)
            }
            val encoded = Base64.Mime.decode(
                pemEncoding
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .trim()
            )
            val privateKeyInfo = ASN1.decode(encoded) as ASN1Sequence
            val version = (privateKeyInfo.elements[0] as ASN1Integer).toLong()
            if (version != 0L) {
                throw IllegalArgumentException("Unexpected version $version")
            }
            val privateKeyAlgorithm = privateKeyInfo.elements[1] as ASN1Sequence
            val algorithm = privateKeyAlgorithm.elements[0] as ASN1ObjectIdentifier
            return when (algorithm.oid) {
                OID.RSA_ENCRYPTION.oid -> {
                    RsaPrivateKey.fromPem(pemEncoding, publicKey as? RsaPublicKey)
                }
                OID.ML_DSA_44.oid,
                OID.ML_DSA_65.oid,
                OID.ML_DSA_87.oid -> {
                    require(publicKey == null || publicKey is MlDsaPublicKey) {
                        "Public key must be an MlDsaPublicKey for ML-DSA private key"
                    }
                    MlDsaPrivateKey.fromPem(
                        pemEncoding,
                        (publicKey as? MlDsaPublicKey)
                            ?: throw IllegalArgumentException("publicKey must be provided for ML-DSA private key")
                    )
                }
                OID.ML_KEM_512.oid,
                OID.ML_KEM_768.oid,
                OID.ML_KEM_1024.oid -> {
                    require(publicKey == null || publicKey is MlKemPublicKey) {
                        "Public key must be an MlKemPublicKey for ML-KEM private key"
                    }
                    MlKemPrivateKey.fromPem(
                        pemEncoding,
                        (publicKey as? MlKemPublicKey)
                            ?: throw IllegalArgumentException("publicKey must be provided for ML-KEM private key")
                    )
                }
                OID.EC_PUBLIC_KEY.oid,
                "1.3.101.110",
                "1.3.101.111",
                "1.3.101.112",
                "1.3.101.113" -> {
                    require(publicKey == null || publicKey is EcPublicKey) {
                        "Public key must be an EcPublicKey for EC private key"
                    }
                    EcPrivateKey.fromPem(
                        pemEncoding,
                        (publicKey as? EcPublicKey)
                            ?: throw IllegalArgumentException("publicKey must be provided for EC private key")
                    )
                }
                else -> throw IllegalArgumentException("Unexpected OID ${algorithm.oid}")
            }
        }

        /**
         * Gets a [PrivateKey] from a COSE Key.
         *
         * @param coseKey the COSE Key.
         * @return the private key.
         */
        fun fromCoseKey(coseKey: CoseKey): PrivateKey =
            when (coseKey.keyType) {
                Cose.COSE_KEY_TYPE_EC2.toDataItem(),
                Cose.COSE_KEY_TYPE_OKP.toDataItem() -> {
                    EcPrivateKey.fromCoseKey(coseKey)
                }
                Cose.COSE_KEY_TYPE_RSA.toDataItem() -> {
                    RsaPrivateKey.fromCoseKey(coseKey)
                }
                Cose.COSE_KEY_TYPE_AKP.toDataItem() -> {
                    val algNum = coseKey.labels[Cose.COSE_KEY_ALG.toCoseLabel]?.asNumber?.toInt()
                    val alg = algNum?.let { Algorithm.fromCoseAlgorithmIdentifier(it) }
                    if (alg != null && alg.isSigning) {
                        MlDsaPrivateKey.fromCoseKey(coseKey)
                    } else if (alg != null && alg.isKeyEncapsulation) {
                        MlKemPrivateKey.fromCoseKey(coseKey)
                    } else {
                        val pubSize = coseKey.labels[Cose.COSE_KEY_PARAM_PUB_KEY.toCoseLabel]?.asBstr?.size ?: 0
                        if (pubSize in listOf(1312, 1952, 2592)) {
                            MlDsaPrivateKey.fromCoseKey(coseKey)
                        } else if (pubSize in listOf(800, 1184, 1568)) {
                            MlKemPrivateKey.fromCoseKey(coseKey)
                        } else {
                            throw IllegalArgumentException("Cannot determine AKP key type for size $pubSize")
                        }
                    }
                }
                else -> {
                    throw IllegalArgumentException("Unknown key type ${coseKey.keyType}")
                }
            }

        /**
         * Creates a [PrivateKey] from a JSON Web Key according to
         * [RFC 7517](https://datatracker.ietf.org/doc/html/rfc7517).
         *
         * @param jwk the JSON Web Key.
         * @return the private key.
         */
        fun fromJwk(jwk: JsonObject): PrivateKey {
            return when (val kty = jwk["kty"]?.jsonPrimitive?.content) {
                "OKP", "EC" -> EcPrivateKey.fromJwk(jwk)
                "RSA" -> RsaPrivateKey.fromJwk(jwk)
                "AKP" -> {
                    val alg = jwk["alg"]?.jsonPrimitive?.content
                    if (alg?.startsWith("ML-DSA") == true) {
                        MlDsaPrivateKey.fromJwk(jwk)
                    } else if (alg?.startsWith("ML-KEM") == true) {
                        MlKemPrivateKey.fromJwk(jwk)
                    } else {
                        throw IllegalArgumentException("Unsupported AKP algorithm $alg")
                    }
                }
                else -> throw IllegalArgumentException("Unsupported key type $kty")
            }
        }

        /**
         * Parses a [PrivateKey] from a CBOR [DataItem] encoding a COSE_Key.
         *
         * @param dataItem the [DataItem] encoding the key.
         * @return the parsed [PrivateKey].
         */
        fun fromDataItem(dataItem: DataItem): PrivateKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
