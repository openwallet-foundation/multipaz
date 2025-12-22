package org.multipaz.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

actual suspend fun ByteArray.deflate(compressionLevel: Int): ByteArray {
    require(compressionLevel >=0 && compressionLevel <= 9) {
        "Compression level $compressionLevel is invalid, must be between 0 and 9"
    }
    val compresser = Deflater(compressionLevel, /* nowrap = */true)
    compresser.setInput(this)
    compresser.finish()
    val baos = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!compresser.finished()) {
        val compressedSize = compresser.deflate(buf)
        baos.write(buf, 0, compressedSize)
    }
    return baos.toByteArray()
}

actual suspend fun ByteArray.inflate(): ByteArray {
    val bais = ByteArrayInputStream(this)
    val decompresser = Inflater(/* nowrap = */ true)
    val iis = InflaterInputStream(bais, decompresser)
    val baos = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    try {
        do {
            val length = iis.read(buf)
            if (length > 0) {
                baos.write(buf, 0, length)
            }
        } while (length > 0)
        iis.close();
    } catch (e: Throwable) {
        throw IllegalArgumentException("Error decompressing data", e)
    }
    return baos.toByteArray()
}
