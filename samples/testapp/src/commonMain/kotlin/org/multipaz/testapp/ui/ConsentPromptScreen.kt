package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
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
import org.jetbrains.compose.resources.painterResource
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.certext.GoogleAccount
import org.multipaz.certext.MultipazExtension
import org.multipaz.certext.fromCbor
import org.multipaz.certext.toCbor
import org.multipaz.compose.presentment.CredentialPresentmentModalBottomSheet
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509Extension
import org.multipaz.crypto.buildX509Cert
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentCannedRequest
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.openid.dcql.DcqlResponse
import org.multipaz.presentment.model.SimplePresentmentSource
import org.multipaz.request.Requester
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testapp.platformAppIcon
import org.multipaz.testapp.platformAppName
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.trustmanagement.TrustPoint
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
    VERIFIER_MULTIPAZ_ORG("verifier.multipaz.org", "verifier.multipaz.org"),
    OTHER_EXAMPLE_COM("other.example.com", "other.example.com"),
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
    OPENID4VP_COMPLEX_EXAMPLE("Complex example from OpenID4VP Appendix D")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentPromptScreen(
    imageLoader: ImageLoader,
    documentTypeRepository: DocumentTypeRepository,
    secureAreaRepository: SecureAreaRepository,
    showToast: (message: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var useCase by remember { mutableStateOf(UseCase.entries.first()) }
    var certChain by remember { mutableStateOf(CertChain.entries.first()) }
    var origin by remember { mutableStateOf(Origin.entries.first()) }
    var appId by remember { mutableStateOf(AppId.entries.first()) }
    var showCancelAsBack by remember { mutableStateOf(false) }
    val showCredentialPresentmentBottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )


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

        item {
            SettingToggle(
                title = "Show Cancel as Back",
                isChecked = showCancelAsBack,
                onCheckedChange = { showCancelAsBack = it },
            )
        }

        item {
            Button(
                onClick = {
                    coroutineScope.launch {
                        showCredentialPresentmentBottomSheetState.show()
                    }
                }
            ) {
                Text("Show Consent Prompt")
            }
        }
    }

    var cardArt by remember { mutableStateOf(ByteArray(0)) }
    var utopiaBreweryIcon by remember { mutableStateOf(ByteString()) }
    var identityReaderIcon by remember { mutableStateOf(ByteString()) }
    var documentStore by remember { mutableStateOf<DocumentStore?>(null) }
    LaunchedEffect(Unit) {
        cardArt = Res.readBytes("files/utopia_driving_license_card_art.png")
        utopiaBreweryIcon = ByteString(Res.readBytes("files/utopia-brewery.png"))
        identityReaderIcon = ByteString(Res.readBytes("drawable/app_icon.webp"))

        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val validFrom = Clock.System.now()
        val validUntil = Clock.System.now() + 10.days
        val dsCert = buildX509Cert(
            publicKey = dsPrivateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(dsPrivateKey),
            serialNumber = ASN1Integer.fromRandom(numBits = 128),
            subject = X500Name.fromName("CN=Test"),
            issuer = X500Name.fromName("CN=Test"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {}
        val dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert)), dsPrivateKey)
        DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = documentStore!!.createDocument(
                displayName = "Erika's Driving License",
                typeDisplayName = "Utopia Driving License",
                cardArt = ByteString(cardArt)
            ),
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            domain = "mdoc"
        )
        PhotoID.getDocumentType().createMdocCredentialWithSampleData(
            document = documentStore!!.createDocument(
                displayName = "Erika's Photo ID",
                typeDisplayName = "Utopia Photo ID",
                cardArt = ByteString(cardArt)
            ),
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            domain = "mdoc"
        )
        PhotoID.getDocumentType().createMdocCredentialWithSampleData(
            document = documentStore!!.createDocument(
                displayName = "Erika's Photo ID #2",
                typeDisplayName = "Utopia Photo ID",
                cardArt = ByteString(cardArt)
            ),
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            domain = "mdoc"
        )
        addCredentialsForOpenID4VPComplexExample(
            documentStore = documentStore!!,
            secureArea = secureArea,
            signedAt = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            dsKey = dsKey,
        )
    }


    if (showCredentialPresentmentBottomSheetState.isVisible) {
        val queryResult = mutableStateOf<QueryResult?>(null)
        LaunchedEffect(useCase, certChain, origin, appId) {
            queryResult.value = getQueryResult(
                useCase = useCase,
                certChain = certChain,
                origin = origin,
                appId = appId,
                utopiaBreweryIcon = utopiaBreweryIcon,
                identityReaderIcon = identityReaderIcon,
                documentStore = documentStore,
                documentTypeRepository = documentTypeRepository
            )
        }
        val result = queryResult.value
        if (result != null) {
            CredentialPresentmentModalBottomSheet(
                sheetState = showCredentialPresentmentBottomSheetState,
                requester = result.requester,
                trustPoint = result.trustPoint,
                credentialPresentmentData = result.dcqlResponse,
                preselectedDocuments = emptyList(),
                imageLoader = imageLoader,
                dynamicMetadataResolver = { requester ->
                    requester.certChain?.certificates?.first()
                        ?.getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)?.let {
                            MultipazExtension.fromCbor(it).googleAccount?.let {
                                TrustMetadata(
                                    displayName = it.emailAddress,
                                    displayIconUrl = it.profilePictureUri,
                                    disclaimer = "The email and picture shown are from the requester's Google Account. " +
                                            "This information has been verified but may not be their real identity"
                                )
                            }
                        }
                },
                appName = platformAppName,
                appIconPainter = painterResource(platformAppIcon),
                onConfirm = { selection ->
                    coroutineScope.launch {
                        showCredentialPresentmentBottomSheetState.hide()
                    }
                },
                onCancel = {
                    coroutineScope.launch {
                        showCredentialPresentmentBottomSheetState.hide()
                    }
                },
                showCancelAsBack = showCancelAsBack
            )
        }
    }
}

