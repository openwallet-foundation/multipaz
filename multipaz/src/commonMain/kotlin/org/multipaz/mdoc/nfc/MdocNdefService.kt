package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcApduRouter
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.ResponseApdu
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentOutcome
import org.multipaz.presentment.PresentmentProtocol
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.PresentmentTelemetry
import org.multipaz.presentment.classifyPresentmentException
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Base class for implementing NFC engagement according to ISO/IEC 18013-5:2021.
 *
 * Applications should subclass this and register it in [NfcApduRouter.buildServices]
 * for the NDEF Type 4 tag AID (`D2760000850101`).
 */
abstract class MdocNdefService : MdocNfcPresentmentService() {
    companion object {
        private const val TAG = "MdocNdefService"
    }

    @Volatile
    private var pendingEngagementDeviceKey: EcPrivateKey? = null

    override fun stop() {
        Logger.i(TAG, "stop")
        super.stop()
        engagement = null
        pendingEngagementDeviceKey?.close()
        pendingEngagementDeviceKey = null
        listenForCancellationFromUiJob?.cancel()
        listenForCancellationFromUiJob = null
    }

    // A job started when the reader has selected us and used for listening for
    // a signal from the UI that it wants to cancel during NFC engagement.
    private var listenForCancellationFromUiJob: Job? = null

    @Volatile
    private var engagement: MdocNfcEngagementHelper? = null

    /**
     * Settings provided by the application for how to configure NFC engagement.
     *
     * @property source the [PresentmentSource] to use as the source of truth for what to present.
     * @property promptModel the [PromptModel] to use.
     * @property presentmentModel the [PresentmentModel] to update during presentment, or `null`.
     * @property launchUi a callback invoked when handover completes to launch the presentment UI, or `null`.
     * @property sessionEncryptionCurve the Elliptic Curve Cryptography curve to use for session encryption.
     * @property useNegotiatedHandover if `true` NFC negotiated handover will be used, otherwise NFC static handover.
     * @property negotiatedHandoverPreferredOrder a list of the preferred order for which kind of
     *   [org.multipaz.mdoc.transport.MdocTransport] to create when using NFC negotiated handover.
     * @property staticHandoverBleCentralClientModeEnabled `true` if mdoc BLE Central Client mode should be offered
     *   when using NFC static handover.
     * @property staticHandoverBlePeripheralServerModeEnabled `true` if mdoc BLE Peripheral Server mode should be
     *   offered when using NFC static handover.
     * @property staticHandoverNfcDataTransferEnabled `true` if NFC data transfer should be offered when using NFC
     *   static handover
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

        val useNegotiatedHandover: Boolean,
        val negotiatedHandoverPreferredOrder: List<String>,

        val staticHandoverBleCentralClientModeEnabled: Boolean,
        val staticHandoverBlePeripheralServerModeEnabled: Boolean,
        val staticHandoverNfcDataTransferEnabled: Boolean,

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
     * field for so long. Every millisecond literally counts and it's very likely the application is cold-
     * starting so be mindful of doing expensive initializations here.
     *
     * @return a [Settings] object.
     */
    abstract suspend fun getSettings(): Settings

    override fun start() {
        Logger.i(TAG, "start")
        engagement = null
        super.start()
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
        listenForCancellationFromUiJob = serviceScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            settings.presentmentModel?.state?.collect { state ->
                if (state == PresentmentModel.State.CanceledByUser) {
                    engagementComplete = true
                    transactionJob?.cancel()
                }
            }
        }

        fun negotiatedHandoverPicker(connectionMethods: List<MdocConnectionMethod>): MdocConnectionMethod {
            Logger.i(TAG, "Negotiated Handover available methods: $connectionMethods")
            for (prefix in settings.negotiatedHandoverPreferredOrder) {
                for (connectionMethod in connectionMethods) {
                    if (connectionMethod.toString().startsWith(prefix)) {
                        Logger.i(TAG, "Using method $connectionMethod")
                        return connectionMethod
                    }
                }
            }
            Logger.i(TAG, "Using method ${connectionMethods.first()}")
            return connectionMethods.first()
        }

        val negotiatedHandoverPicker: ((connectionMethods: List<MdocConnectionMethod>) -> MdocConnectionMethod)? =
            if (settings.useNegotiatedHandover) {
                { connectionMethods -> negotiatedHandoverPicker(connectionMethods) }
            } else {
                null
            }

