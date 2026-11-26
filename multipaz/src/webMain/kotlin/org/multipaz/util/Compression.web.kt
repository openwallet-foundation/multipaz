package org.multipaz.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import js.array.jsArrayOf
import js.buffer.ArrayBuffer
import js.typedarrays.Int8Array
import js.typedarrays.Uint8Array
import js.typedarrays.toByteArray
import js.typedarrays.toUint8Array
import web.blob.Blob
import web.compression.CompressionFormat
import web.compression.CompressionStream
import web.compression.DecompressionStream
import web.compression.deflateRaw
import web.http.BodyInit
import web.http.Response
import web.http.arrayBuffer
import web.streams.ReadableWritablePair
import web.streams.cancel
import web.streams.read
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsException
import kotlin.js.unsafeCast

actual suspend fun ByteArray.deflate(compressionLevel: Int): ByteArray {
    require(compressionLevel >= 0 && compressionLevel <= 9) {
        "Compression level $compressionLevel is invalid, must be between 0 and 9"
    }
    val jsData = this.toUint8Array()
    val blob = Blob(jsArrayOf(jsData))
    val inputStream = blob.stream()
    val compressor = CompressionStream(CompressionFormat.deflateRaw)
    val transform = compressor.unsafeCast<ReadableWritablePair<Uint8Array<*>, Uint8Array<ArrayBuffer>>>()
    val outputStream = inputStream.pipeThrough(transform)
    val compressedBuffer = Response(outputStream as BodyInit?).arrayBuffer()
    return Int8Array(compressedBuffer).toByteArray()
}

@OptIn(ExperimentalWasmJsInterop::class)
actual suspend fun ByteArray.inflate(maxSize: Int): ByteArray {
    require(maxSize >= 0) { "maxSize must be non-negative, was $maxSize" }
    val jsData = this.toUint8Array()
    val blob = Blob(jsArrayOf(jsData))
    val inputStream = blob.stream()
    val decompressor = DecompressionStream(CompressionFormat.deflateRaw)
    val transform = decompressor.unsafeCast<ReadableWritablePair<Uint8Array<*>, Uint8Array<ArrayBuffer>>>()
    val outputStream = inputStream.pipeThrough(transform)
    val reader = outputStream.getReader()
    val buffer = Buffer()
    var totalSize = 0
    var done = false
    try {
        while (true) {
            val result = reader.read()
            if (result.done) {
                done = true
                break
            }
            val chunk = result.value ?: continue
            val chunkSize = chunk.byteLength
            if (chunkSize > maxSize - totalSize) {
                throw IllegalArgumentException(
                    "Decompressed data exceeds maximum size of $maxSize bytes"
                )
            }
            if (chunkSize > 0) {
                buffer.write(chunk.toByteArray())
                totalSize += chunkSize
            }
        }
        return buffer.readByteArray()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: JsException) {
        throw IllegalArgumentException("Error decompressing data", e)
    } catch (e: Exception) {
        throw IllegalArgumentException("Error decompressing data", e)
    } finally {
        if (!done) {
            withContext(NonCancellable) {
                try {
                    reader.cancel()
                } catch (_: JsException) {
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        try {
            reader.releaseLock()
        } catch (_: JsException) {
        }
    }
}