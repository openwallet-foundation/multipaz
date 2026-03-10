package org.multipaz.util

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.io.bytestring.buildByteString

/** Tests are leveraging tested implementation of same extensions for the ByteArray. */
class ByteStringBuilderExtensionsTest {
    // Signed.
    @Test
    fun appendInt8_withinRange() {
        assertDoesNotThrow { buildByteString { appendInt8(Byte.MIN_VALUE.toInt()) } }
        assertDoesNotThrow { buildByteString { appendInt8(0) } }
        assertDoesNotThrow { buildByteString { appendInt8(Byte.MAX_VALUE.toInt()) } }
    }

    @Test
    fun appendInt8_outOfRange() {
        assertFailsWith<IllegalArgumentException> { buildByteString { appendInt8(Byte.MIN_VALUE.toInt() - 1) } }
        assertFailsWith<IllegalArgumentException> { buildByteString { appendInt8(Byte.MAX_VALUE.toInt() + 1) } }
    }

    @Test
    fun appendInt8_readBack() {
        val byteString = buildByteString { appendInt8(100) }
        assertEquals(100, byteString.toByteArray().getInt8(0))
    }

    @Test
    fun appendInt16_withinRange() {
        assertDoesNotThrow {
            buildByteString { appendInt16Le(Short.MIN_VALUE.toInt(), Short.MIN_VALUE..Short.MAX_VALUE) } }
        assertDoesNotThrow {
            buildByteString { appendInt16Le(0, Short.MIN_VALUE..Short.MAX_VALUE) } }
        assertDoesNotThrow {
            buildByteString { appendInt16Le(Short.MAX_VALUE.toInt(), Short.MIN_VALUE..Short.MAX_VALUE) } }
    }

    @Test
    fun appendInt16_outOfRange() {
        assertFailsWith<IllegalArgumentException> { buildByteString { appendInt16Le(
            Short.MIN_VALUE.toInt() - 1,
            Short.MIN_VALUE..Short.MAX_VALUE
        ) } }
        assertFailsWith<IllegalArgumentException> { buildByteString { appendInt16Le(
            Short.MAX_VALUE.toInt() + 1,
            Short.MIN_VALUE..Short.MAX_VALUE
        ) } }
    }

    @Test
    fun appendInt16_readBack() {
        val byteString = buildByteString { appendInt16Le(256, Short.MIN_VALUE..Short.MAX_VALUE) }
        assertEquals(256, byteString.toByteArray().getInt16Le(0))
    }

    @Test
    fun appendInt32_valid() {
        val byteString = buildByteString { appendInt32Le(Int.MIN_VALUE) }
        assertEquals(Int.MIN_VALUE, byteString.toByteArray().getInt32Le(0))

        val byteString2 = buildByteString { appendInt32Le(0) }
        assertEquals(0, byteString2.toByteArray().getInt32Le(0))

        val byteString3 = buildByteString { appendInt32Le(Int.MAX_VALUE) }
        assertEquals(Int.MAX_VALUE, byteString3.toByteArray().getInt32Le(0))
    }

    @Test
    fun appendInt64_valid() {
        val byteString = buildByteString { appendInt64Le(Long.MIN_VALUE) }
        assertEquals(Long.MIN_VALUE, byteString.toByteArray().getInt64Le(0))

        val byteString2 = buildByteString { appendInt64Le(0) }
        assertEquals(0, byteString2.toByteArray().getInt64Le(0))

        val byteString3 = buildByteString { appendInt64Le(Long.MAX_VALUE) }
        assertEquals(Long.MAX_VALUE, byteString3.toByteArray().getInt64Le(0))
    }

    // Unsigned.
    @Test
    fun appendUInt8_withinRange() {
        assertDoesNotThrow { buildByteString { appendUInt8(UByte.MIN_VALUE.toUInt()) } }
        assertDoesNotThrow { buildByteString { appendUInt8(128u) } }
        assertDoesNotThrow { buildByteString { appendUInt8(UByte.MAX_VALUE.toUInt()) } }
    }

    @Test
    fun appendUInt8_outOfRange() {
        assertFailsWith<IllegalArgumentException> { buildByteString { appendUInt8(UByte.MIN_VALUE.toUInt() - 1u) } }
        assertFailsWith<IllegalArgumentException> { buildByteString { appendUInt8(UByte.MAX_VALUE.toUInt() + 1u) } }
    }

