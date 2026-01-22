package org.multipaz.openid.dcql

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.util.Logger
import org.multipaz.util.generateAllPaths

private const val TAG = "DcqlResponse"

/**
 * The result of executing a DCQL query against credentials in [org.multipaz.document.DocumentStore].
 *
 * The response consists of the credential sets. Queries not declaring any `credential_sets`
 * parameter in the DCQL query are equivalent to queries with a single non-optional credential
 * set per credential query, and are treated as such.
 */
data class DcqlResponse(
    override val credentialSets: List<DcqlResponseCredentialSet>
): CredentialPresentmentData() {

    override fun consolidate(): CredentialPresentmentData {
        val ret = mutableListOf<DcqlResponseCredentialSet>()
        credentialSets.forEach { credentialSet ->
            ret.add(credentialSet.consolidateSingleMemberOptions())
        }
        return DcqlResponse(ret)
    }

    override fun select(preselectedDocuments: List<Document>): CredentialPresentmentSelection {
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
