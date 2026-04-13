package org.multipaz.openid4vci.request

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Encodes text string (passed as `q` parameter) into a QR code.
 */
suspend fun qrCode(call: ApplicationCall) {
    val str = call.request.queryParameters["q"]
        ?: throw IllegalStateException("q parameter required")
    val qr = Encoder.encode(str, ErrorCorrectionLevel.L, null)
    val matrix = qr.matrix
    val width = matrix.width
    val height = matrix.height
    val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    val pixels = (image.raster.dataBuffer as DataBufferByte).data
    var index = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[index++] = if (matrix[x, y].toInt() == 0) -1 else 0
        }
    }
    call.respondBytes(ContentType.Image.PNG) {
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)  // not actually blocking, writing to memory-based stream
        bytes.toByteArray()
    }
}