package org.multipaz.document

/**
 * Emitted when a [org.multipaz.credential.Credential]'s usage count is incremented without
 * modifying document metadata, claims, or certified credential availability.
 *
 * @param documentId the identifier of the [Document] owning the credential.
 * @property credentialId the identifier of the [org.multipaz.credential.Credential] whose usage count increased.
 */
class DocumentCredentialUsageUpdated(
    documentId: String,
    val credentialId: String,
) : DocumentEvent(documentId) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is DocumentCredentialUsageUpdated &&
                other.documentId == documentId &&
                other.credentialId == credentialId
            )

    override fun hashCode(): Int = 31 * documentId.hashCode() + credentialId.hashCode()
}
