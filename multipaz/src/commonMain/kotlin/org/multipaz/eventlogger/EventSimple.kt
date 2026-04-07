package org.multipaz.eventlogger

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * A simple event which can be used by applications for custom events.
 *
 * @property data application-specific data.
 */
data class EventSimple(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    val data: ByteString
): Event(identifier, timestamp, appData) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        data = this.data
    )
}