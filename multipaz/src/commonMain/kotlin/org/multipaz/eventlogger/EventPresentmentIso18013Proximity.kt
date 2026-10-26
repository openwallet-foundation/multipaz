package org.multipaz.eventlogger

import kotlin.time.Instant
import org.multipaz.cbor.DataItem
import org.multipaz.presentment.PresentmentTelemetry

/**
 * An event representing an ISO/IEC 18013-5 presentment for proximity.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property presentmentData Data about the presentment, or `null` if presentment failed before response generation.
 * @property request The raw request data, if received.
 * @property response The raw response data, if generated.
 * @property sessionTranscript The raw session transcript data, if established.
 * @property telemetry Anonymized telemetry collected during the presentment session.
 */
data class EventPresentmentIso18013Proximity(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentData: EventPresentmentData? = null,
    // The raw data from the presentment event.
    val request: DataItem? = null,
    val response: DataItem? = null,
    val sessionTranscript: DataItem? = null,
    override val telemetry: PresentmentTelemetry? = null,
): EventPresentment(identifier, timestamp, appData, presentmentData, telemetry) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentData = this.presentmentData,
        telemetry = this.telemetry,
    )
}
