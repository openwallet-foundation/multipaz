package org.multipaz.models.verification

import org.multipaz.claim.MdocClaim
import org.multipaz.crypto.X509CertChain
import kotlin.time.Instant

/**
 * A verified presentation of an ISO mdoc credential
 *
 * @property docType the ISO mdoc document type, e.g. `org.iso.18013.5.1.mDL`.
 */
data class MdocVerifiedPresentation(
    override val documentSignerCertChain: X509CertChain,
    override val issuerSignedClaims: List<MdocClaim>,
    override val deviceSignedClaims: List<MdocClaim>,
    override val validFrom: Instant?,
    override val validUntil: Instant?,
    override val signedAt: Instant?,
    override val expectedUpdate: Instant?,
    val docType: String
): VerifiedPresentation(
    documentSignerCertChain = documentSignerCertChain,
    issuerSignedClaims = issuerSignedClaims,
    deviceSignedClaims = deviceSignedClaims,
    validFrom = validFrom,
    validUntil = validUntil,
    signedAt = signedAt,
    expectedUpdate = expectedUpdate
)
