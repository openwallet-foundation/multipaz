@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.multipaz.golden

import kotlinx.datetime.LocalDate
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.consent.ConsentRiskAssessment
import org.multipaz.consent.ConsentRiskLevel
import org.multipaz.consent.ConsentRiskReason
import org.multipaz.consent.PresentmentPurpose
import org.multipaz.consent.defaultClassifyRisk
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.documenttype.knowntypes.SampleData
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.RequesterHistory
import org.multipaz.presentment.RequesterHistoryEntry
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.request.Iso18013RequesterIdentity
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.utopia.knowntypes.Loyalty
import org.multipaz.utopia.knowntypes.UtopiaBoardingPass
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

data class GoldenRequesterPair(
    val requester: Requester,
    val trustedRequesterIdentity: TrustedRequesterIdentity?,
)

class SwiftConsentGoldenEcosystem private constructor(
    val documentTypeRepository: DocumentTypeRepository,
    val documentStore: DocumentStore,
    val twoPhotoIdsDocumentStore: DocumentStore,
    val mdlDoc: Document,
    val photoIdDoc: Document,
    val pidDoc: Document,
    val wholesaleDoc: Document,
    val cinemaTicketDoc: Document,
    val paymentPlatinumDoc: Document,
    val paymentGoldDoc: Document,
    val boardingPassDoc: Document,
    val avDoc: Document,
    val rpCertChains: Map<String, X509CertChain>,
    val rpTrustMetadata: Map<String, TrustMetadata>,
    val encTargetKeys: Map<String, AsymmetricKey.X509Certified>,
    val encTargetTrustMetadata: Map<String, TrustMetadata>,
) {
    fun trustedProximityRequester(rpId: String): GoldenRequesterPair {
        val certChain = rpCertChains.getValue(rpId)
        val metadata = rpTrustMetadata.getValue(rpId)
        val identity = Iso18013RequesterIdentity(certChain = certChain)
        return GoldenRequesterPair(
            requester = Requester(requesterIdentities = listOf(identity)),
            trustedRequesterIdentity = TrustedRequesterIdentity(identity = identity, trustMetadata = metadata),
        )
    }

    fun trustedWebRequester(
        rpId: String,
        origin: String,
    ): GoldenRequesterPair = trustedWebRequester(
        rpId = rpId,
        origin = origin,
        clientId = "x509_san_dns:${origin.removePrefix("https://")}",
    )

    fun trustedWebRequester(
        rpId: String,
        origin: String,
        clientId: String,
    ): GoldenRequesterPair {
        val certChain = rpCertChains.getValue(rpId)
        val metadata = rpTrustMetadata.getValue(rpId)
        val identity = OpenID4VPRequesterIdentity(certChain = certChain, clientId = clientId)
        return GoldenRequesterPair(
            requester = Requester(
                requesterIdentities = listOf(identity),
                origin = origin,
            ),
            trustedRequesterIdentity = TrustedRequesterIdentity(identity = identity, trustMetadata = metadata),
        )
    }

    fun unverifiedProximityRequester(rpId: String = RP_UNVERIFIED_LOUNGE): GoldenRequesterPair {
        val certChain = rpCertChains.getValue(rpId)
        val identity = Iso18013RequesterIdentity(certChain = certChain)
        return GoldenRequesterPair(
            requester = Requester(requesterIdentities = listOf(identity)),
            trustedRequesterIdentity = null,
        )
    }

    fun anonymousProximityRequester(): GoldenRequesterPair =
        GoldenRequesterPair(requester = Requester(), trustedRequesterIdentity = null)

    fun unverifiedWebRequester(
        origin: String = "https://age-check.utopia-gaming.example.com",
    ): GoldenRequesterPair {
        val certChain = rpCertChains.getValue(RP_UNVERIFIED_GAMING)
        val identity = OpenID4VPRequesterIdentity(
            certChain = certChain,
            clientId = "x509_san_dns:${origin.removePrefix("https://")}"
        )
        return GoldenRequesterPair(
            requester = Requester(
                requesterIdentities = listOf(identity),
                origin = origin,
            ),
            trustedRequesterIdentity = null,
        )
    }

    fun anonymousWebRequester(
        origin: String = "https://age-check.utopia-gaming.example.com",
    ): GoldenRequesterPair =
        GoldenRequesterPair(requester = Requester(origin = origin), trustedRequesterIdentity = null)

    fun unverifiedAppRequester(appId: String): GoldenRequesterPair =
        GoldenRequesterPair(requester = Requester(appId = appId), trustedRequesterIdentity = null)

    private fun buildPresentmentSource(
        encryptionTargetRpId: String? = null,
        encryptionTargetTrusted: Boolean = true,
        store: DocumentStore = documentStore,
    ): SimplePresentmentSource {
        return SimplePresentmentSource(
            documentStore = store,
            documentTypeRepository = documentTypeRepository,
            resolveTrustFn = { requester ->
                val identity = requester.requesterIdentities.firstOrNull() ?: return@SimplePresentmentSource null
                if (encryptionTargetRpId != null && encryptionTargetTrusted) {
                    val encKey = encTargetKeys.getValue(encryptionTargetRpId)
                    if (identity.certChain.certificates.firstOrNull() == encKey.certChain.certificates.firstOrNull()) {
                        return@SimplePresentmentSource TrustedRequesterIdentity(
                            identity = identity,
                            trustMetadata = encTargetTrustMetadata.getValue(encryptionTargetRpId),
                        )
                    }
                }
                null
            },
            domainsMdocSignature = listOf("mdoc"),
            domainsKeylessSdJwt = listOf("sdjwt"),
            domainsKeyBoundSdJwt = listOf("sdjwt"),
        )
    }

    suspend fun executeDcql(dcqlJson: String): PresentmentData = executeDcql(
        dcqlJson = dcqlJson,
        transactionDataMap = emptyMap(),
    )

    suspend fun executeDcql(
        dcqlJson: String,
        transactionDataMap: Map<String, List<TransactionData<*>>>,
    ): PresentmentData = executeDcqlObject(
        dcqlObject = Json.parseToJsonElement(dcqlJson).jsonObject,
        transactionDataMap = transactionDataMap,
    )

    suspend fun executeDcqlObject(
        dcqlObject: JsonObject,
        transactionDataMap: Map<String, List<TransactionData<*>>> = emptyMap(),
    ): PresentmentData {
        val query = DcqlQuery.fromJson(dcql = dcqlObject)
        val source = buildPresentmentSource()
        return query.execute(
            presentmentSource = source,
            transactionDataMap = transactionDataMap,
        )
    }

    suspend fun executeCannedDcql(
        cannedRequest: SingleDocumentCannedRequest,
    ): PresentmentData = executeCannedDcql(
        cannedRequest = cannedRequest,
        transactionDataMap = emptyMap(),
    )

    suspend fun executeCannedDcql(
        cannedRequest: SingleDocumentCannedRequest,
        transactionDataMap: Map<String, List<TransactionData<*>>>,
    ): PresentmentData {
        val dcqlObject = checkNotNull(cannedRequest.mdocRequest).toDcql(emptyList())
        return executeDcqlObject(
            dcqlObject = dcqlObject,
            transactionDataMap = transactionDataMap,
        )
    }

    suspend fun executeDrivingLicenseCannedDcql(cannedRequestId: String): PresentmentData {
        val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == cannedRequestId }
        return executeCannedDcql(canned)
    }

    suspend fun executeAgeVerificationCannedDcql(cannedRequestId: String): PresentmentData {
        val canned = AgeVerification.getDocumentType().cannedRequests.first { it.id == cannedRequestId }
        return executeCannedDcql(canned)
    }

    suspend fun executeLoyaltyCannedDcql(cannedRequestId: String): PresentmentData {
        val canned = Loyalty.getDocumentType().cannedRequests.first { it.id == cannedRequestId }
        return executeCannedDcql(canned)
    }

    suspend fun executePaymentCannedDcql(
        cannedRequestId: String,
        payeeName: String,
        amount: Double,
        tipRequested: Boolean,
    ): PresentmentData {
        val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
            it.id == cannedRequestId
        }
        return executeCannedDcql(
            cannedRequest = canned,
            transactionDataMap = mapOf(
                "cred1" to listOf(
                    utopiaPaymentTransactionData(
                        payeeName = payeeName,
                        amount = amount,
                        tipRequested = tipRequested,
                    )
                )
            ),
        )
    }

    suspend fun executeTransitFareCannedDcql(cannedRequestId: String): PresentmentData {
        val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
            it.id == cannedRequestId
        }
        return executeCannedDcql(
            cannedRequest = canned,
            transactionDataMap = mapOf(
                "cred1" to listOf(
                    goldenTransactionData(
                        GoldenTransitFareTransaction,
                        GoldenTransitFareTransaction.sampleData,
                    )
                )
            ),
        )
    }

    suspend fun executeAp2MandateCannedDcql(cannedRequestId: String): PresentmentData {
        val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
            it.id == cannedRequestId
        }
        return executeCannedDcql(
            cannedRequest = canned,
            transactionDataMap = mapOf(
                "cred1" to listOf(
                    goldenTransactionData(
                        GoldenAp2MandateTransaction,
                        GoldenAp2MandateTransaction.sampleData,
                    )
                )
            ),
        )
    }

    suspend fun executeBorderCrossingEncryptionRequest(
        intentToRetain: Boolean,
        encryptionTargetRpId: String,
        encryptionTargetTrusted: Boolean,
    ): PresentmentData = executeBorderCrossingEncryptionRequest(
        intentToRetain = intentToRetain,
        encryptionTargetRpId = encryptionTargetRpId,
        encryptionTargetTrusted = encryptionTargetTrusted,
        includeRecipientCertificates = true,
    )

    suspend fun executeBorderCrossingEncryptionRequestMultipleChoices(
        intentToRetain: Boolean,
        encryptionTargetRpId: String,
        encryptionTargetTrusted: Boolean,
    ): PresentmentData = executeBorderCrossingEncryptionRequest(
        intentToRetain = intentToRetain,
        encryptionTargetRpId = encryptionTargetRpId,
        encryptionTargetTrusted = encryptionTargetTrusted,
        includeRecipientCertificates = true,
        multiplePhotoIds = true,
    )

    suspend fun executeBorderCrossingEncryptionRequest(
        intentToRetain: Boolean,
        encryptionTargetRpId: String,
        encryptionTargetTrusted: Boolean,
        includeRecipientCertificates: Boolean,
    ): PresentmentData = executeBorderCrossingEncryptionRequest(
        intentToRetain = intentToRetain,
        encryptionTargetRpId = encryptionTargetRpId,
        encryptionTargetTrusted = encryptionTargetTrusted,
        includeRecipientCertificates = includeRecipientCertificates,
        multiplePhotoIds = false,
    )

    private suspend fun executeBorderCrossingEncryptionRequest(
        intentToRetain: Boolean,
        encryptionTargetRpId: String,
        encryptionTargetTrusted: Boolean,
        includeRecipientCertificates: Boolean,
        multiplePhotoIds: Boolean,
    ): PresentmentData {
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val encKey = encTargetKeys.getValue(encryptionTargetRpId)
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(
                    PhotoID.ISO_23220_2_NAMESPACE to mapOf(
                        "given_name" to intentToRetain,
                        "family_name" to intentToRetain,
                        "portrait" to intentToRetain,
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
                        recipientPublicKey = encKey.publicKey as EcPublicKey,
                        recipientCertificates = if (includeRecipientCertificates) {
                            encKey.certChain.certificates
                        } else {
                            emptyList()
                        },
                    )
                ),
            )
            setDeviceRequestInfo(
                DeviceRequestInfo.fromValues(
                    useCases = listOf(
                        UseCase(
                            mandatory = true,
                            documentSets = listOf(DocumentSet(docRequestIds = listOf(0, 1))),
                            purposeHints = emptyMap(),
                        )
                    )
                )
            )
        }
        val source = buildPresentmentSource(
            encryptionTargetRpId = encryptionTargetRpId,
            encryptionTargetTrusted = encryptionTargetTrusted,
            store = if (multiplePhotoIds) twoPhotoIdsDocumentStore else documentStore,
        )
        return deviceRequest.execute(
            presentmentSource = source,
            keyAgreementPossible = emptyList(),
        )
    }

    suspend fun executeBorderCrossingEncryptedOnlyRequest(
        intentToRetain: Boolean,
        encryptionTargetRpId: String,
        encryptionTargetTrusted: Boolean,
    ): PresentmentData {
        val pd = executeBorderCrossingEncryptionRequest(
            intentToRetain = intentToRetain,
            encryptionTargetRpId = encryptionTargetRpId,
            encryptionTargetTrusted = encryptionTargetTrusted,
        )
        return PresentmentData(
            useCases = pd.useCases.map { uc ->
                uc.copy(
                    solutions = uc.solutions.map { sol ->
                        sol.copy(matches = sol.matches.filter { it.encryptionRequested })
                    }
                )
            }
        )
    }

    suspend fun executeMdocRequestWithPurposeHints(
        docType: String,
        nameSpaces: Map<String, Map<String, Boolean>>,
        purposeHints: Map<String, Int>,
    ): PresentmentData {
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = docType,
                nameSpaces = nameSpaces,
            )
            setDeviceRequestInfo(
                DeviceRequestInfo.fromValues(
                    useCases = listOf(
                        UseCase(
                            mandatory = true,
                            documentSets = listOf(DocumentSet(docRequestIds = listOf(0))),
                            purposeHints = purposeHints,
                        )
                    )
                )
            )
        }
        val source = buildPresentmentSource()
        return deviceRequest.execute(
            presentmentSource = source,
            keyAgreementPossible = emptyList(),
        )
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-06-15T14:30:00Z")
        val VALID_FROM: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val VALID_UNTIL: Instant = Instant.parse("2027-06-15T00:00:00Z")

        const val RP_VINEYARDS = "utopia_vineyards"
        const val RP_DOT = "utopia_dot"
        const val RP_MARKETPLACE = "utopia_marketplace"
        const val RP_AIRLINES = "utopia_airlines"
        const val RP_WHOLESALE = "utopia_wholesale"
        const val RP_CINEMA = "utopia_cinema"
        const val RP_METRO_TRANSIT = "utopia_metro_transit"
        const val RP_CAR_RENTALS = "utopia_car_rentals"
        const val RP_CBP = "utopia_cbp"
        const val RP_UNVERIFIED_LOUNGE = "unverified_lounge"
        const val RP_UNVERIFIED_GAMING = "unverified_gaming"
        const val RP_UNVERIFIED_BROKER = "unverified_broker"

        const val APP_NIGHTCLUB_SCANNER = "org.utopia.nightclub.scanner"
        const val APP_WALLET_VERIFIER = "gov.utopia.wallet.verifier"

        private val DETERMINISTIC_P256_KEYS: Map<Long, Triple<String, String, String>> = mapOf(
            1001L to Triple("17d00cc5beae16a8b6ea31ffbf67d03fd58aad74d4894856b05144909767be24", "cbd5afc1d61701a6ed4056fc6240d33a5e3e50168da95755eddf4242f1cb51d0", "03d96fc6fad7c5784f87888bcda7d394e09c973d3491fcc91c91c0b555803a6f"),
            1002L to Triple("3e629838f8b63644ea04cfc6655a8c5f6c2d1ea4b4d237c6fc80607a86de08ea", "e834be573aefbc61caec25870d08c17a8d60a786fecdd125118b5570c311f4c6", "b54880f2049b0d906feb0f923ae44968cdf16b1addfd3411ddc7b877e6e95638"),
            2000L to Triple("3861fbddc689f2c0eccfd212c041f69284324d1724dbb1740688e0f335dd2ef0", "b6f2d2f11340c2ccca009e24d177afb25fb56e6729f7e34c9df3c1f1aeb58e6d", "70252d7aae60f62a9e1b6998ac3ca151903130d106aea03b8f620f0c07d17739"),
            2101L to Triple("344041c44dfd8728673006e203c9002f57191a86c0239ed7cab13efd8d00242c", "7c85e4e866529949fbc614d1a1e1362804f7c33d645e933c054037a9f571e11a", "628e2fe89755bf7e3b09d819303b38b8a8d9a2e80b86dd31c45056fdacd5638e"),
            2102L to Triple("19529437230868c4331a25da29dec44eea7e6957a0d88e4716819a0f7c75eee2", "e22d0c98dee767fdbb909c29bbd1f1235544c84a7a69a74aed67ca08c5e146d5", "fc7c83bb449275dfd18352bca676ccf837485155b092c50860b10cf188bdb687"),
            2103L to Triple("3b3aa5a0035f9d34e17100d25a9c5c77b435692e48b8ae2ab6609ffb91e9e14b", "b1e20cd7e676825d19974e70358075053c3c597d86627d36f54c859396d53d3a", "5670cb6bb2dc1fbf825fe4b7b66686650053e1b6dc3e38dd6e67e9bfdc83d4e6"),
            2104L to Triple("190a69c923f95fa31c869a5a51a2af0ab0783ff0c2ae3c20186aa36f7fb95b65", "6da71e21dde28d813bfeb5d7ed3f940d3f391c3edc11a1ff29395e2ff7869bd6", "a620db6cfe7e86126546faa74c31c81f72613c3e5afd357ede8c43cfdb5bbc45"),
            2105L to Triple("3bb27870039a95510a168f5382e89733f64b3f992af61c74b8c9a69b922d50ca", "2ab67110fc6cd2435e42a5c1edb1849025299573809932fbb5030b55994af56d", "9b859a53c9ec8cfec58777b2fba0915b1bad2a5f110c253af1b4f815d069b56d"),
            2106L to Triple("3208cd9bad9bb5b59633ac6aa8dd4b534becae474a450ce4e498427183a09905", "4f36267f265a57f44e34302126131e10e8b5b5ce8368d6222c60907ccb3774fe", "8b4e3d5ca761753ad84229d86d079e0a9711f9f90a22e15a12e78f263a358088"),
            2107L to Triple("34a8dc344d0c804780ec7763db9cf36a9127b040a26d2c90c468479d8e5496aa", "d1c5ca395d06acf9f82ac17c0941c1cb1a066252a5115aebef22ccc2a7a3af9c", "395a57018bbb5b930198c0465d9d61b5b7722345460c2834a2583311fe2c3f15"),
            2109L to Triple("1acd3be41f588c1bb364a535b36a1cad04f244bc250685805a7617b218eb3dff", "8745063abdbe80124154488bd9c2b5851075199082bcb47749886ccd9e989bb5", "b36560f240f39ff53d1f050055eb56b649a8445456f046c7fc75cec2b224d24b"),
            2110L to Triple("13039017595d6cffe702860e797f68cd1d56b56c455594f0868db347e97b7438", "080429b81e52910089b8354b210b03e7e0897366b66e842ca442ba1bbb052ed9", "e7d01edd4e153374fe5051878c69d38f62095df65149542945e1e62bf5b3d5ab"),
            2111L to Triple("11eb9fc0b9c6990d3dbba1054a3fc0f4679db513ad7db48326d5b6b32410789f", "d91fdc7fadb3e9bf7099bf02c803abafb8729caacbd77a54875276602dac9aef", "5668d40164eb67900b89816121cd6acce04bcbd5f38d6f1391e560ee806e8223"),
            2112L to Triple("3483e2a956fd0080973f0a5817f57ba6ffbd5eafac03bcf882c30426d9b886a7", "cd4b4b1ee0528a4d1b73fbd4935b55da23659f064fe685d70123f0a8f0c9a567", "1af53c49184d0156b0fca19e88a63c6f68e6ab1a78ab239d5222f9ea7186db33"),
            2113L to Triple("326bd302b65635918d901d6064b5a39dc58660d644e39b9d627309d2b46c8910", "489eac93e9b690767b40b7936a21fb8aab9c4c5ffc320c7e5b713e2234e339dd", "ea647bc5a33bb3f4de69404b6854ffde5bdbf177c863d212e2697f89838bc3d9"),
            2601L to Triple("33a88634ee83a48d02ac5aee82090423f4d304a2325516e9c68885624ae2dc16", "92bf1df0ff74e57fa7a95c5c08bae333e6a8e289e78a721dfd24bdac75225f8e", "00237e171a632157086c7cf5a1511719a7c5176e016229693a3dd53606cde540"),
            2602L to Triple("1a9ad2c7c881c4699e45f7d5a8fccf424d749473522626791a5a61983b7524e0", "b9e32c95a4164d8f5f9888298d95e937c6e8899c478450a6df97d59c46bc6a1b", "675fc487ffa7010b1208c271a0039298879edc5c61aa6acdfa57c57cd247dcb8"),
            2603L to Triple("3cc2e2706822919b88d63bdedbbd677b973f960abace451dba29666456091777", "0e12d2c02947a7b08e4512702ee1437b1687ee8b5b1e2c1cd3f04c774d261903", "2649925ba6b9630712a62cb6042c374501293086fb73e19c7c501a838d857d98"),
            2604L to Triple("14833519f254b83569c34483e0429785c03a00f6d59dbe88845df1bead42bb8c", "9bc1650848e22fd350eeea4cd207e569f9c2b5ae89eccb5d2c2dac65bc540f29", "93a2bc2909b571179dfdf0a163aff75a9a09b624887d384b824d5ee1b92b0e41"),
            2605L to Triple("12eb45c012be8cc7bb7af08ab38bbfbc068afe8e3d659eed2435f64ae0b6c021", "8b8f4653da633ca19312676813e120bc1a3876eb45c0f87469dcbe545fc01f8a", "809b665df3375ebd13cb97f5ac010d7ce81f37c498deab603e362b3c4dbf2ae5"),
            2606L to Triple("3b7d914b5cc76c23df9451b3799ef3dc1bee8e5e5d368e7d784ed2c0b127f9ec", "1dc3fbef92b917dae0f8458a0e6cf969fed10d8fec03c3b938c7c17659a9adf6", "5c47163d15f11dd70e673c4ddf61aab479c0754e3b789a9e68fee18375c3acf4"),
            2607L to Triple("19dda1e4bc6098d13525e6ba4a605be561a58f27b5dead185896d54cdcdb0642", "e3a4fa19b5aea0868563cd0ec12c35fd71ea62efadd34c5a88f82c0570643263", "9061e56cca4f6c6edf328bfc8ca7aca7c07a275bd7066285ed6d1424ea055aa5"),
            2609L to Triple("31656cac7aa327bcd02596302b787094e51483eaaf3656e788f00205ee6e3c5b", "a5eab1cfbb9961031cec56c17763a48a117451993f8309edd3c1767efb7ff1b2", "31d253de14f1aa235be85b37298f0216596a1760555352418023f5f5a930fb2b"),
            2610L to Triple("3c1bf95f34a24760cc03b517018b24f43c7b123bcf8566775423e7f61fe08594", "ada4dd47f50472457c93b8e07f7ec4920e5472e7f2f27900e45001a6b4638494", "aaf96635403b0cda5c50b47be3febc25ce6a8db8f34016bc2cb750b3e7d8dfa7"),
            2611L to Triple("1a8308f9d440fab05a5c8020d2d30ccc8238144237ad061b74e3e202f2537a3b", "657946add301f87fdde1df44b1d559ebf1b453dc08ac1186507434efaa5468b5", "ea310637a06cb7366dcc78b98d0ee84fd995eefc481da5dfc0c14adeeb3620a7"),
            2612L to Triple("124220918676530477858745c954fb31c94582be50fe7d87ca3377e089155ec9", "f4f2cf5c1b9b17509bd23a5301e7c567ff176e36750e9cfabd677778bb8a7fe1", "f97bf3ce356ef6e6422c54ad76c4abbcc11e5fb04b6a209eccd25425cc0ae96f"),
            2613L to Triple("146b2f28a6ddfdf4adf4ab4cfa15530a137f80c7b846deeaeadb722c84a9616e", "346ce38c32527bb3eb7e30c9e8b249fe64de69971b43fd0c4aaa20c23834f525", "4917ed9eef4ae567185d66f6358e9a35d941e187b73818fca749d83586fced59"),
        )

        private fun String.hexToBytes(): ByteArray =
            ByteArray(length / 2) { idx -> substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }

        private fun deterministicKey(seed: Long, curve: EcCurve): EcPrivateKey {
            val (dHex, xHex, yHex) = checkNotNull(DETERMINISTIC_P256_KEYS[seed]) {
                "Missing precomputed P-256 key for seed $seed"
            }
            return EcPrivateKeyDoubleCoordinate(curve, dHex.hexToBytes(), xHex.hexToBytes(), yHex.hexToBytes())
        }

        fun firstTimeHistory(): RequesterHistory = object : RequesterHistory {
            override suspend fun getEntry(
                requester: Requester,
                trustedRequesterIdentity: TrustedRequesterIdentity?
            ): RequesterHistoryEntry? = null

            override suspend fun recordShare(
                requester: Requester,
                trustedRequesterIdentity: TrustedRequesterIdentity?
            ) {}

            override suspend fun deleteAll() {}
        }

        fun returningUserHistory(shareCount: Int = 3): RequesterHistory = object : RequesterHistory {
            override suspend fun getEntry(
                requester: Requester,
                trustedRequesterIdentity: TrustedRequesterIdentity?
            ): RequesterHistoryEntry = RequesterHistoryEntry(
                firstSharedAt = NOW - 45.days,
                lastSharedAt = NOW - 2.days - 5.hours,
                shareCount = shareCount,
            )

            override suspend fun recordShare(
                requester: Requester,
                trustedRequesterIdentity: TrustedRequesterIdentity?
            ) {}

            override suspend fun deleteAll() {}
        }

        fun strictRetentionClassifyRisk(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            presentmentData: PresentmentData,
            selections: List<Int>,
            purposes: Set<PresentmentPurpose>,
        ): ConsentRiskAssessment {
            val base = defaultClassifyRisk(requester, trustedRequesterIdentity, presentmentData, selections, purposes)
            val retainsAny = presentmentData.useCases.indices.any { idx ->
                val solIdx = selections.getOrElse(idx) { -1 }
                solIdx >= 0 && presentmentData.useCases[idx].solutions[solIdx].matches.any { match ->
                    match.claims.keys.any { (it as? MdocRequestedClaim)?.intentToRetain == true }
                }
            }
            return if (base.level == ConsentRiskLevel.NORMAL && retainsAny) {
                ConsentRiskAssessment(
                    level = ConsentRiskLevel.WARNING,
                    reasons = setOf(ConsentRiskReason.INTENT_TO_RETAIN),
                )
            } else {
                base
            }
        }

        fun allowlistAppClassifyRisk(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            presentmentData: PresentmentData,
            selections: List<Int>,
            purposes: Set<PresentmentPurpose>,
        ): ConsentRiskAssessment {
            return if (requester.appId == APP_WALLET_VERIFIER) {
                ConsentRiskAssessment(
                    level = ConsentRiskLevel.NORMAL,
                    reasons = emptySet(),
                )
            } else {
                defaultClassifyRisk(requester, trustedRequesterIdentity, presentmentData, selections, purposes)
            }
        }

        suspend fun create(readResourceBytes: (String) -> ByteString): SwiftConsentGoldenEcosystem {
            val mdlDocType = DrivingLicense.getDocumentType()
            val photoIdDocType = PhotoID.getDocumentType()
            val pidDocType = EUPersonalID.getDocumentType()
            val loyaltyDocType = Loyalty.getDocumentType()
            val movieTicketDocType = UtopiaMovieTicket.getDocumentType()
            val paymentDocType = DigitalPaymentCredential.getDocumentType()
            val boardingPassDocType = UtopiaBoardingPass.getDocumentType()
            val avDocType = AgeVerification.getDocumentType()

            val documentTypeRepository = DocumentTypeRepository().apply {
                addDocumentType(mdlDocType)
                addDocumentType(photoIdDocType)
                addDocumentType(pidDocType)
                addDocumentType(loyaltyDocType)
                addDocumentType(movieTicketDocType)
                addDocumentType(paymentDocType)
                addDocumentType(boardingPassDocType)
                addDocumentType(avDocType)
            }

            val storage = DeterministicStorage()
            val secureArea = SoftwareSecureArea.create(storage)
            val secureAreaRepository = SecureAreaRepository.Builder()
                .add(secureArea)
                .build()
            val documentStore = buildDocumentStore(
                storage = storage,
                secureAreaRepository = secureAreaRepository
            ) {}

            val iacaKey = deterministicKey(1001L, EcCurve.P256)
            val dsPrivateKey = deterministicKey(1002L, EcCurve.P256)
            val iacaCert = MdocUtil.generateIacaCertificate(
                iacaKey = AsymmetricKey.anonymous(iacaKey),
                subject = X500Name.fromName("CN=Utopia IACA Root,C=UT"),
                serial = ASN1Integer(1001L),
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                issuerAltNameUrl = "https://issuer.utopia.gov",
                crlUrl = "https://issuer.utopia.gov/crl",
            )
            val dsCert = MdocUtil.generateDsCertificate(
                iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
                dsKey = dsPrivateKey.publicKey,
                subject = X500Name.fromName("CN=Utopia Document Signer,C=UT"),
                serial = ASN1Integer(1002L),
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
            )
            val dsKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(dsCert, iacaCert)),
                privateKey = dsPrivateKey,
            )

            val rng = Random(424242L)
            val keySettings = CreateKeySettings()

            val mdlDoc = documentStore.createDocument(
                displayName = "Erika's Driving License",
                typeDisplayName = "Driving License",
                cardArt = readResourceBytes("/cards/card_utopia_mdl.png"),
            )
            mdlDocType.createMdocCredentialWithSampleData(
                document = mdlDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                signedAt = VALID_FROM,
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                randomProvider = rng,
            )

            val photoIdDoc = documentStore.createDocument(
                displayName = "Erika's Photo ID",
                typeDisplayName = "Photo ID",
                cardArt = readResourceBytes("/cards/card_utopia_photoid.png"),
            )
            photoIdDocType.createMdocCredentialWithSampleData(
                document = photoIdDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                signedAt = VALID_FROM,
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                randomProvider = rng,
            )

            val pidDoc = documentStore.createDocument(
                displayName = "Erika's Personal ID",
                typeDisplayName = "EU Personal ID",
                cardArt = readResourceBytes("/cards/card_utopia_pid.png"),
            )
            pidDocType.createMdocCredentialWithSampleData(
                document = pidDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                signedAt = VALID_FROM,
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                randomProvider = rng,
            )

            val wholesaleDoc = documentStore.createDocument(
                displayName = "Utopia Wholesale Membership",
                typeDisplayName = "Loyalty ID",
                cardArt = readResourceBytes("/cards/card_utopia_wholesale.png"),
            )
            loyaltyDocType.createMdocCredentialWithSampleData(
                document = wholesaleDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                signedAt = VALID_FROM,
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                randomProvider = rng,
            )

            val cinemaTicketDoc = documentStore.createDocument(
                displayName = "Utopia Cinema Ticket",
                typeDisplayName = "Movie Ticket",
                cardArt = readResourceBytes("/cards/card_utopia_cinema.png"),
            )
            createMovieTicketSdJwt(
                document = cinemaTicketDoc,
                dsKey = dsKey,
                randomProvider = rng,
            )

            val paymentPlatinumDoc = documentStore.createDocument(
                displayName = "Utopia Bank Platinum",
                typeDisplayName = "Payment Card (**** 4829)",
                cardArt = readResourceBytes("/cards/card_utopia_payment.png"),
            )
            createPaymentMdoc(
                document = paymentPlatinumDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                maskedAccount = "**** 4829",
                instrumentId = "pi-PLAT-4829",
                randomProvider = rng,
            )

            val paymentGoldDoc = documentStore.createDocument(
                displayName = "Utopia Bank Gold",
                typeDisplayName = "Payment Card (**** 9012)",
                cardArt = readResourceBytes("/cards/card_utopia_payment.png"),
            )
            createPaymentMdoc(
                document = paymentGoldDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                maskedAccount = "**** 9012",
                instrumentId = "pi-GOLD-9012",
                randomProvider = rng,
            )

            val boardingPassDoc = documentStore.createDocument(
                displayName = "Utopia Airlines UT 815",
                typeDisplayName = "Boarding Pass",
                cardArt = readResourceBytes("/cards/card_utopia_boarding_pass.png"),
            )
            createBoardingPassMdoc(
                document = boardingPassDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                randomProvider = rng,
            )

            val avDoc = documentStore.createDocument(
                displayName = "Erika's Age Verification Credential",
                typeDisplayName = "Age Verification",
                cardArt = readResourceBytes("/cards/card_utopia_av.png"),
            )
            avDocType.createMdocCredentialWithSampleData(
                document = avDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                signedAt = VALID_FROM,
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                randomProvider = rng,
            )

            val readerRootKey = deterministicKey(2000L, EcCurve.P256)
            val readerRootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(readerRootKey),
                subject = X500Name.fromName("CN=Utopia Trust Network Root CA,O=Utopia Trust Network,C=UT"),
                serial = ASN1Integer(2000L),
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                crlUrl = "https://trust.utopia.gov/crl",
            )
            val readerRootCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerRootCert)),
                privateKey = readerRootKey,
            )

            val rpConfigs = listOf(
                RpConfig(
                    id = RP_VINEYARDS,
                    serial = 2101L,
                    cn = "Utopia Vineyards Age Check",
                    org = "Utopia Vineyards Ltd.",
                    displayName = "Utopia Vineyards",
                    iconPath = "/icons/rp_utopia_vineyards.png",
                    privacyPolicyUrl = "https://utopiavineyards.example.com/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_DOT,
                    serial = 2102L,
                    cn = "Utopia Department of Transportation",
                    org = "Government of Utopia",
                    displayName = "Utopia Department of Transportation",
                    iconPath = "/icons/rp_utopia_dot.png",
                    privacyPolicyUrl = "https://dot.utopia.gov/privacy",
                    disclaimer = "Pursuant to Utopia Vehicle Code § 402.1, roadside inspection data is used solely for license verification and is deleted immediately after the traffic stop.",
                ),
                RpConfig(
                    id = RP_MARKETPLACE,
                    serial = 2103L,
                    cn = "Utopia Marketplace Checkout",
                    org = "Utopia Marketplace Inc.",
                    displayName = "Utopia Marketplace",
                    iconPath = "/icons/rp_utopia_marketplace.png",
                    privacyPolicyUrl = "https://utopiamarketplace.example.com/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_AIRLINES,
                    serial = 2104L,
                    cn = "Utopia Airlines Gate Reader",
                    org = "Utopia Airlines Corp.",
                    displayName = "Utopia Airlines",
                    iconPath = "/icons/rp_utopia_airlines.png",
                    privacyPolicyUrl = "https://utopiaairlines.example.com/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_WHOLESALE,
                    serial = 2105L,
                    cn = "Utopia Wholesale Point of Sale",
                    org = "Utopia Wholesale Club",
                    displayName = "Utopia Wholesale",
                    iconPath = "/icons/rp_utopia_wholesale.png",
                    privacyPolicyUrl = "https://utopiawholesale.example.com/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_CINEMA,
                    serial = 2106L,
                    cn = "Utopia Cinema Ticket Scanner",
                    org = "Utopia Cinema Entertainment",
                    displayName = "Utopia Cinema",
                    iconPath = "/icons/rp_utopia_cinema.png",
                    privacyPolicyUrl = "https://utopiacinema.example.com/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_METRO_TRANSIT,
                    serial = 2107L,
                    cn = "Utopia Metro Transit Fare Gate",
                    org = "Utopia Metropolitan Transit Authority",
                    displayName = "Utopia Metro Transit",
                    iconPath = "/icons/rp_utopia_metro_transit.png",
                    privacyPolicyUrl = "https://metro.utopia.gov/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_CAR_RENTALS,
                    serial = 2109L,
                    cn = "Utopia Car Rentals Express Kiosk",
                    org = "Utopia Car Rentals Inc.",
                    displayName = "Utopia Car Rentals",
                    iconPath = "/icons/rp_utopia_car_rentals.png",
                    privacyPolicyUrl = "https://utopiacarrentals.example.com/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_CBP,
                    serial = 2110L,
                    cn = "Utopia Border Protection",
                    org = "Utopia Customs and Border Protection",
                    displayName = "Utopia Border Protection",
                    iconPath = "/icons/rp_utopia_cbp.png",
                    privacyPolicyUrl = "https://cbp.utopia.gov/privacy",
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_UNVERIFIED_LOUNGE,
                    serial = 2111L,
                    cn = "Harbor View Nightlife Terminal",
                    org = "Harbor View Hospitality LLC",
                    displayName = "Harbor View Lounge",
                    iconPath = null,
                    privacyPolicyUrl = null,
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_UNVERIFIED_GAMING,
                    serial = 2112L,
                    cn = "age-check.utopia-gaming.example.com",
                    org = "Utopia Online Gaming Ltd.",
                    displayName = "Utopia Online Gaming",
                    iconPath = null,
                    privacyPolicyUrl = null,
                    disclaimer = null,
                ),
                RpConfig(
                    id = RP_UNVERIFIED_BROKER,
                    serial = 2113L,
                    cn = "Unknown Analytics Collector",
                    org = "Offshore Data Brokers Ltd.",
                    displayName = "Offshore Data Brokers",
                    iconPath = null,
                    privacyPolicyUrl = null,
                    disclaimer = null,
                ),
            )

            val rpCertChains = mutableMapOf<String, X509CertChain>()
            val rpTrustMetadata = mutableMapOf<String, TrustMetadata>()
            val encTargetKeys = mutableMapOf<String, AsymmetricKey.X509Certified>()
            val encTargetTrustMetadata = mutableMapOf<String, TrustMetadata>()

            for (cfg in rpConfigs) {
                val leafKey = deterministicKey(cfg.serial, EcCurve.P256)
                val leafCert = MdocUtil.generateReaderCertificate(
                    readerRootKey = readerRootCertified,
                    readerKey = leafKey.publicKey,
                    subject = X500Name.fromName("CN=${cfg.cn},O=${cfg.org},C=UT"),
                    dnsName = null,
                    serial = ASN1Integer(cfg.serial),
                    validFrom = VALID_FROM,
                    validUntil = VALID_UNTIL,
                )
                val chain = X509CertChain(listOf(leafCert, readerRootCert))
                rpCertChains[cfg.id] = chain
                val metadata = TrustMetadata(
                    identifier = cfg.id,
                    displayName = cfg.displayName,
                    displayIcon = cfg.iconPath?.let { readResourceBytes(it) },
                    privacyPolicyUrl = cfg.privacyPolicyUrl,
                    disclaimer = cfg.disclaimer,
                )
                rpTrustMetadata[cfg.id] = metadata

                val encKey = deterministicKey(cfg.serial + 500L, EcCurve.P256)
                val encCert = buildX509Cert(
                    publicKey = encKey.publicKey,
                    signingKey = readerRootCertified,
                    serialNumber = ASN1Integer(cfg.serial + 500L),
                    subject = X500Name.fromName("CN=${cfg.cn} Encryption Key,O=${cfg.org},C=UT"),
                    issuer = readerRootCert.subject,
                    validFrom = VALID_FROM,
                    validUntil = VALID_UNTIL,
                ) {
                    includeSubjectKeyIdentifier()
                    includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
                    setKeyUsage(setOf(X509KeyUsage.KEY_AGREEMENT))
                }
                val encChain = X509CertChain(listOf(encCert, readerRootCert))
                encTargetKeys[cfg.id] = AsymmetricKey.X509CertifiedExplicit(encChain, encKey)
                encTargetTrustMetadata[cfg.id] = metadata
            }

            val twoPhotoIdsStorage = DeterministicStorage()
            val twoPhotoIdsSecureArea = SoftwareSecureArea.create(twoPhotoIdsStorage)
            val twoPhotoIdsRepo = SecureAreaRepository.Builder()
                .add(twoPhotoIdsSecureArea)
                .build()
            val twoPhotoIdsDocumentStore = buildDocumentStore(
                storage = twoPhotoIdsStorage,
                secureAreaRepository = twoPhotoIdsRepo,
            ) {}
            val twoPhotoIdsRng = Random(424243L)
            val photoIdCardArt = readResourceBytes("/cards/card_utopia_photoid.png")
            for (docName in listOf("Erika's Photo ID", "Erika's Photo ID #2")) {
                val doc = twoPhotoIdsDocumentStore.createDocument(
                    displayName = docName,
                    typeDisplayName = "Photo ID",
                    cardArt = photoIdCardArt,
                )
                photoIdDocType.createMdocCredentialWithSampleData(
                    document = doc,
                    secureArea = twoPhotoIdsSecureArea,
                    createKeySettings = keySettings,
                    dsKey = dsKey,
                    signedAt = VALID_FROM,
                    validFrom = VALID_FROM,
                    validUntil = VALID_UNTIL,
                    randomProvider = twoPhotoIdsRng,
                )
            }

            return SwiftConsentGoldenEcosystem(
                documentTypeRepository = documentTypeRepository,
                documentStore = documentStore,
                twoPhotoIdsDocumentStore = twoPhotoIdsDocumentStore,
                mdlDoc = mdlDoc,
                photoIdDoc = photoIdDoc,
                pidDoc = pidDoc,
                wholesaleDoc = wholesaleDoc,
                cinemaTicketDoc = cinemaTicketDoc,
                paymentPlatinumDoc = paymentPlatinumDoc,
                paymentGoldDoc = paymentGoldDoc,
                boardingPassDoc = boardingPassDoc,
                avDoc = avDoc,
                rpCertChains = rpCertChains,
                rpTrustMetadata = rpTrustMetadata,
                encTargetKeys = encTargetKeys,
                encTargetTrustMetadata = encTargetTrustMetadata,
            )
        }

        private data class RpConfig(
            val id: String,
            val serial: Long,
            val cn: String,
            val org: String,
            val displayName: String,
            val iconPath: String?,
            val privacyPolicyUrl: String?,
            val disclaimer: String?,
        )

        private suspend fun createMovieTicketSdJwt(
            document: Document,
            dsKey: AsymmetricKey.X509Certified,
            randomProvider: Random,
        ): KeylessSdJwtVcCredential {
            val claims = buildJsonObject {
                put("ticket_id", JsonPrimitive("UT-CIN-904812"))
                put("cinema", JsonPrimitive("Utopia Cinema Downtown"))
                put("movie", JsonPrimitive("Return to Utopia"))
                put("show_date_time", JsonPrimitive("2026-06-15T19:30:00Z"))
                put("movie_rating", JsonPrimitive("PG-13"))
                put("theater_id", JsonPrimitive("Auditorium 4 (IMAX)"))
                put("seat_id", JsonPrimitive("G14"))
                put("parking_option", JsonPrimitive(true))
            }
            val credential = KeylessSdJwtVcCredential.create(
                document = document,
                asReplacementForIdentifier = null,
                domain = "sdjwt",
                vct = UtopiaMovieTicket.MOVIE_TICKET_VCT,
            )
            val sdJwt = SdJwt.create(
                issuerKey = dsKey,
                kbKey = null,
                claims = claims,
                nonSdClaims = buildJsonObject {
                    put("iss", "https://utopiacinema.example.com")
                    put("vct", UtopiaMovieTicket.MOVIE_TICKET_VCT)
                    put("iat", VALID_FROM.epochSeconds)
                    put("nbf", VALID_FROM.epochSeconds)
                    put("exp", VALID_UNTIL.epochSeconds)
                },
                random = randomProvider,
            )
            credential.certify(sdJwt.compactSerialization.encodeToByteString())
            return credential
        }

        private suspend fun createPaymentMdoc(
            document: Document,
            secureArea: SecureArea,
            createKeySettings: CreateKeySettings,
            dsKey: AsymmetricKey.X509Certified,
            maskedAccount: String,
            instrumentId: String,
            randomProvider: Random,
        ): MdocCredential {
            val ns = DigitalPaymentCredential.CARD_NAMESPACE
            val issuerNamespaces = buildIssuerNamespaces(randomProvider = randomProvider) {
                addNamespace(ns) {
                    addDataElement("issuer_name", "Utopia Bank".toDataItem())
                    addDataElement("payment_instrument_id", instrumentId.toDataItem())
                    addDataElement("masked_account_reference", maskedAccount.toDataItem())
                    addDataElement("holder_name", "${SampleData.GIVEN_NAME} ${SampleData.FAMILY_NAME}".toDataItem())
                    addDataElement("issue_date", LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate())
                    addDataElement("expiry_date", LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate())
                }
            }
            return signMdocCredential(
                document = document,
                docType = DigitalPaymentCredential.CARD_DOCTYPE,
                issuerNamespaces = issuerNamespaces,
                secureArea = secureArea,
                createKeySettings = createKeySettings,
                dsKey = dsKey,
                deviceKeyAuthorizedNamespaces = listOf(
                    PaymentTransaction.openId4VpMdocResponseNamespace,
                    GoldenTransitFareTransaction.openId4VpMdocResponseNamespace,
                    GoldenAp2MandateTransaction.openId4VpMdocResponseNamespace,
                ),
                deviceKeyAuthorizedDataElements = mapOf(
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(
                        PaymentTransaction.identifier,
                    )
                ),
            )
        }

        private suspend fun createBoardingPassMdoc(
            document: Document,
            secureArea: SecureArea,
            createKeySettings: CreateKeySettings,
            dsKey: AsymmetricKey.X509Certified,
            randomProvider: Random,
        ): MdocCredential {
            val ns = UtopiaBoardingPass.BOARDING_PASS_NS
            val issuerNamespaces = buildIssuerNamespaces(randomProvider = randomProvider) {
                addNamespace(ns) {
                    addDataElement("passenger_name", "Erika Mustermann".toDataItem())
                    addDataElement("flight_number", "UT 815".toDataItem())
                    addDataElement("seat_number", "12A".toDataItem())
                    addDataElement("departure_time", Instant.parse("2026-06-15T18:45:00Z").toDataItemDateTimeString())
                }
            }
            return signMdocCredential(
                document = document,
                docType = UtopiaBoardingPass.BOARDING_PASS_DOCTYPE,
                issuerNamespaces = issuerNamespaces,
                secureArea = secureArea,
                createKeySettings = createKeySettings,
                dsKey = dsKey,
            )
        }

        private suspend fun signMdocCredential(
            document: Document,
            docType: String,
            issuerNamespaces: IssuerNamespaces,
            secureArea: SecureArea,
            createKeySettings: CreateKeySettings,
            dsKey: AsymmetricKey.X509Certified,
            deviceKeyAuthorizedNamespaces: List<String> = emptyList(),
            deviceKeyAuthorizedDataElements: Map<String, List<String>> = emptyMap(),
        ): MdocCredential {
            val mdocCredential = MdocCredential.create(
                document = document,
                asReplacementForIdentifier = null,
                domain = "mdoc",
                secureArea = secureArea,
                docType = docType,
                createKeySettings = createKeySettings,
            )
            val mso = MobileSecurityObject(
                version = "1.0",
                docType = docType,
                signedAt = VALID_FROM,
                validFrom = VALID_FROM,
                validUntil = VALID_UNTIL,
                expectedUpdate = null,
                digestAlgorithm = Algorithm.SHA256,
                valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
                deviceKey = mdocCredential.getAttestation().ecPublicKey,
                deviceKeyAuthorizedNamespaces = deviceKeyAuthorizedNamespaces,
                deviceKeyAuthorizedDataElements = deviceKeyAuthorizedDataElements,
            )
            val taggedEncodedMso = Cbor.encode(
                Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem())))
            )
            val signatureAlgorithm = dsKey.algorithm.curve?.defaultSigningAlgorithm ?: dsKey.algorithm
            val protectedHeaders = mapOf<CoseLabel, DataItem>(
                CoseNumberLabel(Cose.COSE_LABEL_ALG) to signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem()
            )
            val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to dsKey.certChain.toCoseX5Chain()
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
            mdocCredential.certify(ByteString(issuerProvidedAuthenticationData))
            return mdocCredential
        }
    }
}

