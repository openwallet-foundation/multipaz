package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.DocumentCannedRequest
import org.multipaz.documenttype.DocumentType
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.nfc.scanMdocReader
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.testapp.App
import org.multipaz.testapp.TestAppUtils
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.fromBase64Url
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagReader
import org.multipaz.testapp.ShowResponseMetadata
import org.multipaz.util.fromHex
import kotlin.time.Clock
import kotlin.time.Duration

private const val TAG = "IsoMdocProximityReadingScreen"

private data class ConnectionMethodPickerData(
    val showPicker: Boolean,
    val connectionMethods: List<MdocConnectionMethod>,
    val continuation:  CancellableContinuation<MdocConnectionMethod?>,
)

private suspend fun selectConnectionMethod(
    connectionMethods: List<MdocConnectionMethod>,
    connectionMethodPickerData: MutableState<ConnectionMethodPickerData?>
): MdocConnectionMethod? {
    return suspendCancellableCoroutine { continuation ->
        connectionMethodPickerData.value = ConnectionMethodPickerData(
            showPicker = true,
            connectionMethods = connectionMethods,
            continuation = continuation
        )
    }
}

private data class RequestPickerEntry(
    val displayName: String,
    val documentType: DocumentType,
    val sampleRequest: DocumentCannedRequest
)

