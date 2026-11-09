package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable

private class JvmCameraPermissionState : PermissionState {
    override val isGranted = true

    override suspend fun launchPermissionRequest() {
    }
}

@Composable
actual fun rememberCameraPermissionState(): PermissionState = JvmCameraPermissionState()
