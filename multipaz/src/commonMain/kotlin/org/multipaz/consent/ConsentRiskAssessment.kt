package org.multipaz.consent

import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentUseCase
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

/**
 * Risk level evaluated for a credential presentment request to determine the placement and styling
 * of the relying party information box, as well as whether a cool-off timer is enforced on the
 * confirmation button.
 */
enum class ConsentRiskLevel {
    /**
     * Normal-risk request (e.g., trusted relying party and trusted encryption target requesting a
     * recognized purpose with known claims, or an unverified/anonymous proximity relying party
     * requesting only low-sensitivity non-PII claims such as `age_over_21` and `portrait` without
     * retention or transaction data). The RP-info box is rendered on a neutral surface at the
     * bottom of the prompt.
     */
    NORMAL,

    /**
     * Moderate-risk request (e.g., an untrusted over-the-Internet or app-to-app relying party
     * making a low-sensitivity request such as age verification or a portrait image without
     * retention or transaction data). The RP-info box is rendered in a yellow/amber warning box at
     * the top of the prompt.
     */
    WARNING,

    /**
     * High-risk request (e.g., unrecognized purpose, unknown claim, untrusted encryption target,
     * or an untrusted relying party requesting PII, data retention, or transaction data). The
     * RP-info box is rendered in a red danger box at the top of the prompt and a 5-second cool-off
     * timer is required.
     */
    HIGH_RISK,
}

/**
 * Specific risk reason(s) explaining why a presentment request was classified with a given
 * [ConsentRiskLevel].
 */
enum class ConsentRiskReason {
    /**
     * The relying party requesting the credentials is not in the wallet's trust list.
     */
    UNTRUSTED_REQUESTER,

    /**
     * The request asks for an unrecognized or over-broad combination of claims, or includes
     * attributes not defined in the credential schema.
     */
    UNUSUAL_REQUEST,

    /**
     * The request includes personally identifiable information (PII).
     */
    PII_REQUESTED,

    /**
     * Part of the response is requested to be encrypted for a recipient that is not in the
     * wallet's trust list.
     */
    UNTRUSTED_ENCRYPTION_TARGET,

    /**
     * The requester intends to store (`intentToRetain`) the shared information.
     */
    INTENT_TO_RETAIN,

    /**
     * The request authorizes a transaction (`transactionData`).
     */
    TRANSACTION_DATA,
}

/**
 * The evaluated risk level and accompanying reasons for a credential presentment request.
 *
 * @property level the overall [ConsentRiskLevel].
 * @property reasons the set of [ConsentRiskReason]s that contributed to [level].
 */
data class ConsentRiskAssessment(
    val level: ConsentRiskLevel,
    val reasons: Set<ConsentRiskReason> = emptySet(),
)

/**
 * Function that classifies the [PresentmentPurpose]s for the active [selections] in
 * [presentmentData].
 *
 * Returning an empty set indicates that the request's purpose is unrecognized or unaccounted for.
 */
typealias ClassifyPurposesFn = (
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<Int>,
) -> Set<PresentmentPurpose>

/**
 * Function that evaluates the [ConsentRiskAssessment] for the active [selections] in
 * [presentmentData] and the classified [purposes].
 */
typealias ClassifyRiskFn = (
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<Int>,
    purposes: Set<PresentmentPurpose>,
) -> ConsentRiskAssessment

