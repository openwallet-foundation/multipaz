package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import multipazproject.samples.testapp.generated.resources.Res
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.Simple
import org.multipaz.cbor.buildCborArray
import org.multipaz.certext.GoogleAccount
import org.multipaz.certext.MultipazExtension
import org.multipaz.certext.fromCbor
import org.multipaz.certext.toCbor
import org.multipaz.compose.document.DocumentModel
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509Extension
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.utopia.knowntypes.UtopiaBoardingPass
import org.multipaz.documenttype.knowntypes.addKnownTypes
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.utopia.knowntypes.addUtopiaTypes
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.ConsentData
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.requestConsent
import org.multipaz.request.Iso18013RequesterIdentity
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.truncateToWholeSeconds
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private enum class CertChain(
    val desc: String,
) {
    CERT_CHAIN_UTOPIA_BREWERY("Utopia Brewery (w/ privacy policy)"),
    CERT_CHAIN_UTOPIA_BREWERY_NO_PRIVACY_POLICY("Utopia Brewery (w/o privacy policy)"),
    CERT_CHAIN_UTOPIA_AIRLINES("Utopia Airlines"),
    CERT_CHAIN_IDENTITY_READER("Multipaz Identity Reader"),
    CERT_CHAIN_IDENTITY_READER_GOOGLE_ACCOUNT("Multipaz Identity Reader (w/ Google Account)"),
    CERT_CHAIN_NONE("None")
}

private enum class EncryptionTarget(
    val desc: String,
) {
    ENCRYPTION_TARGET_UTOPIA_CBP("Utopia Customs and Border Protection"),
    ENCRYPTION_TARGET_NONE("None")
}

private enum class Origin(
    val desc: String,
    val origin: String?
) {
    NONE("No Web Origin", null),
    VERIFIER_MULTIPAZ_ORG("verifier.multipaz.org", "https://verifier.multipaz.org"),
    OTHER_EXAMPLE_COM("other.example.com", "https://other.example.com"),
}

private enum class AppId(
    val desc: String,
    val appId: String?
) {
    NONE("No App", null),
    CHROME("Google Chrome", "com.android.chrome"),
    MESSAGES("Google Messages", "com.google.android.apps.messaging"),
}

private enum class Example(
    val desc: String,
) {
    MDL_US_TRANSPORTATION("mDL: US transportation"),
    MDL_AGE_OVER_21_AND_PORTRAIT("mDL: Age over 21 + portrait"),
    MDL_MANDATORY("mDL: Mandatory data elements"),
    MDL_ALL("mDL: All data elements"),
    MDL_NAME_AND_ADDRESS_PARTIALLY_STORED("mDL: Name and address (partially stored)"),
    MDL_NAME_AND_ADDRESS_ALL_STORED("mDL: Name and address (all stored)"),
    PHOTO_ID_MANDATORY("PhotoID: Mandatory data elements (2 docs)"),
    PAYMENT("Payment"),
    OPENID4VP_COMPLEX_EXAMPLE("Complex example from OpenID4VP Appendix D"),
    MDL_AND_BOARDING_PASS_EXAMPLE("mDL AND Boarding pass"),
    MDL_AND_OPTIONAL_BOARDING_PASS_EXAMPLE("mDL AND optional Boarding pass"),
    MDL_AND_OPTIONAL_BOARDING_PASS_SEPARATE_USE_CASES_EXAMPLE("mDL AND optional Boarding pass (separate use-cases)"),
    MDL_OR_BOARDING_PASS_EXAMPLE("mDL OR Boarding pass"),
    BORDER_CROSSING_EXAMPLE("Border crossing (photoID w/ encrypted request)"),
    BORDER_CROSSING_EXAMPLE_NO_RETAIN("Border crossing (photoID w/ encrypted request - no retain)"),
}

private enum class PaDuration(
    val desc: String,
    val duration: Duration
) {
    PA_DURATION_NONE("None", 0.seconds),
    PA_DURATION_2SEC("2 sec", 2.seconds),
    PA_DURATION_5SEC("5 sec", 5.seconds),
    PA_DURATION_30SEC("30 sec", 30.seconds)
}

private enum class PaPreselectedDocuments(
    val desc: String
) {
    PRESELECTED_DOCUMENTS_NONE("None"),
    PRESELECTED_DOCUMENTS_MDL("mDL"),
    PRESELECTED_DOCUMENTS_PHOTOID("PhotoID"),
    PRESELECTED_DOCUMENTS_BOARDING_PASS("Boarding pass"),
    PRESELECTED_DOCUMENTS_MDL_AND_PHOTOID("mDL and PhotoID"),
    PRESELECTED_DOCUMENTS_MDL_AND_PHOTOID_AND_PHOTOID("mDL and PhotoID and PhotoID"),
    PRESELECTED_DOCUMENTS_MDL_AND_BOARDING_PASS("mDL and boarding pass"),
    PRESELECTED_DOCUMENTS_MDL_AND_OPTIONAL_BOARDING_PASS("mDL and optional boarding pass")
}

data class AndroidPresentmentActivityData(
    val showConsent: Boolean = true,
    val requireAuth: Boolean = true,
    val authRequireConfirmation: Boolean = false,
    val connectionDuration: Duration = 0.seconds,
    val sendResponseDuration: Duration = 0.seconds,
    val preselectedDocuments: List<Document> = emptyList()
)

