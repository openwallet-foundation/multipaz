package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable

private class WebCameraPermissionState: PermissionState {
    override val isGranted = true  // TODO
    override suspend fun launchPermissionRequest() {
        TODO()
    }
}

@Composable
actual fun rememberCameraPermissionState(): PermissionState {
    return WebCameraPermissionState()
}

