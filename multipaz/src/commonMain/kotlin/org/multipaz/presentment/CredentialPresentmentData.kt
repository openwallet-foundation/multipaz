package org.multipaz.presentment

import org.multipaz.document.Document
import org.multipaz.util.Logger
import org.multipaz.util.generateAllPaths

private const val TAG = "CredentialPresentmentData"

/**
 * An object containing data related to a credential presentment event.
 *
 * This object is intended to be used for user interfaces for the user to consent to and
 * possible select which credentials to return. See the [Consent] composable in `multipaz-compose`
 * and [Consent] view in `multipaz-swift` for examples of how to do this.
 *
 * @property credentialSets A list of credential sets which can be presented. Contains at
 *   least one set but may contain more.
 */
data class CredentialPresentmentData(
    val credentialSets: List<CredentialPresentmentSet>
) {
    /**
     * Consolidates matches from several options and members into one.
     *
     * Applications can use this when constructing user interfaces for conveying various
     * options to the end user.
     *
     * For example, for a relying party which requests an identity document (say, mDL, PID or PhotoID)
     * and a transportation ticket (say, airline boarding pass or train ticket) the resulting
     * [CredentialPresentmentData] after executing the request would be:
     * ```
     *   CredentialSet
     *     Option
     *       Member
     *         Match: mDL
     *     Option
     *       Member
     *         Match: PID
     *     Option
     *       Member
     *         Match: PhotoID
     *         Match: PhotoID #2
     *   CredentialSet
     *     Option
     *       Member
     *         Match: Boarding Pass BOS -> ERW
     *     Option
     *       Member
     *         Match: Train Ticket Providence -> New York Penn Station
     * ```
     * This function consolidates options, members, and matches like so
     * ```
     *   CredentialSet
     *     Option
     *       Member
     *         Match: mDL
     *         Match: PID
     *         Match: PhotoID
     *         Match: PhotoID #2
     *   CredentialSet
     *     Option
     *       Member
     *         Match: Boarding Pass BOS -> SFO
     *         Match: Train Ticket Providence -> New York Penn Station
     * ```
     * which - depending on how the application constructs its user interface - may give the user
     * a simpler user interface for deciding which credentials to return.
     *
     * @return a [CredentialPresentmentData] with options, members, and matches consolidated.
     */
    fun consolidate(): CredentialPresentmentData {
        val ret = mutableListOf<CredentialPresentmentSet>()
        credentialSets.forEach { credentialSet ->
            ret.add(credentialSet.consolidateSingleMemberOptions())
        }
        return CredentialPresentmentData(ret)
    }

    /**
     * Selects a particular combination of credentials to present.
     *
     * If [preselectedDocuments] is empty, this picks the first option, member, and match.
     *
     * Otherwise if [preselectedDocuments] is not empty, the options, members, and matches are
     * selected such that the list of returned credentials match the documents in [preselectedDocuments].
     * If this isn't possible, the selection returned will be the same as if [preselectedDocuments]
     * was the empty list.
     *
     * @param preselectedDocuments either empty or a list of documents the user already selected.
     * @return a [CredentialPresentmentSelection].
     */
    fun select(preselectedDocuments: List<Document>): CredentialPresentmentSelection {
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
        return CredentialPresentmentSelection(matches = matches)
    }

    private fun pickFromPreselectedDocuments(preselectedDocuments: List<Document>): CredentialPresentmentSelection? {
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
                return CredentialPresentmentSelection(chosenMatches)
            }
        }
        Logger.w(TAG, "Error picking combination for pre-selected documents")
        return null
    }
}

private data class CombinationElement(
    val matches: List<CredentialPresentmentSetOptionMemberMatch>
)

private data class Combination(
    val elements: List<CombinationElement>
)

