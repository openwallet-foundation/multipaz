package org.multipaz.compose.presentment

import org.multipaz.claim.Claim
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.Icon
import org.multipaz.presentment.PresentmentCredentialMatch
import org.multipaz.presentment.PresentmentUseCase
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.trustmanagement.TrustMetadata

/**
 * A claim entry displayed in the consent prompt's claims grid, either representing an individual
 * [Claim] or a collapsed [org.multipaz.documenttype.DocumentAttributeGroup] pseudo-claim.
 */
internal data class DisplayClaim(
    val displayName: String,
    val icon: Icon?,
    val sensitivity: DocumentAttributeSensitivity,
)

/**
 * One section of requested claims within a [DocumentMatchGroup], sharing the same recipient
 * (either the direct requester or a specific encryption target) and data-retention flag.
 */
internal data class ClaimSection(
    val storesData: Boolean,
    val encryptionRequested: Boolean,
    val encryptionTargetCertificationChain: X509CertChain?,
    val encryptionTargetTrustMetadata: TrustMetadata?,
    val claims: List<Claim>,
    val displayClaims: List<DisplayClaim> = collapseClaimsForDisplay(claims),
)

/**
 * All [PresentmentCredentialMatch]es within a single solution that reference the same [Document],
 * grouped together so the document card is shown once rather than repeated per request part.
 */
internal data class DocumentMatchGroup(
    val firstMatchIndex: Int,
    val matches: List<PresentmentCredentialMatch>,
    val claimSections: List<ClaimSection>,
) {
    val credential: Credential get() = matches.first().credential
    val document: Document get() = credential.document
}

private data class RecipientBucketKey(
    val encryptionRequested: Boolean,
    val encryptionTargetCertificationChain: X509CertChain?,
    val encryptionTargetTrustMetadata: TrustMetadata?,
)

internal fun List<Claim>.sortedBySensitivity(): List<Claim> =
    sortedByDescending { (it.attribute?.sensitivity ?: DocumentAttributeSensitivity.PII).ordinal }

internal fun collapseClaimsForDisplay(claims: List<Claim>): List<DisplayClaim> {
    val groupCounts = mutableMapOf<String, Int>()
    for (claim in claims) {
        val group = claim.attribute?.group ?: continue
        groupCounts[group.identifier] = (groupCounts[group.identifier] ?: 0) + 1
    }
    val emittedGroups = mutableSetOf<String>()
    val result = mutableListOf<DisplayClaim>()
    for (claim in claims) {
        val group = claim.attribute?.group
        if (group != null && (groupCounts[group.identifier] ?: 0) >= group.minClaimsToGroup) {
            if (emittedGroups.add(group.identifier)) {
                result.add(
                    DisplayClaim(
                        displayName = group.displayName,
                        icon = group.icon,
                        sensitivity = group.sensitivity,
                    )
                )
            }
        } else {
            result.add(
                DisplayClaim(
                    displayName = claim.displayName,
                    icon = claim.attribute?.icon,
                    sensitivity = claim.attribute?.sensitivity ?: DocumentAttributeSensitivity.PII,
                )
            )
        }
    }
    return result.sortedByDescending { it.sensitivity.ordinal }
}

/**
 * Groups [matches] from a single use-case solution by [Document.identifier], preserving first-seen
 * document order. Within each document group, direct (unencrypted) claims precede encrypted claims,
 * and non-retained claims precede retained claims for each recipient.
 */
internal fun groupMatchesByDocument(
    matches: List<PresentmentCredentialMatch>,
): List<DocumentMatchGroup> {
    val groupsByDocId = linkedMapOf<String, Pair<Int, MutableList<PresentmentCredentialMatch>>>()
    matches.forEachIndexed { index, match ->
        val docId = match.credential.document.identifier
        val entry = groupsByDocId.getOrPut(docId) { index to mutableListOf() }
        entry.second.add(match)
    }
    return groupsByDocId.values.map { (firstIndex, groupMatches) ->
        DocumentMatchGroup(
            firstMatchIndex = firstIndex,
            matches = groupMatches,
            claimSections = buildClaimSections(groupMatches),
        )
    }
}

/**
 * Returns the set of [Document.identifier]s within [documentGroups] that should render a trailing
 * chevron to navigate to the solution chooser.
 *
 * - When [PresentmentUseCase.optional] is `true`, every displayed document can be omitted by
 *   selecting "Don't share anything", so all documents in [documentGroups] are switchable.
 * - When [PresentmentUseCase.optional] is `false` and there is at most one solution, no document
 *   is switchable.
 * - When [PresentmentUseCase.optional] is `false` and there are multiple solutions, any document
 *   that appears in *every* solution is suppressed (unless every displayed document appears in
 *   every solution, in which case all displayed documents remain switchable so the chooser stays
 *   reachable).
 */
