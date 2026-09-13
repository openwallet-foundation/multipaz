package org.multipaz.crypto

import kotlin.concurrent.Volatile

/**
 * An immutable sequence of bytes containing sensitive data with memory clearing on disposal.
 *
 * Instances of this class hold sensitive data in an internal buffer. When the data is
 * no longer needed, callers should invoke [destroy] or [close], or use Kotlin's [use]
 * extension. Upon disposal, the internal memory is overwritten with zeros using [ByteArray.secureZero].
 *
 * As a secondary safety net, an automatic cleaner ([KeyDisposer]) is registered at creation
 * to zero the memory when the object becomes unreachable, guarding against memory leaks
 * if [close] is inadvertently omitted.
 *
 * @param data the initial data. A defensive copy is stored.
 */
open class SecureByteString(
    data: ByteArray
) : AutoCloseable {
    private val _data: ByteArray = data.copyOf()
    private val disposer = KeyDisposer.register(this) {
        _data.secureZero()
    }

    @Volatile
    private var _isDestroyed: Boolean = false

    /**
     * Whether this byte string has been destroyed.
     */
    val isDestroyed: Boolean
        get() = _isDestroyed

    /**
     * Size of the byte string in bytes.
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     */
    val size: Int
        get() {
            checkNotDestroyed()
            return _data.size
        }

    /**
     * Returns true if this byte string is empty.
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     */
    @Throws(IllegalStateException::class)
    fun isEmpty(): Boolean = size == 0

    /**
     * Returns true if this byte string is not empty.
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     */
    @Throws(IllegalStateException::class)
    fun isNotEmpty(): Boolean = size > 0

    /**
     * Returns the byte at the specified [index].
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     * @throws IndexOutOfBoundsException if [index] is out of bounds.
     */
    @Throws(IllegalStateException::class, IndexOutOfBoundsException::class)
    operator fun get(index: Int): Byte {
        checkNotDestroyed()
        return _data[index]
    }

    /**
     * Returns a defensive copy of the underlying bytes.
     *
     * Callers should zero the returned array using [ByteArray.secureZero]
     * once finished with it.
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     */
    val encoded: ByteArray
        get() {
            checkNotDestroyed()
            return _data.copyOf()
        }

    /**
     * Returns a defensive copy of the underlying bytes as a [ByteArray].
     *
     * Callers should zero the returned array using [ByteArray.secureZero]
     * once finished with it.
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     */
    @Throws(IllegalStateException::class)
    fun toByteArray(): ByteArray = encoded

    /**
     * Internal direct accessor to the underlying byte array, avoiding
     * defensive copying when passing to platform cryptographic primitives.
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     */
    internal val data: ByteArray
        get() {
            checkNotDestroyed()
            return _data
        }

    /**
     * Throws [IllegalStateException] if this byte string has been destroyed.
     *
     * @throws IllegalStateException if this byte string has been destroyed.
     */
    @Throws(IllegalStateException::class)
    fun checkNotDestroyed() {
        check(!_isDestroyed) { "SecureByteString has been destroyed" }
    }

    /**
     * Wipes the data from memory and marks this byte string as destroyed.
     *
     * Subsequent operations on this instance will throw [IllegalStateException].
     * Calling [destroy] on an already-destroyed instance is a no-op.
     */
    fun destroy() {
        if (!_isDestroyed) {
            _isDestroyed = true
            _data.secureZero()
            disposer.dispose()
        }
    }

    override fun close() = destroy()

    override fun toString(): String =
        if (_isDestroyed) "SecureByteString(destroyed)" else "SecureByteString(size=${_data.size} bytes)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureByteString) return false
        checkNotDestroyed()
        other.checkNotDestroyed()
        if (_data.size != other._data.size) return false
        var diff = 0
        for (i in _data.indices) {
            diff = diff or (_data[i].toInt() xor other._data[i].toInt())
        }
        return diff == 0
    }

    override fun hashCode(): Int {
        checkNotDestroyed()
        return _data.contentHashCode()
    }
}

/**
 * Creates a [SecureByteString] from this byte array. A defensive copy of the data is stored.
 */
fun ByteArray.toSecureByteString(): SecureByteString = SecureByteString(this)
