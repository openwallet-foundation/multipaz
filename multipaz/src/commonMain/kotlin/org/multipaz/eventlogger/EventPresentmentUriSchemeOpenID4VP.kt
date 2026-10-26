package org.multipaz.eventlogger

import kotlin.time.Instant
import org.multipaz.cbor.DataItem
import org.multipaz.presentment.PresentmentTelemetry

/**
 * An event representing an OpenID4VP presentment initiated via a URI scheme.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property presentmentData Data about the presentment, or `null` if presentment failed before response generation.
 * @property uri the URI used to invoke the presentment.
 * @property appId the identifier of the application making the request, if known.
 * @property origin the origin of the website making the request, if known.
 * @property requestJwt the JWT containing the authorization request, if fetched.
 * @property vpToken the resulting vpToken, if generated.
 * @property redirectUri the URI which was launched in the user's default browser if any.
 * @property state OpenID4VP state parameter (if any)
 * @property telemetry Anonymized telemetry collected during the presentment session.
 */
data class EventPresentmentUriSchemeOpenID4VP(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentData: EventPresentmentData? = null,
    // The raw data from the presentment event.
    val uri: String,
    val appId: String? = null,
    val origin: String? = null,
    val requestJwt: String? = null,
    val vpToken: String? = null,
    val redirectUri: String? = null,
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
