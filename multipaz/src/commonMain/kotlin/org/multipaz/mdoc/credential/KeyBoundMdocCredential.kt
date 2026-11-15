/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.mdoc.credential

import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.CborBuilder
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.claim.MdocClaim
import org.multipaz.cose.CoseSign1
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mpzpass.MpzPass
import org.multipaz.mpzpass.MpzPassIsoMdoc
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.validation.ValidationResult

/**
 * A key-bound ISO/IEC 18013-5 mdoc credential bound to an authentication key stored in a [SecureArea].
 */
class KeyBoundMdocCredential : SecureAreaBoundCredential, MdocCredential {
    companion object {
        /** The credential type identifier for [KeyBoundMdocCredential]. */
        const val CREDENTIAL_TYPE: String = "KeyBoundMdocCredential"

        /** Legacy credential type identifier kept so existing persisted stores load cleanly. */
        internal const val LEGACY_CREDENTIAL_TYPE: String = "MdocCredential"

        /**
         * Creates a batch of [KeyBoundMdocCredential] instances with keys created in a single batch operation.
         *
         * @param numberOfCredentials The number of credentials to create in the batch.
         * @param document The document to add the credentials to.
         * @param domain The domain for all credentials in the batch.
         * @param secureArea The secure area to use for creating keys.
         * @param docType The docType for all credentials in the batch.
         * @param createKeySettings The settings to use for key creation, including algorithm parameters.
         * @return A pair containing:
         *   - A list of created [KeyBoundMdocCredential] instances, ready to be certified
         *   - An optional string containing the compact serialization of a JWS with OpenID4VCI key attestation
         *     data if supported by the secure area.
         */
        suspend fun createBatch(
            numberOfCredentials: Int,
            document: Document,
            domain: String,
            secureArea: SecureArea,
            docType: String,
            createKeySettings: CreateKeySettings
        ): Pair<List<KeyBoundMdocCredential>, String?> {
            val batchResult = secureArea.batchCreateKey(numberOfCredentials, createKeySettings)
            val credentials = batchResult.keyInfos
                .map { it.alias }
                .map { keyAlias ->
                    KeyBoundMdocCredential(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = domain,
                        secureArea = secureArea,
                        docType = docType,
                    ).apply {
                        useExistingKey(keyAlias)
                    }
                }
            return Pair(credentials, batchResult.openid4vciKeyAttestationJws)
        }

        /**
         * Create a [KeyBoundMdocCredential].
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param secureArea The [SecureArea] to use for creating a key.
         * @param docType The docType for the credential.
         * @param createKeySettings The settings to use for key creation, including algorithm parameters.
         * @return an uncertified [KeyBoundMdocCredential] which has been added to [document].
         */
        suspend fun create(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            secureArea: SecureArea,
            docType: String,
            createKeySettings: CreateKeySettings
        ): KeyBoundMdocCredential {
            return KeyBoundMdocCredential(
                document,
                asReplacementForIdentifier,
                domain,
                secureArea,
                docType
            ).apply {
                generateKey(createKeySettings)
            }
        }

        /**
         * Create a [KeyBoundMdocCredential] using a key that already exists.
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param secureArea The [SecureArea] containing the existing key.
         * @param docType The docType for the credential.
         * @param existingKeyAlias the alias for the existing key in [secureArea].
         * @return an uncertified [KeyBoundMdocCredential] which has been added to [document].
         */
        suspend fun createForExistingAlias(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            secureArea: SecureArea,
            docType: String,
            existingKeyAlias: String,
        ): KeyBoundMdocCredential {
            return KeyBoundMdocCredential(
                document,
                asReplacementForIdentifier,
                domain,
                secureArea,
                docType
            ).apply {
                useExistingKey(keyAlias = existingKeyAlias)
            }
        }
    }

    override lateinit var docType: String
        private set

    private constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String,
        secureArea: SecureArea,
        docType: String
    ) : super(document, asReplacementForIdentifier, domain, secureArea) {
        this.docType = docType
    }

    /**
     * Constructs a Credential from serialized data.
     *
     * [deserialize] providing actual serialized data must be called before using this object.
     *
     * @param document the [Document] that the credential belongs to.
     */
    constructor(document: Document) : super(document)

    override suspend fun deserialize(dataItem: DataItem) {
        super.deserialize(dataItem)
        docType = dataItem["docType"].asTstr
    }

    override fun addSerializedData(builder: MapBuilder<CborBuilder>) {
        super.addSerializedData(builder)
        builder.put("docType", docType)
    }

    override val credentialType: String
        get() = CREDENTIAL_TYPE

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
        check(secureArea is SoftwareSecureArea) {
            "You can only export a credential if it's using a SoftwareSecureArea"
        }
        val swSecureArea = secureArea as SoftwareSecureArea
        val keyInfo = swSecureArea.getKeyInfo(alias)
        val deviceKeyPrivate = swSecureArea.getPrivateKey(alias, keyUnlockData)
        return MpzPass(
            name = document.displayName,
            typeName = document.typeDisplayName,
            cardArt = document.cardArt,
            metadata = document.mpzPassMetadata,
            userAuthenticationRequired = keyInfo.isUserAuthenticationRequired,
            readerIdentifiers = document.readerIdentifiers,
            isoMdoc = listOf(
                MpzPassIsoMdoc(
                    docType = docType,
                    deviceKeyPrivate = deviceKeyPrivate as EcPrivateKey,
                    issuerNamespaces = issuerNamespaces,
                    issuerAuth = issuerAuth
                )
            )
        )
    }
}
