package org.multipaz.consent

import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentUseCase
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

/**
 * Default [ClassifyRiskFn] implementation that evaluates the [ConsentRiskAssessment] for a
 * [presentmentData] given the [trustedRequesterIdentity], the active [selections] per use-case,
 * and the classified [purposes].
 *
 * ### Implementation Details
 *
 * The risk level and accompanying [ConsentRiskReason]s are evaluated in the following order:
 *
 * 1. **Requester trust baseline**:
 *    - The requester is considered trusted (`isRequesterTrusted = true`) if and only if
 *      [trustedRequesterIdentity] is non-`null`.
 *    - When `isRequesterTrusted` is `false`, the requester is either **unverified**
 *      (`requester.requesterIdentities.isNotEmpty()`, i.e. signed with a certificate chain whose
 *      root is not in the wallet's trust list) or **anonymous** (`requester.requesterIdentities.isEmpty()`,
 *      i.e. did not provide a digital signature/certificate).
 *    - Whenever `isRequesterTrusted` is `false` and the evaluated risk level is
 *      [ConsentRiskLevel.WARNING] or [ConsentRiskLevel.HIGH_RISK], either
 *      [ConsentRiskReason.UNTRUSTED_REQUESTER] (for an unverified requester) or
 *      [ConsentRiskReason.ANONYMOUS_REQUESTER] (for an anonymous requester) is included in the
 *      returned [ConsentRiskAssessment.reasons].
 *
 * 2. **No credentials selected** (all use-cases are optional and omitted with selection `-1`):
 *    - If `isRequesterTrusted` is `true`, returns [ConsentRiskLevel.NORMAL] with no reasons.
 *    - If `isRequesterTrusted` is `false` and [Requester.isProximity] is `true`, returns
 *      [ConsentRiskLevel.NORMAL] with [ConsentRiskReason.UNTRUSTED_REQUESTER] or
 *      [ConsentRiskReason.ANONYMOUS_REQUESTER].
 *    - Otherwise (untrusted over-the-Internet or app-to-app requester), returns
 *      [ConsentRiskLevel.WARNING] with [ConsentRiskReason.UNTRUSTED_REQUESTER] or
 *      [ConsentRiskReason.ANONYMOUS_REQUESTER].
 *
 * 3. **Unusual request or unknown schema attribute** (checked for both trusted and untrusted
 *    requesters):
 *    - If any selected credential match contains a claim whose `attribute == null` (not defined in
 *      the credential schema), or if [purposes] is empty and the selection violates the claim rules
 *      of a known document type, violates declared [PresentmentUseCase.purposeHints], or consists
 *      entirely of known document types (whose purposes were rejected by a custom classifier),
 *      returns [ConsentRiskLevel.HIGH_RISK] with [ConsentRiskReason.UNUSUAL_REQUEST] (plus
 *      [ConsentRiskReason.UNTRUSTED_REQUESTER] or [ConsentRiskReason.ANONYMOUS_REQUESTER] if the
 *      requester is untrusted/anonymous).
 *    - If [purposes] is empty solely because a selected credential uses a custom or generic
 *      document type (whose requested claims are all defined in its schema and no
 *      [PresentmentUseCase.purposeHints] are violated), the request is not flagged as
 *      [ConsentRiskReason.UNUSUAL_REQUEST] and evaluation continues below.
 *
 * 4. **Untrusted claim-level encryption target** (checked for both trusted and untrusted
 *    requesters):
 *    - If any selected credential match requests encryption (`encryptionRequested == true`) and
 *      its encryption target is not in a trust list (`encryptionTargetTrustMetadata == null`),
 *      [ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET] is added.
 *    - If any such untrusted encryption target is **anonymous** (`encryptionTargetCertificationChain == null`),
 *      the request is escalated to [ConsentRiskLevel.HIGH_RISK]; if all such targets are
 *      **unverified** (`encryptionTargetCertificationChain != null`, i.e. presented a certificate
 *      chain not in the trust list), it escalates to [ConsentRiskLevel.WARNING] (unless another
 *      condition escalates to [ConsentRiskLevel.HIGH_RISK]).
 *
 * 5. **Untrusted requester checks** (when `isRequesterTrusted` is `false`):
 *    - **Sensitive request escalations** (all matching reasons are collected together alongside
 *      [ConsentRiskReason.UNTRUSTED_REQUESTER] / [ConsentRiskReason.ANONYMOUS_REQUESTER] and any
 *      [ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET]):
 *      - **PII requested**: If the maximum claim sensitivity across the selected matches is at
 *        least [DocumentAttributeSensitivity.PII], adds [ConsentRiskReason.PII_REQUESTED]. Note
 *        that by default this check inspects all selected matches—including matches encrypted for a
 *        trusted encryption target—because the untrusted intermediary still has knowledge of the
 *        transaction; however, this is a policy decision, and a wallet whose threat model accepts
 *        an untrusted intermediary forwarding encrypted claims to a trusted encryption target could
 *        override [ClassifyRiskFn] to classify such requests as [ConsentRiskLevel.NORMAL].
 *      - **Intent to retain**: If any requested mdoc claim in the selected matches has
 *        [MdocRequestedClaim.intentToRetain] set to `true` and its attribute sensitivity is at
 *        least [DocumentAttributeSensitivity.PII] (for proximity requests, where
 *        [Requester.isProximity] is `true`) or at least
 *        [DocumentAttributeSensitivity.PORTRAIT_IMAGE] (for over-the-Internet or app-to-app
 *        requests, where [Requester.isProximity] is `false`), adds
 *        [ConsentRiskReason.INTENT_TO_RETAIN].
 *      - **Transaction data**: If any selected credential match includes non-empty
 *        `transactionData`, adds [ConsentRiskReason.TRANSACTION_DATA].
 *      - If one or more of the above sensitive reasons matched:
 *        - If the requester is **anonymous** (`requester.requesterIdentities.isEmpty()`) and
 *          **non-proximity** (`!requester.isProximity`, i.e. over-the-Internet or app-to-app), or
 *          if an anonymous encryption target is present, returns [ConsentRiskLevel.HIGH_RISK] with
 *          all matched reasons.
 *        - Otherwise (the requester signed the request with an unverified certificate chain, or the
 *          request is over proximity), returns [ConsentRiskLevel.WARNING] with all matched reasons.
 *    - **Low-sensitivity request**: Otherwise (e.g., an untrusted requester asking only for
 *      non-PII claims without sensitive retention or transaction data):
 *      - If an untrusted encryption target is present, returns [ConsentRiskLevel.HIGH_RISK] (for an
 *        anonymous encryption target) or [ConsentRiskLevel.WARNING] (for an unverified encryption
 *        target) with `UNTRUSTED_REQUESTER` and `UNTRUSTED_ENCRYPTION_TARGET`.
 *      - If [Requester.isProximity] is `true`, returns [ConsentRiskLevel.NORMAL] with
 *        [ConsentRiskReason.UNTRUSTED_REQUESTER] or [ConsentRiskReason.ANONYMOUS_REQUESTER],
 *        since the user is physically in proximity to the relying party (which is already free
 *        to observe and capture their likeness).
 *      - If [Requester.isProximity] is `false` (over-the-Internet or app-to-app), returns
 *        [ConsentRiskLevel.WARNING] with [ConsentRiskReason.UNTRUSTED_REQUESTER].
 *
 * 6. **Trusted requester**:
 *    - When `isRequesterTrusted` is `true` and step 3 did not trigger:
 *      - If an untrusted encryption target is present (step 4), returns
 *        [ConsentRiskLevel.HIGH_RISK] (when `encryptionTargetCertificationChain == null`) or
 *        [ConsentRiskLevel.WARNING] (when `encryptionTargetCertificationChain != null`) with
 *        [ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET].
 *      - Otherwise returns [ConsentRiskLevel.NORMAL] with an empty set of reasons.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity trust information for the requester, or `null` if untrusted.
 * @param presentmentData the combinations of credentials and claims that the user can select.
 * @param selections the selected solution indices for each [PresentmentUseCase] in
 *   [PresentmentData.useCases], or an empty list for an omitted optional use-case. Defaults to
 *   [PresentmentData.defaultSelections].
 * @param purposes the classified [PresentmentPurpose]s for [selections]. Defaults to
 *   `defaultClassifyPurposes(requester, trustedRequesterIdentity, presentmentData, selections)`.
 * @return the evaluated [ConsentRiskAssessment].
 */
