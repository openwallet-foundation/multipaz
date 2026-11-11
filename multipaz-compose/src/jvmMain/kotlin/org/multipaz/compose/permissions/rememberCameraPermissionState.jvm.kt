package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private object JvmCameraPermissionState : PermissionState {
    override val isGranted = true

    override suspend fun launchPermissionRequest() {
    }
}

@Composable
actual fun rememberCameraPermissionState(): PermissionState = remember { JvmCameraPermissionState }
