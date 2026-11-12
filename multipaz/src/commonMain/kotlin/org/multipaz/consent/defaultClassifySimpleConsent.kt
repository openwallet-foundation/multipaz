package org.multipaz.consent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.multipaz.cbor.Simple
import org.multipaz.claim.Claim
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentUseCase
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

/**
 * Default [ClassifySimpleConsentFn] implementation that checks whether a credential presentment
 * request qualifies as a simple, well-defined non-PII request (such as age verification) that can
 * be combined with biometric authentication.
 *
 * ### Qualification Rules
 *
 * A request qualifies as a [SimpleConsent] if and only if all of the following hold:
 *
 * 1. **Single use-case and single credential match**:
 *    - [presentmentData] contains a single [PresentmentUseCase] with a single selected solution
 *      index in [selections], resolving to a single [org.multipaz.presentment.PresentmentCredentialMatch].
 *    - The use-case classifies to `setOf(PresentmentPurpose.AGE_VERIFICATION)` via
 *      [PresentmentUseCase.classifyPurposes] (which also validates compatibility with any
 *      protocol-level [PresentmentUseCase.purposeHints]).
 *
 * 2. **No retention, transaction data, or claim-level encryption**:
 *    - No requested mdoc claim has [MdocRequestedClaim.intentToRetain] set to `true`.
 *    - The match has no `transactionData`.
 *    - The match does not request claim-level encryption (`encryptionRequested == false`).
 *
 * 3. **Allowed claims**:
 *    - Every requested claim in the match is defined in the credential schema (`attribute != null`).
 *    - Portrait claims ([DocumentAttributeSensitivity.PORTRAIT_IMAGE], `portrait`, `picture`,
 *      `resident_image`) and `faceMatchNeeded` are permitted **only** for proximity requests
 *      ([Requester.isProximity] is `true`); they disqualify over-the-Internet or app-to-app
 *      requests.
 *    - Validity and issuer metadata claims ([DocumentAttributeSensitivity.VALIDITY] and
 *      [DocumentAttributeSensitivity.ISSUER]) are permitted alongside the age claim.
 *    - Exactly **one** age claim is requested:
 *      - An age threshold claim (`age_over_NN`, `age_aboveNN`, `age_equal_or_over.NN`, or a
 *        numeric identifier with [DocumentAttributeSensitivity.AGE_INFORMATION]) whose value in
 *        the credential is `true`, mapping to [SimpleConsentClaim.AgeOver] (or
 *        [SimpleConsentClaim.AgeOverAndPortrait] when a portrait claim is also requested), or
 *        whose value in the credential is `false`, mapping to [SimpleConsentClaim.NotAgeOver] (or
 *        [SimpleConsentClaim.NotAgeOverAndPortrait] when a portrait claim is also requested).
 *      - `age_in_years`, mapping to [SimpleConsentClaim.AgeInYears] (or
 *        [SimpleConsentClaim.AgeInYearsAndPortrait] when a portrait claim is also requested).
 *      - `age_birth_year`, mapping to [SimpleConsentClaim.AgeBirthYear] (or
 *        [SimpleConsentClaim.AgeBirthYearAndPortrait] when a portrait claim is also requested).
 *    - Any other claim (or more than one age claim) disqualifies the request and returns `null`.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity trust information for the requester, or `null` if untrusted.
 * @param presentmentData the combinations of credentials and claims that the user can select.
 * @param selections the selected solution indices for each [PresentmentUseCase] in
 *   [PresentmentData.useCases], or an empty list for an omitted optional use-case. Defaults to
 *   [PresentmentData.defaultSelections].
 * @return a [SimpleConsent] if the request qualifies as simple, or `null` otherwise.
 */
