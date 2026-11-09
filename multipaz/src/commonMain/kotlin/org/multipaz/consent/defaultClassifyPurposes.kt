package org.multipaz.consent

import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.claim.Claim
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.PresentmentCredentialMatch
import org.multipaz.presentment.PresentmentCredentialMatchSourceIso18013
import org.multipaz.presentment.PresentmentCredentialMatchSourceOpenID4VP
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentPurposeHint
import org.multipaz.presentment.PresentmentPurposeHintIso18013
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.PresentmentUseCase
import org.multipaz.presentment.PresentmentUseCaseSolution
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.sdjwt.credential.SdJwtVcCredential

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
 *      - **General identity / mDL / PID / age verification credentials**
 *        (`org.iso.18013.5.1.mDL`, `org.iso.23220.photoid.1`, `eu.europa.ec.eudi.pid.1`,
 *        `urn:eudi:pid:1`, `https://example.bmi.bund.de/credential/pid/1.0`,
 *        `eu.europa.ec.av.1`, `com.google.wallet.idcard.1`, `in.gov.uidai.aadhaar.1`,
 *        `eu.europa.ec.eudi.cor.1`, `https://example.eudi.ec.europa.eu/cor/1`): Each requested
 *        claim is categorized:
 *        - Driving claims (`driving_privileges`, `domestic_driving_privileges`,
 *          `hazmat_endorsement_expiration_date`) add [PresentmentPurpose.DRIVING_PRIVILEGES].
 *        - Age claims ([DocumentAttributeSensitivity.AGE_INFORMATION], `age_in_years`,
 *          `age_birth_year`, `age_over_*`, `age_above*`, `age_equal_or_over.*`) add
 *          [PresentmentPurpose.AGE_VERIFICATION]. At most 2 age claims per credential are permitted;
 *          requesting 3 or more age claims on a single credential is treated as over-broad and
 *          returns an empty set.
 *        - Address claims (e.g., `resident_address`, `resident_city`, `resident_state`,
 *          `resident_postal_code`, `resident_country`, `address.*`, `locality`, `street_address`,
 *          etc.) add [PresentmentPurpose.ADDRESS_VERIFICATION].
 *        - Core identity claims (e.g., `family_name`, `given_name`, `birth_date`, `document_number`,
 *          `person_id`, `personal_administrative_number`, `sex`, `gender`, `nationality`,
 *          `place_of_birth`, `signature_usual_mark`, `dg1`, `dg2`, `sod`, etc.) add
 *          [PresentmentPurpose.IDENTITY_VERIFICATION].
 *        - Portrait claims ([DocumentAttributeSensitivity.PORTRAIT_IMAGE], `portrait`, `picture`,
 *          `resident_image`) and auxiliary validity/issuer metadata claims (`issue_date`,
 *          `expiry_date`, `issuing_country`, `issuing_authority`, `DHS_compliance`, etc.) are
 *          compatible with any purpose. If a credential requests only a portrait (or requires face
 *          matching) without other purpose-specific claims, it defaults to
 *          [PresentmentPurpose.IDENTITY_VERIFICATION].
 *        - Any other claim not accounted for above (such as `height`, `weight`, `eye_colour`,
 *          `hair_colour`, etc.) causes classification to fail and return an empty set.
 *        - **Same-credential subsumption**: When both [PresentmentPurpose.IDENTITY_VERIFICATION] and
 *          [PresentmentPurpose.AGE_VERIFICATION] are inferred on the same credential,
 *          [PresentmentPurpose.IDENTITY_VERIFICATION] subsumes [PresentmentPurpose.AGE_VERIFICATION].
 *      - **Other / custom credentials**: For any other doctype or VCT, no domain-specific
 *        [PresentmentPurpose] is inferred (unless a recognized transaction type such as
 *        `"urn:eudi:sca:payment:1"` is attached), returning an empty set so the UI renders a
 *        generic request subtitle without flagging the request as unusual.
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
 * @param selections the selected solution indices for each [PresentmentUseCase] in
 *   [PresentmentData.useCases], or an empty list for an omitted optional use-case. Defaults to
 *   [PresentmentData.defaultSelections].
 * @return the set of [PresentmentPurpose]s in enum declaration order, or an empty set if unknown.
 */
