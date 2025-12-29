package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable

private class WebNotificationPermissionState: PermissionState {
    override val isGranted = true  // TODO
    override suspend fun launchPermissionRequest() {
        TODO()
    }
}

@Composable
actual fun rememberNotificationPermissionState(): PermissionState {
    return WebNotificationPermissionState()
}

