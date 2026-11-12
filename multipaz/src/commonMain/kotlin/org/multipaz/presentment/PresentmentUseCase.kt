package org.multipaz.presentment

/**
 * A use-case which is part of a transaction.
 *
 * @property optional whether the use-case is optional or must be satisfied.
 * @property solutions a list of solutions to the use-case, contains at least one solution but may contain more.
 * @property purposeHints a list of purpose hints for the use-case, if any.
 * @property allowMultiple whether multiple solutions may be selected and returned simultaneously for this use-case.
 */
data class PresentmentUseCase(
    val optional: Boolean,
    val solutions: List<PresentmentUseCaseSolution>,
    val purposeHints: List<PresentmentPurposeHint> = emptyList(),
    val allowMultiple: Boolean = false
)
