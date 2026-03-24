package org.multipaz.presentment

import org.multipaz.claim.Claim
import org.multipaz.credential.Credential
import org.multipaz.request.RequestedClaim

/**
 * A presentment of a particular [Credential] from [org.multipaz.document.DocumentStore].
 *
 * @property credential the [Credential] to present.
 * @property claims the claims to present along with their request.
 * @property source the source for the request for the match
 * @property transactionData list of transaction data to use for this credential presentment
 */
data class CredentialPresentmentSetOptionMemberMatch(
    val credential: Credential,
    val claims: Map<RequestedClaim, Claim>,
    val source: CredentialMatchSource,
    val transactionData: List<TransactionData>
)

