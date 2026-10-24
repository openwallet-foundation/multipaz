package org.multipaz.legacytestapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.compose.document.DocumentModel
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.documenttype.knowntypes.addKnownTypes
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.promptModelRequestConsent
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.trustmanagement.ConfigurableTrustManager
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import org.multipaz.util.Platform
import org.multipaz.util.fromHexByteString
import org.multipaz.util.truncateToWholeSeconds
import org.multipaz.utopia.knowntypes.PingTransaction
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.utopia.knowntypes.addUtopiaTypes
import java.io.ByteArrayOutputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Application-wide state holding [DocumentStore], trust managers, and hard-coded test mdoc provisioning.
 */
class LegacyAppState private constructor(
    val promptModel: PromptModel
) {
    lateinit var storage: Storage
    lateinit var secureArea: SecureArea
    lateinit var softwareSecureArea: SoftwareSecureArea
    lateinit var secureAreaRepository: SecureAreaRepository
    lateinit var documentTypeRepository: DocumentTypeRepository
    lateinit var documentStore: DocumentStore
    lateinit var documentModel: DocumentModel
    lateinit var iacaKey: AsymmetricKey.X509Certified
    lateinit var readerRootKey: AsymmetricKey.X509Certified
    lateinit var readerTrustManager: ConfigurableTrustManager
    private lateinit var keyStorage: StorageTable

    private val initLock = Mutex()
    var isInitialized = false
        private set

    suspend fun initialize() {
        initLock.withLock {
            if (isInitialized) {
                return
            }
            storage = Platform.nonBackedUpStorage
            softwareSecureArea = SoftwareSecureArea.create(storage)
            secureArea = Platform.getSecureArea(storage)
            val repoBuilder = SecureAreaRepository.Builder()
                .add(softwareSecureArea)
            if (secureArea !is SoftwareSecureArea) {
                repoBuilder.add(secureArea)
            }
            secureAreaRepository = repoBuilder.build()

            documentTypeRepository = DocumentTypeRepository().apply {
                addKnownTypes()
                addUtopiaTypes()
            }

            documentStore = buildDocumentStore(
                storage = storage,
                secureAreaRepository = secureAreaRepository
            ) {}

            documentModel = DocumentModel.create(
                documentStore = documentStore,
                documentTypeRepository = documentTypeRepository
            )

            keyStorage = storage.getTable(
                StorageTableSpec(
                    name = "LegacyTestAppKeys",
                    supportPartitions = false,
                    supportExpiration = false
                )
            )
            initKeysAndTrust()

            if (documentStore.listDocumentIds().isEmpty()) {
                provisionHardCodedTestDocuments()
            }

            isInitialized = true
        }
    }

    private suspend fun initKeysAndTrust() {
        val iacaPrivateKey = keyStorage.get("iacaKey")?.let {
            EcPrivateKey.fromDataItem(Cbor.decode(it.toByteArray()))
        } ?: run {
            keyStorage.insert("iacaKey", ByteString(Cbor.encode(bundledIacaKey.toDataItem())))
            bundledIacaKey
        }
        val iacaCert = keyStorage.get("iacaCert")?.let {
            X509Cert.fromDataItem(Cbor.decode(it.toByteArray()))
        } ?: run {
            val cert = MdocUtil.generateIacaCertificate(
                iacaKey = AsymmetricKey.anonymous(iacaPrivateKey),
                subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST IACA"),
                serial = ASN1Integer.fromRandom(numBits = 128),
                validFrom = certsValidFrom,
                validUntil = certsValidUntil,
                issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
                crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
            )
            keyStorage.insert("iacaCert", ByteString(Cbor.encode(cert.toDataItem())))
            cert
        }
        iacaKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(iacaCert)),
            privateKey = iacaPrivateKey
        )

        val readerRootCert = keyStorage.get("readerRootCert")?.let {
            X509Cert.fromDataItem(Cbor.decode(it.toByteArray()))
        } ?: run {
            val cert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(bundledReaderRootKey),
                subject = X500Name.fromName("CN=OWF Multipaz TestApp Reader Root"),
                serial = ASN1Integer.fromRandom(numBits = 128),
                validFrom = certsValidFrom,
                validUntil = certsValidUntil,
                crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
            )
            keyStorage.insert("readerRootCert", ByteString(Cbor.encode(cert.toDataItem())))
            cert
        }
        readerRootKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerRootCert)),
            privateKey = bundledReaderRootKey
        )

        readerTrustManager = ConfigurableTrustManager(
            identifier = "builtInReaderTrustManager",
            entries = listOf(
                TrustEntryX509Cert(
                    identifier = "testapp_reader",
                    metadata = TrustMetadata(
                        displayName = "Multipaz TestApp",
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    ),
                    certificate = readerRootCert
                ),
                TrustEntryX509Cert(
                    identifier = "multipaz_verifier",
                    metadata = TrustMetadata(
                        displayName = "Multipaz Verifier",
                        displayIconUrl = "https://www.multipaz.org/multipaz-logo-200x200.png",
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    ),
                    certificate = MULTIPAZ_VERIFIER_READER_CERT
                ),
                TrustEntryX509Cert(
                    identifier = "multipaz_identity_reader",
                    metadata = TrustMetadata(
                        displayName = "Multipaz Identity Reader",
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    ),
                    certificate = MULTIPAZ_IDENTITY_READER_CERT
                ),
                TrustEntryX509Cert(
                    identifier = "multipaz_identity_reader_untrusted",
                    metadata = TrustMetadata(
                        displayName = "Multipaz Identity Reader (Untrusted Devices)",
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    ),
                    certificate = MULTIPAZ_IDENTITY_READER_CERT_UNTRUSTED_DEVICES
                )
            )
        )
    }

    suspend fun resetAndProvisionHardCodedTestDocuments() {
        for (docId in documentStore.listDocumentIds()) {
            documentStore.deleteDocument(docId)
        }
        provisionHardCodedTestDocuments()
    }

    suspend fun provisionHardCodedTestDocuments() {
        val dsCertValidFrom = Clock.System.now().truncateToWholeSeconds() - 1.days
        val dsCertValidUntil = dsCertValidFrom + 455.days
        val dsPrivateKey = Crypto.createEcPrivateKey(Algorithm.ESP256.curve!!)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = iacaKey,
            dsKey = dsPrivateKey.publicKey,
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST DS"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = dsCertValidFrom,
            validUntil = dsCertValidUntil
        )
        val dsKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(dsCert)),
            privateKey = dsPrivateKey
        )

        val hasKeyAgreement = AndroidKeystoreSecureArea.Capabilities().keyAgreementSupported
        val macAlgorithm = if (hasKeyAgreement) Algorithm.ECDH_P256 else Algorithm.UNSET

        val specs = listOf(
            HardCodedDocSpec(
                documentType = DrivingLicense.getDocumentType(),
                displayName = "Erika's Driving License",
                givenNameOverride = "Erika",
                colorStart = 0xFF1565C0.toInt(),
                colorEnd = 0xFF0D47A1.toInt(),
                authorizedNamespaces = listOf(PingTransaction.openId4VpMdocResponseNamespace),
                authorizedDataElements = mapOf(
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(PingTransaction.identifier)
                )
            ),
            HardCodedDocSpec(
                documentType = PhotoID.getDocumentType(),
                displayName = "Erika's Photo ID",
                givenNameOverride = "Erika",
                colorStart = 0xFF00695C.toInt(),
                colorEnd = 0xFF004D40.toInt()
            ),
            HardCodedDocSpec(
                documentType = EUPersonalID.getDocumentType(),
                displayName = "Erika's EU PID",
                givenNameOverride = "Erika",
                colorStart = 0xFF283593.toInt(),
                colorEnd = 0xFF1A237E.toInt(),
                authorizedNamespaces = listOf(PingTransaction.openId4VpMdocResponseNamespace),
                authorizedDataElements = mapOf(
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(PingTransaction.identifier)
                )
            ),
            HardCodedDocSpec(
                documentType = AgeVerification.getDocumentType(),
                displayName = "Erika's Age Verification",
                givenNameOverride = "Erika",
                colorStart = 0xFF6A1B9A.toInt(),
                colorEnd = 0xFF4A148C.toInt()
            ),
            HardCodedDocSpec(
                documentType = UtopiaMovieTicket.getDocumentType(),
                displayName = "The Last Utopian",
                givenNameOverride = "Erika",
                colorStart = 0xFFC62828.toInt(),
                colorEnd = 0xFF8E0000.toInt()
            )
        )

        for (spec in specs) {
            try {
                provisionSingleMdoc(
                    spec = spec,
                    dsKey = dsKey,
                    deviceKeyAlgorithm = Algorithm.ESP256,
                    deviceKeyMacAlgorithm = macAlgorithm
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Error provisioning ${spec.displayName}", e)
            }
        }
    }

    private suspend fun provisionSingleMdoc(
        spec: HardCodedDocSpec,
        dsKey: AsymmetricKey.X509Certified,
        deviceKeyAlgorithm: Algorithm,
        deviceKeyMacAlgorithm: Algorithm
    ) {
        val mdocDocType = spec.documentType.mdocDocumentType ?: return
        val cardArtBytes = renderCardArtPng(
            title = spec.displayName,
            subtitle = spec.documentType.displayName,
            docType = mdocDocType.docType,
            colorStart = spec.colorStart,
            colorEnd = spec.colorEnd
        )
        val document = documentStore.createDocument(
            displayName = spec.displayName,
            typeDisplayName = spec.documentType.displayName,
            cardArt = ByteString(cardArtBytes)
        )

        val now = Clock.System.now().truncateToWholeSeconds()
        val signedAt = now - 1.hours
        val validFrom = now - 1.hours
        val validUntil = now + 365.days

        val issuerNamespaces = buildIssuerNamespaces {
            for ((nsName, ns) in mdocDocType.namespaces) {
                addNamespace(nsName) {
                    for ((deName, de) in ns.dataElements) {
                        val sampleValue = de.attribute.sampleValueMdoc
                        if (sampleValue != null) {
                            val value = if (deName.startsWith("given_name")) {
                                Tstr(spec.givenNameOverride)
                            } else {
                                sampleValue
                            }
                            addDataElement(deName, value)
                        }
                    }
                }
            }
        }

        val domains = listOf(
            CREDENTIAL_DOMAIN_MDOC_USER_AUTH,
            CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH,
            CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH,
            CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH
        )

        for (domain in domains) {
            val userAuthenticationRequired =
                domain == CREDENTIAL_DOMAIN_MDOC_USER_AUTH || domain == CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH
            val algorithm = when (domain) {
                CREDENTIAL_DOMAIN_MDOC_USER_AUTH, CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH -> deviceKeyAlgorithm
                CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH, CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH -> deviceKeyMacAlgorithm
                else -> Algorithm.UNSET
            }
            if (algorithm == Algorithm.UNSET || !secureArea.supportedAlgorithms.contains(algorithm)) {
                continue
            }

            val mdocCredential = try {
                MdocCredential.create(
                    document = document,
                    asReplacementForIdentifier = null,
                    domain = domain,
                    secureArea = secureArea,
                    docType = mdocDocType.docType,
                    createKeySettings = CreateKeySettings(
                        algorithm = algorithm,
                        nonce = "Challenge".encodeToByteString(),
                        userAuthenticationRequired = userAuthenticationRequired,
                        userAuthenticationTimeout = 10.seconds,
                        validFrom = validFrom,
                        validUntil = validUntil
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Fall back to software secure area if device does not have a screen lock / biometric enrolled
                Logger.w(TAG, "Falling back to SoftwareSecureArea for domain $domain: ${e.message}")
                MdocCredential.create(
                    document = document,
                    asReplacementForIdentifier = null,
                    domain = domain,
                    secureArea = softwareSecureArea,
                    docType = mdocDocType.docType,
                    createKeySettings = CreateKeySettings(
                        algorithm = algorithm,
                        nonce = "Challenge".encodeToByteString(),
                        userAuthenticationRequired = false,
                        validFrom = validFrom,
                        validUntil = validUntil
                    )
                )
            }

            val mso = MobileSecurityObject(
                version = "1.0",
                docType = mdocDocType.docType,
                signedAt = signedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                expectedUpdate = null,
                digestAlgorithm = Algorithm.SHA256,
                valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
                deviceKey = mdocCredential.getAttestation().ecPublicKey,
                deviceKeyAuthorizedNamespaces = spec.authorizedNamespaces,
                deviceKeyAuthorizedDataElements = spec.authorizedDataElements
            )
            val taggedEncodedMso = Cbor.encode(
                Tagged(
                    Tagged.ENCODED_CBOR,
                    Bstr(Cbor.encode(mso.toDataItem()))
                )
            )
            val protectedHeaders = mapOf<CoseLabel, DataItem>(
                CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
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
        }
    }

    fun getPresentmentSource(
        requireAuthentication: Boolean,
        showConsentPrompt: Boolean,
        preferSignatureToKeyAgreement: Boolean
    ): PresentmentSource {
        return SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            resolveTrustFn = ::resolveTrust,
            showConsentPromptFn = if (showConsentPrompt) {
                { requester, trustedRequesterIdentity, presentmentData, preselectedDocuments, onDocumentsInFocus ->
                    promptModelRequestConsent(
                        requester = requester,
                        trustedRequesterIdentity = trustedRequesterIdentity,
                        presentmentData = presentmentData,
                        preselectedDocuments = preselectedDocuments,
                        onDocumentsInFocus = onDocumentsInFocus,
                    )
                }
            } else {
                ::promptModelSilentConsent
            },
            preferSignatureToKeyAgreement = preferSignatureToKeyAgreement,
            domainsMdocSignature = if (requireAuthentication) {
                listOf(CREDENTIAL_DOMAIN_MDOC_USER_AUTH, CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH)
            } else {
                listOf(CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH, CREDENTIAL_DOMAIN_MDOC_USER_AUTH)
            },
            domainsMdocKeyAgreement = if (requireAuthentication) {
                listOf(CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH, CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH)
            } else {
                listOf(CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH, CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH)
            }
        )
    }

    private suspend fun resolveTrust(requester: Requester): TrustedRequesterIdentity? {
        for (requesterIdentity in requester.requesterIdentities) {
            val metadata = resolveRequesterIdentity(requesterIdentity) ?: continue
            return TrustedRequesterIdentity(requesterIdentity, metadata)
        }
        return null
    }

    private suspend fun resolveRequesterIdentity(requesterIdentity: RequesterIdentity): TrustMetadata? {
        val trustResult = readerTrustManager.verify(requesterIdentity.certChain.certificates)
        if (trustResult.isTrusted) {
            return trustResult.trustPoints.first().metadata
        }
        return null
    }

    private data class HardCodedDocSpec(
        val documentType: DocumentType,
        val displayName: String,
        val givenNameOverride: String,
        val colorStart: Int,
        val colorEnd: Int,
        val authorizedNamespaces: List<String> = emptyList(),
        val authorizedDataElements: Map<String, List<String>> = emptyMap()
    )

    private fun renderCardArtPng(
        title: String,
        subtitle: String,
        docType: String,
        colorStart: Int,
        colorEnd: Int
    ): ByteArray {
        val width = 640
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                colorStart,
                colorEnd,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 32f, 32f, bgPaint)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22FFFFFF
        }
        canvas.drawCircle(width * 0.85f, height * 0.2f, 140f, accentPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(title, 40f, 88f, titlePaint)

        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            textSize = 26f
        }
        canvas.drawText(subtitle, 40f, 132f, subtitlePaint)

        val docTypePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt()
            textSize = 20f
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText(docType, 40f, height - 40f, docTypePaint)

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
        return baos.toByteArray()
    }

    companion object {
        private const val TAG = "LegacyAppState"

        const val CREDENTIAL_DOMAIN_MDOC_USER_AUTH = "mdoc_user_auth"
        const val CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH = "mdoc_no_user_auth"
        const val CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH = "mdoc_mac_user_auth"
        const val CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH = "mdoc_mac_no_user_auth"

        private val certsValidFrom: Instant = LocalDate.parse("2024-12-01").atStartOfDayIn(TimeZone.UTC)
        private val certsValidUntil: Instant = LocalDate.parse("2034-12-01").atStartOfDayIn(TimeZone.UTC)

        private val bundledIacaKey: EcPrivateKey by lazy {
            val iacaKeyPub = EcPublicKey.fromPem(
                """
                -----BEGIN PUBLIC KEY-----
                MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE+QDye70m2O0llPXMjVjxVZz3m5k6agT+
                wih+L79b7jyqUl99sbeUnpxaLD+cmB3HK3twkA7fmVJSobBc+9CDhkh3mx6n+YoH
                5RulaSWThWBfMyRjsfVODkosHLCDnbPV
                -----END PUBLIC KEY-----
                """.trimIndent().trim()
            )
            EcPrivateKey.fromPem(
                """
                -----BEGIN PRIVATE KEY-----
                MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCcRuzXW3pW2h9W8pu5
                /CSR6JSnfnZVATq+408WPoNC3LzXqJEQSMzPsI9U1q+wZ2yhZANiAAT5APJ7vSbY
                7SWU9cyNWPFVnPebmTpqBP7CKH4vv1vuPKpSX32xt5SenFosP5yYHccre3CQDt+Z
                UlKhsFz70IOGSHebHqf5igflG6VpJZOFYF8zJGOx9U4OSiwcsIOds9U=
                -----END PRIVATE KEY-----
                """.trimIndent().trim(),
                iacaKeyPub
            )
        }

        private val bundledReaderRootKey: EcPrivateKey by lazy {
            bundledIacaKey
        }

        private val MULTIPAZ_VERIFIER_READER_CERT by lazy {
            X509Cert.fromPem(
                """
                -----BEGIN CERTIFICATE-----
                MIICaTCCAe+gAwIBAgIQtzUvFDCKLUBWQAZ4UnCw5zAKBggqhkjOPQQDAzA3MQswCQYDVQQGDAJV
                UzEoMCYGA1UEAwwfdmVyaWZpZXIubXVsdGlwYXoub3JnIFJlYWRlciBDQTAeFw0yNTA2MTkyMjE2
                MzJaFw0zMDA2MTkyMjE2MzJaMDcxCzAJBgNVBAYMAlVTMSgwJgYDVQQDDB92ZXJpZmllci5tdWx0
                aXBhei5vcmcgUmVhZGVyIENBMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEa6oCzC8rfHfwVOmQf83W
                yHEQFE8HrLK+NxsufJDrSFgMXjhRvPt3fIjlMyRAaf94Y25Ux9tXg+28EzzB/xG7q8P/FQ9nOSJk
                w4cQJVdD/ufN599uVdfp1URdG95Vncuoo4G/MIG8MA4GA1UdDwEB/wQEAwIBBjASBgNVHRMBAf8E
                CDAGAQH/AgEAMFYGA1UdHwRPME0wS6BJoEeGRWh0dHBzOi8vZ2l0aHViLmNvbS9vcGVud2FsbGV0
                LWZvdW5kYXRpb24tbGFicy9pZGVudGl0eS1jcmVkZW50aWFsL2NybDAdBgNVHQ4EFgQUsYQ5hS9K
                buq/6mKtvFHQgfdIhykwHwYDVR0jBBgwFoAUsYQ5hS9Kbuq/6mKtvFHQgfdIhykwCgYIKoZIzj0E
                AwMDaAAwZQIwKh87sK/cMbzuc9PFvyiSRedr2RoP0fuFK0X8ddOpi6hEMOapHL/Gs/QByROCpDpk
                AjEA2yLSJDZEu1GI8uChAsDBZwJPtv5KHUjq1Vpok69SNn+zzb1mNpqmiey+tchPBjZm
                -----END CERTIFICATE-----
                """.trimIndent()
            )
        }

        private val MULTIPAZ_IDENTITY_READER_CERT by lazy {
            X509Cert(
                encoded = "30820261308201E7A00302010202103925792727AC38B28778373ED2A9ADB9300A06082A8648CE3D0403033033310B300906035504060C0255533124302206035504030C1B4D756C746970617A204964656E7469747920526561646572204341301E170D3235303730353132323032315A170D3330303730353132323032315A3033310B300906035504060C0255533124302206035504030C1B4D756C746970617A204964656E74697479205265616465722043413076301006072A8648CE3D020106052B81040022036200043E145F98DA6C32EE4688C4A7DAEC6640046CFF0872E8F7A8DE3005462AE9488E92850B30E2D46FEEFC620A279BEB09470AB20C9F66C584E396A9625BC3E90DFBA54197A3668D901AAA41F493C89E4AC20689794FED1352CD2086413965006C54A381BF3081BC300E0603551D0F0101FF04040302010630120603551D130101FF040830060101FF02010030560603551D1F044F304D304BA049A047864568747470733A2F2F6769746875622E636F6D2F6F70656E77616C6C65742D666F756E646174696F6E2D6C6162732F6964656E746974792D63726564656E7469616C2F63726C301D0603551D0E04160414CFA4AF87907312962E4D7A17646ACC1C45719B21301F0603551D23041830168014CFA4AF87907312962E4D7A17646ACC1C45719B21300A06082A8648CE3D040303036800306502310090FB8F814BCC87DB42957D22B54D20BF45F44CE0CF5734167ED27F5E3E0F5FB57505B797B894175D2BD98BF16CE726EA02305BA4F1ECB894A9DBE27B9BBF988F233C2E0BB0B4BADAA3EC5B3EA9D99C58DAD26128A4B363849E32626A9D5C3CE3E4DA".fromHexByteString()
            )
        }

        private val MULTIPAZ_IDENTITY_READER_CERT_UNTRUSTED_DEVICES by lazy {
            X509Cert(
                encoded = "308202893082020FA003020102021041DFFB3D7133B2623E535E09D9C3B56E300A06082A8648CE3D0403033047310B300906035504060C0255533138303606035504030C2F4D756C746970617A204964656E74697479205265616465722043412028556E74727573746564204465766963657329301E170D3235303731393233303831345A170D3330303731393233303831345A3047310B300906035504060C0255533138303606035504030C2F4D756C746970617A204964656E74697479205265616465722043412028556E747275737465642044657669636573293076301006072A8648CE3D020106052B8104002203620004EA8A139ED395B79C877255FEF2138987262CFBB6CA1F72688D4E89F062C3CA05B2704531DAEC0304F93A007CD84F31A119F3794151306082C4D4352855A752F9C733D2FA32B4B462644769F2F7E53280F1AD519C443AE9462B923C64877EDF91A381BF3081BC300E0603551D0F0101FF04040302010630120603551D130101FF040830060101FF02010030560603551D1F044F304D304BA049A047864568747470733A2F2F6769746875622E636F6D2F6F70656E77616C6C65742D666F756E646174696F6E2D6C6162732F6964656E746974792D63726564656E7469616C2F63726C301D0603551D0E041604149BCFDAFD2059978E21869C7DD28AAF7481EBABC5301F0603551D230418301680149BCFDAFD2059978E21869C7DD28AAF7481EBABC5300A06082A8648CE3D0403030368003065023100A26AA37C97B6935EB64B959ACB7B04053723EFE0CFBDA2C972C96812C8FF1DA4E122C296A909502B180DBB5AC4FD7AF202307F1AAE9412B8162A5B29A7E2A9CEE00059A2A4F9B32370CE1A28E28E5378AD981FBD8D74D0DBDD0373C327595C1006CE".fromHexByteString()
            )
        }

        @Volatile
        private var instance: LegacyAppState? = null

        fun getInstance(): LegacyAppState {
            return instance ?: synchronized(this) {
                instance ?: LegacyAppState(Platform.promptModel).also { instance = it }
            }
        }
    }
}
