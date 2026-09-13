package org.multipaz.crypto

/**
 * A cryptographic secret key (symmetric key or shared secret) with memory clearing on disposal.
 *
 * This is a specialized [SecureByteString] representing symmetric key material or shared secrets.
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
) : SecureByteString(keyMaterial) {

    /**
     * Creates a secret key from an existing [SecureByteString].
     *
     * @param byteString the secure byte string whose key material will be used.
     * @throws IllegalStateException if [byteString] has been destroyed.
     */
    @Throws(IllegalStateException::class)
    constructor(byteString: SecureByteString) : this(byteString.data)

    override fun toString(): String =
        if (isDestroyed) "SecretKey(destroyed)" else "SecretKey(size=$size bytes)"
}

/**
 * Creates a [SecretKey] from this byte array. A defensive copy of the key material is stored.
 */
fun ByteArray.toSecretKey(): SecretKey = SecretKey(this)

/**
 * Converts this [SecureByteString] to a [SecretKey].
 *
 * @throws IllegalStateException if this byte string has been destroyed.
 */
@Throws(IllegalStateException::class)
fun SecureByteString.toSecretKey(): SecretKey = SecretKey(this)
