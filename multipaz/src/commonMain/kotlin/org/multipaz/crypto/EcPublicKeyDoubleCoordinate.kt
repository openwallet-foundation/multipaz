package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.util.toBase64Url
import kotlin.io.encoding.Base64

/**
 * EC Public Key with two coordinates.
 *
 * @param x the X coordinate of the public key.
 * @param y the Y coordinate of the public key.
 */
data class EcPublicKeyDoubleCoordinate(
    override val curve: EcCurve,
    val x: ByteArray,
    val y: ByteArray
) : EcPublicKey(curve) {

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey =
        CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_EC2.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_CRV.toCoseLabel, curve.coseCurveIdentifier.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_X.toCoseLabel, x.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_Y.toCoseLabel, y.toDataItem())
            ) + additionalLabels
        )

    override suspend fun toJwk(
        additionalClaims: JsonObject?,
    ): JsonObject {
        return buildJsonObject {
            // Keep in lexicographic order for toJwkThumbprint()
            put("crv", curve.jwkName)
            put("kty", "EC")
            put("x", x.toBase64Url())
            put("y", y.toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }
    }

    override suspend fun toJwkThumbprint(digestAlgorithm: Algorithm): ByteString {
        // See https://datatracker.ietf.org/doc/html/rfc7638#section-3 for the algorithm
        val jsonStr = Json {
            prettyPrint = false
        }.encodeToString(toJwk(additionalClaims = null))
        return ByteString(
            Crypto.digest(
                algorithm = digestAlgorithm,
                message = jsonStr.encodeToByteArray()
            )
        )
    }

    init {
        when (curve) {
            EcCurve.ED25519,
            EcCurve.X25519,
            EcCurve.X448,
            EcCurve.ED448 -> throw IllegalArgumentException("Unsupported curve $curve")
            else -> {}
        }
        check(x.size == (curve.bitSize + 7)/8)
        check(y.size == (curve.bitSize + 7)/8)
    }

    /**
     * The uncompressed point encoding of the key.
     *
     * This is according to SEC 1: Elliptic Curve Cryptography, section 2.3.3
     * Elliptic-Curve-Point-to-Octet-String Conversion.
     *
     * This is the reverse operation of [fromUncompressedPointEncoding].
     */
    val asUncompressedPointEncoding: ByteArray
        get() {
            val builder = ByteStringBuilder()
            builder.append(0x04)
            builder.append(x)
            builder.append(y)
            return builder.toByteString().toByteArray()
        }

    companion object {
        /**
         * Creates a key from uncompressed point encoding.
         *
         * This is according to SEC 1: Elliptic Curve Cryptography, section 2.3.3
         * Elliptic-Curve-Point-to-Octet-String Conversion.
         *
         * This is the reverse of [asUncompressedPointEncoding].
         *
         * @param curve the curve.
         * @param encoded the encoded bytes.
         */
        fun fromUncompressedPointEncoding(
            curve: EcCurve,
            encoded: ByteArray): EcPublicKeyDoubleCoordinate {
            val coordinateSize = (curve.bitSize + 7)/8
            check(encoded.size == 1 + 2*coordinateSize)
            require(encoded[0].toInt() == 0x04)
            return EcPublicKeyDoubleCoordinate(
                curve,
                encoded.sliceArray(IntRange(1, 1 + coordinateSize - 1)),
                encoded.sliceArray(IntRange(1 + coordinateSize, encoded.size - 1))
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as EcPublicKeyDoubleCoordinate

        if (curve != other.curve) return false
        if (!x.contentEquals(other.x)) return false
        if (!y.contentEquals(other.y)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = curve.hashCode()
        result = 31 * result + x.contentHashCode()
        result = 31 * result + y.contentHashCode()
        return result
    }
}
