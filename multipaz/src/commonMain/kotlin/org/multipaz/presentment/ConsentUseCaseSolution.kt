package org.multipaz.presentment

/**
 * A solution to a use-case.
 *
 * @property credentials a list of credentials that must be returned together to satisfy the use-case.
 */
data class ConsentUseCaseSolution(
    val credentials: List<ConsentCredential>
)