internal fun switchableDocumentIds(
    useCase: PresentmentUseCase,
    documentGroups: List<DocumentMatchGroup>,
): Set<String> {
    if (useCase.optional || (useCase.allowMultiple && useCase.solutions.size > 1)) {
        return documentGroups.mapTo(mutableSetOf()) { it.document.identifier }
    }
    if (useCase.solutions.size <= 1) {
        return emptySet()
    }
    val docIdsPerSolution = useCase.solutions.map { sol ->
        sol.matches.mapTo(mutableSetOf()) { it.credential.document.identifier }
    }
    val varyingIds = documentGroups
        .map { it.document.identifier }
        .filterTo(mutableSetOf()) { docId ->
            !docIdsPerSolution.all { docId in it }
        }
    return varyingIds.ifEmpty {
        documentGroups.mapTo(mutableSetOf()) { it.document.identifier }
    }
}

/**
 * One or more [DocumentMatchGroup]s that can be rendered together as stacked credential cards
 * sharing a single claims section when their displayed claim sections match and they have no
 * transaction data.
 */
internal data class StackedDocumentMatchGroup(
    val groups: List<DocumentMatchGroup>,
) {
    val firstGroup: DocumentMatchGroup get() = groups.first()
    val claimSections: List<ClaimSection> get() = firstGroup.claimSections
}

private data class ClaimSectionSignature(
    val storesData: Boolean,
    val encryptionRequested: Boolean,
    val encryptionTargetCertificationChain: X509CertChain?,
    val encryptionTargetTrustMetadata: TrustMetadata?,
    val displayClaims: List<DisplayClaim>,
)

private fun ClaimSection.toSignature() = ClaimSectionSignature(
    storesData = storesData,
    encryptionRequested = encryptionRequested,
    encryptionTargetCertificationChain = encryptionTargetCertificationChain,
    encryptionTargetTrustMetadata = encryptionTargetTrustMetadata,
    displayClaims = displayClaims,
)

/**
 * Merges consecutive compatible [DocumentMatchGroup]s into [StackedDocumentMatchGroup]s when
 * [stackCompatibleGroups] is `true` (e.g. when multiple solutions are selected for a use-case).
 */
internal fun stackDocumentGroups(
    documentGroups: List<DocumentMatchGroup>,
    stackCompatibleGroups: Boolean,
): List<StackedDocumentMatchGroup> {
    if (!stackCompatibleGroups || documentGroups.size <= 1) {
        return documentGroups.map { StackedDocumentMatchGroup(listOf(it)) }
    }
    val result = mutableListOf<StackedDocumentMatchGroup>()
    for (group in documentGroups) {
        val hasTx = group.matches.any { it.transactionData.isNotEmpty() }
        val sig = group.claimSections.map { it.toSignature() }
        val last = result.lastOrNull()
        val canMergeWithLast = last != null &&
            !hasTx &&
            last.groups.none { g -> g.matches.any { it.transactionData.isNotEmpty() } } &&
            last.claimSections.map { it.toSignature() } == sig
        if (canMergeWithLast) {
            result[result.lastIndex] = StackedDocumentMatchGroup(last.groups + group)
        } else {
            result.add(StackedDocumentMatchGroup(listOf(group)))
        }
    }
    return result
}

private fun buildClaimSections(
    groupMatches: List<PresentmentCredentialMatch>,
): List<ClaimSection> {
    val orderedMatches = groupMatches.filter { !it.encryptionRequested } +
        groupMatches.filter { it.encryptionRequested }
    val byRecipient = linkedMapOf<RecipientBucketKey, MutableList<PresentmentCredentialMatch>>()
    for (match in orderedMatches) {
        val key = RecipientBucketKey(
            encryptionRequested = match.encryptionRequested,
            encryptionTargetCertificationChain = match.encryptionTargetCertificationChain,
            encryptionTargetTrustMetadata = match.encryptionTargetTrustMetadata,
        )
        byRecipient.getOrPut(key) { mutableListOf() }.add(match)
    }

    val sections = mutableListOf<ClaimSection>()
    for ((recipient, recipientMatches) in byRecipient) {
        val notStoredClaims = recipientMatches.flatMap { match ->
            match.claims.mapNotNull { (requestedClaim, claim) ->
                if (requestedClaim is MdocRequestedClaim && requestedClaim.intentToRetain) {
                    null
                } else {
                    claim
                }
            }
        }.distinct().sortedBySensitivity()

        val storedClaims = recipientMatches.flatMap { match ->
            match.claims.mapNotNull { (requestedClaim, claim) ->
                if (requestedClaim is MdocRequestedClaim && requestedClaim.intentToRetain) {
                    claim
                } else {
                    null
                }
            }
        }.distinct().sortedBySensitivity()

        if (notStoredClaims.isNotEmpty()) {
            sections.add(
                ClaimSection(
                    storesData = false,
                    encryptionRequested = recipient.encryptionRequested,
                    encryptionTargetCertificationChain = recipient.encryptionTargetCertificationChain,
                    encryptionTargetTrustMetadata = recipient.encryptionTargetTrustMetadata,
                    claims = notStoredClaims,
                )
            )
        }
        if (storedClaims.isNotEmpty()) {
            sections.add(
                ClaimSection(
                    storesData = true,
                    encryptionRequested = recipient.encryptionRequested,
                    encryptionTargetCertificationChain = recipient.encryptionTargetCertificationChain,
                    encryptionTargetTrustMetadata = recipient.encryptionTargetTrustMetadata,
                    claims = storedClaims,
                )
            )
        }
    }
    return sections
}
