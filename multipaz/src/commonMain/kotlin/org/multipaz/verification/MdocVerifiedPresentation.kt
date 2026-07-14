package org.multipaz.verification

import org.multipaz.cbor.DataItem
import org.multipaz.claim.MdocClaim
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.TransactionType
import org.multipaz.presentment.TransactionData
import kotlin.time.Instant

/**
 * A verified presentation of an ISO mdoc credential
 *
 * @property docType the ISO mdoc document type, e.g. `org.iso.18013.5.1.mDL`.
 * @property transactionResponses response to transaction data associated with the presented
 *  credential as a map keyed by [TransactionType.identifier], and with the value is a map
 *  with an entry for each item in the transaction response data, including "transaction_data_hash".
 */
data class MdocVerifiedPresentation(
    override val documentSignerCertChain: X509CertChain,
    override val issuerSignedClaims: List<MdocClaim>,
    override val deviceSignedClaims: List<MdocClaim>,
    override val zkpUsed: Boolean,
    override val validFrom: Instant?,
    override val validUntil: Instant?,
    override val signedAt: Instant?,
    override val expectedUpdate: Instant?,
    val docType: String,
    val transactionResponses: Map<String, Map<String, DataItem>>?,
    override val vpTokenIdentifier: String?,
    override val transactionData: List<TransactionData<*>>
): VerifiedPresentation()
