package org.multipaz.nfc

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.Logger
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
    private val onClose: () -> Unit,
): NfcIsoTag() {
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
            val responseApduBytes = driver.transceive(commandApduBytes)
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

internal class NfcTagReaderUsb internal constructor(
    internal val device: UsbDevice?,
    internal val interfaceIndex: Int,
    private val disconnectDelay: Duration = 5.seconds,
    private val openTagDisconnectDelay: Duration = 30.seconds,
    private val pollInterval: Duration = 100.milliseconds,
    private val backgroundScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val createDriver: (pollingFrameData: ByteArray?) -> CcidDriverOps,
): NfcTagReader {
    constructor(
        manager: UsbManager,
        device: UsbDevice,
        interfaceIndex: Int,
    ) : this(
        device = device,
        interfaceIndex = interfaceIndex,
        createDriver = { pollingFrameData ->
            CcidDriver(
                usbManager = manager,
                device = device,
                interfaceIndex = interfaceIndex,
                pollingFrameData = pollingFrameData
            )
        }
    )

    private class DriverSession(
        val driver: CcidDriverOps,
        val isCardPresent: AtomicBoolean = AtomicBoolean(false),
        @Volatile var keepConnectedUntilTagClosed: Boolean = false,
        @Volatile var activeTag: NfcIsoTagUsb? = null,
        val scanCompleted: AtomicBoolean = AtomicBoolean(false),
        var waitingForRemovalCount: Int = 0,
        var delayedDisconnectJob: Job? = null,
    ) {
        val stillNeedsTag: Boolean
            get() = keepConnectedUntilTagClosed && activeTag?.isClosed != true
    }

    private val driverLock = Any()
    private val usbLifecycleLock = Any()
    private var activeSession: DriverSession? = null
    private var connectedDriver: CcidDriverOps? = null

    override val external: Boolean
        get() = true

    override val dialogAlwaysShown: Boolean
        get() = false

    private fun connectDriver(driver: CcidDriverOps) {
        synchronized(usbLifecycleLock) {
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
        synchronized(usbLifecycleLock) {
            if (connectedDriver === driver) {
                connectedDriver = null
                driver.setListener(null)
                driver.disconnect()
            }
        }
    }

    internal fun close() {
        val session = synchronized(driverLock) {
            val current = activeSession
            current?.delayedDisconnectJob?.cancel()
            current?.delayedDisconnectJob = null
            activeSession = null
            current
        }
        session?.isCardPresent?.set(false)
        if (session != null) {
            disconnectDriver(session.driver)
        } else {
            synchronized(usbLifecycleLock) {
                connectedDriver?.let { previous ->
                    connectedDriver = null
                    previous.setListener(null)
                    previous.disconnect()
                }
            }
        }
    }

    private fun scheduleDelayedDisconnectLocked(session: DriverSession, timeout: Duration) {
        session.delayedDisconnectJob?.cancel()
        lateinit var job: Job
        job = backgroundScope.launch {
            delay(timeout)
            val shouldDisconnect = synchronized(driverLock) {
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
                disconnectDriver(session.driver)
            }
        }
        session.delayedDisconnectJob = job
    }

    override suspend fun waitUntilRemoved() {
        val session = synchronized(driverLock) {
            val current = activeSession ?: return
            current.waitingForRemovalCount++
            current.delayedDisconnectJob?.cancel()
            current.delayedDisconnectJob = null
            current
        }
        try {
            while (session.isCardPresent.get()) {
                val status = withContext(Dispatchers.IO) {
                    try {
                        session.driver.getCardStatus()
                    } catch (e: IOException) {
                        Logger.d(TAG, "Error getting card status while waiting for removal: ${e.message}")
                        CardStatus.ABSENT
                    }
                }
                if (status == CardStatus.ABSENT) {
                    session.isCardPresent.set(false)
                    break
                }
                delay(pollInterval)
            }
        } finally {
            val shouldDisconnect = synchronized(driverLock) {
                session.waitingForRemovalCount--
                if (session.waitingForRemovalCount == 0 && activeSession === session) {
                    session.delayedDisconnectJob?.cancel()
                    session.delayedDisconnectJob = null
                    activeSession = null
                    true
                } else {
                    false
                }
            }
            if (shouldDisconnect) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        disconnectDriver(session.driver)
                    }
                }
            }
        }
    }

    /**
     * Scans for an NFC tag using the USB CCID reader and executes [tagInteractionFunc].
     *
     * @param message Optional message to show during scanning.
     * @param tagInteractionFunc The interaction function to execute when a tag is detected.
     * @param options Options for NFC scanning.
     * @param context The coroutine context for execution.
     * @return The result of [tagInteractionFunc].
     * @throws NfcTagLostException if the tag is lost during transaction.
     * @throws IOException if a USB or CCID communication error occurs.
     * @throws SecurityException if USB permission is denied.
     */
    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        val previousSession = synchronized(driverLock) {
            val prev = activeSession
            prev?.delayedDisconnectJob?.cancel()
            prev?.delayedDisconnectJob = null
            activeSession = null
            prev
        }
        previousSession?.isCardPresent?.set(false)

        val driver = createDriver(options.pollingFrameData?.toByteArray())
        connectDriver(driver)
        val session = DriverSession(driver = driver)
        synchronized(driverLock) {
            activeSession = session
        }

        var scanCancelled = false
        try {
            return scanNfcWithPromptDialog(message) { updateMessage, resetMessage ->
                suspendCancellableCoroutine { continuation ->
                    val stateLock = Any()
                    var readJob: Job? = null
                    session.isCardPresent.set(false)

                    val listener = object : CcidDriverListener {
                        override fun onCardInserted() {
                            Logger.i(TAG, "Card inserted")
                            synchronized(stateLock) {
                                session.isCardPresent.set(true)
                                if (!continuation.isActive || readJob != null) return
                                readJob = CoroutineScope(context).launch {
                                    val tag = NfcIsoTagUsb(
                                        driver = driver,
                                        updateMessage = updateMessage,
                                        onClose = {
                                            if (session.scanCompleted.get()) {
                                                val shouldDisconnect = synchronized(driverLock) {
                                                    if (activeSession !== session) {
                                                        false
                                                    } else if (session.waitingForRemovalCount == 0 &&
                                                        !session.isCardPresent.get()
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
                                                    disconnectDriver(driver)
                                                }
                                            }
                                        }
                                    )
                                    session.activeTag = tag
                                    try {
                                        while (continuation.isActive && session.isCardPresent.get()) {
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
                                            if (!continuation.isActive || !session.isCardPresent.get()) break
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
                                        synchronized(stateLock) {
                                            readJob = null
                                        }
                                    }
                                }
                            }
                        }

                        override fun onCardRemoved() {
                            Logger.i(TAG, "Card removed")
                            synchronized(stateLock) {
                                session.isCardPresent.set(false)
                            }
                        }
                    }

                    driver.setListener(listener = listener)

                    try {
                        val status = driver.getCardStatus()
                        if (status == CardStatus.PRESENT_ACTIVE || status == CardStatus.PRESENT_INACTIVE) {
                            listener.onCardInserted()
                        }
                    } catch (e: IOException) {
                        Logger.w(TAG, "Failed to query initial card status", e)
                    }

                    continuation.invokeOnCancellation {
                        readJob?.cancel()
                        session.isCardPresent.set(false)
                        synchronized(driverLock) {
                            session.delayedDisconnectJob?.cancel()
                            session.delayedDisconnectJob = null
                            if (activeSession === session) {
                                activeSession = null
                            }
                        }
                        disconnectDriver(driver)
                    }
                }
            }
        } catch (e: CancellationException) {
            scanCancelled = true
            throw e
        } catch (e: PromptDismissedException) {
            scanCancelled = true
            throw e
        } finally {
            session.scanCompleted.set(true)
            if (scanCancelled) {
                session.isCardPresent.set(false)
                synchronized(driverLock) {
                    session.delayedDisconnectJob?.cancel()
                    session.delayedDisconnectJob = null
                    if (activeSession === session) {
                        activeSession = null
                    }
                }
                disconnectDriver(driver)
            } else {
                driver.setListener(object : CcidDriverListener {
                    override fun onCardInserted() {
                        session.isCardPresent.set(true)
                    }

                    override fun onCardRemoved() {
                        Logger.i(TAG, "Card removed")
                        session.isCardPresent.set(false)
                        val disconnectOnRemoval = synchronized(driverLock) {
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
                val shouldDisconnectNow = synchronized(driverLock) {
                    if (activeSession !== session) {
                        false
                    } else if (!session.stillNeedsTag &&
                        !session.isCardPresent.get() &&
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
                    disconnectDriver(driver)
                }
            }
        }
    }
}
