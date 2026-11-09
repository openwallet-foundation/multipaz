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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.pickers.rememberImagePicker
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@Composable
actual fun QrCodeScanner(
    modifier: Modifier,
    cameraSelection: CameraSelection,
    captureResolution: CameraCaptureResolution,
    showCameraPreview: Boolean,
    onCodeScanned: (qrCode: String?) -> Unit
) {
    var manualUri by remember { mutableStateOf("") }
    var decodeError by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberImagePicker(allowMultiple = false) { files ->
        val fileBytes = files.firstOrNull()?.toByteArray() ?: return@rememberImagePicker
        try {
            val bufferedImage = ImageIO.read(ByteArrayInputStream(fileBytes))
                ?: throw IllegalArgumentException("Could not decode image")
            val w = bufferedImage.width
            val h = bufferedImage.height
            val pixels = IntArray(w * h)
            bufferedImage.getRGB(0, 0, w, h, pixels, 0, w)
            val source = RGBLuminanceSource(w, h, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(bitmap)
            decodeError = null
            onCodeScanned(result.text)
        } catch (e: Exception) {
            decodeError = "No QR code found in image (${e.message ?: e::class.simpleName})"
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
                onClick = { onCodeScanned(manualUri.trim()) }
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