expect suspend fun launchAndroidPresentmentActivity(
    source: PresentmentSource,
    paData: AndroidPresentmentActivityData,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialSelection?

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentPromptScreen(
    secureAreaRepository: SecureAreaRepository,
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var example by remember { mutableStateOf(Example.MDL_US_TRANSPORTATION) }
    var certChain by remember { mutableStateOf(CertChain.entries.first()) }
    var encryptionTarget by remember { mutableStateOf(EncryptionTarget.entries.first()) }
    var origin by remember { mutableStateOf(Origin.entries.first()) }
    var appId by remember { mutableStateOf(AppId.entries.first()) }
    var cardArtMdl by remember { mutableStateOf(ByteArray(0)) }
    var cardArtPhotoId by remember { mutableStateOf(ByteArray(0)) }
    var cardArtBoardingPass by remember { mutableStateOf(ByteArray(0)) }
    var utopiaBreweryIcon by remember { mutableStateOf(ByteString()) }
    var utopiaAirlinesIcon by remember { mutableStateOf(ByteString()) }
    var utopiaCbpIcon by remember { mutableStateOf(ByteString()) }
    var identityReaderIcon by remember { mutableStateOf(ByteString()) }
    var documentStore by remember { mutableStateOf<DocumentStore?>(null) }
    var onDocumentsInFocus by remember { mutableStateOf<List<Document>?>(null) }
    var documentModel by remember { mutableStateOf<DocumentModel?>(null) }
    var paShowConsent by remember { mutableStateOf(true) }
    var paRequireAuth by remember { mutableStateOf(false) }
    var paAuthRequireConfirmation by remember { mutableStateOf(false) }
    var paConnectionDuration by remember { mutableStateOf(PaDuration.PA_DURATION_NONE) }
    var paSendingDuration by remember { mutableStateOf(PaDuration.PA_DURATION_NONE) }
    var paPreselectedDocuments by remember { mutableStateOf(PaPreselectedDocuments.PRESELECTED_DOCUMENTS_NONE)}
    lateinit var documentTypeRepository: DocumentTypeRepository
    lateinit var documentMdl: Document
    lateinit var documentPhotoId: Document
    lateinit var documentPhotoId2: Document
    lateinit var documentBoardingPass: Document

    LaunchedEffect(Unit) {
        cardArtMdl = Res.readBytes("files/utopia_driving_license_card_art.png")
        cardArtPhotoId = Res.readBytes("drawable/photo_id_card_art.png")
        cardArtBoardingPass = Res.readBytes("files/boarding-pass-utopia-airlines.png")
        utopiaBreweryIcon = ByteString(Res.readBytes("files/utopia-brewery.png"))
        utopiaAirlinesIcon = ByteString(Res.readBytes("files/utopia-airlines.png"))
        utopiaCbpIcon = ByteString(Res.readBytes("files/utopia-cbp.png"))
        identityReaderIcon = ByteString(Res.readBytes("drawable/app_icon.webp"))

        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        documentTypeRepository = DocumentTypeRepository()
        documentTypeRepository.addKnownTypes()
        documentTypeRepository.addUtopiaTypes()
        documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        documentModel = DocumentModel.create(documentStore = documentStore!!, documentTypeRepository = documentTypeRepository)

        val now = Clock.System.now().truncateToWholeSeconds()
        val iacaCertValidFrom = now - 1.days
        val iacaCertsValidUntil = iacaCertValidFrom + 455.days
        val iacaPrivateKey = Crypto.createEcPrivateKey(EcCurve.P521)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaPrivateKey),
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST IACA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = iacaCertValidFrom,
            validUntil = iacaCertsValidUntil,
            issuerAltNameUrl = "https://apps.multipaz.org/",
            crlUrl = "https://apps.multipaz.org/crl"
        )
        val iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaPrivateKey)

        // The DS cert must not be valid for more than 457 days.
        //
        // Reference: ISO/IEC 18013-5:2021 Annex B.1.4 Document signer certificate
        //
        val dsCertValidFrom = now - 1.days
        val dsCertsValidUntil = dsCertValidFrom + 455.days
        val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = iacaKey,
            dsKey = dsPrivateKey.publicKey,
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST DS"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = dsCertValidFrom,
            validUntil = dsCertsValidUntil,
        )
        val dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert, iacaCert)), dsPrivateKey)

        val credsValidFrom = now - 0.5.days
        val credsValidUntil = dsCertValidFrom + 30.days

        documentMdl = documentStore!!.createDocument(
            displayName = "Erika's driving license",
            typeDisplayName = "Utopia driving license",
            cardArt = ByteString(cardArtMdl)
        )
        DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = documentMdl,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = credsValidFrom,
            validFrom = credsValidFrom,
            validUntil = credsValidUntil,
            expectedUpdate = null,
            domain = "mdoc"
        )
        documentPhotoId = documentStore!!.createDocument(
            displayName = "Erika's PhotoID",
            typeDisplayName = "Utopia PhotoID",
            cardArt = ByteString(cardArtPhotoId)
        )
        PhotoID.getDocumentType().createMdocCredentialWithSampleData(
            document = documentPhotoId,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = credsValidFrom,
            validFrom = credsValidFrom,
            validUntil = credsValidUntil,
            expectedUpdate = null,
            domain = "mdoc"
        )
        documentPhotoId2 = documentStore!!.createDocument(
            displayName = "Erika's PhotoID #2",
            typeDisplayName = "Utopia PhotoID",
            cardArt = ByteString(cardArtPhotoId)
        )
        PhotoID.getDocumentType().createMdocCredentialWithSampleData(
            document = documentPhotoId2,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = credsValidFrom,
            validFrom = credsValidFrom,
            validUntil = credsValidUntil,
            expectedUpdate = null,
            domain = "mdoc"
        )
        documentBoardingPass = documentStore!!.createDocument(
            displayName = "Utopia 815 BOS to SFO",
            typeDisplayName = "Utopia Airlines boarding pass",
            cardArt = ByteString(cardArtBoardingPass)
        )
        UtopiaBoardingPass.getDocumentType().createMdocCredentialWithSampleData(
            document = documentBoardingPass,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = credsValidFrom,
            validFrom = credsValidFrom,
            validUntil = credsValidUntil,
            expectedUpdate = null,
            domain = "mdoc"
        )
        addCredentialsForOpenID4VPComplexExample(
            documentStore = documentStore!!,
            secureArea = secureArea,
            signedAt = credsValidFrom,
            validFrom = credsValidFrom,
            validUntil = credsValidUntil,
            dsKey = dsKey,
        )
    }

    LazyColumn(
        modifier = Modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            SettingMultipleChoice(
                title = "Content",
                choices = Example.entries.map { it.desc },
                initialChoice = Example.entries.first().desc,
                onChoiceSelected = { choice -> example = Example.entries.find { it.desc == choice }!! },
            )
        }

        item {
            SettingMultipleChoice(
                title = "TrustPoint",
                choices = CertChain.entries.map { it.desc },
                initialChoice = CertChain.entries.first().desc,
                onChoiceSelected = { choice -> certChain = CertChain.entries.find { it.desc == choice }!! },
            )
        }

        item {
            SettingMultipleChoice(
                title = "Encryption Target",
                choices = EncryptionTarget.entries.map { it.desc },
                initialChoice = EncryptionTarget.entries.first().desc,
                onChoiceSelected = { choice -> encryptionTarget = EncryptionTarget.entries.find { it.desc == choice }!! },
            )
        }

        item {
            SettingMultipleChoice(
                title = "Verifier Origin",
                choices = Origin.entries.map { it.desc },
                initialChoice = Origin.entries.first().desc,
                onChoiceSelected = { choice -> origin = Origin.entries.find { it.desc == choice }!! },
            )
        }

        item {
            SettingMultipleChoice(
                title = "Verifier App",
                choices = AppId.entries.map { it.desc },
                initialChoice = AppId.entries.first().desc,
                onChoiceSelected = { choice -> appId = AppId.entries.find { it.desc == choice }!! },
            )
        }

        fun launchConsent(launcher: suspend (
            source: PresentmentSource,
            paData: AndroidPresentmentActivityData,
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            consentData: ConsentData,
            preselectedDocuments: List<Document>,
            onDocumentsInFocus: (documents: List<Document>) -> Unit
            ) -> CredentialSelection?,
                          paData: AndroidPresentmentActivityData,
        ) {
            coroutineScope.launch {
                try {
                    val queryResult = getQueryResult(
                        example = example,
                        certChain = certChain,
                        encryptionTarget = encryptionTarget,
                        origin = origin,
                        appId = appId,
                        utopiaBreweryIcon = utopiaBreweryIcon,
                        utopiaAirlinesIcon = utopiaAirlinesIcon,
                        utopiaCbpIcon = utopiaCbpIcon,
                        identityReaderIcon = identityReaderIcon,
                        documentStore = documentStore,
                        documentTypeRepository = documentTypeRepository
                    )
                    val trustedRequesterIdentity =
                        queryResult.source.resolveTrust(queryResult.requester)
                    launcher(
                        queryResult.source,
                        paData,
                        queryResult.requester,
                        trustedRequesterIdentity,
                        queryResult.consentData,
                        emptyList(),
                        { documents ->
                            onDocumentsInFocus = documents
                        },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    e.printStackTrace()
                    showToast("Error evaluating query: $e")
                } finally {
                    onDocumentsInFocus = null
                }
            }
        }

        item {
            Button(onClick = {
                launchConsent(launcher = { source, paData, requester, trustedRequesterIdentity, consentData,
                                           preselectedDocuments, onDocumentsInFocus ->
                        promptModel.requestConsent(
                            requester = requester,
                            trustedRequesterIdentity = trustedRequesterIdentity,
                            consentData = consentData,
                            preselectedDocuments = preselectedDocuments,
                            onDocumentsInFocus = onDocumentsInFocus,
                        )
                    },
                    paData = AndroidPresentmentActivityData()
                )}) {
                Text("Show Consent Prompt")
            }
        }

        // Draw currently selected documents from consent prompt.
        //
        if (onDocumentsInFocus != null) {
            onDocumentsInFocus?.forEach { document ->
                documentModel?.documentInfos?.value?.find {
                    documentInfo -> documentInfo.document.identifier == document.identifier
                }?.let { documentInfo ->
                    item {
                        Image(
                            modifier = Modifier.size(100.dp),
                            bitmap = documentInfo.cardArt,
                            contentDescription = null,
                            contentScale = ContentScale.Fit
                        )
                    }
                    item {
                        Text("Document: ${documentInfo.document.displayName}")
                    }
                }
            }
        }

        item {
            HorizontalDivider()
        }

        item {
            Text(
                "The following simulates the consent prompt with the settings from above " +
                        "in PresentmentActivity which is used for proximity presentment using QR and NFC on Android"
            )
        }

        item {
            SettingToggle(
                title = "Show consent prompt",
                isChecked = paShowConsent,
                onCheckedChange = { newValue ->
                    paShowConsent = newValue
                }
            )
        }

        item {
            SettingToggle(
                title = "Require authentication",
                isChecked = paRequireAuth,
                onCheckedChange = { newValue ->
                    paRequireAuth = newValue
                }
            )
        }

        item {
            SettingToggle(
                title = "Require confirmation for auth",
                isChecked = paAuthRequireConfirmation,
                onCheckedChange = { newValue ->
                    paAuthRequireConfirmation = newValue
                }
            )
        }

        item {
            SettingMultipleChoice(
                title = "Connection time",
                choices = PaDuration.entries.map { it.desc },
                initialChoice = paConnectionDuration.desc,
                onChoiceSelected = { choice -> paConnectionDuration = PaDuration.entries.find { it.desc == choice }!! },
            )
        }

        item {
            SettingMultipleChoice(
                title = "Time to send response",
                choices = PaDuration.entries.map { it.desc },
                initialChoice = paSendingDuration.desc,
                onChoiceSelected = { choice -> paSendingDuration = PaDuration.entries.find { it.desc == choice }!! },
            )
        }

        item {
            SettingMultipleChoice(
                title = "Preselected documents",
                choices = PaPreselectedDocuments.entries.map { it.desc },
                initialChoice = paPreselectedDocuments.desc,
                onChoiceSelected = { choice -> paPreselectedDocuments = PaPreselectedDocuments.entries.find { it.desc == choice }!! },
            )
        }

        item {
            Button(onClick = { launchConsent(
                launcher = ::launchAndroidPresentmentActivity,
                paData = AndroidPresentmentActivityData(
                    showConsent = paShowConsent,
                    requireAuth = paRequireAuth,
                    authRequireConfirmation = paAuthRequireConfirmation,
                    connectionDuration = paConnectionDuration.duration,
                    sendResponseDuration = paSendingDuration.duration,
                    preselectedDocuments = when (paPreselectedDocuments) {
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_NONE -> listOf()
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_MDL -> listOf(documentMdl)
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_PHOTOID -> listOf(documentPhotoId)
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_BOARDING_PASS -> listOf(documentBoardingPass)
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_MDL_AND_PHOTOID -> listOf(documentMdl, documentPhotoId)
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_MDL_AND_PHOTOID_AND_PHOTOID ->
                            listOf(documentMdl, documentPhotoId, documentPhotoId2)
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_MDL_AND_BOARDING_PASS ->
                            listOf(documentMdl, documentBoardingPass)
                        PaPreselectedDocuments.PRESELECTED_DOCUMENTS_MDL_AND_OPTIONAL_BOARDING_PASS->
                            listOf(documentMdl, documentBoardingPass)
                    }
                ),
            ) }) {
                Text("Show in PresentmentActivity")
            }
        }
    }
}

