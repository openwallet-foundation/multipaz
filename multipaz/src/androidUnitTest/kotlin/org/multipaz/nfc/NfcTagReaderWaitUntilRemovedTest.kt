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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [NfcTagReaderAndroidImpl] (built-in Android NFC reader mode)
 * [NfcTagReader.waitUntilRemoved] and post-scan teardown lifecycle.
 *
 * The cross-platform [NfcTagReaderUsb] and [CcidDriver] tests live in `commonTest`
 * ([NfcTagReaderUsbTest]).
 */
class NfcTagReaderWaitUntilRemovedTest {

    @BeforeTest
    fun setUp() {
        // scanNfcWithPromptDialog() accesses applicationContext for haptic feedback when no dialog UI is bound.
        initializeApplication(Application())
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
