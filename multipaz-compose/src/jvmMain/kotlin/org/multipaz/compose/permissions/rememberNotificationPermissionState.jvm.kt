package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable

private class JvmNotificationPermissionState : PermissionState {
    override val isGranted = true

    override suspend fun launchPermissionRequest() {
    }
}

@Composable
actual fun rememberNotificationPermissionState(): PermissionState = JvmNotificationPermissionState()
