package org.multipaz.compose.eventlogger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.SimpleEventLogger

/**
 * A UI state holder for [SimpleEventLogger] designed for Compose Multiplatform.
 *
 * This model observes the underlying logger and exposes a reactive [StateFlow] of
 * events. The [eventLogger] is exposed publicly to allow direct invocation of its
 * suspending mutation functions (like `addEvent` or `deleteEvent`) from the UI's
 * coroutine scope.
 *
 * @property eventLogger The underlying persistent event logger.
 * @property coroutineScope The scope used to run background queries for the state flow.
 */
class SimpleEventLoggerModel(
    val eventLogger: SimpleEventLogger,
    coroutineScope: CoroutineScope
) {
    /**
     * A reactive stream of the currently stored events.
     *
     * - The initial value is `null` while the first database read is in progress.
     * - It automatically re-queries [SimpleEventLogger.getEvents] whenever the logger
     * emits a change notification.
     * - Uses [SharingStarted.WhileSubscribed] to pause database observations if
     * the UI is completely hidden, saving resources.
     */
    val events: StateFlow<List<Event>?> = eventLogger.eventFlow
        .onStart { emit(Unit) } // Trigger the initial load
        .map { eventLogger.getEvents() }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}