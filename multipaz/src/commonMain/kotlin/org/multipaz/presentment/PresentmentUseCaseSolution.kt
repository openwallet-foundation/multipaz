package org.multipaz.presentment

/**
 * A solution to a use-case.
 *
 * @property matches a list of credential matches that must be returned together to satisfy the use-case.
 * @property optionIndex the index of the `DocumentSet` or `credential_set` option within the parent
 *   use-case that produced this solution.
 */
data class PresentmentUseCaseSolution(
    val matches: List<PresentmentCredentialMatch>,
    val optionIndex: Int = 0,
)