private var lastRequest: Int = 0

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun IsoMdocProximityReadingScreen(
    app: App,
    showToast: (message: String) -> Unit,
    showResponse: (
        vpToken: JsonObject?,
        deviceResponse: DataItem?,
        sessionTranscript: DataItem,
        nonce: ByteString?,
        eReaderKey: EcPrivateKey?,
        metadata: ShowResponseMetadata
    ) -> Unit
) {
    val requestOptions = mutableListOf<RequestPickerEntry>()
    for (documentType in TestAppUtils.provisionedDocumentTypes) {
        for (sampleRequest in documentType.cannedRequests) {
            requestOptions.add(RequestPickerEntry(
                displayName = "${documentType.displayName}: ${sampleRequest.displayName}",
                documentType = documentType,
                sampleRequest = sampleRequest
            ))
        }
    }
    val requestDropdownExpanded = remember { mutableStateOf(false) }
    val requestSelected = remember { mutableStateOf(requestOptions[lastRequest]) }
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()
    val coroutineScope = rememberCoroutineScope { app.promptModel }
    val readerShowQrScanner = remember { mutableStateOf(false) }
    val readerTransport = remember { mutableStateOf<MdocTransport?>(null) }
    val readerSessionEncryption = remember { mutableStateOf<SessionEncryption?>(null) }
    val readerSessionTranscript = remember { mutableStateOf<ByteArray?>(null) }
    val readerMostRecentDeviceRequest = remember { mutableStateOf<ByteArray?>(null) }
    val readerMostRecentDeviceResponse = remember { mutableStateOf<ByteArray?>(null) }
    val connectionMethodPickerData = remember { mutableStateOf<ConnectionMethodPickerData?>(null) }
    val durationEngagementReceivedToRequestSent = remember { mutableStateOf<Duration?>(null) }
    val durationRequestSentToResponseReceived = remember { mutableStateOf<Duration?>(null) }
    val eReaderKey = remember { mutableStateOf<EcPrivateKey?>(null) }
    var readerJob by remember { mutableStateOf<Job?>(null) }

    if (connectionMethodPickerData.value != null) {
        val radioOptions = connectionMethodPickerData.value!!.connectionMethods
        val (selectedOption, onOptionSelected) = remember { mutableStateOf(radioOptions[0]) }
        AlertDialog(
            title = @Composable { Text(text = "Select Connection Method") },
            text = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        radioOptions.forEach { connectionMethod ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = (connectionMethod == selectedOption),
                                        onClick = { onOptionSelected(connectionMethod) },
                                        role = Role.RadioButton
                                    )
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = (connectionMethod == selectedOption),
                                    onClick = null
                                )
                                Text(
                                    text = connectionMethod.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                }
            },
            dismissButton = @Composable {
                TextButton(
                    onClick = {
                        connectionMethodPickerData.value!!.continuation.resume(null) { _, _, _ -> null }
                        connectionMethodPickerData.value = null
                    }
                ) {
                    Text("Cancel")
                }
            },
            onDismissRequest = {
                connectionMethodPickerData.value!!.continuation.resume(null) { _, _, _ -> null }
                connectionMethodPickerData.value = null
            },
            confirmButton = @Composable {
                TextButton(
                    onClick = {
                        connectionMethodPickerData.value!!.continuation.resume(selectedOption) { _, _, _ -> null }
                        connectionMethodPickerData.value = null
                    }
                ) {
                    Text("Connect")
                }
            }
        )
    }

    if (readerShowQrScanner.value) {
        ScanQrCodeDialog(
            title = { Text(text = "Scan QR code") },
            text = { Text(text = "Scan this QR code on another device") },
            dismissButton = "Close",
            onCodeScanned = { data ->
                if (data.startsWith("mdoc:")) {
                    readerShowQrScanner.value = false
                    eReaderKey.value = null
                    readerJob = coroutineScope.launch() {
                        try {
                            var transferProtocol = ""
                            doReaderFlow(
                                app = app,
                                nfcTagReader = null,
                                encodedDeviceEngagement = ByteString(data.substring(5).fromBase64Url()),
                                existingTransport = null,
                                handover = Simple.NULL,
                                allowMultipleRequests = app.settingsModel.readerAllowMultipleRequests.value,
                                bleUseL2CAP = app.settingsModel.readerBleL2CapEnabled.value,
                                bleUseL2CAPInEngagement = app.settingsModel.readerBleL2CapInEngagementEnabled.value,
                                showToast = showToast,
                                readerTransport = readerTransport,
                                readerSessionEncryption = readerSessionEncryption,
                                readerSessionTranscript = readerSessionTranscript,
                                readerMostRecentDeviceRequest = readerMostRecentDeviceRequest,
                                readerMostRecentDeviceResponse = readerMostRecentDeviceResponse,
                                eReaderKey = eReaderKey,
                                durationEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent,
                                durationRequestSentToResponseReceived = durationRequestSentToResponseReceived,
                                requestSelected = requestSelected,
                                selectConnectionMethod = { connectionMethods ->
                                    val connectionMethod = if (connectionMethods.size == 1) {
                                        connectionMethods[0]
                                    } else if (app.settingsModel.readerAutomaticallySelectTransport.value) {
                                        showToast("Auto-selected first from $connectionMethods")
                                        connectionMethods[0]
                                    } else {
                                        selectConnectionMethod(
                                            connectionMethods,
                                            connectionMethodPickerData
                                        )
                                    }
                                    transferProtocol = connectionMethod.toString()
                                    connectionMethod
                                }
                            )
                            if (readerMostRecentDeviceResponse.value != null) {
                                showResponse(
                                    /* vpToken = */ null,
                                    /* deviceResponse = */ Cbor.decode(readerMostRecentDeviceResponse.value!!),
                                    /* sessionTranscript = */ Cbor.decode(readerSessionTranscript.value!!),
                                    /* nonce = */ null,
                                    /* eReaderKey */ eReaderKey.value!!,
                                    /* metadata = */ ShowResponseMetadata(
                                        engagementType = "QR Code",
                                        transferProtocol = transferProtocol,
                                        requestSize = readerMostRecentDeviceRequest.value?.size?.toLong() ?: 0L,
                                        responseSize = readerMostRecentDeviceResponse.value?.size?.toLong() ?: 0L,
                                        durationMsecNfcTapToEngagement = null,
                                        durationMsecEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent.value?.inWholeMilliseconds ?: 0,
                                        durationMsecRequestSentToResponseReceived = durationRequestSentToResponseReceived.value?.inWholeMilliseconds ?: 0,
                                    )
                                )
                            }
                        } catch (error: Throwable) {
                            Logger.e(TAG, "Caught exception", error)
                            showToast("Error: ${error.message}")
                        }
                        readerJob = null
                    }
                    true
                } else {
                    false
                }
            },
            onDismiss = { readerShowQrScanner.value = false }
        )
    }

    val readerTransportState = readerTransport.value?.state?.collectAsState()

    if (!blePermissionState.isGranted) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        blePermissionState.launchPermissionRequest()
                    }
                }
            ) {
                Text("Request BLE permissions")
            }
        }
    }  else if (!bleEnabledState.isEnabled) {
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
        if (readerJob != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.weight(1.0f),
                    verticalArrangement = Arrangement.Top,
                ) {
                    ShowReaderResults(app, readerMostRecentDeviceResponse, readerSessionTranscript, eReaderKey.value)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Connection State: ${readerTransportState?.value}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val encodedDeviceRequest =
                                        TestAppUtils.generateEncodedDeviceRequest(
                                            request = requestSelected.value.sampleRequest,
                                            encodedSessionTranscript = readerSessionTranscript.value!!,
                                            readerKey = app.readerKey,
                                        )
                                    readerMostRecentDeviceResponse.value = byteArrayOf()
                                    readerTransport.value!!.sendMessage(
                                        readerSessionEncryption.value!!.encryptMessage(
                                            messagePlaintext = encodedDeviceRequest,
                                            statusCode = null
                                        )
                                    )
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Send Another Request")
                    }
                    Button(
                        onClick = {
                            readerMostRecentDeviceResponse.value = null
                            coroutineScope.launch {
                                try {
                                    readerTransport.value!!.sendMessage(
                                        SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                                    )
                                    readerTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Close (Message)")
                    }
                    Button(
                        onClick = {
                            readerMostRecentDeviceResponse.value = null
                            coroutineScope.launch {
                                try {
                                    readerTransport.value!!.sendMessage(byteArrayOf())
                                    readerTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Close (Transport-Specific)")
                    }
                    Button(
                        onClick = {
                            readerMostRecentDeviceResponse.value = null
                            coroutineScope.launch {
                                try {
                                    readerTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Close (None)")
                    }
                }
            }
        } else if (readerMostRecentDeviceResponse.value != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.weight(1.0f),
                    verticalArrangement = Arrangement.Top,
                ) {
                    ShowReaderResults(app, readerMostRecentDeviceResponse, readerSessionTranscript, eReaderKey.value!!)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = { readerMostRecentDeviceResponse.value = null }
                    ) {
                        Text("Close")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                item {
                    ComboBox(
                        headline = "DocType and data elements to request",
                        availableRequests = requestOptions,
                        comboBoxSelected = requestSelected,
                        comboBoxExpanded = requestDropdownExpanded,
                        getDisplayName = { it.displayName },
                        onSelected = { index, value -> lastRequest = index }
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            readerShowQrScanner.value = true
                            readerMostRecentDeviceResponse.value = null
                        },
                        content = { Text("Request mdoc via QR Code") }
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            readerMostRecentDeviceResponse.value = null

                            eReaderKey.value = null
                            readerJob = coroutineScope.launch {
                                try {
                                    val negotiatedHandoverConnectionMethods = mutableListOf<MdocConnectionMethod>()
                                    val bleUuid = UUID.randomUUID()
                                    if (app.settingsModel.readerBleCentralClientModeEnabled.value) {
                                        negotiatedHandoverConnectionMethods.add(
                                            MdocConnectionMethodBle(
                                                supportsPeripheralServerMode = false,
                                                supportsCentralClientMode = true,
                                                peripheralServerModeUuid = null,
                                                centralClientModeUuid = bleUuid,
                                            )
                                        )
                                    }
                                    if (app.settingsModel.readerBlePeripheralServerModeEnabled.value) {
                                        negotiatedHandoverConnectionMethods.add(
                                            MdocConnectionMethodBle(
                                                supportsPeripheralServerMode = true,
                                                supportsCentralClientMode = false,
                                                peripheralServerModeUuid = bleUuid,
                                                centralClientModeUuid = null,
                                            )
                                        )
                                    }
                                    if (app.settingsModel.readerNfcDataTransferEnabled.value) {
                                        negotiatedHandoverConnectionMethods.add(
                                            MdocConnectionMethodNfc(
                                                commandDataFieldMaxLength = 0xffff,
                                                responseDataFieldMaxLength = 0x10000
                                            )
                                        )
                                    }

                                    val reader = if (app.externalNfcTagReaders.size > 0)  {
                                        app.externalNfcTagReaders.first()
                                    } else {
                                        NfcTagReader.getReaders().first()
                                    }
                                    val nfcScanOptions = if (app.settingsModel.observeModeEmitPollingFramesAsReader.value) {
                                        NfcScanOptions(pollingFrameData = ByteString("6a0281030000".fromHex()))
                                    } else {
                                        NfcScanOptions()
                                    }
                                    Logger.i(TAG, "nfcScanOptions: $nfcScanOptions")
                                    val scanResult = reader.scanMdocReader(
                                        message = "Hold near credential holder's phone.",
                                        options = MdocTransportOptions(
                                            bleUseL2CAP = app.settingsModel.readerBleL2CapEnabled.value,
                                            bleUseL2CAPInEngagement = app.settingsModel.readerBleL2CapInEngagementEnabled.value
                                        ),
                                        selectConnectionMethod = { connectionMethods ->
                                            if (connectionMethods.size == 1) {
                                                connectionMethods[0]
                                            } else if (app.settingsModel.readerAutomaticallySelectTransport.value) {
                                                showToast("Auto-selected first from $connectionMethods")
                                                connectionMethods[0]
                                            } else {
                                                selectConnectionMethod(
                                                    connectionMethods,
                                                    connectionMethodPickerData
                                                )
                                            }
                                        },
                                        negotiatedHandoverConnectionMethods = negotiatedHandoverConnectionMethods,
                                        nfcScanOptions = nfcScanOptions
                                    )
                                    if (scanResult != null) {
                                        val transferProtocol = scanResult.transport.connectionMethod.toString()
                                        doReaderFlow(
                                            app = app,
                                            nfcTagReader = reader,
                                            encodedDeviceEngagement = scanResult.encodedDeviceEngagement,
                                            existingTransport = scanResult.transport,
                                            handover = scanResult.handover,
                                            allowMultipleRequests = app.settingsModel.readerAllowMultipleRequests.value,
                                            bleUseL2CAP = app.settingsModel.readerBleL2CapEnabled.value,
                                            bleUseL2CAPInEngagement = app.settingsModel.readerBleL2CapInEngagementEnabled.value,
                                            showToast = showToast,
                                            readerTransport = readerTransport,
                                            readerSessionEncryption = readerSessionEncryption,
                                            readerSessionTranscript = readerSessionTranscript,
                                            readerMostRecentDeviceRequest = readerMostRecentDeviceRequest,
                                            readerMostRecentDeviceResponse = readerMostRecentDeviceResponse,
                                            eReaderKey = eReaderKey,
                                            durationEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent,
                                            durationRequestSentToResponseReceived = durationRequestSentToResponseReceived,
                                            requestSelected = requestSelected,
                                            selectConnectionMethod = { connectionMethods ->
                                                if (app.settingsModel.readerAutomaticallySelectTransport.value) {
                                                    showToast("Auto-selected first from $connectionMethods")
                                                    connectionMethods[0]
                                                } else {
                                                    selectConnectionMethod(
                                                        connectionMethods,
                                                        connectionMethodPickerData
                                                    )
                                                }
                                            },
                                        )
                                        readerJob = null
                                        if (readerMostRecentDeviceResponse.value != null) {
                                            with(Dispatchers.Main) {
                                                val nfcEngagementType = if (scanResult.handover.asArray[1] == Simple.NULL) {
                                                    "NFC Static Handover"
                                                } else {
                                                    "NFC Negotiated Handover"
                                                }
                                                showResponse(
                                                    /* vpToken = */ null,
                                                    /* deviceResponse = */ Cbor.decode(readerMostRecentDeviceResponse.value!!),
                                                    /* sessionTranscript = */ Cbor.decode(readerSessionTranscript.value!!),
                                                    /* nonce = */ null,
                                                    /* eReaderKey */ eReaderKey.value!!,
                                                    /* metadata = */ ShowResponseMetadata(
                                                        engagementType = nfcEngagementType,
                                                        transferProtocol = transferProtocol,
                                                        requestSize = readerMostRecentDeviceRequest.value?.size?.toLong() ?: 0L,
                                                        responseSize = readerMostRecentDeviceResponse.value?.size?.toLong() ?: 0L,
                                                        durationMsecNfcTapToEngagement = scanResult.processingDuration.inWholeMilliseconds,
                                                        durationMsecEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent.value?.inWholeMilliseconds ?: 0,
                                                        durationMsecRequestSentToResponseReceived = durationRequestSentToResponseReceived.value?.inWholeMilliseconds ?: 0,
                                                    )
                                                )
                                            }
                                        }
                                    } else {
                                        // when cancelled/dismissed
                                        readerJob = null
                                    }
                                } catch (e: Throwable) {
                                    Logger.e(TAG, "NFC engagement failed", e)
                                    showToast("NFC engagement failed with $e")
                                    readerJob = null
                                }
                            }
                        },
                        content = { Text("Request mdoc via NFC") }
                    )
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked =  app.settingsModel.observeModeEmitPollingFramesAsReader.collectAsState().value,
                            onCheckedChange = { value ->
                                app.settingsModel.observeModeEmitPollingFramesAsReader.value = value
                            },
                        )
                        Text(
                            text = "Insert polling frames for Observe Mode",
                        )
                    }
                }
            }
        }
    }
}

