package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * An event representing an ISO/IEC 18013-5 presentment for proximity.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property presentmentData Data about the presentment.
 * @property request The raw request data.
 * @property response The raw response data.
 * @property sessionTranscript The raw session transcript data.
 */
data class EventPresentmentIso18013Proximity(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentData: EventPresentmentData,
    // The raw data from the presentment event.
    val request: DataItem,
    val response: DataItem,
    val sessionTranscript: DataItem,
): EventPresentment(identifier, timestamp, appData, presentmentData) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentData = this.presentmentData
    )
}
