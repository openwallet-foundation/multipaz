package org.multipaz.eventlog

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * An event representing an ISO/IEC 18013-5 presentment according to 18013-7 Annex A.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property data Data about the presentment.
 * @property uri the URI used to invoke the presentment.
 * @property request The raw request data.
 * @property response The raw response data.
 * @property sessionTranscript The raw session transcript data.
 * @property appId the identifier of the application making the request, if known.
 * @property origin the origin of the website making the request, if known.
 * @property readerEngagement The raw reader engagement data.
 */
data class PresentmentEventIso18013AnnexA(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    val data: PresentmentEventData,
    // The raw data from the presentment event.
    val uri: String,
    val request: DataItem,
    val response: DataItem,
    val sessionTranscript: DataItem,
    val appId: String?,
    val origin: String?,
    val readerEngagement: DataItem
): Event(identifier, timestamp) {
    override fun copy(eventIdentifier: String, timestamp: Instant) = copy(
        identifier = eventIdentifier,
        timestamp = timestamp
    )
}
