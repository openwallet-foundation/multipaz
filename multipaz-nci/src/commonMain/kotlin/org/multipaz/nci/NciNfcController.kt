package org.multipaz.nci

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcApduRouter
import org.multipaz.nfc.NfcApduRouterBackend
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.NfcTagReader
import org.multipaz.nfc.ResponseApdu
import org.multipaz.nfc.scanNfcWithPromptDialog
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NciNfcController"

/**
 * High-level NFC controller coordinating both NFC Reader/Writer mode ([NfcTagReaderNci]) and
 * NFC Card/Tag Emulation mode ([NfcApduRouterBackend]) over an [NciPacketIo] transport (such as
 * an NXP PN7160 connected via I2C + GPIO on a Raspberry Pi).
 *
 * When [start] is called:
 * - The controller initializes the NCI 2.0 state machine via [NciEngine].
 * - If [registerWithMultipaz] is `true` (the default), [reader] is registered with
 *   [NfcTagReader.registerReader] (so [NfcTagReader.getReaders] returns it automatically) and
 *   this controller is registered with [NfcApduRouter.registerBackend] (so starting an
 *   [NfcApduRouter] automatically enables Listen Mode card emulation).
 * - Whenever [reader.scan] is invoked, the controller temporarily switches from Listen Mode to
 *   Poll Mode for the duration of the scan (plus tag-removal tracking via [waitUntilRemoved]),
 *   and automatically resumes Listen Mode once the scan session ends.
 */