private suspend fun doReaderFlow(
    app: App,
    nfcTagReader: NfcTagReader?,
    encodedDeviceEngagement: ByteString,
    existingTransport: MdocTransport?,
    handover: DataItem,
    allowMultipleRequests: Boolean,
    bleUseL2CAP: Boolean,
    bleUseL2CAPInEngagement: Boolean,
    showToast: (message: String) -> Unit,
    readerTransport: MutableState<MdocTransport?>,
    readerSessionEncryption: MutableState<SessionEncryption?>,
    readerSessionTranscript: MutableState<ByteArray?>,
    readerMostRecentDeviceRequest: MutableState<ByteArray?>,
    readerMostRecentDeviceResponse: MutableState<ByteArray?>,
    eReaderKey: MutableState<EcPrivateKey?>,
    durationEngagementReceivedToRequestSent: MutableState<Duration?>,
    durationRequestSentToResponseReceived: MutableState<Duration?>,
    requestSelected: MutableState<RequestPickerEntry>,
    selectConnectionMethod: suspend (connectionMethods: List<MdocConnectionMethod>) -> MdocConnectionMethod?
) {
    Logger.iCbor(TAG, "DeviceEngagement", encodedDeviceEngagement.toByteArray())
    val deviceEngagement = DeviceEngagement.fromDataItem(Cbor.decode(encodedDeviceEngagement.toByteArray()))
    val eDeviceKey = deviceEngagement.eDeviceKey
    Logger.i(TAG, "Using curve ${eDeviceKey.curve.name} for session encryption")
    eReaderKey.value = Crypto.createEcPrivateKey(eDeviceKey.curve)

    val transport = if (existingTransport != null) {
        if (existingTransport is NfcTransportMdocReader) {
            existingTransport.updateDialogMessage("Transferring data, keep in reader's field")
        }
        existingTransport
    } else {
        val connectionMethods = MdocConnectionMethod.disambiguate(
            deviceEngagement.connectionMethods,
            MdocRole.MDOC_READER
        )
        val connectionMethod = selectConnectionMethod(connectionMethods)
        if (connectionMethod == null) {
            // If user canceled
            return
        }
        val transport = MdocTransportFactory.Default.createTransport(
            connectionMethod,
            MdocRole.MDOC_READER,
            MdocTransportOptions(
                bleUseL2CAP = bleUseL2CAP,
                bleUseL2CAPInEngagement = bleUseL2CAPInEngagement
            )
        )
        if (transport is NfcTransportMdocReader) {
            nfcTagReader!!.scan(
                message = "QR engagement with NFC Data Transfer. Move into NFC field of the mdoc",
                tagInteractionFunc = { tag ->
                    transport.setTag(tag)
                    doReaderFlowWithTransport(
                        app = app,
                        transport = transport,
                        encodedDeviceEngagement = encodedDeviceEngagement,
                        handover = handover,
                        allowMultipleRequests = allowMultipleRequests,
                        showToast = showToast,
                        readerTransport = readerTransport,
                        readerSessionEncryption = readerSessionEncryption,
                        readerSessionTranscript = readerSessionTranscript,
                        readerMostRecentDeviceRequest = readerMostRecentDeviceRequest,
                        readerMostRecentDeviceResponse = readerMostRecentDeviceResponse,
                        durationEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent,
                        durationRequestSentToResponseReceived = durationRequestSentToResponseReceived,
                        selectedRequest = requestSelected,
                        eDeviceKey = eDeviceKey,
                        eReaderKey = eReaderKey.value!!,
                    )
                }
            )
            return
        }
        transport
    }
    doReaderFlowWithTransport(
        app = app,
        transport = transport,
        encodedDeviceEngagement = encodedDeviceEngagement,
        handover = handover,
        allowMultipleRequests = allowMultipleRequests,
        showToast = showToast,
        readerTransport = readerTransport,
        readerSessionEncryption = readerSessionEncryption,
        readerSessionTranscript = readerSessionTranscript,
        readerMostRecentDeviceRequest = readerMostRecentDeviceRequest,
        readerMostRecentDeviceResponse = readerMostRecentDeviceResponse,
        durationEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent,
        durationRequestSentToResponseReceived = durationRequestSentToResponseReceived,
        selectedRequest = requestSelected,
        eDeviceKey = eDeviceKey,
        eReaderKey = eReaderKey.value!!,
    )
}

