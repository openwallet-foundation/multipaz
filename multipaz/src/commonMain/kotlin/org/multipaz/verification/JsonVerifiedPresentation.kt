package org.multipaz.verification

import kotlinx.serialization.json.JsonElement
import org.multipaz.claim.JsonClaim
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.TransactionType
import org.multipaz.presentment.TransactionData
import kotlin.time.Instant

/**
 * A verified presentation of a JSON-based credential
 *
 * @property vct the Verifiable Credential Type.
 * @property transactionResponses response to transaction data associated with the presented
 *   credential, indexed by [TransactionType.identifier]
 */
data class JsonVerifiedPresentation(
    override val documentSignerCertChain: X509CertChain,
    override val issuerSignedClaims: List<JsonClaim>,
    override val deviceSignedClaims: List<JsonClaim>,
    override val zkpUsed: Boolean,
    override val validFrom: Instant?,
    override val validUntil: Instant?,
    override val signedAt: Instant?,
    override val expectedUpdate: Instant?,
    val vct: String,
    val transactionResponses: Map<String, JsonElement>?,
    override val vpTokenIdentifier: String?,
    override val transactionData: List<TransactionData<*>>
): VerifiedPresentation()
