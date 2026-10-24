package org.multipaz.presentment

/**
 * A solution to a use-case.
 *
 * @property matches a list of credential matches that must be returned together to satisfy the use-case.
 */
data class PresentmentUseCaseSolution(
    val matches: List<PresentmentCredentialMatch>
)