private suspend fun doReaderFlowWithTransport(
    app: App,
    transport: MdocTransport,
    encodedDeviceEngagement: ByteString,
    handover: DataItem,
    allowMultipleRequests: Boolean,
    showToast: (message: String) -> Unit,
    readerTransport: MutableState<MdocTransport?>,
    readerSessionEncryption: MutableState<SessionEncryption?>,
    readerSessionTranscript: MutableState<ByteArray?>,
    readerMostRecentDeviceRequest: MutableState<ByteArray?>,
    readerMostRecentDeviceResponse: MutableState<ByteArray?>,
    durationEngagementReceivedToRequestSent: MutableState<Duration?>,
    durationRequestSentToResponseReceived: MutableState<Duration?>,
    selectedRequest: MutableState<RequestPickerEntry>,
    eDeviceKey: EcPublicKey,
    eReaderKey: EcPrivateKey,
) {
    readerTransport.value = transport
    val encodedSessionTranscript = TestAppUtils.generateEncodedSessionTranscript(
        encodedDeviceEngagement.toByteArray(),
        handover,
        eReaderKey.publicKey
    )
    val sessionEncryption = SessionEncryption(
        MdocRole.MDOC_READER,
        eReaderKey,
        eDeviceKey,
        encodedSessionTranscript,
    )
    readerSessionEncryption.value = sessionEncryption
    readerSessionTranscript.value = encodedSessionTranscript
    val encodedDeviceRequest = TestAppUtils.generateEncodedDeviceRequest(
        request = selectedRequest.value.sampleRequest,
        encodedSessionTranscript = readerSessionTranscript.value!!,
        readerKey = app.readerKey,
        zkSystemRepository = app.zkSystemRepository,
    )
    Logger.iCbor(TAG, "deviceRequest", encodedDeviceRequest)
    try {
        val t0 = Clock.System.now()
        transport.open(eDeviceKey)
        readerMostRecentDeviceRequest.value = encodedDeviceRequest
        transport.sendMessage(
            sessionEncryption.encryptMessage(
                messagePlaintext = encodedDeviceRequest,
                statusCode = null
            )
        )
        var t1 = Clock.System.now()
        durationEngagementReceivedToRequestSent.value = t1 - t0
        while (true) {
            val sessionData = transport.waitForMessage()
            if (sessionData.isEmpty()) {
                showToast("Received transport-specific session termination message from holder")
                transport.close()
                break
            }
            val t2 = Clock.System.now()
            durationRequestSentToResponseReceived.value = t2 - t1

            val (message, status) = sessionEncryption.decryptMessage(sessionData)
            Logger.i(TAG, "Holder sent ${message?.size} bytes status $status")
            if (message != null) {
                readerMostRecentDeviceResponse.value = message
            }
            if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                showToast("Received session termination message from holder")
                Logger.i(TAG, "Holder indicated they closed the connection. " +
                        "Closing and ending reader loop")
                transport.close()
                break
            }
            if (!allowMultipleRequests) {
                showToast("Response received, closing connection")
                Logger.i(TAG, "Holder did not indicate they are closing the connection. " +
                        "Auto-close is enabled, so sending termination message, closing, and " +
                        "ending reader loop")
                transport.sendMessage(SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION))
                transport.close()
                break
            }
            showToast("Response received, keeping connection open")
            Logger.i(TAG, "Holder did not indicate they are closing the connection. " +
                    "Auto-close is not enabled so waiting for message from holder")
            // "Send additional request" and close buttons will act further on `transport`
            t1 = t2
        }
    } catch (_: MdocTransportClosedException) {
        // Nothing to do, this is thrown when transport.close() is called from another coroutine, that
        // is, the onClick handlers for the close buttons.
        Logger.i(TAG, "Ending reader flow due to MdocTransportClosedException")
    } finally {
        transport.close()
        readerTransport.value = null
    }
}

@Composable
private fun ShowReaderResults(
    app: App,
    readerMostRecentDeviceResponse: MutableState<ByteArray?>,
    readerSessionTranscript: MutableState<ByteArray?>,
    eReaderKey: EcPrivateKey?,
) {
    val deviceResponse1 = readerMostRecentDeviceResponse.value
    if (deviceResponse1 == null || deviceResponse1.isEmpty() || eReaderKey == null) {
        Text(
            text = "Waiting for data",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
    } else {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth().padding(8.dp)
                .verticalScroll(scrollState)
        ) {
            ShowResponse(
                vpToken = null,
                deviceResponse = Cbor.decode(deviceResponse1!!),
                sessionTranscript = Cbor.decode(readerSessionTranscript.value!!),
                nonce = null,
                eReaderKey = eReaderKey,
                metadata = null,
                issuerTrustManager = app.issuerTrustManager,
                documentTypeRepository = app.documentTypeRepository,
                zkSystemRepository = app.zkSystemRepository,
                onViewCertChain = null
            )
        }
    }
}
