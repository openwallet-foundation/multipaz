package org.multipaz.compose.presentment

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.util.BigIntegers
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
import org.multipaz.claim.MdocClaim
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
import java.io.File
import java.math.BigInteger
import java.util.Locale
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
import org.multipaz.mpzpass.MpzPass
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.PresentmentCredentialMatchSourceIso18013
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.RequesterHistory
import org.multipaz.presentment.RequesterHistoryEntry
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionDataContext
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.prompt.TransactionDataRendererRepository
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
import org.robolectric.Shadows
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Deterministic Utopia ecosystem of issuers, holder credentials, relying parties, encryption
 * targets, transaction types, and history fixtures for golden-master screenshot testing.
 */
internal class ConsentGoldenEcosystem private constructor(
    val locale: Locale,
    val documentTypeRepository: DocumentTypeRepository,
    val transactionDataRendererRepository: TransactionDataRendererRepository,
    val documentStore: DocumentStore,
    val twoPhotoIdsDocumentStore: DocumentStore,
    val fourMovieTicketsDocumentStore: DocumentStore,
    val sixBoardingPassesDocumentStore: DocumentStore,
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
    /**
     * Builds a trusted proximity [Requester] and [TrustedRequesterIdentity] for a relying party id.
     */
    fun trustedProximityRequester(rpId: String): Pair<Requester, TrustedRequesterIdentity> {
        val certChain = rpCertChains.getValue(rpId)
        val metadata = rpTrustMetadata.getValue(rpId)
        val identity = Iso18013RequesterIdentity(certChain = certChain)
        return Requester(requesterIdentities = listOf(identity)) to
            TrustedRequesterIdentity(identity = identity, trustMetadata = metadata)
    }

    /**
     * Builds a trusted web [Requester] and [TrustedRequesterIdentity] for a relying party id.
     */
    fun trustedWebRequester(
        rpId: String,
        origin: String,
        clientId: String = "x509_san_dns:${origin.removePrefix("https://")}",
    ): Pair<Requester, TrustedRequesterIdentity> {
        val certChain = rpCertChains.getValue(rpId)
        val metadata = rpTrustMetadata.getValue(rpId)
        val identity = OpenID4VPRequesterIdentity(certChain = certChain, clientId = clientId)
        return Requester(
            requesterIdentities = listOf(identity),
            origin = origin,
        ) to TrustedRequesterIdentity(identity = identity, trustMetadata = metadata)
    }

    /**
     * Builds an untrusted proximity [Requester] with an unverified certificate chain.
     */
    fun unverifiedProximityRequester(rpId: String = RP_UNVERIFIED_LOUNGE): Pair<Requester, TrustedRequesterIdentity?> {
        val certChain = rpCertChains.getValue(rpId)
        val identity = Iso18013RequesterIdentity(certChain = certChain)
        return Requester(requesterIdentities = listOf(identity)) to null
    }

    /**
     * Builds an anonymous proximity [Requester] with no reader certificate.
     */
    fun anonymousProximityRequester(): Pair<Requester, TrustedRequesterIdentity?> =
        Requester() to null

    /**
     * Builds an untrusted web [Requester] with an unverified certificate chain.
     */
    fun unverifiedWebRequester(
        origin: String = "https://age-check.utopia-gaming.example.com",
    ): Pair<Requester, TrustedRequesterIdentity?> {
        val certChain = rpCertChains.getValue(RP_UNVERIFIED_GAMING)
        val identity = OpenID4VPRequesterIdentity(
            certChain = certChain,
            clientId = "x509_san_dns:${origin.removePrefix("https://")}"
        )
        return Requester(
            requesterIdentities = listOf(identity),
            origin = origin,
        ) to null
    }

    /**
     * Builds an anonymous web [Requester] with no certificate chain.
     */
    fun anonymousWebRequester(
        origin: String = "https://age-check.utopia-gaming.example.com",
    ): Pair<Requester, TrustedRequesterIdentity?> =
        Requester(origin = origin) to null

    /**
     * Builds an untrusted Android application [Requester].
     */
    fun unverifiedAppRequester(appId: String): Pair<Requester, TrustedRequesterIdentity?> =
        Requester(appId = appId) to null

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

    /**
     * Evaluates a [DcqlQuery] JSON string against [documentStore].
     */
    suspend fun executeDcql(
        dcqlJson: String,
        transactionDataMap: Map<String, List<TransactionData<*>>> = emptyMap(),
    ): PresentmentData = executeDcqlObject(
        dcqlObject = Json.parseToJsonElement(dcqlJson).jsonObject,
        transactionDataMap = transactionDataMap,
    )

    /**
     * Evaluates a [DcqlQuery] [JsonObject] against [documentStore].
     */
    suspend fun executeDcqlObject(
        dcqlObject: JsonObject,
        transactionDataMap: Map<String, List<TransactionData<*>>> = emptyMap(),
    ): PresentmentData {
        val prevLocale = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val query = DcqlQuery.fromJson(dcql = dcqlObject)
            val source = buildPresentmentSource()
            return query.execute(
                presentmentSource = source,
                transactionDataMap = transactionDataMap,
            )
        } finally {
            Locale.setDefault(prevLocale)
        }
    }

    /**
     * Evaluates a [SingleDocumentCannedRequest] via DCQL against [documentStore].
     */
    suspend fun executeCannedDcql(
        cannedRequest: SingleDocumentCannedRequest,
        transactionDataMap: Map<String, List<TransactionData<*>>> = emptyMap(),
    ): PresentmentData {
        val dcqlObject = checkNotNull(cannedRequest.mdocRequest).toDcql(emptyList())
        return executeDcqlObject(
            dcqlObject = dcqlObject,
            transactionDataMap = transactionDataMap,
        )
    }

    /**
     * Evaluates an ISO 18013-5 `DeviceRequest` with optional purposeHints or claim-level
     * encryption (e.g., border crossing with plain `org.iso.23220.2` and encrypted `datagroups`).
     */
    suspend fun executeBorderCrossingEncryptionRequest(
        intentToRetain: Boolean,
        encryptionTargetRpId: String,
        encryptionTargetTrusted: Boolean,
        includeRecipientCertificates: Boolean = true,
        multiplePhotoIds: Boolean = false,
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

    /**
     * Evaluates an ISO 18013-5 `DeviceRequest` with explicit `purposeHints` (e.g., to test
     * mismatched purpose hint detection).
     */
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

    /**
     * Evaluates a multi-credential DCQL request (`multiple = true`) for 4 movie tickets to
     * "Return to Utopia" against [fourMovieTicketsDocumentStore].
     */
    suspend fun executeFourMovieTicketsRequest(): PresentmentData {
        val dcqlJson = """
            {
              "credentials": [
                {
                  "id": "movie_tickets",
                  "format": "dc+sd-jwt",
                  "multiple": true,
                  "meta": {
                    "vct_values": ["${UtopiaMovieTicket.MOVIE_TICKET_VCT}"]
                  },
                  "claims": [
                    { "path": ["cinema"] },
                    { "path": ["movie"] },
                    { "path": ["show_date_time"] },
                    { "path": ["theater_id"] },
                    { "path": ["seat_id"] },
                    { "path": ["ticket_id"] }
                  ]
                }
              ]
            }
        """.trimIndent()
        val query = DcqlQuery.fromJson(Json.parseToJsonElement(dcqlJson).jsonObject)
        val source = buildPresentmentSource(store = fourMovieTicketsDocumentStore)
        return query.execute(presentmentSource = source)
    }

    /**
     * Evaluates an ISO 18013-5 `DeviceRequest` with `uniqueDocSetRequired = false` for boarding
     * passes against [sixBoardingPassesDocumentStore] (3 passes for UT 815 and 3 passes for UT 516).
     */
    suspend fun executeSixBoardingPassesRequest(): PresentmentData {
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = UtopiaBoardingPass.BOARDING_PASS_DOCTYPE,
                nameSpaces = mapOf(
                    UtopiaBoardingPass.BOARDING_PASS_NS to mapOf(
                        "passenger_name" to false,
                        "flight_number" to false,
                        "seat_number" to false,
                        "departure_time" to false,
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    uniqueDocSetRequired = false,
                ),
            )
            setDeviceRequestInfo(
                DeviceRequestInfo.fromValues(
                    useCases = listOf(
                        UseCase(
                            mandatory = true,
                            documentSets = listOf(DocumentSet(docRequestIds = listOf(0))),
                            purposeHints = emptyMap(),
                        )
                    )
                )
            )
        }
        val source = buildPresentmentSource(store = sixBoardingPassesDocumentStore)
        return deviceRequest.execute(
            presentmentSource = source,
            keyAgreementPossible = emptyList(),
        )
    }

    /**
     * Evaluates an ISO 18013-5 `DeviceRequest` with `uniqueDocSetRequired = false` and
     * `flight_number` filtering via `DocRequestInfo` against [sixBoardingPassesDocumentStore],
     * matching only the 3 boarding passes for flight [flightNumber].
     */
    suspend fun executeThreeBoardingPassesFilteredRequest(
        flightNumber: String = "UT 815",
    ): PresentmentData {
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = UtopiaBoardingPass.BOARDING_PASS_DOCTYPE,
                nameSpaces = mapOf(
                    UtopiaBoardingPass.BOARDING_PASS_NS to mapOf(
                        "passenger_name" to false,
                        "flight_number" to false,
                        "seat_number" to false,
                        "departure_time" to false,
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    uniqueDocSetRequired = false,
                    otherInfo = mapOf(
                        "flight_number" to flightNumber.toDataItem(),
                    ),
                ),
            )
            setDeviceRequestInfo(
                DeviceRequestInfo.fromValues(
                    useCases = listOf(
                        UseCase(
                            mandatory = true,
                            documentSets = listOf(DocumentSet(docRequestIds = listOf(0))),
                            purposeHints = emptyMap(),
                        )
                    )
                )
            )
        }
        val source = buildPresentmentSource(store = sixBoardingPassesDocumentStore)
        val rawData = deviceRequest.execute(
            presentmentSource = source,
            keyAgreementPossible = emptyList(),
        )
        return PresentmentData(
            useCases = rawData.useCases.map { useCase ->
                useCase.copy(
                    solutions = useCase.solutions.filter { solution ->
                        solution.matches.all { match ->
                            val reqFlight = (match.source as? PresentmentCredentialMatchSourceIso18013)
                                ?.docRequest
                                ?.docRequestInfo
                                ?.otherInfo
                                ?.get("flight_number")
                                ?.asTstr
                            if (reqFlight == null) {
                                true
                            } else {
                                val flightClaim = match.claims.entries.firstOrNull { (req, _) ->
                                    (req as? MdocRequestedClaim)?.dataElementName == "flight_number"
                                }?.value as? MdocClaim
                                flightClaim?.value?.asTstr == reqFlight
                            }
                        }
                    }
                )
            }
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

        private fun deterministicKey(seed: Long, curve: EcCurve): EcPrivateKey {
            val rng = Random(seed)
            val d = ByteArray(32).also { rng.nextBytes(it) }
            d[0] = (d[0].toInt() and 0x3F or 0x10).toByte()
            val spec = ECNamedCurveTable.getParameterSpec("P-256")
            val q = spec.g.multiply(BigInteger(1, d)).normalize()
            val x = BigIntegers.asUnsignedByteArray(32, q.affineXCoord.toBigInteger())
            val y = BigIntegers.asUnsignedByteArray(32, q.affineYCoord.toBigInteger())
            return EcPrivateKeyDoubleCoordinate(curve, d, x, y)
        }

        private fun readResourceBytes(path: String): ByteString {
            val stream = checkNotNull(ConsentGoldenEcosystem::class.java.getResourceAsStream(path)) {
                "Missing test resource at $path"
            }
            return ByteString(stream.use { it.readBytes() })
        }

        private fun resolveMpzPassFile(relativePath: String): File {
            val userDir = File(System.getProperty("user.dir") ?: ".")
            val rootDir = if (userDir.name == "multipaz-compose") {
                userDir.parentFile ?: userDir
            } else {
                userDir
            }
            val file = File(rootDir, relativePath)
            check(file.exists()) { "Missing MpzPass file at ${file.absolutePath}" }
            return file
        }

        fun registerFakeAndroidApps(application: Application) {
            val pm = Shadows.shadowOf(application.packageManager)
            val iconBytes = readResourceBytes("/icons/rp_utopia_dot.png").toByteArray()
            val drawable = BitmapDrawable(
                application.resources,
                BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
            )
            listOf(
                APP_NIGHTCLUB_SCANNER to "Utopia Nightclub Scanner",
                APP_WALLET_VERIFIER to "Utopia Wallet Verifier",
            ).forEach { (pkgName, label) ->
                val appInfo = ApplicationInfo().apply {
                    packageName = pkgName
                    name = label
                    nonLocalizedLabel = label
                }
                val pkgInfo = PackageInfo().apply {
                    packageName = pkgName
                    applicationInfo = appInfo
                }
                pm.installPackage(pkgInfo)
                pm.setUnbadgedApplicationIcon(pkgName, drawable)
                pm.setApplicationIcon(pkgName, drawable)
            }
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

        suspend fun create(locale: Locale = Locale.US): ConsentGoldenEcosystem {
            val prevLocale = Locale.getDefault()
            Locale.setDefault(locale)
            try {
                return createInternal(locale)
            } finally {
                Locale.setDefault(prevLocale)
            }
        }

        private suspend fun createInternal(locale: Locale): ConsentGoldenEcosystem {
            val lang = locale.language
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
            val transactionDataRendererRepository = TransactionDataRendererRepository().apply {
                addRenderer(GoldenTransitFareTransaction.identifier, goldenTransitFareRenderer)
                addRenderer(GoldenAp2MandateTransaction.identifier, goldenAp2MandateRenderer)
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

            // 1. Erika's Driving License (mDL)
            val mdlDoc = documentStore.createDocument(
                displayName = when (lang) {
                    "da" -> "Erikas kørekort"
                    "ar" -> "رخصة قيادة إريكا"
                    else -> "Erika's Driving License"
                },
                typeDisplayName = if (lang == "en") "Driving License" else mdlDocType.displayName,
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

            // 2. Erika's Photo ID (ISO 23220-4 PhotoID)
            val photoIdDoc = documentStore.createDocument(
                displayName = when (lang) {
                    "da" -> "Erikas billed-ID"
                    "ar" -> "بطاقة هوية إريكا المصورة"
                    else -> "Erika's Photo ID"
                },
                typeDisplayName = if (lang == "en") "Photo ID" else photoIdDocType.displayName,
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

            // 3. Erika's Personal ID (EU PID)
            val pidDoc = documentStore.createDocument(
                displayName = when (lang) {
                    "da" -> "Erikas personlige ID"
                    "ar" -> "الهوية الشخصية لإريكا"
                    else -> "Erika's Personal ID"
                },
                typeDisplayName = if (lang == "en") "EU Personal ID" else pidDocType.displayName,
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

            // 4. Utopia Wholesale Membership (Loyalty mdoc)
            val wholesaleDoc = documentStore.createDocument(
                displayName = when (lang) {
                    "da" -> "Utopia Wholesale-medlemskab"
                    "ar" -> "عضوية Utopia Wholesale"
                    else -> "Utopia Wholesale Membership"
                },
                typeDisplayName = if (lang == "en") "Loyalty ID" else loyaltyDocType.displayName,
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

            // 5. Utopia Cinema Ticket (Keyless SD-JWT VC)
            val cinemaTicketDoc = documentStore.createDocument(
                displayName = when (lang) {
                    "da" -> "Utopia Cinema-billet"
                    "ar" -> "تذكرة سينما Utopia"
                    else -> "Utopia Cinema Ticket"
                },
                typeDisplayName = if (lang == "en") "Movie Ticket" else movieTicketDocType.displayName,
                cardArt = readResourceBytes("/cards/card_utopia_cinema.png"),
            )
            createMovieTicketSdJwt(
                document = cinemaTicketDoc,
                dsKey = dsKey,
                randomProvider = rng,
            )

            // 6. Utopia Bank Platinum (Digital Payment Credential #1)
            val paymentPlatinumDoc = documentStore.createDocument(
                displayName = "Utopia Bank Platinum",
                typeDisplayName = when (lang) {
                    "da" -> "Betalingskort (**** 4829)"
                    "ar" -> "بطاقة دفع (**** 4829)"
                    else -> "Payment Card (**** 4829)"
                },
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

            // 7. Utopia Bank Gold (Digital Payment Credential #2)
            val paymentGoldDoc = documentStore.createDocument(
                displayName = "Utopia Bank Gold",
                typeDisplayName = when (lang) {
                    "da" -> "Betalingskort (**** 9012)"
                    "ar" -> "بطاقة دفع (**** 9012)"
                    else -> "Payment Card (**** 9012)"
                },
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

            // 8. Utopia Airlines Boarding Pass (Flight UT 815)
            val boardingPassDoc = documentStore.createDocument(
                displayName = "Utopia Airlines UT 815",
                typeDisplayName = if (lang == "en") "Boarding Pass" else boardingPassDocType.displayName,
                cardArt = readResourceBytes("/cards/card_utopia_boarding_pass.png"),
            )
            createBoardingPassMdoc(
                document = boardingPassDoc,
                secureArea = secureArea,
                createKeySettings = keySettings,
                dsKey = dsKey,
                randomProvider = rng,
            )

            // 9. Erika's Age Verification Credential (EU Age Verification)
            val avDoc = documentStore.createDocument(
                displayName = when (lang) {
                    "da" -> "Erikas aldersbekræftelse"
                    "ar" -> "إثبات عمر إريكا"
                    else -> "Erika's Age Verification Credential"
                },
                typeDisplayName = if (lang == "en") "Age Verification" else avDocType.displayName,
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
            val photoIdTypeDisplay = if (lang == "en") "Photo ID" else photoIdDocType.displayName
            for (docName in listOf("Erika's Photo ID", "Erika's Photo ID #2")) {
                val doc = twoPhotoIdsDocumentStore.createDocument(
                    displayName = docName,
                    typeDisplayName = photoIdTypeDisplay,
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

            // Store with 4 movie tickets to one movie ("Return to Utopia", Seats G14–G17)
            val fourTicketsStorage = DeterministicStorage()
            val fourTicketsSecureArea = SoftwareSecureArea.create(fourTicketsStorage)
            val fourTicketsRepo = SecureAreaRepository.Builder()
                .add(fourTicketsSecureArea)
                .build()
            val fourMovieTicketsDocumentStore = buildDocumentStore(
                storage = fourTicketsStorage,
                secureAreaRepository = fourTicketsRepo,
            ) {}
            val fourTicketsRng = Random(424244L)
            val cinemaCardArt = readResourceBytes("/cards/card_utopia_cinema.png")
            val movieTicketTypeDisplay = if (lang == "en") "Movie Ticket" else movieTicketDocType.displayName
            listOf(
                "G14" to "UT-CIN-904812",
                "G15" to "UT-CIN-904813",
                "G16" to "UT-CIN-904814",
                "G17" to "UT-CIN-904815",
            ).forEach { (seat, ticketId) ->
                val doc = fourMovieTicketsDocumentStore.createDocument(
                    displayName = "Return to Utopia (Seat $seat)",
                    typeDisplayName = movieTicketTypeDisplay,
                    cardArt = cinemaCardArt,
                )
                createMovieTicketSdJwt(
                    document = doc,
                    dsKey = dsKey,
                    randomProvider = fourTicketsRng,
                    seatId = seat,
                    ticketId = ticketId,
                )
            }

            // Store with 6 boarding passes (3 for flight UT 815 and 3 for flight UT 516)
            val sixPassesStorage = DeterministicStorage()
            val sixPassesSecureArea = SoftwareSecureArea.create(sixPassesStorage)
            val sixPassesRepo = SecureAreaRepository.Builder()
                .add(sixPassesSecureArea)
                .build()
            val sixBoardingPassesDocumentStore = buildDocumentStore(
                storage = sixPassesStorage,
                secureAreaRepository = sixPassesRepo,
            ) {}
            val sixPassesRng = Random(424245L)
            val boardingPassCardArt = readResourceBytes("/cards/card_utopia_boarding_pass.png")
            val boardingPassTypeDisplay = if (lang == "en") "Boarding Pass" else boardingPassDocType.displayName
            data class BoardingPassSpec(
                val displayName: String,
                val passengerName: String,
                val flightNumber: String,
                val seatNumber: String,
                val departureTime: String,
            )
            listOf(
                BoardingPassSpec(
                    displayName = "Flight UT 815 (12A · Erika)",
                    passengerName = "Erika Mustermann",
                    flightNumber = "UT 815",
                    seatNumber = "12A",
                    departureTime = "2026-06-15T18:45:00Z",
                ),
                BoardingPassSpec(
                    displayName = "Flight UT 815 (12B · Max)",
                    passengerName = "Max Mustermann",
                    flightNumber = "UT 815",
                    seatNumber = "12B",
                    departureTime = "2026-06-15T18:45:00Z",
                ),
                BoardingPassSpec(
                    displayName = "Flight UT 815 (12C · Mia)",
                    passengerName = "Mia Mustermann",
                    flightNumber = "UT 815",
                    seatNumber = "12C",
                    departureTime = "2026-06-15T18:45:00Z",
                ),
                BoardingPassSpec(
                    displayName = "Flight UT 516 (14A · Erika)",
                    passengerName = "Erika Mustermann",
                    flightNumber = "UT 516",
                    seatNumber = "14A",
                    departureTime = "2026-06-15T21:15:00Z",
                ),
                BoardingPassSpec(
                    displayName = "Flight UT 516 (14B · Max)",
                    passengerName = "Max Mustermann",
                    flightNumber = "UT 516",
                    seatNumber = "14B",
                    departureTime = "2026-06-15T21:15:00Z",
                ),
                BoardingPassSpec(
                    displayName = "Flight UT 516 (14C · Mia)",
                    passengerName = "Mia Mustermann",
                    flightNumber = "UT 516",
                    seatNumber = "14C",
                    departureTime = "2026-06-15T21:15:00Z",
                ),
            ).forEach { spec ->
                val doc = sixBoardingPassesDocumentStore.createDocument(
                    displayName = spec.displayName,
                    typeDisplayName = boardingPassTypeDisplay,
                    cardArt = boardingPassCardArt,
                )
                createBoardingPassMdoc(
                    document = doc,
                    secureArea = sixPassesSecureArea,
                    createKeySettings = keySettings,
                    dsKey = dsKey,
                    randomProvider = sixPassesRng,
                    passengerName = spec.passengerName,
                    flightNumber = spec.flightNumber,
                    seatNumber = spec.seatNumber,
                    departureTime = spec.departureTime,
                )
            }

            // 10. Import mpzpass/utopia_mini_mart_loyalty_card_for_erika.mpzpass (org.example.loyalty.1,
            // intentionally NOT registered in documentTypeRepository so metadata comes from the MpzPass)
            val mpzPassBytes = resolveMpzPassFile("mpzpass/utopia_mini_mart_loyalty_card_for_erika.mpzpass").readBytes()
            val mpzPass = MpzPass.fromDataItem(Cbor.decode(mpzPassBytes))
            documentStore.importMpzPass(
                mpzPass = mpzPass,
                isoMdocDomain = "mdoc",
                sdJwtVcDomain = "sdjwt",
                keylessSdJwtVcDomain = "sdjwt",
            )

            return ConsentGoldenEcosystem(
                locale = locale,
                documentTypeRepository = documentTypeRepository,
                transactionDataRendererRepository = transactionDataRendererRepository,
                documentStore = documentStore,
                twoPhotoIdsDocumentStore = twoPhotoIdsDocumentStore,
                fourMovieTicketsDocumentStore = fourMovieTicketsDocumentStore,
                sixBoardingPassesDocumentStore = sixBoardingPassesDocumentStore,
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
            seatId: String = "G14",
            ticketId: String = "UT-CIN-904812",
        ): KeylessSdJwtVcCredential {
            val claims = buildJsonObject {
                put("ticket_id", JsonPrimitive(ticketId))
                put("cinema", JsonPrimitive("Utopia Cinema Downtown"))
                put("movie", JsonPrimitive("Return to Utopia"))
                put("show_date_time", JsonPrimitive("2026-06-15T19:30:00Z"))
                put("movie_rating", JsonPrimitive("PG-13"))
                put("theater_id", JsonPrimitive("Auditorium 4 (IMAX)"))
                put("seat_id", JsonPrimitive(seatId))
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
            passengerName: String = "Erika Mustermann",
            flightNumber: String = "UT 815",
            seatNumber: String = "12A",
            departureTime: String = "2026-06-15T18:45:00Z",
        ): MdocCredential {
            val ns = UtopiaBoardingPass.BOARDING_PASS_NS
            val issuerNamespaces = buildIssuerNamespaces(randomProvider = randomProvider) {
                addNamespace(ns) {
                    addDataElement("passenger_name", passengerName.toDataItem())
                    addDataElement("flight_number", flightNumber.toDataItem())
                    addDataElement("seat_number", seatNumber.toDataItem())
                    addDataElement("departure_time", Instant.parse(departureTime).toDataItemDateTimeString())
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

/**
 * Custom transaction type for buying a Utopia Metro Transit fare with interactive fare chips.
 */
internal object GoldenTransitFareTransaction : TransactionType<GoldenTransitFareTransaction.Payload>(
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

/**
 * Custom transaction type for an Agent Payments Protocol (AP2) payment delegation mandate.
 */
internal object GoldenAp2MandateTransaction : TransactionType<GoldenAp2MandateTransaction.Payload>(
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

internal fun <PayloadT : Any> goldenTransactionData(
    type: TransactionType<PayloadT>,
    payload: PayloadT,
): TransactionData<PayloadT> = TransactionData(
    type = type,
    payload = payload,
    protocol = TransactionProtocol.OPENID4VP,
    rawBytes = ByteString("${type.identifier}:$payload".encodeToByteArray()),
)

internal fun utopiaPaymentTransactionData(
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

private fun formatGoldenAmount(amount: Double): String {
    val roundedCents = round(amount * 100.0).toLong()
    return "${roundedCents / 100}.${abs(roundedCents % 100).toString().padStart(2, '0')}"
}

private fun selectedGoldenFare(context: TransactionDataContext): GoldenTransitFareTransaction.Fare {
    val payload = context.transactionData.first().payload as GoldenTransitFareTransaction.Payload
    val fareId = (context.userInput as? GoldenTransitFareTransaction.UserInput)?.fareId
    return payload.fares.find { it.id == fareId } ?: GoldenTransitFareTransaction.defaultFare(payload)
}

@OptIn(ExperimentalLayoutApi::class)
internal val goldenTransitFareRenderer = TransactionDataRendererCompose(
    summary = { context ->
        val payload = context.transactionData.first().payload as GoldenTransitFareTransaction.Payload
        val fare = selectedGoldenFare(context)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Pay ${formatGoldenAmount(fare.amount)} ${payload.currency}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${payload.operator} · ${payload.zones} · ${fare.validity}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                payload.fares.forEach { option ->
                    FilterChip(
                        selected = option.id == fare.id,
                        onClick = { context.onUserInputChanged(GoldenTransitFareTransaction.UserInput(option.id)) },
                        label = { Text(option.name) },
                    )
                }
            }
        }
    },
    details = { context ->
        val payload = context.transactionData.first().payload as GoldenTransitFareTransaction.Payload
        val fare = selectedGoldenFare(context)
        val rows = listOf(
            "Operator" to payload.operator,
            "Zones" to payload.zones,
            "Selected" to "${fare.name}, ${formatGoldenAmount(fare.amount)} ${payload.currency}",
            "Valid" to fare.validity,
        )
        GoldenDetailRows(title = "TICKET DETAILS", rows = rows)
    }
)

internal val goldenAp2MandateRenderer = TransactionDataRendererCompose(
    summary = { context ->
        val payload = context.transactionData.first().payload as GoldenAp2MandateTransaction.Payload
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Authorize up to ${formatGoldenAmount(payload.maxAmount)} ${payload.currency}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Delegate to ${payload.agentName}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = payload.intentDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${payload.constraints} · Expires ${payload.validUntil}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    },
    details = { context ->
        val payload = context.transactionData.first().payload as GoldenAp2MandateTransaction.Payload
        val rows = listOf(
            "Mandate ID" to payload.mandateId,
            "Delegate agent" to payload.agentName,
            "Agent DID" to payload.agentId,
            "Merchant" to payload.merchant,
            "Authorized intent" to payload.intentDescription,
            "Maximum amount" to "${formatGoldenAmount(payload.maxAmount)} ${payload.currency}",
            "Constraints" to payload.constraints,
            "Expires" to payload.validUntil,
        )
        GoldenDetailRows(title = "AP2 INTENT MANDATE", rows = rows)
    }
)

@Composable
private fun GoldenDetailRows(title: String, rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    modifier = Modifier.weight(0.4f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, modifier = Modifier.weight(0.6f))
            }
            HorizontalDivider()
        }
    }
}
