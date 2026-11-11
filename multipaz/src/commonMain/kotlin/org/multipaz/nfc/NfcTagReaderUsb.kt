package org.multipaz.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.Logger
import org.multipaz.util.SynchronizedLock
import org.multipaz.util.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcTagReaderUsb"

private class NfcIsoTagUsb(
    private val driver: CcidDriverOps,
    private val updateMessage: (message: String) -> Unit,
    private val onClose: suspend () -> Unit,
) : NfcIsoTag() {
    @Volatile
    var isClosed: Boolean = false
        private set

    fun resetClosed() {
        isClosed = false
    }

    override val maxTransceiveLength: Int
        get() = driver.maxCommandLength

    override suspend fun transceive(command: CommandApdu): ResponseApdu {
        val commandApduBytes = command.encode()
        try {
            val responseApduBytes = withContext(nfcIoDispatcher) {
                driver.transceive(commandApduBytes)
            }
            return ResponseApdu.decode(responseApduBytes)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw NfcTagLostException("Tag was lost", e)
        }
    }

    override suspend fun close() {
        isClosed = true
        onClose()
    }

    override suspend fun updateDialogMessage(message: String) {
        updateMessage(message)
    }
}

/**
 * An [NfcTagReader] backed by an external USB CCID (Chip Card Interface Device) contactless
 * smart card / NFC reader.
 *
 * Instances can be obtained either via [ExternalNfcReaderUsb.getNfcTagReader] (managed through
 * [ExternalNfcReaderStore]) or directly on supported platforms via factory methods on
 * [NfcTagReaderUsb.Companion] (such as `NfcTagReaderUsb.create(...)` on JVM/Linux).
 *
 * When a caller creates an [NfcTagReaderUsb] directly, [close] should be invoked when the reader
 * is no longer needed to cancel background disconnect timers and release any claimed USB
 * interface immediately.
 */