fun defaultClassifyRisk(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<List<Int>> = presentmentData.defaultSelections(),
    purposes: Set<PresentmentPurpose> = defaultClassifyPurposes(
        requester = requester,
        trustedRequesterIdentity = trustedRequesterIdentity,
        presentmentData = presentmentData,
        selections = selections,
    ),
): ConsentRiskAssessment {
    require(selections.size == presentmentData.useCases.size) {
        "Expected selections length (${selections.size}) to match useCases length (${presentmentData.useCases.size})"
    }

    var anySelectedUseCase = false
    presentmentData.useCases.forEachIndexed { index, useCase ->
        val selectedIndices = selections[index]
        if (selectedIndices.isEmpty()) {
            require(useCase.optional) { "Cannot omit non-optional use-case at index $index" }
        } else {
            for (selectionIndex in selectedIndices) {
                require(selectionIndex in useCase.solutions.indices) {
                    "Out-of-bounds solution index $selectionIndex for use-case at index $index"
                }
            }
            anySelectedUseCase = true
        }
    }

    val isRequesterTrusted = trustedRequesterIdentity != null
    val isRequesterAnonymous = !isRequesterTrusted && requester.requesterIdentities.isEmpty()
    val baseReasons = if (isRequesterTrusted) {
        emptySet()
    } else if (isRequesterAnonymous) {
        setOf(ConsentRiskReason.ANONYMOUS_REQUESTER)
    } else {
        setOf(ConsentRiskReason.UNTRUSTED_REQUESTER)
    }

    if (!anySelectedUseCase) {
        return if (isRequesterTrusted || requester.isProximity) {
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, baseReasons)
        } else {
            ConsentRiskAssessment(ConsentRiskLevel.WARNING, baseReasons)
        }
    }

    val selection = presentmentData.toPresentmentSelection(selections)
    val hasUnknownClaim = selection.matches.any { match ->
        match.claims.values.any { it.attribute == null }
    }
    val isUnusual = hasUnknownClaim || (
        purposes.isEmpty() && (
            presentmentData.hasUnusualRequest(selections) ||
                selection.matches.all { it.isKnownPurposeDocTypeOrVct() }
        )
    )
    if (isUnusual) {
        return ConsentRiskAssessment(
            level = ConsentRiskLevel.HIGH_RISK,
            reasons = baseReasons + ConsentRiskReason.UNUSUAL_REQUEST,
        )
    }

    val untrustedEncryptionMatches = selection.matches.filter {
        it.encryptionRequested && it.encryptionTargetTrustMetadata == null
    }
    val hasUntrustedEncryptionTarget = untrustedEncryptionMatches.isNotEmpty()
    val hasAnonymousEncryptionTarget = untrustedEncryptionMatches.any {
        it.encryptionTargetCertificationChain == null
    }

    if (!isRequesterTrusted) {
        val reasons = baseReasons.toMutableSet()
        if (hasUntrustedEncryptionTarget) {
            reasons.add(ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET)
        }
        // Note: By default we evaluate maxSensitivity across all selected matches—even if a match
        // is encrypted for a trusted encryption target—because the untrusted/anonymous requester
        // still has knowledge of the transaction. Like everything else in defaultClassifyRisk,
        // this is policy; a wallet whose threat model accepts an untrusted intermediary forwarding
        // encrypted claims to a trusted recipient could classify that case as ConsentRiskLevel.NORMAL.
        val maxSensitivity = selection.getMaxSensitivity()
        if (maxSensitivity != null && maxSensitivity >= DocumentAttributeSensitivity.PII) {
            reasons.add(ConsentRiskReason.PII_REQUESTED)
        }
        val minRetainSensitivity = if (requester.isProximity) {
            DocumentAttributeSensitivity.PII
        } else {
            DocumentAttributeSensitivity.PORTRAIT_IMAGE
        }
        val anySensitiveRetained = selection.matches.any { match ->
            match.claims.any { (requestedClaim, claimValue) ->
                requestedClaim is MdocRequestedClaim &&
                    requestedClaim.intentToRetain &&
                    (claimValue.attribute?.sensitivity?.let { it >= minRetainSensitivity } == true)
            }
        }
        if (anySensitiveRetained) {
            reasons.add(ConsentRiskReason.INTENT_TO_RETAIN)
        }
        val hasTransactionData = selection.matches.any { it.transactionData.isNotEmpty() }
        if (hasTransactionData) {
            reasons.add(ConsentRiskReason.TRANSACTION_DATA)
        }
        val hasSensitiveEscalation =
            ConsentRiskReason.PII_REQUESTED in reasons ||
                ConsentRiskReason.INTENT_TO_RETAIN in reasons ||
                ConsentRiskReason.TRANSACTION_DATA in reasons
        if (hasAnonymousEncryptionTarget || (hasSensitiveEscalation && isRequesterAnonymous && !requester.isProximity)) {
            return ConsentRiskAssessment(
                level = ConsentRiskLevel.HIGH_RISK,
                reasons = reasons,
            )
        }
        if (hasSensitiveEscalation || hasUntrustedEncryptionTarget || !requester.isProximity) {
            return ConsentRiskAssessment(
                level = ConsentRiskLevel.WARNING,
                reasons = reasons,
            )
        }
        return ConsentRiskAssessment(
            level = ConsentRiskLevel.NORMAL,
            reasons = reasons,
        )
    }

    if (hasUntrustedEncryptionTarget) {
        return ConsentRiskAssessment(
            level = if (hasAnonymousEncryptionTarget) {
                ConsentRiskLevel.HIGH_RISK
            } else {
                ConsentRiskLevel.WARNING
            },
            reasons = setOf(ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET),
        )
    }

    return ConsentRiskAssessment(
        level = ConsentRiskLevel.NORMAL,
        reasons = emptySet(),
    )
}
