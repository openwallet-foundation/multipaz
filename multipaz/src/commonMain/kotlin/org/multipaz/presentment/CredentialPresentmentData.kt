package org.multipaz.presentment

import org.multipaz.document.Document

/**
 * An object containing data related to a credential presentment event.
 */
abstract class CredentialPresentmentData {
    /**
     * A list of credential sets which can be presented. Contains at least one set but may contain more.
     */
    abstract val credentialSets: List<CredentialPresentmentSet>

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
    abstract fun consolidate(): CredentialPresentmentData

    /**
     * Selects a particular combination of credentials to select.
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
    abstract fun select(preselectedDocuments: List<Document>): CredentialPresentmentSelection
}
