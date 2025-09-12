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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.testapp.DocumentModel
import org.multipaz.testapp.Platform
import org.multipaz.testapp.platform
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.about_screen_title
import multipazproject.samples.testapp.generated.resources.android_keystore_secure_area_screen_title
import multipazproject.samples.testapp.generated.resources.certificate_viewer_examples_title
import multipazproject.samples.testapp.generated.resources.passphrase_entry_field_screen_title
import multipazproject.samples.testapp.generated.resources.cloud_secure_area_screen_title
import multipazproject.samples.testapp.generated.resources.consent_prompt_screen_title
import multipazproject.samples.testapp.generated.resources.document_store_screen_title
import multipazproject.samples.testapp.generated.resources.iso_mdoc_multi_device_testing_title
import multipazproject.samples.testapp.generated.resources.iso_mdoc_proximity_reading_title
import multipazproject.samples.testapp.generated.resources.iso_mdoc_proximity_sharing_title
import multipazproject.samples.testapp.generated.resources.nfc_screen_title
import multipazproject.samples.testapp.generated.resources.notifications_title
import multipazproject.samples.testapp.generated.resources.passphrase_prompt_screen_title
import multipazproject.samples.testapp.generated.resources.qr_codes_screen_title
import multipazproject.samples.testapp.generated.resources.rich_text_title
import multipazproject.samples.testapp.generated.resources.screen_lock_title
import multipazproject.samples.testapp.generated.resources.secure_enclave_secure_area_screen_title
import multipazproject.samples.testapp.generated.resources.software_secure_area_screen_title
import multipazproject.samples.testapp.generated.resources.face_detection_title
import multipazproject.samples.testapp.generated.resources.selfie_check_title
import multipazproject.samples.testapp.generated.resources.face_match_title
import kotlinx.coroutines.launch
import multipazproject.samples.testapp.generated.resources.dc_request_title
import multipazproject.samples.testapp.generated.resources.barcode_scanning_title
import multipazproject.samples.testapp.generated.resources.camera_title
import multipazproject.samples.testapp.generated.resources.trusted_issuers_screen_title
import multipazproject.samples.testapp.generated.resources.trusted_verifiers_screen_title
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.cards.InfoCard
import org.multipaz.compose.cards.WarningCard
import org.multipaz.compose.permissions.rememberBluetoothPermissionState

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
    onClickCamera: () -> Unit = {},
    onClickFaceDetection: () -> Unit = {},
    onClickBarcodeScanning: () -> Unit = {},
    onClickSelfieCheck: () -> Unit = {},
    onClickFaceMatch: () -> Unit = {}
) {
    val blePermissionState = rememberBluetoothPermissionState()
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Column {
                AppUpdateCard()
                if (documentModel.documentInfos.isEmpty()) {
                    WarningCard(
                        modifier = Modifier.padding(8.dp).clickable() {
                            onClickDocumentStore()
                        }
                    ) {
                        Text("Document Store is empty so proximity and W3C DC presentment won't work. Click to fix.")
                    }
                } else {
                    val numDocs = documentModel.documentInfos.size
                    InfoCard(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text("Document Store has $numDocs documents. For proximity presentment, use NFC or QR. " +
                                "For W3C DC API, go to a reader website in a supported browser.")
                    }
                }
                if (!documentModel.documentInfos.isEmpty()) {
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
                        Text(stringResource(Res.string.about_screen_title))
                    }
                }

                item {
                    TextButton(onClick = onClickDocumentStore) {
                        Text(stringResource(Res.string.document_store_screen_title))
                    }
                }

                item {
                    TextButton(onClick = onClickTrustedIssuers) {
                        Text(stringResource(Res.string.trusted_issuers_screen_title))
                    }
                }

                item {
                    TextButton(onClick = onClickTrustedVerifiers) {
                        Text(stringResource(Res.string.trusted_verifiers_screen_title))
                    }
                }

                item {
                    TextButton(onClick = onClickSoftwareSecureArea) {
                        Text(stringResource(Res.string.software_secure_area_screen_title))
                    }
                }

                when (platform) {
                    Platform.ANDROID -> {
                        item {
                            TextButton(onClick = onClickAndroidKeystoreSecureArea) {
                                Text(stringResource(Res.string.android_keystore_secure_area_screen_title))
                            }
                        }
                    }

                    Platform.IOS -> {
                        item {
                            TextButton(onClick = onClickSecureEnclaveSecureArea) {
                                Text(stringResource(Res.string.secure_enclave_secure_area_screen_title))
                            }
                        }
                    }
                }

                item {
                    TextButton(onClick = onClickCloudSecureArea) {
                        Text(stringResource(Res.string.cloud_secure_area_screen_title))
                    }
                }

                item {
                    TextButton(onClick = onClickPassphraseEntryField) {
                        Text(stringResource(Res.string.passphrase_entry_field_screen_title))
                    }
                }

                item {
                    TextButton(onClick = onClickPassphrasePrompt) {
                        Text(stringResource(Res.string.passphrase_prompt_screen_title))
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
                        Text(stringResource(Res.string.consent_prompt_screen_title))
                    }
                }
                item {
                    TextButton(onClick = onClickQrCodes) {
                        Text(stringResource(Res.string.qr_codes_screen_title))
                    }
                }
                item {
                    TextButton(onClick = onClickNfc) {
                        Text(stringResource(Res.string.nfc_screen_title))
                    }
                }
                item {
                    TextButton(onClick = onClickIsoMdocProximitySharing) {
                        Text(stringResource(Res.string.iso_mdoc_proximity_sharing_title))
                    }
                }

                item {
                    TextButton(onClick = onClickIsoMdocProximityReading) {
                        Text(stringResource(Res.string.iso_mdoc_proximity_reading_title))
                    }
                }

                item {
                    TextButton(onClick = onClickDcRequest) {
                        Text(stringResource(Res.string.dc_request_title))
                    }
                }

                item {
                    TextButton(onClick = onClickMdocTransportMultiDeviceTesting) {
                        Text(stringResource(Res.string.iso_mdoc_multi_device_testing_title))
                    }
                }

                item {
                    TextButton(onClick = onClickCertificatesViewerExamples) {
                        Text(stringResource(Res.string.certificate_viewer_examples_title))
                    }
                }

                item {
                    TextButton(onClick = onClickRichText) {
                        Text(stringResource(Res.string.rich_text_title))
                    }
                }

                item {
                    TextButton(onClick = onClickNotifications) {
                        Text(stringResource(Res.string.notifications_title))
                    }
                }

                item {
                    TextButton(onClick = onClickScreenLock) {
                        Text(stringResource(Res.string.screen_lock_title))
                    }
                }

                item {
                    TextButton(onClick = onClickCamera) {
                        Text(stringResource(Res.string.camera_title))
                    }
                }

                item {
                    TextButton(onClick = onClickFaceDetection) {
                        Text(stringResource(Res.string.face_detection_title))
                    }
                }

                item {
                    TextButton(onClick = onClickFaceMatch) {
                        Text(stringResource(Res.string.face_match_title))
                    }
                }

                item {
                    TextButton(onClick = onClickSelfieCheck) {
                        Text(stringResource(Res.string.selfie_check_title))
                    }
                }

                item {
                    TextButton(onClick = onClickBarcodeScanning) {
                        Text(stringResource(Res.string.barcode_scanning_title))
                    }
                }
            }
        }
    }
}
