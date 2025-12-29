package org.multipaz.util

import js.buffer.BufferSource
import js.typedarrays.toInt8Array
import org.khronos.webgl.Int8Array
import kotlin.js.unsafeCast

/**
 * Converts to a [BufferSource]
 *
 * @receiver a [ByteArray].
 * @return a [BufferSource]
 */
fun ByteArray.toBufferSource(): BufferSource {
    return this.toInt8Array().unsafeCast<BufferSource>()
}
