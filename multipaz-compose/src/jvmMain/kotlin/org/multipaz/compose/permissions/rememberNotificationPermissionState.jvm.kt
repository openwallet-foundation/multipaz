package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private object JvmNotificationPermissionState : PermissionState {
    override val isGranted = true

    override suspend fun launchPermissionRequest() {
    }
}

@Composable
actual fun rememberNotificationPermissionState(): PermissionState = remember { JvmNotificationPermissionState }
