package org.multipaz.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A coroutine-safe Least Recently Used (LRU) cache implementation designed for Kotlin Coroutines.
 *
 * This cache evicts the least recently accessed items when the [maxSize] is exceeded.
 * It uses a [Mutex] to ensure thread safety across suspending functions, making it safe
 * for concurrent access from multiple coroutines.
 *
 * Operations generally run in _O(1)_ time complexity, subject to lock contention.
 *
 * @param K The type of keys maintained by this cache.
 * @param V The type of mapped values.
 * @property maxSize The maximum number of entries the cache can hold before evicting the oldest.
 */
class LruCache<K, V>(private val maxSize: Int) {
    /**
     * Internal storage. Note: We do not use the `accessOrder` flag of LinkedHashMap here
     * because we manually handle the re-ordering in [get] and [put] to ensure
     * predictable behavior within the lock.
     */
    private val cache = LinkedHashMap<K, V>(maxSize)
    private val mutex = Mutex()

    /**
     * Associates the specified [value] with the specified [key] in the cache.
     *
     * If the cache previously contained a mapping for the key, the old value is replaced
     * and the entry is moved to the "most recently used" position (tail).
     *
     * If the cache is full (size > [maxSize]) after the insertion, the "least recently used"
     * entry (head) is evicted.
     *
     * @param key The key with which the specified value is to be associated.
     * @param value The value to be associated with the specified key.
     */
    suspend fun put(key: K, value: V) {
        mutex.withLock {
            // Remove if exists to update position to tail (most recently used)
            if (cache.containsKey(key)) {
                cache.remove(key)
            }
            cache[key] = value

            // Evict oldest (head of the map) if we exceed size
            if (cache.size > maxSize) {
                val it = cache.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
    }

    /**
     * Returns the value to which the specified [key] is mapped, or `null` if this cache
     * contains no mapping for the key.
     *
     * **Side Effect:** A successful retrieval marks the entry as "most recently used,"
     * moving it to the end of the eviction queue.
     *
     * @param key The key whose associated value is to be returned.
     * @return The value associated with the key, or `null` if the key is not found.
     */
    suspend fun get(key: K): V? {
        mutex.withLock {
            return cache.remove(key)?.also {
                cache[key] = it
            }
        }
    }

    /**
     * Removes the mapping for the specified [key] from this cache if present.
     *
     * @param key The key whose mapping is to be removed.
     * @return The previous value associated with [key], or `null` if there was no mapping.
     */
    suspend fun remove(key: K): V? {
        mutex.withLock {
            return cache.remove(key)
        }
    }

    /**
     * Removes all of the mappings from this cache.
     * The cache will be empty after this call returns.
     */
    suspend fun clear() {
        mutex.withLock {
            cache.clear()
        }
    }
}