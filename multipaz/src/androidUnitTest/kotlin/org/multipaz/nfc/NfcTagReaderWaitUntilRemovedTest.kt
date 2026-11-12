package org.multipaz.nfc

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.multipaz.context.initializeApplication
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [NfcTagReader.waitUntilRemoved] and post-scan teardown lifecycle across
 * [NfcTagReaderUsb] (external USB CCID readers) and [NfcTagReaderAndroidImpl] (built-in Android
 * NFC reader mode).
 *
 * ### Lifecycle Invariants Verified
 * 1. **Grace window vs. active [NfcTagReader.waitUntilRemoved]:** When [NfcTagReader.scan] finishes
 *    while a tag/card is still in the RF field, both implementations keep the underlying USB driver
 *    connection or `NfcAdapter` reader mode alive for a short grace window (`disconnectDelay` /
 *    `disableReaderModeDelay`, 5 seconds in production) so a subsequent [NfcTagReader.waitUntilRemoved]
 *    call can observe presence. Once [NfcTagReader.waitUntilRemoved] is entered, that background
 *    timer **must** be cancelled so a tag held in the field longer than the grace window does not
 *    trigger a premature disconnect/reader-mode exit (which would falsely appear as tag removal).
 * 2. **Immediate teardown on removal or cancellation:** As soon as [NfcTagReader.waitUntilRemoved]
 *    observes removal—or if [NfcTagReader.scan] is cancelled—the USB driver is disconnected / reader
 *    mode is disabled immediately rather than waiting out the fallback delay. Likewise, on USB CCID
 *    readers, if the caller never invokes [NfcTagReader.waitUntilRemoved] and an interrupt-driven
 *    `onCardRemoved()` arrives during the grace window, the driver disconnects immediately.
 * 3. **Post-scan bounce isolation:** If a tag briefly leaves and re-enters the RF field during the
 *    post-scan grace window or while [NfcTagReader.waitUntilRemoved] is suspended, `tagInteractionFunc`
 *    from the completed [NfcTagReader.scan] call must never be re-invoked.
 * 4. **Deferred tag closing:** When `tagInteractionFunc` returns an unclosed [NfcIsoTag] (e.g., for
 *    mdoc NFC data transfer), the USB driver remains connected until [NfcIsoTag.close] is called and
 *    still supports [NfcTagReader.waitUntilRemoved] afterward.
 * 5. **Back-to-back scan serialization:** Starting a new [NfcTagReader.scan] within the previous
 *    scan's grace window must cleanly cancel the previous teardown timer, disconnect any previous
 *    USB driver *before* opening the next one, and never disable the active second scan when the
 *    first scan's timer would have expired.
 */
class NfcTagReaderWaitUntilRemovedTest {

    @BeforeTest
    fun setUp() {
        // scanNfcWithPromptDialog() accesses applicationContext for haptic feedback when no dialog UI is bound.
        initializeApplication(Application())
    }

