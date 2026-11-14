package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import multipazproject.samples.testapp.generated.resources.Res
import org.multipaz.compose.branding.Branding
import org.multipaz.crypto.EcCurve
import org.multipaz.testapp.App
import org.multipaz.testapp.TestAppPlatform
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.compose.cards.WarningCard
import org.multipaz.compose.decodeImage
import org.multipaz.compose.scrollbar.LazyColumnWithScrollbar
import org.multipaz.document.Document
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.testapp.ui.customtheme.AppThemeLimeGreen

@Composable
fun SettingsScreen(
    app: App,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val nciNfcEnabled = app.settingsModel.nciNfcEnabled.collectAsState().value
    val nfcAvailable = (TestAppConfiguration.platform == TestAppPlatform.ANDROID) ||
        (TestAppConfiguration.platform == TestAppPlatform.JVM && nciNfcEnabled)
    val negotiatedHandoverOrder = app.settingsModel.presentmentNegotiatedHandoverPreferredOrder.collectAsState().value

    LazyColumnWithScrollbar(
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
                        TestAppConfiguration.restartApp()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        showToast("An error occurred: $e")
                    }
                },
                enabled = (TestAppConfiguration.platform == TestAppPlatform.ANDROID)
            )
        }
        item { SettingHeadline("ISO mdoc NFC Engagement Settings") }
        if (TestAppConfiguration.platform == TestAppPlatform.JVM) {
            item {
                SettingToggle(
                    title = "Enable NCI NFC Controller (Linux I2C + GPIO)",
                    isChecked = nciNfcEnabled,
                    onCheckedChange = { app.settingsModel.nciNfcEnabled.value = it }
                )
            }
            item {
                val i2cDev = app.settingsModel.nciI2cDevicePath.collectAsState().value
                val i2cAddr = app.settingsModel.nciI2cAddress.collectAsState().value
                val gpioChip = app.settingsModel.nciGpioChipPath.collectAsState().value
                val irqPin = app.settingsModel.nciIrqGpioPin.collectAsState().value
                val venPin = app.settingsModel.nciVenGpioPin.collectAsState().value
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = i2cDev,
                            onValueChange = { app.settingsModel.nciI2cDevicePath.value = it },
                            label = { Text("I2C Device") },
                            singleLine = true,
                            enabled = !nciNfcEnabled,
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = i2cAddr,
                            onValueChange = { app.settingsModel.nciI2cAddress.value = it },
                            label = { Text("I2C Address (hex)") },
                            singleLine = true,
                            enabled = !nciNfcEnabled,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        OutlinedTextField(
                            value = gpioChip,
                            onValueChange = { app.settingsModel.nciGpioChipPath.value = it },
                            label = { Text("GPIO Chip") },
                            singleLine = true,
                            enabled = !nciNfcEnabled,
                            modifier = Modifier.weight(1.4f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = irqPin,
                            onValueChange = { app.settingsModel.nciIrqGpioPin.value = it },
                            label = { Text("IRQ Pin") },
                            singleLine = true,
                            enabled = !nciNfcEnabled,
                            modifier = Modifier.weight(0.8f).padding(horizontal = 4.dp)
                        )
                        OutlinedTextField(
                            value = venPin,
                            onValueChange = { app.settingsModel.nciVenGpioPin.value = it },
                            label = { Text("VEN Pin") },
                            singleLine = true,
                            enabled = !nciNfcEnabled,
                            modifier = Modifier.weight(0.8f).padding(start = 4.dp)
                        )
                    }
                    if (nciNfcEnabled) {
                        Text(
                            text = "Disable NCI NFC Controller above before editing hardware pins or device paths.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
        item {
            if (!nfcAvailable) {
                WarningCard {
                    val msg = if (TestAppConfiguration.platform == TestAppPlatform.JVM) {
                        "Enable NCI NFC Controller above when an NXP PN7160 is connected over I2C + GPIO"
                    } else {
                        "NFC Engagement as an mdoc is not supported on ${TestAppConfiguration.platform.displayName}"
                    }
                    Text(msg)
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
            SettingToggle(
                title = "NFC CCE: Only Allow NFC Data Transfer",
                isChecked = app.settingsModel.presentmentNfcCceOnlyAllowNfc.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentNfcCceOnlyAllowNfc.value = it },
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
            SettingHeadline("ISO mdoc Reader QR, NFC Negotiated Handover & NFC CCE Preferred Order")
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
            HorizontalDivider(
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            SettingHeadline("ISO mdoc reader Transports (Reader QR, NFC Negotiated Handover & NFC CCE)")
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
            SettingMultipleChoice(
                title = "Session Encryption Curve",
                choices = EcCurve.entries.mapNotNull { if (it.supportsKeyAgreement) it.name else null },
                initialChoice = app.settingsModel.readerSessionEncryptionCurve.collectAsState().value.toString(),
                onChoiceSelected = { choice ->
                    app.settingsModel.readerSessionEncryptionCurve.value =
                        EcCurve.entries.find { it.name == choice }!!
                },
            )
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
        val dc = app.digitalCredentials
        if (dc != null && dc.registerAvailable) {
            for (protocol in dc.supportedProtocols.sorted()) {
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
                    Text("Digital Credentials API is not supported on ${TestAppConfiguration.platform.displayName}")
                }
            }
        }

        item {
            SettingHeadline("Digital Credentials create() API options")
        }
        if (TestAppConfiguration.supportedCreateProtocols.isNotEmpty()) {
            for (protocol in TestAppConfiguration.supportedCreateProtocols.sorted()) {
                item {
                    SettingToggle(
                        title = "Protocol: $protocol",
                        isChecked = app.settingsModel.dcApiCreateProtocols.collectAsState().value.contains(protocol),
                        onCheckedChange = {
                            val mutableSet = app.settingsModel.dcApiCreateProtocols.value.toMutableSet()
                            if (it) mutableSet.add(protocol) else mutableSet.remove(protocol)
                            app.settingsModel.dcApiCreateProtocols.value = mutableSet
                        },
                    )
                }
            }
        } else {
            item {
                WarningCard {
                    Text(
                        "Digital Credentials create() API is not supported on " +
                            TestAppConfiguration.platform.displayName
                    )
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
                title = "Use app-provided transaction data renderers",
                isChecked = app.settingsModel.presentmentUseTransactionDataRenderers.collectAsState().value,
                onCheckedChange = { app.settingsModel.presentmentUseTransactionDataRenderers.value = it },
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
            val choices = remember {
                app.faceMatcherRepository.all.map { it.displayName } + listOf("None")
            }
            SettingMultipleChoice(
                title = "Presentation Face Matcher",
                choices = choices,
                initialChoice = app.settingsModel.presentmentFaceMatcher.collectAsState().value,
                onChoiceSelected = { choice ->
                    app.settingsModel.presentmentFaceMatcher.value = choice
                },
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            app.requesterHistory.deleteAll()
                            showToast("Requester history cleared")
                        }
                    },
                ) {
                    Text(text = "Reset Requester History")
                }
            }
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
            SettingHeadline("Logging Options")
        }
        item {
            SettingToggle(
                title = "Enable Debug Logging",
                isChecked = app.settingsModel.loggingDebugEnabled.collectAsState().value,
                onCheckedChange = { app.settingsModel.loggingDebugEnabled.value = it },
            )
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            SettingHeadline("Branding")
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = {
                    coroutineScope.launch {
                        val appIconBitmap = decodeImage(Res.readBytes("drawable/multipaz-logo-lime-green.png"))
                        Branding.setCurrent(object : Branding {
                            override val appName: String?
                                get() = "Multipaz Test App (Lime Green Edition)"

                            override val appIconPainter: Painter?
                                get() = BitmapPainter(appIconBitmap)

                            override val theme: @Composable (content: @Composable () -> Unit) -> Unit
                                get() = { AppThemeLimeGreen(it) }

                            // TODO: use lime green default cardart
                            override suspend fun renderFallbackCardArt(document: Document): ImageBitmap =
                                Branding.Default.renderFallbackCardArt(document)

                            // TODO: use random colors from a lime green palette
                            @Composable
                            override fun AvatarIcon(
                                size: Dp,
                                name: String,
                                additionalData: ByteArray?,
                                modifier: Modifier
                            ) = Branding.Default.AvatarIcon(size, name, additionalData, modifier)
                        })
                    }
                }) {
                    Text(text = "Use app-provided branding (Lime Green)")
                }

                Button(onClick = { Branding.setCurrent(Branding.Default) }) {
                    Text(text = "Reset to default branding")
                }
            }
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
    "ble:direct_l2cap_central:" to "BLE Direct L2CAP (mdoc central mode)",
    "ble:direct_l2cap_peripheral:" to "BLE Direct L2CAP (mdoc peripheral mode)",
    "ble:gatt_central:" to "BLE GATT (mdoc central client mode)",
    "ble:gatt_peripheral:" to "BLE GATT (mdoc peripheral server mode)",
    "nfc:" to "NFC Data Transfer"
)

private fun TestAppSettingsModel.swapNegotiatedHandoverOrder(index1: Int, index2: Int) {
    val list = presentmentNegotiatedHandoverPreferredOrder.value.toMutableList()
    val tmp = list[index2]
    list[index2] = list[index1]
    list[index1] = tmp
    presentmentNegotiatedHandoverPreferredOrder.value = list
}
