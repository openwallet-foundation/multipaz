package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * Base class for events recorded in the [EventLogger] related to credential presentment.
 *
 * @property presentmentData a [EventPresentmentData] with details about the presentment.
 */
sealed class EventPresentment(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    open val presentmentData: EventPresentmentData
): Event(identifier, timestamp, appData)