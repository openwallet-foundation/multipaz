package org.multipaz.presentment

/**
 * A set of credentials that can be presented together.
 *
 * If [optional] is `false` the application must present the credentials in exactly one of
 * the elements in the [options] list. All members in an option must be presented.
 *
 * If [options] has more than one element an application would typically present these in
 * an user interface for the user to select.
 *
 * @property optional `false` if the credential set must be presented, `true` if it's optional.
 * @property options a list of different credentials that can be presented. Contains at least one option
 *   and may contain more.
 */
data class CredentialPresentmentSet(
    val optional: Boolean,
    val options: List<CredentialPresentmentSetOption>
) {

    internal fun consolidateSingleMemberOptions(): CredentialPresentmentSet {
        val nonSingleMemberOptions = mutableListOf<CredentialPresentmentSetOption>()
        val singleMemberMatches = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
        var numSingleMemberOptions = 0
        for (option in options) {
            if (option.members.size == 1) {
                singleMemberMatches.addAll(option.members[0].matches)
                numSingleMemberOptions += 1
            } else {
                nonSingleMemberOptions.add(option)
            }
        }

        if (numSingleMemberOptions > 1) {
            return CredentialPresentmentSet(
                optional = optional,
                options = listOf(
                    CredentialPresentmentSetOption(
                        members = listOf(CredentialPresentmentSetOptionMember(
                            matches = singleMemberMatches
                        ))
                    )) + nonSingleMemberOptions
            )
        } else {
            return this
        }
    }

}
