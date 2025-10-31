package org.multipaz.testapp

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.av18_card_art
import multipazproject.samples.testapp.generated.resources.card_utopia_wholesale
import multipazproject.samples.testapp.generated.resources.driving_license_card_art
import multipazproject.samples.testapp.generated.resources.movie_ticket_cart_art
import multipazproject.samples.testapp.generated.resources.photo_id_card_art
import multipazproject.samples.testapp.generated.resources.pid_card_art
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentCannedRequest
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.Loyalty
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.documenttype.knowntypes.UtopiaMovieTicket
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.ZkRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.testapp.ui.DocumentCreationMode
import org.multipaz.util.Logger
import org.multipaz.util.truncateToWholeSeconds
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

object TestAppUtils {
    private const val TAG = "TestAppUtils"

    // This domain is for MdocCredential using mdoc ECDSA/EdDSA authentication and requiring user authentication.
    const val CREDENTIAL_DOMAIN_MDOC_USER_AUTH = "mdoc_user_auth"

    // This domain is for MdocCredential using mdoc ECDSA/EdDSA authentication and not requiring user authentication.
    const val CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH = "mdoc_no_user_auth"

    // This domain is for MdocCredential using mdoc MAC authentication and requiring user authentication.
    const val CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH = "mdoc_mac_user_auth"

    // This domain is for MdocCredential using mdoc MAC authentication and not requiring user authentication.
    const val CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH = "mdoc_mac_no_user_auth"

    // This domain is for KeyBoundSdJwtVcCredential and requiring user authentication.
    const val CREDENTIAL_DOMAIN_SDJWT_USER_AUTH = "sdjwt_user_auth"

    // This domain is for KeyBoundSdJwtVcCredential and not requiring user authentication.
    const val CREDENTIAL_DOMAIN_SDJWT_NO_USER_AUTH = "sdjwt_no_user_auth"

    // This domain is for KeylessSdJwtVcCredential
    const val CREDENTIAL_DOMAIN_SDJWT_KEYLESS = "sdjwt_keyless"

    suspend fun generateEncodedDeviceRequest(
        request: DocumentCannedRequest,
        encodedSessionTranscript: ByteArray,
        readerKey: AsymmetricKey.X509Compatible,
        zkSystemRepository: ZkSystemRepository? = null,
    ): ByteArray {
        val mdocRequest = request.mdocRequest!!
        val itemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()
        for (ns in mdocRequest.namespacesToRequest) {
            for ((de, intentToRetain) in ns.dataElementsToRequest) {
                itemsToRequest.getOrPut(ns.namespace) { mutableMapOf() }
                    .put(de.attribute.identifier, intentToRetain)
            }
        }

        val zkRequest = if (mdocRequest.useZkp) {
            if (zkSystemRepository == null){
                throw IllegalStateException("zkSystemRepository is null")
            }
            ZkRequest(
                systemSpecs = zkSystemRepository.getAllZkSystemSpecs(),
                zkRequired = false
            )
        } else {
            null
        }

        return Cbor.encode(buildDeviceRequest(
            sessionTranscript = RawCbor(encodedSessionTranscript)
        ) {
            addDocRequest(
                docType = mdocRequest.docType,
                nameSpaces = itemsToRequest,
                docRequestInfo = DocRequestInfo(
                    zkRequest = zkRequest
                ),
                readerKey = readerKey,
            )
        }.toDataItem())
    }

    fun generateEncodedSessionTranscript(
        encodedDeviceEngagement: ByteArray,
        handover: DataItem,
        eReaderKey: EcPublicKey
    ): ByteArray {
        val encodedEReaderKey = Cbor.encode(eReaderKey.toCoseKey().toDataItem())
        return Cbor.encode(
            buildCborArray {
                add(Tagged(24, Bstr(encodedDeviceEngagement)))
                add(Tagged(24, Bstr(encodedEReaderKey)))
                add(handover)
            }
        )
    }


    val provisionedDocumentTypes = listOf(
        DrivingLicense.getDocumentType(),
        PhotoID.getDocumentType(),
        EUPersonalID.getDocumentType(),
        UtopiaMovieTicket.getDocumentType(),
        AgeVerification.getDocumentType(),
        Loyalty.getDocumentType(),
    )

