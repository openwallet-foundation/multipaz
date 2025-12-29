package org.multipaz.compose.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun Camera(
    modifier: Modifier,
    cameraSelection: CameraSelection,
    captureResolution: CameraCaptureResolution,
    showCameraPreview: Boolean,
    onFrameCaptured: suspend (frame: CameraFrame) -> Unit
) {
    TODO("Camera not yet implemented")
}
