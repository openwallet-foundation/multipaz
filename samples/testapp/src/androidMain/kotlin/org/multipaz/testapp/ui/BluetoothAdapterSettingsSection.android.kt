package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.multipaz.testapp.AndroidUsbBluetoothManager
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.testapp.UsbBluetoothAdapterInfo
import org.multipaz.testapp.UsbBluetoothAdapterState

@Composable
actual fun PlatformBluetoothAdapterSettingsSection(
    settingsModel: TestAppSettingsModel,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(settingsModel) {
        AndroidUsbBluetoothManager.init(settingsModel)
    }

    val snapshot = AndroidUsbBluetoothManager.snapshot.collectAsState().value
    val selectedAdapter = snapshot.adapters.firstOrNull { it.isSelected }

    var isProbing by remember { mutableStateOf(false) }
    var probeMessage by remember { mutableStateOf<String?>(null) }
    var probeFailed by remember { mutableStateOf(false) }

    LaunchedEffect(snapshot.builtInSelected, selectedAdapter?.uiKey) {
        probeMessage = null
        probeFailed = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
    ) {
        SettingHeadline("Bluetooth Adapter")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = snapshot.builtInSelected,
                    onClick = { AndroidUsbBluetoothManager.selectBuiltIn() },
                    role = Role.RadioButton,
                )
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = snapshot.builtInSelected,
                onClick = null,
            )
            Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                Text(
                    text = "Built-in Android Bluetooth (Default)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Uses Android OS Bluetooth stack",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (snapshot.adapters.isEmpty()) {
            Text(
                text = "No external USB Bluetooth HCI adapters attached. " +
                    "Plug in a USB Bluetooth dongle via OTG to use multipaz-bthci.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else {
            for (adapter in snapshot.adapters) {
                UsbAdapterRow(
                    adapter = adapter,
                    onSelect = {
                        coroutineScope.launch {
                            try {
                                val ready = AndroidUsbBluetoothManager.selectUsbAdapter(adapter)
                                if (!ready && adapter.state == UsbBluetoothAdapterState.CONNECTED_NO_PERMISSION) {
                                    showToast("USB permission was not granted")
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                showToast("Error selecting USB adapter: ${e.message}")
                            }
                        }
                    },
                )
            }
        }

        if (selectedAdapter != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedAdapter.state == UsbBluetoothAdapterState.CONNECTED_NO_PERMISSION) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    if (!AndroidUsbBluetoothManager.requestPermission(selectedAdapter)) {
                                        showToast("User did not grant USB permission")
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    showToast("Error requesting USB permission: ${e.message}")
                                }
                            }
                        },
                    ) {
                        Text("Grant USB Permission")
                    }
                }

                Button(
                    enabled = selectedAdapter.state == UsbBluetoothAdapterState.CONNECTED && !isProbing,
                    onClick = {
                        isProbing = true
                        probeMessage = null
                        coroutineScope.launch {
                            try {
                                val summary = AndroidUsbBluetoothManager.probeSelectedAdapter()
                                probeFailed = false
                                probeMessage = summary
                                showToast(summary)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                val errMsg = "Adapter test failed: ${e.message ?: e::class.simpleName}"
                                probeFailed = true
                                probeMessage = errMsg
                                showToast(errMsg)
                            } finally {
                                isProbing = false
                            }
                        }
                    },
                ) {
                    Text(if (isProbing) "Testing..." else "Test Adapter")
                }
            }

            val currentProbeMessage = probeMessage
            if (currentProbeMessage != null) {
                Text(
                    text = currentProbeMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probeFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun UsbAdapterRow(
    adapter: UsbBluetoothAdapterInfo,
    onSelect: () -> Unit,
) {
    val statusText = when (adapter.state) {
        UsbBluetoothAdapterState.CONNECTED -> "Connected (multipaz-bthci)"
        UsbBluetoothAdapterState.CONNECTED_NO_PERMISSION -> "USB permission required"
        UsbBluetoothAdapterState.NOT_CONNECTED -> "Not connected"
    }
    val idDetails = buildString {
        append("USB ")
        append(adapter.vidPidHex)
        if (adapter.serialNumber != null) {
            append(" • S/N ")
            append(adapter.serialNumber)
        } else if (adapter.disambiguateByPath && adapter.deviceName != null) {
            append(" • ")
            append(adapter.deviceName)
        }
        append(" — ")
        append(statusText)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = adapter.isSelected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = adapter.isSelected,
            onClick = null,
        )
        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            Text(
                text = adapter.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = idDetails,
                style = MaterialTheme.typography.bodySmall,
                color = if (adapter.state == UsbBluetoothAdapterState.CONNECTED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}
