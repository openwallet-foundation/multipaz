package org.multipaz.eventlog

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * An event representing an ISO/IEC 18013-7 Annex C presentment requested via Digital Credentials API.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property data Data about the presentment.
 * @property appId the identifier of the application making the request, if known.
 * @property origin the origin of the website making the request.
 * @property protocol the W3C Digital Credentials API protocol identifier.
 * @property requestJson the W3C Digital Credentials API request.
 * @property responseJson the W3C Digital Credentials API response.
 * @property deviceResponse The raw response data.
 */
data class PresentmentEventDigitalCredentialsMdocApi(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    val data: PresentmentEventData,
    // The raw data from the presentment event.
    val appId: String?,
    val origin: String,
    val protocol: String,
    val requestJson: String,
    val responseJson: String,
    val deviceResponse: DataItem
): Event(identifier, timestamp) {
    override fun copy(eventIdentifier: String, timestamp: Instant) = copy(
        identifier = eventIdentifier,
        timestamp = timestamp
    )
}
