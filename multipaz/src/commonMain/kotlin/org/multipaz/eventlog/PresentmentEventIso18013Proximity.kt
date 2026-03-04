package org.multipaz.eventlog

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * An event representing an ISO/IEC 18013-5 presentment for proximity.
 *
 * TODO: include location (e.g. GPS coordinates)
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property data Data about the presentment.
 * @property request The raw request data.
 * @property response The raw response data.
 * @property sessionTranscript The raw session transcript data.
 */
data class PresentmentEventIso18013Proximity(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    val data: PresentmentEventData,
    // The raw data from the presentment event.
    val request: DataItem,
    val response: DataItem,
    val sessionTranscript: DataItem,
): Event(identifier, timestamp) {
    override fun copy(eventIdentifier: String, timestamp: Instant) = copy(
        identifier = eventIdentifier,
        timestamp = timestamp
    )
}
