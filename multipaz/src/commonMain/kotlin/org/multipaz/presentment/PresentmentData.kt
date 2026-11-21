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
     * Every transaction data item of every match in every solution of every use-case, in order. A
     * consent prompt uses it to find out which renderers it needs before it is shown.
     */
    val allTransactionData: List<TransactionData<*>>
        get() = useCases.flatMap { useCase ->
            useCase.solutions.flatMap { solution -> solution.matches.flatMap { it.transactionData } }
        }

    private fun compatibleMultiIndices(
        useCase: PresentmentUseCase,
        candidateIndices: List<Int>,
    ): List<Int> {
        if (candidateIndices.isEmpty()) return emptyList()
        val anchor = useCase.solutions[candidateIndices.first()]
        return candidateIndices.filter { idx ->
            val candidate = useCase.solutions[idx]
            candidate.optionIndex == anchor.optionIndex &&
                candidate.matches.size == anchor.matches.size &&
                candidate.matches.indices.all { pos ->
                    anchor.matches[pos].source.allowsMultiple ||
                        candidate.matches[pos].credential == anchor.matches[pos].credential
                }
        }
    }

    /**
     * Returns the default selection of solution indices for each use-case.
     *
     * For each use-case, if [PresentmentUseCase.allowMultiple] is `true`, all compatible solutions from the first
     * option are selected by default; otherwise only the first solution (`0`) is selected. If [preselectedDocuments]
     * is non-empty, solutions matching [preselectedDocuments] are preferred.
     *
     * @param preselectedDocuments either empty or a list of documents the user already selected.
     * @return a list of selected solution index lists, one per use-case.
     */
    fun defaultSelections(preselectedDocuments: List<Document> = emptyList()): List<List<Int>> {
        if (preselectedDocuments.isEmpty()) {
            return useCases.map { useCase ->
                when {
                    useCase.solutions.isEmpty() -> emptyList()
                    useCase.allowMultiple -> compatibleMultiIndices(useCase, useCase.solutions.indices.toList())
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
                    matchingIndices.isNotEmpty() -> compatibleMultiIndices(useCase, matchingIndices)
                    useCase.optional -> emptyList()
                    else -> compatibleMultiIndices(useCase, useCase.solutions.indices.toList())
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
        require(useCases.isEmpty() || selections.any { it.isNotEmpty() }) {
            "At least one use-case must be selected"
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
                val selectedOptionIndices = mutableSetOf<Int>()
                for (selection in selectedIndices) {
                    require(selection in useCase.solutions.indices) {
                        "Cannot select out-of-bound use-case at index $index"
                    }
                    val solution = useCase.solutions[selection]
                    selectedOptionIndices.add(solution.optionIndex)
                    solution.matches.forEach { match ->
                        val input = transactionUserInput[match] ?: emptyMap()
                        useCaseMatches.add(match.copy(transactionUserInput = input))
                    }
                }
                require(selectedOptionIndices.size <= 1) {
                    "Cannot select solutions from multiple options in use-case at index $index"
                }
                val singleSources = useCaseMatches.filter { !it.source.allowsMultiple }.groupBy { it.source }
                for ((_, sourceMatches) in singleSources) {
                    require(sourceMatches.map { it.credential }.distinct().size <= 1) {
                        "Cannot select multiple credentials for a single-credential request in use-case at index $index"
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
     * If [preselectedDocuments] is empty, this picks the first solution for each use-case (or all compatible solutions
     * from the first option if [PresentmentUseCase.allowMultiple] is `true`).
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
            if (useCase.solutions.isEmpty()) {
                return@forEach
            }
            if (useCase.allowMultiple) {
                val chosenIndices = compatibleMultiIndices(useCase, useCase.solutions.indices.toList())
                val useCaseMatches = chosenIndices.flatMap { useCase.solutions[it].matches }
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
                    val compatibleGroups = matchingSolutions.groupBy { solution ->
                        solution.optionIndex to solution.matches
                            .filter { !it.source.allowsMultiple }
                            .map { it.source to it.credential }
                    }
                    for (group in compatibleGroups.values) {
                        options.add(group.flatMap { it.matches }.distinctBy { it.credential to it.source })
                    }
                    for (solution in matchingSolutions) {
                        val singleList = solution.matches
                        if (options.none { it == singleList }) {
                            options.add(singleList)
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
            if (chosenMatches.isNotEmpty() &&
                chosenMatches.size == preselectedDocuments.size &&
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
            if (allMatches.isNotEmpty() || useCases.isEmpty()) {
                finalSelections.add(PresentmentSelection(allMatches))
            }
        }
        return finalSelections
    }
}
