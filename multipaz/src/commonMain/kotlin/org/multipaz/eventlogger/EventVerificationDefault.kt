package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.verification.PresentmentRecord
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A fallback or general event representing a verification event.
 *
 * @property identifier a unique identifier for the event.
 * @property timestamp the timestamp when the event was recorded.
 * @property appData additional application-specific data.
 * @property presentmentRecord a [PresentmentRecord] with the result of the presentation.
 * @property durationRequestSentToResponseReceived duration from sending the request until receiving the response, if known.
 */
@CborSerializable(typeId = "Verification")
data class EventVerificationDefault(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentRecord: PresentmentRecord,
    override val durationRequestSentToResponseReceived: Duration? = null,
): EventVerification(identifier, timestamp, appData, presentmentRecord, durationRequestSentToResponseReceived) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentRecord = this.presentmentRecord,
        durationRequestSentToResponseReceived = this.durationRequestSentToResponseReceived,
    )
}
