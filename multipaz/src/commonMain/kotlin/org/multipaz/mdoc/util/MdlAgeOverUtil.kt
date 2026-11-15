package org.multipaz.mdoc.util

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple

internal const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
internal const val MDL_NAMESPACE = "org.iso.18013.5.1"
internal const val MAX_MDL_AGE_OVER_ELEMENTS = 2

private const val AGE_OVER_PREFIX = "age_over_"
private const val AGE_OVER_IDENTIFIER_LENGTH = 11

/**
 * Parses an ISO/IEC 18013-5 Clause 13.4.6 `age_over_NN` data element identifier where `NN` is
 * two ASCII digits (`"00"`..`"99"`), returning `0`..`99` or `null` if [dataElementName] does not
 * match that exact format.
 */
internal fun parseAgeOverThreshold(dataElementName: String): Int? {
    if (dataElementName.length != AGE_OVER_IDENTIFIER_LENGTH ||
        !dataElementName.startsWith(AGE_OVER_PREFIX)
    ) {
        return null
    }
    val tens = dataElementName[9]
    val ones = dataElementName[10]
    if (tens !in '0'..'9' || ones !in '0'..'9') {
        return null
    }
    return (tens - '0') * 10 + (ones - '0')
}

/**
 * Returns `true` if ([docType], [namespaceName], [dataElementName]) is an mDL (`"org.iso.18013.5.1.mDL"`)
 * `age_over_NN` data element in `"org.iso.18013.5.1"`.
 */
internal fun isMdlAgeOverElement(
    docType: String?,
    namespaceName: String,
    dataElementName: String,
): Boolean =
    docType == MDL_DOCTYPE &&
        namespaceName == MDL_NAMESPACE &&
        parseAgeOverThreshold(dataElementName) != null

/**
 * Returns `true` if ([namespaceName], [dataElementName]) is in `"org.iso.18013.5.1"` with
 * format `age_over_NN`.
 */
internal fun isMdlNamespaceAgeOverElement(
    namespaceName: String,
    dataElementName: String,
): Boolean =
    namespaceName == MDL_NAMESPACE && parseAgeOverThreshold(dataElementName) != null

internal fun DataItem.asBooleanOrNull(): Boolean? = when (this) {
    Simple.TRUE -> true
    Simple.FALSE -> false
    else -> null
}

/**
 * Implements ISO/IEC 18013-5 Clause 13.4.6 nearest-attestation lookup for an `age_over_NN`
 * request against [candidates] stored on an mDL:
 * 1. Among attestations `age_over_nn` with value `true` and `nn >= NN`, select the one
 *    minimizing `nn - NN`.
 * 2. Otherwise, among attestations `age_over_nn` with value `false` and `nn <= NN`, select the
 *    one minimizing `NN - nn`.
 * 3. Otherwise return `null`.
 */
internal fun <T> findNearestAgeOver(
    requestedDataElementName: String,
    candidates: Iterable<T>,
    getName: (T) -> String,
    getBooleanValue: (T) -> Boolean?,
): T? {
    val requestedAge = parseAgeOverThreshold(requestedDataElementName) ?: return null

    var bestTrueCandidate: T? = null
    var bestTrueDiff = Int.MAX_VALUE
    var bestFalseCandidate: T? = null
    var bestFalseDiff = Int.MAX_VALUE

    for (candidate in candidates) {
        val candidateAge = parseAgeOverThreshold(getName(candidate)) ?: continue
        val value = getBooleanValue(candidate) ?: continue
        if (value) {
            if (candidateAge >= requestedAge) {
                val diff = candidateAge - requestedAge
                if (diff < bestTrueDiff) {
                    bestTrueDiff = diff
                    bestTrueCandidate = candidate
                }
            }
        } else {
            if (candidateAge <= requestedAge) {
                val diff = requestedAge - candidateAge
                if (diff < bestFalseDiff) {
                    bestFalseDiff = diff
                    bestFalseCandidate = candidate
                }
            }
        }
    }

    return bestTrueCandidate ?: bestFalseCandidate
}

/**
 * Returns `true` if a disclosed issuer-signed `age_over_nn` element with boolean value
 * [returnedValue] validly answers a request for [requestedDataElementName] under
 * ISO/IEC 18013-5 Clause 13.4.6 (`true` with `nn >= NN`, or `false` with `nn <= NN`).
 */
internal fun isValidMdlAgeOverSubstitution(
    requestedDataElementName: String,
    returnedDataElementName: String,
    returnedValue: Boolean,
): Boolean {
    val requestedAge = parseAgeOverThreshold(requestedDataElementName) ?: return false
    val returnedAge = parseAgeOverThreshold(returnedDataElementName) ?: return false
    return if (returnedValue) {
        returnedAge >= requestedAge
    } else {
        returnedAge <= requestedAge
    }
}
