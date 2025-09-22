package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.presentment.MdocProximityQrPresentment
import org.multipaz.compose.presentment.MdocProximityQrSettings
import org.multipaz.compose.qrcode.generateQrCode
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.models.presentment.PresentmentModel
import org.multipaz.models.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.testapp.platformAppIcon
import org.multipaz.testapp.platformAppName
import org.multipaz.util.UUID

private const val TAG = "IsoMdocProximitySharingScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun IsoMdocProximitySharingScreen(
    presentmentSource: PresentmentSource,
    presentmentModel: PresentmentModel,
    settingsModel: TestAppSettingsModel,
    promptModel: PromptModel,
    documentTypeRepository: DocumentTypeRepository,
    imageLoader: ImageLoader,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()

    Column(
        modifier = Modifier.fillMaxSize(),
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
            MdocProximityQrPresentment(
                modifier = Modifier,
                appName = platformAppName,
                appIconPainter = painterResource(platformAppIcon),
                presentmentModel = presentmentModel,
                presentmentSource = presentmentSource,
                promptModel = promptModel,
                documentTypeRepository = documentTypeRepository,
                imageLoader = imageLoader,
                allowMultipleRequests = settingsModel.presentmentAllowMultipleRequests.value,
                showQrButton = { onQrButtonClicked ->
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
                        onQrButtonClicked(
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
                showQrCode = { uri ->
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
                        Button(onClick = { presentmentModel.reset() }) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }
}
