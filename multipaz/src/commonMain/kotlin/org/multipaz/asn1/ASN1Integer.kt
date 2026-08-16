package org.multipaz.asn1

import org.multipaz.util.toHex
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.asn1.ASN1Integer
import kotlin.random.Random

class ASN1Integer(
    val value: ByteArray,
    tag: Int = ASN1IntegerTag.INTEGER.tag
): ASN1PrimitiveValue(tag = tag) {

    constructor(longValue: Long,
                tag: Int = ASN1IntegerTag.INTEGER.tag)
            : this(longValue.derEncodeToByteArray(), tag)

    override fun encode(builder: ByteStringBuilder) {
        ASN1.appendUniversalTagEncodingLength(builder, tag, enc, value.size)
        builder.append(value)
    }

    override fun equals(other: Any?): Boolean =
        other is ASN1Integer && tag == other.tag && value contentEquals other.value

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String {
        return "ASN1Integer(${tag}, ${value.toHex()})"
    }

    /**
     * Gets the value as a [Long].
     *
     * @throws IllegalStateException if the value doesn't fit in a [Long].
     */
    fun toLong(): Long {
        if (value.size > 8) {
            throw IllegalStateException("Value doesn't fit in a Long")
        }
        return value.derDecodeAsLong()
    }

    companion object {
        fun parse(content: ByteArray, tag: Int): ASN1Integer {
            return ASN1Integer(content, tag)
        }

        /**
         * Generates a positive [ASN1Integer] with [numBits] bits of random.
         *
         * The generated integer is guaranteed to be positive (greater than zero)
         * and conform to DER encoding rules (e.g. for use as X.509 certificate serial numbers
         * per RFC 5280 section 4.1.2.2).
         *
         * @param numBits number of bits of random, must be positive and divisible by 8.
         * @param random the [Random] to use for randomness.
         * @return a positive [ASN1Integer]
         */
        fun fromRandom(
            numBits: Int,
            random: Random = Random.Default
        ): ASN1Integer {
            require(numBits >= 8 && numBits.and(0x07) == 0) {
                "numBits must be positive and a multiple of 8"
            }
            var bytes: ByteArray
            do {
                bytes = random.nextBytes(numBits / 8)
            } while (bytes.all { it == 0.toByte() })

            // Strip redundant leading 0x00 bytes according to ITU-T X.690 8.3.2
            var firstNonZeroIndex = 0
            while (firstNonZeroIndex < bytes.size - 1 &&
                bytes[firstNonZeroIndex] == 0.toByte() &&
                (bytes[firstNonZeroIndex + 1].toInt() and 0x80) == 0
            ) {
                firstNonZeroIndex++
            }
            val trimmed = if (firstNonZeroIndex > 0) {
                bytes.sliceArray(firstNonZeroIndex until bytes.size)
            } else {
                bytes
            }

            // If the leading bit is 1, prepend 0x00 so it is positive in two's-complement DER
            val derBytes = if ((trimmed[0].toInt() and 0x80) != 0) {
                byteArrayOf(0x00) + trimmed
            } else {
                trimmed
            }
            return ASN1Integer(derBytes)
        }
    }
}

internal fun Long.derEncodeToByteArray(): ByteArray {
    var v = this
    val bsb = ByteStringBuilder()
    for (n in IntRange(0, 7)) {
        bsb.append(v.and(0xffL).toByte())
        v = v.shr(8)
    }
    var value = bsb.toByteString().toByteArray().reversedArray()
    if (this >= 0) {
        // Remove leading 0x00
        var numRemove = 0
        for (n in IntRange(0, 6)) {
            val digit = value[n].toInt().and(0xff)
            val nextDigit = value[n + 1].toInt().and(0xff)
            if (digit == 0x00 && (nextDigit.and(0x80) == 0)) {
                numRemove++
            } else {
                break
            }
        }
        return value.sliceArray(IntRange(numRemove, 7))
    } else {
        // Remove leading 0xff
        var numRemove = 0
        for (n in IntRange(0, 6)) {
            val digit = value[n].toInt().and(0xff)
            val nextDigit = value[n + 1].toInt().and(0xff)
            if (digit == 0xff && (nextDigit.and(0x80) != 0)) {
                numRemove++
            } else {
                break
            }
        }
        return value.sliceArray(IntRange(numRemove, 7))
    }
}

internal fun ByteArray.derDecodeAsLong(): Long {
    var signPositive = true
    if (this.size > 9) {
        throw IllegalArgumentException("Cannot decode Long from ByteArray of size ${this.size}")
    } else if (this.size == 9) {
        if (this[0].toInt() == 0xff) {
            signPositive = false
        } else {
            throw IllegalArgumentException("Illegal sign value ${this[0]}")
        }
    } else {
        if (this[0].toInt().and(0x80) != 0) {
            signPositive = false
        }
    }

    var result = 0L
    if (!signPositive && this.size < 8) {
        for (n in IntRange(this.size, 7)) {
            result = result or 0xffL.shl((this.size - 1 - n)*8)
        }
    }
    for (n in this.indices) {
        result = result or this[n].toLong().and(0xff).shl((this.size - 1 - n)*8)
    }
    return result
}