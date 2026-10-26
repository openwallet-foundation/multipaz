package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
import org.multipaz.mdoc.transport.TransportWarning
import org.multipaz.mdoc.transport.TransportWarningCode
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcApduRouter
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.ResponseApdu
import org.multipaz.presentment.EngagementParams
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentOutcome
import org.multipaz.presentment.PresentmentProtocol
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.PresentmentTelemetry
import org.multipaz.presentment.classifyPresentmentException
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Base class for implementing NFCv2 engagement according to ISO/IEC 18013 Second Edition.
 *
 * Applications should subclass this and register it in [NfcApduRouter.buildServices]
 * for the appropriate AID (`A0000002480401`).
 */
abstract class MdocNfcV2Service : MdocNfcPresentmentService() {
    companion object {
        private const val TAG = "MdocNfcV2Service"

        // Active multi-tap state stored in companion so that subsequent taps
        // work regardless of whether the service instance is reused or recreated.
        @Volatile
        internal var hybridTransport: NfcHybridTransportMdoc? = null

        @Volatile
        internal var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>? = null

        @Volatile
        internal var activeDeviceRequest: DeviceRequest? = null

        @Volatile
        internal var activeEngagement: MdocNfcV2EngagementHelper? = null

        @Volatile
        internal var onHybridTransportCreated: ((NfcHybridTransportMdoc) -> Unit)? = null
    }

    // A job started when the reader has selected us and used for listening for
    // a signal from the UI that it wants to cancel during NFC engagement.
    private var listenForCancellationFromUiJob: Job? = null

    @Volatile
    private var engagement: MdocNfcV2EngagementHelper? = null

    /**
     * Settings provided by the application for how to configure NFCv2 engagement.
     *
     * @property source the [PresentmentSource] to use as the source of truth for what to present.
     * @property promptModel the [PromptModel] to use.
     * @property presentmentModel the [PresentmentModel] to update during presentment, or `null`.
     * @property launchUi a callback invoked when handover completes to launch the presentment UI, or `null`.
     * @property sessionEncryptionCurve the Elliptic Curve Cryptography curve to use for session encryption.
     * @property onlyAllowNfc if `true`, only NFC data transfer ([MdocConnectionMethodNfcV2]) will be used,
     *   otherwise the connection method is chosen according to [handoverPreferredOrder].
     * @property handoverPreferredOrder a list of the preferred order for which kind of
     *   [MdocConnectionMethod] to select during handover when [onlyAllowNfc] is `false`.
     * @property transportOptions the [MdocTransportOptions] to use for newly created connections.
     * @property transportFactory the [MdocTransportFactory] to use for creating transports.
     * @property capabilities the capabilities to convey to the mdoc reader.
     */
    data class Settings(
        val source: PresentmentSource,
        val promptModel: PromptModel,
        val presentmentModel: PresentmentModel?,
        val launchUi: (() -> Unit)? = null,
        val sessionEncryptionCurve: EcCurve,
        val onlyAllowNfc: Boolean,
        val handoverPreferredOrder: List<String> = emptyList(),
        val transportOptions: MdocTransportOptions,
        val transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
        val capabilities: Map<Capability, DataItem> = mapOf(
            Capability.READER_AUTH_ALL_SUPPORT to true.toDataItem(),
            Capability.EXTENDED_REQUEST_SUPPORT to true.toDataItem()
        )
    )

    /**
     * Must be implemented by the application to specify its preferences/settings for NFC engagement.
     *
     * Note that this is called after the NFC tap has been detected but before any messages are sent. As such
     * it's of paramount importance that this completes quickly because the NFC tag reader only stays in the
     * field for so long. Every millisecond literally counts, and it's very likely the application is cold-starting
     * so be mindful of doing expensive initializations here.
     *
     * @return a [Settings] object.
     */
    abstract suspend fun getSettings(): Settings

    override fun start() {
        Logger.i(TAG, "start")
        super.start()
    }

    @Volatile
    private var pendingEngagementDeviceKey: EcPrivateKey? = null