    /**
     * In-memory fake of [CcidDriverOps] simulating both polled CCID `PC_to_RDR_GetSlotStatus` queries
     * ([getCardStatus]) and asynchronous USB interrupt `RDR_to_PC_NotifySlotChange` notifications
     * ([simulateCardInserted] / [simulateCardRemoved]).
     *
     * Appends `"connect#<id>"` and `"disconnect#<id>"` entries to [events] so tests can verify exact
     * ordering across consecutive driver instances.
     */
    private class FakeCcidDriver(
        val id: Int,
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        initialStatus: CardStatus = CardStatus.PRESENT_ACTIVE,
    ) : CcidDriverOps {
        override val maxCommandLength: Int = 65535

        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        var status: CardStatus = initialStatus

        @Volatile
        var currentListener: CcidDriverListener? = null
            private set

        override fun connect() {
            connected = true
            events.add("connect#$id")
        }

        override fun disconnect() {
            if (connected) {
                connected = false
                events.add("disconnect#$id")
            }
        }

        override fun setListener(listener: CcidDriverListener?) {
            currentListener = listener
        }

        override fun getCardStatus(): CardStatus {
            // Mirrors CcidDriver.getCardStatus(), which throws IOException once disconnected.
            if (!connected) {
                throw IOException("Driver is not connected.")
            }
            return status
        }

        override suspend fun powerCycleCard(fieldOffDelay: Duration): Boolean {
            if (!connected) return false
            return status == CardStatus.PRESENT_ACTIVE || status == CardStatus.PRESENT_INACTIVE
        }

        override fun transceive(commandApdu: ByteArray): ByteArray {
            if (!connected) {
                throw IOException("Driver is not connected.")
            }
            return byteArrayOf(0x90.toByte(), 0x00)
        }

        /** Simulates a CCID interrupt slot-change packet reporting card insertion. */
        fun simulateCardInserted() {
            status = CardStatus.PRESENT_ACTIVE
            currentListener?.onCardInserted()
        }

        /** Simulates a CCID interrupt slot-change packet reporting card removal. */
        fun simulateCardRemoved() {
            status = CardStatus.ABSENT
            currentListener?.onCardRemoved()
        }
    }

    /**
     * Calling [NfcTagReaderUsb.waitUntilRemoved] before any [NfcTagReaderUsb.scan] has been run
     * should be a fast no-op rather than hanging or connecting to the USB device.
     */
    @Test
    fun usbWaitUntilRemovedBeforeAnyScanReturnsImmediately() = runBlocking {
        val reader = NfcTagReaderUsb(
            device = null,
            interfaceIndex = 0,
            createDriver = { FakeCcidDriver(1) }
        )
        reader.waitUntilRemoved()
    }