        var staticHandoverConnectionMethods: List<MdocConnectionMethod>? = null
        if (!settings.useNegotiatedHandover) {
            staticHandoverConnectionMethods = mutableListOf<MdocConnectionMethod>()
            val bleUuid = UUID.randomUUID()
            if (settings.staticHandoverBleCentralClientModeEnabled) {
                staticHandoverConnectionMethods.add(
                    MdocConnectionMethodBle(
                        supportsPeripheralServerMode = false,
                        supportsCentralClientMode = true,
                        peripheralServerModeUuid = null,
                        centralClientModeUuid = bleUuid,
                    )
                )
            }
            if (settings.staticHandoverBlePeripheralServerModeEnabled) {
                staticHandoverConnectionMethods.add(
                    MdocConnectionMethodBle(
                        supportsPeripheralServerMode = true,
                        supportsCentralClientMode = false,
                        peripheralServerModeUuid = bleUuid,
                        centralClientModeUuid = null,
                    )
                )
            }
            if (settings.staticHandoverNfcDataTransferEnabled) {
                staticHandoverConnectionMethods.add(
                    MdocConnectionMethodNfc(
                        commandDataFieldMaxLength = 0xffff,
                        responseDataFieldMaxLength = 0x10000
                    )
                )
            }
        }

        // TODO: Listen on methods _before_ starting the engagement helper so we can send the PSM
        //   for mdoc Peripheral Server mode when using NFC Static Handover.
        //
        engagement = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                // OK, we're done with engagement and we're communicating with a bona fide ISO/IEC 18013-5:2021 reader.
                // Let the user know and launch a new job to start the transaction.
                //
                vibrateSuccess()

                if (pendingEngagementDeviceKey === eDeviceKey) {
                    pendingEngagementDeviceKey = null
                }

                val previousTransactionJob = transactionJob
                previousTransactionJob?.cancel(TransactionSupersededException())

                val engagementCancelJob = listenForCancellationFromUiJob
                listenForCancellationFromUiJob = null

