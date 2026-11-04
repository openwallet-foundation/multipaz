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
     * Returns the default selection of solution indices for each use-case.
     *
     * For each use-case, if [PresentmentUseCase.allowMultiple] is `true`, all solutions are selected by default;
     * otherwise only the first solution (`0`) is selected. If [preselectedDocuments] is non-empty, solutions matching
     * [preselectedDocuments] are preferred.
     *
     * @param preselectedDocuments either empty or a list of documents the user already selected.
     * @return a list of selected solution index lists, one per use-case.
     */
    fun defaultSelections(preselectedDocuments: List<Document> = emptyList()): List<List<Int>> {
        if (preselectedDocuments.isEmpty()) {
            return useCases.map { useCase ->
                when {
                    useCase.solutions.isEmpty() -> emptyList()
                    useCase.allowMultiple -> useCase.solutions.indices.toList()
                    else -> listOf(0)
                }
            }
        }
        val preselectedSet = preselectedDocuments.toSet()
        return useCases.map { useCase ->
            if (useCase.allowMultiple) {
                val matchingIndices = useCase.solutions.indices.filter { idx ->
                    useCase.solutions[idx].matches.all { it.credential.document in preselectedSet }
                }
                when {
                    matchingIndices.isNotEmpty() -> matchingIndices
                    useCase.optional -> emptyList()
                    else -> useCase.solutions.indices.toList()
                }
            } else {
                val matchingIndex = useCase.solutions.indexOfFirst { solution ->
                    solution.matches.all { it.credential.document in preselectedSet }
                }
                when {
                    matchingIndex >= 0 -> listOf(matchingIndex)
                    useCase.optional -> emptyList()
                    useCase.solutions.isNotEmpty() -> listOf(0)
                    else -> emptyList()
                }
            }
        }
    }

    /**
     * Calculates a [PresentmentSelection] from a list of selections.
     *
     * @param selections the list of solution indices selected for each use-case, or an empty list if not selecting an
     *  optional use-case.
     * @param transactionUserInput additional user input for transactions, indexed by credential match and transaction
     *  type identifier.
     * @return a [PresentmentSelection].
     */
    fun toPresentmentSelection(
        selections: List<List<Int>>,
        transactionUserInput: Map<PresentmentCredentialMatch, Map<String, TransactionUserInput>> = emptyMap()
    ): PresentmentSelection {
        require(selections.size == useCases.size) {
            "Expected selectionPerUseCase length to be that of useCases"
        }
        val matches = mutableListOf<PresentmentCredentialMatch>()
        useCases.forEachIndexed { index, useCase ->
            val selectedIndices = selections[index]
            if (selectedIndices.isEmpty()) {
                require(useCase.optional) { "Cannot un-select non-optional use-case at index $index" }
            } else {
                if (!useCase.allowMultiple) {
                    require(selectedIndices.size == 1) {
                        "Cannot select multiple solutions for use-case at index $index when allowMultiple is false"
                    }
                }
                val useCaseMatches = mutableListOf<PresentmentCredentialMatch>()
                for (selection in selectedIndices) {
                    require(selection in useCase.solutions.indices) {
                        "Cannot select out-of-bound use-case at index $index"
                    }
                    val solution = useCase.solutions[selection]
                    solution.matches.forEach { match ->
                        val input = transactionUserInput[match] ?: emptyMap()
                        useCaseMatches.add(match.copy(transactionUserInput = input))
                    }
                }
                matches.addAll(useCaseMatches.distinctBy { it.credential to it.source })
            }
        }
        return PresentmentSelection(matches)
    }

    /**
     * Selects a particular combination of credentials to present.
     *
     * If [preselectedDocuments] is empty, this picks the first solution for each use-case (or all solutions if
     * [PresentmentUseCase.allowMultiple] is `true`).
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
            if (useCase.allowMultiple) {
                val useCaseMatches = mutableListOf<PresentmentCredentialMatch>()
                useCase.solutions.forEach { solution ->
                    useCaseMatches.addAll(solution.matches)
                }
                matches.addAll(useCaseMatches.distinctBy { it.credential to it.source })
            } else {
                matches.addAll(useCase.solutions[0].matches)
            }
        }
        return PresentmentSelection(matches = matches)
    }

    private fun pickFromPreselectedDocuments(preselectedDocuments: List<Document>): PresentmentSelection? {
        val setOfPreselectedDocuments = preselectedDocuments.toSet()
        val candidateOptionsPerUseCase = useCases.map { useCase ->
            val options = mutableListOf<List<PresentmentCredentialMatch>>()
            if (useCase.allowMultiple) {
                val matchingSolutions = useCase.solutions.filter { solution ->
                    solution.matches.all { setOfPreselectedDocuments.contains(it.credential.document) }
                }
                if (matchingSolutions.isNotEmpty()) {
                    // Solutions from the same credential_set/useCase option share the same list of sources;
                    // also keep separate groups if multiple solutions exist so we can match either the
                    // combined multi-credential selection or a specific option group.
                    val combinedMatches = matchingSolutions
                        .flatMap { it.matches }
                        .distinctBy { it.credential to it.source }
                    options.add(combinedMatches)
                    val groupsBySource = matchingSolutions.groupBy { solution ->
                        solution.matches.map { it.source }
                    }
                    if (groupsBySource.size > 1) {
                        for (group in groupsBySource.values) {
                            options.add(group.flatMap { it.matches }.distinctBy { it.credential to it.source })
                        }
                    }
                }
            } else {
                for (solution in useCase.solutions) {
                    if (solution.matches.all { setOfPreselectedDocuments.contains(it.credential.document) }) {
                        options.add(solution.matches)
                    }
                }
            }
            if (useCase.optional) {
                options.add(emptyList())
            }
            if (options.isEmpty()) {
                Logger.w(TAG, "Error picking combination for pre-selected documents")
                return null
            }
            options
        }

        for (path in candidateOptionsPerUseCase.map { it.size }.generateAllPaths()) {
            val chosenMatches = mutableListOf<PresentmentCredentialMatch>()
            path.forEachIndexed { useCaseNum, optionIndex ->
                chosenMatches.addAll(candidateOptionsPerUseCase[useCaseNum][optionIndex])
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
