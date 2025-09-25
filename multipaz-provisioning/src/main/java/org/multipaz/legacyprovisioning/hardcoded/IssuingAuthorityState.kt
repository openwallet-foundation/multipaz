package org.multipaz.legacyprovisioning.hardcoded

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborInt
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.document.NameSpacedData
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.GermanPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.Resources
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.legacyprovisioning.CredentialData
import org.multipaz.legacyprovisioning.CredentialFormat
import org.multipaz.legacyprovisioning.DocumentCondition
import org.multipaz.legacyprovisioning.DocumentConfiguration
import org.multipaz.legacyprovisioning.DocumentState
import org.multipaz.legacyprovisioning.IssuingAuthority
import org.multipaz.legacyprovisioning.IssuingAuthorityConfiguration
import org.multipaz.legacyprovisioning.MdocDocumentConfiguration
import org.multipaz.legacyprovisioning.RegistrationResponse
import org.multipaz.legacyprovisioning.IssuingAuthorityNotification
import org.multipaz.legacyprovisioning.SdJwtVcDocumentConfiguration
import org.multipaz.legacyprovisioning.WalletApplicationCapabilities
import org.multipaz.legacyprovisioning.ProvisioningBackendSettings
import org.multipaz.rpc.cache
import org.multipaz.rpc.backend.getTable
import org.multipaz.legacyprovisioning.evidence.DirectAccessDocumentConfiguration
import org.multipaz.legacyprovisioning.evidence.EvidenceResponse
import org.multipaz.legacyprovisioning.evidence.EvidenceResponseIcaoNfcTunnelResult
import org.multipaz.legacyprovisioning.evidence.EvidenceResponseIcaoPassiveAuthentication
import org.multipaz.legacyprovisioning.evidence.EvidenceResponseQuestionMultipleChoice
import org.multipaz.legacyprovisioning.fromCbor
import org.multipaz.legacyprovisioning.proofing.defaultCredentialConfiguration
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.mdoc.mso.StaticAuthDataGenerator
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mrtd.MrtdNfcData
import org.multipaz.mrtd.MrtdNfcDataDecoder
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.legacyprovisioning.Proofing
import org.multipaz.legacyprovisioning.Registration
import org.multipaz.legacyprovisioning.RequestCredentials
import org.multipaz.legacyprovisioning.wallet.AuthenticationState
import org.multipaz.rpc.backend.RpcAuthBackendDelegate
import org.multipaz.rpc.handler.RpcAuthContext
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.sdjwt.SdJwt
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

private const val MDL_DOCTYPE = DrivingLicense.MDL_DOCTYPE
private const val MDL_NAMESPACE = DrivingLicense.MDL_NAMESPACE
private const val AAMVA_NAMESPACE = DrivingLicense.AAMVA_NAMESPACE

private const val EUPID_DOCTYPE = EUPersonalID.EUPID_DOCTYPE
private const val EUPID_NAMESPACE = EUPersonalID.EUPID_NAMESPACE

private const val PHOTO_ID_DOCTYPE = PhotoID.PHOTO_ID_DOCTYPE
private const val PHOTO_ID_NAMESPACE = PhotoID.PHOTO_ID_NAMESPACE
private const val ISO_23220_2_NAMESPACE = PhotoID.ISO_23220_2_NAMESPACE

/**
 * State of [IssuingAuthority] RPC implementation.
 */
