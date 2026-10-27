package org.multipaz.legacytestapp

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.coroutineScope
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.launch
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.document.DocumentInfo
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.compose.qrcode.generateQrCode
import org.multipaz.context.initializeApplication
import org.multipaz.legacytestapp.LegacyPresentmentManager.SessionState
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.util.Logger

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(applicationContext)
        enableEdgeToEdge()

        val appState = LegacyAppState.getInstance()
        lifecycle.coroutineScope.launch {
            appState.initialize()
            setContent {
                LegacyTestAppContent(appState = appState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.let { adapter ->
            val cardEmulation = CardEmulation.getInstance(adapter)
            val componentName = ComponentName(this, LegacyNfcApduService::class.java)
            if (!cardEmulation.setPreferredService(this, componentName)) {
                Logger.w(TAG, "CardEmulation.setPreferredService() returned false")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.let { adapter ->
            val cardEmulation = CardEmulation.getInstance(adapter)
            cardEmulation.unsetPreferredService(this)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyTestAppContent(appState: LegacyAppState) {
    val currentBranding by Branding.Current.collectAsState()
    val platformContext = LocalPlatformContext.current
    val imageLoader = remember {
        ImageLoader.Builder(platformContext)
            .components {
                add(KtorNetworkFetcherFactory(HttpClient(Android)))
            }
            .build()
    }

    currentBranding.theme {
        PromptDialogs(
            promptModel = appState.promptModel,
            imageLoader = imageLoader
        )
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Multipaz Legacy TestApp") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        ) { innerPadding ->
            LegacyMainScreen(
                appState = appState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
private fun LegacyMainScreen(
    appState: LegacyAppState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope { appState.promptModel }
    val scrollState = rememberScrollState()

    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()

    val sessionState by LegacyPresentmentManager.sessionState.collectAsState()
    val documentInfos by appState.documentModel.documentInfos.collectAsState()

    val nfcStaticHandover by LegacyPresentmentManager.nfcStaticHandover.collectAsState()
    val bleCentralClientMode by LegacyPresentmentManager.bleCentralClientMode.collectAsState()
    val blePeripheralServerMode by LegacyPresentmentManager.blePeripheralServerMode.collectAsState()
    val nfcDataTransfer by LegacyPresentmentManager.nfcDataTransfer.collectAsState()
    val bleUseL2Cap by LegacyPresentmentManager.bleUseL2Cap.collectAsState()
    val requireAuth by LegacyPresentmentManager.requireAuthentication.collectAsState()
    val showConsent by LegacyPresentmentManager.showConsentPrompt.collectAsState()
    val preferSignature by LegacyPresentmentManager.preferSignatureToKeyAgreement.collectAsState()
    val keepConnectionOpen by LegacyPresentmentManager.keepConnectionOpen.collectAsState()

    var isResettingDocs by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!blePermissionState.isGranted) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Bluetooth permissions are required for BLE mdoc transport.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                blePermissionState.launchPermissionRequest()
                            }
                        }
                    ) {
                        Text("Grant Bluetooth Permissions")
                    }
                }
            }
        } else if (!bleEnabledState.isEnabled) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Bluetooth is disabled. Enable Bluetooth to use BLE transport.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
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
            }
        }

        PresentmentSessionCard(
            sessionState = sessionState,
            nfcStaticHandover = nfcStaticHandover,
            onStartQr = { LegacyPresentmentManager.startQrEngagement(context) },
            onReset = { LegacyPresentmentManager.reset() }
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Legacy Engagement & Transport Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                SettingSwitchRow(
                    title = "NFC Static Handover (off = Negotiated)",
                    checked = nfcStaticHandover,
                    onCheckedChange = { LegacyPresentmentManager.nfcStaticHandover.value = it }
                )
                SettingSwitchRow(
                    title = "BLE Central Client Mode",
                    checked = bleCentralClientMode,
                    onCheckedChange = { LegacyPresentmentManager.bleCentralClientMode.value = it }
                )
                SettingSwitchRow(
                    title = "BLE Peripheral Server Mode",
                    checked = blePeripheralServerMode,
                    onCheckedChange = { LegacyPresentmentManager.blePeripheralServerMode.value = it }
                )
                SettingSwitchRow(
                    title = "NFC Data Transfer",
                    checked = nfcDataTransfer,
                    onCheckedChange = { LegacyPresentmentManager.nfcDataTransfer.value = it }
                )
                SettingSwitchRow(
                    title = "BLE Use L2CAP",
                    checked = bleUseL2Cap,
                    onCheckedChange = { LegacyPresentmentManager.bleUseL2Cap.value = it }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Presentment Options",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                SettingSwitchRow(
                    title = "Require User Authentication",
                    checked = requireAuth,
                    onCheckedChange = { LegacyPresentmentManager.requireAuthentication.value = it }
                )
                SettingSwitchRow(
                    title = "Show Consent Prompt",
                    checked = showConsent,
                    onCheckedChange = { LegacyPresentmentManager.showConsentPrompt.value = it }
                )
                SettingSwitchRow(
                    title = "Prefer ECDSA Signature over MAC",
                    checked = preferSignature,
                    onCheckedChange = {
                        LegacyPresentmentManager.preferSignatureToKeyAgreement.value = it
                    }
                )
                SettingSwitchRow(
                    title = "Keep Connection Open After Response",
                    checked = keepConnectionOpen,
                    onCheckedChange = { LegacyPresentmentManager.keepConnectionOpen.value = it }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hard-coded Test Documents (${documentInfos.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        enabled = !isResettingDocs,
                        onClick = {
                            coroutineScope.launch {
                                isResettingDocs = true
                                try {
                                    appState.resetAndProvisionHardCodedTestDocuments()
                                } finally {
                                    isResettingDocs = false
                                }
                            }
                        }
                    ) {
                        Text(if (isResettingDocs) "Resetting..." else "Reset Docs")
                    }
                }

                for (docInfo in documentInfos) {
                    DocumentRow(docInfo = docInfo)
                }
            }
        }
    }
}

