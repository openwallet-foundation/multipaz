package org.multipaz.presentment

/**
 * A member in a credential set option.
 *
 * All members in an option must be presented together.
 *
 * @property members a list of members for this credential set option. Contains at least one
 *   and may contain more.
 */
data class CredentialPresentmentSetOption(
    val members: List<CredentialPresentmentSetOptionMember>
)