fun defaultClassifyPurposes(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    selections: List<List<Int>> = presentmentData.defaultSelections(),
): Set<PresentmentPurpose> = presentmentData.classifyPurposes(selections)

private const val ISO_18013_PURPOSE_CONTROLLER_ID = "org.iso.jtc1.sc17"
private const val ISO_18013_PURPOSE_CODE_IDENTITY = 1
private const val ISO_18013_PURPOSE_CODE_DRIVING_PRIVILEGES = 2
private const val ISO_18013_PURPOSE_CODE_AGE_VERIFICATION = 3

private const val PAYMENT_TRANSACTION_IDENTIFIER = "urn:eudi:sca:payment:1"

private val PAYMENT_DOCTYPES_AND_VCTS = setOf(
    "org.multipaz.payment.sca.1",
    "urn:emvco:dpc:card:1",
)

private val PAYMENT_CLAIM_IDENTIFIERS = setOf(
    "issuer_name",
    "payment_instrument_id",
    "masked_account_reference",
    "holder_name",
    "issue_date",
    "expiry_date",
    "credential_id",
    "network",
    "last_four",
)

private val LOYALTY_DOCTYPES_AND_VCTS = setOf(
    "org.multipaz.loyalty.1",
)

private val LOYALTY_CLAIM_IDENTIFIERS = setOf(
    "family_name",
    "given_name",
    "portrait",
    "membership_number",
    "tier",
    "issue_date",
    "expiry_date",
)

private val TICKET_DOCTYPES_AND_VCTS = setOf(
    "org.multipaz.example.boarding-pass.1",
    "https://utopia.example.com/vct/movieticket",
)

private val IDENTITY_DOCTYPES_AND_VCTS = setOf(
    "org.iso.18013.5.1.mDL",
    "org.iso.23220.photoid.1",
    "eu.europa.ec.eudi.pid.1",
    "urn:eudi:pid:1",
    "https://example.bmi.bund.de/credential/pid/1.0",
    "eu.europa.ec.av.1",
    "com.google.wallet.idcard.1",
    "in.gov.uidai.aadhaar.1",
    "eu.europa.ec.eudi.cor.1",
    "https://example.eudi.ec.europa.eu/cor/1",
)

internal val KNOWN_PURPOSE_DOCTYPES_AND_VCTS =
    PAYMENT_DOCTYPES_AND_VCTS +
        LOYALTY_DOCTYPES_AND_VCTS +
        TICKET_DOCTYPES_AND_VCTS +
        IDENTITY_DOCTYPES_AND_VCTS

private val TICKET_CLAIM_IDENTIFIERS = setOf(
    "passenger_name",
    "flight_number",
    "seat_number",
    "departure_time",
    "ticket_id",
    "cinema",
    "movie",
    "show_date_time",
    "movie_rating",
    "theater_id",
    "seat_id",
    "parking_option",
    "poster",
)

private val METADATA_CLAIM_IDENTIFIERS = setOf(
    "issue_date",
    "issuance_date",
    "date_of_issuance",
    "expiry_date",
    "date_of_expiry",
    "issuing_country",
    "issuing_authority",
    "issuing_jurisdiction",
    "issuing_subdivision",
    "portrait",
    "picture",
    "resident_image",
    "un_distinguishing_sign",
    "audit_information",
    "aamva_version",
    "DHS_compliance",
    "DHS_temporary_lawful_status",
    "credential_issuing_date",
    "enrolment_date",
    "AadhaarExpiresOn",
)

private val PORTRAIT_CLAIM_IDENTIFIERS = setOf(
    "portrait",
    "picture",
    "resident_image",
)

private val DRIVING_CLAIM_IDENTIFIERS = setOf(
    "driving_privileges",
    "domestic_driving_privileges",
    "hazmat_endorsement_expiration_date",
)

