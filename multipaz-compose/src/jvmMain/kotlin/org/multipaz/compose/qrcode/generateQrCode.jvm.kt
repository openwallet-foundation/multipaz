package org.multipaz.compose.qrcode

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import java.awt.image.BufferedImage

actual fun generateQrCode(
    url: String,
): ImageBitmap {
    val width = 800
    val result: BitMatrix = try {
        MultiFormatWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            width,
            width,
            null
        )
    } catch (e: WriterException) {
        throw IllegalArgumentException(e)
    }
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until h) {
        val offset = y * w
        for (x in 0 until w) {
            pixels[offset + x] = if (result[x, y]) black else white
        }
    }
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, w, h, pixels, 0, w)
    return image.toComposeImageBitmap()
}