private data class QueryResult(
    val requester: Requester,
    val source: PresentmentSource,
    val consentData: ConsentData
)

private suspend fun getQueryResult(
    example: Example,
    certChain: CertChain,
    encryptionTarget: EncryptionTarget,
    origin: Origin,
    appId: AppId,
    utopiaBreweryIcon: ByteString,
    utopiaAirlinesIcon: ByteString,
    utopiaCbpIcon: ByteString,
    identityReaderIcon: ByteString,
    documentStore: DocumentStore?,
    documentTypeRepository: DocumentTypeRepository
): QueryResult {
    val dcql = when (example) {
        Example.MDL_AGE_OVER_21_AND_PORTRAIT ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "age_over_21_and_portrait" }!!.mdocRequest!!.toDcql(emptyList())
        Example.MDL_US_TRANSPORTATION ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "us-transportation" }!!.mdocRequest!!.toDcql(emptyList())
        Example.MDL_MANDATORY ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "mandatory" }!!.mdocRequest!!.toDcql(emptyList())
        Example.MDL_ALL ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "full" }!!.mdocRequest!!.toDcql(emptyList())
        Example.MDL_NAME_AND_ADDRESS_PARTIALLY_STORED ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "name-and-address-partially-stored" }!!.mdocRequest!!.toDcql(emptyList())
        Example.MDL_NAME_AND_ADDRESS_ALL_STORED ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "name-and-address-all-stored" }!!.mdocRequest!!.toDcql(emptyList())
        Example.PHOTO_ID_MANDATORY ->
            PhotoID.getDocumentType().cannedRequests.find { it.id == "mandatory" }!!.mdocRequest!!.toDcql(emptyList())
        Example.PAYMENT ->
            DigitalPaymentCredential.getDocumentType().cannedRequests.find { it.id == "payment_transaction" }!!.mdocRequest!!.toDcql(emptyList())
        Example.OPENID4VP_COMPLEX_EXAMPLE -> Json.parseToJsonElement(
            """
            {
              "credentials": [
                {
                  "id": "pid",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://credentials.example.com/identity_credential"]
                  },
                  "claims": [
                    {"path": ["given_name"]},
                    {"path": ["family_name"]},
                    {"path": ["address", "street_address"]}
                  ]
                },
                {
                  "id": "other_pid",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://othercredentials.example/pid"]
                  },
                  "claims": [
                    {"path": ["given_name"]},
                    {"path": ["family_name"]},
                    {"path": ["address", "street_address"]}
                  ]
                },
                {
                  "id": "pid_reduced_cred_1",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://credentials.example.com/reduced_identity_credential"]
                  },
                  "claims": [
                    {"path": ["family_name"]},
                    {"path": ["given_name"]}
                  ]
                },
                {
                  "id": "pid_reduced_cred_2",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://cred.example/residence_credential"]
                  },
                  "claims": [
                    {"path": ["postal_code"]},
                    {"path": ["locality"]},
                    {"path": ["region"]}
                  ]
                },
                {
                  "id": "nice_to_have",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://company.example/company_rewards"]
                  },
                  "claims": [
                    {"path": ["rewards_number"]}
                  ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "pid" ],
                    [ "other_pid" ],
                    [ "pid_reduced_cred_1", "pid_reduced_cred_2" ]
                  ]
                },
                {
                  "required": false,
                  "options": [
                    [ "nice_to_have" ]
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        Example.MDL_AND_BOARDING_PASS_EXAMPLE -> Json.parseToJsonElement(
            """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "family_name" ] },
                    { "path": ["org.iso.18013.5.1", "given_name" ] },
                    { "path": ["org.iso.18013.5.1", "birth_date" ] },
                    { "path": ["org.iso.18013.5.1", "issue_date" ] },
                    { "path": ["org.iso.18013.5.1", "expiry_date" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_country" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_authority" ] },
                    { "path": ["org.iso.18013.5.1", "document_number" ] },
                    { "path": ["org.iso.18013.5.1", "portrait" ] },
                    { "path": ["org.iso.18013.5.1", "un_distinguishing_sign" ] }
                  ]
                },
                {
                  "id": "boarding-pass",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.multipaz.example.boarding-pass.1"
                  },
                  "claims": [
                    { "path": ["org.multipaz.example.boarding-pass.1", "passenger_name" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "seat_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "flight_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "departure_time" ] }
                  ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "mdl", "boarding-pass" ]
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        Example.MDL_AND_OPTIONAL_BOARDING_PASS_EXAMPLE -> Json.parseToJsonElement(
            """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "family_name" ] },
                    { "path": ["org.iso.18013.5.1", "given_name" ] },
                    { "path": ["org.iso.18013.5.1", "birth_date" ] },
                    { "path": ["org.iso.18013.5.1", "issue_date" ] },
                    { "path": ["org.iso.18013.5.1", "expiry_date" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_country" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_authority" ] },
                    { "path": ["org.iso.18013.5.1", "document_number" ] },
                    { "path": ["org.iso.18013.5.1", "portrait" ] },
                    { "path": ["org.iso.18013.5.1", "un_distinguishing_sign" ] }
                  ]
                },
                {
                  "id": "boarding-pass",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.multipaz.example.boarding-pass.1"
                  },
                  "claims": [
                    { "path": ["org.multipaz.example.boarding-pass.1", "passenger_name" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "seat_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "flight_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "departure_time" ] }
                  ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "mdl", "boarding-pass" ],
                    [ "mdl" ]
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        Example.MDL_AND_OPTIONAL_BOARDING_PASS_SEPARATE_USE_CASES_EXAMPLE -> Json.parseToJsonElement(
            """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "family_name" ] },
                    { "path": ["org.iso.18013.5.1", "given_name" ] },
                    { "path": ["org.iso.18013.5.1", "birth_date" ] },
                    { "path": ["org.iso.18013.5.1", "issue_date" ] },
                    { "path": ["org.iso.18013.5.1", "expiry_date" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_country" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_authority" ] },
                    { "path": ["org.iso.18013.5.1", "document_number" ] },
                    { "path": ["org.iso.18013.5.1", "portrait" ] },
                    { "path": ["org.iso.18013.5.1", "un_distinguishing_sign" ] }
                  ]
                },
                {
                  "id": "boarding-pass",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.multipaz.example.boarding-pass.1"
                  },
                  "claims": [
                    { "path": ["org.multipaz.example.boarding-pass.1", "passenger_name" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "seat_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "flight_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "departure_time" ] }
                  ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "mdl" ]
                  ]
                },
                {
                  "required": false,
                  "options": [
                    [ "boarding-pass" ]
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        Example.MDL_OR_BOARDING_PASS_EXAMPLE -> Json.parseToJsonElement(
                """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "family_name" ] },
                    { "path": ["org.iso.18013.5.1", "given_name" ] },
                    { "path": ["org.iso.18013.5.1", "birth_date" ] },
                    { "path": ["org.iso.18013.5.1", "issue_date" ] },
                    { "path": ["org.iso.18013.5.1", "expiry_date" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_country" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_authority" ] },
                    { "path": ["org.iso.18013.5.1", "document_number" ] },
                    { "path": ["org.iso.18013.5.1", "portrait" ] },
                    { "path": ["org.iso.18013.5.1", "un_distinguishing_sign" ] }
                  ]
                },
                {
                  "id": "boarding-pass",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.multipaz.example.boarding-pass.1"
                  },
                  "claims": [
                    { "path": ["org.multipaz.example.boarding-pass.1", "passenger_name" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "seat_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "flight_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "departure_time" ] }
                  ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "mdl" ],
                    [ "boarding-pass" ]
                  ]
                }
              ]
            }
            """.trimIndent()
            ).jsonObject
        Example.BORDER_CROSSING_EXAMPLE,
        Example.BORDER_CROSSING_EXAMPLE_NO_RETAIN -> null
    }
    val (requester, trustMetadata) = calculateRequester(
        certChain = certChain,
        origin = origin,
        appId = appId,
        utopiaBreweryIcon = utopiaBreweryIcon,
        utopiaAirlinesIcon = utopiaAirlinesIcon,
        identityReaderIcon = identityReaderIcon
    )
    val source = SimplePresentmentSource(
        documentStore = documentStore!!,
        documentTypeRepository = documentTypeRepository,
        resolveTrustFn = { requester ->
            for (requesterIdentity in requester.requesterIdentities) {
                val trustMetadata = resolveTrust(
                    encryptionTarget,
                    utopiaCbpIcon,
                    requesterIdentity
                ) ?: continue
                return@SimplePresentmentSource TrustedRequesterIdentity(requesterIdentity, trustMetadata)
            }
            // Otherwise, just base it on the trustMetadata
            trustMetadata?.let {
                TrustedRequesterIdentity(requester.requesterIdentities.first(), it)
            }
        },
        domainsMdocSignature = listOf("mdoc"),
        domainsKeyBoundSdJwt = listOf("sdjwt")
    )

    if (dcql != null) {
        val dcqlQuery = DcqlQuery.fromJson(dcql = dcql)
        val transactionDataMap = when (example) {
            Example.PAYMENT -> DigitalPaymentCredential.getDocumentType()
                .cannedRequests.find { it.id == "payment_transaction" }!!
                .toTransactionDataMap("cred1")

            else -> emptyMap()
        }
        val dcqlResponse = dcqlQuery.execute(
            presentmentSource = source,
            transactionDataMap = transactionDataMap
        )
        val consentData = ConsentData.fromCredentialQueryResult(
            credentialQueryResult = dcqlResponse,
            source = source
        )
        return QueryResult(requester, source, consentData)
    }

    val deviceRequest = when (example) {
        Example.MDL_US_TRANSPORTATION,
        Example.MDL_AGE_OVER_21_AND_PORTRAIT,
        Example.MDL_MANDATORY,
        Example.MDL_ALL,
        Example.MDL_NAME_AND_ADDRESS_PARTIALLY_STORED,
        Example.MDL_NAME_AND_ADDRESS_ALL_STORED,
        Example.PHOTO_ID_MANDATORY,
        Example.PAYMENT,
        Example.OPENID4VP_COMPLEX_EXAMPLE,
        Example.MDL_AND_BOARDING_PASS_EXAMPLE,
        Example.MDL_AND_OPTIONAL_BOARDING_PASS_EXAMPLE,
        Example.MDL_AND_OPTIONAL_BOARDING_PASS_SEPARATE_USE_CASES_EXAMPLE,
        Example.MDL_OR_BOARDING_PASS_EXAMPLE -> {
            throw IllegalStateException("Already covered by DCQL")
        }
        Example.BORDER_CROSSING_EXAMPLE,
        Example.BORDER_CROSSING_EXAMPLE_NO_RETAIN -> {
            val intentToRetain = example != Example.BORDER_CROSSING_EXAMPLE_NO_RETAIN
            val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
            val documentEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val now = Clock.System.now()
            val documentEncryptionKeyCertification = buildX509Cert(
                publicKey = documentEncryptionKey.publicKey,
                signingKey = AsymmetricKey.anonymous(documentEncryptionKey, documentEncryptionKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Encrypted Document Receiver"),
                issuer = X500Name.fromName("CN=Encrypted Document Receiver"),
                validFrom = (now - 1.hours).truncateToWholeSeconds(),
                validUntil = (now + 1.hours).truncateToWholeSeconds()
            ) {
                includeSubjectKeyIdentifier()
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                setBasicConstraints(true, null)
            }

            buildDeviceRequest(
                sessionTranscript = sessionTranscript,
            ) {
                addDocRequest(
                    docType = PhotoID.PHOTO_ID_DOCTYPE,
                    nameSpaces = mapOf(
                        PhotoID.ISO_23220_2_NAMESPACE to mapOf(
                            "given_name" to intentToRetain,
                            "family_name" to intentToRetain,
                            "portrait" to intentToRetain
                        )
                    ),
                )
                addDocRequest(
                    docType = PhotoID.PHOTO_ID_DOCTYPE,
                    nameSpaces = mapOf(
                        PhotoID.DATAGROUPS_NAMESPACE to mapOf(
                            "sod" to intentToRetain,
                            "dg1" to intentToRetain,
                            "dg2" to intentToRetain,
                        )
                    ),
                    docRequestInfo = DocRequestInfo(
                        docResponseEncryption = EncryptionParameters.fromValues(
                            recipientPublicKey = documentEncryptionKey.publicKey,
                            recipientCertificates = listOf(documentEncryptionKeyCertification)
                        )
                    ),
                )
                setDeviceRequestInfo(DeviceRequestInfo.fromValues(
                    useCases = listOf(UseCase(
                        mandatory = true,
                        documentSets = listOf(
                            DocumentSet(docRequestIds = listOf(0, 1))
                        ),
                        purposeHints = emptyMap()
                    ))
                ))
            }
        }
    }
    val iso18013Response = deviceRequest.execute(
        presentmentSource = source,
        keyAgreementPossible = emptyList()
    )
    val consentData = ConsentData.fromCredentialQueryResult(
        credentialQueryResult = iso18013Response,
        source = source
    )
    return QueryResult(requester, source, consentData)
}

private fun resolveTrust(
    encryptionTarget: EncryptionTarget,
    utopiaCbpIcon: ByteString,
    requesterIdentity: RequesterIdentity
): TrustMetadata? {
    if (requesterIdentity.certChain.certificates.first().subject.name == "CN=Encrypted Document Receiver") {
        return if (encryptionTarget.desc == "None") {
            null
        } else {
            TrustMetadata(
                displayName = encryptionTarget.desc,
                displayIcon = utopiaCbpIcon,     // For now, assume this is the only encryption target
            )
        }
    }

    // If available, use dynamic metadata...
    val readerCert = requesterIdentity.certChain.certificates.first()
    val mpzExtensionData = readerCert.getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)
    if (mpzExtensionData != null) {
        val mpzExtension = MultipazExtension.fromCbor(mpzExtensionData)
        mpzExtension.googleAccount?.let {
            return TrustMetadata(
                displayName = it.emailAddress,
                displayIconUrl = it.profilePictureUri,
                disclaimer = "The email and picture shown are from the requester's Google Account. " +
                        "This information has been verified but may not be their real identity",
            )
        }
    }

    return null
}