private val ADDRESS_CLAIM_IDENTIFIERS = setOf(
    "resident_address",
    "resident_city",
    "resident_state",
    "resident_postal_code",
    "resident_country",
    "resident_street",
    "resident_house_number",
    "resident_county",
    "resident_city_latin1",
    "address",
    "address.formatted",
    "address.country",
    "address.region",
    "address.locality",
    "address.postal_code",
    "address.street_address",
    "address.house_number",
    "street_address",
    "locality",
    "country",
    "postal_code",
    "local_address",
    "street",
    "local_street",
    "building",
    "local_building",
    "local_locality",
    "landmark",
    "local_landmark",
    "vtc",
    "local_vtc",
    "sub_district",
    "local_sub_district",
    "district",
    "local_district",
    "state",
    "local_state",
    "po_name",
    "local_po_name",
    "pincode",
)

private val IDENTITY_CLAIM_IDENTIFIERS = setOf(
    "family_name",
    "given_name",
    "birth_date",
    "birthdate",
    "dob",
    "document_number",
    "person_id",
    "personal_administrative_number",
    "administrative_number",
    "sex",
    "gender",
    "nationality",
    "nationalities",
    "place_of_birth",
    "place_of_birth.country",
    "place_of_birth.region",
    "place_of_birth.locality",
    "family_name_national_character",
    "given_name_national_character",
    "family_name_latin1",
    "given_name_latin1",
    "family_name_viz",
    "given_name_viz",
    "family_name_truncation",
    "given_name_truncation",
    "name_suffix",
    "signature_usual_mark",
    "EDL_credential",
    "resident_name",
    "local_resident_name",
    "masked_uid",
    "sod",
    "dg1",
    "dg2",
)

private const val MAX_AGE_CLAIMS_PER_CREDENTIAL = 2

/**
 * Maps a protocol-level [PresentmentPurposeHint] to an opinionated [PresentmentPurpose],
 * or `null` if the hint is not recognized.
 */
internal fun PresentmentPurposeHint.toPresentmentPurpose(): PresentmentPurpose? = when (this) {
    is PresentmentPurposeHintIso18013 -> {
        if (controllerId == ISO_18013_PURPOSE_CONTROLLER_ID) {
            when (purposeHintCode) {
                ISO_18013_PURPOSE_CODE_IDENTITY -> PresentmentPurpose.IDENTITY_VERIFICATION
                ISO_18013_PURPOSE_CODE_DRIVING_PRIVILEGES -> PresentmentPurpose.DRIVING_PRIVILEGES
                ISO_18013_PURPOSE_CODE_AGE_VERIFICATION -> PresentmentPurpose.AGE_VERIFICATION
                else -> null
            }
        } else {
            null
        }
    }
}

/**
 * Classifies the [PresentmentPurpose]s for a single [PresentmentCredentialMatch] based on its
 * document type / VCT, transaction data, and requested claims.
 *
 * Returns an empty set if any requested claim is unknown (`attribute == null`) or is not
 * accounted for by any recognized purpose (such as an over-broad "all data elements" request).
 */
