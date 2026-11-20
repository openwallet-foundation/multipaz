package org.multipaz.testapp.ui

import androidx.compose.runtime.Composable
import org.multipaz.testapp.TestAppSettingsModel

@Composable
actual fun PlatformBluetoothAdapterSettingsSection(
    settingsModel: TestAppSettingsModel,
    showToast: (message: String) -> Unit,
) {
}