class NciNfcController(
    private val packetIo: NciPacketIo,
    private val registerWithMultipaz: Boolean = true,
    externalReader: Boolean = false,
    private val disableReaderModeDelay: Duration = 5.seconds,
    private val pollInterval: Duration = 100.milliseconds,
    private val commandTimeout: Duration = 3.seconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : NfcApduRouterBackend {

    val reader: NfcTagReaderNci = NfcTagReaderNci(
        controller = this,
        external = externalReader,
        disableReaderModeDelay = disableReaderModeDelay,
        pollInterval = pollInterval,
        backgroundScope = scope
    )

    private val controllerMutex = Mutex()

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    @Volatile
    private var activeRouter: NfcApduRouter? = null

    @Volatile
    private var pollingActive = false

    @Volatile
    private var listenDiscoveryActive = false

    @Volatile
    private var postScanTagPresent = false

    @Volatile
    private var waitingForRemovalCount = 0

    @Volatile
    private var delayedDisablePollJob: Job? = null

    @Volatile
    private var currentPollActivationChannel: Channel<NciRfActivatedEvent>? = null

    @Volatile
    private var currentPollDeactivatedDeferred: CompletableDeferred<Unit>? = null

    private val engine = NciEngine(
        packetIo = packetIo,
        commandTimeout = commandTimeout,
        scope = scope,
        listener = object : NciEngineListener {
            override fun onRfActivated(event: NciRfActivatedEvent) {
                handleRfActivated(event)
            }

            override fun onRfDeactivated(deactivationType: Int, deactivationReason: Int) {
                handleRfDeactivated(deactivationType, deactivationReason)
            }

            override fun onDataPacketReceived(data: ByteArray) {
                handleListenModeCommandApdu(data)
            }

            override fun onEngineError(error: Throwable) {
                Logger.e(TAG, "NCI engine error", error)
            }
        }
    )

    /**
     * Initializes the NCI controller hardware and registers with [NfcTagReader] and [NfcApduRouter].
     */
    suspend fun start() {
        controllerMutex.withLock {
            check(!closed) { "NciNfcController is closed" }
            if (started) return
            engine.initializeController()
            started = true
        }
        if (registerWithMultipaz) {
            NfcTagReader.registerReader(reader)
            NfcApduRouter.registerBackend(this)
        }
        syncIdleOrListenMode()
    }

    /**
     * Stops the NCI controller, unregisters from [NfcTagReader] and [NfcApduRouter], and closes
     * the underlying [NciPacketIo].
     */
    suspend fun stop() {
        if (registerWithMultipaz) {
            NfcTagReader.unregisterReader(reader)
            NfcApduRouter.unregisterBackend(this)
        }
        controllerMutex.withLock {
            if (closed) return
            closed = true
            delayedDisablePollJob?.cancel()
            delayedDisablePollJob = null
            postScanTagPresent = false
            if (started) {
                try {
                    engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                } catch (_: Throwable) {
                }
            }
            engine.close()
        }
    }

    /**
     * Non-suspending close that immediately tears down the NCI engine and [NciPacketIo].
     */
    fun close() {
        if (registerWithMultipaz) {
            NfcTagReader.unregisterReader(reader)
            NfcApduRouter.unregisterBackend(this)
        }
        closed = true
        delayedDisablePollJob?.cancel()
        delayedDisablePollJob = null
        postScanTagPresent = false
        engine.close()
    }

    override fun onRouterStarted(router: NfcApduRouter) {
        activeRouter = router
        if (started && !closed) {
            scope.launch {
                syncIdleOrListenMode()
            }
        }
    }

    override fun onRouterStopped(router: NfcApduRouter) {
        if (activeRouter === router) {
            activeRouter = null
            if (started && !closed) {
                scope.launch {
                    syncIdleOrListenMode()
                }
            }
        }
    }

    override fun sendResponseApdu(responseApdu: ResponseApdu) {
        if (closed) return
        val encoded = responseApdu.encode()
        scope.launch {
            try {
                engine.sendDataMessage(encoded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "Failed to send Listen Mode ResponseApdu", e)
            }
        }
    }

    internal suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
        @Suppress("UNUSED_PARAMETER") options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        if (!started) {
            start()
        }

        val activationChannel = Channel<NciRfActivatedEvent>(Channel.CONFLATED)
        controllerMutex.withLock {
            delayedDisablePollJob?.cancel()
            delayedDisablePollJob = null
            postScanTagPresent = false
            pollingActive = true
            currentPollActivationChannel = activationChannel
            if (listenDiscoveryActive || engine.activeRfEvent != null) {
                try {
                    engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                } catch (_: Throwable) {
                }
                listenDiscoveryActive = false
            }
            engine.startDiscovery(enablePoll = true, enableListen = false)
        }

        var scanCancelled = false
        var returnedTag: NfcIsoTagNci? = null
        try {
            return scanNfcWithPromptDialog(message) { updateMessage, resetMessage ->
                while (true) {
                    val event = activationChannel.receive()
                    val deactivatedDeferred = CompletableDeferred<Unit>()
                    currentPollDeactivatedDeferred = deactivatedDeferred
                    postScanTagPresent = true

                    val isoTag = NfcIsoTagNci(
                        engine = engine,
                        activatedEvent = event,
                        updateMessageCallback = updateMessage,
                        onCloseCallback = {
                            onScannedTagClosed()
                        }
                    )
                    try {
                        val result = withContext(context) {
                            tagInteractionFunc(isoTag)
                        }
                        if (result == null) {
                            resetMessage()
                            restartPollDiscoveryAfterRejectedTag()
                            continue
                        }
                        returnedTag = isoTag
                        return@scanNfcWithPromptDialog result
                    } catch (e: NfcTagLostException) {
                        Logger.w(TAG, "Tag lost during scan interaction; continuing polling", e)
                        resetMessage()
                        restartPollDiscoveryAfterRejectedTag()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            }
        } catch (e: CancellationException) {
            scanCancelled = true
            throw e
        } catch (e: PromptDismissedException) {
            scanCancelled = true
            throw e
        } finally {
            withContext(NonCancellable) {
                controllerMutex.withLock {
                    if (currentPollActivationChannel === activationChannel) {
                        currentPollActivationChannel = null
                    }
                }
                val tag = returnedTag
                if (scanCancelled) {
                    finishPollSessionAndResumeListen()
                } else if (tag == null) {
                    // tagInteractionFunc threw a non-NfcTagLostException while tag was still in field
                    if (postScanTagPresent) {
                        scheduleDelayedPollDisable()
                    } else {
                        finishPollSessionAndResumeListen()
                    }
                } else if (tag.isClosed) {
                    if (postScanTagPresent) {
                        scheduleDelayedPollDisable()
                    } else {
                        finishPollSessionAndResumeListen()
                    }
                }
                // If tag != null && !tag.isClosed, the caller returned an open NfcIsoTag for
                // deferred communication; onScannedTagClosed() will schedule teardown when closed.
            }
        }
    }

    internal suspend fun waitUntilRemoved() {
        val shouldWait = controllerMutex.withLock {
            if (!pollingActive || !postScanTagPresent) {
                false
            } else {
                waitingForRemovalCount += 1
                delayedDisablePollJob?.cancel()
                delayedDisablePollJob = null
                true
            }
        }
        if (!shouldWait) {
            return
        }

        try {
            while (postScanTagPresent && !closed) {
                val stillPresent = engine.checkTagPresence()
                if (!stillPresent) {
                    postScanTagPresent = false
                    break
                }
                val deactDeferred = currentPollDeactivatedDeferred
                if (deactDeferred != null && deactDeferred.isCompleted) {
                    postScanTagPresent = false
                    break
                }
                withContext(Dispatchers.Default) {
                    delay(pollInterval)
                }
            }
        } finally {
            withContext(NonCancellable) {
                val shouldFinish = controllerMutex.withLock {
                    waitingForRemovalCount -= 1
                    waitingForRemovalCount == 0 && pollingActive
                }
                if (shouldFinish) {
                    finishPollSessionAndResumeListen()
                }
            }
        }
    }

    private suspend fun onScannedTagClosed() {
        val shouldFinishImmediately = controllerMutex.withLock {
            if (!pollingActive) {
                false
            } else if (waitingForRemovalCount > 0) {
                false
            } else if (!postScanTagPresent) {
                true
            } else {
                scheduleDelayedPollDisableLocked()
                false
            }
        }
        if (shouldFinishImmediately) {
            finishPollSessionAndResumeListen()
        }
    }

    private suspend fun scheduleDelayedPollDisable() {
        controllerMutex.withLock {
            if (pollingActive && waitingForRemovalCount == 0) {
                scheduleDelayedPollDisableLocked()
            }
        }
    }

    private fun scheduleDelayedPollDisableLocked() {
        delayedDisablePollJob?.cancel()
        lateinit var job: Job
        job = scope.launch {
            delay(disableReaderModeDelay)
            val shouldFinish = controllerMutex.withLock {
                if (delayedDisablePollJob === job) {
                    delayedDisablePollJob = null
                    waitingForRemovalCount == 0 && pollingActive
                } else {
                    false
                }
            }
            if (shouldFinish) {
                finishPollSessionAndResumeListen()
            }
        }
        delayedDisablePollJob = job
    }

    private suspend fun restartPollDiscoveryAfterRejectedTag() {
        controllerMutex.withLock {
            postScanTagPresent = false
            if (!pollingActive || closed) return
            try {
                engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
            } catch (_: Throwable) {
            }
            try {
                engine.startDiscovery(enablePoll = true, enableListen = false)
            } catch (e: Throwable) {
                Logger.w(TAG, "Failed to restart poll discovery", e)
            }
        }
    }

    private suspend fun finishPollSessionAndResumeListen() {
        controllerMutex.withLock {
            delayedDisablePollJob?.cancel()
            delayedDisablePollJob = null
            postScanTagPresent = false
            currentPollDeactivatedDeferred?.complete(Unit)
            currentPollDeactivatedDeferred = null
            if (!pollingActive && !listenDiscoveryActive) {
                return
            }
            pollingActive = false
            if (!closed && started) {
                try {
                    engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                } catch (_: Throwable) {
                }
                listenDiscoveryActive = false
                if (activeRouter != null) {
                    try {
                        engine.startDiscovery(enablePoll = false, enableListen = true)
                        listenDiscoveryActive = true
                    } catch (e: Throwable) {
                        Logger.w(TAG, "Failed to resume Listen Mode discovery", e)
                    }
                }
            }
        }
    }

    private suspend fun syncIdleOrListenMode() {
        controllerMutex.withLock {
            if (!started || closed || pollingActive) {
                return
            }
            val wantListen = activeRouter != null
            if (wantListen && !listenDiscoveryActive) {
                try {
                    engine.startDiscovery(enablePoll = false, enableListen = true)
                    listenDiscoveryActive = true
                } catch (e: Throwable) {
                    Logger.w(TAG, "Failed to start Listen Mode discovery", e)
                }
            } else if (!wantListen && listenDiscoveryActive) {
                try {
                    engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                } catch (_: Throwable) {
                }
                listenDiscoveryActive = false
            }
        }
    }

    private fun handleRfActivated(event: NciRfActivatedEvent) {
        if (event.isPollMode) {
            postScanTagPresent = true
            currentPollActivationChannel?.trySend(event)
        } else if (event.isListenMode) {
            Logger.i(TAG, "NFC Listen Mode interface activated (protocol=0x${event.rfProtocol.toString(16)})")
        }
    }

    private fun handleRfDeactivated(deactivationType: Int, deactivationReason: Int) {
        if (pollingActive) {
            postScanTagPresent = false
            currentPollDeactivatedDeferred?.complete(Unit)
        } else {
            val router = activeRouter
            if (router != null) {
                val reason = if (deactivationReason == NciConstants.DEACTIVATE_REASON_ENDPOINT_REQUEST &&
                    (deactivationType == NciConstants.DEACTIVATE_TYPE_SLEEP ||
                        deactivationType == NciConstants.DEACTIVATE_TYPE_SLEEP_AF)
                ) {
                    NfcDeactivationReason.DESELECTED
                } else {
                    NfcDeactivationReason.LINK_LOSS
                }
                router.deactivate(reason)
            }
            // If controller deactivated to IDLE (rather than DISCOVERY), restart Listen Mode discovery
            if (deactivationType == NciConstants.DEACTIVATE_TYPE_IDLE && activeRouter != null && !closed) {
                listenDiscoveryActive = false
                scope.launch {
                    syncIdleOrListenMode()
                }
            }
        }
    }

    private fun handleListenModeCommandApdu(data: ByteArray) {
        val router = activeRouter ?: return
        val commandApdu = try {
            CommandApdu.decode(data)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to decode incoming Listen Mode CommandApdu", e)
            return
        }
        val syncResponse = try {
            router.processCommandApdu(commandApdu)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "Error in NfcApduRouter.processCommandApdu", e)
            null
        }
        if (syncResponse != null) {
            sendResponseApdu(syncResponse)
        }
    }
}
