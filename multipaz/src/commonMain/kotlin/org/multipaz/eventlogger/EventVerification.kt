package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.verification.PresentmentRecord
import kotlin.time.Instant

/**
 * An event representing a verification event.
 *
 * @property presentmentRecord a [PresentmentRecord] with the result of the presentation.
 */
data class EventVerification(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    val presentmentRecord: PresentmentRecord
): Event(identifier, timestamp, appData) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentRecord = this.presentmentRecord
    )
}