package org.multipaz.server.drawing

import kotlinx.io.bytestring.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import jj2000.j2k.encoder.Encoder
import jj2000.j2k.image.DataBlkInt
import jj2000.j2k.image.ImgData
import jj2000.j2k.image.BlkImgDataSrc
import jj2000.j2k.util.ParameterList

import kotlinx.serialization.json.buildJsonObject

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
