package org.multipaz.eventlogger

import kotlinx.coroutines.CoroutineScope

/**
 * An interface for a persistent logger for recording events.
 *
 * See [SimpleEventLogger] for an implementation backed by [org.multipaz.storage.Storage] and
 * also includes an functionality for inspecting, filtering, amending, and deleting events
 * as well as observing when events are added or removed.
 */
interface EventLogger {
    /**
     * Adds an event to the log.
     *
     * This method generates a chronologically-sortable storage identifier and a current
     * timestamp for the event, runs application-provided code to check if the event
     * should be dropped or if app-specific data should be amended, and then finally saves
     * the event.
     *
     * Most code logging events will want to use [addEventAsync] since it runs in a separate
     * coroutine and thus not slow down a time-sensitive code such as credential presentment
     * where an external component is waiting for data to be returned.
     *
     * @param event The [Event] to be recorded, note that [Event.identifier], [Event.timestamp],
     * and [Event.appData] will be overwritten.
     * @return A copy of the [Event] with assigned id, timestamp, and appData. Returns `null` if
     * the event was dropped.
     */
    suspend fun addEvent(event: Event): Event?

    /**
     * Asynchronously adds an event to the log.
     *
     * This calls [addEvent] in a [CoroutineScope] passed to [EventLogger] implementation
     * at construction time. This is useful for time-sensitive code (e.g. presentment) which
     * do not want to wait until the event has been processed or written to persistent storage.
     *
     * @param event The [Event] to be recorded.
     */
    fun addEventAsync(event: Event)
}
