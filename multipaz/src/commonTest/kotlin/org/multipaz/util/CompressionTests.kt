package org.multipaz.util

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompressionTests {
    
    @Test fun roundTripLevel0() = runTest { roundTrip(0) }
    @Test fun roundTripLevel1() = runTest { roundTrip(1) }
    @Test fun roundTripLevel2() = runTest { roundTrip(2) }
    @Test fun roundTripLevel3() = runTest { roundTrip(3) }
    @Test fun roundTripLevel4() = runTest { roundTrip(4) }
    @Test fun roundTripLevel5() = runTest { roundTrip(5) }
    @Test fun roundTripLevel6() = runTest { roundTrip(6) }
    @Test fun roundTripLevel7() = runTest { roundTrip(7) }
    @Test fun roundTripLevel8() = runTest { roundTrip(8) }
    @Test fun roundTripLevel9() = runTest { roundTrip(9) }

    suspend fun roundTrip(level: Int) {
        val sb = StringBuilder()
        repeat(1000) {
            sb.append("Hello Multipaz!\n")
        }
        val data = sb.toString().encodeToByteArray()

        val compressedData = data.deflate(level)
        if (level > 0) {
            assertTrue(compressedData.size < data.size)
        }
        val decompressedData = compressedData.inflate()
        assertContentEquals(decompressedData, data)
    }

    @Test
    fun testVector() = runTest {
        val sb = StringBuilder()
        repeat(1000) {
            sb.append("Hello Multipaz!\n")
        }
        val expectedData = sb.toString().encodeToByteArray()

        val knownCompressedDataBase64Url =
            "7cehDcAgEABA3ynoNm8YAoFo8gkIMEzPIL1zFz1zlLpzfbOd9wl3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3f_5S8"
        val decompressedData = knownCompressedDataBase64Url.fromBase64Url().inflate()
        assertContentEquals(expectedData, decompressedData)
    }

    @Test
    fun inflateWithUnsupportedLevel() = runTest {
        assertFailsWith(IllegalArgumentException::class) { byteArrayOf(1, 2).deflate(-10) }
        assertFailsWith(IllegalArgumentException::class) { byteArrayOf(1, 2).deflate(-1) }
        assertFailsWith(IllegalArgumentException::class) { byteArrayOf(1, 2).deflate(10) }
        assertFailsWith(IllegalArgumentException::class) { byteArrayOf(1, 2).deflate(11) }
    }

    @Test
    fun inflateWithInvalidData() = runTest {
        assertFailsWith(IllegalArgumentException::class) {
            byteArrayOf(1, 2).inflate()
        }
    }

    @Test
    fun zlibRoundtripFixed() = runTest {
        // NB: StatusListTest also indirectly tests some pre-defined compressed data.
        val data = byteArrayOf(42, 57, 70, 11, 17)
        assertContentEquals(data, data.zlibDeflate().zlibInflate())
    }

    @Test
    fun zlibHeader() = runTest {
        val data = byteArrayOf(42, 57, 70, 11, 17)
        val compressed = data.zlibDeflate()
        compressed[0] = 0
        assertFailsWith(IllegalArgumentException::class) {
            compressed.zlibInflate()
        }
    }

    @Test
    fun zlibHeaderAnyCompressionLevel() = runTest {
        // FLEVEL, the upper two bits of the second header byte, depends on the compression level
        // used by the producer: 0x7801 for level 1, 0x785E for 2-5, 0x789C for 6 and 0x78DA for
        // 7-9. Level 6 is the default for java.util.zip.Deflater, Python zlib and Node zlib, so
        // rejecting anything other than 0x78DA rejects most producers.
        val expected = "Hello, Multipaz!".encodeToByteArray()
        // "Hello, Multipaz!" deflated at level 6, header 0x789C.
        val level6 = byteArrayOf(
            120, -100, -13, 72, -51, -55, -55, -41, 81, -16, 45, -51, 41, -55, 44, 72,
            -84, 82, 4, 0, 48, 69, 5, -72
        )
        // The same data at level 1, header 0x7801.
        val level1 = byteArrayOf(
            120, 1, -13, 72, -51, -55, -55, -41, 81, -16, 45, -51, 41, -55, 44, 72,
            -84, 82, 4, 0, 48, 69, 5, -72
        )
        assertContentEquals(expected, level6.zlibInflate())
        assertContentEquals(expected, level1.zlibInflate())
    }

    @Test
    fun zlibHeaderRejectsInvalid() = runTest {
        val data = byteArrayOf(42, 57, 70, 11, 17)
        val compressed = data.zlibDeflate()

        // Compression method other than DEFLATE.
        val wrongMethod = compressed.copyOf()
        wrongMethod[0] = 0x79
        assertFailsWith(IllegalArgumentException::class) { wrongMethod.zlibInflate() }

        // FCHECK does not make CMF*256 + FLG a multiple of 31.
        val badCheck = compressed.copyOf()
        badCheck[1] = (compressed[1].toInt() xor 1).toByte()
        assertFailsWith(IllegalArgumentException::class) { badCheck.zlibInflate() }

        // A preset dictionary is not supported: FDICT set, FCHECK adjusted to stay valid.
        val presetDict = compressed.copyOf()
        presetDict[1] = 0xBB.toByte()
        assertFailsWith(IllegalArgumentException::class) { presetDict.zlibInflate() }

        assertFailsWith(IllegalArgumentException::class) { byteArrayOf(120, -38).zlibInflate() }
    }

    @Test
    fun zlibChecksum() = runTest {
        val data = byteArrayOf(42, 57, 70, 11, 17)
        val compressed = data.zlibDeflate()
        compressed[compressed.size - 1] = 0
        assertFailsWith(IllegalArgumentException::class) {
            compressed.zlibInflate()
        }
    }

    @Test
    fun zlibRoundtripRandom() = runTest {
        val data = Random.nextBytes(64000)
        assertContentEquals(data, data.zlibDeflate().zlibInflate())
    }
}