private data class QueryResult(
    val requester: Requester,
    val trustPoint: TrustPoint?,
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
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "age_over_21_and_portrait" }!!.toDcql()
        UseCase.MDL_US_TRANSPORTATION ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "us-transportation" }!!.toDcql()
        UseCase.MDL_MANDATORY ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "mandatory" }!!.toDcql()
        UseCase.MDL_ALL ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "full" }!!.toDcql()
        UseCase.MDL_NAME_AND_ADDRESS_PARTIALLY_STORED ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "name-and-address-partially-stored" }!!.toDcql()
        UseCase.MDL_NAME_AND_ADDRESS_ALL_STORED ->
            DrivingLicense.getDocumentType().cannedRequests.find { it.id == "name-and-address-all-stored" }!!.toDcql()
        UseCase.PHOTO_ID_MANDATORY ->
            PhotoID.getDocumentType().cannedRequests.find { it.id == "mandatory" }!!.toDcql()
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
    }
    val (requester, trustPoint) = calculateRequester(
        certChain = certChain,
        origin = origin,
        appId = appId,
        utopiaBreweryIcon = utopiaBreweryIcon,
        identityReaderIcon = identityReaderIcon
    )
    val readerTrustManager = TrustManagerLocal(
        storage = EphemeralStorage()
    )
    val presentmentSource = SimplePresentmentSource(
        documentStore = documentStore!!,
        documentTypeRepository = documentTypeRepository,
        readerTrustManager = readerTrustManager,
        domainMdocSignature = "mdoc",
        domainKeyBoundSdJwt = "sdjwt"
    )
    val dcqlQuery = DcqlQuery.fromJson(dcql = dcql)
    val dcqlResponse = dcqlQuery.execute(presentmentSource = presentmentSource)
    return QueryResult(requester, trustPoint, dcqlResponse)
}

