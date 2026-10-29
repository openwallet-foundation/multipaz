package org.multipaz.jpeg2000

import org.multipaz.util.fromHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Jpeg2000Test {

    companion object {
        // 4x4 lossless sRGB JP2 image:
        // pixel(0,0) = Red (255, 0, 0), pixel(1,0) = Green (0, 255, 0),
        // pixel(2,0) = Blue (0, 0, 255), pixel(3,0) = White (255, 255, 255)
        private val RGB_JP2 = (
            "0000000c6a5020200d0a870a00000014667479706a703220000000006a703220" +
            "0000002d6a703268000000166968647200000004000000040003070700000000" +
            "000f636f6c7201000000000010000000c46a703263ff4fff51002f0000000000" +
            "0400000004000000000000000000000004000000040000000000000000000307" +
            "0101070101070101ff52000c00000001000004040001ff5c00044040ff640025" +
            "000143726561746564206279204f70656e4a5045472076657273696f6e20322e" +
            "352e34ff90000a00000000004c0001ff93df80881769662d5c26ed9824684c07" +
            "bb4e16a176df80a011f186279e38ef018637ebc31972f7d5f4a921b7df808012" +
            "5c074b597d96665366bcef74c36b6effd9"
        ).fromHex()

        // Same 4x4 lossless sRGB image encoded as a raw J2K codestream
        private val RGB_J2K = (
            "ff4fff51002f0000000000040000000400000000000000000000000400000004" +
            "00000000000000000003070101070101070101ff52000c000000010000040400" +
            "01ff5c00044040ff640025000143726561746564206279204f70656e4a504547" +
            "2076657273696f6e20322e352e34ff90000a00000000004c0001ff93df808817" +
            "69662d5c26ed9824684c07bb4e16a176df80a011f186279e38ef018637ebc31" +
            "972f7d5f4a921b7df8080125c074b597d96665366bcef74c36b6effd9"
        ).fromHex()

        // 4x4 lossless Grayscale JP2 image where pixel i has intensity i * 16
        private val GRAY_JP2 = (
            "0000000c6a5020200d0a870a00000014667479706a703220000000006a703220" +
            "0000002d6a703268000000166968647200000004000000040001070700000000" +
            "000f636f6c72010000000000110000008f6a703263ff4fff5100290000000000" +
            "0400000004000000000000000000000004000000040000000000000000000107" +
            "0101ff52000c00000001000004040001ff5c00044040ff640025000143726561" +
            "746564206279204f70656e4a5045472076657273696f6e20322e352e34ff9000" +
            "0a00000000001d0001ff93df8060120f849642e1ba6df1cfbd07ffd9"
        ).fromHex()
    }

    @Test
    fun testIsJpeg2000() {
        assertTrue(isJpeg2000(RGB_JP2))
        assertTrue(isJpeg2000(RGB_J2K))
        assertTrue(isJpeg2000(GRAY_JP2))
        assertFalse(isJpeg2000(ByteArray(0)))
        assertFalse(isJpeg2000(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertFalse(isJpeg2000(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }

    @Test
    fun testDecodeRgbJp2() {
        val decoded = decodeJpeg2000(RGB_JP2)
        assertNotNull(decoded)
        assertEquals(4, decoded.width)
        assertEquals(4, decoded.height)
        assertEquals(16, decoded.argbPixels.size)
        assertEquals(0xFFFF0000.toInt(), decoded.argbPixels[0])
        assertEquals(0xFF00FF00.toInt(), decoded.argbPixels[1])
        assertEquals(0xFF0000FF.toInt(), decoded.argbPixels[2])
        assertEquals(0xFFFFFFFF.toInt(), decoded.argbPixels[3])
    }

    @Test
    fun testDecodeRgbJ2kCodestream() {
        val decoded = decodeJpeg2000(RGB_J2K)
        assertNotNull(decoded)
        assertEquals(4, decoded.width)
        assertEquals(4, decoded.height)
        assertEquals(16, decoded.argbPixels.size)
        assertEquals(0xFFFF0000.toInt(), decoded.argbPixels[0])
        assertEquals(0xFF00FF00.toInt(), decoded.argbPixels[1])
        assertEquals(0xFF0000FF.toInt(), decoded.argbPixels[2])
        assertEquals(0xFFFFFFFF.toInt(), decoded.argbPixels[3])
    }

    @Test
    fun testDecodeGrayscaleJp2() {
        val decoded = decodeJpeg2000(GRAY_JP2)
        assertNotNull(decoded)
        assertEquals(4, decoded.width)
        assertEquals(4, decoded.height)
        for (i in 0 until 16) {
            val v = i * 16
            val expected = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            assertEquals(expected, decoded.argbPixels[i], "Mismatch at pixel $i")
        }
    }

    @Test
    fun testDecodeCorruptedOrInvalidReturnsNull() {
        assertNull(decodeJpeg2000(ByteArray(0)))
        assertNull(decodeJpeg2000(byteArrayOf(1, 2, 3, 4, 5)))
        // Truncated JP2 header with no valid codestream
        val truncated = RGB_JP2.copyOfRange(0, 32)
        assertNull(decodeJpeg2000(truncated))
    }
}
