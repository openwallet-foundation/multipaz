package org.multipaz.util

import js.buffer.BufferSource
import org.khronos.webgl.Int8Array

/**
 * Converts to a [BufferSource]
 *
 * @receiver a [ByteArray].
 * @return a [BufferSource]
 */
fun ByteArray.toBufferSource(): BufferSource {
    return this.unsafeCast<Int8Array>().unsafeCast<BufferSource>()
}
