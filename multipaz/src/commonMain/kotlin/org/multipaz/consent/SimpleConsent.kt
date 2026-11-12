package org.multipaz.consent

import org.multipaz.credential.Credential
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

/**
 * High-level classification of the specific non-PII claim being requested in a [SimpleConsent].
 */
sealed class SimpleConsentClaim {
    /**
     * Proving that the holder is at least [age] years old (e.g., `age_over_21`, `age_over_18`,
     * `age_above18`, or `age_equal_or_over.21`).
     *
     * @property age the age threshold in years.
     */
    data class AgeOver(val age: Int) : SimpleConsentClaim()

    /**
     * Proving that the holder is at least [age] years old along with sharing the holder's portrait.
     *
     * @property age the age threshold in years.
     */
    data class AgeOverAndPortrait(val age: Int) : SimpleConsentClaim()

    /**
     * Sharing that the holder is not at least [age] years old (when an `age_over_NN`,
     * `age_aboveNN`, or `age_equal_or_over.NN` claim in the credential has value `false`).
     *
     * @property age the age threshold in years.
     */
    data class NotAgeOver(val age: Int) : SimpleConsentClaim()

    /**
     * Sharing that the holder is not at least [age] years old along with sharing the holder's
     * portrait.
     *
     * @property age the age threshold in years.
     */
    data class NotAgeOverAndPortrait(val age: Int) : SimpleConsentClaim()

    /**
     * Sharing the holder's age in years (`age_in_years`).
     */
    data object AgeInYears : SimpleConsentClaim()

    /**
     * Sharing the holder's age in years (`age_in_years`) along with the holder's portrait.
     */
    data object AgeInYearsAndPortrait : SimpleConsentClaim()

    /**
     * Sharing the holder's birth year (`age_birth_year`).
     */
    data object AgeBirthYear : SimpleConsentClaim()

    /**
     * Sharing the holder's birth year (`age_birth_year`) along with the holder's portrait.
     */
    data object AgeBirthYearAndPortrait : SimpleConsentClaim()
}

/**
 * Represents a simple, well-defined, non-PII credential presentment request that can be
 * combined with biometric authentication.
 *
 * @property credential the [Credential] selected to satisfy the request.
 * @property selection the resolved [PresentmentSelection] for the request.
 * @property claim the specific [SimpleConsentClaim] being requested.
 */
data class SimpleConsent(
    val credential: Credential,
    val selection: PresentmentSelection,
    val claim: SimpleConsentClaim,
)

/**
 * Function that evaluates whether the active [selections] in [presentmentData] qualify as a
 * [SimpleConsent] request, returning `null` if the full consent prompt should be used.
 */
typealias ClassifySimpleConsentFn = (
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<List<Int>>,
) -> SimpleConsent?
