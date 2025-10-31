package org.multipaz.presentment.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.util.Logger
import kotlin.collections.iterator
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Test harness for DocumentStore and related types.
 *
 * This provides a test harness for [DocumentStore], [Document], and [Credential]
 * which can be used to test functionality sitting on top of these.
 *
 * Creating a [DocumentStoreTestHarness] is a no-op, call [initialize] to actually
 * populate it.
 */
class DocumentStoreTestHarness {
    companion object {
        const val TAG = "TestDocumentStore"
    }

    lateinit var presentmentSource: PresentmentSource

    lateinit var documentTypeRepository: DocumentTypeRepository

    lateinit var storage: Storage
    lateinit var softwareSecureArea: SoftwareSecureArea
    lateinit var secureAreaRepository: SecureAreaRepository

    lateinit var documentStore: DocumentStore
    lateinit var docMdl: Document
    lateinit var docEuPid: Document
    lateinit var docEuPid2: Document
    lateinit var docPhotoId: Document
    lateinit var docPhotoId2: Document

    lateinit var signedAt: Instant
    lateinit var validFrom: Instant
    lateinit var validUntil: Instant

    lateinit var dsKey: AsymmetricKey.X509Certified
    lateinit var readerRootKey: AsymmetricKey.X509Certified

    private val lock = Mutex()
    private var isInitialized = false

    /**
     * Initializes the [DocumentStoreTestHarness].
     *
     * This creates [documentStore] and with give documents [docMdl], [docPhotoId], [docPhotoId2],
     * [docEuPid], [docEuPid2], all populated with sample data. Each document will have one [MdocCredential]
     * in the domain `mdoc` and the EU PIDs will also have a [KeyBoundSdJwtVcCredential]
     * in the domain `sdjwt`.
     *
     * The [DocumentStore] itself is backed by [EphemeralStorage] and each credential is using
     * a single [SoftwareSecureArea].
     *
     * This method can be called multiple times.
     */
    suspend fun initialize() {
        lock.withLock {
            initializeWithLock()
        }
    }

    private suspend fun initializeWithLock() {
        if (isInitialized) {
            return
        }
        documentTypeRepository = DocumentTypeRepository()
        documentTypeRepository.addDocumentType(DrivingLicense.getDocumentType())
        documentTypeRepository.addDocumentType(PhotoID.getDocumentType())
        documentTypeRepository.addDocumentType(EUPersonalID.getDocumentType())

        storage = EphemeralStorage()

        softwareSecureArea = SoftwareSecureArea.create(storage)
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(softwareSecureArea)
            .build()

        documentStore = buildDocumentStore(storage = storage, secureAreaRepository = secureAreaRepository) {}

        val readerTrustManager = TrustManagerLocal(storage = EphemeralStorage())

        presentmentSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            readerTrustManager = readerTrustManager,
            zkSystemRepository = null,
            preferSignatureToKeyAgreement = true,
            domainMdocSignature = "mdoc",
            domainMdocKeyAgreement = "mdoc_key_agreement",
            domainKeylessSdJwt = "sdjwt_keyless",
            domainKeyBoundSdJwt = "sdjwt"
        )

        val now = Clock.System.now()
        signedAt = now - 1.days
        validFrom = now - 1.days
        validUntil = now + 365.days
        val iacaValidFrom = validFrom
        val iacaValidUntil = validUntil
        val dsValidFrom = validFrom
        val dsValidUntil = validUntil

