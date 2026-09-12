package org.multipaz.crypto

actual fun ByteArray.secureZero() {
    fill(0)
}
