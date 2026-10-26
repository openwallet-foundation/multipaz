package org.multipaz.presentment

import kotlin.time.Duration
import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.mdoc.response.DeviceResponse

/**
 * The result of calling [mdocPresentment].
 *
 * @property deviceResponse a [org.multipaz.mdoc.response.DeviceResponse].
 * @property eventData a [eventData] which can be used to log the presentment.
 * @property durationUserConsentAndAuth time spent obtaining user consent, unlocking keys, and performing face matching.
 * @property durationResponseGeneration time spent generating and signing the [deviceResponse].
 */
data class Iso18013Response(
    val deviceResponse: DeviceResponse,
    val eventData: EventPresentmentData,
    val durationUserConsentAndAuth: Duration = Duration.ZERO,
    val durationResponseGeneration: Duration = Duration.ZERO,
)
