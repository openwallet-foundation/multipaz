package org.multipaz.models.verification

import org.multipaz.claim.JsonClaim
import org.multipaz.crypto.X509CertChain
import kotlin.time.Instant

/**
 * A verified presentation of a JSON-based credential
 *
 * @property vct the Verifiable Credential Type.
 */
data class JsonVerifiedPresentation(
    override val documentSignerCertChain: X509CertChain,
    override val issuerSignedClaims: List<JsonClaim>,
    override val deviceSignedClaims: List<JsonClaim>,
    override val validFrom: Instant?,
    override val validUntil: Instant?,
    override val signedAt: Instant?,
    override val expectedUpdate: Instant?,
    val vct: String
): VerifiedPresentation(
    documentSignerCertChain = documentSignerCertChain,
    issuerSignedClaims = issuerSignedClaims,
    deviceSignedClaims = deviceSignedClaims,
    validFrom = validFrom,
    validUntil = validUntil,
    signedAt = signedAt,
    expectedUpdate = expectedUpdate
)
