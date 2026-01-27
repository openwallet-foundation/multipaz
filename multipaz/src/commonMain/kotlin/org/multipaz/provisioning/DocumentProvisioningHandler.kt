package org.multipaz.provisioning

import kotlinx.io.bytestring.ByteString
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.AbstractDocumentMetadata
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import kotlin.math.min

/**
 * Implementation of [AbstractDocumentMetadataHandler] suitable for most uses.
 *
 * TODO: integrate with credential replacement logic
 *
 * @param secureArea credentials will be bound to keys from this [SecureArea]
 * @param documentStore new [Document] will be created in this [DocumentStore]
 * @param mdocCredentialDomain credential domain for (key-bound) ISO mdoc credentials
 * @param sdJwtCredentialDomain credential domain for key-bound IETF SD-JWT credentials
 * @param keylessCredentialDomain credential domain for keyless IETF SD-JWT credentials
 * @param batchSize number of key-bound credentials to request in one batch (but not exceeding
 *  issuer-imposed limit)
 * @param metadataHandler interface that initializes and updates document metadata; it may be
 *  provided if [DocumentStore] uses an [AbstractDocumentMetadata] factory (see
 *  [DocumentStore.Builder.setDocumentMetadataFactory]).
 */
class DocumentProvisioningHandler(
    val secureArea: SecureArea,
    val documentStore: DocumentStore,
    val mdocCredentialDomain: String = "mdoc_user_auth",
    val sdJwtCredentialDomain: String = "sdjwt_user_auth",
    val keylessCredentialDomain: String = "sdjwt_keyless",
    val batchSize: Int = 3,
    val metadataHandler: AbstractDocumentMetadataHandler? = null
): AbstractDocumentProvisioningHandler {
    override suspend fun createDocument(
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata,
        documentAuthorizationData: ByteString?
    ): Document =
        documentStore.createDocument(
            displayName = credentialMetadata.display.text,
            typeDisplayName = credentialMetadata.display.text,
            cardArt = credentialMetadata.display.logo,
            issuerLogo = issuerMetadata.display.logo,
            authorizationData = documentAuthorizationData,
            metadata = metadataHandler?.initializeDocumentMetadata(
                credentialDisplay = credentialMetadata.display,
                issuerDisplay = issuerMetadata.display,
                authorizationData = documentAuthorizationData
            )
        )

    override suspend fun updateDocument(
        document: Document,
        display: Display?,
        documentAuthorizationData: ByteString?
    ) {
        document.edit {
            if (!provisioned && document.getCertifiedCredentials().isNotEmpty()) {
                provisioned = true
            }
            documentAuthorizationData?.let {
                authorizationData = documentAuthorizationData
            }
            if (display != null) {
                displayName = display.text
                display.logo?.let { cardArt = it }
                metadataHandler?.apply {
                    metadata = updateDocumentMetadata(
                        document = document,
                        credentialDisplay = display
                    )
                }
            }
        }
    }

    override suspend fun cleanupDocumentOnError(document: Document, err: Throwable) {
        documentStore.deleteDocument(document.identifier)
    }

    override suspend fun cleanupCredentialsOnError(
        pendingCredentials: List<Credential>,
        err: Throwable
    ) {
        pendingCredentials.forEach { it.document.deleteCredential(it.identifier) }
    }

    override suspend fun createKeyBoundCredentials(
        document: Document,
        credentialMetadata: CredentialMetadata,
        createKeySettings: CreateKeySettings
    ): List<SecureAreaBoundCredential> {
        if (document.getPendingCredentials().isNotEmpty()) {
            // Not all credentials were certified yet, wait for them to be certified first.
            // This should only happen for issuers that support offline certification. If you get
            // here in other cases, you probably should have cleared up pending credentials before
            // attempting to obtain more credentials from the issuer.
            return listOf()
        }
        val credentialCount = min(credentialMetadata.maxBatchSize, batchSize)
        return when (val format = credentialMetadata.format) {
            is CredentialFormat.Mdoc -> {
                (0..<credentialCount).map {
                    MdocCredential.create(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = mdocCredentialDomain,
                        secureArea = secureArea,
                        docType = format.docType,
                        createKeySettings = createKeySettings
                    )
                }
            }

            is CredentialFormat.SdJwt -> {
                (0..<credentialCount).map {
                    KeyBoundSdJwtVcCredential.create(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = sdJwtCredentialDomain,
                        secureArea = secureArea,
                        vct = format.vct,
                        createKeySettings = createKeySettings
                    )
                }
            }
        }
    }

    override suspend fun createKeylessCredential(
        document: Document,
        credentialMetadata: CredentialMetadata
    ): Credential =
        KeylessSdJwtVcCredential.create(
            document,
            null,
            keylessCredentialDomain,
            (credentialMetadata.format as CredentialFormat.SdJwt).vct
        )

    /**
     * Manager document metadata when the document is created and when the metadata is updated
     * from the server.
     */
    interface AbstractDocumentMetadataHandler {
        /**
         * Initializes metadata object when the document is first created.
         *
         * @param credentialDisplay display data from the issuer's credential configuration
         * @param issuerDisplay display data for the issuer itself
         * @param authorizationData data for creating a provisioning session later
         */
        suspend fun initializeDocumentMetadata(
            credentialDisplay: Display,
            issuerDisplay: Display,
            authorizationData: ByteString?
        ): AbstractDocumentMetadata?

        /**
         * Updates metadata for the existing document.
         *
         * @param document document being updated
         * @param credentialDisplay customized display data for the provisioned credentials
         */
        suspend fun updateDocumentMetadata(
            document: Document,
            credentialDisplay: Display
        ): AbstractDocumentMetadata?
    }
}