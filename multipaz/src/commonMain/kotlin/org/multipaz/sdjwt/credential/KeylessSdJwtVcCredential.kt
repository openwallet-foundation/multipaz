package org.multipaz.sdjwt.credential

import org.multipaz.cbor.CborBuilder
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.claim.JsonClaim
import org.multipaz.credential.Credential
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.securearea.SecureArea
import kotlin.time.Instant

class KeylessSdJwtVcCredential : Credential, SdJwtVcCredential {
    override lateinit var vct: String
        private set

    /**
     * Constructs a new [KeyBoundSdJwtVcCredential].
     *
     * @param document the document to add the credential to.
     * @param asReplacementFor the credential this credential will replace, if not null
     * @param domain the domain of the credential
     * @param vct the Verifiable Credential Type.
     */
    private constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String,
        vct: String,
    ) : super(document, asReplacementForIdentifier, domain) {
        this.vct = vct
        // Only the leaf constructors for keyless credentials should add the credential to
        // the document.
        check (this::class == KeylessSdJwtVcCredential::class)
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
        vct = dataItem["vct"].asTstr
    }

    override fun addSerializedData(builder: MapBuilder<CborBuilder>) {
        super.addSerializedData(builder)
        builder.put("vct", vct)
    }

    override suspend fun getClaims(documentTypeRepository: DocumentTypeRepository?): List<JsonClaim> {
        return getClaimsImpl(documentTypeRepository)
    }

    override suspend fun extractValidityFromIssuerData(): Pair<Instant, Instant> =
        extractValidityFromIssuerDataImpl()

    companion object {
        const val CREDENTIAL_TYPE: String = "KeylessSdJwtVcCredential"

        /**
         * Create a [KeyBoundSdJwtVcCredential].
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param vct The Verifiable Credential Type for the credential.
         * @return an uncertified [Credential] which has been added to [document].
         */
        suspend fun create(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            vct: String
        ): KeylessSdJwtVcCredential {
            return KeylessSdJwtVcCredential(
                document,
                asReplacementForIdentifier,
                domain,
                vct
            ).apply {
                addToDocument()
            }
        }
    }
}