    override fun stop() {
        Logger.i(TAG, "stop")
        super.stop()
        val currentEngagement = engagement
        if (currentEngagement != null) {
            currentEngagement.processDeactivated(NfcDeactivationReason.LINK_LOSS)
            if (activeEngagement === currentEngagement) {
                activeEngagement = null
                hybridTransport?.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)
            }
            engagement = null
        }
        pendingEngagementDeviceKey?.close()
        pendingEngagementDeviceKey = null
        listenForCancellationFromUiJob?.cancel()
        listenForCancellationFromUiJob = null
    }

    @Volatile
    private var engagementStarted = false
    @Volatile
    private var engagementComplete = false

    private suspend fun startEngagement() {
        Logger.i(TAG, "startEngagement")

        // Note: Every millisecond literally counts here because we're handling a
        // NFC tap and users tend to remove their phone from the reader really fast. So
        // log how much time the application takes to give us settings.
        //
        val t0 = Clock.System.now()
        val settings = getSettings()
        val t1 = Clock.System.now()
        Logger.i(TAG, "Settings provided by application in ${(t1 - t0).inWholeMilliseconds} ms")

        pendingEngagementDeviceKey?.close()
        val eDeviceKey = Crypto.createEcPrivateKey(settings.sessionEncryptionCurve)
        pendingEngagementDeviceKey = eDeviceKey
        val timeStarted = Clock.System.now()

        val currentJob = transactionJob
        if (currentJob != null && !currentJob.isActive && !currentJob.isCompleted) {
            currentJob.join()
        }
        if (transactionJob?.isActive != true) {
            settings.presentmentModel?.setConnecting()
        }

        listenForCancellationFromUiJob?.cancel()
        listenForCancellationFromUiJob = serviceScope.launch(Dispatchers.Default) {
            settings.presentmentModel?.state?.collect { state ->
                if (state == PresentmentModel.State.CanceledByUser) {
                    engagementComplete = true
                    transactionJob?.cancel()
                }
            }
        }

        lateinit var currentEngagement: MdocNfcV2EngagementHelper
        currentEngagement = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover ->
                // OK, we're done with engagement, and we're communicating with a bona fide ISO/IEC 18013-5 Second
                // Edition reader capable of NFCv2. Let the user know and launch a new job to start the transaction.
                //
                vibrateSuccess()

                if (pendingEngagementDeviceKey === eDeviceKey) {
                    pendingEngagementDeviceKey = null
                }
                activeEngagement = currentEngagement

                val currentParams = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover
                )

                val isNfcOnly = connectionMethod is MdocConnectionMethodNfcV2

                if (isNfcOnly &&
                    hybridTransport?.isNfcOnly == true &&
                    transactionJob?.isActive == true &&
                    activeEngagementParamsFlow != null
                ) {
                    Logger.i(TAG, "Re-tap detected during active presentment. Updating engagement parameters.")
                    val previousParams = activeEngagementParamsFlow?.value
                    activeEngagementParamsFlow?.value = currentParams
                    if (previousParams != null && previousParams.eDeviceKey !== currentParams.eDeviceKey) {
                        previousParams.eDeviceKey.close()
                    }
                    return@MdocNfcV2EngagementHelper
                }

                val previousTransactionJob = transactionJob
                previousTransactionJob?.cancel(TransactionSupersededException())

                val paramsFlow = MutableStateFlow(currentParams)
                activeEngagementParamsFlow = paramsFlow
                activeDeviceRequest = null

                lateinit var newTransport: NfcHybridTransportMdoc
                newTransport = NfcHybridTransportMdoc(
                    sendMessageViaNfc = { message ->
                        if (hybridTransport === newTransport) {
                            activeEngagement?.let {
                                it.sendMessage(message)
                                true
                            } ?: false
                        } else {
                            false
                        }
                    }
                )
                if (!isNfcOnly) {
                    newTransport.setExpectTransport(true, connectionMethod)
                }
                hybridTransport = newTransport
                onHybridTransportCreated?.invoke(newTransport)

                // We launch transactionJob in a new detached scope so it survives both
                // NFC deactivation (the reader moving away) and the Service's onDestroy
                // (as the transaction may continue over BLE and wait for UI consent).
                transactionJob = CoroutineScope(Dispatchers.Default + settings.promptModel).launch {
                    startTransaction(
                        previousTransactionJob = previousTransactionJob,
                        timeStarted = timeStarted,
                        transport = newTransport,
                        settings = settings,
                        connectionMethod = connectionMethod,
                        engagementParamsFlow = paramsFlow,
                    )
                }
            },
            onMessageReceived = { message ->
                if (hybridTransport?.isNfcOnly == true) {
                    try {
                        val readerKey = SessionEncryption.getEReaderKey(message.toByteArray())
                        activeEngagementParamsFlow?.value?.let { current ->
                            val updatedParams = current.copy(eReaderKey = readerKey.publicKey)
                            activeEngagementParamsFlow?.value = updatedParams

                            val currentActiveRequest = activeDeviceRequest
                            if (currentActiveRequest != null) {
                                val sessionTranscript = buildCborArray {
                                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(updatedParams.deviceEngagement))))
                                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerKey.publicKey.toCoseKey().toDataItem()))))
                                    add(updatedParams.handover)
                                }
                                val (encodedDeviceRequest, _) = SessionEncryption(
                                    role = MdocRole.MDOC,
                                    eSelfKey = updatedParams.eDeviceKey.duplicate(),
                                    remotePublicKey = readerKey.publicKey,
                                    encodedSessionTranscript = Cbor.encode(sessionTranscript)
                                ).use { sessionEncryption ->
                                    sessionEncryption.decryptMessage(message.toByteArray())
                                }
                                if (encodedDeviceRequest != null) {
                                    val newDeviceRequest = DeviceRequest.fromDataItem(Cbor.decode(encodedDeviceRequest))
                                    if (!newDeviceRequest.isStructurallyEquivalent(currentActiveRequest)) {
                                        Logger.iCbor(TAG, "Current active request", currentActiveRequest.toDataItem())
                                        Logger.iCbor(TAG, "New request", newDeviceRequest.toDataItem())
                                        Logger.w(TAG, "Incoming DeviceRequest is NOT structurally equivalent to active request. Killing old session.")
                                        transactionJob?.cancel()
                                        activeDeviceRequest = null
                                        hybridTransport = null
                                        activeEngagement?.sendMessage(
                                            ByteString(
                                                SessionEncryption.encodeStatus(
                                                    Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                                                )
                                            )
                                        )
                                        return@MdocNfcV2EngagementHelper
                                    } else {
                                        Logger.i(TAG, "Incoming DeviceRequest IS structurally equivalent to active request.")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Not a SessionEstablishment message or decryption failed, ignore
                    }
                }
                hybridTransport?.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                // Engagement failed. This can happen if a NDEF tag reader - for example another unlocked
                // Android device - is reading this device. So we really don't want any user-visible side effects
                // here such as showing an error or vibrating the phone.
                //
                engagementComplete = true
                if (pendingEngagementDeviceKey === eDeviceKey) {
                    pendingEngagementDeviceKey?.close()
                    pendingEngagementDeviceKey = null
                }
                if (transactionJob?.isActive != true) {
                    val failureTelemetry = PresentmentTelemetry(
                        protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
                        engagementType = EngagementType.NFC_CONCURRENT_CHANNEL_ENGAGEMENT,
                        outcome = PresentmentOutcome.NFC_ENGAGEMENT_FAILED,
                        durationTotal = Clock.System.now() - timeStarted,
                    )
                    settings.presentmentModel?.setCompleted(error, failureTelemetry)
                }
                Logger.w(TAG, "Engagement failed. Maybe this wasn't an ISO mdoc reader.", error)
            },
            handoverPicker = { connectionMethods ->
                if (settings.onlyAllowNfc) {
                    connectionMethods.find { it is MdocConnectionMethodNfcV2 }!!
                } else {
                    for (prefix in settings.handoverPreferredOrder) {
                        for (connectionMethod in connectionMethods) {
                            if (connectionMethod.toString().startsWith(prefix)) {
                                return@MdocNfcV2EngagementHelper connectionMethod
                            }
                        }
                    }
                    return@MdocNfcV2EngagementHelper connectionMethods.first()
                }
            },
            capabilities = settings.capabilities
        )
        engagement = currentEngagement
    }

    private suspend fun startTransaction(
        previousTransactionJob: Job?,
        timeStarted: Instant,
        transport: NfcHybridTransportMdoc,
        settings: Settings,
        connectionMethod: MdocConnectionMethod,
        engagementParamsFlow: StateFlow<EngagementParams>,
    ) {
        val transactionJobContext = currentCoroutineContext()
        val isNfcOnly = connectionMethod is MdocConnectionMethodNfcV2
        var enteredPresentment = false
        var waitForTransportJob: Job? = null
        var uiCancelJob: Job? = null
        var nfcConnectedJob: Job? = null
        var lastTelemetry: PresentmentTelemetry? = null

        try {
            previousTransactionJob?.let { prevJob ->
                Logger.i(TAG, "Waiting for previous active transactionJob to finish cleanup")
                prevJob.join()
            }

            settings.presentmentModel?.setNfcOnly(isNfcOnly)

            settings.launchUi?.invoke()

            val engagementDuration = Clock.System.now() - timeStarted
            val initialParams = engagementParamsFlow.value
            val eDeviceKey = initialParams.eDeviceKey

            uiCancelJob = CoroutineScope(transactionJobContext).launch {
                settings.presentmentModel?.state?.collect { state ->
                    if (state == PresentmentModel.State.CanceledByUser) {
                        transactionJobContext.cancel(CancellationException("Presentment was cancelled by user"))
                    }
                }
            }

            nfcConnectedJob = if (isNfcOnly) {
                CoroutineScope(transactionJobContext).launch {
                    transport.isNfcConnected.collect { connected ->
                        settings.presentmentModel?.setNfcConnected(connected)
                    }
                }
            } else {
                null
            }

            val preselectedDocuments = settings.presentmentModel?.documentsSelected?.value ?: emptyList()
            settings.presentmentModel?.setConnecting()

            transport.open(eDeviceKey.publicKey)

            if (isNfcOnly) {
                transport.setExpectTransport(false)
            } else {
                transport.setExpectTransport(true, connectionMethod)
                // Wait for the non-NFC transport in a coroutine so we are not blocking
                // initiating presentment....
                waitForTransportJob = CoroutineScope(transactionJobContext).launch(Dispatchers.Default) {
                    var secondaryTransport: MdocTransport? = null
                    var handedOffToHybrid = false
                    try {
                        val createdTransport = settings.transportFactory.createTransport(
                            connectionMethod = connectionMethod,
                            role = MdocRole.MDOC,
                            options = settings.transportOptions
                        )
                        secondaryTransport = createdTransport
                        createdTransport.open(eSenderKey = eDeviceKey.publicKey)
                        transport.setTransport(createdTransport)
                        handedOffToHybrid = true

                        // Monitor the secondary transport
                        createdTransport.state.collect { state ->
                            if (state == MdocTransport.State.FAILED || state == MdocTransport.State.CLOSED) {
                                transactionJobContext.cancel(
                                    CancellationException(
                                        "Secondary transport $state",
                                        MdocTransportException("Secondary transport $state")
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Error opening non-NFC transport", e)
                        transport.addWarning(TransportWarning(TransportWarningCode.NFC_SECONDARY_TRANSPORT_FAILED))
                    } finally {
                        if (!handedOffToHybrid) {
                            withContext(NonCancellable) {
                                try {
                                    secondaryTransport?.close()
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.w(TAG, "Error closing unattached secondary transport", e)
                                }
                            }
                        }
                    }
                }
            }

            enteredPresentment = true
            Iso18013Presentment(
                transport = transport,
                engagementParams = engagementParamsFlow,
                source = settings.source,
                keyAgreementPossible = listOf(eDeviceKey.curve),
                preselectedDocuments = preselectedDocuments,
                insertSequenceNumbers = true,
                onWaitingForRequest = { settings.presentmentModel?.setWaitingForReader() },
                onWaitingForUserInput = { settings.presentmentModel?.setWaitingForUserInput() },
                onDocumentsInFocus = { documents ->
                    settings.presentmentModel?.setDocumentsSelected(selectedDocuments = documents)
                },
                onSendingResponse = { settings.presentmentModel?.setSending() },
                onDeviceRequest = { deviceRequest ->
                    activeDeviceRequest = deviceRequest
                },
                onTelemetry = { lastTelemetry = it },
            )
            settings.presentmentModel?.setCompleted(null, lastTelemetry)
        } catch (e: Exception) {
            if (e is TransactionSupersededException) {
                Logger.i(TAG, "Transaction was superseded by a new NFC tap")
            } else {
                Logger.w(TAG, "Caught error while performing 18013-5 transaction", e)
                val resolvedTelemetry = lastTelemetry ?: PresentmentTelemetry(
                    protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
                    engagementType = EngagementType.NFC_CONCURRENT_CHANNEL_ENGAGEMENT,
                    outcome = classifyPresentmentException(e, waitingForConnection = !enteredPresentment),
                    durationTotal = Clock.System.now() - timeStarted,
                    transportStats = transport.stats,
                )
                val reportedError = if (e is CancellationException) {
                    generateSequence(e.cause) { it.cause }
                        .firstOrNull { it !is CancellationException }
                        ?: PresentmentCanceledException("Presentment was cancelled")
                } else {
                    e
                }
                settings.presentmentModel?.setCompleted(reportedError, resolvedTelemetry)
            }
        } finally {
            withContext(NonCancellable) {
                uiCancelJob?.cancel()
                nfcConnectedJob?.cancel()
                listenForCancellationFromUiJob?.cancel()
                listenForCancellationFromUiJob = null
                waitForTransportJob?.let {
                    it.cancel()
                    it.join()
                }
                if (!enteredPresentment) {
                    try {
                        engagementParamsFlow.value.eDeviceKey.close()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Error closing eDeviceKey", e)
                    }
                    try {
                        transport.close()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Error closing hybrid transport", e)
                    }
                }
                if (activeEngagementParamsFlow === engagementParamsFlow) {
                    activeEngagementParamsFlow = null
                    activeDeviceRequest = null
                }
                if (hybridTransport === transport) {
                    hybridTransport = null
                }
            }
        }
    }

    private fun cancelEngagementJobs() {
        listenForCancellationFromUiJob?.cancel()
        listenForCancellationFromUiJob = null
    }

    private val firstCommandResponse = ResponseApdu(
        status = Nfc.RESPONSE_STATUS_SUCCESS,
        payload = ByteString(Cbor.encode(buildCborMap {
            put(0, 65536L /* apduCommandMaxSize */)
        }))
    )

    private var numApdusReceived = 0
    private var firstCommandApdu: CommandApdu? = null

    override suspend fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu {
        if (commandApdu.isApplicationSelect) {
            engagementComplete = false
            if (numApdusReceived > 0) {
                Logger.i(TAG, "Received SELECT APPLICATION while engagement already in progress; resetting for new engagement")
                engagement?.processDeactivated(NfcDeactivationReason.DESELECTED)
                if (activeEngagement === engagement) {
                    activeEngagement = null
                }
                engagement = null
                pendingEngagementDeviceKey?.close()
                pendingEngagementDeviceKey = null
                engagementStarted = false
                cancelEngagementJobs()
                numApdusReceived = 0
            }
        } else if (engagementComplete) {
            Logger.w(TAG, "Engagement complete but received APDU $commandApdu")
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS)
        }

        if (numApdusReceived == 0) {
            numApdusReceived = 1
            firstCommandApdu = commandApdu
            return firstCommandResponse
        }

        if (!engagementStarted) {
            engagementStarted = true
            startEngagement()
        }
        try {
            engagement?.let {
                // Replay the first APDU
                if (numApdusReceived++ == 1) {
                    val responseApdu = it.processApdu(firstCommandApdu!!)
                    if (responseApdu != firstCommandResponse) {
                        Logger.w(TAG, "Expected response $firstCommandResponse to SELECT APPLICATION, " +
                                "got $responseApdu")
                    }
                }
                return it.processApdu(commandApdu)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing APDU in MdocNfcV2EngagementHelper", e)
        }
        return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS)
    }

    override suspend fun processDeactivated(reason: NfcDeactivationReason) {
        Logger.i(TAG, "processDeactivated: reason=$reason")
        try {
            val currentEngagement = engagement
            currentEngagement?.processDeactivated(reason)
            hybridTransport?.onNfcDeactivated(reason)

            // The service might be reused for the next tap. So reset everything so next time
            // processCommandApdu() is called we'll start processing a new engagement.
            if (activeEngagement === currentEngagement) {
                activeEngagement = null
            }
            engagement = null
            pendingEngagementDeviceKey?.close()
            pendingEngagementDeviceKey = null
            engagementStarted = false
            engagementComplete = false
            numApdusReceived = 0

            cancelEngagementJobs()
            if (transactionJob?.isActive != true) {
                hybridTransport = null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing deactivation event in MdocNfcV2EngagementHelper", e)
        }
    }
}