internal fun PresentmentCredentialMatch.classifyPurposes(): Set<PresentmentPurpose> {
    if (claims.values.any { it.attribute == null }) {
        return emptySet()
    }

    val purposes = mutableSetOf<PresentmentPurpose>()
    if (transactionData.any { it.type.identifier == PAYMENT_TRANSACTION_IDENTIFIER }) {
        purposes.add(PresentmentPurpose.PAYMENT)
    }

    val docTypeOrVct = resolveDocTypeOrVct()

    if (docTypeOrVct in PAYMENT_DOCTYPES_AND_VCTS) {
        for (claim in claims.values) {
            val id = claim.canonicalIdentifier()
            val sensitivity = claim.attribute?.sensitivity
            if (id !in PAYMENT_CLAIM_IDENTIFIERS &&
                sensitivity != DocumentAttributeSensitivity.VALIDITY &&
                sensitivity != DocumentAttributeSensitivity.ISSUER
            ) {
                return emptySet()
            }
        }
        purposes.add(PresentmentPurpose.PAYMENT)
        return purposes.sortedBy { it.ordinal }.toSet()
    }

    if (docTypeOrVct in LOYALTY_DOCTYPES_AND_VCTS) {
        for (claim in claims.values) {
            val id = claim.canonicalIdentifier()
            val sensitivity = claim.attribute?.sensitivity
            if (id !in LOYALTY_CLAIM_IDENTIFIERS &&
                sensitivity != DocumentAttributeSensitivity.VALIDITY &&
                sensitivity != DocumentAttributeSensitivity.ISSUER &&
                sensitivity != DocumentAttributeSensitivity.PORTRAIT_IMAGE
            ) {
                return emptySet()
            }
        }
        purposes.add(PresentmentPurpose.LOYALTY_VERIFICATION)
        return purposes.sortedBy { it.ordinal }.toSet()
    }

    if (docTypeOrVct in TICKET_DOCTYPES_AND_VCTS) {
        for (claim in claims.values) {
            val id = claim.canonicalIdentifier()
            val sensitivity = claim.attribute?.sensitivity
            if (id !in TICKET_CLAIM_IDENTIFIERS &&
                sensitivity != DocumentAttributeSensitivity.VALIDITY &&
                sensitivity != DocumentAttributeSensitivity.ISSUER &&
                sensitivity != DocumentAttributeSensitivity.PORTRAIT_IMAGE
            ) {
                return emptySet()
            }
        }
        purposes.add(PresentmentPurpose.TICKET_VERIFICATION)
        return purposes.sortedBy { it.ordinal }.toSet()
    }

    if (docTypeOrVct !in IDENTITY_DOCTYPES_AND_VCTS) {
        return purposes.sortedBy { it.ordinal }.toSet()
    }

    var ageClaimCount = 0
    var hasPortrait = false

    for (claim in claims.values) {
        val id = claim.canonicalIdentifier()
        val sensitivity = claim.attribute?.sensitivity

        when {
            id in DRIVING_CLAIM_IDENTIFIERS -> {
                purposes.add(PresentmentPurpose.DRIVING_PRIVILEGES)
            }
            sensitivity == DocumentAttributeSensitivity.AGE_INFORMATION || isAgeClaimIdentifier(id) -> {
                ageClaimCount++
                purposes.add(PresentmentPurpose.AGE_VERIFICATION)
            }
            id in ADDRESS_CLAIM_IDENTIFIERS -> {
                purposes.add(PresentmentPurpose.ADDRESS_VERIFICATION)
            }
            id in IDENTITY_CLAIM_IDENTIFIERS -> {
                purposes.add(PresentmentPurpose.IDENTITY_VERIFICATION)
            }
            sensitivity == DocumentAttributeSensitivity.PORTRAIT_IMAGE || id in PORTRAIT_CLAIM_IDENTIFIERS -> {
                hasPortrait = true
            }
            sensitivity == DocumentAttributeSensitivity.VALIDITY ||
                sensitivity == DocumentAttributeSensitivity.ISSUER ||
                id in METADATA_CLAIM_IDENTIFIERS -> {
                // Auxiliary metadata claim compatible with any purpose
            }
            else -> {
                // Unaccounted-for claim (e.g. height, weight, eye_colour, hair_colour, email_address, etc.)
                return emptySet()
            }
        }
    }

    if (ageClaimCount > MAX_AGE_CLAIMS_PER_CREDENTIAL) {
        return emptySet()
    }

    if (purposes.isEmpty() && (hasPortrait || faceMatchNeeded)) {
        purposes.add(PresentmentPurpose.IDENTITY_VERIFICATION)
    }

    // Same-credential subsumption: full identity verification subsumes age verification on the same credential.
    if (PresentmentPurpose.IDENTITY_VERIFICATION in purposes) {
        purposes.remove(PresentmentPurpose.AGE_VERIFICATION)
    }

    return purposes.sortedBy { it.ordinal }.toSet()
}

/**
 * Classifies the [PresentmentPurpose]s across all credential matches in a [PresentmentUseCaseSolution].
 *
 * Returns an empty set if the solution has no matches or if any match has unaccounted-for claims.
 */
