package org.multipaz.verification

import org.multipaz.claim.Claim
import org.multipaz.crypto.X509CertChain
import org.multipaz.presentment.TransactionData
import kotlin.time.Instant

/**
 * A verified presentation of credential.
 *
 * This is an abstract base class, see [JsonVerifiedPresentation] and [MdocVerifiedPresentation] for
 * concrete implementations.
 *
 * @property documentSignerCertChain the certificate chain of the document signer key or `null`
 *   if not known.
 * @property issuerSignedClaims the claims signed by the issuer.
 * @property deviceSignedClaims the claims signed by the device.
 * @property zkpUsed if true, a Zero-Knowledge Proof was used to verify the credential.
 * @property validFrom the point in time this presentation is valid from.
 * @property validUntil the point in time this presentation is valid until.
 * @property signedAt the point in time this was signed.
 * @property expectedUpdate the point in time an update is expected, if any.
 * @property vpTokenIdentifier identifier for this document in OpenID4VP response, only set when
 *   OpenID4VP is in use
 * @property transactionData transaction data that were sent in the presentation request,
 *   acknowledged in the response (with the acknowledgement verified).
 */
sealed class VerifiedPresentation() {
    abstract val documentSignerCertChain: X509CertChain?
    abstract val issuerSignedClaims: List<Claim>
    abstract val deviceSignedClaims: List<Claim>
    abstract val zkpUsed: Boolean
    abstract val validFrom: Instant?
    abstract val validUntil: Instant?
    abstract val signedAt: Instant?
    abstract val expectedUpdate: Instant?
    abstract val vpTokenIdentifier: String?
    abstract val transactionData: List<TransactionData<*>>
}
