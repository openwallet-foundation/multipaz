package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Instant

/**
 * Base class for events recorded in the [EventLogger].
 *
 * This sealed class ensures a restricted hierarchy of event types that can be securely
 * serialized and persisted. New events may be added to this hierarchy in future versions.
 *
 * Any data recorded needs to be able to work on other systems (e.g. forensic analysis)
 * and in the future where e.g. the user might have deleted the document that they presented.
 *
 * As such, data should be copied into the event and use of identifiers to point to e.g.
 * documents, credentials, or trust entries is only allowed if there it's optional or not
 * the only source of truth.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 * @property appData Additional application-specific data.
 */
@CborSerializable
sealed class Event(
    open val identifier: String,
    open val timestamp: Instant,
    open val appData: Map<String, DataItem>
) {
    /**
     * Creates a copy of this event with a new identifier, timestamp, and application-provided data.
     *
     * This is used internally by [EventLogger] implementations to assign a storage-generated key,
     * timestamp at the exact moment of persistence, and application-provided data.
     */
    internal abstract fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event

    companion object
}

// TODO: Add events for provisioning, PII updates, MSO updates, etc