    /**
     * Verifies that [NfcTagReaderUsb.waitUntilRemoved] cancels `delayedDisconnectJob` upon entry so
     * keeping a card on the reader longer than `disconnectDelay` does not disconnect the driver
     * mid-wait (which would cause `getCardStatus()` to throw `IOException` and falsely report the
     * card as `ABSENT`), and that transitioning [FakeCcidDriver.status] to [CardStatus.ABSENT] via
     * polling unblocks `waitUntilRemoved()` and disconnects the driver.
     */
    @Test
    fun usbWaitUntilRemovedSuspendsPastDisconnectDelayUntilCardRemoved() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 50.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            val scanResult = reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "ok"
                }
            )
            assertEquals("ok", scanResult)
            // Card is still present, so driver remains connected during the grace window.
            assertTrue(driver.connected)

            val waitDeferred = async(Dispatchers.Default) {
                reader.waitUntilRemoved()
            }

            // Hold card past >3x disconnectDelay (160ms vs 50ms); waitUntilRemoved() must remain
            // suspended and the driver must stay connected.
            delay(160.milliseconds)
            assertFalse(waitDeferred.isCompleted)
            assertTrue(driver.connected)

            // Simulate card leaving the RF field without an interrupt (polled GetSlotStatus path).
            driver.status = CardStatus.ABSENT
            waitDeferred.await()
            assertFalse(driver.connected)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that an asynchronous USB interrupt removal event ([CcidDriverListener.onCardRemoved])
     * unblocks an active [NfcTagReaderUsb.waitUntilRemoved] call and disconnects the driver
     * immediately without waiting for the 5-second fallback `disconnectDelay`.
     */
    @Test
    fun usbWaitUntilRemovedCompletesOnInterruptCardRemoved() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 5.seconds,
                pollInterval = 20.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "done"
                }
            )

            val waitDeferred = async(Dispatchers.Default) {
                reader.waitUntilRemoved()
            }
            delay(40.milliseconds)
            assertFalse(waitDeferred.isCompleted)

            // Deliver interrupt-driven card removal; waitUntilRemoved() completes and disconnects.
            driver.simulateCardRemoved()
            waitDeferred.await()
            assertFalse(driver.connected)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that if the caller does *not* call [NfcTagReaderUsb.waitUntilRemoved], removing the
     * card during the post-scan grace window triggers the post-scan listener to disconnect the USB
     * driver immediately (turning off RF polling right away instead of keeping the reader hot for
     * the full `disconnectDelay`), and a later `waitUntilRemoved()` call returns immediately.
     */
    @Test
    fun usbPostScanCardRemovalWithoutWaitUntilRemovedDisconnectsImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 10.seconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "done"
                }
            )
            assertTrue(driver.connected)

            // Without calling waitUntilRemoved(), removing the card disconnects immediately.
            driver.simulateCardRemoved()
            assertFalse(driver.connected)

            // A subsequent waitUntilRemoved() sees no active session and returns immediately.
            reader.waitUntilRemoved()
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that once [NfcTagReaderUsb.scan] completes, its scan listener is replaced by a
     * removal-only post-scan listener so a card re-insertion / RF bounce while waiting for removal
     * never re-executes `tagInteractionFunc`.
     */
    @Test
    fun usbPostScanCardBounceDoesNotReinvokeTagInteractionFunc() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val invocations = AtomicInteger(0)
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 200.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    invocations.incrementAndGet()
                    tag.close()
                    "done"
                }
            )

            val waitDeferred = async(Dispatchers.Default) {
                reader.waitUntilRemoved()
            }
            delay(25.milliseconds)

            // Simulate a card insertion interrupt post-scan; tagInteractionFunc must not run again.
            driver.simulateCardInserted()
            delay(25.milliseconds)
            assertEquals(1, invocations.get())

            driver.simulateCardRemoved()
            waitDeferred.await()
            assertEquals(1, invocations.get())
            assertFalse(driver.connected)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies the deferred-close flow (used when `scan()` returns an open [NfcIsoTag] for mdoc NFC
     * data transfer): the USB driver remains connected after `scan()` returns, stays connected when
     * `deferredTag.close()` is called while the card is still in the field so `waitUntilRemoved()`
     * can still observe removal, and disconnects once the card is removed.
     */
    @Test
    fun usbOpenTagStaysConnectedUntilClosedThenWaitsForRemoval() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 60.milliseconds,
                openTagDisconnectDelay = 5.seconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            // Return tag without calling tag.close() inside tagInteractionFunc.
            val deferredTag = reader.scan(
                message = null,
                tagInteractionFunc = { tag -> tag }
            )
            assertTrue(driver.connected)

            // Close tag after scan() returned while card is still present; driver remains connected
            // for the grace window so waitUntilRemoved() can be invoked next.
            deferredTag.close()
            assertTrue(driver.connected)

            val waitDeferred = async(Dispatchers.Default) {
                reader.waitUntilRemoved()
            }
            // Hold past disconnectDelay (120ms vs 60ms) to ensure the shortened timer issued by
            // onClose() was also cancelled when waitUntilRemoved() started.
            delay(120.milliseconds)
            assertFalse(waitDeferred.isCompleted)
            assertTrue(driver.connected)

            driver.simulateCardRemoved()
            waitDeferred.await()
            assertFalse(driver.connected)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that when `tagInteractionFunc` throws an application error (e.g., an unsupported or
     * malformed card in a kiosk loop), the caller can catch the exception and still call
     * [NfcTagReaderUsb.waitUntilRemoved] to wait for the user to remove the offending card before
     * starting the next scan.
     */
    @Test
    fun usbScanExceptionStillAllowsWaitUntilRemoved() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 60.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            assertFailsWith<IllegalStateException> {
                reader.scan<String>(
                    message = null,
                    tagInteractionFunc = {
                        throw IllegalStateException("Unsupported tag")
                    }
                )
            }
            assertTrue(driver.connected)

            val waitDeferred = async(Dispatchers.Default) {
                reader.waitUntilRemoved()
            }
            delay(120.milliseconds)
            assertFalse(waitDeferred.isCompleted)

            driver.simulateCardRemoved()
            waitDeferred.await()
            assertFalse(driver.connected)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that starting a second [NfcTagReaderUsb.scan] while the first scan's grace-window
     * `delayedDisconnectJob` is still pending:
     * 1. Synchronously disconnects `driver#1` *before* connecting `driver#2` (preventing USB
     *    interface claim conflicts).
     * 2. Cancels `driver#1`'s delayed disconnect so it never fires mid-way through `scan#2`.
     */
    @Test
    fun usbBackToBackScansOrderingAndNoInterference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val nextId = AtomicInteger(1)
            val drivers = Collections.synchronizedList(mutableListOf<FakeCcidDriver>())
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 50.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = {
                    FakeCcidDriver(nextId.getAndIncrement(), events).also { drivers.add(it) }
                }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "scan1"
                }
            )

            // Start scan #2 15ms later (while scan #1's 50ms delayedDisconnectJob is still active).
            delay(15.milliseconds)
            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    // Remain inside scan #2 past scan #1's original 50ms deadline.
                    delay(80.milliseconds)
                    assertTrue(drivers[1].connected)
                    tag.close()
                    "scan2"
                }
            )

            val waitJob = launch(Dispatchers.Default) {
                reader.waitUntilRemoved()
            }
            delay(25.milliseconds)
            drivers[1].simulateCardRemoved()
            waitJob.join()

            assertEquals(
                listOf("connect#1", "disconnect#1", "connect#2", "disconnect#2"),
                events.toList()
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that cancelling an in-progress [NfcTagReaderUsb.scan] disconnects the USB driver
     * immediately without scheduling a grace-window timer, and that a subsequent
     * [NfcTagReaderUsb.waitUntilRemoved] returns immediately.
     */
    @Test
    fun usbCancelledScanDisconnectsImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.ABSENT)
            val reader = NfcTagReaderUsb(
                device = null,
                interfaceIndex = 0,
                disconnectDelay = 5.seconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            val scanJob = async(Dispatchers.Default) {
                reader.scan(
                    message = null,
                    tagInteractionFunc = { "never" }
                )
            }
            delay(30.milliseconds)
            assertTrue(driver.connected)

            scanJob.cancel()
            assertFailsWith<CancellationException> {
                scanJob.await()
            }
            assertFalse(driver.connected)

            // waitUntilRemoved() must return immediately after a cancelled scan.
            reader.waitUntilRemoved()
        } finally {
            scope.cancel()
        }
    }

    /**
     * Calling [NfcTagReaderAndroidImpl.waitUntilRemoved] before any scan has connected to an
     * `IsoDep` tag should return immediately.
     */
    @Test
    fun androidWaitUntilRemovedBeforeAnyScanReturnsImmediately() = runBlocking {
        val reader = NfcTagReaderAndroidImpl()
        reader.waitUntilRemoved()
    }

    /**
     * Verifies that [NfcTagReaderAndroidImpl.waitUntilRemoved] cancels `disableReaderModeJob` on
     * entry so Android reader mode stays active for as long as the `IsoDep` tag remains connected
     * (even well past `disableReaderModeDelay`), and disables reader mode once—immediately—as soon
     * as the tag disconnects.
     */
    @Test
    fun androidWaitUntilRemovedSuspendsPastDisableDelayUntilTagDisconnected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tagConnected = AtomicBoolean(true)
            val disableCount = AtomicInteger(0)
            val reader = NfcTagReaderAndroidImpl(
                disableReaderModeDelay = 50.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
            )

            val result = reader.runScanSession(
                message = null,
                enableReaderMode = { _, _, continuation ->
                    reader.onTagConnected { tagConnected.get() }
                    continuation.resume("scanned")
                },
                disableReaderMode = {
                    disableCount.incrementAndGet()
                }
            )
            assertEquals("scanned", result)
            assertEquals(0, disableCount.get())

            val waitDeferred = async(Dispatchers.Default) {
                reader.waitUntilRemoved()
            }

            // Hold tag connected for >3x disableReaderModeDelay (160ms vs 50ms); reader mode must
            // remain enabled so Android does not drop IsoDep or re-dispatch the tag to the OS.
            delay(160.milliseconds)
            assertFalse(waitDeferred.isCompleted)
            assertEquals(0, disableCount.get())

            // Simulate tag leaving the RF field (IsoDep.isConnected == false).
            tagConnected.set(false)
            waitDeferred.await()
            assertEquals(1, disableCount.get())

            // Calling waitUntilRemoved() a second time is a no-op and does not disable again.
            reader.waitUntilRemoved()
            assertEquals(1, disableCount.get())
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that cancelling an active Android NFC scan disables reader mode immediately instead
     * of keeping reader mode enabled for `disableReaderModeDelay`, and clears tag state so
     * [NfcTagReaderAndroidImpl.waitUntilRemoved] returns immediately.
     */
    @Test
    fun androidCancelledScanDisablesReaderModeImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disableCount = AtomicInteger(0)
            val reader = NfcTagReaderAndroidImpl(
                disableReaderModeDelay = 5.seconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
            )

            val scanDeferred = async(Dispatchers.Default) {
                reader.runScanSession<String>(
                    message = null,
                    enableReaderMode = { _, _, _ ->
                        // Stay suspended until cancelled.
                    },
                    disableReaderMode = {
                        disableCount.incrementAndGet()
                    }
                )
            }
            delay(30.milliseconds)
            scanDeferred.cancel()
            assertFailsWith<CancellationException> {
                scanDeferred.await()
            }
            assertEquals(1, disableCount.get())

            reader.waitUntilRemoved()
            assertEquals(1, disableCount.get())
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies that starting a second Android scan before the first scan's `disableReaderModeDelay`
     * expires cancels the first scan's pending disable job so it cannot disable reader mode while
     * the second scan is actively scanning or waiting for tag removal.
     */
    @Test
    fun androidBackToBackScansDoNotDisableActiveSecondScan() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disableScan1Count = AtomicInteger(0)
            val disableScan2Count = AtomicInteger(0)
            val tag2Connected = AtomicBoolean(true)
            val reader = NfcTagReaderAndroidImpl(
                disableReaderModeDelay = 50.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
            )

            reader.runScanSession(
                message = null,
                enableReaderMode = { _, _, continuation ->
                    reader.onTagConnected { true }
                    continuation.resume("scan1")
                },
                disableReaderMode = {
                    disableScan1Count.incrementAndGet()
                }
            )

            // Begin scan #2 15ms later, before scan #1's 50ms disableReaderModeJob fires.
            delay(15.milliseconds)
            val scan2Deferred = async(Dispatchers.Default) {
                reader.runScanSession<String>(
                    message = null,
                    enableReaderMode = { _, _, continuation ->
                        scope.launch {
                            // Stay active past scan #1's original 50ms deadline; neither disable
                            // callback should have run.
                            delay(80.milliseconds)
                            assertEquals(0, disableScan1Count.get())
                            assertEquals(0, disableScan2Count.get())
                            reader.onTagConnected { tag2Connected.get() }
                            continuation.resume("scan2")
                        }
                    },
                    disableReaderMode = {
                        disableScan2Count.incrementAndGet()
                    }
                )
            }

            assertEquals("scan2", scan2Deferred.await())
            assertEquals(0, disableScan1Count.get())
            assertEquals(0, disableScan2Count.get())

            // Once scan #2's tag is removed, only scan #2's disable callback runs.
            tag2Connected.set(false)
            reader.waitUntilRemoved()
            assertEquals(0, disableScan1Count.get())
            assertEquals(1, disableScan2Count.get())
        } finally {
            scope.cancel()
        }
    }
}
