package org.multipaz.presentment

/**
 * A member of a credential set option.
 *
 * @property matches a list of matches of credentials from [DocumentStore] along with the [Claim]s to
 *   present that can be used for this member. Contains at least one match but may contain more.
 */
data class CredentialPresentmentSetOptionMember(
    val matches: List<CredentialPresentmentSetOptionMemberMatch>
)