internal fun PresentmentUseCaseSolution.classifyPurposes(): Set<PresentmentPurpose> {
    if (matches.isEmpty()) {
        return emptySet()
    }
    val combined = mutableSetOf<PresentmentPurpose>()
    for (match in matches) {
        val matchPurposes = match.classifyPurposes()
        if (matchPurposes.isEmpty()) {
            return emptySet()
        }
        combined.addAll(matchPurposes)
    }
    return combined.sortedBy { it.ordinal }.toSet()
}

/**
 * Classifies the [PresentmentPurpose]s for a [PresentmentUseCase] using the solution at [solutionIndex].
 *
 * If [PresentmentUseCase.purposeHints] is empty, the purposes are inferred from the requested
 * doctypes, transaction data, and claims in the selected solution.
 *
 * If [PresentmentUseCase.purposeHints] is non-empty, each hint is mapped to a [PresentmentPurpose]
 * and checked for compatibility against the claims in the selected solution. If any hint is
 * unrecognized or if the requested claims exceed what is permitted by the declared hints, an empty
 * set is returned.
 *
 * @param solutionIndex the index into [PresentmentUseCase.solutions] to evaluate, defaults to `0`.
 * @return the set of [PresentmentPurpose]s for this use-case, or an empty set if no valid purpose
 *   accounts for all requested claims.
 */
internal fun PresentmentUseCase.classifyPurposes(solutionIndex: Int = 0): Set<PresentmentPurpose> {
    val solution = solutions.getOrNull(solutionIndex) ?: return emptySet()
    val inferredPurposes = solution.classifyPurposes()
    if (inferredPurposes.isEmpty()) {
        return emptySet()
    }

    if (purposeHints.isEmpty()) {
        return inferredPurposes
    }

    val hintedPurposes = mutableSetOf<PresentmentPurpose>()
    for (hint in purposeHints) {
        val mapped = hint.toPresentmentPurpose() ?: return emptySet()
        hintedPurposes.add(mapped)
    }

    val permittedByHints = hintedPurposes.flatMapTo(mutableSetOf()) { it.permittedInferredPurposes() }
    if (!permittedByHints.containsAll(inferredPurposes)) {
        return emptySet()
    }

    return hintedPurposes.sortedBy { it.ordinal }.toSet()
}

/**
 * Classifies the [PresentmentPurpose]s across all selected use-cases in a [PresentmentData].
 *
 * Returns an empty set if no use-cases are selected or if any selected use-case has an unknown
 * or unaccounted-for purpose (`useCase.classifyPurposes(selectionIndex).isEmpty()`).
 *
 * @param selections the selected solution indices for each [PresentmentUseCase] in [PresentmentData.useCases],
 *   or an empty list for an omitted optional use-case. Defaults to [PresentmentData.defaultSelections].
 * @return the set of [PresentmentPurpose]s in enum declaration order, or an empty set if unknown.
 */
internal fun PresentmentData.classifyPurposes(
    selections: List<List<Int>> = defaultSelections()
): Set<PresentmentPurpose> {
    require(selections.size == useCases.size) {
        "Expected selections length (${selections.size}) to match useCases length (${useCases.size})"
    }
    val combined = mutableSetOf<PresentmentPurpose>()
    useCases.forEachIndexed { index, useCase ->
        val selectedIndices = selections[index]
        if (selectedIndices.isEmpty()) {
            require(useCase.optional) { "Cannot omit non-optional use-case at index $index" }
        } else {
            for (selectionIndex in selectedIndices) {
                require(selectionIndex in useCase.solutions.indices) {
                    "Out-of-bounds solution index $selectionIndex for use-case at index $index"
                }
                val useCasePurposes = useCase.classifyPurposes(selectionIndex)
                if (useCasePurposes.isEmpty()) {
                    return emptySet()
                }
                combined.addAll(useCasePurposes)
            }
        }
    }
    return combined.sortedBy { it.ordinal }.toSet()
}

/**
 * Classifies the [PresentmentPurpose]s across all credential matches in a [PresentmentSelection].
 *
 * Returns an empty set if the selection has no matches or if any match has unaccounted-for claims.
 */
