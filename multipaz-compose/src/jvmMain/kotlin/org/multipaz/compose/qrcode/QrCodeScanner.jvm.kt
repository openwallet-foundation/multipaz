package org.multipaz.compose.qrcode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.pickers.rememberImagePicker
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private const val MAX_IMAGE_PIXELS = 25_000_000L

private val QR_DECODE_HINTS = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
)

@Composable
actual fun QrCodeScanner(
    modifier: Modifier,
    cameraSelection: CameraSelection,
    captureResolution: CameraCaptureResolution,
    showCameraPreview: Boolean,
    onCodeScanned: (qrCode: String?) -> Unit
) {
    val currentOnCodeScanned = rememberUpdatedState(onCodeScanned)
    val scope = rememberCoroutineScope()
    var manualUri by remember { mutableStateOf("") }
    var decodeError by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberImagePicker(allowMultiple = false) { files ->
        val fileBytes = files.firstOrNull()?.toByteArray() ?: return@rememberImagePicker
        scope.launch {
            try {
                val decodedText = withContext(Dispatchers.Default) {
                    val bufferedImage = ImageIO.read(ByteArrayInputStream(fileBytes))
                        ?: throw IllegalArgumentException("Could not decode image")
                    val w = bufferedImage.width
                    val h = bufferedImage.height
                    require(w > 0 && h > 0 && w.toLong() * h.toLong() <= MAX_IMAGE_PIXELS) {
                        "Image dimensions out of bounds (${w}x${h})"
                    }
                    // Composite onto an opaque white canvas so PNG QR codes with transparent
                    // backgrounds do not collapse to all-black pixels in getRGB().
                    val rgbImage = if (bufferedImage.type == BufferedImage.TYPE_INT_RGB) {
                        bufferedImage
                    } else {
                        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { target ->
                            val g = target.createGraphics()
                            try {
                                g.color = AwtColor.WHITE
                                g.fillRect(0, 0, w, h)
                                g.drawImage(bufferedImage, 0, 0, null)
                            } finally {
                                g.dispose()
                            }
                        }
                    }
                    val pixels = IntArray(w * h)
                    rgbImage.getRGB(0, 0, w, h, pixels, 0, w)
                    val source = RGBLuminanceSource(w, h, pixels)
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    MultiFormatReader().decode(bitmap, QR_DECODE_HINTS).text
                }
                decodeError = null
                currentOnCodeScanned.value(decodedText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                decodeError = "No QR code found in image (${e.message ?: e::class.simpleName})"
            }
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = manualUri,
            onValueChange = { manualUri = it },
            label = { Text("QR Code URI (e.g. mdoc:...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = manualUri.isNotBlank(),
                onClick = { currentOnCodeScanned.value(manualUri.trim()) }
            ) {
                Text("Use QR URI")
            }
            OutlinedButton(
                onClick = { imagePicker.launch() }
            ) {
                Text("Load QR Image...")
            }
        }
        if (decodeError != null) {
            Text(decodeError!!)
        }
    }
}