fun defaultClassifySimpleConsent(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<List<Int>> = presentmentData.defaultSelections(),
): SimpleConsent? {
    require(selections.size == presentmentData.useCases.size) {
        "Expected selections length (${selections.size}) to match useCases length (${presentmentData.useCases.size})"
    }

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
        }
    }

    if (presentmentData.useCases.size != 1) {
        return null
    }
    val selectedIndices = selections[0]
    if (selectedIndices.size != 1) {
        return null
    }
    val useCase = presentmentData.useCases[0]
    val solutionIndex = selectedIndices[0]
    if (useCase.classifyPurposes(solutionIndex) != setOf(PresentmentPurpose.AGE_VERIFICATION)) {
        return null
    }

    val selection = presentmentData.toPresentmentSelection(selections)
    if (selection.matches.size != 1) {
        return null
    }
    val match = selection.matches[0]

    if (match.transactionData.isNotEmpty() || match.encryptionRequested) {
        return null
    }
    if (match.faceMatchNeeded && !requester.isProximity) {
        return null
    }
    if (match.claims.keys.any { it is MdocRequestedClaim && it.intentToRetain }) {
        return null
    }

    var simpleClaim: SimpleConsentClaim? = null
    var hasPortrait = false

    for (claim in match.claims.values) {
        val attribute = claim.attribute ?: return null
        val id = claim.canonicalIdentifier()
        val sensitivity = attribute.sensitivity

        when {
            sensitivity == DocumentAttributeSensitivity.PORTRAIT_IMAGE || id in PORTRAIT_CLAIM_IDENTIFIERS -> {
                if (!requester.isProximity) {
                    return null
                }
                hasPortrait = true
            }
            sensitivity == DocumentAttributeSensitivity.AGE_INFORMATION || isAgeClaimIdentifier(id) -> {
                if (simpleClaim != null) {
                    return null
                }
                simpleClaim = claim.toSimpleConsentClaim(id, sensitivity) ?: return null
            }
            sensitivity == DocumentAttributeSensitivity.VALIDITY ||
                sensitivity == DocumentAttributeSensitivity.ISSUER ||
                id in METADATA_CLAIM_IDENTIFIERS -> {
                // Allowed auxiliary validity/issuer metadata claim
            }
            else -> {
                return null
            }
        }
    }

    val baseClaim = simpleClaim ?: return null
    val resolvedClaim = if (hasPortrait) {
        when (baseClaim) {
            is SimpleConsentClaim.AgeOver -> SimpleConsentClaim.AgeOverAndPortrait(baseClaim.age)
            is SimpleConsentClaim.NotAgeOver -> SimpleConsentClaim.NotAgeOverAndPortrait(baseClaim.age)
            SimpleConsentClaim.AgeInYears -> SimpleConsentClaim.AgeInYearsAndPortrait
            SimpleConsentClaim.AgeBirthYear -> SimpleConsentClaim.AgeBirthYearAndPortrait
            is SimpleConsentClaim.AgeOverAndPortrait,
            is SimpleConsentClaim.NotAgeOverAndPortrait,
            SimpleConsentClaim.AgeInYearsAndPortrait,
            SimpleConsentClaim.AgeBirthYearAndPortrait -> baseClaim
        }
    } else {
        baseClaim
    }
    return SimpleConsent(
        credential = match.credential,
        selection = selection,
        claim = resolvedClaim,
    )
}

private fun Claim.toSimpleConsentClaim(
    id: String,
    sensitivity: DocumentAttributeSensitivity,
): SimpleConsentClaim? {
    return when {
        id == "age_in_years" -> SimpleConsentClaim.AgeInYears
        id == "age_birth_year" -> SimpleConsentClaim.AgeBirthYear
        else -> {
            val ageThreshold = when {
                id.startsWith("age_over_") -> id.removePrefix("age_over_").toIntOrNull()
                id.startsWith("age_above") -> id.removePrefix("age_above").toIntOrNull()
                id.startsWith("age_equal_or_over.") -> id.removePrefix("age_equal_or_over.").toIntOrNull()
                sensitivity == DocumentAttributeSensitivity.AGE_INFORMATION -> id.toIntOrNull()
                else -> null
            }
            if (ageThreshold == null || ageThreshold <= 0) {
                null
            } else {
                when (booleanValueOrNull()) {
                    true -> SimpleConsentClaim.AgeOver(ageThreshold)
                    false -> SimpleConsentClaim.NotAgeOver(ageThreshold)
                    null -> null
                }
            }
        }
    }
}

private fun Claim.booleanValueOrNull(): Boolean? = when (this) {
    is MdocClaim -> when (value) {
        Simple.TRUE -> true
        Simple.FALSE -> false
        else -> null
    }
    is JsonClaim -> (value as? JsonPrimitive)?.booleanOrNull
}
