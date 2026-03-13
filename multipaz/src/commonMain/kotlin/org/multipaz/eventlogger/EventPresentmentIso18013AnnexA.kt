package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * An event representing an ISO/IEC 18013-5 presentment according to 18013-7 Annex A.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property presentmentData Data about the presentment.
 * @property uri the URI used to invoke the presentment.
 * @property request The raw request data.
 * @property response The raw response data.
 * @property sessionTranscript The raw session transcript data.
 * @property appId the identifier of the application making the request, if known.
 * @property origin the origin of the website making the request, if known.
 * @property readerEngagement The raw reader engagement data.
 */
data class EventPresentmentIso18013AnnexA(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentData: EventPresentmentData,
    // The raw data from the presentment event.
    val uri: String,
    val request: DataItem,
    val response: DataItem,
    val sessionTranscript: DataItem,
    val appId: String?,
    val origin: String?,
    val readerEngagement: DataItem
): EventPresentment(identifier, timestamp, appData, presentmentData) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentData = this.presentmentData
    )
}
