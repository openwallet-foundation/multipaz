package org.multipaz.eventlogger

import kotlin.time.Instant
import org.multipaz.cbor.DataItem
import org.multipaz.presentment.PresentmentTelemetry

/**
 * An event representing an OpenID4VP presentment requested via Digital Credentials API.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property presentmentData Data about the presentment, or `null` if presentment failed before response generation.
 * @property appId the identifier of the application making the request, if known.
 * @property origin the origin of the website making the request.
 * @property protocol the W3C Digital Credentials API protocol identifier.
 * @property requestJson the W3C Digital Credentials API request.
 * @property responseJson the W3C Digital Credentials API response, if generated.
 * @property vpToken the resulting vpToken, if generated.
 * @property state OpenID4VP state parameter (if any)
 * @property telemetry Anonymized telemetry collected during the presentment session.
 */
data class EventPresentmentDigitalCredentialsOpenID4VP(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentData: EventPresentmentData? = null,
    // The raw data from the presentment event.
    val appId: String? = null,
    val origin: String,
    val protocol: String,
    val requestJson: String,
    val responseJson: String? = null,
    val vpToken: String? = null,
    val state: String? = null,
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
