package org.multipaz.util

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LruCacheTest {
    @Test
    fun putAndGetRetrieveCorrectValues() = runTest {
        val cache = LruCache<String, Int>(maxSize = 3)

        cache.put("A", 1)
        cache.put("B", 2)

        assertEquals(1, cache.get("A"))
        assertEquals(2, cache.get("B"))
        assertNull(cache.get("C"))
    }

    @Test
    fun evictsOldestItemWhenMaxSizeExceeded() = runTest {
        val cache = LruCache<String, Int>(maxSize = 2)

        cache.put("A", 1)
        cache.put("B", 2)
        // Cache is full: [A, B]

        cache.put("C", 3)
        // Should evict A. Cache is now: [B, C]

        assertNull(cache.get("A"), "A should be evicted")
        assertEquals(2, cache.get("B"))
        assertEquals(3, cache.get("C"))
    }

    @Test
    fun getUpdatesEvictionOrder() = runTest {
        val cache = LruCache<String, Int>(maxSize = 2)

        cache.put("A", 1)
        cache.put("B", 2)
        // Order: A (oldest), B (newest)

        // Access A. This should move A to the tail (newest).
        cache.get("A")
        // Order: B (oldest), A (newest)

        cache.put("C", 3)
        // Should evict B (because A was just accessed)

        assertNull(cache.get("B"), "B should be evicted because A was accessed recently")
        assertEquals(1, cache.get("A"))
        assertEquals(3, cache.get("C"))
    }

    @Test
    fun updatingExistingKeyMovesItToNewestPosition() = runTest {
        val cache = LruCache<String, Int>(maxSize = 2)

        cache.put("A", 1)
        cache.put("B", 2)
        // Order: A, B

        // Update A
        cache.put("A", 10)
        // Order: B, A (A is now newest)

        cache.put("C", 3)
        // Should evict B

        assertNull(cache.get("B"), "B should be evicted")
        assertEquals(10, cache.get("A"), "A should have the updated value")
        assertEquals(3, cache.get("C"))
    }

    @Test
    fun removeDeletesItemAndReturnsValue() = runTest {
        val cache = LruCache<String, Int>(maxSize = 3)
        cache.put("A", 1)

        val removedValue = cache.remove("A")

        assertEquals(1, removedValue)
        assertNull(cache.get("A"))
    }

    @Test
    fun clearEmptiesTheCache() = runTest {
        val cache = LruCache<String, Int>(maxSize = 3)
        cache.put("A", 1)
        cache.put("B", 2)

        cache.clear()

        assertNull(cache.get("A"))
        assertNull(cache.get("B"))
        // Verify we can start fresh
        cache.put("C", 3)
        assertEquals(3, cache.get("C"))
    }
}