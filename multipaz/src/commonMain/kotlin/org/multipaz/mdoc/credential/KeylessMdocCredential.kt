package org.multipaz.mdoc.credential

import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.CborBuilder
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.claim.MdocClaim
import org.multipaz.cose.CoseSign1
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mpzpass.MpzPass
import org.multipaz.mpzpass.MpzPassIsoMdoc
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.validation.ValidationResult

/**
 * A non-key-bound (keyless) ISO/IEC 18013-5 mdoc credential.
 *
 * According to ISO/IEC 18013-5 Second Edition, a non-key-bound mdoc omits `deviceKeyInfo` in the
 * [MobileSecurityObject] and omits `deviceSigned` in `Document` presentations, and may only satisfy
 * a `DocRequest` when `nonKeyBoundSupported` is `true` (or DCQL `require_cryptographic_holder_binding`
 * is `false`).
 */
class KeylessMdocCredential : Credential, MdocCredential {
    override lateinit var docType: String
        private set

    private constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String,
        docType: String,
    ) : super(document, asReplacementForIdentifier, domain) {
        this.docType = docType
        check(this::class == KeylessMdocCredential::class)
    }

    /**
     * Constructs a Credential from serialized data.
     *
     * [deserialize] providing actual serialized data must be called before using this object.
     *
     * @param document the [Document] that the credential belongs to.
     */
    constructor(document: Document) : super(document)

    override val credentialType: String
        get() = CREDENTIAL_TYPE

    override suspend fun deserialize(dataItem: DataItem) {
        super.deserialize(dataItem)
        docType = dataItem["docType"].asTstr
    }

    override fun addSerializedData(builder: MapBuilder<CborBuilder>) {
        super.addSerializedData(builder)
        builder.put("docType", docType)
    }

    private val cache = MdocCredentialCache()

    override val issuerSigned: DataItem
        get() = cache.getParsed(issuerProvidedData).issuerSigned

    override val issuerAuth: CoseSign1
        get() = cache.getParsed(issuerProvidedData).issuerAuth

    override val issuerNamespaces: IssuerNamespaces
        get() = cache.getParsed(issuerProvidedData).issuerNamespaces
            ?: IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])

    override val mso: MobileSecurityObject
        get() = cache.getParsed(issuerProvidedData).mso

    override val issuerCertChain: X509CertChain
        get() = cache.getParsed(issuerProvidedData).issuerCertChain

    override suspend fun extractValidityFromIssuerData(): Pair<Instant, Instant> {
        return Pair(mso.validFrom, mso.validUntil)
    }

    override suspend fun validate(
        issuerProvidedAuthenticationData: ByteString,
        now: Instant?
    ): ValidationResult = validateMdocImpl(issuerProvidedAuthenticationData, now)

    override suspend fun getClaims(
        documentTypeRepository: DocumentTypeRepository?,
        locales: List<String>
    ): List<MdocClaim> = getCachedClaimsImpl(cache, documentTypeRepository, locales)

    override suspend fun exportToMpzPass(keyUnlockData: KeyUnlockData?): MpzPass {
        return MpzPass(
            name = document.displayName,
            typeName = document.typeDisplayName,
            cardArt = document.cardArt,
            metadata = document.mpzPassMetadata,
            readerIdentifiers = document.readerIdentifiers,
            isoMdoc = listOf(
                MpzPassIsoMdoc(
                    docType = docType,
                    deviceKeyPrivate = null,
                    issuerNamespaces = issuerNamespaces,
                    issuerAuth = issuerAuth
                )
            )
        )
    }

    companion object {
        /** The credential type identifier for [KeylessMdocCredential]. */
        const val CREDENTIAL_TYPE: String = "KeylessMdocCredential"

        /**
         * Create a [KeylessMdocCredential].
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param docType The docType for the credential.
         * @return an uncertified [KeylessMdocCredential] which has been added to [document].
         */
        suspend fun create(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            docType: String,
        ): KeylessMdocCredential {
            return KeylessMdocCredential(
                document = document,
                asReplacementForIdentifier = asReplacementForIdentifier,
                domain = domain,
                docType = docType,
            ).apply {
                addToDocument()
            }
        }
    }
}
