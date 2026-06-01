package org.multipaz.presentment

import org.multipaz.document.Document
import org.multipaz.util.Logger
import org.multipaz.util.generateAllPaths

private const val TAG = "CredentialQueryResult"

/**
 * An object containing the result of executing a query against [org.multipaz.document.DocumentStore].
 *
 * This object contains all the combinations in which the query can be satisfied, not just a single one.
 * For just a single solution, see [CredentialSelection].
 *
 * @property credentialSets A list of credential sets which can be presented. Contains at
 *   least one set but may contain more.
 */
data class CredentialQueryResult(
    val credentialSets: List<CredentialPresentmentSet>
) {
    /**
     * Selects a particular combination of credentials to present.
     *
     * If [preselectedDocuments] is empty, this picks the first option, member, and match.
     *
     * Otherwise, if [preselectedDocuments] is not empty, the options, members, and matches are
     * selected such that the list of returned credentials match the documents in [preselectedDocuments].
     * If this isn't possible, the selection returned will be the same as if [preselectedDocuments]
     * was the empty list.
     *
     * @param preselectedDocuments either empty or a list of documents the user already selected.
     * @return a [CredentialSelection].
     */
    fun select(preselectedDocuments: List<Document>): CredentialSelection {
        if (preselectedDocuments.isNotEmpty()) {
            pickFromPreselectedDocuments(preselectedDocuments)?.let {
                return it
            }
        }

        val matches = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
        credentialSets.forEach { credentialSet ->
            val option = credentialSet.options[0]
            option.members.forEach { member ->
                matches.add(member.matches[0])
            }
        }
        return CredentialSelection(matches = matches)
    }

    private fun pickFromPreselectedDocuments(preselectedDocuments: List<Document>): CredentialSelection? {
        val credentialSetsMaxPath = mutableListOf<Int>()
        credentialSets.forEachIndexed { n, credentialSet ->
            // If a credentialSet is optional, it's an extra combination we tag at the end
            credentialSetsMaxPath.add(credentialSet.options.size + (if (credentialSet.optional) 1 else 0))
        }

        val combinations = mutableListOf<Combination>()
        for (path in credentialSetsMaxPath.generateAllPaths()) {
            val elements = mutableListOf<CombinationElement>()
            credentialSets.forEachIndexed { credentialSetNum, credentialSet ->
                val omitCredentialSet = (path[credentialSetNum] == credentialSet.options.size)
                if (omitCredentialSet) {
                    check(credentialSet.optional)
                } else {
                    val option = credentialSet.options[path[credentialSetNum]]
                    for (member in option.members) {
                        elements.add(CombinationElement(matches = member.matches))
                    }
                }
            }
            combinations.add(Combination(elements = elements))
        }

        val setOfPreselectedDocuments = preselectedDocuments.toSet()
        combinations.forEach { combination ->
            if (combination.elements.size == preselectedDocuments.size) {
                val chosenMatches = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
                combination.elements.forEachIndexed { n, element ->
                    val match = element.matches.find { setOfPreselectedDocuments.contains(it.credential.document) }
                    if (match == null) {
                        return@forEach
                    }
                    chosenMatches.add(match)
                }
                return CredentialSelection(chosenMatches)
            }
        }
        Logger.w(TAG, "Error picking combination for pre-selected documents")
        return null
    }

    /**
     * Gets all possible selections.
     *
     * @return all possible selections from the given options.
     */
    fun getAllSelections(): List<CredentialSelection> {
        val allSetSelections = credentialSets.map { set ->
            val selectionsForSet = mutableListOf<List<CredentialPresentmentSetOptionMemberMatch>>()
            if (set.optional) {
                selectionsForSet.add(emptyList())
            }
            for (option in set.options) {
                // We need one match from each member.
                val memberMatchChoices = option.members.map { it.matches }
                val paths = memberMatchChoices.map { it.size }.generateAllPaths()
                for (path in paths) {
                    val selection = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
                    path.forEachIndexed { memberIndex, matchIndex ->
                        selection.add(memberMatchChoices[memberIndex][matchIndex])
                    }
                    selectionsForSet.add(selection)
                }
            }
            selectionsForSet
        }

        val paths = allSetSelections.map { it.size }.generateAllPaths()
        val finalSelections = mutableListOf<CredentialSelection>()
        for (path in paths) {
            val allMatches = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
            path.forEachIndexed { setIndex, selectionIndex ->
                allMatches.addAll(allSetSelections[setIndex][selectionIndex])
            }
            finalSelections.add(CredentialSelection(allMatches))
        }
        return finalSelections
    }
}

private data class CombinationElement(
    val matches: List<CredentialPresentmentSetOptionMemberMatch>
)

private data class Combination(
    val elements: List<CombinationElement>
)

