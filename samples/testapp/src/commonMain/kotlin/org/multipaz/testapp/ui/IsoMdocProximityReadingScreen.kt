package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
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
import org.multipaz.mdoc.nfc.MdocHandoverType
import org.multipaz.mdoc.nfc.MdocReaderNfcHandoverOptions
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagReader
import org.multipaz.mdoc.transport.NfcHybridTransportMdocReader
import org.multipaz.testapp.ShowResponseMetadata
import org.multipaz.util.fromHex
import org.multipaz.utopia.knowntypes.wellKnownMultipleDocumentRequests
import org.multipaz.verification.VerificationSession
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
    val id: String,
    val displayName: String,
    val request: DocumentCannedRequest,
    val requestSdJwtVc: Boolean
)

internal var lastNfcReaderSelected: Int = 0

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun IsoMdocProximityReadingScreen(
    app: App,
    showToast: (message: String) -> Unit,
    showResponse: (
        vpToken: JsonObject?,
        deviceResponse: DataItem?,
        session: VerificationSession,
        eReaderKey: EcPrivateKey?,
        metadata: ShowResponseMetadata
    ) -> Unit
) {
    val requestOptions = mutableListOf<RequestPickerEntry>()
    for (documentType in app.documentTypeRepository.documentTypes) {
        for (sampleRequest in documentType.cannedRequests) {
            if (sampleRequest.mdocRequest != null) {
                requestOptions.add(
                    RequestPickerEntry(
                        id = "mdoc_" + documentType.mdocDocumentType!!.docType + "_" + sampleRequest.id,
                        displayName = "${documentType.displayName}: ${sampleRequest.displayName}",
                        request = sampleRequest,
                        requestSdJwtVc = false
                    )
                )
            }
        }
        for (sampleRequest in documentType.cannedRequests) {
            if (sampleRequest.jsonRequest != null) {
                requestOptions.add(
                    RequestPickerEntry(
                        id = "json_" + documentType.jsonDocumentType!!.vct + "_" + sampleRequest.id,
                        displayName = "${documentType.displayName}: ${sampleRequest.displayName} (SD-JWT VC)",
                        request = sampleRequest,
                        requestSdJwtVc = true
                    )
                )
            }
        }
    }
    for (request in app.documentTypeRepository.extraSingleDocumentCannedRequests) {
        requestOptions.add(RequestPickerEntry(
            id = "extra_" + request.id,
            displayName = request.displayName,
            request = request,
            requestSdJwtVc = request.mdocRequest == null && request.jsonRequest != null
        ))
    }
    for (request in wellKnownMultipleDocumentRequests) {
        requestOptions.add(RequestPickerEntry(
            id = "multidoc_" + request.id,
            displayName = "Multi-doc: ${request.displayName}",
            request = request,
            requestSdJwtVc = false
        ))
    }
    val requestDropdownExpanded = remember { mutableStateOf(false) }
    val requestSelected = remember { mutableStateOf(
        requestOptions.find {
            it.id == app.settingsModel.readerLastSelectedRequestId.value
        } ?: requestOptions.first()
    )}
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()
    val coroutineScope = rememberCoroutineScope { app.promptModel }
    val readerShowQrScanner = remember { mutableStateOf(false) }
    val readerTransport = remember { mutableStateOf<MdocTransport?>(null) }
    val readerSessionEncryption = remember { mutableStateOf<SessionEncryption?>(null) }
    val readerSession = remember { mutableStateOf<VerificationSession?>(null) }
    val readerMostRecentDeviceRequest = remember { mutableStateOf<ByteArray?>(null) }
    val readerMostRecentDeviceResponse = remember { mutableStateOf<ByteArray?>(null) }
    val connectionMethodPickerData = remember { mutableStateOf<ConnectionMethodPickerData?>(null) }
    val durationEngagementReceivedToRequestSent = remember { mutableStateOf<Duration?>(null) }
    val durationRequestSentToResponseReceived = remember { mutableStateOf<Duration?>(null) }
    val eReaderKey = remember { mutableStateOf<EcPrivateKey?>(null) }
    val deviceHandover = remember { mutableStateOf<DataItem?>(null) }
    val deviceEngagement = remember { mutableStateOf<ByteString?>(null) }
    var readerJob by remember { mutableStateOf<Job?>(null) }

    val readers = mutableListOf<NfcReaderEntry>()
    NfcTagReader.getReaders().forEachIndexed { index, reader ->
        readers.add(NfcReaderInternal("Internal NFC Reader", index))
    }
    app.externalNfcReaderStore.readers.value.forEach { externalReader ->
        readers.add(NfcReaderExternal(externalReader.displayName, externalReader))
    }
    if (readers.isEmpty()) {
        readers.add(NfcReaderNoneAvailable())
    }
    val readerSelected = remember { mutableStateOf<NfcReaderEntry>(
        if (lastNfcReaderSelected < readers.size) {
            readers[lastNfcReaderSelected]
        } else {
            readers[0]
        }
    )}
    val readerDropdownExpanded = remember { mutableStateOf(false) }

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
                                nfcTagReader = readerSelected.value
                                    .takeUnless { it is NfcReaderNoneAvailable }
                                    ?.getNfcTagReader(),
                                encodedDeviceEngagement = ByteString(data.substring(5).fromBase64Url()),
                                existingTransport = null,
                                handover = Simple.NULL,
                                allowMultipleRequests = app.settingsModel.readerAllowMultipleRequests.value,
                                insertSequenceNumbers = false,
                                bleUseL2CAP = app.settingsModel.readerBleL2CapEnabled.value,
                                bleUseL2CAPInEngagement = app.settingsModel.readerBleL2CapInEngagementEnabled.value,
                                showToast = showToast,
                                readerTransport = readerTransport,
                                readerSessionEncryption = readerSessionEncryption,
                                readerSession = readerSession,
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
                                },
                                signRequest = app.settingsModel.signRequest.value
                            )
                            if (readerMostRecentDeviceResponse.value != null) {
                                showResponse(
                                    /* vpToken = */ null,
                                    /* deviceResponse = */ Cbor.decode(readerMostRecentDeviceResponse.value!!),
                                    /* readerSession = */ readerSession.value!!,
                                    /* eReaderKey = */ eReaderKey.value!!,
                                    /* metadata = */ ShowResponseMetadata(
                                        engagementType = "QR Code",
                                        transferProtocol = transferProtocol,
                                        requestSize = readerMostRecentDeviceRequest.value?.size?.toLong() ?: 0L,
                                        responseSize = readerMostRecentDeviceResponse.value?.size?.toLong() ?: 0L,
                                        durationMsecNfcTapToEngagement = null,
                                        durationMsecEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent.value?.inWholeMilliseconds ?: 0,
                                        durationMsecRequestSentToResponseReceived = durationRequestSentToResponseReceived.value?.inWholeMilliseconds ?: 0,
                                        nfcHybridTransportStats = null
                                    )
                                )
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
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
                    ShowReaderResults(app, readerMostRecentDeviceResponse, readerSession.value, eReaderKey.value)
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
                                    val session = TestAppUtils.createProximityVerificationSession(
                                        app = app,
                                        request = requestSelected.value.request,
                                        signRequest = app.settingsModel.signRequest.value,
                                        handover = deviceHandover.value!!,
                                        eReaderKey = eReaderKey.value!!,
                                        deviceEngagement = deviceEngagement.value!!,
                                        requestSdJwtVc = requestSelected.value.requestSdJwtVc
                                    )
                                    readerSession.value = session
                                    readerMostRecentDeviceResponse.value = byteArrayOf()
                                    val proximityRequest =
                                        session.find<VerificationSession.Iso18013ProximityRequest>()
                                    val encodedDeviceRequest =
                                        Cbor.encode(proximityRequest.deviceRequest)
                                    readerTransport.value!!.sendMessage(
                                        readerSessionEncryption.value!!.encryptMessage(
                                            messagePlaintext = encodedDeviceRequest,
                                            statusCode = null
                                        )
                                    )
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
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
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
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
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
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
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
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
                    ShowReaderResults(app, readerMostRecentDeviceResponse, readerSession.value!!, eReaderKey.value!!)
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
                    if (readers.size == 0) {
                        Text("No NFC readers available")
                    } else {
                        ComboBox(
                            headline = "NFC Reader",
                            options = readers,
                            comboBoxSelected = readerSelected as MutableState<NfcReaderEntry>,
                            comboBoxExpanded = readerDropdownExpanded,
                            getDisplayName = { it.displayName },
                            onSelected = { index, value -> lastNfcReaderSelected = index }
                        )
                    }
                }
                item {
                    ComboBox(
                        headline = "DocType and data elements to request",
                        options = requestOptions,
                        comboBoxSelected = requestSelected,
                        comboBoxExpanded = requestDropdownExpanded,
                        getDisplayName = { it.displayName },
                        onSelected = { index, value ->
                            app.settingsModel.readerLastSelectedRequestId.value = value.id
                        }
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

                fun launchNfcScan(handoverOptions: MdocReaderNfcHandoverOptions, nfcOnly: Boolean) {
                    readerJob = coroutineScope.launch {
                        try {
                            val negotiatedHandoverConnectionMethods = mutableListOf<MdocConnectionMethod>()
                            if (!nfcOnly) {
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
                            }

                            val nfcScanOptions = if (app.settingsModel.observeModeEmitPollingFramesAsReader.value) {
                                NfcScanOptions(pollingFrameData = ByteString("6a0281030000".fromHex()))
                            } else {
                                NfcScanOptions()
                            }
                            Logger.i(TAG, "nfcScanOptions: $nfcScanOptions")
                            val reader =  readerSelected.value.getNfcTagReader()
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
                                nfcScanOptions = nfcScanOptions,
                                handoverOptions = handoverOptions
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
                                    insertSequenceNumbers = scanResult.type == MdocHandoverType.V2_HANDOVER,
                                    bleUseL2CAP = app.settingsModel.readerBleL2CapEnabled.value,
                                    bleUseL2CAPInEngagement = app.settingsModel.readerBleL2CapInEngagementEnabled.value,
                                    showToast = showToast,
                                    readerTransport = readerTransport,
                                    readerSessionEncryption = readerSessionEncryption,
                                    readerSession = readerSession,
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
                                    signRequest = app.settingsModel.signRequest.value
                                )
                                readerJob = null
                                if (readerMostRecentDeviceResponse.value != null) {
                                    with(Dispatchers.Main) {
                                        val nfcEngagementType = when (scanResult.type) {
                                            MdocHandoverType.STATIC_HANDOVER -> "NFC Static Handover"
                                            MdocHandoverType.NEGOTIATED_HANDOVER -> "NFC Negotiated Handover"
                                            MdocHandoverType.V2_HANDOVER -> "NFC Handover V2"
                                        }
                                        showResponse(
                                            /* vpToken = */ null,
                                            /* deviceResponse = */ Cbor.decode(readerMostRecentDeviceResponse.value!!),
                                            /* session = */ readerSession.value!!,
                                            /* eReaderKey */ eReaderKey.value!!,
                                            /* metadata = */ ShowResponseMetadata(
                                                engagementType = nfcEngagementType,
                                                transferProtocol = transferProtocol,
                                                requestSize = readerMostRecentDeviceRequest.value?.size?.toLong() ?: 0L,
                                                responseSize = readerMostRecentDeviceResponse.value?.size?.toLong() ?: 0L,
                                                durationMsecNfcTapToEngagement = scanResult.processingDuration.inWholeMilliseconds,
                                                durationMsecEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent.value?.inWholeMilliseconds ?: 0,
                                                durationMsecRequestSentToResponseReceived = durationRequestSentToResponseReceived.value?.inWholeMilliseconds ?: 0,
                                                nfcHybridTransportStats = (scanResult.transport as? NfcHybridTransportMdocReader)?.stats
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
                }

                item {
                    TextButton(
                        onClick = {
                            launchNfcScan(MdocReaderNfcHandoverOptions(useNfcV2 = false), nfcOnly = false)
                            readerMostRecentDeviceResponse.value = null
                            eReaderKey.value = null
                        },
                        content = { Text("Request mdoc via NFC") }
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            launchNfcScan(MdocReaderNfcHandoverOptions(useNfcV2 = true), nfcOnly = false)
                            readerMostRecentDeviceResponse.value = null
                            eReaderKey.value = null
                        },
                        content = { Text("Request mdoc via NFCv2") }
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            launchNfcScan(MdocReaderNfcHandoverOptions(useNfcV2 = true), nfcOnly = true)
                            readerMostRecentDeviceResponse.value = null
                            eReaderKey.value = null
                        },
                        content = { Text("Request mdoc via NFCv2 (NFC only)") }
                    )
                }

                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = app.settingsModel.signRequest.collectAsState().value,
                            onCheckedChange = { value ->
                                app.settingsModel.signRequest.value = value
                            },
                        )
                        Text(
                            text = "Sign the request",
                        )
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = app.settingsModel.observeModeEmitPollingFramesAsReader.collectAsState().value,
                            onCheckedChange = { value ->
                                app.settingsModel.observeModeEmitPollingFramesAsReader.value = value
                            },
                        )
                        Text(
                            text = "Insert polling frames for Observe Mode",
                        )
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked =  app.settingsModel.readerAllowMultipleRequests.collectAsState().value,
                            onCheckedChange = { value ->
                                app.settingsModel.readerAllowMultipleRequests.value = value
                            },
                        )
                        Text(
                            text = "Keep connection open for multiple requests",
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
    insertSequenceNumbers: Boolean,
    bleUseL2CAP: Boolean,
    bleUseL2CAPInEngagement: Boolean,
    showToast: (message: String) -> Unit,
    readerTransport: MutableState<MdocTransport?>,
    readerSessionEncryption: MutableState<SessionEncryption?>,
    readerSession: MutableState<VerificationSession?>,
    readerMostRecentDeviceRequest: MutableState<ByteArray?>,
    readerMostRecentDeviceResponse: MutableState<ByteArray?>,
    eReaderKey: MutableState<EcPrivateKey?>,
    durationEngagementReceivedToRequestSent: MutableState<Duration?>,
    durationRequestSentToResponseReceived: MutableState<Duration?>,
    requestSelected: MutableState<RequestPickerEntry>,
    selectConnectionMethod: suspend (connectionMethods: List<MdocConnectionMethod>) -> MdocConnectionMethod?,
    signRequest: Boolean
) {
    Logger.iCbor(TAG, "DeviceEngagement", encodedDeviceEngagement.toByteArray())
    val deviceEngagement = DeviceEngagement.fromDataItem(Cbor.decode(encodedDeviceEngagement.toByteArray()))
    val eDeviceKey = deviceEngagement.eDeviceKey
    Logger.i(TAG, "Using curve ${eDeviceKey.curve.name} for session encryption")
    eReaderKey.value = Crypto.createEcPrivateKey(eDeviceKey.curve)

    val transport = if (existingTransport != null) {
        Logger.i(TAG, "existingTransport: $existingTransport")
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
                        insertSequenceNumbers = insertSequenceNumbers,
                        showToast = showToast,
                        readerTransport = readerTransport,
                        readerSessionEncryption = readerSessionEncryption,
                        readerSession = readerSession,
                        readerMostRecentDeviceRequest = readerMostRecentDeviceRequest,
                        readerMostRecentDeviceResponse = readerMostRecentDeviceResponse,
                        durationEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent,
                        durationRequestSentToResponseReceived = durationRequestSentToResponseReceived,
                        selectedRequest = requestSelected,
                        eDeviceKey = eDeviceKey,
                        eReaderKey = eReaderKey.value!!,
                        signRequest = signRequest
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
        insertSequenceNumbers = insertSequenceNumbers,
        showToast = showToast,
        readerTransport = readerTransport,
        readerSessionEncryption = readerSessionEncryption,
        readerSession = readerSession,
        readerMostRecentDeviceRequest = readerMostRecentDeviceRequest,
        readerMostRecentDeviceResponse = readerMostRecentDeviceResponse,
        durationEngagementReceivedToRequestSent = durationEngagementReceivedToRequestSent,
        durationRequestSentToResponseReceived = durationRequestSentToResponseReceived,
        selectedRequest = requestSelected,
        eDeviceKey = eDeviceKey,
        eReaderKey = eReaderKey.value!!,
        signRequest = signRequest
    )
}

private suspend fun doReaderFlowWithTransport(
    app: App,
    transport: MdocTransport,
    encodedDeviceEngagement: ByteString,
    handover: DataItem,
    allowMultipleRequests: Boolean,
    insertSequenceNumbers: Boolean,
    showToast: (message: String) -> Unit,
    readerTransport: MutableState<MdocTransport?>,
    readerSessionEncryption: MutableState<SessionEncryption?>,
    readerSession: MutableState<VerificationSession?>,
    readerMostRecentDeviceRequest: MutableState<ByteArray?>,
    readerMostRecentDeviceResponse: MutableState<ByteArray?>,
    durationEngagementReceivedToRequestSent: MutableState<Duration?>,
    durationRequestSentToResponseReceived: MutableState<Duration?>,
    selectedRequest: MutableState<RequestPickerEntry>,
    eDeviceKey: EcPublicKey,
    eReaderKey: EcPrivateKey,
    signRequest: Boolean,
) {
    readerTransport.value = transport
    val session = TestAppUtils.createProximityVerificationSession(
        app = app,
        request = selectedRequest.value.request,
        handover = handover,
        deviceEngagement = encodedDeviceEngagement,
        eReaderKey = eReaderKey,
        signRequest = signRequest,
        requestSdJwtVc = selectedRequest.value.requestSdJwtVc
    )
    readerSession.value = session
    val proximityRequest = session.find<VerificationSession.Iso18013ProximityRequest>()
    val deviceRequest = Cbor.encode(proximityRequest.deviceRequest)
    val sessionEncryption = SessionEncryption(
        role = MdocRole.MDOC_READER,
        eReaderKey,
        eDeviceKey,
        Cbor.encode(proximityRequest.sessionTranscript),
    )
    readerSessionEncryption.value = sessionEncryption
    Logger.iCbor(TAG, "deviceRequest", deviceRequest)
    try {
        val t0 = Clock.System.now()
        transport.open(eDeviceKey)
        readerMostRecentDeviceRequest.value = deviceRequest
        transport.sendMessage(
            sessionEncryption.encryptMessage(
                messagePlaintext = deviceRequest,
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
    readerSession: VerificationSession?,
    eReaderKey: EcPrivateKey?,
) {
    val deviceResponse1 = readerMostRecentDeviceResponse.value
    if (readerSession == null) {
        // Making the request. We could show some text here, but typically NFC reader is shown at
        // this point and it provides enough visual feedback to the user.
    } else if (deviceResponse1 == null || deviceResponse1.isEmpty() || eReaderKey == null) {
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
                session = readerSession,
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
