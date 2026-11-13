package org.multipaz.openid.dcql

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.claim.findMatchingClaim
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.request.RequestedClaim
import org.multipaz.verification.JsonVerifiedPresentation
import org.multipaz.verification.MdocVerifiedPresentation
import org.multipaz.verification.VerifiedPresentation

/**
 * DCQL Credential Query.
 *
 * Reference: OpenID4VP 1.0 Section 6.1.
 *
 * @property id the assigned identifier for the Credential Query.
 * @property format the requested format of the credential e.g. `mso_mdoc` or `dc+sd-jwt`.
 * @property mdocDocType the ISO mdoc doctype or `null` if format isn't `mso_mdoc`.
 * @property vctValues the array of Verifiable Credential Types or `null` if format isn't `dc+sd-jwt`.
 * @property issuerIdentifiers the list of Authority Key Identifiers from `trusted_authorities` or empty.
 * @property multiple whether multiple credentials may be returned for this query.
 * @property claims a list of claims being requested.
 * @property claimSets a list of claim sets.
 * @property requireCryptographicHolderBinding whether cryptographic holder binding is required.
 */
data class DcqlCredentialQuery(
    val id: String,
    val format: String,
    val meta: JsonObject,
    val multiple: Boolean = false,

    // from meta
    val mdocDocType: String? = null,
    val vctValues: List<String>? = null,

    // from trusted_authorities
    val issuerIdentifiers: List<ByteString> = emptyList(),

    val claims: List<RequestedClaim>,
    val claimSets: List<DcqlClaimSet>,

    internal val claimIdToClaim: Map<String, RequestedClaim>,
    val requireCryptographicHolderBinding: Boolean = true,
) {
    /**
     * Verifies that [presentation] matches this credential query's format, type (`vct` or `docType`),
     * and requested claims or `claim_sets`.
     *
     * @param presentation the verified presentation to check.
     * @param documentTypeRepository optional [DocumentTypeRepository] used to resolve verifier-approved
     *   `vct` aliases via [org.multipaz.documenttype.JsonDocumentType.otherVcts].
     * @throws IllegalStateException if [presentation] does not satisfy this query.
     */
    fun checkPresentationMatches(
        presentation: VerifiedPresentation,
        documentTypeRepository: DocumentTypeRepository? = null,
    ) {
        val candidateClaims = when (format) {
            "dc+sd-jwt" -> {
                val jsonPresentation = presentation as? JsonVerifiedPresentation
                    ?: throw IllegalStateException("Expected SD-JWT presentation for query '$id'")
                val matchesVct = vctValues != null && (
                    jsonPresentation.vct in vctValues ||
                        (documentTypeRepository != null &&
                            vctValues.any { documentTypeRepository.vctMatches(jsonPresentation.vct, it) })
                    )
                if (!matchesVct) {
                    throw IllegalStateException(
                        "Presented SD-JWT vct '${jsonPresentation.vct}' is not in allowed vct_values $vctValues for query '$id'"
                    )
                }
                presentation.issuerSignedClaims
            }
            "mso_mdoc" -> {
                val mdocPresentation = presentation as? MdocVerifiedPresentation
                    ?: throw IllegalStateException("Expected mdoc presentation for query '$id'")
                if (mdocPresentation.zkpUsed) {
                    throw IllegalStateException("Unexpected ZK presentation for 'mso_mdoc' query '$id'")
                }
                if (mdocPresentation.docType != mdocDocType) {
                    throw IllegalStateException(
                        "Presented mdoc docType '${mdocPresentation.docType}' does not match requested docType '$mdocDocType' for query '$id'"
                    )
                }
                presentation.issuerSignedClaims + presentation.deviceSignedClaims
            }
            "mso_mdoc_zk" -> {
                val mdocPresentation = presentation as? MdocVerifiedPresentation
                    ?: throw IllegalStateException("Expected mdoc ZK presentation for query '$id'")
                if (!mdocPresentation.zkpUsed) {
                    throw IllegalStateException("Expected ZK presentation for 'mso_mdoc_zk' query '$id'")
                }
                if (mdocPresentation.docType != mdocDocType) {
                    throw IllegalStateException(
                        "Presented mdoc docType '${mdocPresentation.docType}' does not match requested docType '$mdocDocType' for query '$id'"
                    )
                }
                val allowedZkSpecs = (meta["zk_system_type"] as? JsonArray)?.map {
                    it.jsonObject["id"]!!.jsonPrimitive.content
                }
                if (allowedZkSpecs != null && mdocPresentation.zkSystemSpecId !in allowedZkSpecs) {
                    throw IllegalStateException(
                        "Presented ZK system spec '${mdocPresentation.zkSystemSpecId}' is not in allowed zk_system_type $allowedZkSpecs for query '$id'"
                    )
                }
                presentation.issuerSignedClaims + presentation.deviceSignedClaims
            }
            else -> throw IllegalStateException("Unsupported format '$format' for query '$id'")
        }
        if (issuerIdentifiers.isNotEmpty()) {
            val certChain = presentation.documentSignerCertChain
                ?: throw IllegalStateException(
                    "Presented credential has no certificate chain to match trusted_authorities for query '$id'"
                )
            val requiredAkis = issuerIdentifiers.toSet()
            val matchesTrustedAuthority = certChain.certificates.any { cert ->
                cert.authorityKeyIdentifier?.let { aki ->
                    requiredAkis.contains(ByteString(aki))
                } ?: false
            }
            if (!matchesTrustedAuthority) {
                throw IllegalStateException(
                    "Presented credential certificate chain does not match trusted_authorities for query '$id'"
                )
            }
        }
        if (claimSets.isEmpty()) {
            for (requestedClaim in claims) {
                if (candidateClaims.findMatchingClaim(requestedClaim) == null) {
                    throw IllegalStateException(
                        "Missing or non-matching requested claim $requestedClaim for query '$id'"
                    )
                }
            }
        } else {
            val satisfiedSet = claimSets.any { claimSet ->
                claimSet.claimIdentifiers.all { claimId ->
                    val requestedClaim = claimIdToClaim[claimId]
                    requestedClaim != null && candidateClaims.findMatchingClaim(requestedClaim) != null
                }
            }
            if (!satisfiedSet) {
                throw IllegalStateException(
                    "Presented credential does not satisfy any claim_set for query '$id'"
                )
            }
        }
    }
}