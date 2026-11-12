package org.multipaz.presentment

import org.multipaz.claim.Claim
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.request.RequestedClaim
import org.multipaz.trustmanagement.TrustMetadata

/**
 * A match of a particular [Credential] from [org.multipaz.document.DocumentStore] for presentment.
 *
 * @property credential the [Credential] to present.
 * @property claims the claims to present along with their request.
 * @property source the source for the request for the match.
 * @property transactionData list of transaction data to use for this credential presentment.
 * @property transactionUserInput additional user input for transactions, indexed by the transaction
 *   type identifier.
 * @property faceMatchNeeded whether face matching is needed for this credential presentment.
 * @property encryptionRequested whether the resulting credential is requested to be encrypted to a third party.
 * @property encryptionTargetCertificationChain the certificate chain of the third party, if supplied.
 * @property encryptionTargetTrustMetadata trust metadata for the third party, if available.
 */
data class PresentmentCredentialMatch(
    val credential: Credential,
    val claims: Map<RequestedClaim, Claim>,
    val source: PresentmentCredentialMatchSource,
    val transactionData: List<TransactionData<*>>,
    val transactionUserInput: Map<String, TransactionUserInput> = emptyMap(),
    val faceMatchNeeded: Boolean = false,
    val encryptionRequested: Boolean = false,
    val encryptionTargetCertificationChain: X509CertChain? = null,
    val encryptionTargetTrustMetadata: TrustMetadata? = null,
)
