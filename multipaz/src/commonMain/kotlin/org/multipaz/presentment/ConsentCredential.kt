package org.multipaz.presentment

import org.multipaz.crypto.X509CertChain
import org.multipaz.trustmanagement.TrustMetadata

/**
 * A data structure for showing a credential request in the UI
 *
 * @property match the match that was made, identifying the credential in a [org.multipaz.document.DocumentStore] to return.
 * @property encryptionRequested whether the resulting credential is requested to be encrypted to a third party.
 * @property encryptionTargetCertificationChain the certificate chain of the third party, if supplied.
 * @property encryptionTargetTrustMetadata trust metadata for the third party, if available.
 */
data class ConsentCredential(
    val match: CredentialPresentmentSetOptionMemberMatch,
    val encryptionRequested: Boolean,
    val encryptionTargetCertificationChain: X509CertChain?,
    val encryptionTargetTrustMetadata: TrustMetadata?
)
