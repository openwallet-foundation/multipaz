package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.compose.cards.InfoCard
import org.multipaz.compose.cards.WarningCard
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.document.DocumentModel
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.testapp.TestAppPlatform

@Composable
fun StartScreen(
    documentModel: DocumentModel,
    onClickAbout: () -> Unit = {},
    onClickDocumentStore: () -> Unit = {},
    onClickTrustedIssuers: () -> Unit = {},
    onClickTrustedVerifiers: () -> Unit = {},
    onClickSoftwareSecureArea: () -> Unit = {},
    onClickAndroidKeystoreSecureArea: () -> Unit = {},
    onClickCloudSecureArea: () -> Unit = {},
    onClickSecureEnclaveSecureArea: () -> Unit = {},
    onClickPassphraseEntryField: () -> Unit = {},
    onClickPassphrasePrompt: () -> Unit = {},
    onClickProvisioningTestField: () -> Unit = {},
    onClickConsentSheetList: () -> Unit = {},
    onClickQrCodes: () -> Unit = {},
    onClickNfc: () -> Unit = {},
    onClickIsoMdocProximitySharing: () -> Unit = {},
    onClickIsoMdocProximityReading: () -> Unit = {},
    onClickDcRequest: () -> Unit = {},
    onClickMdocTransportMultiDeviceTesting: () -> Unit = {},
    onClickCertificatesViewerExamples: () -> Unit = {},
    onClickRichText: () -> Unit = {},
    onClickNotifications: () -> Unit = {},
    onClickScreenLock: () -> Unit = {},
    onClickPickersScreen: () -> Unit = {}
) {
    val blePermissionState = rememberBluetoothPermissionState()
    val coroutineScope = rememberCoroutineScope()
    val documentInfos = documentModel.documentInfos.collectAsState().value

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Column {
                AppUpdateCard()
                if (documentInfos.isEmpty()) {
                    WarningCard(
                        modifier = Modifier.padding(8.dp).clickable() {
                            onClickDocumentStore()
                        }
                    ) {
                        Text("Document Store is empty so proximity and W3C DC presentment won't work. Click to fix.")
                    }
                } else {
                    val numDocs = documentInfos.size
                    InfoCard(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text("Document Store has $numDocs documents. For proximity presentment, use NFC or QR. " +
                                "For W3C DC API, go to a reader website in a supported browser.")
                    }
                }
                if (!documentInfos.isEmpty()) {
                    if (!blePermissionState.isGranted) {
                        WarningCard(
                            modifier = Modifier.padding(8.dp).clickable() {
                                coroutineScope.launch {
                                    blePermissionState.launchPermissionRequest()
                                }
                            }
                        ) {
                            Text("Proximity presentment require BLE permissions to be granted. Click to fix.")
                        }
                    }
                }
            }
            LazyColumn {
                item {
                    TextButton(onClick = onClickAbout) {
                        Text("About")
                    }
                }

                item {
                    TextButton(onClick = onClickDocumentStore) {
                        Text("Document store")
                    }
                }

                item {
                    TextButton(onClick = onClickTrustedIssuers) {
                        Text("Trusted issuers")
                    }
                }

                item {
                    TextButton(onClick = onClickTrustedVerifiers) {
                        Text("Trusted verifiers")
                    }
                }

                item {
                    TextButton(onClick = onClickSoftwareSecureArea) {
                        Text("Software Secure Area")
                    }
                }

                when (TestAppConfiguration.platform) {
                    TestAppPlatform.ANDROID -> {
                        item {
                            TextButton(onClick = onClickAndroidKeystoreSecureArea) {
                                Text("Android Keystore Secure Area")
                            }
                        }
                    }

                    TestAppPlatform.IOS -> {
                        item {
                            TextButton(onClick = onClickSecureEnclaveSecureArea) {
                                Text("Secure Enclave Secure Area")
                            }
                        }
                    }

                    TestAppPlatform.WASMJS -> {
                        // No native SecureArea (for now)
                    }
                }

                item {
                    TextButton(onClick = onClickCloudSecureArea) {
                        Text("Cloud Secure Area")
                    }
                }

                item {
                    TextButton(onClick = onClickPassphraseEntryField) {
                        Text("PassphraseEntryField use-cases")
                    }
                }

                item {
                    TextButton(onClick = onClickPassphrasePrompt) {
                        Text("PassphrasePrompt use-cases")
                    }
                }

                /*
                // Not useful yet
                item {
                    TextButton(onClick = onClickProvisioningTestField) {
                        Text(stringResource(Res.string.provisioning_test_title))
                    }
                }
                 */

                item {
                    TextButton(onClick = onClickConsentSheetList) {
                        Text("Consent prompt use-cases")
                    }
                }
                item {
                    TextButton(onClick = onClickQrCodes) {
                        Text("QR code generation and scanning")
                    }
                }
                item {
                    TextButton(onClick = onClickNfc) {
                        Text("NFC sharing and scanning")
                    }
                }
                item {
                    TextButton(onClick = onClickIsoMdocProximitySharing) {
                        Text("ISO mdoc Proximity Sharing")
                    }
                }

                item {
                    TextButton(onClick = onClickIsoMdocProximityReading) {
                        Text("ISO mdoc Proximity Reading")
                    }
                }

                item {
                    TextButton(onClick = onClickDcRequest) {
                        Text("W3C Digital Credentials requests")
                    }
                }

                item {
                    TextButton(onClick = onClickMdocTransportMultiDeviceTesting) {
                        Text("ISO mdoc Multi-Device Testing")
                    }
                }

                item {
                    TextButton(onClick = onClickCertificatesViewerExamples) {
                        Text("CertificateViewer use-cases")
                    }
                }

                item {
                    TextButton(onClick = onClickRichText) {
                        Text("Rich Text")
                    }
                }

                item {
                    TextButton(onClick = onClickNotifications) {
                        Text("Notifications")
                    }
                }

                item {
                    TextButton(onClick = onClickScreenLock) {
                        Text("Screen lock")
                    }
                }

                item {
                    TextButton(onClick = onClickPickersScreen) {
                        Text("Pickers")
                    }
                }
            }
        }
    }
}
