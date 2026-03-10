package org.multipaz.compose.qrcode

import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection

@Composable
actual fun QrCodeScanner(
    modifier: Modifier,
    cameraSelection: CameraSelection,
    captureResolution: CameraCaptureResolution,
    showCameraPreview: Boolean,
    onCodeScanned: (qrCode: String?) -> Unit
) {
    var lastCodeScanned by remember { mutableStateOf<String?>(null) }
    Camera(
        modifier = modifier,
        cameraSelection = cameraSelection,
        captureResolution = captureResolution,
        showCameraPreview = showCameraPreview,
        onFrameCaptured = { cameraFrame ->
            val imageProxy = cameraFrame.cameraImage.imageProxy
            val yuvData = if (imageProxy.planes[0].buffer.hasArray()) {
                imageProxy.planes[0].buffer.array()
            } else {
                val byteArray = ByteArray(imageProxy.planes[0].buffer.remaining())
                imageProxy.planes[0].buffer.get(byteArray)
                byteArray
            }
            val source = PlanarYUVLuminanceSource(
                /* yuvData = */ yuvData,
                /* dataWidth = */ imageProxy.planes[0].rowStride,
                /* dataHeight = */ imageProxy.height,
                /* left = */ 0,
                /* top = */ 0,
                /* width = */ imageProxy.width,
                /* height = */ imageProxy.height,
                /* reverseHorizontal = */ false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source));
            try {
                val result = MultiFormatReader().decode(bitmap)
                if (result.text != lastCodeScanned) {
                    onCodeScanned(result.text)
                    lastCodeScanned = result.text
                }
            } catch (e : Exception) {
                if (e is CancellationException) throw e
                // QR code not found in this frame
                if (lastCodeScanned != null) {
                    onCodeScanned(null)
                    lastCodeScanned = null
                }
            }
        }
    )
}