private suspend fun calculateRequester(
    certChain: CertChain,
    origin: Origin,
    appId: AppId,
    utopiaBreweryIcon: ByteString,
    utopiaAirlinesIcon: ByteString,
    identityReaderIcon: ByteString
): Pair<Requester, TrustMetadata?> {
    val now = Clock.System.now().truncateToWholeSeconds()
    val validFrom = now - 1.days
    val validUntil = now + 1.days
    val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
    val readerRootCert = MdocUtil.generateReaderRootCertificate(
        readerRootKey = AsymmetricKey.anonymous(readerRootKey),
        subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST Reader Root"),
        serial = ASN1Integer.fromRandom(128),
        validFrom = validFrom,
        validUntil = validUntil,
        crlUrl = "https://verifier.multipaz.org/crl"
    )
    val readerRootSigningKey = AsymmetricKey.X509CertifiedExplicit(
        certChain = X509CertChain(listOf(readerRootCert)),
        privateKey = readerRootKey
    )
    val readerKey = Crypto.createEcPrivateKey(EcCurve.P256)
    val readerCertWithoutGoogleAccount = MdocUtil.generateReaderCertificate(
        readerRootKey = readerRootSigningKey,
        readerKey =readerKey.publicKey,
        subject = X500Name.fromName("CN=Multipaz Reader Single-Use key"),
        dnsName = null,
        serial = ASN1Integer.fromRandom(128),
        validFrom = validFrom,
        validUntil = validUntil
    )
    val readerCertWithGoogleAccount = MdocUtil.generateReaderCertificate(
        readerRootKey = readerRootSigningKey,
        readerKey = readerKey.publicKey,
        subject = X500Name.fromName("CN=Multipaz Reader Single-Use key"),
        dnsName = null,
        serial = ASN1Integer.fromRandom(128),
        validFrom = validFrom,
        validUntil = validUntil,
        extensions = listOf(X509Extension(
            oid = OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid,
            isCritical = false,
            data = ByteString(MultipazExtension(
                googleAccount = GoogleAccount(
                    id = "1234",
                    emailAddress = "example@gmail.com",
                    displayName = "Example Google Account",
                    profilePictureUri = "https://lh3.googleusercontent.com/a/ACg8ocI0A6iHTOJdLsEeVq929dWnJ617_ggBn6PdnP4DgcCR4eK5uu4A=s160-p-k-rw-no"
                )
            ).toCbor())
        ))
    )

    val (trustMetadata, readerCert) = when (certChain) {
        CertChain.CERT_CHAIN_UTOPIA_BREWERY -> {
            Pair(
                TrustMetadata(
                    displayName = "Utopia Brewery",
                    displayIcon = utopiaBreweryIcon,
                    privacyPolicyUrl = "https://apps.multipaz.org",
                ),
                readerCertWithoutGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_UTOPIA_BREWERY_NO_PRIVACY_POLICY -> {
            Pair(
            TrustMetadata(
                    displayName = "Utopia Brewery",
                    displayIcon = utopiaBreweryIcon,
                    privacyPolicyUrl = null,
                ),
                readerCertWithoutGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_UTOPIA_AIRLINES -> {
            Pair(
                TrustMetadata(
                    displayName = "Utopia Airlines",
                    displayIcon = utopiaAirlinesIcon,
                    privacyPolicyUrl = "https://apps.multipaz.org",
                ),
                readerCertWithoutGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_IDENTITY_READER ->  {
            Pair(
            TrustMetadata(
                    displayName = "Multipaz Identity Reader",
                    displayIcon = identityReaderIcon,
                    privacyPolicyUrl = "https://apps.multipaz.org",
                ),
                readerCertWithoutGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_IDENTITY_READER_GOOGLE_ACCOUNT -> {
            Pair(
            TrustMetadata(
                    displayName = "Multipaz Identity Reader",
                    displayIcon = identityReaderIcon,
                    privacyPolicyUrl = "https://apps.multipaz.org",
                ),
                readerCertWithGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_NONE -> Pair(null, null)
    }

    return Pair(
        Requester(
            requesterIdentities = buildList {
                readerCert?.let {
                    add(Iso18013RequesterIdentity(
                        certChain = X509CertChain(certificates = listOf(readerCert, readerRootCert))
                    ))
                }
            },
            appId = appId.appId,
            origin = origin.origin
        ),
        trustMetadata
    )
}

private suspend fun addCredentialsForOpenID4VPComplexExample(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    addCredPid(
        documentStore = documentStore,
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
    addCredPidMax(
        documentStore = documentStore,
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
    addCredOtherPid(
        documentStore = documentStore,
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
    addCredPidReduced1(
        documentStore = documentStore,
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
    addCredPidReduced2(
        documentStore = documentStore,
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
    addCredCompanyRewards(
        documentStore = documentStore,
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
    addCredCompanyRewards2(
        documentStore = documentStore,
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun addCredPid(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    documentStore.provisionSdJwtVc(
        displayName = "my-pid",
        vct = "https://credentials.example.com/identity_credential",
        data = listOf(
            "given_name" to JsonPrimitive("Erika"),
            "family_name" to JsonPrimitive("Mustermann"),
            "address" to buildJsonObject {
                put("street_address", JsonPrimitive("Sample Street 123"))
            }
        ),
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun addCredPidMax(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    documentStore.provisionSdJwtVc(
        displayName = "my-pid-max",
        vct = "https://credentials.example.com/identity_credential",
        data = listOf(
            "given_name" to JsonPrimitive("Max"),
            "family_name" to JsonPrimitive("Mustermann"),
            "address" to buildJsonObject {
                put("street_address", JsonPrimitive("Sample Street 456"))
            }
        ),
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun addCredOtherPid(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    documentStore.provisionSdJwtVc(
        displayName = "my-other-pid",
        vct = "https://othercredentials.example/pid",
        data = listOf(
            "given_name" to JsonPrimitive("Erika"),
            "family_name" to JsonPrimitive("Mustermann"),
            "address" to buildJsonObject {
                put("street_address", JsonPrimitive("Sample Street 123"))
            }
        ),
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun addCredPidReduced1(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    documentStore.provisionSdJwtVc(
        displayName = "my-pid-reduced1",
        vct = "https://credentials.example.com/reduced_identity_credential",
        data = listOf(
            "given_name" to JsonPrimitive("Erika"),
            "family_name" to JsonPrimitive("Mustermann"),
        ),
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun addCredPidReduced2(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    documentStore.provisionSdJwtVc(
        displayName = "my-pid-reduced2",
        vct = "https://cred.example/residence_credential",
        data = listOf(
            "postal_code" to JsonPrimitive(90210),
            "locality" to JsonPrimitive("Beverly Hills"),
            "region" to JsonPrimitive("Los Angeles Basin"),
        ),
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun addCredCompanyRewards(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    documentStore.provisionSdJwtVc(
        displayName = "my-reward-card",
        vct = "https://company.example/company_rewards",
        data = listOf(
            "rewards_number" to JsonPrimitive(24601),
        ),
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun addCredCompanyRewards2(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
) {
    documentStore.provisionSdJwtVc(
        displayName = "my-other-reward-card",
        vct = "https://company.example/company_rewards",
        data = listOf(
            "rewards_number" to JsonPrimitive(42),
        ),
        secureArea = secureArea,
        signedAt = signedAt,
        validFrom = validFrom,
        validUntil = validUntil,
        dsKey = dsKey,
    )
}

private suspend fun DocumentStore.provisionSdJwtVc(
    displayName: String,
    vct: String,
    data: List<Pair<String, JsonElement>>,
    secureArea: SecureArea,
    signedAt: Instant,
    validFrom: Instant,
    validUntil: Instant,
    dsKey: AsymmetricKey,
): Document {
    val document = createDocument(
        displayName = displayName,
        typeDisplayName = vct
    )
    val identityAttributes = buildJsonObject {
        for ((claimName, claimValue) in data) {
            put(claimName, claimValue)
        }
    }

    val credential = KeyBoundSdJwtVcCredential.create(
        document = document,
        asReplacementForIdentifier = null,
        domain = "sdjwt",
        secureArea = secureArea,
        vct = vct,
        createKeySettings = SoftwareCreateKeySettings.Builder().build()
    )

    val sdJwt = SdJwt.create(
        issuerKey = dsKey,
        kbKey = (credential as? SecureAreaBoundCredential)?.let { it.secureArea.getKeyInfo(it.alias).publicKey },
        claims = identityAttributes,
        nonSdClaims = buildJsonObject {
            put("iss", "https://example-issuer.com")
            put("vct", credential.vct)
            put("iat", signedAt.epochSeconds)
            put("nbf", validFrom.epochSeconds)
            put("exp", validUntil.epochSeconds)
        },
    )
    credential.certify(sdJwt.compactSerialization.encodeToByteString())
    return document
}
