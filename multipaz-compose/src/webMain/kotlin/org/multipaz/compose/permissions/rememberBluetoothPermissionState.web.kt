package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable

private class WebBluetoothPermissionState: PermissionState {
    override val isGranted = true  // TODO
    override suspend fun launchPermissionRequest() {
        TODO()
    }
}

@Composable
actual fun rememberBluetoothPermissionState(): PermissionState {
    return WebBluetoothPermissionState()
}