    @Test
    fun appendUInt8_readBack() {
        val byteString = buildByteString { appendUInt8(200u) }
        assertEquals(200u, byteString.toByteArray().getUInt8(0))
    }

    @Test
    fun appendUInt16_withinRange() {
        assertDoesNotThrow { buildByteString { appendUInt16Le(UShort.MIN_VALUE.toUInt()) } }
        assertDoesNotThrow { buildByteString { appendUInt16Le(32768u) } }
        assertDoesNotThrow { buildByteString { appendUInt16Le(UShort.MAX_VALUE.toUInt()) } }
    }

    @Test
    fun appendUInt16_outOfRange() {
        assertFailsWith<IllegalArgumentException> { buildByteString { appendUInt16Le(UShort.MIN_VALUE - 1u) } }
        assertFailsWith<IllegalArgumentException> { buildByteString { appendUInt16Le(UShort.MAX_VALUE + 1u) } }
    }

    @Test
    fun appendUInt16_readBack() {
        val byteString = buildByteString { appendUInt16Le(50000u) }
        assertEquals(50000u, byteString.toByteArray().getUInt16Le(0))
    }

    @Test
    fun appendUInt32_valid() {
        val byteString = buildByteString { appendUInt32Le(UInt.MIN_VALUE) }
        assertEquals(UInt.MIN_VALUE, byteString.toByteArray().getUInt32Le(0))

        val byteString2 = buildByteString { appendUInt32Le(2147483647u) }
        assertEquals(2147483647u, byteString2.toByteArray().getUInt32Le(0))
    }
    
    @Test
    fun appendUInt64_valid() {
        val byteString = buildByteString { appendUInt64Le(ULong.MIN_VALUE) }
        assertEquals(ULong.MIN_VALUE, byteString.toByteArray().getUInt64Le(0))

        val byteString2 = buildByteString { appendUInt64Le(4294967295u) }
        assertEquals(4294967295u, byteString2.toByteArray().getUInt64Le(0))
    }

    @Test
    fun appendByteArray_empty() {
        val builder = ByteStringBuilder()
        val bArray = ByteArray(0)
        builder.appendByteArray(bArray)
        assertEquals(buildByteString {  }, builder.toByteString())
    }

    @Test
    fun appendByteArray_nonEmpty() {
        val builder = ByteStringBuilder()
        val bArray = byteArrayOf(0x01, 0x02, 0x03)
        builder.appendByteArray(bArray)
        assertEquals(ByteString(bArray), builder.toByteString())
    }