internal fun PresentmentSelection.classifyPurposes(): Set<PresentmentPurpose> {
    if (matches.isEmpty()) {
        return emptySet()
    }
    val combined = mutableSetOf<PresentmentPurpose>()
    for (match in matches) {
        val matchPurposes = match.classifyPurposes()
        if (matchPurposes.isEmpty()) {
            return emptySet()
        }
        combined.addAll(matchPurposes)
    }
    return combined.sortedBy { it.ordinal }.toSet()
}

/**
 * Returns `true` if this credential match's doctype or VCT is in [KNOWN_PURPOSE_DOCTYPES_AND_VCTS].
 */
internal fun PresentmentCredentialMatch.isKnownPurposeDocTypeOrVct(): Boolean =
    resolveDocTypeOrVct() in KNOWN_PURPOSE_DOCTYPES_AND_VCTS

/**
 * Returns `true` if the selected credentials in [PresentmentData] represent an unusual or
 * over-broad request on a known document type, an unrecognized/mismatched
 * [PresentmentUseCase.purposeHints], or an unknown schema attribute (`attribute == null`).
 */
internal fun PresentmentData.hasUnusualRequest(
    selections: List<List<Int>> = defaultSelections()
): Boolean {
    useCases.forEachIndexed { index, useCase ->
        val selectedIndices = selections[index]
        for (selectionIndex in selectedIndices) {
            val solution = useCase.solutions.getOrNull(selectionIndex) ?: return true
            for (match in solution.matches) {
                if (match.claims.values.any { it.attribute == null }) {
                    return true
                }
                if (match.isKnownPurposeDocTypeOrVct() && match.classifyPurposes().isEmpty()) {
                    return true
                }
            }
            if (useCase.purposeHints.isNotEmpty() && useCase.classifyPurposes(selectionIndex).isEmpty()) {
                return true
            }
        }
    }
    return false
}

private fun PresentmentPurpose.permittedInferredPurposes(): Set<PresentmentPurpose> = when (this) {
    PresentmentPurpose.DRIVING_PRIVILEGES -> setOf(
        PresentmentPurpose.DRIVING_PRIVILEGES,
        PresentmentPurpose.IDENTITY_VERIFICATION,
        PresentmentPurpose.AGE_VERIFICATION,
    )
    PresentmentPurpose.IDENTITY_VERIFICATION -> setOf(
        PresentmentPurpose.IDENTITY_VERIFICATION,
        PresentmentPurpose.ADDRESS_VERIFICATION,
        PresentmentPurpose.AGE_VERIFICATION,
    )
    else -> setOf(this)
}

private fun PresentmentCredentialMatch.resolveDocTypeOrVct(): String? {
    when (val cred = credential) {
        is MdocCredential -> return cred.docType
        is SdJwtVcCredential -> return cred.vct
    }
    when (val src = source) {
        is PresentmentCredentialMatchSourceIso18013 -> src.docRequest.docType?.let { return it }
        is PresentmentCredentialMatchSourceOpenID4VP -> {
            src.credentialQuery.mdocDocType?.let { return it }
            src.credentialQuery.vctValues?.firstOrNull()?.let { return it }
        }
    }
    claims.values.forEach { claim ->
        when (claim) {
            is MdocClaim -> return claim.docType
            is JsonClaim -> return claim.vct
        }
    }
    return null
}

private fun Claim.canonicalIdentifier(): String {
    attribute?.identifier?.let { attrId ->
        if (this is JsonClaim && attribute?.parentAttribute != null) {
            return "${attribute!!.parentAttribute!!.identifier}.$attrId"
        }
        return attrId
    }
    return when (this) {
        is MdocClaim -> dataElementName
        is JsonClaim -> claimPath
            .filterIsInstance<JsonPrimitive>()
            .filter { it.isString }
            .joinToString(".") { it.content }
    }
}

private fun isAgeClaimIdentifier(id: String): Boolean {
    return id == "age_in_years" ||
        id == "age_birth_year" ||
        id.startsWith("age_over_") ||
        id.startsWith("age_above") ||
        id.startsWith("age_equal_or_over.")
}