class NfcTagReaderUsb internal constructor(
    internal val deviceIdentity: Any? = null,
    internal val interfaceIndex: Int?,
    private val disconnectDelay: Duration = 5.seconds,
    private val openTagDisconnectDelay: Duration = 30.seconds,
    private val pollInterval: Duration = 100.milliseconds,
    backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + nfcIoDispatcher),
    private val createDriver: (pollingFrameData: ByteArray?) -> CcidDriverOps,
) : NfcTagReader {

    /**
     * Companion object hosting platform-specific factory extensions for [NfcTagReaderUsb].
     */
    companion object {}

    private class DriverSession(
        val driver: CcidDriverOps,
        @Volatile var isCardPresent: Boolean = false,
        @Volatile var keepConnectedUntilTagClosed: Boolean = false,
        @Volatile var activeTag: NfcIsoTagUsb? = null,
        @Volatile var scanCompleted: Boolean = false,
        var waitingForRemovalCount: Int = 0,
        var delayedDisconnectJob: Job? = null,
    ) {
        val stillNeedsTag: Boolean
            get() = keepConnectedUntilTagClosed && activeTag?.isClosed != true
    }

    private val readerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
    private val readerScope = CoroutineScope(backgroundScope.coroutineContext + readerJob)

    private val driverLock = SynchronizedLock()
    private val usbLifecycleLock = SynchronizedLock()
    private var activeSession: DriverSession? = null
    private var connectedDriver: CcidDriverOps? = null

    @Volatile
    private var closed: Boolean = false

    /**
     * Always `true` for USB CCID NFC readers.
     */
    override val external: Boolean
        get() = true

    /**
     * Always `false` for USB CCID NFC readers (dialog presentation is optional).
     */
    override val dialogAlwaysShown: Boolean
        get() = false

    private fun connectDriver(driver: CcidDriverOps) {
        usbLifecycleLock.withLock {
            connectedDriver?.let { previous ->
                previous.setListener(null)
                previous.disconnect()
                connectedDriver = null
            }
            driver.connect()
            connectedDriver = driver
        }
    }

    private fun disconnectDriver(driver: CcidDriverOps) {
        usbLifecycleLock.withLock {
            if (connectedDriver === driver) {
                connectedDriver = null
                driver.setListener(null)
                driver.disconnect()
            }
        }
    }

    /**
     * Closes this [NfcTagReaderUsb], cancelling any delayed USB disconnect timer and releasing
     * any currently claimed USB CCID interface.
     *
     * Once closed, subsequent calls to [scan] throw [IllegalStateException].
     */
    suspend fun close() {
        closed = true
        val session = driverLock.withLock {
            val current = activeSession
            current?.delayedDisconnectJob?.cancel()
            current?.delayedDisconnectJob = null
            activeSession = null
            current
        }
        session?.isCardPresent = false
        readerJob.cancel()
        withContext(NonCancellable + nfcIoDispatcher) {
            if (session != null) {
                disconnectDriver(session.driver)
            } else {
                usbLifecycleLock.withLock {
                    connectedDriver?.let { previous ->
                        connectedDriver = null
                        previous.setListener(null)
                        previous.disconnect()
                    }
                }
            }
        }
    }

    private fun scheduleDelayedDisconnectLocked(session: DriverSession, timeout: Duration) {
        session.delayedDisconnectJob?.cancel()
        lateinit var job: Job
        job = readerScope.launch {
            delay(timeout)
            val shouldDisconnect = driverLock.withLock {
                if (session.delayedDisconnectJob === job) {
                    session.delayedDisconnectJob = null
                    if (session.waitingForRemovalCount == 0 && activeSession === session) {
                        activeSession = null
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            if (shouldDisconnect) {
                withContext(nfcIoDispatcher) {
                    disconnectDriver(session.driver)
                }
            }
        }
        session.delayedDisconnectJob = job
    }

    /**
     * Suspends until the tag from the most recent [scan] call is removed from the USB reader's
     * RF field.
     */
    override suspend fun waitUntilRemoved() {
        val session = driverLock.withLock {
            val current = activeSession ?: return
            current.waitingForRemovalCount++
            current.delayedDisconnectJob?.cancel()
            current.delayedDisconnectJob = null
            current
        }
        try {
            while (session.isCardPresent) {
                val status = withContext(nfcIoDispatcher) {
                    try {
                        session.driver.getCardStatus()
                    } catch (e: IOException) {
                        Logger.d(TAG, "Error getting card status while waiting for removal: ${e.message}")
                        CardStatus.ABSENT
                    }
                }
                if (status == CardStatus.ABSENT) {
                    session.isCardPresent = false
                    break
                }
                delay(pollInterval)
            }
        } finally {
            val shouldDisconnect = driverLock.withLock {
                session.waitingForRemovalCount--
                if (session.waitingForRemovalCount == 0 &&
                    !session.stillNeedsTag &&
                    activeSession === session
                ) {
                    session.delayedDisconnectJob?.cancel()
                    session.delayedDisconnectJob = null
                    activeSession = null
                    true
                } else {
                    false
                }
            }
            if (shouldDisconnect) {
                withContext(NonCancellable + nfcIoDispatcher) {
                    disconnectDriver(session.driver)
                }
            }
        }
    }

    /**
     * Scans for an NFC tag using the USB CCID reader and executes [tagInteractionFunc].
     *
     * @param message optional message to display in the scan prompt dialog, or `null` for headless scanning.
     * @param tagInteractionFunc callback invoked when an ISO-DEP tag enters the reader's RF field.
     * @param options options for NFC scanning (such as custom polling frames).
     * @param context the [CoroutineContext] used to execute [tagInteractionFunc].
     * @return the non-null result returned by [tagInteractionFunc].
     * @throws IllegalStateException if [close] has already been called on this reader.
     * @throws PromptDismissedException if the user dismisses the scan prompt dialog.
     * @throws NfcTagLostException if the tag is lost and not caught by [tagInteractionFunc].
     * @throws IOException if opening the USB device or communicating over CCID fails.
     */
    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        check(!closed) { "NfcTagReaderUsb has been closed" }
        val previousSession = driverLock.withLock {
            val prev = activeSession
            prev?.delayedDisconnectJob?.cancel()
            prev?.delayedDisconnectJob = null
            activeSession = null
            prev
        }
        previousSession?.isCardPresent = false

        val driver = createDriver(options.pollingFrameData?.toByteArray())
        withContext(nfcIoDispatcher) {
            connectDriver(driver)
        }
        val session = DriverSession(driver = driver)
        driverLock.withLock {
            activeSession = session
        }

        var scanCancelled = false
        try {
            return scanNfcWithPromptDialog(message) { updateMessage, resetMessage ->
                val initialStatus = withContext(nfcIoDispatcher) {
                    try {
                        driver.getCardStatus()
                    } catch (e: IOException) {
                        Logger.w(TAG, "Failed to query initial card status", e)
                        CardStatus.UNKNOWN
                    }
                }
                val interactionJob = SupervisorJob()
                val interactionScope = CoroutineScope(context + interactionJob)
                try {
                    suspendCancellableCoroutine { continuation ->
                        val stateLock = SynchronizedLock()
                        var readJob: Job? = null
                    session.isCardPresent = false

                    val listener = object : CcidDriverListener {
                        override fun onCardInserted() {
                            Logger.i(TAG, "Card inserted")
                            stateLock.withLock {
                                session.isCardPresent = true
                                if (!continuation.isActive || readJob != null) return
                                readJob = interactionScope.launch {
                                    val tag = NfcIsoTagUsb(
                                        driver = driver,
                                        updateMessage = updateMessage,
                                        onClose = {
                                            if (session.scanCompleted) {
                                                val shouldDisconnect = driverLock.withLock {
                                                    if (activeSession !== session) {
                                                        false
                                                    } else if (session.waitingForRemovalCount == 0 &&
                                                        !session.isCardPresent
                                                    ) {
                                                        session.delayedDisconnectJob?.cancel()
                                                        session.delayedDisconnectJob = null
                                                        activeSession = null
                                                        true
                                                    } else {
                                                        if (session.waitingForRemovalCount == 0) {
                                                            scheduleDelayedDisconnectLocked(
                                                                session,
                                                                disconnectDelay
                                                            )
                                                        }
                                                        false
                                                    }
                                                }
                                                if (shouldDisconnect) {
                                                    withContext(nfcIoDispatcher) {
                                                        disconnectDriver(driver)
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    session.activeTag = tag
                                    try {
                                        while (continuation.isActive && session.isCardPresent) {
                                            tag.resetClosed()
                                            val funcResult = tagInteractionFunc(tag)
                                            if (funcResult != null) {
                                                session.keepConnectedUntilTagClosed = !tag.isClosed
                                                if (continuation.isActive) {
                                                    continuation.resume(funcResult)
                                                }
                                                break
                                            }
                                            resetMessage()
                                            // Keep the RF field on briefly while Android's PollingFrameHandler
                                            // finishes disabling Observe Mode, then power-cycle the slot so a fresh
                                            // RATS/ATTRIB triggers ISO-DEP activation on the phone.
                                            delay(200.milliseconds)
                                            if (!continuation.isActive || !session.isCardPresent) break
                                            if (!driver.powerCycleCard()) {
                                                delay(150.milliseconds)
                                            }
                                        }
                                    } catch (e: NfcTagLostException) {
                                        // This is to properly handle emulated tags - such as on Android - which may
                                        // be showing disambiguation UI if multiple applications have registered for
                                        // the same AID.
                                        resetMessage()
                                        Logger.w(TAG, "Tag lost", e)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(e)
                                        }
                                    } finally {
                                        stateLock.withLock {
                                            readJob = null
                                        }
                                    }
                                }
                            }
                        }

                        override fun onCardRemoved() {
                            Logger.i(TAG, "Card removed")
                            stateLock.withLock {
                                session.isCardPresent = false
                            }
                        }
                    }

                    driver.setListener(listener = listener)
                    if (initialStatus == CardStatus.PRESENT_ACTIVE ||
                        initialStatus == CardStatus.PRESENT_INACTIVE
                    ) {
                        listener.onCardInserted()
                    }

                    continuation.invokeOnCancellation {
                        interactionJob.cancel()
                        readJob?.cancel()
                        session.isCardPresent = false
                        driverLock.withLock {
                            session.delayedDisconnectJob?.cancel()
                            session.delayedDisconnectJob = null
                            if (activeSession === session) {
                                activeSession = null
                            }
                        }
                        // Avoid blocking the cancelling thread (e.g. UI thread on prompt dismissal)
                        // with synchronous USB transfers; scan()'s finally also awaits disconnect.
                        readerScope.launch(nfcIoDispatcher) {
                            disconnectDriver(driver)
                        }
                    }
                    }
                } finally {
                    interactionJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            scanCancelled = true
            throw e
        } catch (e: PromptDismissedException) {
            scanCancelled = true
            throw e
        } finally {
            session.scanCompleted = true
            withContext(NonCancellable) {
                if (scanCancelled) {
                    session.isCardPresent = false
                    driverLock.withLock {
                        session.delayedDisconnectJob?.cancel()
                        session.delayedDisconnectJob = null
                        if (activeSession === session) {
                            activeSession = null
                        }
                    }
                    withContext(nfcIoDispatcher) {
                        disconnectDriver(driver)
                    }
                } else {
                    driver.setListener(object : CcidDriverListener {
                        override fun onCardInserted() {
                            session.isCardPresent = true
                        }

                        override fun onCardRemoved() {
                            Logger.i(TAG, "Card removed")
                            session.isCardPresent = false
                            val disconnectOnRemoval = driverLock.withLock {
                                if (session.waitingForRemovalCount == 0 &&
                                    !session.stillNeedsTag &&
                                    activeSession === session
                                ) {
                                    session.delayedDisconnectJob?.cancel()
                                    session.delayedDisconnectJob = null
                                    activeSession = null
                                    true
                                } else {
                                    false
                                }
                            }
                            if (disconnectOnRemoval) {
                                disconnectDriver(driver)
                            }
                        }
                    })
                    val shouldDisconnectNow = driverLock.withLock {
                        if (activeSession !== session) {
                            false
                        } else if (!session.stillNeedsTag &&
                            !session.isCardPresent &&
                            session.waitingForRemovalCount == 0
                        ) {
                            session.delayedDisconnectJob?.cancel()
                            session.delayedDisconnectJob = null
                            activeSession = null
                            true
                        } else {
                            if (session.waitingForRemovalCount == 0) {
                                val timeout = if (session.stillNeedsTag) {
                                    openTagDisconnectDelay
                                } else {
                                    disconnectDelay
                                }
                                scheduleDelayedDisconnectLocked(session, timeout)
                            }
                            false
                        }
                    }
                    if (shouldDisconnectNow) {
                        withContext(nfcIoDispatcher) {
                            disconnectDriver(driver)
                        }
                    }
                }
            }
        }
    }
}
