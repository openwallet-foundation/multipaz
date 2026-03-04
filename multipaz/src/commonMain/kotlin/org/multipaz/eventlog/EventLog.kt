package org.multipaz.eventlog

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.UUID
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * A persistent logger for recording and retrieving events.
 *
 * `EventLog` backs its data with a [Storage] implementation, serializing events
 * into CBOR format. It also provides an observable flow ([eventFlow]) that emits
 * whenever the underlying event data is modified, making it easy for UI or other
 * observers to react to changes.
 *
 * @property storage The underlying persistent storage mechanism.
 * @property partitionId A logical partition identifier to group events. Defaults to "default".
 * @property expireAfter the amount of time to keep events, use [Duration.INFINITE] to never expire events.
 * @property clock The time source used to timestamp events. Defaults to [Clock.System].
 */
class EventLog(
    private val storage: Storage,
    private val partitionId: String = "default",
    private val expireAfter: Duration = 60.days,
    private val clock: Clock = Clock.System
) {
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
     * timestamp for the event before saving it. If successful, it triggers an emission
     * to [eventFlow].
     *
     * @param event The [Event] to be recorded.
     * @return A copy of the [Event] containing its assigned identifier and timestamp.
     */
    suspend fun addEvent(event: Event): Event {
        ensureInitialized()
        val now = clock.now()

        // The ISO-8601 string representation of Instant sorts lexically in chronological order.
        // Appending a UUID ensures uniqueness for events occurring at the exact same millisecond.
        val key = "$now-${UUID.randomUUID()}"

        val modifiedEvent = event.copy(
            eventIdentifier = key,
            timestamp = now
        )

        storageTable.insert(
            key = key,
            data = ByteString(Cbor.encode(modifiedEvent.toDataItem())),
            partitionId = partitionId,
            expiration = now + expireAfter
        )

        _eventFlow.tryEmit(Unit)

        return modifiedEvent
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
            name = "EventLog",
            supportPartitions = true,
            supportExpiration = true,
            schemaVersion = 0L
        )
    }
}