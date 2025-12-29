package org.multipaz.util

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
import kotlin.js.unsafeCast

actual suspend fun ByteArray.deflate(compressionLevel: Int): ByteArray {
    require(compressionLevel >=0 && compressionLevel <= 9) {
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

actual suspend fun ByteArray.inflate(): ByteArray {
    val jsData = this.toUint8Array()
    val blob = Blob(jsArrayOf(jsData))
    val inputStream = blob.stream()
    val decompressor = DecompressionStream(CompressionFormat.deflateRaw)
    val transform = decompressor.unsafeCast<ReadableWritablePair<Uint8Array<*>, Uint8Array<ArrayBuffer>>>()
    try {
        val outputStream = inputStream.pipeThrough(transform)
        val decompressedBuffer = Response(outputStream as BodyInit?).arrayBuffer()
        return Int8Array(decompressedBuffer).toByteArray()
    } catch (e: Throwable) {
        throw IllegalArgumentException("Error decompressing data", e)
    }
}