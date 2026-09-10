package org.multipaz.crypto

import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap
import org.multipaz.util.toHex

/**
 * An Elliptic Curve Cryptography signature.
 *
 * @param r the R value.
 * @param s the S value.
 */
@CborSerializationImplemented(schemaId = "gNUtRYauOgwxNqDcHj-yzJE-3todHsBMhT7MObK1wCs")
data class EcSignature(
    val r: ByteArray,
    val s: ByteArray
) : Signature {
    override fun toCoseEncoded() = r + s

    fun toDataItem(): DataItem {
        return buildCborMap {
            put("r", r)
            put("s", s)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as EcSignature

        if (!r.contentEquals(other.r)) return false
        if (!s.contentEquals(other.s)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = r.contentHashCode()
        result = 31 * result + s.contentHashCode()
        return result
    }

    override fun toDerEncoded(): ByteArray {
        // r and s are both encoded without a sign but ASN1Integer uses a sign. So we need
        // to insert zeroes as needed...
        val rS = stripLeadingZeroes(r)
        val sS = stripLeadingZeroes(s)
        val rP = if (rS[0].toInt().and(0x80) != 0) { byteArrayOf(0x00) + rS } else { rS }
        val sP = if (sS[0].toInt().and(0x80) != 0) { byteArrayOf(0x00) + sS } else { sS }
        val derEncoded = ASN1.encode(ASN1Sequence(listOf(
            ASN1Integer(rP),
            ASN1Integer(sP)
        )))
        return derEncoded
    }

    companion object {
        fun fromCoseEncoded(coseSignature: ByteArray): EcSignature {
            val len = coseSignature.size
            val r = coseSignature.sliceArray(IntRange(0, len/2 - 1))
            val s = coseSignature.sliceArray(IntRange(len/2, len - 1))
            return EcSignature(r, s)
        }

        fun fromDataItem(dataItem: DataItem): EcSignature {
            require(dataItem is CborMap)
            return EcSignature(dataItem["r"].asBstr, dataItem["s"].asBstr)
        }

        fun fromDerEncoded(
            keySizeBits: Int,
            derEncodedSignature: ByteArray
        ): EcSignature {
            val derSignature = ASN1.decode(derEncodedSignature) as ASN1Sequence
            val r = (derSignature.elements[0] as ASN1Integer).value
            val s = (derSignature.elements[1] as ASN1Integer).value
            // Need to make sure that each component is exactly as big as the key size.
            val rS = stripLeadingZeroes(r)
            val sS = stripLeadingZeroes(s)
            val keySize = (keySizeBits + 7)/8
            val rPadded = ByteArray(keySize)
            val sPadded = ByteArray(keySize)
            rS.copyInto(rPadded, keySize - rS.size)
            sS.copyInto(sPadded, keySize - sS.size)
            check(rPadded.size == keySize)
            check(sPadded.size == keySize)
            val sig = EcSignature(rPadded, sPadded)
            return sig
        }
    }
}

private fun stripLeadingZeroes(array: ByteArray): ByteArray {
    val idx = array.indexOfFirst { it != 0.toByte() }
    if (idx == -1)
        return array
    return array.copyOfRange(idx, array.size)
}
