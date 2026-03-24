package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * An event representing an OpenID4VP presentment initiated via a URI scheme.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property presentmentData Data about the presentment.
 * @property uri the URI used to invoke the presentment.
 * @property appId the identifier of the application making the request, if known.
 * @property origin the origin of the website making the request, if known.
 * @property requestJwt the JWT containing the authorization request.
 * @property vpToken the resulting vpToken.
 * @property redirectUri the URI which was launched in the user's default browser if any.
 */
data class EventPresentmentUriSchemeOpenID4VP(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentData: EventPresentmentData,
    // The raw data from the presentment event.
    val uri: String,
    val appId: String?,
    val origin: String?,
    val requestJwt: String,
    val vpToken: String,
    val redirectUri: String?
): EventPresentment(identifier, timestamp, appData, presentmentData) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentData = this.presentmentData
    )
}
