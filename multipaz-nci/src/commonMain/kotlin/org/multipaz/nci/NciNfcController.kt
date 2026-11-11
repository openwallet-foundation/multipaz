package org.multipaz.nci

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
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
    private val openTagDisconnectDelay: Duration = 30.seconds,
    private val pollInterval: Duration = 100.milliseconds,
    private val commandTimeout: Duration = 3.seconds,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : NfcApduRouterBackend {

    private class PollSession(
        val activationChannel: Channel<NciRfActivatedEvent> = Channel(Channel.CONFLATED),
        @Volatile var tagPresent: Boolean = false,
        @Volatile var deactivatedDeferred: CompletableDeferred<Unit>? = null,
        @Volatile var keepOpenUntilTagClosed: Boolean = false,
        @Volatile var activeTag: NfcIsoTagNci? = null,
        @Volatile var scanExited: Boolean = false,
        var waitingForRemovalCount: Int = 0,
        var delayedDisableJob: Job? = null,
    ) {
        val stillNeedsTag: Boolean
            get() = keepOpenUntilTagClosed && activeTag?.isClosed != true
    }

    private val controllerJob = SupervisorJob(scope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(scope.coroutineContext + controllerJob)

    val reader: NfcTagReaderNci = NfcTagReaderNci(
        controller = this,
        external = externalReader
    )

    private val controllerMutex = Mutex()
    private val outboundListenResponseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var outboundListenSenderJob: Job? = null

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    @Volatile
    private var activeRouter: NfcApduRouter? = null

    @Volatile
    private var activePollSession: PollSession? = null

    @Volatile
    private var listenDiscoveryActive = false

    private val engine = NciEngine(
        packetIo = packetIo,
        commandTimeout = commandTimeout,
        scope = controllerScope,
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

            override fun onEngineError(error: Exception) {
                Logger.e(TAG, "NCI engine error", error)
                val ioError = error as? IOException ?: IOException("NCI engine error", error)
                activePollSession?.let { session ->
                    session.tagPresent = false
                    session.deactivatedDeferred?.complete(Unit)
                    session.activationChannel.close(ioError)
                }
            }
        }
    )

    /**
     * Initializes the NCI controller hardware and registers with [NfcTagReader] and [NfcApduRouter].
     *
     * @throws IOException if initializing the NCI controller fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun start() {
        controllerMutex.withLock {
            check(!closed) { "NciNfcController is closed" }
            if (started) return
            engine.initializeController()
            if (outboundListenSenderJob?.isActive != true) {
                outboundListenSenderJob = controllerScope.launch {
                    for (encoded in outboundListenResponseChannel) {
                        if (closed) break
                        try {
                            engine.sendDataMessage(encoded)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w(TAG, "Failed to send Listen Mode ResponseApdu", e)
                        }
                    }
                }
            }
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
     *
     * @throws IOException if deactivating the controller fails unexpectedly.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun stop() {
        if (registerWithMultipaz) {
            NfcTagReader.unregisterReader(reader)
            NfcApduRouter.unregisterBackend(this)
        }
        controllerMutex.withLock {
            if (closed) return
            closed = true
            cancelActivePollSessionLocked(CancellationException("NCI NFC controller stopped"))
            outboundListenResponseChannel.close()
            outboundListenSenderJob?.cancel()
            if (started) {
                try {
                    engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            engine.close()
            controllerJob.cancel()
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
        activePollSession?.let { session ->
            session.delayedDisableJob?.cancel()
            session.delayedDisableJob = null
            session.tagPresent = false
            session.deactivatedDeferred?.complete(Unit)
            session.activationChannel.close(CancellationException("NCI NFC controller closed"))
        }
        activePollSession = null
        outboundListenResponseChannel.close()
        outboundListenSenderJob?.cancel()
        engine.close()
        controllerJob.cancel()
    }

    override fun onRouterStarted(router: NfcApduRouter) {
        activeRouter = router
        if (started && !closed) {
            controllerScope.launch {
                syncIdleOrListenMode()
            }
        }
    }

    override fun onRouterStopped(router: NfcApduRouter) {
        if (activeRouter === router) {
            activeRouter = null
            if (started && !closed) {
                controllerScope.launch {
                    syncIdleOrListenMode()
                }
            }
        }
    }

    override fun sendResponseApdu(responseApdu: ResponseApdu) {
        if (closed) return
        outboundListenResponseChannel.trySend(responseApdu.encode())
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

        val session = PollSession()
        var scanCancelled = false
        var returnedTag: NfcIsoTagNci? = null
        try {
            var notifyRouterLinkLoss: NfcApduRouter? = null
            controllerMutex.withLock {
                check(!closed) { "NciNfcController is closed" }
                val wasListenRfActivated = activePollSession == null && engine.activeRfEvent?.isListenMode == true
                cancelActivePollSessionLocked(CancellationException("Preempted by a new NFC scan"))
                activePollSession = session
                if (listenDiscoveryActive || engine.activeRfEvent != null) {
                    try {
                        engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    listenDiscoveryActive = false
                    if (wasListenRfActivated) {
                        notifyRouterLinkLoss = activeRouter
                    }
                }
                engine.startDiscovery(enablePoll = true, enableListen = false)
            }
            notifyRouterLinkLoss?.deactivate(NfcDeactivationReason.LINK_LOSS)

            return scanNfcWithPromptDialog(message) { updateMessage, resetMessage ->
                while (true) {
                    val event = session.activationChannel.receive()
                    val deactivatedDeferred = CompletableDeferred<Unit>()
                    session.deactivatedDeferred = deactivatedDeferred
                    session.tagPresent = true

                    val isoTag = NfcIsoTagNci(
                        engine = engine,
                        activatedEvent = event,
                        updateMessageCallback = updateMessage,
                        onCloseCallback = {
                            onScannedTagClosed(session)
                        }
                    )
                    session.activeTag = isoTag
                    try {
                        val result = withContext(context) {
                            tagInteractionFunc(isoTag)
                        }
                        if (result == null) {
                            resetMessage()
                            restartPollDiscoveryAfterRejectedTag(session)
                            continue
                        }
                        session.keepOpenUntilTagClosed = !isoTag.isClosed
                        returnedTag = isoTag
                        return@scanNfcWithPromptDialog result
                    } catch (e: NfcTagLostException) {
                        Logger.w(TAG, "Tag lost during scan interaction; continuing polling", e)
                        resetMessage()
                        restartPollDiscoveryAfterRejectedTag(session)
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
            session.scanExited = true
            withContext(NonCancellable) {
                val tag = returnedTag
                if (scanCancelled) {
                    finishPollSessionAndResumeListen(session)
                } else if (tag == null) {
                    // tagInteractionFunc threw a non-NfcTagLostException (or startDiscovery failed)
                    if (session.tagPresent) {
                        scheduleDelayedPollDisable(session, disableReaderModeDelay)
                    } else {
                        finishPollSessionAndResumeListen(session)
                    }
                } else if (tag.isClosed) {
                    if (session.tagPresent) {
                        scheduleDelayedPollDisable(session, disableReaderModeDelay)
                    } else {
                        finishPollSessionAndResumeListen(session)
                    }
                } else {
                    // Caller returned an open NfcIsoTag for deferred communication; schedule a
                    // fallback timeout so Poll Mode cannot remain stuck indefinitely if unclosed.
                    scheduleDelayedPollDisable(session, openTagDisconnectDelay)
                }
            }
        }
    }

    internal suspend fun waitUntilRemoved() {
        val session = controllerMutex.withLock {
            val current = activePollSession
            if (current == null || !current.tagPresent) {
                null
            } else {
                current.waitingForRemovalCount += 1
                current.delayedDisableJob?.cancel()
                current.delayedDisableJob = null
                current
            }
        } ?: return

        try {
            while (session.tagPresent && !closed && activePollSession === session) {
                val stillPresent = engine.checkTagPresence()
                if (!stillPresent) {
                    session.tagPresent = false
                    break
                }
                val deactDeferred = session.deactivatedDeferred
                if (deactDeferred != null && deactDeferred.isCompleted) {
                    session.tagPresent = false
                    break
                }
                withContext(Dispatchers.Default) {
                    delay(pollInterval)
                }
            }
        } finally {
            withContext(NonCancellable) {
                val shouldFinish = controllerMutex.withLock {
                    session.waitingForRemovalCount -= 1
                    session.waitingForRemovalCount == 0 &&
                        !session.stillNeedsTag &&
                        activePollSession === session
                }
                if (shouldFinish) {
                    finishPollSessionAndResumeListen(session)
                }
            }
        }
    }

    private suspend fun onScannedTagClosed(session: PollSession) {
        if (!session.scanExited) {
            return
        }
        val shouldFinishImmediately = controllerMutex.withLock {
            if (activePollSession !== session) {
                false
            } else if (session.waitingForRemovalCount > 0) {
                false
            } else if (!session.tagPresent) {
                true
            } else {
                scheduleDelayedPollDisableLocked(session, disableReaderModeDelay)
                false
            }
        }
        if (shouldFinishImmediately) {
            finishPollSessionAndResumeListen(session)
        }
    }

    private suspend fun scheduleDelayedPollDisable(session: PollSession, timeout: Duration) {
        controllerMutex.withLock {
            if (activePollSession === session && session.waitingForRemovalCount == 0) {
                scheduleDelayedPollDisableLocked(session, timeout)
            }
        }
    }

    private fun scheduleDelayedPollDisableLocked(session: PollSession, timeout: Duration) {
        session.delayedDisableJob?.cancel()
        lateinit var job: Job
        job = controllerScope.launch {
            delay(timeout)
            val shouldFinish = controllerMutex.withLock {
                if (session.delayedDisableJob === job) {
                    session.delayedDisableJob = null
                    session.waitingForRemovalCount == 0 && activePollSession === session
                } else {
                    false
                }
            }
            if (shouldFinish) {
                finishPollSessionAndResumeListen(session)
            }
        }
        session.delayedDisableJob = job
    }

    private suspend fun restartPollDiscoveryAfterRejectedTag(session: PollSession) {
        controllerMutex.withLock {
            session.tagPresent = false
            if (activePollSession !== session || closed) return
            try {
                engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            try {
                engine.startDiscovery(enablePoll = true, enableListen = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to restart poll discovery", e)
            }
        }
    }

    private fun cancelActivePollSessionLocked(cause: CancellationException) {
        activePollSession?.let { prev ->
            prev.delayedDisableJob?.cancel()
            prev.delayedDisableJob = null
            prev.tagPresent = false
            prev.deactivatedDeferred?.complete(Unit)
            prev.deactivatedDeferred = null
            prev.activationChannel.close(cause)
        }
        activePollSession = null
    }

    private suspend fun finishPollSessionAndResumeListen(session: PollSession) {
        controllerMutex.withLock {
            if (activePollSession !== session) {
                return
            }
            session.delayedDisableJob?.cancel()
            session.delayedDisableJob = null
            session.tagPresent = false
            session.deactivatedDeferred?.complete(Unit)
            session.deactivatedDeferred = null
            session.activationChannel.close()
            activePollSession = null

            if (!closed && started) {
                try {
                    engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                listenDiscoveryActive = false
                if (activeRouter != null) {
                    try {
                        engine.startDiscovery(enablePoll = false, enableListen = true)
                        listenDiscoveryActive = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to resume Listen Mode discovery", e)
                    }
                }
            }
        }
    }

    private suspend fun syncIdleOrListenMode() {
        controllerMutex.withLock {
            if (!started || closed || activePollSession != null) {
                return
            }
            val wantListen = activeRouter != null
            if (wantListen && !listenDiscoveryActive) {
                try {
                    engine.startDiscovery(enablePoll = false, enableListen = true)
                    listenDiscoveryActive = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to start Listen Mode discovery", e)
                }
            } else if (!wantListen && listenDiscoveryActive) {
                try {
                    engine.deactivate(NciConstants.DEACTIVATE_TYPE_IDLE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                listenDiscoveryActive = false
            }
        }
    }

    private fun handleRfActivated(event: NciRfActivatedEvent) {
        if (event.isPollMode) {
            val session = activePollSession ?: return
            session.tagPresent = true
            session.activationChannel.trySend(event)
        } else if (event.isListenMode) {
            Logger.i(TAG, "NFC Listen Mode interface activated (protocol=0x${event.rfProtocol.toString(16)})")
        }
    }

    private fun handleRfDeactivated(deactivationType: Int, deactivationReason: Int) {
        val pollSession = activePollSession
        if (pollSession != null) {
            pollSession.tagPresent = false
            pollSession.deactivatedDeferred?.complete(Unit)
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
                controllerScope.launch {
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
            if (e is CancellationException) throw e
            Logger.w(TAG, "Failed to decode incoming Listen Mode CommandApdu", e)
            return
        }
        val syncResponse = try {
            router.processCommandApdu(commandApdu)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error in NfcApduRouter.processCommandApdu", e)
            null
        }
        if (syncResponse != null) {
            sendResponseApdu(syncResponse)
        }
    }
}
