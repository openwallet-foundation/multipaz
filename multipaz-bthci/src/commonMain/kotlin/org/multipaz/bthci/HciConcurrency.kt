package org.multipaz.bthci

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal expect class HciLock() {
    fun <T> withLock(block: () -> T): T
}

internal expect suspend fun openDefaultHciPacketIo(): HciPacketIo

@OptIn(ExperimentalAtomicApi::class)
internal class HciAtomicInt(initial: Int) {
    private val delegate = AtomicInt(initial)

    fun get(): Int = delegate.load()

    fun set(value: Int) {
        delegate.store(value)
    }

    fun incrementAndGet(): Int = delegate.addAndFetch(1)

    fun getAndIncrement(): Int = delegate.fetchAndAdd(1)
}

internal class HciSyncMap<K, V> {
    private val lock = HciLock()
    private val map = mutableMapOf<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }

    operator fun set(key: K, value: V) {
        lock.withLock { map[key] = value }
    }

    fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }

    fun remove(key: K): V? = lock.withLock { map.remove(key) }

    fun clear() {
        lock.withLock { map.clear() }
    }

    fun valuesCopy(): List<V> = lock.withLock { map.values.toList() }
}

internal class HciSyncSet<E> {
    private val lock = HciLock()
    private val set = mutableSetOf<E>()

    fun add(element: E): Boolean = lock.withLock { set.add(element) }

    fun remove(element: E): Boolean = lock.withLock { set.remove(element) }

    fun contains(element: E): Boolean = lock.withLock { set.contains(element) }

    fun isEmpty(): Boolean = lock.withLock { set.isEmpty() }

    fun toList(): List<E> = lock.withLock { set.toList() }

    fun clear() {
        lock.withLock { set.clear() }
    }
}

internal class HciByteBuffer(initialCapacity: Int = 32) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
    private var count = 0

    val size: Int
        get() = count

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= buf.size) return
        var newSize = buf.size * 2
        if (newSize < minCapacity) newSize = minCapacity
        buf = buf.copyOf(newSize)
    }

    fun write(b: Int) {
        ensureCapacity(count + 1)
        buf[count++] = b.toByte()
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length <= 0) return
        ensureCapacity(count + length)
        bytes.copyInto(buf, destinationOffset = count, startIndex = offset, endIndex = offset + length)
        count += length
    }

    fun reset() {
        count = 0
    }

    fun toByteArray(): ByteArray = buf.copyOf(count)
}
