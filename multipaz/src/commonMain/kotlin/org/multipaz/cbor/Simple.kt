package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder
import kotlin.experimental.ExperimentalObjCName
import kotlin.experimental.or
import kotlin.native.ObjCName

/**
 * Simple (major type 7)
 *
 * @param value the simple value, for example [Simple.TRUE].
 */
class Simple(val value: UInt) : DataItem(MajorType.SPECIAL) {
    init {
        check(value < 24U || (value in 32U..255U))
    }

    override fun encode(builder: ByteStringBuilder) {
        val majorTypeShifted = (majorType.type shl 5).toByte()
        builder.append(majorTypeShifted.or(value.toByte()))
    }

    @OptIn(ExperimentalObjCName::class)
    companion object {
        /** The [Simple] value for FALSE */
        @ObjCName(name = "False", swiftName = "FALSE")
        val FALSE = Simple(20U)

        /** The [Simple] value for TRUE */
        @ObjCName(name = "True", swiftName = "TRUE")
        val TRUE = Simple(21U)

        /** The [Simple] value for NULL */
        @ObjCName(name = "Null", swiftName = "NULL")
        val NULL = Simple(22U)

        /** The [Simple] value for UNDEFINED */
        val UNDEFINED = Simple(23U)

        internal fun decode(encodedCbor: ByteArray, offset: Int): Pair<Int, Simple> {
            val (newOffset, value) = Cbor.decodeLength(encodedCbor, offset)
            if (newOffset - offset > 1 && value < 32UL) {
                throw IllegalArgumentException("two-byte simple value must be >= 32")
            }
            return Pair(newOffset, Simple(value.toUInt()))
        }
    }

    override fun equals(other: Any?): Boolean = other is Simple && value == other.value

    override fun hashCode(): Int = CborHash.hashLong(CborHash.TAG_SIMPLE, value.toLong())

    override fun toString() = when (this) {
            FALSE -> "Simple(FALSE)"
            TRUE -> "Simple(TRUE)"
            NULL -> "Simple(NULL)"
            UNDEFINED -> "Simple(UNDEFINED)"
            else -> "Simple($value)"
        }

}