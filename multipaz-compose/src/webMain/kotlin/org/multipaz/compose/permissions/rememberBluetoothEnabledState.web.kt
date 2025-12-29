package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class BluetoothEnabledState {
    actual suspend fun enable() {
        TODO()
    }

    actual val isEnabled = true  // TODO
}

@Composable
actual fun rememberBluetoothEnabledState(): BluetoothEnabledState {
    val state = remember { BluetoothEnabledState() }
    return state
}