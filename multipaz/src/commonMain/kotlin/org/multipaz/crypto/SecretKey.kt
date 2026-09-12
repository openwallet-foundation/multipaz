package org.multipaz.crypto

import kotlin.concurrent.Volatile

/**
 * A cryptographic secret key (symmetric key or shared secret) with memory clearing on disposal.
 *
 * Instances of this class hold raw secret key material in an internal buffer.
 * When the key is no longer needed, callers should invoke [destroy] or [close],
 * or use Kotlin's [use] extension. Upon disposal, the internal key material is
 * overwritten with zeros using [ByteArray.secureZero].
 *
 * As a secondary safety net, an automatic cleaner ([KeyDisposer]) is registered
 * at creation to zero the memory when the object becomes unreachable, guarding
 * against leaks if [close] is inadvertently omitted.
 *
 * @param keyMaterial the initial key material. A defensive copy is stored.
 */
class SecretKey(
    keyMaterial: ByteArray
) : AutoCloseable {
    private val _data: ByteArray = keyMaterial.copyOf()
    private val disposer = KeyDisposer.register(this) {
        _data.secureZero()
    }

    @Volatile
    private var _isDestroyed: Boolean = false

    /**
     * Whether this key has been destroyed.
     */
    val isDestroyed: Boolean
        get() = _isDestroyed

    /**
     * Size of the secret key in bytes.
     *
     * @throws IllegalStateException if this key has been destroyed.
     */
    val size: Int
        get() {
            checkNotDestroyed()
            return _data.size
        }

    /**
     * Returns a defensive copy of the secret key material.
     *
     * Callers should zero the returned array using [ByteArray.secureZero]
     * once finished with it.
     *
     * @throws IllegalStateException if this key has been destroyed.
     */
    val encoded: ByteArray
        get() {
            checkNotDestroyed()
            return _data.copyOf()
        }

    /**
     * Internal direct accessor to the underlying byte array, avoiding
     * defensive copying when passing to platform cryptographic primitives.
     *
     * @throws IllegalStateException if this key has been destroyed.
     */
    internal val data: ByteArray
        get() {
            checkNotDestroyed()
            return _data
        }

    /**
     * Throws [IllegalStateException] if this key has been destroyed.
     */
    fun checkNotDestroyed() {
        check(!_isDestroyed) { "SecretKey has been destroyed" }
    }

    /**
     * Wipes the secret key material from memory and marks this key as destroyed.
     *
     * Subsequent operations on this key will throw [IllegalStateException].
     * Calling [destroy] on an already-destroyed key is a no-op.
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
        if (_isDestroyed) "SecretKey(destroyed)" else "SecretKey(size=${_data.size} bytes)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecretKey) return false
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