object GoldenTransitFareTransaction : TransactionType<GoldenTransitFareTransaction.Payload>(
    displayName = "Transit fare",
    identifier = "org.multipaz.golden.transit_fare",
) {
    data class Fare(
        val id: String,
        val name: String,
        val validity: String,
        val amount: Double,
    )

    data class Payload(
        val operator: String,
        val zones: String,
        val currency: String,
        val fares: List<Fare>,
    )

    data class UserInput(val fareId: String) : TransactionUserInput() {
        override fun generateMdocResponseElements(
            transactionData: TransactionData<*>,
            credential: Credential
        ): Map<String, DataItem> = mapOf("fare" to Tstr(fareId))

        override fun generateSdJwtResponseClaims(
            transactionData: TransactionData<*>,
            credential: Credential
        ): Map<String, JsonElement> = mapOf("fare" to JsonPrimitive(fareId))
    }

    fun defaultFare(payload: Payload): Fare = payload.fares.first()

    val sampleData = Payload(
        operator = "Utopia Metro Transit",
        zones = "Zones 1–2",
        currency = "USD",
        fares = listOf(
            Fare(id = "single", name = "Single ride", validity = "Valid for 90 minutes", amount = 2.75),
            Fare(id = "day", name = "Day pass", validity = "Valid until midnight", amount = 7.50),
            Fare(id = "week", name = "7-day pass", validity = "Valid for 7 days", amount = 25.00),
        ),
    )
}