/**
 * Default [ClassifyPurposesFn] implementation that classifies the [PresentmentPurpose]s across
 * all selected use-cases in [presentmentData].
 *
 * ### Implementation Details
 *
 * Each selected [PresentmentUseCase] (where `selections[index] >= 0`) is evaluated against its
 * selected [org.multipaz.presentment.PresentmentUseCaseSolution]:
 *
 * 1. **Per-credential match inference**: For each [org.multipaz.presentment.PresentmentCredentialMatch]
 *    in the selected solution:
 *    - **Unknown claims**: If any requested claim is not defined in the credential's schema
 *      (`claim.attribute == null`), classification fails for that match and returns an empty set.
 *    - **Transaction data**: If any attached transaction data has type identifier
 *      `"urn:eudi:sca:payment:1"`, [PresentmentPurpose.PAYMENT] is added.
 *    - **Domain-specific doctypes and VCTs**:
 *      - **Payment credentials** (`org.multipaz.payment.sca.1`, `urn:emvco:dpc:card:1`): Classified
 *        as [PresentmentPurpose.PAYMENT] if all requested claims are recognized payment claims
 *        (`issuer_name`, `payment_instrument_id`, `masked_account_reference`, `holder_name`,
 *        `issue_date`, `expiry_date`, `credential_id`, `network`, `last_four`) or have
 *        [DocumentAttributeSensitivity.VALIDITY] / [DocumentAttributeSensitivity.ISSUER]
 *        sensitivity; otherwise returns an empty set.
 *      - **Loyalty credentials** (`org.multipaz.loyalty.1`): Classified as
 *        [PresentmentPurpose.LOYALTY_VERIFICATION] if all requested claims are recognized loyalty
 *        claims (`family_name`, `given_name`, `portrait`, `membership_number`, `tier`,
 *        `issue_date`, `expiry_date`) or have `VALIDITY`, `ISSUER`, or `PORTRAIT_IMAGE`
 *        sensitivity; otherwise returns an empty set.
 *      - **Ticket / boarding pass credentials** (`org.multipaz.example.boarding-pass.1`,
 *        `https://utopia.example.com/vct/movieticket`): Classified as
 *        [PresentmentPurpose.TICKET_VERIFICATION] if all requested claims are recognized ticket
 *        claims (`passenger_name`, `flight_number`, `seat_number`, `departure_time`, `ticket_id`,
 *        `cinema`, `movie`, `show_date_time`, `movie_rating`, `theater_id`, `seat_id`,
 *        `parking_option`, `poster`) or have `VALIDITY`, `ISSUER`, or `PORTRAIT_IMAGE`
 *        sensitivity; otherwise returns an empty set.
 *    - **General identity / mDL / PID credentials**: Each requested claim is categorized:
 *      - Driving claims (`driving_privileges`, `domestic_driving_privileges`,
 *        `hazmat_endorsement_expiration_date`) add [PresentmentPurpose.DRIVING_PRIVILEGES].
 *      - Age claims ([DocumentAttributeSensitivity.AGE_INFORMATION], `age_in_years`,
 *        `age_birth_year`, `age_over_*`, `age_above*`, `age_equal_or_over.*`) add
 *        [PresentmentPurpose.AGE_VERIFICATION]. At most 2 age claims per credential are permitted;
 *        requesting 3 or more age claims on a single credential is treated as over-broad and
 *        returns an empty set.
 *      - Address claims (e.g., `resident_address`, `resident_city`, `resident_state`,
 *        `resident_postal_code`, `resident_country`, `address.*`, `locality`, `street_address`,
 *        etc.) add [PresentmentPurpose.ADDRESS_VERIFICATION].
 *      - Core identity claims (e.g., `family_name`, `given_name`, `birth_date`, `document_number`,
 *        `person_id`, `personal_administrative_number`, `sex`, `gender`, `nationality`,
 *        `place_of_birth`, `signature_usual_mark`, `dg1`, `dg2`, `sod`, etc.) add
 *        [PresentmentPurpose.IDENTITY_VERIFICATION].
 *      - Portrait claims ([DocumentAttributeSensitivity.PORTRAIT_IMAGE], `portrait`, `picture`,
 *        `resident_image`) and auxiliary validity/issuer metadata claims (`issue_date`,
 *        `expiry_date`, `issuing_country`, `issuing_authority`, `DHS_compliance`, etc.) are
 *        compatible with any purpose. If a credential requests only a portrait (or requires face
 *        matching) without other purpose-specific claims, it defaults to
 *        [PresentmentPurpose.IDENTITY_VERIFICATION].
 *      - Any other claim not accounted for above (such as `height`, `weight`, `eye_colour`,
 *        `hair_colour`, etc.) causes classification to fail and return an empty set.
 *      - **Same-credential subsumption**: When both [PresentmentPurpose.IDENTITY_VERIFICATION] and
 *        [PresentmentPurpose.AGE_VERIFICATION] are inferred on the same credential,
 *        [PresentmentPurpose.IDENTITY_VERIFICATION] subsumes [PresentmentPurpose.AGE_VERIFICATION].
 *
 * 2. **Purpose hints validation**: If [PresentmentUseCase.purposeHints] is non-empty, each
 *    protocol-level hint (such as ISO/IEC 18013-5 `org.iso.jtc1.sc17` purpose codes `1` for
 *    identity, `2` for driving privileges, and `3` for age verification) is mapped to a
 *    [PresentmentPurpose] and checked against the inferred purposes of the selected solution:
 *    - [PresentmentPurpose.DRIVING_PRIVILEGES] permits inferred `DRIVING_PRIVILEGES`,
 *      `IDENTITY_VERIFICATION`, and `AGE_VERIFICATION`.
 *    - [PresentmentPurpose.IDENTITY_VERIFICATION] permits inferred `IDENTITY_VERIFICATION`,
 *      `ADDRESS_VERIFICATION`, and `AGE_VERIFICATION`.
 *    - All other hinted purposes permit only that exact inferred purpose.
 *    - If any hint is unrecognized or if the inferred purposes exceed what the declared hints
 *      permit, the use-case fails classification and returns an empty set.
 *
 * 3. **Aggregation across use-cases**: The classified purposes across all selected use-cases are
 *    combined in [PresentmentPurpose] enum declaration order. If no use-cases are selected or if
 *    any selected use-case returns an empty set, this function returns an empty set.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity trust information for the requester, or `null` if untrusted.
 * @param presentmentData the combinations of credentials and claims that the user can select.
 * @param selections the selected solution index for each [PresentmentUseCase] in
 *   [PresentmentData.useCases], or `-1` for an omitted optional use-case. Defaults to selecting
 *   solution `0` for each use-case.
 * @return the set of [PresentmentPurpose]s in enum declaration order, or an empty set if unknown.
 */
