package org.multipaz.presentment

import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.TransactionUserInput

/**
 * A selection of credentials and claims in a [CredentialQueryResult].
 *
 * This object represents the result of selecting a concrete set of options, members, and matches
 * from a [CredentialQueryResult] object.
 *
 * This is typically returned from a consent prompt user interface.
 *
 * @property matches the list of credentials and claims to return to the relying party.
 * @property transactionUserInput additional user input for transactions, indexed by the transaction
 *  type identifier
 */
data class CredentialSelection(
    val matches: List<CredentialPresentmentSetOptionMemberMatch>,
    val transactionUserInput: Map<String, TransactionUserInput>
) {

    /**
     * Returns the highest sensitivity level of the claims in all the credentials in this object.
     *
     * @return The highest sensitivity level or `null` if one or more of the claims are unknown.
     */
    fun getMaxSensitivity(): DocumentAttributeSensitivity? {
        var maxSensitivity: DocumentAttributeSensitivity? = null
        matches.forEach { match ->
            match.claims.forEach { (_, claim) ->
                claim.attribute?.let { attribute ->
                    if (maxSensitivity == null) {
                        maxSensitivity = attribute.sensitivity
                    } else {
                        if (attribute.sensitivity > maxSensitivity!!) {
                            maxSensitivity = attribute.sensitivity
                        }
                    }
                } ?: return null
            }
        }
        return maxSensitivity
    }
}