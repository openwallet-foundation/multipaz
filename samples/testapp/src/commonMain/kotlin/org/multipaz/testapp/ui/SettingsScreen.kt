package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.crypto.EcCurve
import org.multipaz.testapp.App
import org.multipaz.testapp.Platform
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.testapp.platform
import org.multipaz.compose.cards.WarningCard
import org.multipaz.digitalcredentials.Default
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.testapp.platformRestartApp

@Composable
fun SettingsScreen(
    app: App,
    showToast: (message: String) -> Unit,
) {
    // NFC engagement as an mdoc is only supported on Android.
    //
    val nfcAvailable = (platform == Platform.ANDROID)
    val negotiatedHandoverOrder = app.settingsModel.presentmentNegotiatedHandoverPreferredOrder.collectAsState().value

    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item { SettingHeadline("Cryptography Library Settings") }
        item {
            SettingToggle(
                title = "Prefer BouncyCastle to Conscrypt (restarts app)",
                isChecked = app.settingsModel.cryptoPreferBouncyCastle.collectAsState().value,
                onCheckedChange = {
                    app.settingsModel.cryptoPreferBouncyCastle.value = it
                    try {
                        platformRestartApp()
                    } catch (e: Throwable) {
                        showToast("An error occurred: $e")
                    }
                },
                enabled = (platform == Platform.ANDROID)
            )
        }
        item { SettingHeadline("ISO mdoc NFC Engagement Settings") }
        item {
            if (!nfcAvailable) {
                WarningCard {
                    Text("NFC Engagement as an mdoc is not supported on ${platform.displayName}")
                }
            }
        }
        item {
            SettingToggle(
                title = "Use NFC Static Handover",
                isChecked = !app.settingsModel.presentmentUseNegotiatedHandover.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentUseNegotiatedHandover.value = !it },
                enabled = nfcAvailable
            )
        }
        item {
            SettingToggle(
                title = "Use NFC Negotiated Handover",
                isChecked = app.settingsModel.presentmentUseNegotiatedHandover.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentUseNegotiatedHandover.value = it },
                enabled = nfcAvailable
            )
        }
        item {
            SettingHeadline("ISO mdoc Transports (QR and NFC Static Handover)")
        }
        item {
            SettingToggle(
                title = "BLE (mdoc central client mode)",
                isChecked = app.settingsModel.presentmentBleCentralClientModeEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentBleCentralClientModeEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "BLE (mdoc peripheral server mode)",
                isChecked = app.settingsModel.presentmentBlePeripheralServerModeEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentBlePeripheralServerModeEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "NFC Data Transfer",
                isChecked = app.settingsModel.presentmentNfcDataTransferEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentNfcDataTransferEnabled.value = it },
            )
        }
        item {
            SettingHeadline("ISO mdoc NFC Negotiated Handover Preferred Order")
        }
        for (n in negotiatedHandoverOrder.indices) {
            val prefix = negotiatedHandoverOrder[n]
            val isFirst = (n == 0)
            val isLast = (n == negotiatedHandoverOrder.size - 1)
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = prefixToDisplayNameMap[prefix] ?: prefix,
                            fontWeight = FontWeight.Normal,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { app.settingsModel.swapNegotiatedHandoverOrder(n, n - 1) },
                        enabled = !isFirst
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = null,
                        )
                    }
                    IconButton(
                        onClick = { app.settingsModel.swapNegotiatedHandoverOrder(n, n + 1) },
                        enabled = !isLast
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDownward,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
        item {
            SettingHeadline("ISO mdoc Transport Options")
        }
        item {
            SettingMultipleChoice(
                title = "Session Encryption Curve",
                choices = EcCurve.entries.mapNotNull { if (it.supportsKeyAgreement) it.name else null },
                initialChoice = app.settingsModel.presentmentSessionEncryptionCurve.value.toString(),
                onChoiceSelected = { choice ->
                    app.settingsModel.presentmentSessionEncryptionCurve.value = EcCurve.entries.find { it.name == choice }!!
                },
            )
        }
        item {
            SettingToggle(
                title = "Use L2CAP if available",
                isChecked = app.settingsModel.presentmentBleL2CapEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentBleL2CapEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "Use L2CAP in engagement if available",
                isChecked = app.settingsModel.presentmentBleL2CapInEngagementEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentBleL2CapInEngagementEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "Keep connection open after first request",
                isChecked = app.settingsModel.presentmentAllowMultipleRequests.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentAllowMultipleRequests.value = it },
            )
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            SettingHeadline("ISO mdoc reader Transports (NFC Negotiated Handover)")
        }
        item {
            SettingToggle(
                title = "BLE (mdoc central client mode)",
                isChecked = app.settingsModel.readerBleCentralClientModeEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.readerBleCentralClientModeEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "BLE (mdoc peripheral server mode)",
                isChecked = app.settingsModel.readerBlePeripheralServerModeEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.readerBlePeripheralServerModeEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "NFC Data Transfer",
                isChecked = app.settingsModel.readerNfcDataTransferEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.readerNfcDataTransferEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "Automatically select transport",
                isChecked = app.settingsModel.readerAutomaticallySelectTransport.collectAsState().value,
                onCheckedChange = { app.settingsModel.readerAutomaticallySelectTransport.value = it },
            )
        }
        item {
            SettingHeadline("ISO mdoc reader Transport Options")
        }
        item {
            SettingToggle(
                title = "Use L2CAP if available",
                isChecked = app.settingsModel.readerBleL2CapEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.readerBleL2CapEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "Use L2CAP in engagement if available",
                isChecked = app.settingsModel.readerBleL2CapInEngagementEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.readerBleL2CapInEngagementEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "Keep connection open after first request",
                isChecked = app.settingsModel.readerAllowMultipleRequests.collectAsState().value,
                onCheckedChange = { app.settingsModel.readerAllowMultipleRequests.value = it },
            )
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            SettingHeadline("Digital Credentials API Options")
        }
        if (DigitalCredentials.Default.available) {
            for (protocol in DigitalCredentials.Default.supportedProtocols.sorted()) {
                item {
                    SettingToggle(
                        title = "Protocol: $protocol",
                        isChecked = app.settingsModel.dcApiProtocols.collectAsState().value.contains(protocol),
                        onCheckedChange = {
                            val mutableSet = app.settingsModel.dcApiProtocols.value.toMutableSet()
                            if (it) mutableSet.add(protocol) else mutableSet.remove(protocol)
                            app.settingsModel.dcApiProtocols.value = mutableSet
                        },
                    )
                }
            }
        } else {
            item {
                WarningCard {
                    Text("Digital Credentials API is not supported on ${platform.displayName}")
                }
            }
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            SettingHeadline("Wallet General Options")
        }
        item {
            SettingToggle(
                title = "Skip consent prompt",
                isChecked = !app.settingsModel.presentmentShowConsentPrompt.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentShowConsentPrompt.value = !it },
            )
        }
        item {
            SettingToggle(
                title = "Skip user authentication",
                isChecked = !app.settingsModel.presentmentRequireAuthentication.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentRequireAuthentication.value = !it },
            )
        }
        item {
            SettingToggle(
                title = "Prefer Key Agreement to Signature",
                isChecked = !app.settingsModel.presentmentPreferSignatureToKeyAgreement.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentPreferSignatureToKeyAgreement.value = !it },
            )
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            SettingHeadline("NFC Observe Mode")
        }
        item {
            SettingToggle(
                title = "Transact only when Identity Reader is seen (wallet)",
                isChecked = app.settingsModel.observeModeEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.observeModeEnabled.value = it },
            )
        }
        item {
            SettingToggle(
                title = "Insert Identity Reader polling frames (reader)",
                isChecked = app.settingsModel.observeModeEmitPollingFramesAsReader.collectAsState().value,
                onCheckedChange = { app.settingsModel.observeModeEmitPollingFramesAsReader.value = it },
            )
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        app.settingsModel.resetSettings()
                    },
                ) {
                    Text(text = "Reset Settings")
                }
            }
        }

    }
}

private val prefixToDisplayNameMap = mapOf<String, String>(
    "ble:central_client_mode:" to "BLE (mdoc central client mode)",
    "ble:peripheral_server_mode:" to "BLE (mdoc peripheral server mode)",
    "nfc:" to "NFC Data Transfer"
)

private fun TestAppSettingsModel.swapNegotiatedHandoverOrder(index1: Int, index2: Int) {
    val list = presentmentNegotiatedHandoverPreferredOrder.value.toMutableList()
    val tmp = list[index2]
    list[index2] = list[index1]
    list[index1] = tmp
    presentmentNegotiatedHandoverPreferredOrder.value = list
}
