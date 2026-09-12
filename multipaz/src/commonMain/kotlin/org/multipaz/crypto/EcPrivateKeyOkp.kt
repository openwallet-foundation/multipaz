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
 * EC Private Key with Octet Key Pairs.
 *
 * @param x the X coordinate of the public key.
 */
class EcPrivateKeyOkp(
    override val curve: EcCurve,
    d: ByteArray,
    val x: ByteArray
): EcPrivateKey(curve, d) {

    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey {
        checkNotDestroyed()
        return CoseKey(mapOf(
            Pair(Cose.COSE_KEY_KTY.toCoseLabel, Cose.COSE_KEY_TYPE_OKP.toDataItem()),
            Pair(Cose.COSE_KEY_PARAM_CRV.toCoseLabel, curve.coseCurveIdentifier.toDataItem()),
            Pair(Cose.COSE_KEY_PARAM_D.toCoseLabel, d.toDataItem()),
            Pair(Cose.COSE_KEY_PARAM_X.toCoseLabel, x.toDataItem())) + additionalLabels)
    }

    override fun toJwk(
        additionalClaims: JsonObject?,
    ): JsonObject {
        checkNotDestroyed()
        return buildJsonObject {
            put("kty", "OKP")
            put("crv", curve.jwkName)
            put("d", d.toBase64Url())
            put("x", x.toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }
    }

    override val publicKey: EcPublicKey
        get() = EcPublicKeyOkp(curve, x)

    override fun toString(): String =
        "EcPrivateKeyOkp(curve=$curve, x=${x.toHex()})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as EcPrivateKeyOkp

        if (curve != other.curve) return false
        if (isDestroyed || other.isDestroyed) {
            return isDestroyed == other.isDestroyed
        }
        if (!d.contentEquals(other.d)) return false
        if (!x.contentEquals(other.x)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = curve.hashCode()
        if (!isDestroyed) {
            result = 31 * result + d.contentHashCode()
        }
        result = 31 * result + x.contentHashCode()
        return result
    }
}