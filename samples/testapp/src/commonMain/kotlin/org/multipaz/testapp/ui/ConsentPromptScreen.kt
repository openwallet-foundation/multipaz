package org.multipaz.testapp.ui

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import multipazproject.samples.testapp.generated.resources.Res
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
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
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentCannedRequest
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.documenttype.knowntypes.UtopiaBoardingPass
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.openid.dcql.DcqlResponse
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.presentment.model.SimplePresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.requestConsent
import org.multipaz.request.Requester
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
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private enum class CertChain(
    val desc: String,
) {
    CERT_CHAIN_UTOPIA_BREWERY("Utopia Brewery (w/ privacy policy)"),
    CERT_CHAIN_UTOPIA_BREWERY_NO_PRIVACY_POLICY("Utopia Brewery (w/o privacy policy)"),
    CERT_CHAIN_IDENTITY_READER("Multipaz Identity Reader"),
    CERT_CHAIN_IDENTITY_READER_GOOGLE_ACCOUNT("Multipaz Identity Reader (w/ Google Account)"),
    CERT_CHAIN_NONE("None")
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

private enum class UseCase(
    val desc: String,
) {
    MDL_US_TRANSPORTATION("mDL: US transportation"),
    MDL_AGE_OVER_21_AND_PORTRAIT("mDL: Age over 21 + portrait"),
    MDL_MANDATORY("mDL: Mandatory data elements"),
    MDL_ALL("mDL: All data elements"),
    MDL_NAME_AND_ADDRESS_PARTIALLY_STORED("mDL: Name and address (partially stored)"),
    MDL_NAME_AND_ADDRESS_ALL_STORED("mDL: Name and address (all stored)"),
    PHOTO_ID_MANDATORY("PhotoID: Mandatory data elements (2 docs)"),
    OPENID4VP_COMPLEX_EXAMPLE("Complex example from OpenID4VP Appendix D"),
    BOARDING_PASS_AND_MDL_EXAMPLE("Boarding pass AND mDL"),
    BOARDING_PASS_OR_MDL_EXAMPLE("Boarding pass OR mDL")
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
    PRESELECTED_DOCUMENTS_MDL_AND_BOARDING_PASS("mDL and boarding pass")
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
    trustMetadata: TrustMetadata?,
    credentialPresentmentData: CredentialPresentmentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialPresentmentSelection?

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentPromptScreen(
    secureAreaRepository: SecureAreaRepository,
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var useCase by remember { mutableStateOf(UseCase.MDL_US_TRANSPORTATION) }
    var certChain by remember { mutableStateOf(CertChain.entries.first()) }
    var origin by remember { mutableStateOf(Origin.entries.first()) }
    var appId by remember { mutableStateOf(AppId.entries.first()) }
    var cardArtMdl by remember { mutableStateOf(ByteArray(0)) }
    var cardArtPhotoId by remember { mutableStateOf(ByteArray(0)) }
    var cardArtBoardingPass by remember { mutableStateOf(ByteArray(0)) }
    var utopiaBreweryIcon by remember { mutableStateOf(ByteString()) }
    var identityReaderIcon by remember { mutableStateOf(ByteString()) }
    var documentStore by remember { mutableStateOf<DocumentStore?>(null) }
    var onDocumentsInFocus by remember { mutableStateOf<List<Document>?>(null) }
    var documentModel by remember { mutableStateOf<DocumentModel?>(null) }
    var paShowConsent by remember { mutableStateOf(true) }
    var paRequireAuth by remember { mutableStateOf(true) }
    var paAuthRequireConfirmation by remember { mutableStateOf(false) }
    var paConnectionDuration by remember { mutableStateOf(PaDuration.PA_DURATION_2SEC) }
    var paSendingDuration by remember { mutableStateOf(PaDuration.PA_DURATION_2SEC) }
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
        identityReaderIcon = ByteString(Res.readBytes("drawable/app_icon.webp"))

        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        documentTypeRepository = DocumentTypeRepository()
        documentTypeRepository.addDocumentType(DrivingLicense.getDocumentType())
        documentTypeRepository.addDocumentType(PhotoID.getDocumentType())
        documentTypeRepository.addDocumentType(UtopiaBoardingPass.getDocumentType())
        documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        documentModel = DocumentModel(documentStore = documentStore!!, documentTypeRepository = documentTypeRepository)

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
                choices = UseCase.entries.map { it.desc },
                initialChoice = UseCase.entries.first().desc,
                onChoiceSelected = { choice -> useCase = UseCase.entries.find { it.desc == choice }!! },
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
                trustMetadata: TrustMetadata?,
                credentialPresentmentData: CredentialPresentmentData,
                preselectedDocuments: List<Document>,
                onDocumentsInFocus: (documents: List<Document>) -> Unit
            ) -> CredentialPresentmentSelection?,
                          paData: AndroidPresentmentActivityData,
        ) {
            coroutineScope.launch {
                try {
                    val queryResult = getQueryResult(
                        useCase = useCase,
                        certChain = certChain,
                        origin = origin,
                        appId = appId,
                        utopiaBreweryIcon = utopiaBreweryIcon,
                        identityReaderIcon = identityReaderIcon,
                        documentStore = documentStore,
                        documentTypeRepository = documentTypeRepository
                    )
                    launcher(
                        queryResult.source,
                        paData,
                        queryResult.requester,
                        queryResult.source.resolveTrust(queryResult.requester),
                        queryResult.dcqlResponse,
                        emptyList(),
                        { documents ->
                            onDocumentsInFocus = documents
                        },
                    )
                } catch (e: Throwable) {
                    e.printStackTrace()
                    showToast("Error evaluating query: $e")
                } finally {
                    onDocumentsInFocus = null
                }
            }
        }

        item {
            Button(onClick = {
                launchConsent(launcher = { source, paData,
                                           requester, trustMetadata, credentialPresentmentData, preselectedDocuments, onDocumentsInFocus ->
                        promptModel.requestConsent(
                            requester = requester,
                            trustMetadata = trustMetadata,
                            credentialPresentmentData = credentialPresentmentData,
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
    val dcqlResponse: DcqlResponse
)

private suspend fun getQueryResult(
    useCase: UseCase,
    certChain: CertChain,
    origin: Origin,
    appId: AppId,
    utopiaBreweryIcon: ByteString,
    identityReaderIcon: ByteString,
    documentStore: DocumentStore?,
    documentTypeRepository: DocumentTypeRepository
): QueryResult {
    val dcql = when (useCase) {
        UseCase.MDL_AGE_OVER_21_AND_PORTRAIT ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "age_over_21_and_portrait" }!!.mdocRequest!!.toDcql()
        UseCase.MDL_US_TRANSPORTATION ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "us-transportation" }!!.mdocRequest!!.toDcql()
        UseCase.MDL_MANDATORY ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "mandatory" }!!.mdocRequest!!.toDcql()
        UseCase.MDL_ALL ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "full" }!!.mdocRequest!!.toDcql()
        UseCase.MDL_NAME_AND_ADDRESS_PARTIALLY_STORED ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "name-and-address-partially-stored" }!!.mdocRequest!!.toDcql()
        UseCase.MDL_NAME_AND_ADDRESS_ALL_STORED ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "name-and-address-all-stored" }!!.mdocRequest!!.toDcql()
        UseCase.PHOTO_ID_MANDATORY ->
            PhotoID.getDocumentType().cannedRequests.find { it.id == "mandatory" }!!.mdocRequest!!.toDcql()
        UseCase.OPENID4VP_COMPLEX_EXAMPLE -> Json.parseToJsonElement(
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
        UseCase.BOARDING_PASS_AND_MDL_EXAMPLE -> Json.parseToJsonElement(
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
              ]
            }
            """.trimIndent()
        ).jsonObject
        UseCase.BOARDING_PASS_OR_MDL_EXAMPLE -> Json.parseToJsonElement(
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
    }
    val (requester, trustMetadata) = calculateRequester(
        certChain = certChain,
        origin = origin,
        appId = appId,
        utopiaBreweryIcon = utopiaBreweryIcon,
        identityReaderIcon = identityReaderIcon
    )
    val source = SimplePresentmentSource(
        documentStore = documentStore!!,
        documentTypeRepository = documentTypeRepository,
        resolveTrustFn = { requester ->
            // If available, use dynamic metadata...
            val readerCert = requester.certChain?.certificates?.first()
            val mpzExtensionData = readerCert?.getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)
            if (mpzExtensionData != null) {
                val mpzExtension = MultipazExtension.fromCbor(mpzExtensionData)
                mpzExtension.googleAccount?.let {
                    return@SimplePresentmentSource TrustMetadata(
                        displayName = it.emailAddress,
                        displayIconUrl = it.profilePictureUri,
                        disclaimer = "The email and picture shown are from the requester's Google Account. " +
                                "This information has been verified but may not be their real identity"
                    )
                }
            }
            // Otherwise, just return the trustMetadata
            trustMetadata
        },
        domainMdocSignature = "mdoc",
        domainKeyBoundSdJwt = "sdjwt"
    )
    val dcqlQuery = DcqlQuery.fromJson(dcql = dcql)
    val dcqlResponse = dcqlQuery.execute(presentmentSource = source)
    return QueryResult(requester, source, dcqlResponse)
}

private suspend fun calculateRequester(
    certChain: CertChain,
    origin: Origin,
    appId: AppId,
    utopiaBreweryIcon: ByteString,
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
        serial = ASN1Integer.fromRandom(128),
        validFrom = validFrom,
        validUntil = validUntil
    )
    val readerCertWithGoogleAccount = MdocUtil.generateReaderCertificate(
        readerRootKey = readerRootSigningKey,
        readerKey = readerKey.publicKey,
        subject = X500Name.fromName("CN=Multipaz Reader Single-Use key"),
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
            certChain = readerCert?.let { X509CertChain(certificates = listOf(readerCert, readerRootCert)) },
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
