package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.presentment.MdocProximityQrPresentment
import org.multipaz.compose.presentment.MdocProximityQrSettings
import org.multipaz.compose.qrcode.generateQrCode
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val TAG = "IsoMdocProximitySharingScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun IsoMdocProximitySharingScreen(
    presentmentSource: PresentmentSource,
    settingsModel: TestAppSettingsModel,
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()
    var disablePlatformImplementation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!blePermissionState.isGranted) {
            Button(
                onClick = { coroutineScope.launch { blePermissionState.launchPermissionRequest() } }
            ) {
                Text("Request BLE permissions")
            }
        } else if (!bleEnabledState.isEnabled) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            bleEnabledState.enable()
                        }
                    }
                ) {
                    Text("Enable Bluetooth")
                }
            }
        } else {
            SettingToggle(
                title = "Disable platform-specific implementation",
                isChecked = disablePlatformImplementation,
                onCheckedChange = { newValue ->
                    disablePlatformImplementation = newValue
                }
            )
            MdocProximityQrPresentment(
                modifier = Modifier,
                source = presentmentSource,
                promptModel = promptModel,
                prepareSettings = { generateQrCode ->
                    Button(onClick = {
                        val connectionMethods = mutableListOf<MdocConnectionMethod>()
                        val bleUuid = UUID.randomUUID()
                        if (settingsModel.presentmentBleCentralClientModeEnabled.value) {
                            connectionMethods.add(
                                MdocConnectionMethodBle(
                                    supportsPeripheralServerMode = false,
                                    supportsCentralClientMode = true,
                                    peripheralServerModeUuid = null,
                                    centralClientModeUuid = bleUuid,
                                )
                            )
                        }
                        if (settingsModel.presentmentBlePeripheralServerModeEnabled.value) {
                            connectionMethods.add(
                                MdocConnectionMethodBle(
                                    supportsPeripheralServerMode = true,
                                    supportsCentralClientMode = false,
                                    peripheralServerModeUuid = bleUuid,
                                    centralClientModeUuid = null,
                                )
                            )
                        }
                        if (settingsModel.presentmentNfcDataTransferEnabled.value) {
                            connectionMethods.add(
                                MdocConnectionMethodNfc(
                                    commandDataFieldMaxLength = 0xffff,
                                    responseDataFieldMaxLength = 0x10000
                                )
                            )
                        }
                        generateQrCode(
                            MdocProximityQrSettings(
                                availableConnectionMethods = connectionMethods,
                                createTransportOptions = MdocTransportOptions(
                                    bleUseL2CAP = settingsModel.presentmentBleL2CapEnabled.value,
                                    bleUseL2CAPInEngagement = settingsModel.presentmentBleL2CapInEngagementEnabled.value
                                )
                            )
                        )
                    }) {
                        Text("Present mDL via QR")
                    }
                },
                showQrCode = { uri, reset ->
                    val qrCodeBitmap = remember { generateQrCode(uri) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(text = "Present QR code to mdoc reader")
                        Image(
                            modifier = Modifier.fillMaxWidth(),
                            bitmap = qrCodeBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth
                        )
                        Button(onClick = { reset() }) {
                            Text("Cancel")
                        }
                    }
                },
                showTransacting = { reset ->
                    Text("Transacting")
                    Button(onClick = { reset() }) {
                        Text("Cancel")
                    }
                },
                showCompleted = { error, reset ->
                    if (error != null) {
                        Text("Something went wrong: $error")
                    } else {
                        Text("The data was shared")
                    }
                    LaunchedEffect(Unit) {
                        delay(1.5.seconds)
                        reset()
                    }
                },
                eDeviceKeyCurve = settingsModel.presentmentSessionEncryptionCurve.value,
                disablePlatformSpecificImplementation = disablePlatformImplementation,
            )
        }
    }
}
