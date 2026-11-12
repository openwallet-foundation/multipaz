package org.multipaz.server.drawing

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.multipaz.util.fromHex
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class CanvasTest {

    @Test
    fun testLoadPngImage() {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 16, 16)
        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)

        val canvas = Canvas.createBlank(16, 16)
        canvas.drawImage(ByteString(baos.toByteArray()), buildJsonObject {})
        assertEquals(16, canvas.width)
        assertEquals(16, canvas.height)
    }

    @Test
    fun testLoadJpeg2000Image() {
        val jp2Bytes = (
            "0000000c6a5020200d0a870a00000014667479706a703220000000006a703220" +
            "0000002d6a703268000000166968647200000004000000040003070700000000" +
            "000f636f6c7201000000000010000000c46a703263ff4fff51002f0000000000" +
            "0400000004000000000000000000000004000000040000000000000000000307" +
            "0101070101070101ff52000c00000001000004040001ff5c00044040ff640025" +
            "000143726561746564206279204f70656e4a5045472076657273696f6e20322e" +
            "352e34ff90000a00000000004c0001ff93df80881769662d5c26ed9824684c07" +
            "bb4e16a176df80a011f186279e38ef018637ebc31972f7d5f4a921b7df808012" +
            "5c074b597d96665366bcef74c36b6effd9"
        ).fromHex()

        val canvas = Canvas.createBlank(4, 4)
        canvas.drawImage(ByteString(jp2Bytes), buildJsonObject {})
        val pixels = canvas.toPixels()
        assertEquals(0xFFFF0000.toInt(), pixels[0])
        assertEquals(0xFF00FF00.toInt(), pixels[1])
        assertEquals(0xFF0000FF.toInt(), pixels[2])
        assertEquals(0xFFFFFFFF.toInt(), pixels[3])
    }

    @Test
    fun testToJpegAndToDataUrl() {
        val canvas = Canvas.createBlank(810, 510)
        val jpegBytes = canvas.toJpeg()
        assertNotNull(jpegBytes)
        org.junit.Assert.assertTrue(jpegBytes.isNotEmpty())

        val dataUrl = canvas.toDataUrl()
        org.junit.Assert.assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"))

        val decoded = Canvas.createBlank(16, 16)
        decoded.drawImage(ByteString(jpegBytes), buildJsonObject {})
        assertEquals(16, decoded.width)
        assertEquals(16, decoded.height)
    }
}
