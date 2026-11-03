package org.multipaz.consent

import org.multipaz.presentment.PresentmentData
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
     * Moderate-risk request (e.g., an unverified relying party whose certificate is not in the
     * wallet's trust list, an unverified encryption target that provided a certificate chain not in
     * the trust list, an anonymous proximity relying party requesting PII, retention, or
     * transaction data, or an anonymous over-the-Internet/app-to-app relying party making a
     * low-sensitivity request without retention or transaction data). The RP-info box is rendered
     * in a yellow/amber warning box at the top of the prompt.
     */
    WARNING,

    /**
     * High-risk request (e.g., unrecognized purpose, unknown claim, anonymous encryption target
     * with no certificate chain, or an anonymous over-the-Internet/app-to-app relying party
     * requesting PII, data retention, or transaction data). The RP-info box is rendered in a red
     * danger box at the top of the prompt and a 5-second cool-off timer is required.
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
     * The relying party requesting the credentials is anonymous (did not provide a digital
     * signature or certificate).
     */
    ANONYMOUS_REQUESTER,

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

