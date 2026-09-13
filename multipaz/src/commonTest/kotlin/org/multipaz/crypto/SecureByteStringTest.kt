package org.multipaz.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureByteStringTest {

    @Test
    fun testCreationAndProperties() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val sbs = SecureByteString(raw)
        assertEquals(8, sbs.size)
        assertFalse(sbs.isEmpty())
        assertTrue(sbs.isNotEmpty())
        assertFalse(sbs.isDestroyed)
        assertContentEquals(raw, sbs.encoded)
        assertContentEquals(raw, sbs.toByteArray())
        assertContentEquals(raw, sbs.data)
        assertEquals(1, sbs[0])
        assertEquals(8, sbs[7])
        assertEquals("SecureByteString(size=8 bytes)", sbs.toString())
    }

    @Test
    fun testEmpty() {
        val sbs = SecureByteString(byteArrayOf())
        assertEquals(0, sbs.size)
        assertTrue(sbs.isEmpty())
        assertFalse(sbs.isNotEmpty())
    }

    @Test
    fun testDefensiveCopying() {
        val raw = byteArrayOf(10, 20, 30, 40)
        val sbs = SecureByteString(raw)
        raw[0] = 99
        assertEquals(10, sbs.encoded[0])
        assertEquals(10, sbs.toByteArray()[0])
        assertEquals(10, sbs.data[0])

        val exported1 = sbs.encoded
        exported1[0] = 77
        assertEquals(10, sbs.encoded[0])

        val exported2 = sbs.toByteArray()
        exported2[0] = 88
        assertEquals(10, sbs.toByteArray()[0])
    }

    @Test
    fun testDestroy() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val sbs = SecureByteString(raw)
        assertFalse(sbs.isDestroyed)

        sbs.destroy()
        assertTrue(sbs.isDestroyed)
        assertEquals("SecureByteString(destroyed)", sbs.toString())

        assertFailsWith<IllegalStateException> { sbs.size }
        assertFailsWith<IllegalStateException> { sbs.isEmpty() }
        assertFailsWith<IllegalStateException> { sbs.isNotEmpty() }
        assertFailsWith<IllegalStateException> { sbs[0] }
        assertFailsWith<IllegalStateException> { sbs.encoded }
        assertFailsWith<IllegalStateException> { sbs.toByteArray() }
        assertFailsWith<IllegalStateException> { sbs.data }
        assertFailsWith<IllegalStateException> { sbs.checkNotDestroyed() }

        // Redundant destroy and close are no-ops
        sbs.destroy()
        sbs.close()
        assertTrue(sbs.isDestroyed)
    }

    @Test
    fun testAutoCloseableUse() {
        val sbs = SecureByteString(byteArrayOf(1, 2, 3))
        sbs.use {
            assertEquals(3, it.size)
            assertFalse(it.isDestroyed)
        }
        assertTrue(sbs.isDestroyed)
    }

    @Test
    fun testEqualityAndHashCode() {
        val sbs1 = SecureByteString(byteArrayOf(1, 2, 3))
        val sbs2 = SecureByteString(byteArrayOf(1, 2, 3))
        val sbs3 = SecureByteString(byteArrayOf(1, 2, 4))
        val sbs4 = SecureByteString(byteArrayOf(1, 2))

        assertEquals(sbs1, sbs2)
        assertEquals(sbs1.hashCode(), sbs2.hashCode())
        assertNotEquals(sbs1, sbs3)
        assertNotEquals(sbs1, sbs4)

        sbs1.destroy()
        assertFailsWith<IllegalStateException> { sbs1 == sbs2 }
        assertFailsWith<IllegalStateException> { sbs2 == sbs1 }
        assertFailsWith<IllegalStateException> { sbs1.hashCode() }
    }

    @Test
    fun testExtensionFunctions() {
        val raw = byteArrayOf(5, 6, 7)
        val sbs = raw.toSecureByteString()
        assertEquals(3, sbs.size)
        assertContentEquals(raw, sbs.toByteArray())

        val key = sbs.toSecretKey()
        assertEquals(3, key.size)
        assertContentEquals(raw, key.encoded)
    }

    @Test
    fun testSecretKeyInheritance() {
        val raw = byteArrayOf(42, 43, 44)
        val key = SecretKey(raw)
        assertTrue(key is SecureByteString)
        assertEquals(3, key.size)
        assertEquals("SecretKey(size=3 bytes)", key.toString())

        val sbs = SecureByteString(raw)
        val keyFromSbs = SecretKey(sbs)
        assertEquals(key, keyFromSbs)
        assertEquals(sbs, key)
    }
}
