package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.verification.PresentmentRecord
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An event representing credential verification requested via the W3C Digital Credentials API.
 *
 * @property identifier a unique identifier for the event.
 * @property timestamp the timestamp when the event was recorded.
 * @property appData additional application-specific data.
 * @property presentmentRecord a [PresentmentRecord] with the result of the presentation.
 * @property requestJson the W3C Digital Credentials API request JSON string.
 * @property responseJson the W3C Digital Credentials API response JSON string.
 * @property durationRequestSentToResponseReceived duration from sending the request until receiving the response, if known.
 * @property origin the origin of the website making the request, if known.
 * @property appId the identifier of the application making the request, if known.
 */
@CborSerializable
data class EventVerificationDigitalCredentials(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentRecord: PresentmentRecord,
    val requestJson: String,
    val responseJson: String,
    override val durationRequestSentToResponseReceived: Duration? = null,
    val origin: String? = null,
    val appId: String? = null,
): EventVerification(identifier, timestamp, appData, presentmentRecord, durationRequestSentToResponseReceived) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentRecord = this.presentmentRecord,
        requestJson = this.requestJson,
        responseJson = this.responseJson,
        durationRequestSentToResponseReceived = this.durationRequestSentToResponseReceived,
        origin = this.origin,
        appId = this.appId,
    )
}
