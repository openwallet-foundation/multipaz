package org.multipaz.eventlogger

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Simple
import org.multipaz.cbor.toDataItem
import org.multipaz.claim.MdocClaim
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleEventLoggerTests {

    @Test
    fun testAddEventEmitsToFlowAndAssignsId() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val logger = SimpleEventLogger(storage = ephemeralStorage, partitionId = "test-partition", clock = fakeClock)

        val emissions = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            logger.eventFlow.toList(emissions)
        }

        val event = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = mapOf(
                            MdocRequestedClaim(
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                intentToRetain = false,
                            ) to MdocClaim(
                                displayName = "Age over 18?",
                                attribute = null,
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                value = true.toDataItem()
                            )
                        )
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        val savedEvent = logger.addEvent(event)

        // Verify the event was modified with a new ID and timestamp
        assertNotNull(savedEvent)
        assertTrue(savedEvent.identifier.isNotEmpty())
        assertEquals(Instant.fromEpochMilliseconds(1000), savedEvent.timestamp)

        // Verify flow emission
        assertEquals(1, emissions.size)

        // Verify storage state
        val eventsInDb = logger.getEvents()
        assertEquals(1, eventsInDb.size)
        assertEquals(savedEvent.identifier, eventsInDb.first().identifier)

        job.cancel()
    }

    @Test
    fun testDeleteEventEmitsToFlowAndRemovesData() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val logger = SimpleEventLogger(storage = ephemeralStorage, partitionId = "test-partition", clock = fakeClock)

        val event = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = mapOf(
                            MdocRequestedClaim(
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                intentToRetain = false,
                            ) to MdocClaim(
                                displayName = "Age over 18?",
                                attribute = null,
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                value = true.toDataItem()
                            )
                        )
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        val savedEvent = logger.addEvent(event)
        assertNotNull(savedEvent)

        val emissions = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            logger.eventFlow.toList(emissions)
        }

        val isDeleted = logger.deleteEvent(savedEvent)

        assertTrue(isDeleted)
        assertEquals(1, emissions.size)
        assertTrue(logger.getEvents().isEmpty())

        job.cancel()
    }

    @Test
    fun testDeleteNonExistentEventDoesNotEmit() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val logger = SimpleEventLogger(storage = ephemeralStorage, partitionId = "test-partition", clock = fakeClock)

        val emissions = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            logger.eventFlow.toList(emissions)
        }

        val dummyEvent = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = mapOf(
                            MdocRequestedClaim(
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                intentToRetain = false,
                            ) to MdocClaim(
                                displayName = "Age over 18?",
                                attribute = null,
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                value = true.toDataItem()
                            )
                        )
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        val isDeleted = logger.deleteEvent(dummyEvent)

        assertFalse(isDeleted)
        assertEquals(0, emissions.size)

        job.cancel()
    }

    @Test
    fun testDeleteAllEventsClearsPartitionAndEmits() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val logger = SimpleEventLogger(storage = ephemeralStorage, partitionId = "test-partition", clock = fakeClock)

        val event = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = mapOf(
                            MdocRequestedClaim(
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                intentToRetain = false,
                            ) to MdocClaim(
                                displayName = "Age over 18?",
                                attribute = null,
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                value = true.toDataItem()
                            )
                        )
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )
        logger.addEvent(event)
        logger.addEvent(event)

        val emissions = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            logger.eventFlow.toList(emissions)
        }

        logger.deleteAllEvents()

        assertEquals(1, emissions.size)
        assertTrue(logger.getEvents().isEmpty())

        job.cancel()
    }

    @Test
    fun testGetEventsReturnsChronologicallySortedList() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(3000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val logger = SimpleEventLogger(storage = ephemeralStorage, partitionId = "test-partition", clock = fakeClock)

        val eventBase = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = mapOf(
                            MdocRequestedClaim(
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                intentToRetain = false,
                            ) to MdocClaim(
                                displayName = "Age over 18?",
                                attribute = null,
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName = "age_over_18",
                                value = true.toDataItem()
                            )
                        )
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        // Add out of order chronologically by manipulating the clock
        fakeClock.currentTime = Instant.fromEpochMilliseconds(3000)
        val event3 = logger.addEvent(eventBase)

        fakeClock.currentTime = Instant.fromEpochMilliseconds(1000)
        val event1 = logger.addEvent(eventBase)

        fakeClock.currentTime = Instant.fromEpochMilliseconds(2000)
        val event2 = logger.addEvent(eventBase)

        val sortedEvents = logger.getEvents()

        assertEquals(3, sortedEvents.size)

        // Assert that the items are sorted chronologically. Because our storage engine keys
        // are now prefixed with the ISO-8601 timestamp, the database native retrieval matches
        // our expected chronological order perfectly.
        assertEquals(event1?.identifier, sortedEvents[0].identifier)
        assertEquals(event2?.identifier, sortedEvents[1].identifier)
        assertEquals(event3?.identifier, sortedEvents[2].identifier)
    }

    @Test
    fun testGetEventsPagination() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val logger = SimpleEventLogger(storage = ephemeralStorage, partitionId = "test-partition", clock = fakeClock)

        val eventBase = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = emptyMap()
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        // Insert 5 events, advancing the clock so they are chronologically staggered.
        // Because the timestamp is the DB key prefix, native DB pagination will work.
        val insertedEvents = mutableListOf<Event>()
        for (i in 1..5) {
            fakeClock.currentTime = Instant.fromEpochMilliseconds(1000L * i)
            logger.addEvent(eventBase)?.let { insertedEvents.add(it) }
        }

        // Fetch page 1 (limit 2)
        val page1 = logger.getEvents(limit = 2)
        assertEquals(2, page1.size)
        assertEquals(insertedEvents[0].identifier, page1[0].identifier)
        assertEquals(insertedEvents[1].identifier, page1[1].identifier)

        // Fetch page 2 (limit 2, after event 2)
        val page2 = logger.getEvents(limit = 2, afterEventId = page1.last().identifier)
        assertEquals(2, page2.size)
        assertEquals(insertedEvents[2].identifier, page2[0].identifier)
        assertEquals(insertedEvents[3].identifier, page2[1].identifier)

        // Fetch page 3 (limit 2, after event 4) -> Should only return 1 event
        val page3 = logger.getEvents(limit = 2, afterEventId = page2.last().identifier)
        assertEquals(1, page3.size)
        assertEquals(insertedEvents[4].identifier, page3[0].identifier)
    }

    @Test
    fun testEventExpiration() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val logger = SimpleEventLogger(
            storage = ephemeralStorage,
            partitionId = "test-partition",
            expireAfter = 1.days,
            clock = fakeClock
        )

        val eventBase = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = emptyMap()
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        logger.addEvent(eventBase)

        // Ensure the event exists immediately after insertion
        assertEquals(1, logger.getEvents().size)

        // Fast forward time to just before the expiration (e.g., 23 hours later)
        fakeClock.currentTime = fakeClock.currentTime.plus(23.hours)
        assertEquals(1, logger.getEvents().size)

        // Fast forward time past the expiration limit (e.g., another 2 hours)
        fakeClock.currentTime = fakeClock.currentTime.plus(2.hours)

        // Because EphemeralStorage relies on the injected clock to evaluate TTL,
        // it should prune or filter out the expired event.
        assertTrue(logger.getEvents().isEmpty(), "Expected event to be expired and removed")
    }

    @Test
    fun testOnAddEventInjectsAppData() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)
        val expectedAppData = mapOf("test_key" to "test_value".toDataItem())

        val logger = SimpleEventLogger(
            storage = ephemeralStorage,
            partitionId = "test-partition",
            clock = fakeClock,
            onAddEvent = { _ -> expectedAppData }
        )

        val eventBase = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = emptyMap()
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        val savedEvent = logger.addEvent(eventBase)

        // Verify returned event contains the injected appData
        assertNotNull(savedEvent)
        assertEquals(expectedAppData, savedEvent.appData)

        // Verify storage state contains the injected appData
        val eventsInDb = logger.getEvents()
        assertEquals(1, eventsInDb.size)
        assertEquals(expectedAppData, eventsInDb.first().appData)
    }

    @Test
    fun testOnAddEventDropsEvent() = runTest {
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000))
        val ephemeralStorage = EphemeralStorage(fakeClock)

        val logger = SimpleEventLogger(
            storage = ephemeralStorage,
            partitionId = "test-partition",
            clock = fakeClock,
            onAddEvent = { _ -> null }
        )

        val emissions = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            logger.eventFlow.toList(emissions)
        }

        val eventBase = EventPresentmentIso18013Proximity(
            identifier = "",
            timestamp = Instant.DISTANT_PAST,
            presentmentData = EventPresentmentData(
                requesterName = "Test Requester",
                requesterCertChain = null,
                trustMetadata = null,
                requestedDocuments = listOf(
                    EventPresentmentDataDocument(
                        documentId = "test-document-id",
                        documentName = "Test Document",
                        claims = emptyMap()
                    )
                ),
            ),
            request = Simple.NULL,
            response = Simple.NULL,
            sessionTranscript = Simple.NULL
        )

        val savedEvent = logger.addEvent(eventBase)

        // Verify the event was dropped
        assertNull(savedEvent)

        // Verify no flow emission
        assertEquals(0, emissions.size)

        // Verify storage state is empty
        assertTrue(logger.getEvents().isEmpty())

        job.cancel()
    }

    // --- Fakes ---

    class FakeClock(var currentTime: Instant = Instant.fromEpochMilliseconds(0)) : Clock {
        override fun now(): Instant = currentTime
    }
}