@Composable
private fun PresentmentSessionCard(
    sessionState: SessionState,
    nfcStaticHandover: Boolean,
    onStartQr: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "ISO mdoc Proximity Presentment (Legacy)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            when (sessionState) {
                is SessionState.Idle -> {
                    Text(
                        text = "Ready for NFC tap (${if (nfcStaticHandover) "Static" else "Negotiated"} Handover) or start QR engagement below.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onStartQr) {
                        Text("Present via QR Code")
                    }
                }

                is SessionState.QrEngaging -> {
                    val qrBitmap = remember(sessionState.qrCodeUri) {
                        generateQrCode(sessionState.qrCodeUri)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Device Engagement QR Code",
                            modifier = Modifier.size(240.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Text(
                        text = "Scan QR code with an ISO 18013-5 reader",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = sessionState.qrCodeUri,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedButton(onClick = onReset) {
                        Text("Cancel QR Engagement")
                    }
                }

                is SessionState.NfcEngaging -> {
                    CircularProgressIndicator()
                    Text(
                        text = "${sessionState.handoverType}: Waiting for reader to connect...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(onClick = onReset) {
                        Text("Cancel")
                    }
                }

                is SessionState.Connecting -> {
                    CircularProgressIndicator()
                    Text(
                        text = "${sessionState.engagementSource}: Reader connecting...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(onClick = onReset) {
                        Text("Cancel")
                    }
                }

                is SessionState.Connected -> {
                    CircularProgressIndicator()
                    Text(
                        text = "Connected via ${sessionState.engagementSource}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = sessionState.transportDescription,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedButton(onClick = onReset) {
                        Text("Disconnect")
                    }
                }

                is SessionState.ProcessingRequest -> {
                    CircularProgressIndicator()
                    Text(
                        text = "Processing DeviceRequest (${sessionState.engagementSource})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Requested: ${sessionState.requestedDocTypes.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedButton(onClick = onReset) {
                        Text("Cancel")
                    }
                }

                is SessionState.Completed -> {
                    Text(
                        text = "Presentment Completed (${sessionState.engagementSource})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Transport: ${sessionState.transportDescription}\n" +
                            "Requests served: ${sessionState.numRequestsServed} (${sessionState.responseSizeBytes} bytes)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStartQr) {
                            Text("New QR Session")
                        }
                        OutlinedButton(onClick = onReset) {
                            Text("Done")
                        }
                    }
                }

                is SessionState.Failed -> {
                    Text(
                        text = "Presentment Failed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = sessionState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStartQr) {
                            Text("Retry QR")
                        }
                        OutlinedButton(onClick = onReset) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun DocumentRow(docInfo: DocumentInfo) {
    val mdocDocType = docInfo.credentialInfos
        .mapNotNull { it.credential as? MdocCredential }
        .firstOrNull()
        ?.docType

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = docInfo.cardArt,
            contentDescription = docInfo.document.displayName,
            modifier = Modifier
                .width(88.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = docInfo.document.displayName ?: docInfo.document.identifier,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            docInfo.document.typeDisplayName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            mdocDocType?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
