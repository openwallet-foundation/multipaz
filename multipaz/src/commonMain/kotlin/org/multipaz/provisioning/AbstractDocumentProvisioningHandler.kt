package org.multipaz.provisioning

import kotlinx.io.bytestring.ByteString
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.Document
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea

/**
 * Interface to manage [Document] and [Credential] instances during provisioning.
 *
 * [DocumentProvisioningHandler] provides default implementation for this interface.
 */
interface AbstractDocumentProvisioningHandler {

    /**
     * Creates a new [Document] to do initial credential provisioning.
     *
     * @param credentialMetadata information about the credentials that will be provisioned
     * @param issuerMetadata information about the credential issuer
     * @param documentAuthorizationData data that can be used to provision additional credentials
     *  (e.g. for credential refresh)
     * @return new [Document] to hold the provisioned credentials.
     */
    suspend fun createDocument(
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata,
        documentAuthorizationData: ByteString?
    ): Document

    /**
     * Update the [Document] data.
     *
     * This is called after some credentials have been issued.
     * @param document [Document] that is being updated
     * @param display provides updated card art and credential title if provided by the issuer
     * @param documentAuthorizationData updated data that can be used to provision additional
     *  credentials (e.g. for credential refresh)
     */
    suspend fun updateDocument(
        document: Document,
        display: Display?,
        documentAuthorizationData: ByteString?
    )

    /**
     * Clean up after failed initial provisioning (e.g. by deleting the document)
     *
     * @param document [Document] for which the initial provisioning failed
     * @param err provisioning error
     */
    suspend fun cleanupDocumentOnError(document: Document, err: Throwable)

    /**
     * Creates a set of pending key-bound credentials.
     *
     * Provisioning will call [Credential.certify] method on these credentials once the data
     * comes from the issuer. When pending credentials are created, it is very important that their
     * keys are created with appropriate settings, as this is what anchors the whole security model
     * of Digital Credential ecosystem. Parameter [createKeySettings] should be seen as providing
     * the minimal requirements. In particular, [CreateKeySettings.algorithm] should and
     * [CreateKeySettings.nonce] must be honored. In some cases, implementations may want or need
     * to use custom and/or [SecureArea]-specific [CreateKeySettings] to have better control over
     * key properties.
     *
     * TODO: propagate more metadata about issuer key requirements through [CredentialMetadata].
     *
     * It is up to the implementation to determine the number of credentials to create, but it
     * should generally not exceed issuer limit given in [CredentialMetadata.maxBatchSize].
     *
     * @param document [Document] for which credentials are being issued
     * @param credentialMetadata information about credentials being issued
     * @param createKeySettings suggested settings for the key to which credentials are bound
     * @return a list of pending key-bound credentials
     */
    suspend fun createKeyBoundCredentials(
        document: Document,
        credentialMetadata: CredentialMetadata,
        createKeySettings: CreateKeySettings
    ): List<SecureAreaBoundCredential>

    /**
     * Creates a pending keyless credential.
     *
     * @param document [Document] for which the credential is being issued
     * @param credentialMetadata information about the credential being issued
     * @return pending keyless credential
     */
    suspend fun createKeylessCredential(
        document: Document,
        credentialMetadata: CredentialMetadata,
    ): Credential

    /**
     * Clean up after failed not-initial (e.g. credential refresh) provisioning.
     *
     * The simplest way is to remove pending credentials.
     *
     * @param pendingCredentials list of credentials that could not be provisioned
     * @param err provisioning error
     */
    suspend fun cleanupCredentialsOnError(pendingCredentials: List<Credential>, err: Throwable)
}