package org.multipaz.eventlogger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.UUID
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private const val TAG = "SimpleEventLogger"

/**
 * An implementation of [EventLogger] backed by a [Storage] implementation.
 *
 * This implementation also provides an observable flow ([eventFlow]) that emits
 * whenever the underlying event data is modified, making it easy for UI or other
 * observers to react to changes.
 *
 * Applications may use [onAddEvent] to determine if an event should be dropped
 * or if additional application-specific data should be amended to the event. For
 * example an application may have per-document setting on whether to log events
 * or it may add a GPS-location for proximity presentment events.
 *
 * @property storage The underlying persistent storage mechanism.
 * @property partitionId A logical partition identifier to group events. Defaults to "default".
 * @property expireAfter the amount of time to keep events, use [Duration.INFINITE] to never expire events.
 * @property clock The time source used to timestamp events. Defaults to [Clock.System].
 * @property onAddEvent Function to inject additional metadata or drop the event by returning `null`.
 * @property scope a [CoroutineScope] used by [addEventAsync], to add events asynchronously.
 */
class SimpleEventLogger(
    private val storage: Storage,
    private val partitionId: String = "default",
    private val expireAfter: Duration = 60.days,
    private val clock: Clock = Clock.System,
    private val onAddEvent: suspend (event: Event) -> Map<String, DataItem>? = { _ -> emptyMap() },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
): EventLogger {
    private lateinit var storageTable: StorageTable

    private val _eventFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * A [SharedFlow] that emits a [Unit] whenever an event is added, deleted,
     * or when all events are cleared. Observers can collect this flow to know
     * when to refresh their data.
     */
    val eventFlow: SharedFlow<Unit>
        get() = _eventFlow.asSharedFlow()

    private val initializationLock = Mutex()

    @Volatile
    private var initializationComplete = false

    /**
     * Ensures that the underlying storage table is initialized and ready for operations.
     *
     * This method uses a double-checked locking mechanism to prevent unnecessary
     * suspension once the initialization is complete. It is called automatically
     * before any read or write operations.
     */
    private suspend fun ensureInitialized() {
        if (initializationComplete) return

        initializationLock.withLock {
            if (initializationComplete) {
                return
            }
            storageTable = storage.getTable(tableSpec)
            initializationComplete = true
        }
    }

    /**
     * Adds a new event to the logger.
     *
     * This method generates a chronologically-sortable storage identifier and a current
     * timestamp for the event, runs application-provided [onAddEvent] to check if the event
     * should be dropped or if app-specific data should be amended, and then finally saves
     * the event.
     *
     * If successful, it triggers an emission to [eventFlow].
     *
     * Most code logging events will want to use [addEventAsync] since it runs in a separate
     * coroutine and thus not slow down a time-sensitive where an external component is waiting
     * for data to be returned.
     *
     * @param event The [Event] to be recorded.
     * @return A copy of the [Event] with assigned id, timestamp, and appData. Returns `null` if the event was dropped.
     */
    override suspend fun addEvent(event: Event): Event? {
        ensureInitialized()
        val now = clock.now()

        // The ISO-8601 string representation of Instant sorts lexically in chronological order.
        // Appending a UUID ensures uniqueness for events occurring at the exact same millisecond.
        val key = "$now-${UUID.randomUUID()}"

        val eventWithTimestamp = event.copy(
            identifier = key,
            timestamp = now,
            appData = emptyMap()
        )

        val appData = onAddEvent(eventWithTimestamp)
        if (appData == null) {
            return null
        }

        val eventWithAppData = event.copy(
            identifier = key,
            timestamp = now,
            appData = appData
        )

        storageTable.insert(
            key = key,
            data = ByteString(Cbor.encode(eventWithAppData.toDataItem())),
            partitionId = partitionId,
            expiration = now + expireAfter
        )

        _eventFlow.tryEmit(Unit)

        return eventWithAppData
    }

    /**
     * Asynchronously adds an event to the event log.
     *
     * This calls [addEvent] in the [CoroutineScope] passed to [SimpleEventLogger] at construction time.
     *
     * This is useful for code emitting events which do not want to wait until the event has been processed or
     * written to persistent storage.
     *
     * @param event The [Event] to be recorded.
     */
    override fun addEventAsync(event: Event) {
        scope.launch {
            addEvent(event)
        }
    }

    /**
     * Deletes a specific event from the logger.
     *
     * If the event is successfully deleted, it triggers an emission to [eventFlow].
     *
     * @param event The [Event] to delete. Must contain a valid identifier.
     * @return `true` if the event was found and deleted, `false` otherwise.
     */
    suspend fun deleteEvent(event: Event): Boolean {
        ensureInitialized()
        return storageTable.delete(
            key = event.identifier,
            partitionId = partitionId,
        ).also { deleted ->
            if (deleted) {
                _eventFlow.tryEmit(Unit)
            }
        }
    }

    /**
     * Deletes all events within the current partition.
     *
     * Triggers an emission to [eventFlow] upon completion.
     */
    suspend fun deleteAllEvents() {
        ensureInitialized()
        storageTable.deletePartition(partitionId = partitionId)
        _eventFlow.tryEmit(Unit)
    }

    /**
     * Retrieves all events stored in the current partition.
     *
     * The returned list is sorted chronologically by the event's timestamp.
     *
     * To enumerate a large set of events completely in manageable chunks, specify the
     * desired [limit] to repeated [getEvents] calls and pass the last event ID from from
     * the previously returned list as [afterEventId].
     *
     * @param limit return at most this many events.
     * @param afterEventId only return events after this event.
     * @return A chronological list of [Event]s.
     */
    suspend fun getEvents(
        limit: Int = Int.MAX_VALUE,
        afterEventId: String? = null
    ): List<Event> {
        ensureInitialized()

        return storageTable.enumerateWithData(
            partitionId = partitionId,
            afterKey = afterEventId,
            limit = limit
        )
            .map { (_, encodedData) ->
                Event.fromDataItem(Cbor.decode(encodedData.toByteArray()))
            }
    }

    companion object {
        /**
         * The schema specification for the storage table used by this logger.
         */
        private val tableSpec = StorageTableSpec(
            name = "SimpleEventLogger",
            supportPartitions = true,
            supportExpiration = true,
            schemaVersion = 0L
        )
    }
}