package org.multipaz.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

/**
 * EC Private Key with two coordinates.
 *
 * @param x the X coordinate of the public key.
 * @param y the Y coordinate of the public key.
 */
class EcPrivateKeyDoubleCoordinate(
    override val curve: EcCurve,
    d: ByteArray,
    val x: ByteArray,
    val y: ByteArray
) : EcPrivateKey(curve, d) {

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey {
        checkNotDestroyed()
        return CoseKey(
            mapOf(
                Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_EC2.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_CRV.toCoseLabel, curve.coseCurveIdentifier.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_D.toCoseLabel, d.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_X.toCoseLabel, x.toDataItem()),
                Pair(Cose.COSE_KEY_PARAM_Y.toCoseLabel, y.toDataItem())
            ) + additionalLabels
        )
    }

    override fun toJwk(
        additionalClaims: JsonObject?,
    ): JsonObject {
        checkNotDestroyed()
        return buildJsonObject {
            put("kty", "EC")
            put("crv", curve.jwkName)
            put("d", d.toBase64Url())
            put("x", x.toBase64Url())
            put("y", y.toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }
    }

    override val publicKey: EcPublicKey
        get() = EcPublicKeyDoubleCoordinate(curve, x, y)

    override fun toString(): String =
        "EcPrivateKeyDoubleCoordinate(curve=$curve, x=${x.toHex()}, y=${y.toHex()})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as EcPrivateKeyDoubleCoordinate

        if (curve != other.curve) return false
        if (isDestroyed || other.isDestroyed) {
            return isDestroyed == other.isDestroyed
        }
        if (!d.contentEquals(other.d)) return false
        if (!x.contentEquals(other.x)) return false
        if (!y.contentEquals(other.y)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = curve.hashCode()
        if (!isDestroyed) {
            result = 31 * result + d.contentHashCode()
        }
        result = 31 * result + x.contentHashCode()
        result = 31 * result + y.contentHashCode()
        return result
    }
}