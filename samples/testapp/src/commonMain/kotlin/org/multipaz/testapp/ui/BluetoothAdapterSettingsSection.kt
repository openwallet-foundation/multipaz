package org.multipaz.testapp.ui

import androidx.compose.runtime.Composable
import org.multipaz.testapp.TestAppSettingsModel

@Composable
expect fun PlatformBluetoothAdapterSettingsSection(
    settingsModel: TestAppSettingsModel,
    showToast: (message: String) -> Unit,
)