@RpcState(endpoint = "hardcoded")
@CborSerializable
class IssuingAuthorityState(
    // NB: since this object is used to emit notifications, it cannot change, as its state
    // serves as notification key. So it generally should not have var members, only val.
    // This is also the reason we keep clientId here (so there is no conflict between different
    // clients)
    val clientId: String,
    val authorityId: String = ""
) : IssuingAuthority, RpcAuthInspector by RpcAuthBackendDelegate {
    companion object {
        private const val TAG = "IssuingAuthorityState"
        
        private const val TYPE_EU_PID = "EuPid"
        private const val TYPE_DRIVING_LICENSE = "DrivingLicense"
        private const val TYPE_PHOTO_ID = "PhotoId"

        suspend fun getConfiguration(id: String): IssuingAuthorityConfiguration {
            return BackendEnvironment.cache(
                IssuingAuthorityConfiguration::class,
                id
            ) { configuration, resources ->
                val settings = ProvisioningBackendSettings(configuration)
                val prefix = "issuingAuthority.$id"
                val logoPath = settings.getString("${prefix}.logo") ?: "default/logo.png"
                val logo = resources.getRawResource(logoPath)!!
                val artPath =
                    settings.getString("${prefix}.cardArt") ?: "default/card_art.png"
                val art = resources.getRawResource(artPath)!!
                val requireUserAuthenticationToViewDocument = settings.getBool(
                    "${prefix}.requireUserAuthenticationToViewDocument",
                    false
                )
                val type = settings.getString("$prefix.type") ?: TYPE_DRIVING_LICENSE

                IssuingAuthorityConfiguration(
                    identifier = id,
                    issuingAuthorityName = settings.getString("${prefix}.name") ?: "Untitled",
                    issuingAuthorityLogo = logo.toByteArray(),
                    issuingAuthorityDescription = settings.getString("${prefix}.description")
                        ?: "Unknown",
                    pendingDocumentInformation = DocumentConfiguration(
                        displayName = "Pending",
                        typeDisplayName = when (type) {
                            TYPE_DRIVING_LICENSE -> "Driving License"
                            TYPE_EU_PID -> "EU Personal ID"
                            TYPE_PHOTO_ID -> "Photo ID"
                            else -> throw IllegalArgumentException("Unknown type $type")
                        },
                        cardArt = art.toByteArray(),
                        requireUserAuthenticationToViewDocument = requireUserAuthenticationToViewDocument,
                        mdocConfiguration = null,
                        directAccessConfiguration = null,
                        sdJwtVcDocumentConfiguration = null
                    ),
                    numberOfCredentialsToRequest = 3,
                    minCredentialValidityMillis = 30 * 24 * 3600L,
                    maxUsesPerCredentials = 1
                )
            }
        }

        // TODO: calling app should pass in DocumentTypeRepository, we shouldn't create it here
        val documentTypeRepository = DocumentTypeRepository().apply {
            addDocumentType(DrivingLicense.getDocumentType())
            addDocumentType(GermanPersonalID.getDocumentType())
            addDocumentType(EUPersonalID.getDocumentType())
            addDocumentType(PhotoID.getDocumentType())
        }

        val documentTableSpec = StorageTableSpec(
            name = "HardcodedIssuerDocument",
            supportPartitions = true,
            supportExpiration = false
        )
    }

    override suspend fun register(): Registration {
        checkClientId()
        val documentId = createIssuerDocument(
            IssuerDocument(
                RegistrationResponse(false),
                DocumentCondition.PROOFING_REQUIRED,
                mutableMapOf(),
                null,
                mutableListOf()
            )
        )
        return RegistrationState(documentId)
    }

    override suspend fun getConfiguration(): IssuingAuthorityConfiguration {
        checkClientId()
        return getConfiguration(authorityId)
    }

    override suspend fun completeRegistration(registration: Registration) {
        checkClientId()
        val registrationState = registration as RegistrationState
        updateIssuerDocument(
            registrationState.documentId,
            IssuerDocument(
                registrationState.response!!,
                DocumentCondition.PROOFING_REQUIRED,
                mutableMapOf(),           // collectedEvidence - initially empty
                null,  // no initial document configuration
                mutableListOf()           // cpoRequests - initially empty
            ))
    }

    override suspend fun getState(documentId: String): DocumentState {
        checkClientId()
        val now = Clock.System.now()

        if (!issuerDocumentExists(documentId)) {
            return DocumentState(
                now,
                DocumentCondition.NO_SUCH_DOCUMENT,
                0,
                0
            )
        }

        val issuerDocument = loadIssuerDocument(documentId)

        if (issuerDocument.state == DocumentCondition.PROOFING_PROCESSING) {
            issuerDocument.state =
                if (checkEvidence(issuerDocument.collectedEvidence)) {
                    DocumentCondition.CONFIGURATION_AVAILABLE
                } else {
                    DocumentCondition.PROOFING_FAILED
                }
            updateIssuerDocument(documentId, issuerDocument)
        }

        // This information is helpful for when using the admin web interface.
        Logger.i(TAG, "Returning state for clientId=$clientId " +
                "issuingAuthorityId=$authorityId documentId=$documentId")

        // We create and sign Credential requests immediately so numPendingCredentialRequests is
        // always 0
        return DocumentState(
            now,
            issuerDocument.state,
            0,
            issuerDocument.simpleCredentialRequests.count()
        )
    }

    override suspend fun proof(documentId: String): Proofing {
        checkClientId()
        val issuerDocument = loadIssuerDocument(documentId)
        issuerDocument.state = DocumentCondition.PROOFING_PROCESSING
        issuerDocument.collectedEvidence.clear()
        // TODO: propagate developer mode
        val settings = ProvisioningBackendSettings(BackendEnvironment.getInterface(Configuration::class)!!)
        return ProofingState(documentId, authorityId, settings.developerMode)
    }

    override suspend fun completeProof(proofing: Proofing) {
        checkClientId()
        val state = proofing as ProofingState
        val issuerDocument = loadIssuerDocument(state.documentId)
        issuerDocument.state = DocumentCondition.PROOFING_PROCESSING
        issuerDocument.collectedEvidence.putAll(state.evidence)
        updateIssuerDocument(state.documentId, issuerDocument)
    }

    override suspend fun getDocumentConfiguration(documentId: String): DocumentConfiguration {
        checkClientId()
        try {
            val issuerDocument = loadIssuerDocument(documentId)
            check(issuerDocument.state == DocumentCondition.CONFIGURATION_AVAILABLE)
            issuerDocument.state = DocumentCondition.READY
            if (issuerDocument.documentConfiguration == null) {
                issuerDocument.documentConfiguration =
                    generateDocumentConfiguration(issuerDocument.collectedEvidence)
            }
            updateIssuerDocument(documentId, issuerDocument)
            return issuerDocument.documentConfiguration!!
        } catch (err: Throwable) {
            err.printStackTrace()
            throw err
        }
    }

    override suspend fun requestCredentials(documentId: String): RequestCredentials {
        checkClientId()
        val issuerDocument = loadIssuerDocument(documentId)
        check(issuerDocument.state == DocumentCondition.READY)

        val storage = BackendEnvironment.getTable(AuthenticationState.walletAppCapabilitiesTableSpec)
        val walletApplicationCapabilities = storage.get(clientId)?.let {
                WalletApplicationCapabilities.fromCbor(it.toByteArray())
            } ?: throw IllegalStateException("WalletApplicationCapabilities not found")

        val credentialConfiguration = defaultCredentialConfiguration(
            documentId,
            walletApplicationCapabilities,
            issuerDocument.collectedEvidence
        )
        return RequestCredentialsState(documentId, credentialConfiguration)
    }

    override suspend fun completeRequestCredentials(requestCredentials: RequestCredentials) {
        checkClientId()
        val state = requestCredentials as RequestCredentialsState
        val issuerDocument = loadIssuerDocument(state.documentId)
        for (request in state.credentialRequests) {
            // Skip if we already have a request for the authentication key
            if (hasCpoRequestForAuthenticationKey(issuerDocument,
                    request.secureAreaBoundKeyAttestation.publicKey)) {
                continue
            }
            val authenticationKey = request.secureAreaBoundKeyAttestation.publicKey
            val presentationData = createPresentationData(
                BackendEnvironment.get(coroutineContext),
                state.format!!,
                issuerDocument.documentConfiguration!!,
                authenticationKey
            )
            val simpleCredentialRequest = SimpleCredentialRequest(
                authenticationKey,
                CredentialFormat.MDOC_MSO,
                presentationData,
            )
            issuerDocument.simpleCredentialRequests.add(simpleCredentialRequest)
        }
        updateIssuerDocument(state.documentId, issuerDocument)
    }

    override suspend fun getCredentials(documentId: String): List<CredentialData> {
        checkClientId()
        val now = Clock.System.now()
        val validFrom = now
        val validUntil = now + 30.days
        val issuerDocument = loadIssuerDocument(documentId)
        val availableCPOs = mutableListOf<CredentialData>()
        for (cpoRequest in issuerDocument.simpleCredentialRequests) {
            availableCPOs.add(
                CredentialData(
                    cpoRequest.authenticationKey,
                    validFrom,
                    validUntil,
                    cpoRequest.format,
                    cpoRequest.data,
                )
            )
        }
        issuerDocument.simpleCredentialRequests.clear()
        updateIssuerDocument(documentId, issuerDocument)
        return availableCPOs
    }

    override suspend fun developerModeRequestUpdate(
        documentId: String,
        requestRemoteDeletion: Boolean,
        notifyApplicationOfUpdate: Boolean
    ) {
        checkClientId()
        val settings = ProvisioningBackendSettings(BackendEnvironment.getInterface(Configuration::class)!!)
        val prefix = "issuingAuthority.$authorityId"
        val type = settings.getString("$prefix.type") ?: TYPE_DRIVING_LICENSE

        if (!settings.developerMode) {
            throw IllegalStateException("Must be in developer mode for this feature to work")
        }

        if (requestRemoteDeletion) {
            deleteIssuerDocument(documentId, notifyApplicationOfUpdate)
        } else {
            val issuerDocument = loadIssuerDocument(documentId)
            issuerDocument.state = DocumentCondition.CONFIGURATION_AVAILABLE

            // The update consists of just slapping an extra 0 at the end of `administrative_number`
            val namespace = when (type) {
                TYPE_DRIVING_LICENSE -> MDL_NAMESPACE
                TYPE_EU_PID -> EUPID_NAMESPACE
                TYPE_PHOTO_ID -> PHOTO_ID_NAMESPACE
                else -> throw IllegalArgumentException("Unknown type $type")
            }
            val newAdministrativeNumber = try {
                issuerDocument.documentConfiguration!!.mdocConfiguration!!.staticData
                    .getDataElementString(namespace, "administrative_number")
            } catch (e: Throwable) {
                ""
            } + "0"

            val builder = NameSpacedData.Builder(
                issuerDocument.documentConfiguration!!.mdocConfiguration!!.staticData
            )
            builder.putEntryString(
                namespace,
                "administrative_number",
                newAdministrativeNumber
            )

            issuerDocument.documentConfiguration = DocumentConfiguration(
                issuerDocument.documentConfiguration!!.displayName,
                issuerDocument.documentConfiguration!!.typeDisplayName,
                issuerDocument.documentConfiguration!!.cardArt,
                issuerDocument.documentConfiguration!!.requireUserAuthenticationToViewDocument,
                MdocDocumentConfiguration(
                    issuerDocument.documentConfiguration!!.mdocConfiguration!!.docType,
                    builder.build()
                ),
                issuerDocument.documentConfiguration!!.sdJwtVcDocumentConfiguration,
                issuerDocument.documentConfiguration!!.directAccessConfiguration
            )
            updateIssuerDocument(documentId, issuerDocument, notifyApplicationOfUpdate)
        }
    }

    suspend fun administrativeActionUpdateAdministrativeNumber(
        documentId: String,
        administrativeNumber: String
    ) {
        val settings = ProvisioningBackendSettings(BackendEnvironment.getInterface(Configuration::class)!!)
        val prefix = "issuingAuthority.$authorityId"
        val type = settings.getString("$prefix.type") ?: TYPE_DRIVING_LICENSE

        val issuerDocument = loadIssuerDocument(documentId)
        issuerDocument.state = DocumentCondition.CONFIGURATION_AVAILABLE

        val builder = NameSpacedData.Builder(
            issuerDocument.documentConfiguration!!.mdocConfiguration!!.staticData
        )
        val namespace = when (type) {
            TYPE_DRIVING_LICENSE -> MDL_NAMESPACE
            TYPE_EU_PID -> EUPID_NAMESPACE
            TYPE_PHOTO_ID -> PHOTO_ID_NAMESPACE
            else -> throw IllegalArgumentException("Unknown type $type")
        }
        builder.putEntryString(
            namespace,
            "administrative_number",
            administrativeNumber
        )
        issuerDocument.documentConfiguration = DocumentConfiguration(
            issuerDocument.documentConfiguration!!.displayName,
            issuerDocument.documentConfiguration!!.typeDisplayName,
            issuerDocument.documentConfiguration!!.cardArt,
            issuerDocument.documentConfiguration!!.requireUserAuthenticationToViewDocument,
            MdocDocumentConfiguration(
                issuerDocument.documentConfiguration!!.mdocConfiguration!!.docType,
                builder.build()
            ),
            issuerDocument.documentConfiguration!!.sdJwtVcDocumentConfiguration,
            issuerDocument.documentConfiguration!!.directAccessConfiguration
        )
        updateIssuerDocument(documentId, issuerDocument, true)
    }

    private fun createPresentationData(
        env: BackendEnvironment,
        format: CredentialFormat,
        documentConfiguration: DocumentConfiguration,
        authenticationKey: EcPublicKey
    ): ByteArray {
        return when (format) {
            CredentialFormat.MDOC_MSO -> createPresentationDataMdoc(env, documentConfiguration, authenticationKey, false)
            CredentialFormat.SD_JWT_VC -> createPresentationDataSdJwt(env, documentConfiguration, authenticationKey)
            CredentialFormat.DirectAccess -> createPresentationDataMdoc(env, documentConfiguration, authenticationKey, true)
        }
    }

    private fun createPresentationDataMdoc(
        env: BackendEnvironment,
        documentConfiguration: DocumentConfiguration,
        authenticationKey: EcPublicKey,
        isDirectAccess: Boolean
    ): ByteArray {
        val now = Clock.System.now()

        val settings = ProvisioningBackendSettings(env.getInterface(Configuration::class)!!)
        val prefix = "issuingAuthority.$authorityId"
        val type = settings.getString("$prefix.type") ?: TYPE_DRIVING_LICENSE

        // Create AuthKeys and MSOs, make sure they're valid for 30 days. Also make
        // sure to not use fractional seconds as 18013-5 calls for this (clauses 7.1
        // and 9.1.2.4)
        //
        val timeSigned = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validFrom = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validUntil = validFrom + 30.days

        // Generate an MSO and issuer-signed data for this authentication key.
        val docType = when (type) {
            TYPE_DRIVING_LICENSE -> MDL_DOCTYPE
            TYPE_EU_PID -> EUPID_DOCTYPE
            TYPE_PHOTO_ID -> PHOTO_ID_DOCTYPE
            else -> throw IllegalArgumentException("Unknown type $type")
        }
        val msoGenerator = MobileSecurityObjectGenerator(
            Algorithm.SHA256,
            docType,
            authenticationKey
        )
        msoGenerator.setValidityInfo(timeSigned, validFrom, validUntil, null)
        val randomProvider = Random.Default
        val staticData = if (isDirectAccess) {
            documentConfiguration.directAccessConfiguration!!.staticData
        } else {
            documentConfiguration.mdocConfiguration!!.staticData
        }
        val issuerNameSpaces = MdocUtil.generateIssuerNameSpaces(
            staticData,
            randomProvider,
            16,
            null
        )
        for (nameSpaceName in issuerNameSpaces.keys) {
            val digests = MdocUtil.calculateDigestsForNameSpace(
                nameSpaceName,
                issuerNameSpaces,
                Algorithm.SHA256
            )
            msoGenerator.addDigestIdsForNamespace(nameSpaceName, digests)
        }

        val resources = env.getInterface(Resources::class)!!
        val documentSigningKeyCert = X509Cert.fromPem(
            resources.getStringResource("ds_certificate.pem")!!)
        val documentSigningKey = EcPrivateKey.fromPem(
            resources.getStringResource("ds_private_key.pem")!!,
            documentSigningKeyCert.ecPublicKey
        )

        val mso = msoGenerator.generate()
        val taggedEncodedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(mso)))
        val protectedHeaders = mapOf<CoseLabel, DataItem>(Pair(
            CoseNumberLabel(Cose.COSE_LABEL_ALG),
            Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
        ))
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(Pair(
            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
            X509CertChain(listOf(
                X509Cert(documentSigningKeyCert.encodedCertificate))
            ).toDataItem()
        ))
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                documentSigningKey,
                taggedEncodedMso,
                true,
                Algorithm.ES256,
                protectedHeaders,
                unprotectedHeaders
            ).toDataItem()
        )

        val issuerProvidedAuthenticationData = if (isDirectAccess) {
            val pemEncodedCert =
                resources.getStringResource("owf_identity_credential_reader_cert.pem")!!
            val trustedReaderCert = X509Cert.fromPem(pemEncodedCert)
            val readerAuth = buildCborArray {
                add((trustedReaderCert.ecPublicKey as EcPublicKeyDoubleCoordinate).asUncompressedPointEncoding)
            }

            val digestIdMappingItem = buildCborMap {
                for ((namespace, bytesList) in issuerNameSpaces) {
                    putArray(namespace).let { innerBuilder ->
                        bytesList.forEach { encodedIssuerSignedItemMetadata ->
                            innerBuilder.add(RawCbor(encodedIssuerSignedItemMetadata))
                        }
                    }
                }
            }
            Cbor.encode(
                buildCborMap {
                    put("issuerNameSpaces", digestIdMappingItem)
                    put("issuerAuth", RawCbor(encodedIssuerAuth))
                    put("readerAccess", readerAuth)
                }
            )
        } else {
            StaticAuthDataGenerator(
                issuerNameSpaces,
                encodedIssuerAuth
            ).generate()
        }

        return issuerProvidedAuthenticationData
    }

    private fun createPresentationDataSdJwt(
        env: BackendEnvironment,
        documentConfiguration: DocumentConfiguration,
        authenticationKey: EcPublicKey
    ): ByteArray {
        // For now, just use the mdoc data element names and pretty print its value
        //
        val identityAttributes = buildJsonObject {
            for (nsName in documentConfiguration.mdocConfiguration!!.staticData.nameSpaceNames) {
                for (deName in documentConfiguration.mdocConfiguration!!.staticData.getDataElementNames(nsName)) {
                    val value = Cbor.decode(
                        documentConfiguration.mdocConfiguration!!.staticData.getDataElement(nsName, deName)
                    )
                    // TODO: This will need support for bstr once we support that in a DocumentType.
                    when (value) {
                        is Tstr -> put(deName, value.asTstr)
                        is CborInt -> put(deName, value.asNumber)
                        is Simple -> {
                            if (value == Simple.TRUE) {
                                put(deName, true)
                            } else if (value == Simple.FALSE){
                                put(deName, false)
                            } else {
                                put(deName, value.toString())
                            }
                        }
                        else -> {
                            put(
                                deName,
                                Cbor.toDiagnostics(
                                    value,
                                    setOf(
                                        DiagnosticOption.PRETTY_PRINT,
                                        DiagnosticOption.BSTR_PRINT_LENGTH,
                                        DiagnosticOption.EMBEDDED_CBOR
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }

        val now = Clock.System.now()
        val timeSigned = now
        val validFrom = now
        val validUntil = validFrom + 30.days

        // Just use the mdoc Document Signing key for now
        //
        val resources = env.getInterface(Resources::class)!!
        val documentSigningKeyCert = X509Cert.fromPem(
            resources.getStringResource("ds_certificate.pem")!!)
        val documentSigningKey = EcPrivateKey.fromPem(
            resources.getStringResource("ds_private_key.pem")!!,
            documentSigningKeyCert.ecPublicKey
        )

        val sdJwt = SdJwt.create(
            issuerKey = documentSigningKey,
            issuerAlgorithm = documentSigningKey.curve.defaultSigningAlgorithmFullySpecified,
            issuerCertChain = X509CertChain(listOf(documentSigningKeyCert)),
            kbKey = authenticationKey,
            claims = identityAttributes,
            nonSdClaims = buildJsonObject {
                put("iss", JsonPrimitive("https://example-issuer.com"))
                put("iat", JsonPrimitive(timeSigned.epochSeconds))
                put("nbf", JsonPrimitive(validFrom.epochSeconds))
                put("exp", JsonPrimitive(validUntil.epochSeconds))
            },
        )

        return sdJwt.compactSerialization.toByteArray()
    }

    private suspend fun generateDocumentConfiguration(
        collectedEvidence: Map<String, EvidenceResponse>
    ): DocumentConfiguration {
        val settings = ProvisioningBackendSettings(BackendEnvironment.getInterface(Configuration::class)!!)
        val prefix = "issuingAuthority.$authorityId"
        val type = settings.getString("$prefix.type") ?: TYPE_DRIVING_LICENSE
        return when (type) {
            TYPE_DRIVING_LICENSE -> generateMdlDocumentConfiguration(collectedEvidence)
            TYPE_EU_PID -> generateEuPidDocumentConfiguration(collectedEvidence)
            TYPE_PHOTO_ID -> generatePhotoIdDocumentConfiguration(collectedEvidence)
            else -> throw IllegalArgumentException("Unknown type $type")
        }
    }

    private suspend fun generateEuPidDocumentConfiguration(
        collectedEvidence: Map<String, EvidenceResponse>
    ): DocumentConfiguration {
        val now = Clock.System.now()
        val issueDate = now
        val resources = BackendEnvironment.getInterface(Resources::class)!!
        val settings = ProvisioningBackendSettings(BackendEnvironment.getInterface(Configuration::class)!!)
        val expiryDate = now + 365.days * 5

        val prefix = "issuingAuthority.$authorityId"
        val artPath = settings.getString("${prefix}.cardArt") ?: "default/card_art.png"
        val issuingAuthorityName = settings.getString("${prefix}.name") ?: "Default Issuer"
        val art = resources.getRawResource(artPath)!!

        val credType = documentTypeRepository.getDocumentTypeForMdoc(EUPID_DOCTYPE)!!
        val staticData: NameSpacedData

        val path = (collectedEvidence["path"] as EvidenceResponseQuestionMultipleChoice).answerId
        if (path == "hardcoded") {
            val imageFormat = collectedEvidence["devmode_image_format"]
            val jpeg2k = imageFormat is EvidenceResponseQuestionMultipleChoice &&
                    imageFormat.answerId == "devmode_image_format_jpeg2000"
            staticData = fillInSampleData(resources, jpeg2k, credType).build()
        } else { // todo
            val icaoPassiveData = collectedEvidence["passive"]
            val icaoTunnelData = collectedEvidence["tunnel"]
            val mrtdData = if (icaoTunnelData is EvidenceResponseIcaoNfcTunnelResult)
                MrtdNfcData(icaoTunnelData.dataGroups, icaoTunnelData.securityObject)
            else if (icaoPassiveData is EvidenceResponseIcaoPassiveAuthentication)
                MrtdNfcData(icaoPassiveData.dataGroups, icaoPassiveData.securityObject)
            else
                throw IllegalStateException("Should not happen")
            val decoded = MrtdNfcDataDecoder().decode(mrtdData)
            val firstName = decoded.firstName
            val lastName = decoded.lastName
            val sex = when (decoded.gender) {
                "MALE" -> 1L
                "FEMALE" -> 2L
                else -> 0L
            }
            val timeZone = TimeZone.currentSystemDefault()
            val dateOfBirth = LocalDate.parse(input = decoded.dateOfBirth,
                format = LocalDate.Format {
                    // date of birth cannot be in future
                    yearTwoDigits(now.toLocalDateTime(timeZone).year - 99)
                    monthNumber()
                    day()
                })
            val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)
            // over 18/21 is calculated purely based on calendar date (not based on the birth time zone)
            val ageOver18 = now > dateOfBirthInstant.plus(18, DateTimeUnit.YEAR, timeZone)
            val ageOver21 = now > dateOfBirthInstant.plus(21, DateTimeUnit.YEAR, timeZone)

            // Make sure we set at least all the mandatory data elements
            staticData = NameSpacedData.Builder()
                .putEntryString(EUPID_NAMESPACE, "family_name", lastName)
                .putEntryString(EUPID_NAMESPACE, "given_name", firstName)
                .putEntry(EUPID_NAMESPACE, "birth_date",
                    Cbor.encode(dateOfBirth.toDataItemFullDate()))
                .putEntryNumber(EUPID_NAMESPACE, "age_in_years",
                    dateOfBirth.yearsUntil(now.toLocalDateTime(timeZone).date).toLong())
                .putEntryNumber(EUPID_NAMESPACE, "age_birth_year", dateOfBirth.year.toLong())
                .putEntryBoolean(EUPID_NAMESPACE, "age_over_18", ageOver18)
                .putEntryBoolean(EUPID_NAMESPACE, "age_over_21", ageOver21)
                // not included: family_name_birth, given_name_birth, birth_place, birth_country,
                // birth_state, birth_city, resident_address, resident_country, resident_state,
                // resident_city, resident_postal_code, resident_street, resident_house_number,
                // issuing_jurisdiction,
                .putEntryNumber(EUPID_NAMESPACE, "gender", sex)
                .putEntryString(EUPID_NAMESPACE, "nationality", "ZZ")
                .putEntry(EUPID_NAMESPACE, "issuance_date",
                    Cbor.encode(issueDate.toDataItemDateTimeString()))
                .putEntry(EUPID_NAMESPACE, "expiry_date",
                    Cbor.encode(expiryDate.toDataItemDateTimeString())
                )
                .putEntryString(EUPID_NAMESPACE, "issuing_authority",
                    issuingAuthorityName)
                .putEntryString(EUPID_NAMESPACE, "document_number", "1234567890")
                .putEntryString(EUPID_NAMESPACE, "administrative_number", "123456789")
                .putEntryString(EUPID_NAMESPACE, "issuing_country", "ZZ")
                .build()
        }

        val firstName = staticData.getDataElementString(EUPID_NAMESPACE, "given_name")
        return DocumentConfiguration(
            displayName = "$firstName's Personal ID",
            typeDisplayName = "EU Personal ID",
            cardArt = art.toByteArray(),
            requireUserAuthenticationToViewDocument =
                settings.getBool("${prefix}.requireUserAuthenticationToViewDocument"),
            mdocConfiguration = MdocDocumentConfiguration(
                docType = EUPID_DOCTYPE,
                staticData = staticData,
            ),
            sdJwtVcDocumentConfiguration = SdJwtVcDocumentConfiguration(
                vct = EUPersonalID.EUPID_VCT,
                keyBound = true
            ),
            directAccessConfiguration = null
        )
    }

    private suspend fun generateMdlDocumentConfiguration(
        collectedEvidence: Map<String, EvidenceResponse>
    ): DocumentConfiguration {
        val now = Clock.System.now()
        val issueDate = now
        val resources = BackendEnvironment.getInterface(Resources::class)!!
        val settings = ProvisioningBackendSettings(BackendEnvironment.getInterface(Configuration::class)!!)
        val expiryDate = now + 365.days * 5

        val prefix = "issuingAuthority.$authorityId"
        val artPath = settings.getString("${prefix}.cardArt") ?: "default/card_art.png"
        val issuingAuthorityName = settings.getString("${prefix}.name") ?: "Default Issuer"
        val art = resources.getRawResource(artPath)!!

        val credType = documentTypeRepository.getDocumentTypeForMdoc(MDL_DOCTYPE)!!
        val staticData: NameSpacedData
        val staticDataCompressed: NameSpacedData

        val path = (collectedEvidence["path"] as EvidenceResponseQuestionMultipleChoice).answerId
        if (path == "hardcoded") {
            val imageFormat = collectedEvidence["devmode_image_format"]
            val jpeg2k = imageFormat is EvidenceResponseQuestionMultipleChoice &&
                    imageFormat.answerId == "devmode_image_format_jpeg2000"
            staticData = fillInSampleData(resources, jpeg2k, credType).build()
            staticDataCompressed = staticData
        } else {
            val icaoPassiveData = collectedEvidence["passive"]
            val icaoTunnelData = collectedEvidence["tunnel"]
            val mrtdData = if (icaoTunnelData is EvidenceResponseIcaoNfcTunnelResult)
                MrtdNfcData(icaoTunnelData.dataGroups, icaoTunnelData.securityObject)
            else if (icaoPassiveData is EvidenceResponseIcaoPassiveAuthentication)
                MrtdNfcData(icaoPassiveData.dataGroups, icaoPassiveData.securityObject)
            else
                throw IllegalStateException("Should not happen")
            val decoded = MrtdNfcDataDecoder().decode(mrtdData)
            val firstName = decoded.firstName
            val lastName = decoded.lastName
            val sex = when (decoded.gender) {
                "MALE" -> 1L
                "FEMALE" -> 2L
                else -> 0L
            }
            val timeZone = TimeZone.currentSystemDefault()
            val dateOfBirth = LocalDate.parse(input = decoded.dateOfBirth,
                format = LocalDate.Format {
                    // date of birth cannot be in future
                    yearTwoDigits(now.toLocalDateTime(timeZone).year - 99)
                    monthNumber()
                    day()
                })
            val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)
            // over 18/21 is calculated purely based on calendar date (not based on the birth time zone)
            val ageOver18 = now > dateOfBirthInstant.plus(18, DateTimeUnit.YEAR, timeZone)
            val ageOver21 = now > dateOfBirthInstant.plus(21, DateTimeUnit.YEAR, timeZone)
            val portrait = decoded.photo ?: resources.getRawResource("img_erika_portrait.jpg")!!
            val portraitCompressed = decoded.photo ?: resources.getRawResource("img_erika_portrait_compressed.jpg")!!
            val signatureOrUsualMark = decoded.signature ?: resources.getRawResource("img_erika_signature.jpg")!!

            // Make sure we set at least all the mandatory data elements
            //
            val nsBuilder = NameSpacedData.Builder()
                .putEntryString(MDL_NAMESPACE, "given_name", firstName)
                .putEntryString(MDL_NAMESPACE, "family_name", lastName)
                .putEntry(MDL_NAMESPACE, "birth_date",
                    Cbor.encode(dateOfBirth.toDataItemFullDate()))
                .putEntryByteString(MDL_NAMESPACE, "signature_usual_mark",
                    signatureOrUsualMark.toByteArray())
                .putEntryNumber(MDL_NAMESPACE, "sex", sex)
                .putEntry(MDL_NAMESPACE, "issue_date",
                    Cbor.encode(issueDate.toDataItemDateTimeString()))
                .putEntry(MDL_NAMESPACE, "expiry_date",
                    Cbor.encode(expiryDate.toDataItemDateTimeString())
                )
                // TODO
                .putEntryString(MDL_NAMESPACE, "issuing_authority",
                    issuingAuthorityName)
                .putEntryString(MDL_NAMESPACE, "issuing_country", "ZZ")
                .putEntryString(MDL_NAMESPACE, "un_distinguishing_sign", "UTO")
                .putEntryString(MDL_NAMESPACE, "document_number", "1234567890")
                .putEntryString(MDL_NAMESPACE, "administrative_number", "123456789")
                .putEntry(MDL_NAMESPACE, "driving_privileges", Cbor.encode(buildCborArray {}))

                .putEntryBoolean(MDL_NAMESPACE, "age_over_18", ageOver18)
                .putEntryBoolean(MDL_NAMESPACE, "age_over_21", ageOver21)

                .putEntryString(AAMVA_NAMESPACE, "DHS_compliance", "F")
                .putEntryNumber(AAMVA_NAMESPACE, "EDL_credential", 1)
                .putEntryNumber(AAMVA_NAMESPACE, "sex", sex)

            staticData = nsBuilder
                .putEntryByteString(MDL_NAMESPACE, "portrait", portrait.toByteArray())
                .build()
            staticDataCompressed = nsBuilder
                .putEntryByteString(MDL_NAMESPACE, "portrait", portraitCompressed.toByteArray())
                .build()
        }

        var directAccessConfiguration: DirectAccessDocumentConfiguration? = null
        if (collectedEvidence["directAccess"] != null) {
            val includeDirectAccess = (collectedEvidence["directAccess"] as EvidenceResponseQuestionMultipleChoice).answerId
            directAccessConfiguration = if (includeDirectAccess == "yes") {
                DirectAccessDocumentConfiguration(
                    docType = MDL_DOCTYPE,
                    staticData = staticDataCompressed,
                )
            } else null
        }


        val firstName = staticData.getDataElementString(MDL_NAMESPACE, "given_name")
        return DocumentConfiguration(
            displayName = "$firstName's Driving License",
            typeDisplayName = "Driving License",
            cardArt = art.toByteArray(),
            requireUserAuthenticationToViewDocument =
                settings.getBool("${prefix}.requireUserAuthenticationToViewDocument"),
            mdocConfiguration = MdocDocumentConfiguration(
                docType = MDL_DOCTYPE,
                staticData = staticData,
            ),
            sdJwtVcDocumentConfiguration = null,
            directAccessConfiguration = directAccessConfiguration
        )
    }

    private suspend fun generatePhotoIdDocumentConfiguration(
        collectedEvidence: Map<String, EvidenceResponse>
    ): DocumentConfiguration {
        val now = Clock.System.now()
        val issueDate = now
        val resources = BackendEnvironment.getInterface(Resources::class)!!
        val settings = ProvisioningBackendSettings(BackendEnvironment.getInterface(Configuration::class)!!)
        val expiryDate = now + 365.days * 5

        val prefix = "issuingAuthority.$authorityId"
        val artPath = settings.getString("${prefix}.cardArt") ?: "default/card_art.png"
        val issuingAuthorityName = settings.getString("${prefix}.name") ?: "Default Issuer"
        val art = resources.getRawResource(artPath)!!

        val credType = documentTypeRepository.getDocumentTypeForMdoc(PHOTO_ID_DOCTYPE)!!
        val staticData: NameSpacedData

        val path = (collectedEvidence["path"] as EvidenceResponseQuestionMultipleChoice).answerId
        if (path == "hardcoded") {
            val imageFormat = collectedEvidence["devmode_image_format"]
            val jpeg2k = imageFormat is EvidenceResponseQuestionMultipleChoice &&
                    imageFormat.answerId == "devmode_image_format_jpeg2000"
            staticData = fillInSampleData(resources, jpeg2k, credType).build()
        } else {
            val icaoPassiveData = collectedEvidence["passive"]
            val icaoTunnelData = collectedEvidence["tunnel"]
            val mrtdData = if (icaoTunnelData is EvidenceResponseIcaoNfcTunnelResult)
                MrtdNfcData(icaoTunnelData.dataGroups, icaoTunnelData.securityObject)
            else if (icaoPassiveData is EvidenceResponseIcaoPassiveAuthentication)
                MrtdNfcData(icaoPassiveData.dataGroups, icaoPassiveData.securityObject)
            else
                throw IllegalStateException("Should not happen")
            val decoded = MrtdNfcDataDecoder().decode(mrtdData)
            val firstName = decoded.firstName
            val lastName = decoded.lastName
            val sex = when (decoded.gender) {
                "MALE" -> 1L
                "FEMALE" -> 2L
                else -> 0L
            }
            val timeZone = TimeZone.currentSystemDefault()
            val dateOfBirth = LocalDate.parse(input = decoded.dateOfBirth,
                format = LocalDate.Format {
                    // date of birth cannot be in future
                    yearTwoDigits(now.toLocalDateTime(timeZone).year - 99)
                    monthNumber()
                    day()
                })
            val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)
            // over 18/21 is calculated purely based on calendar date (not based on the birth time zone)
            val ageOver18 = now > dateOfBirthInstant.plus(18, DateTimeUnit.YEAR, timeZone)
            val ageOver21 = now > dateOfBirthInstant.plus(21, DateTimeUnit.YEAR, timeZone)
            val portrait = decoded.photo ?: resources.getRawResource("img_erika_portrait.jpg")!!
            val signatureOrUsualMark =
                decoded.signature ?: resources.getRawResource("img_erika_signature.jpg")!!

            // Make sure we set at least all the mandatory data elements
            //
            staticData = NameSpacedData.Builder().apply {
                putEntryString(ISO_23220_2_NAMESPACE, "given_name", firstName)
                putEntryString(ISO_23220_2_NAMESPACE, "family_name", lastName)
                putEntry(
                    ISO_23220_2_NAMESPACE, "birthdate",
                    Cbor.encode(dateOfBirth.toDataItemFullDate())
                )
                putEntryByteString(ISO_23220_2_NAMESPACE, "portrait", portrait.toByteArray())
                putEntryNumber(ISO_23220_2_NAMESPACE, "sex", sex)
                putEntry(
                    ISO_23220_2_NAMESPACE, "issue_date",
                    Cbor.encode(issueDate.toDataItemDateTimeString())
                )
                putEntry(
                    ISO_23220_2_NAMESPACE, "expiry_date",
                    Cbor.encode(expiryDate.toDataItemDateTimeString())
                )
                putEntryString(
                    ISO_23220_2_NAMESPACE, "issuing_authority_unicode",
                    issuingAuthorityName
                )
                putEntryString(ISO_23220_2_NAMESPACE, "issuing_country", "ZZ")
                putEntryString(ISO_23220_2_NAMESPACE, "document_number", "1234567890")
                putEntryString(PHOTO_ID_NAMESPACE, "administrative_number", "123456789")
                putEntryString(PHOTO_ID_NAMESPACE, "person_id", "24601")
                putEntryBoolean(ISO_23220_2_NAMESPACE, "age_over_18", ageOver18)
                putEntryBoolean(ISO_23220_2_NAMESPACE, "age_over_21", ageOver21)
                for (entry in mrtdData.dataGroups) {
                    putEntryByteString(
                        PhotoID.DTC_NAMESPACE,
                        "dtc_dg${entry.key}",
                        entry.value.toByteArray())
                }
                putEntryByteString(PhotoID.DTC_NAMESPACE, "dtc_sod", mrtdData.sod.toByteArray())
            }.build()
        }

        val firstName = staticData.getDataElementString(ISO_23220_2_NAMESPACE, "given_name")
        return DocumentConfiguration(
            displayName = "$firstName's Photo ID",
            typeDisplayName = "Photo ID",
            cardArt = art.toByteArray(),
            requireUserAuthenticationToViewDocument =
            settings.getBool("${prefix}.requireUserAuthenticationToViewDocument"),
            mdocConfiguration = MdocDocumentConfiguration(
                docType = PHOTO_ID_DOCTYPE,
                staticData = staticData,
            ),
            sdJwtVcDocumentConfiguration = null,
            directAccessConfiguration = null
        )
    }

    private fun fillInSampleData(
        resources: Resources,
        jpeg2k: Boolean,
        documentType: DocumentType
    ): NameSpacedData.Builder {
        val builder = NameSpacedData.Builder()
        for ((namespaceName, namespace) in documentType.mdocDocumentType!!.namespaces) {
            for ((dataElementName, dataElement) in namespace.dataElements) {
                if (dataElement.attribute.sampleValueMdoc != null) {
                    builder.putEntry(
                        namespaceName,
                        dataElementName,
                        Cbor.encode(dataElement.attribute.sampleValueMdoc!!)
                    )
                }
            }
        }

        if (documentType == documentTypeRepository.getDocumentTypeForMdoc(MDL_DOCTYPE)!!) {
            val portrait = resources.getRawResource(if (jpeg2k) {
                "img_erika_portrait.jpf"
            } else {
                "img_erika_portrait.jpg"
            })!!.toByteArray()
            val signatureOrUsualMark = resources.getRawResource(if (jpeg2k) {
                "img_erika_signature.jpf"
            } else {
                "img_erika_signature.jpg"
            })!!.toByteArray()
            builder
                .putEntryByteString(MDL_NAMESPACE, "portrait", portrait)
                .putEntryByteString(MDL_NAMESPACE, "signature_usual_mark", signatureOrUsualMark)
        } else if (documentType == documentTypeRepository.getDocumentTypeForMdoc(PHOTO_ID_DOCTYPE)!!) {
            val portrait = resources.getRawResource(if (jpeg2k) {
                "img_erika_portrait.jpf"
            } else {
                "img_erika_portrait.jpg"
            })!!.toByteArray()
            builder
                .putEntryByteString(ISO_23220_2_NAMESPACE, "portrait", portrait)
        }

        return builder
    }

    // TODO: b/393388152 - parameter unused.
    private fun checkEvidence(evidence: Map<String, EvidenceResponse>): Boolean {
        return true
    }

    private fun hasCpoRequestForAuthenticationKey(
        issuerDocument: IssuerDocument,
        authenticationKey: EcPublicKey
    ) : Boolean {
        for (cpoRequest in issuerDocument.simpleCredentialRequests) {
            if (cpoRequest.authenticationKey.equals(authenticationKey)) {
                return true
            }
        }
        return false
    }

    private suspend fun issuerDocumentExists(documentId: String): Boolean {
        val storage = BackendEnvironment.getTable(documentTableSpec)
        val encodedCbor = storage.get(partitionId = clientId, key = documentId)
        return encodedCbor != null
    }

    private suspend fun loadIssuerDocument(documentId: String): IssuerDocument {
        val storage = BackendEnvironment.getTable(documentTableSpec)
        val encodedCbor = storage.get(partitionId = clientId, key = documentId)
        if (encodedCbor == null) {
            // TODO: replace with (new) UnknownDocumentException
            throw Error("No such document")
        }
        return IssuerDocument.fromDataItem(Cbor.decode(encodedCbor.toByteArray()))
    }

    private suspend fun createIssuerDocument(document: IssuerDocument): String {
        val storage = BackendEnvironment.getTable(documentTableSpec)
        val bytes = Cbor.encode(document.toDataItem())
        return storage.insert(partitionId = clientId, key = null, data = ByteString(bytes))
    }

    private suspend fun deleteIssuerDocument(
        documentId: String,
        emitNotification: Boolean = true
    ) {
        val storage = BackendEnvironment.getTable(documentTableSpec)
        storage.delete(partitionId = clientId, key = documentId)
        if (emitNotification) {
            emit(IssuingAuthorityNotification(documentId))
        }
    }

    private suspend fun updateIssuerDocument(
        documentId: String,
        document: IssuerDocument,
        emitNotification: Boolean = true,
    ) {
        val storage = BackendEnvironment.getTable(documentTableSpec)
        val bytes = Cbor.encode(document.toDataItem())
        storage.update(partitionId = clientId, key = documentId, data = ByteString(bytes))
        if (emitNotification) {
            emit(IssuingAuthorityNotification(documentId))
        }
    }

    private suspend fun checkClientId() {
        check(clientId == RpcAuthContext.getClientId())
    }

    private fun parseDateOfBirth(date: String): LocalDate {
        return LocalDate.parse(date.substring(0, 10), format = LocalDate.Format {
            year()
            chars("-")
            monthNumber()
            chars("-")
            day()
        })
    }
}
