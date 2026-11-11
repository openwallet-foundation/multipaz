package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class BluetoothEnabledState {
    actual suspend fun enable() {
    }

    actual val isEnabled = true
}

@Composable
actual fun rememberBluetoothEnabledState(): BluetoothEnabledState {
    return remember { BluetoothEnabledState() }
}