private suspend fun calculateRequester(
    certChain: CertChain,
    origin: Origin,
    appId: AppId,
    utopiaBreweryIcon: ByteString,
    identityReaderIcon: ByteString
): Pair<Requester, TrustPoint?> {
    val now = Clock.System.now()
    val validFrom = now - 1.days
    val validUntil = now + 1.days
    // TODO: should it be in SecureArea?
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


    val trustManager = TrustManagerLocal(storage = EphemeralStorage())
    val (trustPoint, readerCert) = when (certChain) {
        CertChain.CERT_CHAIN_UTOPIA_BREWERY -> {
            Pair(
                TrustPoint(
                certificate = readerRootCert,
                metadata = TrustMetadata(
                    displayName = "Utopia Brewery",
                    displayIcon = utopiaBreweryIcon,
                    privacyPolicyUrl = "https://apps.multipaz.org",
                ),
                trustManager = trustManager
            ),
                readerCertWithoutGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_UTOPIA_BREWERY_NO_PRIVACY_POLICY -> {
            Pair(
            TrustPoint(
                certificate = readerRootCert,
                metadata = TrustMetadata(
                    displayName = "Utopia Brewery",
                    displayIcon = utopiaBreweryIcon,
                    privacyPolicyUrl = null,
                ),
                trustManager = trustManager
            ),
                readerCertWithoutGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_IDENTITY_READER ->  {
            Pair(
            TrustPoint(
                certificate = readerRootCert,
                metadata = TrustMetadata(
                    displayName = "Multipaz Identity Reader",
                    displayIcon = identityReaderIcon,
                    privacyPolicyUrl = "https://apps.multipaz.org",
                ),
                trustManager = trustManager
            ),
                readerCertWithoutGoogleAccount
            )
        }
        CertChain.CERT_CHAIN_IDENTITY_READER_GOOGLE_ACCOUNT -> {
            Pair(
            TrustPoint(
                certificate = readerRootCert,
                metadata = TrustMetadata(
                    displayName = "Multipaz Identity Reader",
                    displayIcon = identityReaderIcon,
                    privacyPolicyUrl = "https://apps.multipaz.org",
                ),
                trustManager = trustManager
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
        trustPoint
    )
}

private fun DocumentCannedRequest.toDcql(
    requestJson: Boolean = false
): JsonObject {
    val dcql = buildJsonObject {
        putJsonArray("credentials") {
            if (requestJson) {
                addJsonObject {
                    put("id", JsonPrimitive("cred1"))
                    put("format", JsonPrimitive("dc+sd-jwt"))
                    putJsonObject("meta") {
                        put(
                            "vct_values",
                            buildJsonArray {
                                add(JsonPrimitive(jsonRequest!!.vct))
                            }
                        )
                    }
                    putJsonArray("claims") {
                        for (claim in jsonRequest!!.claimsToRequest) {
                            addJsonObject {
                                putJsonArray("path") {
                                    claim.parentAttribute?.let { add(JsonPrimitive(it.identifier)) }
                                    add(JsonPrimitive(claim.identifier))
                                }
                            }
                        }
                    }
                }
            } else {
                addJsonObject {
                    put("id", JsonPrimitive("cred1"))
                    put("format", JsonPrimitive("mso_mdoc"))
                    putJsonObject("meta") {
                        put("doctype_value", JsonPrimitive(mdocRequest!!.docType))
                    }
                    putJsonArray("claims") {
                        for (ns in mdocRequest!!.namespacesToRequest) {
                            for ((de, intentToRetain) in ns.dataElementsToRequest) {
                                addJsonObject {
                                    putJsonArray("path") {
                                        add(JsonPrimitive(ns.namespace))
                                        add(JsonPrimitive(de.attribute.identifier))
                                    }
                                    put("intent_to_retain", JsonPrimitive(intentToRetain))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return dcql
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
    credential.certify(
        sdJwt.compactSerialization.encodeToByteArray(),
        validFrom,
        validUntil
    )
    return document
}