object GoldenAp2MandateTransaction : TransactionType<GoldenAp2MandateTransaction.Payload>(
    displayName = "AP2 payment mandate",
    identifier = "org.multipaz.golden.ap2_mandate",
) {
    data class Payload(
        val mandateId: String,
        val agentName: String,
        val agentId: String,
        val merchant: String,
        val intentDescription: String,
        val maxAmount: Double,
        val currency: String,
        val constraints: String,
        val validUntil: String,
    )

    val sampleData = Payload(
        mandateId = "mandate-ap2-2026-0841",
        agentName = "Utopia Travel Concierge AI",
        agentId = "did:web:agent.utopiaair.example.com",
        merchant = "Utopia Airlines",
        intentDescription = "Book round-trip Economy flights SFO to UTP (Oct 12–19, 2026) when fare drops",
        maxAmount = 450.00,
        currency = "USD",
        constraints = "Non-stop flights only · Single charge",
        validUntil = "Jun 22, 2026, 11:59 PM UTC",
    )
}

private fun <PayloadT : Any> goldenTransactionData(
    type: TransactionType<PayloadT>,
    payload: PayloadT,
): TransactionData<PayloadT> = TransactionData(
    type = type,
    payload = payload,
    protocol = TransactionProtocol.OPENID4VP,
    rawBytes = ByteString("${type.identifier}:$payload".encodeToByteArray()),
)

