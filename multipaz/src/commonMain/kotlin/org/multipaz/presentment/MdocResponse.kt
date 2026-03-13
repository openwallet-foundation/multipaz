package org.multipaz.presentment

import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.mdoc.response.DeviceResponse

/**
 * The result of calling [mdocPresentment].
 *
 * @property deviceResponse a [org.multipaz.mdoc.response.DeviceResponse].
 * @property eventData a [eventData] which can be used to log the presentment.
 */
data class MdocResponse(
    val deviceResponse: DeviceResponse,
    val eventData: EventPresentmentData
)