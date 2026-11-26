package org.multipaz.util

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

actual suspend fun ByteArray.deflate(compressionLevel: Int): ByteArray {
    require(compressionLevel >= 0 && compressionLevel <= 9) {
        "Compression level $compressionLevel is invalid, must be between 0 and 9"
    }
    val compresser = Deflater(compressionLevel, /* nowrap = */ true)
    try {
        compresser.setInput(this)
        compresser.finish()
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!compresser.finished()) {
            val compressedSize = compresser.deflate(buf)
            baos.write(buf, 0, compressedSize)
        }
        return baos.toByteArray()
    } finally {
        compresser.end()
    }
}

actual suspend fun ByteArray.inflate(maxSize: Int): ByteArray {
    require(maxSize >= 0) { "maxSize must be non-negative, was $maxSize" }
    val bais = ByteArrayInputStream(this)
    val decompresser = Inflater(/* nowrap = */ true)
    val iis = InflaterInputStream(bais, decompresser)
    val baos = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    try {
        while (true) {
            val length = iis.read(buf)
            if (length == -1) {
                break
            }
            if (length > 0) {
                if (length > maxSize - baos.size()) {
                    throw IllegalArgumentException(
                        "Decompressed data exceeds maximum size of $maxSize bytes"
                    )
                }
                baos.write(buf, 0, length)
            }
        }
        iis.close()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("Error decompressing data", e)
    } finally {
        decompresser.end()
    }
    return baos.toByteArray()
}
