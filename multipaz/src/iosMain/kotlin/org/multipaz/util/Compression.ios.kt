package org.multipaz.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import platform.Foundation.NSMutableData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.compressUsingAlgorithm
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate as zlibInflateNative
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
actual suspend fun ByteArray.deflate(compressionLevel: Int): ByteArray {
    require(compressionLevel >= 0 && compressionLevel <= 9) {
        "Compression level $compressionLevel is invalid, must be between 0 and 9"
    }
    // Note: This API doesn't allow setting the compressionLevel, it's always hardcoded to 5
    val data = toNSData().mutableCopy() as NSMutableData
    data.compressUsingAlgorithm(
        algorithm = NSDataCompressionAlgorithmZlib,
        error = null
    )
    return data.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun ByteArray.inflate(maxSize: Int): ByteArray {
    require(maxSize >= 0) { "maxSize must be non-negative, was $maxSize" }
    if (isEmpty()) {
        throw IllegalArgumentException("Error decompressing data")
    }
    return memScoped {
        val stream = alloc<z_stream>()
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.avail_in = 0u
        stream.next_in = null

        val initResult = inflateInit2_(
            strm = stream.ptr,
            windowBits = -MAX_WBITS,
            version = ZLIB_VERSION,
            stream_size = sizeOf<z_stream>().toInt()
        )
        if (initResult != Z_OK) {
            throw IllegalArgumentException("Error initializing decompression: $initResult")
        }

        try {
            val output = Buffer()
            val outBuf = ByteArray(4096)
            var totalBytes = 0
            this@inflate.usePinned { pinnedIn ->
                outBuf.usePinned { pinnedOut ->
                    stream.next_in = pinnedIn.addressOf(0).reinterpret()
                    stream.avail_in = this@inflate.size.toUInt()
                    while (true) {
                        stream.next_out = pinnedOut.addressOf(0).reinterpret()
                        stream.avail_out = outBuf.size.toUInt()
                        val result = zlibInflateNative(stream.ptr, Z_NO_FLUSH)
                        if (result != Z_OK && result != Z_STREAM_END) {
                            throw IllegalArgumentException("Error decompressing data: $result")
                        }
                        val produced = outBuf.size - stream.avail_out.toInt()
                        if (produced > 0) {
                            if (produced > maxSize - totalBytes) {
                                throw IllegalArgumentException(
                                    "Decompressed data exceeds maximum size of $maxSize bytes"
                                )
                            }
                            output.write(outBuf, 0, produced)
                            totalBytes += produced
                        }
                        if (result == Z_STREAM_END) {
                            break
                        }
                        if (produced == 0 && stream.avail_in == 0u) {
                            throw IllegalArgumentException("Error decompressing data")
                        }
                    }
                }
            }
            output.readByteArray()
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}
