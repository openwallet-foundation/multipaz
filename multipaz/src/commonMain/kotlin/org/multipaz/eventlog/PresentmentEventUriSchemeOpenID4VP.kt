package org.multipaz.eventlog

import kotlin.time.Instant

/**
 * An event representing an OpenID4VP presentment initiated via a URI scheme.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property data Data about the presentment.
 * @property uri the URI used to invoke the presentment.
 * @property appId the identifier of the application making the request, if known.
 * @property origin the origin of the website making the request, if known.
 * @property requestJwt the JWT containing the authorization request.
 * @property vpToken the resulting vpToken.
 * @property redirectUri the URI which was launched in the user's default browser.
 */
data class PresentmentEventUriSchemeOpenID4VP(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    val data: PresentmentEventData,
    // The raw data from the presentment event.
    val uri: String,
    val appId: String?,
    val origin: String?,
    val requestJwt: String,
    val vpToken: String,
    val redirectUri: String
): Event(identifier, timestamp) {
    override fun copy(eventIdentifier: String, timestamp: Instant) = copy(
        identifier = eventIdentifier,
        timestamp = timestamp
    )
}
