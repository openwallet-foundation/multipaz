package org.multipaz.crypto

import java.util.Arrays

actual fun ByteArray.secureZero() {
    Arrays.fill(this, 0.toByte())
    // Volatile write barrier to prevent JIT dead-store elimination
    SecureZeroBarrier.sink = if (this.isNotEmpty()) this[0] else 0.toByte()
}

internal object SecureZeroBarrier {
    @Volatile
    var sink: Byte = 0
}
