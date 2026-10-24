package org.multipaz.presentment

import org.multipaz.document.Document
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.util.Logger
import org.multipaz.util.generateAllPaths

private const val TAG = "PresentmentData"

/**
 * Data for credential presentment.
 *
 * This object contains the result of executing a credential query against a [PresentmentSource],
 * organized by use-case and solution so it's easy to display in a user interface.
 *
 * @property useCases the use-cases in the transaction, contains at least one.
 */
data class PresentmentData(
    val useCases: List<PresentmentUseCase>,
) {

    /**
     * Calculates a [PresentmentSelection] from a list of selections.
     *
     * @param selections the solution selected for each use-case or -1 if not selecting an optional use-case.
     * @param transactionUserInput additional user input for transactions, indexed by credential match and transaction
     *  type identifier.
     * @return a [PresentmentSelection].
     */
    fun toPresentmentSelection(
        selections: List<Int>,
        transactionUserInput: Map<PresentmentCredentialMatch, Map<String, TransactionUserInput>> = emptyMap()
    ): PresentmentSelection {
        require(selections.size == useCases.size) {
            "Expected selectionPerUseCase length to be that of useCases"
        }
        val matches = mutableListOf<PresentmentCredentialMatch>()
        useCases.forEachIndexed { index, useCase ->
            val selection = selections[index]
            if (selection < 0) {
                require(useCase.optional) { "Cannot un-select non-optional use-case at index $index" }
            } else {
                require(selection < useCase.solutions.size) { "Cannot select out-of-bound use-case at index $index" }
                val solution = useCase.solutions[selection]
                solution.matches.forEach { match ->
                    val input = transactionUserInput[match] ?: emptyMap()
                    matches.add(match.copy(transactionUserInput = input))
                }
            }
        }
        return PresentmentSelection(matches)
    }

    /**
     * Selects a particular combination of credentials to present.
     *
     * If [preselectedDocuments] is empty, this picks the first solution for each use-case.
     *
     * Otherwise, if [preselectedDocuments] is not empty, the solutions are selected such that
     * the list of returned credentials match the documents in [preselectedDocuments].
     * If this isn't possible, the selection returned will be the same as if [preselectedDocuments]
     * was the empty list.
     *
     * @param preselectedDocuments either empty or a list of documents the user already selected.
     * @return a [PresentmentSelection].
     */
    fun select(preselectedDocuments: List<Document>): PresentmentSelection {
        if (preselectedDocuments.isNotEmpty()) {
            pickFromPreselectedDocuments(preselectedDocuments)?.let {
                return it
            }
        }

        val matches = mutableListOf<PresentmentCredentialMatch>()
        useCases.forEach { useCase ->
            matches.addAll(useCase.solutions[0].matches)
        }
        return PresentmentSelection(matches = matches)
    }

    private fun pickFromPreselectedDocuments(preselectedDocuments: List<Document>): PresentmentSelection? {
        val useCasesMaxPath = useCases.map { useCase ->
            // If a useCase is optional, omitting it is an extra combination we tag at the end
            useCase.solutions.size + (if (useCase.optional) 1 else 0)
        }

        val setOfPreselectedDocuments = preselectedDocuments.toSet()
        for (path in useCasesMaxPath.generateAllPaths()) {
            val chosenMatches = mutableListOf<PresentmentCredentialMatch>()
            useCases.forEachIndexed { useCaseNum, useCase ->
                val omitUseCase = (path[useCaseNum] == useCase.solutions.size)
                if (omitUseCase) {
                    check(useCase.optional)
                } else {
                    chosenMatches.addAll(useCase.solutions[path[useCaseNum]].matches)
                }
            }
            if (chosenMatches.size == preselectedDocuments.size &&
                chosenMatches.all { setOfPreselectedDocuments.contains(it.credential.document) }
            ) {
                return PresentmentSelection(chosenMatches)
            }
        }
        Logger.w(TAG, "Error picking combination for pre-selected documents")
        return null
    }

    /**
     * Gets all possible selections.
     *
     * @return all possible selections from the given use-cases and solutions.
     */
    fun getAllSelections(): List<PresentmentSelection> {
        val allUseCaseSelections = useCases.map { useCase ->
            val selectionsForUseCase = mutableListOf<List<PresentmentCredentialMatch>>()
            if (useCase.optional) {
                selectionsForUseCase.add(emptyList())
            }
            for (solution in useCase.solutions) {
                selectionsForUseCase.add(solution.matches)
            }
            selectionsForUseCase
        }

        val paths = allUseCaseSelections.map { it.size }.generateAllPaths()
        val finalSelections = mutableListOf<PresentmentSelection>()
        for (path in paths) {
            val allMatches = mutableListOf<PresentmentCredentialMatch>()
            path.forEachIndexed { useCaseIndex, selectionIndex ->
                allMatches.addAll(allUseCaseSelections[useCaseIndex][selectionIndex])
            }
            finalSelections.add(PresentmentSelection(allMatches))
        }
        return finalSelections
    }
}
