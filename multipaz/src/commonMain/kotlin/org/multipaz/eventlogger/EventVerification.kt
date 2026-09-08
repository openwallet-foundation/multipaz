package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.verification.PresentmentRecord
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Base class for events recorded in [EventLogger] related to credential verification.
 *
 * Specific subclasses exist for digital credentials API ([EventVerificationDigitalCredentials]) and
 * proximity presentment ([EventVerificationIso18013Proximity]). Support for verification via URI
 * schemes (such as ISO/IEC 18013-7 Annex A and OpenID4VP URI schemes) will be added in the future as needed.
 *
 * @property identifier a unique identifier for the event.
 * @property timestamp the timestamp when the event was recorded.
 * @property appData additional application-specific data.
 * @property presentmentRecord a [PresentmentRecord] with the result of the presentation.
 * @property durationRequestSentToResponseReceived duration from sending the request until receiving the response, if known.
 */
sealed class EventVerification(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    open val presentmentRecord: PresentmentRecord,
    open val durationRequestSentToResponseReceived: Duration? = null,
): Event(identifier, timestamp, appData)