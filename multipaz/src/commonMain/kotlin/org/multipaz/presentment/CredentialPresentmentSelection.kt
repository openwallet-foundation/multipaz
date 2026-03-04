package org.multipaz.presentment

/**
 * A selection of credentials and claims in a [CredentialPresentmentData].
 *
 * This object represents the result of selecting a concrete set of options, members, and matches
 * from a [CredentialPresentmentData] object.
 *
 * This is typically returned from a consent prompt user interface.
 *
 * @property matches the list of credentials and claims to return to the relying party.
 */
data class CredentialPresentmentSelection(
    val matches: List<CredentialPresentmentSetOptionMemberMatch>,
)