    @Test
    fun appendByteArray_multiple() {
        val builder = ByteStringBuilder()
        val bArray1 = byteArrayOf(0x01, 0x02)
        val bArray2 = byteArrayOf(0x03, 0x04)
        builder.appendByteArray(bArray1).appendByteArray(bArray2)
        assertEquals(ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)), builder.toByteString())
    }

    @Test
    fun appendByteArray_mixed() {
        val builder = ByteStringBuilder()
        val bArray1 = byteArrayOf(0x01, 0x02)
        val bArray2 = ByteArray(0)
        val bArray3 = byteArrayOf(0x05, 0x06)
        builder.appendByteArray(bArray1)
        builder.appendByteArray(bArray2)
        builder.appendByteArray(bArray3)
        assertEquals(ByteString(byteArrayOf(0x01, 0x02, 0x05, 0x06)), builder.toByteString())
    }

    @Test
    fun appendByteString_empty() {
        val builder = ByteStringBuilder()
        val bString = buildByteString {  }
        builder.appendByteString(bString)
        assertEquals(buildByteString {  }, builder.toByteString())
    }

    @Test
    fun appendByteString_nonEmpty() {
        val builder = ByteStringBuilder()
        val bString = ByteString(byteArrayOf(0x01, 0x02, 0x03))
        builder.appendByteString(bString)
        assertEquals(bString, builder.toByteString())
    }

    @Test
    fun appendByteString_multiple() {
        val builder = ByteStringBuilder()
        val bString1 = ByteString(byteArrayOf(0x01, 0x02))
        val bString2 = ByteString(byteArrayOf(0x03, 0x04))
        builder.appendByteString(bString1).appendByteString(bString2)
        assertEquals(ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)), builder.toByteString())
    }

    @Test
    fun appendByteString_mixed() {
        val builder = ByteStringBuilder()
        val bString1 = ByteString(byteArrayOf(0x01, 0x02))
        val bString2 = buildByteString {  }
        val bString3 = ByteString(byteArrayOf(0x05, 0x06))
        builder.appendByteString(bString1)
        builder.appendByteString(bString2)
        builder.appendByteString(bString3)
        assertEquals(ByteString(byteArrayOf(0x01, 0x02, 0x05, 0x06)), builder.toByteString())
    }

    // Readers

    @Test
    fun testGetInt8_validData() {
        assertEquals(0x01.toByte(), ByteString(byteArrayOf(0x01)).getInt8(0))
    }

    @Test
    fun testGetInt8_validDataWithOffset() {
        assertEquals(0x02.toByte(), ByteString(byteArrayOf(0x00, 0x02)).getInt8(1))
    }

    @Test
    fun testGetInt8_zero() {
        assertEquals(0.toByte(), ByteString(byteArrayOf(0x00)).getInt8(0))
    }

    @Test
    fun testGetInt8_maxByte() {
        assertEquals(0x7F.toByte(), ByteString(byteArrayOf(0x7F)).getInt8(0))
    }

    @Test
    fun testGetInt8_minByte() {
        assertEquals((-0x80).toByte(), ByteString(byteArrayOf(0x80.toByte())).getInt8(0))
    }

    @Test
    fun testGetInt8_negative() {
        assertEquals((-1).toByte(), ByteString(byteArrayOf(0xFF.toByte())).getInt8(0))
    }
    @Test
    fun testGetInt8_positive() {
        assertEquals(1.toByte(), ByteString(byteArrayOf(0x01)).getInt8(0))
    }

    @Test
    fun testGetInt8_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01)).getInt8(1)
        }
    }

    @Test
    fun testGetInt8_offsetNegative() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01)).getInt8(-1)
        }
    }

    @Test
    fun testGetInt8_emptyByteString() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf()).getInt8(0)
        }
    }

    @Test
    fun testGetInt16_validData() {
        assertEquals(0x0102.toShort(), ByteString(byteArrayOf(0x01, 0x02)).getInt16(0))
        assertEquals(0x0201.toShort(), ByteString(byteArrayOf(0x01, 0x02)).getInt16Le(0))
    }

    @Test
    fun testGetInt16_validDataWithOffset() {
        assertEquals(0x0203.toShort(), ByteString(byteArrayOf(0x00, 0x02, 0x03)).getInt16(1))
        assertEquals(0x0302.toShort(), ByteString(byteArrayOf(0x00, 0x02, 0x03)).getInt16Le(1))
    }

    @Test
    fun testGetInt16_zero() {
        assertEquals(0.toShort(), ByteString(byteArrayOf(0x00, 0x00)).getInt16(0))
        assertEquals(0.toShort(), ByteString(byteArrayOf(0x00, 0x00)).getInt16Le(0))
    }

    @Test
    fun testGetInt16_maxShort() {
        assertEquals(0x7FFF.toShort(), ByteString(byteArrayOf(0x7F, 0xFF.toByte())).getInt16(0))
        assertEquals(0x7FFF.toShort(), ByteString(byteArrayOf(0xFF.toByte(), 0x7F)).getInt16Le(0))
    }

    @Test
    fun testGetInt16_minShort() {
        assertEquals((-0x7FFF-1).toShort(), ByteString(byteArrayOf(0x80.toByte(), 0x00)).getInt16(0))
        assertEquals((-0x7FFF-1).toShort(), ByteString(byteArrayOf(0x00, 0x80.toByte())).getInt16Le(0))
    }

    @Test
    fun testGetInt16_negative() {
        assertEquals((-1).toShort(), ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte())).getInt16(0))
        assertEquals((-1).toShort(), ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte())).getInt16Le(0))
    }
    @Test
    fun testGetInt16_positive() {
        assertEquals(1.toShort(), ByteString(byteArrayOf(0x00, 0x01)).getInt16(0))
        assertEquals(1.toShort(), ByteString(byteArrayOf(0x01, 0x00)).getInt16Le(0))
    }

    @Test
    fun testGetInt16_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getInt16(1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getInt16Le(1) }
    }

    @Test
    fun testGetInt16_offsetNegative() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getInt16(-1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getInt16Le(-1) }
    }

    @Test
    fun testGetInt16_emptyByteString() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getInt16(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getInt16Le(0) }
    }

    @Test
    fun testGetInt16_oneByte() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getInt16(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getInt16Le(0) }
    }

    @Test
    fun testGetInt32_validData() {
        assertEquals(0x01020304, ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getInt32(0))
        assertEquals(0x04030201, ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getInt32Le(0))
    }

    @Test
    fun testGetInt32_validDataWithOffset() {
        assertEquals(0x02030405, ByteString(byteArrayOf(0x00, 0x02, 0x03, 0x04, 0x05)).getInt32(1))
        assertEquals(0x05040302, ByteString(byteArrayOf(0x00, 0x02, 0x03, 0x04, 0x05)).getInt32Le(1))
    }

    @Test
    fun testGetInt32_zero() {
        assertEquals(0, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00)).getInt32(0))
        assertEquals(0, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00)).getInt32Le(0))
    }

    @Test
    fun testGetInt32_maxInt() {
        assertEquals(0x7FFFFFFF,
            ByteString(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getInt32(0))
        assertEquals(0x7FFFFFFF,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)).getInt32Le(0))
    }

    @Test
    fun testGetInt32_minInt() {
        assertEquals(-0x80000000, ByteString(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00)).getInt32(0))
        assertEquals(-0x80000000, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte())).getInt32Le(0))
    }

    @Test
    fun testGetInt32_negative() {
        assertEquals(-1,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getInt32(0))
        assertEquals(-1,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getInt32Le(0))
    }
    @Test
    fun testGetInt32_positive() {
        assertEquals(1, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x01)).getInt32(0))
        assertEquals(1, ByteString(byteArrayOf(0x01, 0x00, 0x00, 0x00)).getInt32Le(0))
    }

    @Test
    fun testGetInt32_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getInt32(1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getInt32Le(1) }
    }

    @Test
    fun testGetInt32_offsetNegative() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getInt32(-1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getInt32Le(-1) }
    }

    @Test
    fun testGetInt32_emptyByteString() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getInt32(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getInt32Le(0) }
    }

    @Test
    fun testGetInt32_oneByte() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getInt32(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getInt32Le(0) }
    }

    @Test
    fun testGetInt32_threeBytes() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03)).getInt32(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03)).getInt32Le(0) }
    }

    @Test
    fun testGetInt64_validData() {
        assertEquals(0x0102030405060708L,
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getInt64(0))
        assertEquals(0x0807060504030201L,
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getInt64Le(0))
    }

    @Test
    fun testGetInt64_validDataWithOffset() {
        assertEquals(0x0203040506070809L,
            ByteString(byteArrayOf(0x00, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)).getInt64(1))
        assertEquals(0x0908070605040302L,
            ByteString(byteArrayOf(0x00, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)).getInt64Le(1))
    }

    @Test
    fun testGetInt64_zero() {
        assertEquals(0L, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).getInt64(0))
        assertEquals(0L, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).getInt64Le(0))
    }

    @Test
    fun testGetInt64_maxLong() {
        assertEquals(0x7FFFFFFFFFFFFFFFL,
            ByteString(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte())).getInt64(0))
        assertEquals(0x7FFFFFFFFFFFFFFFL,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0x7F)).getInt64Le(0))
    }

    @Test
    fun testGetInt64_minLong() {
        assertEquals(-0x7FFFFFFFFFFFFFFFL-1,
            ByteString(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).getInt64(0))
        assertEquals(-0x7FFFFFFFFFFFFFFFL-1,
            ByteString(byteArrayOf(0x00.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte())).getInt64Le(0))
    }
    @Test
    fun testGetInt64_negative() {
        assertEquals(-1L,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getInt64(0))
        assertEquals(-1L,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getInt64Le(0))
    }

    @Test
    fun testGetInt64_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getInt64(1) }
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getInt64Le(1) }
    }

    @Test
    fun testGetInt64_offsetNegative() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getInt64(-1) }
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getInt64Le(-1) }
    }

    @Test
    fun testGetInt64_emptyByteString() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getInt64(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getInt64Le(0) }
    }

    @Test
    fun testGetInt64_oneByte() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getInt64(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getInt64Le(0) }
    }

    @Test
    fun testGetInt64_sevenBytes() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)).getInt64(0) }
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)).getInt64Le(0) }
    }

    @Test
    fun testGetUInt8_validData() {
        assertEquals(0x01u, ByteString(byteArrayOf(0x01)).getUInt8(0))
    }

    @Test
    fun testGetUInt8_validDataWithOffset() {
        assertEquals(0x02u, ByteString(byteArrayOf(0x00, 0x02)).getUInt8(1))
    }

    @Test
    fun testGetUInt8_zero() {
        assertEquals(0u, ByteString(byteArrayOf(0x00)).getUInt8(0))
    }

    @Test
    fun testGetUInt8_maxUByte() {
        assertEquals(UByte.MAX_VALUE, ByteString(byteArrayOf(0xFF.toByte())).getUInt8(0))
    }

    @Test
    fun testGetUInt8_minUByte() {
        assertEquals(UByte.MIN_VALUE, ByteString(byteArrayOf(0x00)).getUInt8(0))
    }

    @Test
    fun testGetUInt8_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01)).getUInt8(1)
        }
    }

    @Test
    fun testGetUInt8_offsetNegative() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01)).getUInt8(-1)
        }
    }

    @Test
    fun testGetUInt8_emptyByteString() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf()).getUInt8(0)
        }
    }

    @Test
    fun testGetUInt16_validData() {
        assertEquals(0x0102u, ByteString(byteArrayOf(0x01, 0x02)).getUInt16(0))
        assertEquals(0x0201u, ByteString(byteArrayOf(0x01, 0x02)).getUInt16Le(0))
    }

    @Test
    fun testGetUInt16_validDataWithOffset() {
        assertEquals(0x0203u, ByteString(byteArrayOf(0x00, 0x02, 0x03)).getUInt16(1))
        assertEquals(0x0302u, ByteString(byteArrayOf(0x00, 0x02, 0x03)).getUInt16Le(1))
    }

    @Test
    fun testGetUInt16_zero() {
        assertEquals(0u, ByteString(byteArrayOf(0x00, 0x00)).getUInt16(0))
        assertEquals(0u, ByteString(byteArrayOf(0x00, 0x00)).getUInt16Le(0))
    }

    @Test
    fun testGetUInt16_maxUShort() {
        assertEquals(UShort.MAX_VALUE, ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte())).getUInt16(0))
        assertEquals(UShort.MAX_VALUE, ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte())).getUInt16Le(0))
    }

    @Test
    fun testGetUInt16_minUShort() {
        assertEquals(UShort.MIN_VALUE, ByteString(byteArrayOf(0x00, 0x00)).getUInt16(0))
        assertEquals(UShort.MIN_VALUE, ByteString(byteArrayOf(0x00, 0x00)).getUInt16Le(0))
    }

    @Test
    fun testGetUInt16_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getUInt16(1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getUInt16Le(1) }
    }

    @Test
    fun testGetUInt16_offsetNegative() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getUInt16(-1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02)).getUInt16Le(-1) }
    }

    @Test
    fun testGetUInt16_emptyByteString() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getUInt16(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getUInt16Le(0) }
    }

    @Test
    fun testGetUInt16_oneByte() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getUInt16(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getUInt16Le(0) }
    }

    @Test
    fun testGetUInt32_validData() {
        assertEquals(0x01020304u, ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getUInt32(0))
        assertEquals(0x04030201u, ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getUInt32Le(0))
    }

    @Test
    fun testGetUInt32_validDataWithOffset() {
        assertEquals(0x02030405u, ByteString(byteArrayOf(0x00, 0x02, 0x03, 0x04, 0x05)).getUInt32(1))
        assertEquals(0x05040302u, ByteString(byteArrayOf(0x00, 0x02, 0x03, 0x04, 0x05)).getUInt32Le(1))
    }

    @Test
    fun testGetUInt32_zero() {
        assertEquals(0u, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00)).getUInt32(0))
        assertEquals(0u, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00)).getUInt32Le(0))
    }

    @Test
    fun testGetUInt32_maxUInt() {
        assertEquals(UInt.MAX_VALUE,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getUInt32(0))
        assertEquals(UInt.MAX_VALUE,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getUInt32Le(0))
    }

    @Test
    fun testGetUInt32_minUInt() {
        assertEquals(UInt.MIN_VALUE, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00)).getUInt32(0))
        assertEquals(UInt.MIN_VALUE, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00)).getUInt32Le(0))
    }

    @Test
    fun testGetUInt32_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getUInt32(1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getUInt32Le(1) }
    }

    @Test
    fun testGetUInt32_offsetNegative() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getUInt32(-1) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04)).getUInt32Le(-1) }
    }

    @Test
    fun testGetUInt32_emptyByteString() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getUInt32(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getUInt32Le(0) }
    }

    @Test
    fun testGetUInt32_oneByte() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getUInt32(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getUInt32Le(0) }
    }

    @Test
    fun testGetUInt32_threeBytes() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03)).getUInt32(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01, 0x02, 0x03)).getUInt32Le(0) }
    }

    @Test
    fun testGetUInt64_validData() {
        assertEquals(0x0102030405060708uL, ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
            .getUInt64(0))
        assertEquals(0x0807060504030201uL, ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
            .getUInt64Le(0))
    }

    @Test
    fun testGetUInt64_validDataWithOffset() {
        assertEquals(0x0102030405060708uL,
            ByteString(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)).getUInt64(1))
        assertEquals(0x0807060504030201uL,
            ByteString(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)).getUInt64Le(1))
    }

    @Test
    fun testGetUInt64_zero() {
        assertEquals(0uL, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).getUInt64(0))
        assertEquals(0uL, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).getUInt64Le(0))
    }

    @Test
    fun testGetUInt64_maxULong() {
        assertEquals(ULong.MAX_VALUE,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getUInt64(0))
        assertEquals(ULong.MAX_VALUE,
            ByteString(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).getUInt64Le(0))
    }

    @Test
    fun testGetUInt64_minULong() {
        assertEquals(ULong.MIN_VALUE, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            .getUInt64(0))
        assertEquals(ULong.MIN_VALUE, ByteString(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            .getUInt64Le(0))
    }

    @Test
    fun testGetUInt64_offsetTooLarge() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getUInt64(1) }
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getUInt64Le(1) }
    }

    @Test
    fun testGetUInt64_offsetNegative() {
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getUInt64(-1) }
        assertFailsWith<IllegalArgumentException> {
            ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)).getUInt64Le(-1) }
    }

    @Test
    fun testGetUInt64_emptyByteString() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getUInt64(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf()).getUInt64Le(0) }
    }

    @Test
    fun testGetUInt64_oneByte() {
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getUInt64(0) }
        assertFailsWith<IllegalArgumentException> { ByteString(byteArrayOf(0x01)).getUInt64Le(0) }
    }

    @Test
    fun concat_emptyStrings() {
        val bs1 = buildByteString {  }
        val bs2 = buildByteString {  }
        val result = bs1.concat(bs2)
        assertEquals(buildByteString {  }, result)
    }

    @Test
    fun concat_emptyString_withNonEmpty() {
        val bs1 = buildByteString {  }
        val bs2 = ByteString(byteArrayOf(0x01, 0x02))
        val result = bs1.concat(bs2)
        assertEquals(bs2, result)

        val bs3 = ByteString(byteArrayOf(0x03, 0x04))
        val result2 = bs3.concat(bs1)
        assertEquals(bs3, result2)
    }

    @Test
    fun concat_nonEmptyStrings() {
        val bs1 = ByteString(byteArrayOf(0x01, 0x02))
        val bs2 = ByteString(byteArrayOf(0x03, 0x04))
        val expected = ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val result = bs1.concat(bs2)
        assertEquals(expected, result)
    }

    @Test
    fun concat_singleByte() {
        val bs1 = ByteString(byteArrayOf(0x01))
        val bs2 = ByteString(byteArrayOf(0x02))
        val expected = ByteString(byteArrayOf(0x01, 0x02))
        val result = bs1.concat(bs2)
        assertEquals(expected, result)
    }

    @Test
    fun concat_longerStrings() {
        val bs1 = ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val bs2 = ByteString(byteArrayOf(0x06, 0x07, 0x08, 0x09, 0x0A))
        val expected = ByteString(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A))
        val result = bs1.concat(bs2)
        assertEquals(expected, result)
    }

    @Test
    fun concat_veryLongStrings() {
        val bs1 = ByteString(ByteArray(1024) { it.toByte() })
        val bs2 = ByteString(ByteArray(2048) { (it + 1024).toByte() })
        val expected = ByteString(ByteArray(3072) { if (it < 1024) it.toByte() else (it).toByte() })

        val result = bs1.concat(bs2)
        assertEquals(expected, result)
    }
}

// Helper function to assert that a block of code does not throw an exception
private fun assertDoesNotThrow(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        kotlin.test.fail("Expected no exception, but got: $e")
    }
}