                // We launch transactionJob in a new detached scope so it survives both
                // NFC deactivation (the reader moving away) and the Service's onDestroy
                // (as the transaction may continue over BLE and wait for UI consent).
                transactionJob = CoroutineScope(Dispatchers.Default + settings.promptModel).launch {
                    startTransaction(
                        previousTransactionJob = previousTransactionJob,
                        engagementCancelJob = engagementCancelJob,
                        timeStarted = timeStarted,
                        settings = settings,
                        connectionMethods = connectionMethods,
                        encodedDeviceEngagement = encodedDeviceEngagement,
                        handover = handover,
                        eDeviceKey = eDeviceKey,
                    )
                }
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
                        engagementType = if (settings.useNegotiatedHandover) {
                            EngagementType.NFC_NEGOTIATED_HANDOVER
                        } else {
                            EngagementType.NFC_STATIC_HANDOVER
                        },
                        outcome = PresentmentOutcome.NFC_ENGAGEMENT_FAILED,
                        durationTotal = Clock.System.now() - timeStarted,
                    )
                    settings.presentmentModel?.setCompleted(error, failureTelemetry)
                }
                Logger.w(TAG, "Engagement failed. Maybe this wasn't an ISO mdoc reader.", error)
            },
            staticHandoverMethods = staticHandoverConnectionMethods,
            negotiatedHandoverPicker = negotiatedHandoverPicker,
            capabilities = settings.capabilities
        )
    }

    private suspend fun startTransaction(
        previousTransactionJob: Job?,
        engagementCancelJob: Job?,
        timeStarted: Instant,
        settings: Settings,
        connectionMethods: List<MdocConnectionMethod>,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        eDeviceKey: EcPrivateKey,
    ) {
        val context = currentCoroutineContext()
        var advertisedTransports: List<MdocTransport>? = null
        var connectedTransport: MdocTransport? = null
        var enteredPresentment = false
        var monitorJob: Job? = null
        var uiCancelJob: Job? = null
        var lastTelemetry: PresentmentTelemetry? = null
        var reportCompletion = false
        var completionError: Throwable? = null
        var completionTelemetry: PresentmentTelemetry? = null

        try {
            previousTransactionJob?.let { prevJob ->
                Logger.i(TAG, "Waiting for previous active transactionJob to finish cleanup")
                prevJob.join()
            }

            settings.launchUi?.invoke()

            val engagementDuration = Clock.System.now() - timeStarted
            val preselectedDocuments = settings.presentmentModel?.documentsSelected?.value ?: emptyList()
            settings.presentmentModel?.setConnecting()

            uiCancelJob = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
                settings.presentmentModel?.state?.collect { state ->
                    if (state == PresentmentModel.State.CanceledByUser) {
                        context.cancel(CancellationException("Presentment was cancelled by user"))
                    }
                }
            }
            engagementCancelJob?.cancel()

            Logger.i(TAG, "startEngagement - advertising and waiting for connection")
            val transports = connectionMethods.advertise(
                role = MdocRole.MDOC,
                transportFactory = settings.transportFactory,
                options = settings.transportOptions,
            )
            advertisedTransports = transports
            val transport = transports.waitForConnection(
                eSenderKey = eDeviceKey.publicKey,
            )
            connectedTransport = transport
            advertisedTransports = null

            monitorJob = CoroutineScope(context).launch {
                transport.state.collect { state ->
                    if (state == MdocTransport.State.FAILED || state == MdocTransport.State.CLOSED) {
                        context.cancel(
                            CancellationException(
                                "Transport $state",
                                MdocTransportException("Transport $state")
                            )
                        )
                    }
                }
            }

            enteredPresentment = true
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                handover = handover,
                source = settings.source,
                keyAgreementPossible = listOf(eDeviceKey.curve),
                preselectedDocuments = preselectedDocuments,
                onWaitingForRequest = { settings.presentmentModel?.setWaitingForReader() },
                onWaitingForUserInput = { settings.presentmentModel?.setWaitingForUserInput() },
                onDocumentsInFocus = { documents ->
                    settings.presentmentModel?.setDocumentsSelected(selectedDocuments = documents)
                },
                onSendingResponse = { settings.presentmentModel?.setSending() },
                onTelemetry = { lastTelemetry = it },
            )
            reportCompletion = true
            completionError = null
            completionTelemetry = lastTelemetry
        } catch (e: Exception) {
            if (e is TransactionSupersededException) {
                Logger.i(TAG, "Transaction was superseded by a new NFC tap")
            } else {
                Logger.w(TAG, "Caught error while performing 18013-5 transaction", e)
                val resolvedTelemetry = lastTelemetry ?: PresentmentTelemetry(
                    protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
                    engagementType = if (settings.useNegotiatedHandover) {
                        EngagementType.NFC_NEGOTIATED_HANDOVER
                    } else {
                        EngagementType.NFC_STATIC_HANDOVER
                    },
                    outcome = classifyPresentmentException(e, waitingForConnection = !enteredPresentment),
                    durationTotal = Clock.System.now() - timeStarted,
                    transportStats = (connectedTransport ?: advertisedTransports?.firstOrNull())?.stats,
                )
                val reportedError = if (e is CancellationException) {
                    generateSequence(e.cause) { it.cause }
                        .firstOrNull { it !is CancellationException }
                        ?: PresentmentCanceledException("Presentment was cancelled")
                } else {
                    e
                }
                reportCompletion = true
                completionError = reportedError
                completionTelemetry = resolvedTelemetry
            }
        } finally {
            withContext(NonCancellable) {
                monitorJob?.cancel()
                uiCancelJob?.cancel()
                engagementCancelJob?.cancel()
                if (!enteredPresentment) {
                    connectedTransport?.let {
                        try {
                            it.close()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Logger.w(TAG, "Error closing connected transport", e)
                        }
                    }
                    advertisedTransports?.forEach {
                        try {
                            it.close()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Logger.w(TAG, "Error closing advertised transport", e)
                        }
                    }
                    try {
                        eDeviceKey.close()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Error closing eDeviceKey", e)
                    }
                }
                if (reportCompletion) {
                    settings.presentmentModel?.setCompleted(completionError, completionTelemetry)
                }
            }
        }
    }

    private fun cancelEngagementJobs() {
        listenForCancellationFromUiJob?.cancel()
        listenForCancellationFromUiJob = null
    }

    private var numApdusReceived = 0
    private var firstCommandApdu: CommandApdu? = null

    override suspend fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu {
        if (commandApdu.isApplicationSelect) {
            engagementComplete = false
            if (numApdusReceived > 0) {
                Logger.i(TAG, "Received SELECT APPLICATION while engagement already in progress; resetting for new engagement")
                engagement?.processDeactivated(NfcDeactivationReason.DESELECTED)
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

        // Recent Android versions seems to want a super-fast response to the SELECT APPLICATION
        // command otherwise it may pick the Wallet Role owner instead. Observed this when using
        // Multipaz Test App on an iOS device to read from Multipaz Test App on an Android
        // device in the foregroup where Google Wallet is the Wallet Role Owner and has registered
        // for NDEF AID.
        //
        if (numApdusReceived == 0) {
            numApdusReceived = 1
            firstCommandApdu = commandApdu
            return ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS)
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
                    if (responseApdu != ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS)) {
                        Logger.w(TAG, "Expected response 9000 to SELECT APPLICATION, " +
                                " got $responseApdu")
                    }
                }
                return it.processApdu(commandApdu)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing APDU in MdocNfcEngagementHelper", e)
        }
        return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS)
    }

    override suspend fun processDeactivated(reason: NfcDeactivationReason) {
        Logger.i(TAG, "processDeactivated: reason=$reason")
        try {
            engagement?.processDeactivated(reason)

            // The service might be reused for the next tap. So reset everything so next time
            // processCommandApdu() is called we're ready to go with a new engagement.
            engagement = null
            pendingEngagementDeviceKey?.close()
            pendingEngagementDeviceKey = null
            engagementStarted = false
            engagementComplete = false
            numApdusReceived = 0

            cancelEngagementJobs()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing deactivation event in MdocNfcEngagementHelper", e)
        }
    }
}
