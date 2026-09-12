package org.multipaz.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretKeyTest {

    @Test
    fun testCreationAndProperties() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val key = SecretKey(raw)
        assertEquals(8, key.size)
        assertFalse(key.isDestroyed)
        assertContentEquals(raw, key.encoded)
        assertContentEquals(raw, key.data)
        assertEquals("SecretKey(size=8 bytes)", key.toString())
    }

    @Test
    fun testDefensiveCopying() {
        val raw = byteArrayOf(10, 20, 30, 40)
        val key = SecretKey(raw)
        raw[0] = 99
        assertEquals(10, key.encoded[0])
        assertEquals(10, key.data[0])

        val exported = key.encoded
        exported[0] = 77
        assertEquals(10, key.encoded[0])
    }

    @Test
    fun testDestroy() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val key = SecretKey(raw)
        assertFalse(key.isDestroyed)

        key.destroy()
        assertTrue(key.isDestroyed)
        assertEquals("SecretKey(destroyed)", key.toString())

        assertFailsWith<IllegalStateException> { key.size }
        assertFailsWith<IllegalStateException> { key.encoded }
        assertFailsWith<IllegalStateException> { key.data }
        assertFailsWith<IllegalStateException> { key.checkNotDestroyed() }

        // Redundant destroy is a no-op
        key.destroy()
        key.close()
        assertTrue(key.isDestroyed)
    }

    @Test
    fun testAutoCloseableUse() {
        val key = SecretKey(byteArrayOf(1, 2, 3))
        key.use {
            assertEquals(3, it.size)
            assertFalse(it.isDestroyed)
        }
        assertTrue(key.isDestroyed)
    }

    @Test
    fun testEqualityAndHashCode() {
        val key1 = SecretKey(byteArrayOf(1, 2, 3))
        val key2 = SecretKey(byteArrayOf(1, 2, 3))
        val key3 = SecretKey(byteArrayOf(1, 2, 4))
        val key4 = SecretKey(byteArrayOf(1, 2))

        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
        assertNotEquals(key1, key3)
        assertNotEquals(key1, key4)

        key1.destroy()
        assertFailsWith<IllegalStateException> { key1 == key2 }
        assertFailsWith<IllegalStateException> { key2 == key1 }
        assertFailsWith<IllegalStateException> { key1.hashCode() }
    }
}
