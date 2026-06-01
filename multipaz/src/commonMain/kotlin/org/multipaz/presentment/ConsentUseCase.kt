package org.multipaz.presentment

/**
 * A use-case which is part of a transaction.
 *
 * @property optional whether the use-case is optional or must be satisfied.
 * @property solutions a list of solutions to the use-case, contains at least one solution but may contain more.
 */
data class ConsentUseCase(
    val optional: Boolean,
    val solutions: List<ConsentUseCaseSolution>
)
