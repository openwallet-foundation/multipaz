package org.multipaz.compose.qrcode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    TODO()
}
