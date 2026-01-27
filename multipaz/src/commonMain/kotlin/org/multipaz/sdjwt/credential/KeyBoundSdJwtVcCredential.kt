package org.multipaz.sdjwt.credential

import org.multipaz.cbor.CborBuilder
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.claim.JsonClaim
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import kotlin.time.Instant

/**
 * A SD-JWT VC credential, according to [draft-ietf-oauth-sd-jwt-vc-03]
 * (https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/) that is bound to a key
 * stored in a [SecureArea].
 */
class KeyBoundSdJwtVcCredential : SecureAreaBoundCredential, SdJwtVcCredential {
    companion object {
        private const val TAG = "SdJwtVcCredential"
        const val CREDENTIAL_TYPE: String = "KeyBoundSdJwtVcCredential"

        /**
         * Creates a batch of [KeyBoundSdJwtVcCredential] instances with keys created in a single batch operation.
         *
         * This method optimizes the key creation process by using the secure area's batch key creation
         * functionality, which is more efficient than creating keys individually, especially for
         * hardware-backed secure areas where multiple cryptographic operations can be expensive.
         *
         * All credentials in the batch will share the same domain and credential type (vct),
         * but will have unique keys and identifiers.
         *
         * @param numberOfCredentials The number of credentials to create in the batch.
         * @param document The document to add the credentials to.
         * @param domain The domain for all credentials in the batch.
         * @param secureArea The secure area to use for creating keys.
         * @param vct The Verifiable Credential Type for all credentials in the batch.
         * @param createKeySettings The settings to use for key creation, including algorithm parameters.
         * @return A pair containing:
         *   - A list of created [KeyBoundSdJwtVcCredential] instances, ready to be certified
         *   - An optional string containing the compact serialization of a JWS with OpenID4VCI key attestation
         *     data if supported by the secure area.
         */
        suspend fun createBatch(
            numberOfCredentials: Int,
            document: Document,
            domain: String,
            secureArea: SecureArea,
            vct: String,
            createKeySettings: CreateKeySettings
        ): Pair<List<KeyBoundSdJwtVcCredential>, String?> {
            val batchResult = secureArea.batchCreateKey(numberOfCredentials, createKeySettings)
            val credentials = batchResult.keyInfos
                .map { it.alias }
                .map { keyAlias ->
                    KeyBoundSdJwtVcCredential(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = domain,
                        secureArea = secureArea,
                        vct = vct,
                    ).apply {
                        useExistingKey(keyAlias)
                    }
                }
            return Pair(credentials, batchResult.openid4vciKeyAttestationJws)
        }

        /**
         * Create a [KeyBoundSdJwtVcCredential].
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param secureArea The [SecureArea] to use for creating a key.
         * @param vct The Verifiable Credential Type for the credential.
         * @param createKeySettings The settings to use for key creation, including algorithm parameters.
         * @return an uncertified [Credential] which has been added to [document].
         */
        suspend fun create(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            secureArea: SecureArea,
            vct: String,
            createKeySettings: CreateKeySettings
        ): KeyBoundSdJwtVcCredential {
            return KeyBoundSdJwtVcCredential(
                document,
                asReplacementForIdentifier,
                domain,
                secureArea,
                vct
            ).apply {
                generateKey(createKeySettings)
            }
        }

        /**
         * Create a [KeyBoundSdJwtVcCredential] using a key that already exists.
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param secureArea The [SecureArea] to use for creating a key.
         * @param vct The Verifiable Credential Type for the credential.
         * @param existingKeyAlias the alias for the existing key in [secureArea].
         * @return an uncertified [Credential] which has been added to [document].
         */
        suspend fun createForExistingAlias(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            secureArea: SecureArea,
            vct: String,
            existingKeyAlias: String,
        ): KeyBoundSdJwtVcCredential {
            return KeyBoundSdJwtVcCredential(
                document,
                asReplacementForIdentifier,
                domain,
                secureArea,
                vct
            ).apply {
                useExistingKey(keyAlias = existingKeyAlias)
            }
        }
    }

    /**
     * The Verifiable Credential Type - or `vct` - as defined in section 3.2.2.1.1 of
     * [draft-ietf-oauth-sd-jwt-vc-03]
     * (https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/)
     */
    override lateinit var vct: String
        private set

    /**
     * Constructs a new [KeyBoundSdJwtVcCredential].
     *
     * [generateKey] providing [CreateKeySettings] must be called before using this object.
     *
     * @param document the document to add the credential to.
     * @param asReplacementForIdentifier identifier of the credential this credential will replace,
     *     if not null
     * @param domain the domain of the credential
     * @param secureArea the secure area for the authentication key associated with this credential.
     * @param vct the Verifiable Credential Type.
     */
    private constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String,
        secureArea: SecureArea,
        vct: String,
    ) : super(document, asReplacementForIdentifier, domain, secureArea) {
        this.vct = vct
    }

    /**
     * Constructs a Credential from serialized data.
     *
     * [deserialize] providing serialized data must be called before using this object.
     *
     * @param document the [Document] that the credential belongs to.
     */
    constructor(
        document: Document
    ) : super(document)

    override suspend fun deserialize(dataItem: DataItem) {
        super.deserialize(dataItem)
        vct = dataItem["vct"].asTstr
    }

    override fun addSerializedData(builder: MapBuilder<CborBuilder>) {
        super.addSerializedData(builder)
        builder.put("vct", vct)
    }

    override val credentialType: String
        get() = CREDENTIAL_TYPE

    override suspend fun getClaims(documentTypeRepository: DocumentTypeRepository?): List<JsonClaim> {
        return getClaimsImpl(documentTypeRepository)
    }

    override suspend fun extractValidityFromIssuerData(): Pair<Instant, Instant> =
        extractValidityFromIssuerDataImpl()
}