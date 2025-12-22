package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.util.toBase64Url
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.io.encoding.Base64

/**
 * EC Public Key with Octet Key Pairs.
 *
 * @param x the X coordinate of the public key.
 */
data class EcPublicKeyOkp(
    override val curve: EcCurve,
    val x: ByteArray
) : EcPublicKey(curve) {

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey =
        CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_OKP.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_CRV.toCoseLabel, curve.coseCurveIdentifier.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_X.toCoseLabel, x.toDataItem())
            ) + additionalLabels
        )

    override suspend fun toJwk(
        additionalClaims: JsonObject?,
    ): JsonObject {
        return buildJsonObject {
            // Keep in lexicographic order for toJwkThumbprint()
            put("crv", curve.jwkName)
            put("kty", "OKP")
            put("x", x.toBase64Url())
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
            EcCurve.ED448 -> {
            }

            else -> throw IllegalArgumentException("Unsupported curve $curve")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as EcPublicKeyOkp

        if (curve != other.curve) return false
        if (!x.contentEquals(other.x)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = curve.hashCode()
        result = 31 * result + x.contentHashCode()
        return result
    }
}
