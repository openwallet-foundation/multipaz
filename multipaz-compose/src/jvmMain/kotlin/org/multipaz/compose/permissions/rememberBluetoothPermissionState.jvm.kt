package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable

private class JvmBluetoothPermissionState : PermissionState {
    override val isGranted = true

    override suspend fun launchPermissionRequest() {
    }
}

@Composable
actual fun rememberBluetoothPermissionState(): PermissionState = JvmBluetoothPermissionState()