    suspend fun provisionTestDocuments(
        documentCreationMode: DocumentCreationMode,
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
    ): String? {
        require(deviceKeyAlgorithm.isSigning)
        require(deviceKeyMacAlgorithm == Algorithm.UNSET || deviceKeyMacAlgorithm.isKeyAgreement)
        when (documentCreationMode) {
            DocumentCreationMode.EUPID_WITH_10_CREDENTIALS,
            DocumentCreationMode.EUPID_WITH_10_CREDENTIALS_BATCH -> {
                return provisionCsaEuPid(
                    documentCreationMode == DocumentCreationMode.EUPID_WITH_10_CREDENTIALS_BATCH,
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    EUPersonalID.getDocumentType(),
                    "Erika",
                    "Erika's EU PID in CSA",
                    Res.drawable.pid_card_art
                )
            }
            DocumentCreationMode.DCQL_TEST_DOCUMENTS -> {
                dcqlTestProvisionSdJwtVc(
                    displayName = "Erika's PID",
                    typeDisplayName = "Freedonia PID",
                    cardArtResource = Res.drawable.pid_card_art,
                    vct = "https://credentials.example.com/identity_credential",
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                        "family_name" to JsonPrimitive("Mustermann"),
                        "address" to buildJsonObject {
                            put("street_address", JsonPrimitive("Sample Street 123"))
                        }
                    ),
                    documentStore = documentStore,
                    secureArea = secureArea,
                    secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                    dsKey = dsKey
                )
                dcqlTestProvisionSdJwtVc(
                    displayName = "Max's PID",
                    typeDisplayName = "Freedonia PID",
                    cardArtResource = Res.drawable.pid_card_art,
                    vct = "https://credentials.example.com/identity_credential",
                    data = listOf(
                        "given_name" to JsonPrimitive("Max"),
                        "family_name" to JsonPrimitive("Mustermann"),
                        "address" to buildJsonObject {
                            put("street_address", JsonPrimitive("Sample Street 456"))
                        }
                    ),
                    documentStore = documentStore,
                    secureArea = secureArea,
                    secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                    dsKey = dsKey
                )
                dcqlTestProvisionSdJwtVc(
                    displayName = "Erika's PID",
                    typeDisplayName = "Elbonia PID",
                    cardArtResource = Res.drawable.photo_id_card_art,
                    vct = "https://othercredentials.example/pid",
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                        "family_name" to JsonPrimitive("Mustermann"),
                        "address" to buildJsonObject {
                            put("street_address", JsonPrimitive("Sample Street 123"))
                        }
                    ),
                    documentStore = documentStore,
                    secureArea = secureArea,
                    secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                    dsKey = dsKey
                )
                dcqlTestProvisionSdJwtVc(
                    displayName = "Erika's ID Card",
                    typeDisplayName = "Gotham City ID Card",
                    cardArtResource = Res.drawable.driving_license_card_art,
                    vct = "https://credentials.example.com/reduced_identity_credential",
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                        "family_name" to JsonPrimitive("Mustermann"),
                    ),
                    documentStore = documentStore,
                    secureArea = secureArea,
                    secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                    dsKey = dsKey
                )
                dcqlTestProvisionSdJwtVc(
                    displayName = "Erika's Residence Card",
                    typeDisplayName = "Gotham City Residence Card",
                    cardArtResource = Res.drawable.driving_license_card_art,
                    vct = "https://cred.example/residence_credential",
                    data = listOf(
                        "postal_code" to JsonPrimitive(90210),
                        "locality" to JsonPrimitive("Beverly Hills"),
                        "region" to JsonPrimitive("Los Angeles Basin"),
                    ),
                    documentStore = documentStore,
                    secureArea = secureArea,
                    secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                    dsKey = dsKey
                )
                dcqlTestProvisionSdJwtVc(
                    displayName = "Erika's Reward Card",
                    typeDisplayName = "Earl's Market Sav4You",
                    cardArtResource = Res.drawable.movie_ticket_cart_art,
                    vct = "https://company.example/company_rewards",
                    data = listOf(
                        "rewards_number" to JsonPrimitive(24601),
                    ),
                    documentStore = documentStore,
                    secureArea = secureArea,
                    secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                    dsKey = dsKey
                )
                return null
            }
            DocumentCreationMode.NORMAL -> {
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    DrivingLicense.getDocumentType(),
                    "Erika",
                    "Erika's Driving License",
                    Res.drawable.driving_license_card_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    PhotoID.getDocumentType(),
                    "Erika",
                    "Erika's Photo ID",
                    Res.drawable.photo_id_card_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    PhotoID.getDocumentType(),
                    "Erika #2",
                    "Erika's Photo ID #2",
                    Res.drawable.photo_id_card_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    EUPersonalID.getDocumentType(),
                    "Erika",
                    "Erika's EU PID",
                    Res.drawable.pid_card_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    UtopiaMovieTicket.getDocumentType(),
                    "Erika",
                    "The Last Utopian",
                    Res.drawable.movie_ticket_cart_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    UtopiaMovieTicket.getDocumentType(),
                    "Erika",
                    "One flew over the Utopian Nest",
                    Res.drawable.movie_ticket_cart_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    UtopiaMovieTicket.getDocumentType(),
                    "Erika",
                    "Utopia! Utopia! Utopia!",
                    Res.drawable.movie_ticket_cart_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    AgeVerification.getDocumentType(),
                    "Erika",
                    "Erika's Age Verification Credential",
                    Res.drawable.av18_card_art
                )
                provisionDocument(
                    documentStore,
                    secureArea,
                    secureAreaCreateKeySettingsFunc,
                    dsKey,
                    deviceKeyAlgorithm,
                    deviceKeyMacAlgorithm,
                    numCredentialsPerDomain,
                    Loyalty.getDocumentType(),
                    "Erika",
                    "Erika's Loyalty ID",
                    Res.drawable.card_utopia_wholesale
                )
                return null
            }
        }
    }

    private suspend fun dcqlTestProvisionSdJwtVc(
        displayName: String,
        typeDisplayName: String,
        cardArtResource: DrawableResource,
        vct: String,
        data: List<Pair<String, JsonElement>>,
        documentStore: DocumentStore,
        secureArea: SecureArea,
        secureAreaCreateKeySettingsFunc: (
            challenge: ByteString,
            algorithm: Algorithm,
            userAuthenticationRequired: Boolean,
            validFrom: Instant,
            validUntil: Instant
        ) -> CreateKeySettings,
        dsKey: AsymmetricKey
    ): Document {
        val cardArt = getDrawableResourceBytes(
            getSystemResourceEnvironment(),
            cardArtResource,
        )

        val now = Clock.System.now()
        val signedAt = now - 1.hours
        val validFrom =  now - 1.hours
        val validUntil = now + 365.days
        val document = documentStore.createDocument(
            displayName = displayName,
            typeDisplayName = typeDisplayName,
            cardArt = ByteString(cardArt)
        )
        val identityAttributes = buildJsonObject {
            for ((claimName, claimValue) in data) {
                put(claimName, claimValue)
            }
        }
        addSdJwtVcCredentialWithData(
            document = document,
            vct = vct,
            identityAttributes = identityAttributes,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            secureArea = secureArea,
            secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
            dsKey = dsKey,
        )
        return document
    }

    private suspend fun addSdJwtVcCredentialWithData(
        document: Document,
        vct: String,
        identityAttributes: JsonObject,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        secureArea: SecureArea,
        secureAreaCreateKeySettingsFunc: (
            challenge: ByteString,
            algorithm: Algorithm,
            userAuthenticationRequired: Boolean,
            validFrom: Instant,
            validUntil: Instant
        ) -> CreateKeySettings,
        dsKey: AsymmetricKey,
    ) {
        for (domain in listOf(CREDENTIAL_DOMAIN_SDJWT_NO_USER_AUTH, CREDENTIAL_DOMAIN_SDJWT_USER_AUTH)) {
            val credential = KeyBoundSdJwtVcCredential.create(
                document = document,
                asReplacementForIdentifier = null,
                domain = domain,
                secureArea = secureArea,
                vct = vct,
                createKeySettings = secureAreaCreateKeySettingsFunc(
                    ByteString(),
                    Algorithm.ESP256,
                    if (domain == CREDENTIAL_DOMAIN_SDJWT_USER_AUTH) true else false,
                    validFrom,
                    validUntil,
                )
            )

            val sdJwt = SdJwt.create(
                issuerKey = dsKey,
                kbKey = (credential as? SecureAreaBoundCredential)?.let {
                    it.secureArea.getKeyInfo(
                        it.alias
                    ).publicKey
                },
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
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun provisionCsaEuPid(
        useBatching: Boolean,
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
        documentType: DocumentType,
        givenNameOverride: String,
        displayName: String,
        cardArtResource: DrawableResource,
    ): String? {
        val cardArt = getDrawableResourceBytes(
            getSystemResourceEnvironment(),
            cardArtResource,
        )

        val document = documentStore.createDocument(
            displayName = displayName,
            typeDisplayName = documentType.displayName,
            cardArt = ByteString(cardArt),
        )

        val now = Clock.System.now()
        val signedAt = now - 1.hours
        val validFrom =  now - 1.hours
        val validUntil = now + 365.days

        return addMdocCredentialsSameType(
            useBatching = useBatching,
            document = document,
            documentType = documentType,
            secureArea = secureArea,
            secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
            deviceKeyAlgorithm = deviceKeyAlgorithm,
            deviceKeyMacAlgorithm = deviceKeyMacAlgorithm,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            dsKey = dsKey,
            numCredentialsPerDomain = 10,
            givenNameOverride = givenNameOverride
        )
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun provisionDocument(
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
        documentType: DocumentType,
        givenNameOverride: String,
        displayName: String,
        cardArtResource: DrawableResource,
    ) {
        val cardArt = getDrawableResourceBytes(
            getSystemResourceEnvironment(),
            cardArtResource,
        )

        val document = documentStore.createDocument(
            displayName = displayName,
            typeDisplayName = documentType.displayName,
            cardArt = ByteString(cardArt),
        )

        val now = Clock.System.now().truncateToWholeSeconds()
        val signedAt = now - 1.hours
        val validFrom =  now - 1.hours
        val validUntil = now + 365.days

        if (documentType.mdocDocumentType != null) {
            addMdocCredentials(
                document = document,
                documentType = documentType,
                secureArea = secureArea,
                secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                deviceKeyAlgorithm = deviceKeyAlgorithm,
                deviceKeyMacAlgorithm = deviceKeyMacAlgorithm,
                signedAt = signedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                dsKey = dsKey,
                numCredentialsPerDomain = numCredentialsPerDomain,
                givenNameOverride = givenNameOverride
            )
        }

        if (documentType.jsonDocumentType != null) {
            addSdJwtVcCredentials(
                document = document,
                documentType = documentType,
                secureArea = secureArea,
                secureAreaCreateKeySettingsFunc = secureAreaCreateKeySettingsFunc,
                deviceKeyAlgorithm = deviceKeyAlgorithm,
                signedAt = signedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                dsKey = dsKey,
                numCredentialsPerDomain = numCredentialsPerDomain,
                givenNameOverride = givenNameOverride
            )
        }
    }

    private suspend fun addMdocCredentials(
        document: Document,
        documentType: DocumentType,
        secureArea: SecureArea,
        secureAreaCreateKeySettingsFunc: (
            challenge: ByteString,
            algorithm: Algorithm,
            userAuthenticationRequired: Boolean,
            validFrom: Instant,
            validUntil: Instant
        ) -> CreateKeySettings,
        deviceKeyAlgorithm: Algorithm,
        deviceKeyMacAlgorithm: Algorithm,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        dsKey: AsymmetricKey.X509Certified,
        numCredentialsPerDomain: Int,
        givenNameOverride: String
    ) {
        val issuerNamespaces = buildIssuerNamespaces {
            for ((nsName, ns) in documentType.mdocDocumentType?.namespaces!!) {
                addNamespace(nsName) {
                    for ((deName, de) in ns.dataElements) {
                        val sampleValue = de.attribute.sampleValueMdoc
                        if (sampleValue != null) {
                            val value = if (deName.startsWith("given_name")) {
                                Tstr(givenNameOverride)
                            } else {
                                sampleValue
                            }
                            addDataElement(deName, value)
                        } else {
                            Logger.w(TAG, "No sample value for data element $deName")
                        }
                    }
                }
            }
        }

        // Create authentication keys...
        for (domain in listOf(
            CREDENTIAL_DOMAIN_MDOC_USER_AUTH,
            CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH,
            CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH,
            CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH
        )) {
            val userAuthenticationRequired = when (domain) {
                CREDENTIAL_DOMAIN_MDOC_USER_AUTH, CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH -> true
                else -> false
            }
            val algorithm = when (domain) {
                CREDENTIAL_DOMAIN_MDOC_USER_AUTH -> deviceKeyAlgorithm
                CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH -> deviceKeyAlgorithm
                CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH -> deviceKeyMacAlgorithm
                CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH ->  deviceKeyMacAlgorithm
                else -> throw IllegalStateException()
            }
            if (algorithm == Algorithm.UNSET) {
                continue
            }

            for (n in 1..numCredentialsPerDomain) {
                val mdocCredential = MdocCredential.create(
                    document = document,
                    asReplacementForIdentifier = null,
                    domain = domain,
                    secureArea = secureArea,
                    docType = documentType.mdocDocumentType!!.docType,
                    createKeySettings = secureAreaCreateKeySettingsFunc(
                        "Challenge".encodeToByteString(),
                        algorithm,
                        userAuthenticationRequired,
                        validFrom,
                        validUntil
                    )
                )

                // Generate an MSO and issuer-signed data for this authentication key.
                val msoGenerator = MobileSecurityObjectGenerator(
                    Algorithm.SHA256,
                    documentType.mdocDocumentType!!.docType,
                    mdocCredential.getAttestation().publicKey
                )
                msoGenerator.setValidityInfo(signedAt, validFrom, validUntil, null)
                msoGenerator.addValueDigests(issuerNamespaces)

                val mso = msoGenerator.generate()
                val taggedEncodedMso = Cbor.encode(Tagged(24, Bstr(mso)))

                // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
                //
                // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
                //
                val protectedHeaders = mapOf<CoseLabel, DataItem>(
                    Pair(
                        CoseNumberLabel(Cose.COSE_LABEL_ALG),
                        Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
                    )
                )
                val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
                    Pair(
                        CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                        dsKey.certChain.toDataItem()
                    )
                )
                val encodedIssuerAuth = Cbor.encode(
                    Cose.coseSign1Sign(
                        dsKey,
                        taggedEncodedMso,
                        true,
                        protectedHeaders,
                        unprotectedHeaders
                    ).toDataItem()
                )
                val issuerProvidedAuthenticationData = Cbor.encode(
                    buildCborMap {
                        put("nameSpaces", issuerNamespaces.toDataItem())
                        put("issuerAuth", RawCbor(encodedIssuerAuth))
                    }
                )

                // Now that we have issuer-provided authentication data we certify the authentication key.
                mdocCredential.certify(
                    issuerProvidedAuthenticationData,
                    validFrom,
                    validUntil
                )
            }
        }

    }

    private suspend fun addMdocCredentialsSameType(
        useBatching: Boolean,
        document: Document,
        documentType: DocumentType,
        secureArea: SecureArea,
        secureAreaCreateKeySettingsFunc: (
            challenge: ByteString,
            algorithm: Algorithm,
            userAuthenticationRequired: Boolean,
            validFrom: Instant,
            validUntil: Instant
        ) -> CreateKeySettings,
        deviceKeyAlgorithm: Algorithm,
        deviceKeyMacAlgorithm: Algorithm,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        dsKey: AsymmetricKey.X509Certified,
        numCredentialsPerDomain: Int,
        givenNameOverride: String
    ): String? {
        val issuerNamespaces = buildIssuerNamespaces {
            for ((nsName, ns) in documentType.mdocDocumentType?.namespaces!!) {
                addNamespace(nsName) {
                    for ((deName, de) in ns.dataElements) {
                        val sampleValue = de.attribute.sampleValueMdoc
                        if (sampleValue != null) {
                            val value = if (deName.startsWith("given_name")) {
                                Tstr(givenNameOverride)
                            } else {
                                sampleValue
                            }
                            addDataElement(deName, value)
                        }
                    }
                }
            }
        }

        val domain = CREDENTIAL_DOMAIN_MDOC_USER_AUTH
        val algorithm = deviceKeyAlgorithm

        val (credentials, openid4vciAttestationCompactSerialization) = if (useBatching) {
            MdocCredential.createBatch(
                numberOfCredentials = 10,
                document = document,
                domain = CREDENTIAL_DOMAIN_MDOC_USER_AUTH,
                secureArea = secureArea,
                docType = documentType.mdocDocumentType!!.docType,
                createKeySettings = secureAreaCreateKeySettingsFunc(
                    "Challenge".encodeToByteString(),
                    deviceKeyAlgorithm,
                    true,
                    validFrom,
                    validUntil
                )
            )
        } else {
            val creds = mutableListOf<MdocCredential>()
            for (n in 1..numCredentialsPerDomain) {
                val mdocCredential = MdocCredential.create(
                    document = document,
                    asReplacementForIdentifier = null,
                    domain = CREDENTIAL_DOMAIN_MDOC_USER_AUTH,
                    secureArea = secureArea,
                    docType = documentType.mdocDocumentType!!.docType,
                    createKeySettings = secureAreaCreateKeySettingsFunc(
                        "Challenge".encodeToByteString(),
                        deviceKeyAlgorithm,
                        true,
                        validFrom,
                        validUntil
                    )
                )
                creds.add(mdocCredential)
            }
            Pair(creds, null)
        }

        for (mdocCredential in credentials) {
            // Generate an MSO and issuer-signed data for this authentication key.
            val msoGenerator = MobileSecurityObjectGenerator(
                Algorithm.SHA256,
                documentType.mdocDocumentType!!.docType,
                mdocCredential.getAttestation().publicKey
            )
            msoGenerator.setValidityInfo(signedAt, validFrom, validUntil, null)
            msoGenerator.addValueDigests(issuerNamespaces)

            val mso = msoGenerator.generate()
            val taggedEncodedMso = Cbor.encode(Tagged(24, Bstr(mso)))

            // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
            //
            // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
            //
            val protectedHeaders = mapOf<CoseLabel, DataItem>(
                Pair(
                    CoseNumberLabel(Cose.COSE_LABEL_ALG),
                    Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
                )
            )
            val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
                Pair(
                    CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                    dsKey.certChain.toDataItem()
                )
            )
            val encodedIssuerAuth = Cbor.encode(
                Cose.coseSign1Sign(
                    dsKey,
                    taggedEncodedMso,
                    true,
                    protectedHeaders,
                    unprotectedHeaders
                ).toDataItem()
            )
            val issuerProvidedAuthenticationData = Cbor.encode(
                buildCborMap {
                    put("nameSpaces", issuerNamespaces.toDataItem())
                    put("issuerAuth", RawCbor(encodedIssuerAuth))
                }
            )

            // Now that we have issuer-provided authentication data we certify the authentication key.
            mdocCredential.certify(
                issuerProvidedAuthenticationData,
                validFrom,
                validUntil
            )
        }
        return openid4vciAttestationCompactSerialization
    }

    // Technically - according to RFC 7800 at least - SD-JWT could do MACing too but it would
    // need to be specced out in e.g. SD-JWT VC profile where to get the public key from the
    // recipient. So for now, we only support signing.
    //
    private suspend fun addSdJwtVcCredentials(
        document: Document,
        documentType: DocumentType,
        secureArea: SecureArea,
        secureAreaCreateKeySettingsFunc: (
            challenge: ByteString,
            algorithm: Algorithm,
            userAuthenticationRequired: Boolean,
            validFrom: Instant,
            validUntil: Instant
        ) -> CreateKeySettings,
        deviceKeyAlgorithm: Algorithm,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        dsKey: AsymmetricKey,
        numCredentialsPerDomain: Int,
        givenNameOverride: String
    ) {
        if (documentType.jsonDocumentType == null) {
            return
        }

        val identityAttributes = buildJsonObject {
            for ((claimName, attribute) in documentType.jsonDocumentType!!.claims) {
                // Skip sub-claims.
                if (claimName.contains('.')) {
                    continue
                }
                val sampleValue = attribute.sampleValueJson
                if (sampleValue != null) {
                    val value = if (claimName.startsWith("given_name")) {
                        JsonPrimitive(givenNameOverride)
                    } else {
                        sampleValue
                    }
                    put(claimName, value)
                }
            }
        }

        val (domains, numCredentialsPerDomainAdj) = if (documentType.jsonDocumentType!!.keyBound) {
            Pair(listOf(CREDENTIAL_DOMAIN_SDJWT_USER_AUTH, CREDENTIAL_DOMAIN_SDJWT_NO_USER_AUTH), numCredentialsPerDomain)
        } else {
            // No point in having multiple credentials for keyless credentials..
            Pair(listOf(CREDENTIAL_DOMAIN_SDJWT_KEYLESS), 1)
        }
        for (domain in domains) {
            for (n in 1..numCredentialsPerDomainAdj) {
                val credential = if (documentType.jsonDocumentType!!.keyBound) {
                    val userAuthenticationRequired = (domain == CREDENTIAL_DOMAIN_SDJWT_USER_AUTH)
                    KeyBoundSdJwtVcCredential.create(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = domain,
                        secureArea = secureArea,
                        vct = documentType.jsonDocumentType!!.vct,
                        createKeySettings = secureAreaCreateKeySettingsFunc(
                            "Challenge".encodeToByteString(),
                            deviceKeyAlgorithm,
                            userAuthenticationRequired,
                            validFrom,
                            validUntil
                        )
                    )
                } else {
                    KeylessSdJwtVcCredential.create(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = domain,
                        vct = documentType.jsonDocumentType!!.vct,
                    )
                }

                val kbKey = (credential as? SecureAreaBoundCredential)?.getAttestation()?.publicKey
                val sdJwt = SdJwt.create(
                    issuerKey = dsKey,
                    kbKey = kbKey,
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
            }
        }
    }

}