        val iacaKeyPub = EcPublicKey.fromPem(
            """
                    -----BEGIN PUBLIC KEY-----
                    MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE+QDye70m2O0llPXMjVjxVZz3m5k6agT+
                    wih+L79b7jyqUl99sbeUnpxaLD+cmB3HK3twkA7fmVJSobBc+9CDhkh3mx6n+YoH
                    5RulaSWThWBfMyRjsfVODkosHLCDnbPV
                    -----END PUBLIC KEY-----
                """.trimIndent().trim(),
            EcCurve.P384
        )
        val iacaKey = EcPrivateKey.fromPem(
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

        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey),
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST IACA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = iacaValidFrom,
            validUntil = iacaValidUntil,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )

        val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = dsPrivateKey.publicKey,
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST DS"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = dsValidFrom,
            validUntil = dsValidUntil,
        )
        dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert)), dsPrivateKey)

        val readerRootValidFrom = validFrom
        val readerRootValidUntil = validUntil
        val readerRootPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(readerRootPrivateKey),
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST Reader Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = readerRootValidFrom,
            validUntil = readerRootValidUntil,
            crlUrl = "https://verifier.multipaz.org/crl"
        )
        readerRootKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerRootCert)),
            privateKey = readerRootPrivateKey
        )

        isInitialized = true
    }

    suspend fun provisionStandardDocuments() {
        initialize()
        provisionTestDocuments(
            documentStore = documentStore,
            dsKey = dsKey,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
        )
    }

    suspend fun provisionMdoc(
        displayName: String,
        docType: String,
        data: Map<String, List<Pair<String, DataItem>>>
    ): Document {
        initialize()
        val document = documentStore.createDocument(
            displayName = displayName
        )
        val issuerNamespaces = buildIssuerNamespaces {
            for ((nsName, listOfClaims) in data) {
                addNamespace(nsName) {
                    for ((deName, deValue) in listOfClaims) {
                        addDataElement(deName, deValue)
                    }
                }
            }
        }
        addMdocCredentialWithData(
            document = document,
            docType = docType,
            issuerNamespaces = issuerNamespaces,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            dsKey = dsKey,
        )
        return document
    }

    suspend fun provisionSdJwtVc(
        displayName: String,
        vct: String,
        data: List<Pair<String, JsonElement>>
    ): Document {
        initialize()
        val document = documentStore.createDocument(
            displayName = displayName
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
            dsKey = dsKey,
        )
        return document
    }

    private suspend fun provisionTestDocuments(
        documentStore: DocumentStore,
        dsKey: AsymmetricKey.X509Certified,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
    ) {
        docMdl = provisionDocument(
            documentStore = documentStore,
            displayName = "mDL",
            dsKey = dsKey,
            documentType = DrivingLicense.getDocumentType(),
            overrideMdocClaims = emptyMap(),
            overrideJsonClaims = emptyMap(),
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
        )
        docEuPid = provisionDocument(
            documentStore = documentStore,
            displayName = "EU PID",
            dsKey = dsKey,
            documentType = EUPersonalID.getDocumentType(),
            overrideMdocClaims = mapOf(
                Pair(EUPersonalID.EUPID_NAMESPACE, "given_name") to Tstr("Erika"),
                Pair(EUPersonalID.EUPID_NAMESPACE, "sex") to Uint(2UL),
            ),
            overrideJsonClaims = mapOf(
                "given_name" to JsonPrimitive("Erika"),
                "sex" to JsonPrimitive(2),
            ),
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
        )
        docEuPid2 = provisionDocument(
            documentStore = documentStore,
            displayName = "EU PID 2",
            dsKey = dsKey,
            documentType = EUPersonalID.getDocumentType(),
            overrideMdocClaims = mapOf(
                Pair(EUPersonalID.EUPID_NAMESPACE, "given_name") to Tstr("Max"),
                Pair(EUPersonalID.EUPID_NAMESPACE, "sex") to Uint(1UL),
            ),
            overrideJsonClaims = mapOf(
                "given_name" to JsonPrimitive("Max"),
                "sex" to JsonPrimitive(1),
            ),
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
        )
        docPhotoId = provisionDocument(
            documentStore = documentStore,
            displayName = "Photo ID",
            dsKey = dsKey,
            documentType = PhotoID.getDocumentType(),
            overrideMdocClaims = mapOf(
                Pair(PhotoID.ISO_23220_2_NAMESPACE, "given_name") to Tstr("Erika"),
                Pair(PhotoID.ISO_23220_2_NAMESPACE, "sex") to Uint(2UL),
                Pair(PhotoID.ISO_23220_2_NAMESPACE, "age_over_25") to Simple.FALSE
            ),
            overrideJsonClaims = emptyMap(),
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
        )
        docPhotoId2 = provisionDocument(
            documentStore = documentStore,
            displayName = "Photo ID 2",
            dsKey = dsKey,
            documentType = PhotoID.getDocumentType(),
            overrideMdocClaims = mapOf(
                Pair(PhotoID.ISO_23220_2_NAMESPACE, "given_name") to Tstr("Max"),
                Pair(PhotoID.ISO_23220_2_NAMESPACE, "sex") to Uint(1UL),
                Pair(PhotoID.ISO_23220_2_NAMESPACE, "age_over_25") to Simple.TRUE
            ),
            overrideJsonClaims = emptyMap(),
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
        )
    }

    private suspend fun provisionDocument(
        documentStore: DocumentStore,
        displayName: String,
        dsKey: AsymmetricKey.X509Certified,
        documentType: DocumentType,
        overrideMdocClaims: Map<Pair<String, String>, DataItem>,
        overrideJsonClaims: Map<String, JsonElement>,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
    ): Document {
        val document = documentStore.createDocument(
            displayName = displayName
        )

        if (documentType.mdocDocumentType != null) {
            addMdocCredential(
                document = document,
                documentType = documentType,
                overrideMdocClaims = overrideMdocClaims,
                signedAt = signedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                dsKey = dsKey,
            )
        }

        if (documentType.jsonDocumentType != null) {
            addSdJwtVcCredential(
                document = document,
                documentType = documentType,
                overrideJsonClaims = overrideJsonClaims,
                signedAt = signedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                dsKey = dsKey,
            )
        }

        return document
    }

    private suspend fun addMdocCredential(
        document: Document,
        documentType: DocumentType,
        overrideMdocClaims: Map<Pair<String, String>, DataItem>,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        dsKey: AsymmetricKey.X509Certified,
    ) {
        val issuerNamespaces = buildIssuerNamespaces {
            for ((nsName, ns) in documentType.mdocDocumentType?.namespaces!!) {
                addNamespace(nsName) {
                    for ((deName, de) in ns.dataElements) {
                        val overrideValue = overrideMdocClaims.get(Pair(nsName, deName))
                        if (overrideValue != null) {
                            addDataElement(deName, overrideValue)
                        } else {
                            val sampleValue = de.attribute.sampleValueMdoc
                            if (sampleValue != null) {
                                addDataElement(deName, sampleValue)
                            } else {
                                Logger.w(TAG, "No sample value for data element $deName")
                            }
                        }
                    }
                }
            }
        }
        addMdocCredentialWithData(
            document = document,
            docType = documentType.mdocDocumentType!!.docType,
            issuerNamespaces = issuerNamespaces,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            dsKey = dsKey,
        )
    }

    private suspend fun addMdocCredentialWithData(
        document: Document,
        docType: String,
        issuerNamespaces: IssuerNamespaces,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        dsKey: AsymmetricKey.X509Certified,
    ) {
        // Create authentication keys...
        val mdocCredential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = softwareSecureArea,
            docType = docType,
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )

        // Generate an MSO and issuer-signed data for this authentication key.
        val msoGenerator = MobileSecurityObjectGenerator(
            Algorithm.SHA256,
            docType,
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

    private suspend fun addSdJwtVcCredential(
        document: Document,
        documentType: DocumentType,
        overrideJsonClaims: Map<String, JsonElement>,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        dsKey: AsymmetricKey.X509Certified,
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
                val overrideValue = overrideJsonClaims.get(claimName)
                if (overrideValue != null) {
                    put(claimName, overrideValue)
                } else {
                    val sampleValue = attribute.sampleValueJson
                    if (sampleValue != null) {
                        put(claimName, sampleValue)
                    } else {
                        Logger.w(TAG, "No sample value for claim $claimName")
                    }
                }
            }
        }

        addSdJwtVcCredentialWithData(
            document = document,
            vct = documentType.jsonDocumentType!!.vct,
            identityAttributes = identityAttributes,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            dsKey = dsKey,
        )
    }

    private suspend fun addSdJwtVcCredentialWithData(
        document: Document,
        vct: String,
        identityAttributes: JsonObject,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        dsKey: AsymmetricKey.X509Certified,
    ) {
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            secureArea = softwareSecureArea,
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
    }

}