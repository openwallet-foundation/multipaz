package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X500Name
import org.multipaz.document.DocumentStore
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.secure_area_test_app.ui.CsaConnectDialog
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.cloud.CloudCreateKeySettings
import org.multipaz.securearea.cloud.CloudSecureArea
import org.multipaz.securearea.cloud.CloudUserAuthType
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.compose.document.DocumentModel
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.testapp.TestAppUtils
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.multipaz.compose.document.DocumentCarousel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.util.Logger
import org.multipaz.util.Platform
import org.multipaz.util.truncateToWholeSeconds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private const val TAG = "DocumentStoreScreen"

enum class DocumentCreationMode {
    NORMAL,
    EUPID_WITH_10_CREDENTIALS,
    EUPID_WITH_10_CREDENTIALS_BATCH,
    DCQL_TEST_DOCUMENTS
}

private val userAuthenticationTimeoutValues = mapOf(
    "0 sec (Auth every use)" to 0.seconds,
    "10 sec" to 10.seconds,
    "60 sec" to 60.seconds,
    "No auth" to null
)

@Composable
fun DocumentStoreScreen(
    documentStore: DocumentStore,
    documentModel: DocumentModel,
    softwareSecureArea: SoftwareSecureArea,
    settingsModel: TestAppSettingsModel,
    iacaKey: AsymmetricKey.X509Certified,
    showToast: (message: String) -> Unit,
    onViewDocument: (documentId: String) -> Unit,
) {
    // TODO: Use the same coroutine scope as what the storage layer uses to make it faster.
    val coroutineScope = rememberCoroutineScope()
    val numCredentialsPerDomain = remember { mutableIntStateOf(2) }
    val deviceKeyAlgorithm = remember { mutableStateOf<Algorithm>(Algorithm.ESP256) }
    val deviceKeyMacAlgorithm = remember { mutableStateOf<Algorithm>(Algorithm.ECDH_P256) }
    val documentSigningAlgorithm = remember { mutableStateOf<Algorithm>(Algorithm.ESP256) }
    val showProvisioningResult = remember { mutableStateOf<AnnotatedString?>(null) }
    val userAuthenticationTimeout = remember { mutableStateOf<Duration?>(10.seconds) }
    val documentInfos = documentModel.documentInfos.collectAsState().value

    val showDocumentCreationDialog = remember { mutableStateOf(false) }
    if (showDocumentCreationDialog.value) {
        // TODO: would be nice to show a live-updated string "Creating credential 42 of 80"
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = "Creating Documents") },
            text = { Text(text = "Creating documents and credentials may take a while. " +
            "This dialog will disappear when the process is complete.") },
            confirmButton = {
                Button(
                    onClick = {}) {
                    Text("OK")
                }
            }
        )
    }

    showProvisioningResult.value?.let {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = "Provisioning Result") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .fillMaxWidth()
                        .background(color = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = it
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showProvisioningResult.value = null }) {
                    Text("Close")
                }
            }
        )
    }

    val showCsaConnectDialog = remember { mutableStateOf(false) }
    val documentCreationMode = remember { mutableStateOf<DocumentCreationMode>(DocumentCreationMode.NORMAL) }
    if (showCsaConnectDialog.value) {
        CsaConnectDialog(
            settingsModel.cloudSecureAreaUrl.collectAsState().value,
            onDismissRequest = {
                showCsaConnectDialog.value = false
            },
            onConnectButtonClicked = { url: String, walletPin: String, constraints: PassphraseConstraints ->
                showCsaConnectDialog.value = false
                settingsModel.cloudSecureAreaUrl.value = url
                coroutineScope.launch {
                    val cloudSecureArea = CloudSecureArea.create(
                        TestAppConfiguration.storage,
                        "CloudSecureArea?url=${url.encodeURLParameter()}",
                        url,
                        TestAppConfiguration.httpClientEngineFactory
                    )
                    try {
                        cloudSecureArea.register(
                            walletPin,
                            constraints
                        ) { true }
                        showToast("Registered with CSA")
                        val dsKey = generateDsKeyAndCert(documentSigningAlgorithm.value, iacaKey)
                        provisionTestDocuments(
                            documentCreationMode = documentCreationMode.value,
                            showProvisioningResult = showProvisioningResult,
                            documentStore = documentStore,
                            secureArea = cloudSecureArea,
                            secureAreaCreateKeySettingsFunc = { challenge, algorithm, userAuthenticationRequired,
                                                                validFrom, validUntil ->
                                CloudCreateKeySettings.Builder(challenge)
                                    .setAlgorithm(algorithm)
                                    .setPassphraseRequired(true)
                                    .setUserAuthenticationRequired(
                                        userAuthenticationRequired,
                                        setOf(CloudUserAuthType.PASSCODE, CloudUserAuthType.BIOMETRIC)
                                    )
                                    .setValidityPeriod(validFrom, validUntil)
                                    .build()
                            },
                            dsKey = dsKey,
                            showToast = showToast,
                            deviceKeyAlgorithm = deviceKeyAlgorithm.value,
                            deviceKeyMacAlgorithm = deviceKeyMacAlgorithm.value,
                            numCredentialsPerDomain = numCredentialsPerDomain.value,
                            showDocumentCreationDialog = showDocumentCreationDialog,
                        )
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        showToast("${e.message}")
                    }
                }
            }
        )
    }


    LazyColumn(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            DocumentCarousel(
                documentModel = documentModel,
                initialDocumentId = settingsModel.currentlyFocusedDocumentId.value,
                allowReordering = true,
                onDocumentClicked = { documentInfo ->
                    onViewDocument(documentInfo.document.identifier)
                },
                onDocumentFocused = { documentInfo ->
                    settingsModel.currentlyFocusedDocumentId.value = documentInfo.document.identifier
                },
                onDocumentReordered = { documentInfo, oldPos, newPos ->
                    coroutineScope.launch {
                        try {
                            documentModel.setDocumentPosition(
                                documentInfo = documentInfo,
                                position = newPos
                            )
                        } catch (e: IllegalArgumentException) {
                            Logger.e(TAG, "Error setting document position", e)
                        }
                    }
                },
                selectedDocumentInfo = { documentInfo, index, total ->
                    if (documentInfo != null) {
                        Text(
                            text = "${index + 1} of $total: ${documentInfo.document.displayName ?: "No displayname"}",
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Drag to reorder",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                emptyDocumentContent = {
                    Text(
                        text = "No documents in store",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }

        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    val timestampBegin = Clock.System.now()
                    documentStore.listDocumentIds().forEach { documentId ->
                        documentStore.deleteDocument(documentId)
                    }
                    val timestampEnd = Clock.System.now()
                    Logger.e(TAG, "Deleted all docs in ${timestampEnd - timestampBegin}")
                }
            }) {
                Text(text = "Delete all Documents")
            }
        }
        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    if (deviceKeyMacAlgorithm.value != Algorithm.UNSET && !TestAppConfiguration.platformSecureAreaHasKeyAgreement) {
                        showToast("Platform Secure Area does not have Key Agreement support. " +
                                "Unset DeviceKey MAC Algorithm or try another Secure Area.")
                        return@launch
                    }
                    val dsKey = generateDsKeyAndCert(documentSigningAlgorithm.value, iacaKey)
                    provisionTestDocuments(
                        documentCreationMode = DocumentCreationMode.NORMAL,
                        showProvisioningResult = showProvisioningResult,
                        documentStore = documentStore,
                        secureArea = Platform.getSecureArea(TestAppConfiguration.storage),
                        secureAreaCreateKeySettingsFunc = { challenge, algorithm, userAuthenticationRequired,
                                                            validFrom, validUntil ->
                            CreateKeySettings(
                                algorithm = algorithm,
                                nonce = challenge,
                                userAuthenticationRequired = userAuthenticationRequired && userAuthenticationTimeout.value != null,
                                userAuthenticationTimeout = userAuthenticationTimeout.value ?: 0.seconds,
                                validFrom = validFrom,
                                validUntil = validUntil
                            )
                        },
                        dsKey = dsKey,
                        showToast = showToast,
                        deviceKeyAlgorithm = deviceKeyAlgorithm.value,
                        deviceKeyMacAlgorithm = deviceKeyMacAlgorithm.value,
                        numCredentialsPerDomain = numCredentialsPerDomain.value,
                        showDocumentCreationDialog = showDocumentCreationDialog,
                    )
                }
            }) {
                Text(text = "Create Test Documents in Platform Secure Area")
            }
        }
        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    val dsKey = generateDsKeyAndCert(documentSigningAlgorithm.value, iacaKey)
                    provisionTestDocuments(
                        documentCreationMode = DocumentCreationMode.NORMAL,
                        showProvisioningResult = showProvisioningResult,
                        documentStore = documentStore,
                        secureArea = softwareSecureArea,
                        secureAreaCreateKeySettingsFunc = { challenge, algorithm, userAuthenticationRequired,
                                                            validFrom, validUntil ->
                            SoftwareCreateKeySettings.Builder()
                                .setAlgorithm(algorithm)
                                .setPassphraseRequired(true, "1111", PassphraseConstraints.PIN_FOUR_DIGITS)
                                .build()
                        },
                        dsKey = dsKey,
                        showToast = showToast,
                        deviceKeyAlgorithm = deviceKeyAlgorithm.value,
                        deviceKeyMacAlgorithm = deviceKeyMacAlgorithm.value,
                        numCredentialsPerDomain = numCredentialsPerDomain.value,
                        showDocumentCreationDialog = showDocumentCreationDialog,
                    )
                }
            }) {
                Text(text = "Create Test Documents in Software Secure Area")
            }
        }
        item {
            TextButton(onClick = {
                documentCreationMode.value = DocumentCreationMode.NORMAL
                showCsaConnectDialog.value = true
            }) {
                Text(text = "Create Test Documents in Cloud Secure Area")
            }
        }
        item {
            TextButton(onClick = {
                documentCreationMode.value = DocumentCreationMode.EUPID_WITH_10_CREDENTIALS
                showCsaConnectDialog.value = true
            }) {
                Text(text = "Create EUPID in CSA w/ 10 creds")
            }
        }
        item {
            TextButton(onClick = {
                documentCreationMode.value = DocumentCreationMode.EUPID_WITH_10_CREDENTIALS_BATCH
                showCsaConnectDialog.value = true
            }) {
                Text(text = "Create EUPID in CSA w/ 10 creds (batch)")
            }
        }
        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    val dsKey = generateDsKeyAndCert(documentSigningAlgorithm.value, iacaKey)
                    provisionTestDocuments(
                        documentCreationMode = DocumentCreationMode.DCQL_TEST_DOCUMENTS,
                        showProvisioningResult = showProvisioningResult,
                        documentStore = documentStore,
                        secureArea = Platform.getSecureArea(TestAppConfiguration.storage),
                        secureAreaCreateKeySettingsFunc = { challenge, algorithm, userAuthenticationRequired,
                                                            validFrom, validUntil ->
                            CreateKeySettings(
                                algorithm = algorithm,
                                nonce = challenge,
                                userAuthenticationRequired = userAuthenticationRequired && userAuthenticationTimeout.value != null,
                                userAuthenticationTimeout = userAuthenticationTimeout.value ?: 0.seconds,
                                validFrom = validFrom,
                                validUntil = validUntil
                            )
                        },
                        dsKey = dsKey,
                        showToast = showToast,
                        deviceKeyAlgorithm = deviceKeyAlgorithm.value,
                        deviceKeyMacAlgorithm = deviceKeyMacAlgorithm.value,
                        numCredentialsPerDomain = numCredentialsPerDomain.value,
                        showDocumentCreationDialog = showDocumentCreationDialog,
                    )
                }
            }) {
                Text(text = "Create Test Documents for DCQL testing")
            }
        }
        item {
            SettingHeadline("Settings for new documents")
        }
        item {
            SettingMultipleChoice(
                title ="Credentials per Domain",
                choices = listOf(1, 2, 3, 5, 10, 15, 20).map { it.toString() },
                initialChoice = numCredentialsPerDomain.value.toString(),
                onChoiceSelected = { choice ->
                    numCredentialsPerDomain.value = choice.toInt(10)
                },
            )
        }
        item {
            SettingMultipleChoice(
                title = "DeviceKey Algorithm",
                choices = Algorithm.entries.mapNotNull { if (it.isSigning) it.name else null },
                initialChoice = deviceKeyAlgorithm.value.toString(),
                onChoiceSelected = { choice ->
                    val algorithm = Algorithm.entries.find { it.name == choice }!!
                    deviceKeyAlgorithm.value = algorithm
                },
            )
        }
        item {
            SettingMultipleChoice(
                title = "DeviceKey MAC Algorithm",
                choices = listOf(Algorithm.UNSET.name) +
                        Algorithm.entries.mapNotNull { if (it.isKeyAgreement) it.name else null },
                initialChoice = deviceKeyMacAlgorithm.value.toString(),
                onChoiceSelected = { choice ->
                    val algorithm = Algorithm.entries.find { it.name == choice }!!
                    deviceKeyMacAlgorithm.value = algorithm
                },
            )
        }
        item {
            SettingMultipleChoice(
                title = "Document Signing Algorithm",
                choices = Algorithm.entries.mapNotNull { if (it.fullySpecified && it.isSigning) it.name else null },
                initialChoice = documentSigningAlgorithm.value.toString(),
                onChoiceSelected = { choice ->
                    documentSigningAlgorithm.value = Algorithm.entries.find { it.name == choice }!!
                },
            )
        }
        item {
            SettingMultipleChoice(
                title = "User Auth Timeout",
                choices = userAuthenticationTimeoutValues.keys.toList(),
                initialChoice = userAuthenticationTimeoutValues.keys.toList()[1],
                onChoiceSelected = { choice ->
                    val duration = userAuthenticationTimeoutValues[choice]
                    userAuthenticationTimeout.value = duration
                },
            )
        }
        item {
            SettingHeadline("Current Documents in DocumentStore")
        }
        if (documentInfos.isEmpty()) {
            item {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = "DocumentStore is empty",
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            for (documentInfo in documentInfos) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            modifier = Modifier.height(32.dp),
                            contentScale = ContentScale.Fit,
                            bitmap = documentInfo.cardArt,
                            contentDescription = null,
                        )
                        TextButton(onClick = {
                            onViewDocument(documentInfo.document.identifier)
                        }) {
                            Text(
                                text = documentInfo.document.displayName ?: "(displayName not set)"
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun generateDsKeyAndCert(
    algorithm: Algorithm,
    iacaKey: AsymmetricKey.X509Certified
): AsymmetricKey.X509Certified {
    // The DS cert must not be valid for more than 457 days.
    //
    // Reference: ISO/IEC 18013-5:2021 Annex B.1.4 Document signer certificate
    //
    val dsCertValidFrom = Clock.System.now().truncateToWholeSeconds() - 1.days
    val dsCertsValidUntil = dsCertValidFrom + 455.days
    val dsKey = Crypto.createEcPrivateKey(algorithm.curve!!)
    val dsCert = MdocUtil.generateDsCertificate(
        iacaKey = iacaKey,
        dsKey = dsKey.publicKey,
        subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST DS"),
        serial = ASN1Integer.fromRandom(numBits = 128),
        validFrom = dsCertValidFrom,
        validUntil = dsCertsValidUntil,
    )
    // TODO: should we keep the whole chain here (from iacaKey?)
    return AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert)), dsKey)
}