private fun utopiaPaymentTransactionData(
    payeeName: String,
    amount: Double,
    tipRequested: Boolean = true,
): TransactionData<PaymentTransaction.Payload> = goldenTransactionData(
    type = PaymentTransaction,
    payload = PaymentTransaction.Payload(
        transactionId = "tx-utopia-948102",
        currency = "USD",
        amount = amount,
        payee = PaymentTransaction.Payee(
            name = payeeName,
            id = "merchant-${payeeName.lowercase().replace(' ', '-')}",
        ),
        tipRequested = tipRequested,
    )
)

private class DeterministicStorage(
    private val delegate: EphemeralStorage = EphemeralStorage()
) : Storage by delegate {
    private var nextKeyId = 1

    override suspend fun getTable(spec: StorageTableSpec): StorageTable {
        val table = delegate.getTable(spec)
        val selfStorage = this
        return object : StorageTable {
            override val storage: Storage
                get() = selfStorage

            override suspend fun get(key: String, partitionId: String?): ByteString? =
                table.get(key, partitionId)

            override suspend fun insert(
                key: String?,
                data: ByteString,
                partitionId: String?,
                expiration: Instant,
            ): String = table.insert(
                key = key ?: "det-key-${(nextKeyId++).toString().padStart(4, '0')}",
                data = data,
                partitionId = partitionId,
                expiration = expiration,
            )

            override suspend fun update(
                key: String,
                data: ByteString,
                partitionId: String?,
                expiration: Instant?,
            ) = table.update(key, data, partitionId, expiration)

            override suspend fun delete(key: String, partitionId: String?): Boolean =
                table.delete(key, partitionId)

            override suspend fun deleteAll() = table.deleteAll()

            override suspend fun deletePartition(partitionId: String) =
                table.deletePartition(partitionId)

            override suspend fun enumerate(
                partitionId: String?,
                afterKey: String?,
                limit: Int,
            ): List<String> = table.enumerate(partitionId, afterKey, limit)

            override suspend fun enumerateWithData(
                partitionId: String?,
                afterKey: String?,
                limit: Int,
            ): List<Pair<String, ByteString>> = table.enumerateWithData(partitionId, afterKey, limit)
        }
    }
}
