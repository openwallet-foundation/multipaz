package org.multipaz.eventlog

import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Instant

/**
 * Base class for events recorded in the [EventLog].
 *
 * This sealed class ensures a restricted hierarchy of event types that can be securely
 * serialized and persisted.
 *
 * Any data recorded needs to be able to work on other systems (e.g. forensic analysis)
 * and in the future where the user might have deleted the document that they presented.
 *
 * As such, data should be copied into the event and use of identifiers to point to e.g.
 * documents, credentials, or trust entries is only allowed if there it's optional or not
 * the only source of truth.
 *
 * @property identifier A unique identifier for the event.
 * @property timestamp The timestamp when the event was recorded.
 */
@CborSerializable
sealed class Event(
    open val identifier: String,
    open val timestamp: Instant,
) {
    /**
     * Creates a copy of this event with a new identifier and timestamp.
     *
     * This is used internally by [EventLog] to assign a storage-generated key
     * and a concrete timestamp at the exact moment of persistence.
     */
    internal abstract fun copy(eventIdentifier: String, timestamp: Instant): Event

    companion object
}

// TODO: Add events for provisioning, PII updates, MSO updates, etc