@OptIn(ExperimentalSerializationApi::class)
private suspend fun provisionTestDocuments(
    documentCreationMode: DocumentCreationMode,
    showProvisioningResult: MutableState<AnnotatedString?>,
    documentStore: DocumentStore,
    secureArea: SecureArea,
    secureAreaCreateKeySettingsFunc: (
        challenge: ByteString,
        algorithm: Algorithm,
        userAuthenticationRequired: Boolean,
        validFrom: Instant,
        validUntil: Instant
    ) -> CreateKeySettings,
    dsKey: AsymmetricKey.X509Certified,
    deviceKeyAlgorithm: Algorithm,
    deviceKeyMacAlgorithm: Algorithm,
    numCredentialsPerDomain: Int,
    showToast: (message: String) -> Unit,
    showDocumentCreationDialog: MutableState<Boolean>
) {
    // This can be slow... so we show a dialog to help convey this to the user.
    showDocumentCreationDialog.value = true

    if (documentCreationMode == DocumentCreationMode.NORMAL && documentStore.listDocumentIds().size >= 5) {
        // TODO: we need a more granular check once we support provisioning of other kinds of documents
        showToast("Test Documents already provisioned. Delete all documents and try again")
        showDocumentCreationDialog.value = false
        return
    }
    if (secureArea.supportedAlgorithms.find { it == deviceKeyAlgorithm } == null) {
        showToast("Secure Area doesn't support algorithm $deviceKeyAlgorithm for DeviceKey")
        showDocumentCreationDialog.value = false
        return
    }
    if (deviceKeyMacAlgorithm != Algorithm.UNSET &&
        secureArea.supportedAlgorithms.find { it == deviceKeyMacAlgorithm } == null) {
        showToast("Secure Area doesn't support algorithm $deviceKeyMacAlgorithm for DeviceKey for MAC")
        showDocumentCreationDialog.value = false
        return
    }
    try {
        val numDocsBegin = documentStore.listDocumentIds().size
        val timestampBegin = Clock.System.now()
        val openid4vciAttestationCompactSerialization = TestAppUtils.provisionTestDocuments(
            documentCreationMode,
            documentStore,
            secureArea,
            secureAreaCreateKeySettingsFunc,
            dsKey,
            deviceKeyAlgorithm,
            deviceKeyMacAlgorithm,
            numCredentialsPerDomain
        )
        val timestampEnd = Clock.System.now()
        val numDocsEnd = documentStore.listDocumentIds().size

        val provisioningResult = buildAnnotatedString {
            Logger.i(TAG, "Created ${numDocsEnd - numDocsBegin} document(s) in ${timestampEnd - timestampBegin}.")
            append("Created ${numDocsEnd - numDocsBegin} document(s) in ${timestampEnd - timestampBegin}.")
            if (openid4vciAttestationCompactSerialization != null) {
                val prettyAttestation = prettyJson.encodeToString(JsonWebSignature.getInfo
                    (openid4vciAttestationCompactSerialization).claimsSet)
                append("\n\nOpenID4VCI attestation:\n")
                append(prettyAttestation)
            }
        }
        showProvisioningResult.value = provisioningResult
    } catch (e: Throwable) {
        e.printStackTrace()
        showToast("Error provisioning documents: $e")
    }
    showDocumentCreationDialog.value = false
}

@OptIn(ExperimentalSerializationApi::class)
private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}