fun defaultClassifyPurposes(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<Int> = presentmentData.useCases.map { if (it.solutions.isNotEmpty()) 0 else -1 },
): Set<PresentmentPurpose> = presentmentData.classifyPurposes(selections)

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
 *    - Whenever `isRequesterTrusted` is `false` and the evaluated risk level is
 *      [ConsentRiskLevel.WARNING] or [ConsentRiskLevel.HIGH_RISK],
 *      [ConsentRiskReason.UNTRUSTED_REQUESTER] is included in the returned
 *      [ConsentRiskAssessment.reasons].
 *
 * 2. **No credentials selected** (all use-cases are optional and omitted with selection `-1`):
 *    - If `isRequesterTrusted` is `true` or [Requester.isProximity] is `true`, returns
 *      [ConsentRiskLevel.NORMAL] with no reasons.
 *    - Otherwise (untrusted over-the-Internet or app-to-app requester), returns
 *      [ConsentRiskLevel.WARNING] with [ConsentRiskReason.UNTRUSTED_REQUESTER].
 *
 * 3. **Unclassified purpose or unknown schema attribute** (checked for both trusted and untrusted
 *    requesters):
 *    - If [purposes] is empty or any selected credential match contains a claim whose
 *      `attribute == null` (not defined in the credential schema), returns
 *      [ConsentRiskLevel.HIGH_RISK] with [ConsentRiskReason.UNUSUAL_REQUEST] (plus
 *      [ConsentRiskReason.UNTRUSTED_REQUESTER] if the requester is untrusted).
 *
 * 4. **Untrusted claim-level encryption target** (checked for both trusted and untrusted
 *    requesters):
 *    - If any selected credential match requests encryption (`encryptionRequested == true`) and
 *      its encryption target is not in a trust list (`encryptionTargetTrustMetadata == null`),
 *      returns [ConsentRiskLevel.HIGH_RISK] with
 *      [ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET] (plus
 *      [ConsentRiskReason.UNTRUSTED_REQUESTER] if the requester is untrusted).
 *
 * 5. **Untrusted requester checks** (when `isRequesterTrusted` is `false`):
 *    - **High-risk escalations** (all matching reasons are collected together alongside
 *      [ConsentRiskReason.UNTRUSTED_REQUESTER]):
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
 *      - If one or more of the above high-risk reasons matched, returns
 *        [ConsentRiskLevel.HIGH_RISK] with all matched reasons.
 *    - **Low-sensitivity request**: Otherwise (e.g., an untrusted requester asking only for
 *      non-PII claims without high-risk retention or transaction data):
 *      - If [Requester.isProximity] is `true`, returns [ConsentRiskLevel.NORMAL] with an empty set
 *        of reasons, since the user is physically in proximity to the relying party (which is
 *        already free to observe and capture their likeness).
 *      - If [Requester.isProximity] is `false` (over-the-Internet or app-to-app), returns
 *        [ConsentRiskLevel.WARNING] with [ConsentRiskReason.UNTRUSTED_REQUESTER].
 *
 * 6. **Trusted requester happy path**:
 *    - When `isRequesterTrusted` is `true` and none of steps 3–4 triggered, returns
 *      [ConsentRiskLevel.NORMAL] with an empty set of reasons.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity trust information for the requester, or `null` if untrusted.
 * @param presentmentData the combinations of credentials and claims that the user can select.
 * @param selections the selected solution index for each [PresentmentUseCase] in
 *   [PresentmentData.useCases], or `-1` for an omitted optional use-case. Defaults to selecting
 *   solution `0` for each use-case.
 * @param purposes the classified [PresentmentPurpose]s for [selections]. Defaults to
 *   `defaultClassifyPurposes(requester, trustedRequesterIdentity, presentmentData, selections)`.
 * @return the evaluated [ConsentRiskAssessment].
 */
