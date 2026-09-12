package org.multipaz.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.memset

@OptIn(ExperimentalForeignApi::class)
actual fun ByteArray.secureZero() {
    if (isEmpty()) return
    fill(0)
    usePinned { pinned ->
        memset(pinned.addressOf(0), 0, size.toULong())
    }
}
