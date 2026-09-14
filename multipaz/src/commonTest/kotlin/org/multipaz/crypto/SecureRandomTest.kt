package org.multipaz.crypto

import org.multipaz.asn1.ASN1Integer
import org.multipaz.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureRandomTest {

    @Test
    fun testNextBytesVariousSizes() {
        val sizes = listOf(0, 1, 2, 15, 16, 31, 32, 64, 256, 1024, 70_000)
        for (size in sizes) {
            val bytes = Crypto.secureRandom.nextBytes(size)
            assertEquals(size, bytes.size)
        }
    }

    @Test
    fun testNextBytesInPlace() {
        val array1 = ByteArray(32) { 0x55.toByte() }
        val returned = Crypto.secureRandom.nextBytes(array1)
        assertEquals(returned, array1)
        // With overwhelming probability, 32 bytes from CSPRNG will not all remain 0x55
        assertFalse(array1.all { it == 0x55.toByte() })
    }

    @Test
    fun testNextBytesSlice() {
        val array = ByteArray(20) { 0xAA.toByte() }
        val fromIndex = 5
        val toIndex = 15
        Crypto.secureRandom.nextBytes(array, fromIndex, toIndex)

        // Elements before fromIndex must remain untouched
        for (i in 0 until fromIndex) {
            assertEquals(0xAA.toByte(), array[i])
        }
        // Elements after toIndex must remain untouched
        for (i in toIndex until array.size) {
            assertEquals(0xAA.toByte(), array[i])
        }
        // Sub-range should have been filled
        val slice = array.sliceArray(fromIndex until toIndex)
        assertFalse(slice.all { it == 0xAA.toByte() })
    }

    @Test
    fun testNextBytesSliceBoundsCheck() {
        val array = ByteArray(10)
        assertFailsWith<IllegalArgumentException> {
            Crypto.secureRandom.nextBytes(array, -1, 5)
        }
        assertFailsWith<IllegalArgumentException> {
            Crypto.secureRandom.nextBytes(array, 0, 11)
        }
        assertFailsWith<IllegalArgumentException> {
            Crypto.secureRandom.nextBytes(array, 6, 5)
        }
    }

    @Test
    fun testPrimitiveGenerators() {
        val random = Crypto.secureRandom

        // nextInt
        val intVal = random.nextInt()
        val boundedInt = random.nextInt(100)
        assertTrue(boundedInt in 0 until 100)
        val rangeInt = random.nextInt(50, 100)
        assertTrue(rangeInt in 50 until 100)

        // nextLong
        val longVal = random.nextLong()
        val boundedLong = random.nextLong(1000L)
        assertTrue(boundedLong in 0L until 1000L)
        val rangeLong = random.nextLong(500L, 1000L)
        assertTrue(rangeLong in 500L until 1000L)

        // nextBoolean (verify both true and false appear over repeated calls)
        var sawTrue = false
        var sawFalse = false
        for (i in 0 until 100) {
            if (random.nextBoolean()) {
                sawTrue = true
            } else {
                sawFalse = true
            }
            if (sawTrue && sawFalse) break
        }
        assertTrue(sawTrue && sawFalse)

        // nextDouble
        val d = random.nextDouble()
        assertTrue(d >= 0.0 && d < 1.0)
        val boundedD = random.nextDouble(10.0)
        assertTrue(boundedD >= 0.0 && boundedD < 10.0)
        val rangeD = random.nextDouble(5.0, 10.0)
        assertTrue(rangeD >= 5.0 && rangeD < 10.0)

        // nextFloat
        val f = random.nextFloat()
        assertTrue(f >= 0.0f && f < 1.0f)

        // nextBits
        assertEquals(0, random.nextBits(0))
        for (bits in 1..32) {
            val value = random.nextBits(bits)
            if (bits < 32) {
                val maxAllowed = (1L shl bits) - 1
                assertTrue(value.toLong() and 0xFFFFFFFFL <= maxAllowed)
                assertTrue(value >= 0)
            }
        }
    }

    @Test
    fun testCollisionAndEntropy() {
        val random = Crypto.secureRandom
        val set = mutableSetOf<String>()
        for (i in 0 until 100) {
            val bytes = random.nextBytes(16)
            val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            assertTrue(set.add(hex), "Collision detected in secure random generation")
        }

        val large = random.nextBytes(1000)
        val distinctBytes = large.toSet()
        // 1000 random bytes should cover a wide range of values (at least 100 unique bytes)
        assertTrue(distinctBytes.size > 100)
    }

    @Test
    fun testIntegrationWithUuidAndAsn1() {
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID(Crypto.secureRandom)
        assertNotEquals(uuid1, uuid2)

        val asn1 = ASN1Integer.fromRandom(128)
        assertTrue(asn1.value.isNotEmpty())
        assertTrue(asn1.value.any { it != 0.toByte() })
    }
}