fun defaultClassifyRisk(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<Int> = presentmentData.useCases.map { if (it.solutions.isNotEmpty()) 0 else -1 },
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
        val selectionIndex = selections[index]
        if (selectionIndex < 0) {
            require(useCase.optional) { "Cannot omit non-optional use-case at index $index" }
        } else {
            require(selectionIndex < useCase.solutions.size) {
                "Out-of-bounds solution index $selectionIndex for use-case at index $index"
            }
            anySelectedUseCase = true
        }
    }

    val isRequesterTrusted = trustedRequesterIdentity != null
    val baseReasons = if (isRequesterTrusted) {
        emptySet()
    } else {
        setOf(ConsentRiskReason.UNTRUSTED_REQUESTER)
    }

    if (!anySelectedUseCase) {
        return if (isRequesterTrusted || requester.isProximity) {
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet())
        } else {
            ConsentRiskAssessment(ConsentRiskLevel.WARNING, baseReasons)
        }
    }

    val selection = presentmentData.toPresentmentSelection(selections)
    val hasUnknownClaim = selection.matches.any { match ->
        match.claims.values.any { it.attribute == null }
    }
    if (purposes.isEmpty() || hasUnknownClaim) {
        return ConsentRiskAssessment(
            level = ConsentRiskLevel.HIGH_RISK,
            reasons = baseReasons + ConsentRiskReason.UNUSUAL_REQUEST,
        )
    }

    val hasUntrustedEncryptionTarget = selection.matches.any {
        it.encryptionRequested && it.encryptionTargetTrustMetadata == null
    }
    if (hasUntrustedEncryptionTarget) {
        return ConsentRiskAssessment(
            level = ConsentRiskLevel.HIGH_RISK,
            reasons = baseReasons + ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET,
        )
    }

    if (!isRequesterTrusted) {
        val highRiskReasons = baseReasons.toMutableSet()
        // Note: By default we evaluate maxSensitivity across all selected matches—even if a match
        // is encrypted for a trusted encryption target—because the untrusted/anonymous requester
        // still has knowledge of the transaction. Like everything else in defaultClassifyRisk,
        // this is policy; a wallet whose threat model accepts an untrusted intermediary forwarding
        // encrypted claims to a trusted recipient could classify that case as ConsentRiskLevel.NORMAL.
        val maxSensitivity = selection.getMaxSensitivity()
        if (maxSensitivity != null && maxSensitivity >= DocumentAttributeSensitivity.PII) {
            highRiskReasons.add(ConsentRiskReason.PII_REQUESTED)
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
            highRiskReasons.add(ConsentRiskReason.INTENT_TO_RETAIN)
        }
        val hasTransactionData = selection.matches.any { it.transactionData.isNotEmpty() }
        if (hasTransactionData) {
            highRiskReasons.add(ConsentRiskReason.TRANSACTION_DATA)
        }
        if (highRiskReasons.size > baseReasons.size) {
            return ConsentRiskAssessment(
                level = ConsentRiskLevel.HIGH_RISK,
                reasons = highRiskReasons,
            )
        }
        if (requester.isProximity) {
            return ConsentRiskAssessment(
                level = ConsentRiskLevel.NORMAL,
                reasons = emptySet(),
            )
        }
        return ConsentRiskAssessment(
            level = ConsentRiskLevel.WARNING,
            reasons = baseReasons,
        )
    }

    return ConsentRiskAssessment(
        level = ConsentRiskLevel.NORMAL,
        reasons = emptySet(),
    )
}
