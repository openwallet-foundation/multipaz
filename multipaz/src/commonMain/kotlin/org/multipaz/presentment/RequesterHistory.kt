package org.multipaz.presentment

import kotlin.time.Instant
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

/**
 * Historical interaction record for a specific relying party (requester).
 *
 * @property shareCount total number of times credentials have been shared with this requester.
 * @property firstSharedAt timestamp of the first completed share with this requester.
 * @property lastSharedAt timestamp of the most recent completed share with this requester.
 */
@CborSerializable
data class RequesterHistoryEntry(
    val shareCount: Int,
    val firstSharedAt: Instant,
    val lastSharedAt: Instant,
) {
    companion object
}

/**
 * Tracks past credential presentment interactions with relying parties (requesters).
 *
 * Unlike [org.multipaz.eventlogger.EventLogger], which records detailed audit logs of
 * individual presentment sessions and payloads, [RequesterHistory] stores only lightweight
 * interaction counts and timestamps per identifiable requester so the consent prompt can
 * surface whether the user has shared with a relying party before.
 *
 * See [SimpleRequesterHistory] for an implementation backed by [org.multipaz.storage.Storage].
 */
interface RequesterHistory {
    /**
     * Looks up past interaction history for the given [requester] and [trustedRequesterIdentity].
     *
     * @param requester the relying party requesting credentials.
     * @param trustedRequesterIdentity trust metadata for the requester, or `null` if untrusted.
     * @return the [RequesterHistoryEntry] if the user has previously shared with this requester,
     *   or `null` if this is the first interaction or the requester is anonymous and cannot be
     *   uniquely identified.
     */
    suspend fun getEntry(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
    ): RequesterHistoryEntry?

    /**
     * Records a completed credential share with the given [requester] and [trustedRequesterIdentity].
     *
     * If the requester is anonymous and cannot be uniquely identified across sessions, this
     * method is a no-op.
     *
     * @param requester the relying party that received credentials.
     * @param trustedRequesterIdentity trust metadata for the requester, or `null` if untrusted.
     */
    suspend fun recordShare(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
    )

    /**
     * Deletes all recorded requester history.
     */
    suspend fun deleteAll()
}
