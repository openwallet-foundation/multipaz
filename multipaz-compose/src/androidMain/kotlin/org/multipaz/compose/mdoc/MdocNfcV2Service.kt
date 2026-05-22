package org.multipaz.compose.mdoc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.context.initializeApplication
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.nfc.MdocNfcV2EngagementHelper
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Base class for implementing NFCv2 engagement according to ISO/IEC 18013 Second Edition.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 * for binding to the appropriate AID (A0000002480401).
 *
 * @property applicationContext the [Context], passed by [CombinedNfcService].
 * @property sendResponse a function to send a response APDU via [CombinedNfcService].
 */
abstract class MdocNfcV2Service(
    applicationContext: Context,
    sendResponse: (ByteArray) -> Unit
) : NfcApduService(applicationContext, sendResponse) {
    companion object {
        private const val TAG = "MdocNfcV2Service"
    }

    private fun vibrate(pattern: List<Int>) {
        val vibrator = ContextCompat.getSystemService(applicationContext, Vibrator::class.java)
        val vibrationEffect = VibrationEffect.createWaveform(pattern.map { it.toLong() }.toLongArray(), -1)
        vibrator?.vibrate(vibrationEffect)
    }

    private fun vibrateError() {
        vibrate(listOf(0, 500))
    }

    private fun vibrateSuccess() {
        vibrate(listOf(0, 100, 50, 100))
    }

    // A job started when onCreate is called and used for dispatching APDUs and onDeactivated events
    // to a suspend-world. Runs until onDestroy is called.
    private var dispatchJob: Job? = null

    // A job started when the reader has selected us and used for listening for
    // a signal from the UI that it wants to cancel.
    private var listenForCancellationFromUiJob: Job? = null

    // A job started when we're waiting for a connection on the non-NFC transport.
    private var waitForTransportJob: Job? = null

    // A job started after NFC engagement completes successfully and
    // runs until the remote reader disconnects.
    private var transactionJob: Job? = null

    @Volatile
    private var engagement: MdocNfcV2EngagementHelper? = null

    // Channel used for bouncing data from processCommandApdu() and onDeactivated() to engagementJob coroutine.
    private val dispatchChannel = Channel<Data>(Channel.UNLIMITED)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private sealed class Data

    private data class CommandApduData(
        val commandApdu: CommandApdu
    ): Data()

    private data class DeactivatedData(
        val reason: Int
    ): Data()

    /**
     * Settings provided by the application for how to configure NFC engagement.
     *
     * @property source the [PresentmentSource] to use as the source of truth for what to present.
     * @property promptModel the [PromptModel] to use.
     * @property activityClass the activity to launch or `null` to not launch an activity.
     * @property sessionEncryptionCurve the Elliptic Curve Cryptography curve to use for session encryption.
     * @property useNegotiatedHandover if `true` NFC negotiated handover will be used, otherwise NFC static handover.
     * @property negotiatedHandoverPreferredOrder a list of the preferred order for which kind of
     *   [org.multipaz.mdoc.transport.MdocTransport] to create when using NFC negotiated handover.
     * @property transportOptions the [MdocTransportOptions] to use for newly created connections.
     * @property capabilities the capabilities to convey to the mdoc reader.
     */
    data class Settings(
        val source: PresentmentSource,
        val promptModel: PromptModel,
        val presentmentModel: PresentmentModel?,
        val activityClass: Class<out Activity>?,
        val sessionEncryptionCurve: EcCurve,
        val useNegotiatedHandover: Boolean,
        val negotiatedHandoverPreferredOrder: List<String>,
        val transportOptions: MdocTransportOptions,
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

    override fun onCreate() {
        Logger.i(TAG, "onCreate")
        super.onCreate()

        initializeApplication(applicationContext)

        // Start a coroutine on an I/O thread for handling incoming APDUs and deactivation events
        // from the OS in processCommandApdu() and onDeactivated() overrides. This is so we can
        // use suspend functions.
        //
        dispatchJob = serviceScope.launch(Dispatchers.IO) {
            for (data in dispatchChannel) {
                try {
                    when (data) {
                        is CommandApduData -> {
                            processCommandApdu(data.commandApdu)?.let { responseApdu ->
                                try {
                                    sendResponseApdu(responseApdu.encode())
                                } catch (e: Exception) {
                                    Logger.w(TAG, "Error sending response APDU", e)
                                }
                            }
                        }
                        is DeactivatedData -> {
                            processDeactivated(data.reason)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        if (!isActive) {
                            // The whole service/job is being shut down, let the exception propagate so the loop dies.
                            throw e
                        }
                        // Only the current sub-task (APDU processing) was aborted, keep the loop alive for the next tap.
                        Logger.i(TAG, "dispatchJob: APDU processing cancelled (likely due to deactivation)")
                    } else {
                        Logger.e(TAG, "Error processing data from channel", e)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy")
        super.onDestroy()
        serviceScope.cancel()
        dispatchJob = null
        dispatchChannel.close()
    }

    @Volatile
    private var engagementStarted = false
    @Volatile
    private var engagementComplete = false
    @Volatile
    private var hybridTransport: NfcHybridTransportMdoc? = null

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

        val eDeviceKey = Crypto.createEcPrivateKey(settings.sessionEncryptionCurve)
        val timeStarted = Clock.System.now()

        listenForCancellationFromUiJob = CoroutineScope(Dispatchers.IO).launch {
            settings.presentmentModel?.state?.collect { state ->
                if (state == PresentmentModel.State.CanceledByUser) {
                    cancelEngagementJobs()
                    transactionJob?.cancel()
                    transactionJob = null
                    waitForTransportJob?.cancel()
                    waitForTransportJob = null
                }
            }
        }

        settings.presentmentModel?.setConnecting()

        // TODO: Listen on methods _before_ starting the engagement helper so we can send the PSM
        //   for mdoc Peripheral Server mode when using NFC Static Handover.
        //
        engagement = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover ->
                // OK, we're done with engagement and we're communicating with a bona fide ISO/IEC 18013-5 Second
                // Edition reader capable of NFCv2. Start the activity and also launch a new job for handling the
                // transaction...
                //
                vibrateSuccess()

                if (settings.activityClass != null) {
                    val intent = Intent(applicationContext, settings.activityClass)
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_HISTORY or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    applicationContext.startActivity(intent)
                }

                hybridTransport = NfcHybridTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagement?.let {
                            it.sendMessage(message)
                            true
                        } ?: false
                    }
                )

                // We launch transactionJob in a new detached scope so it survives both
                // NFC deactivation (the reader moving away) and the Service's onDestroy
                // (as the transaction may continue over BLE and wait for UI consent).
                transactionJob = CoroutineScope(Dispatchers.IO + settings.promptModel).launch {
                    hybridTransport!!.open(eDeviceKey.publicKey)
                    val duration = Clock.System.now() - timeStarted
                    startTransaction(
                        transport = hybridTransport!!,
                        settings = settings,
                        connectionMethod = connectionMethod,
                        encodedDeviceEngagement = encodedDeviceEngagement,
                        handover = handover,
                        eDeviceKey = eDeviceKey,
                        engagementDuration = duration
                    )
                }
            },
            onMessageReceived = { message ->
                hybridTransport?.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                // Engagement failed. This can happen if a NDEF tag reader - for example another unlocked
                // Android device - is reading this device. So we really don't want any user-visible side-effects
                // here such as showing an error or vibrating the phone.
                //
                engagementComplete = true
                settings.presentmentModel?.setCompleted(error)
                Logger.w(TAG, "Engagement failed. Maybe this wasn't an ISO mdoc reader.", error)
            },
            negotiatedHandoverPicker = { connectionMethods ->
                if (settings.useNegotiatedHandover) {
                    for (prefix in settings.negotiatedHandoverPreferredOrder) {
                        for (connectionMethod in connectionMethods) {
                            if (connectionMethod.toString().startsWith(prefix)) {
                                return@MdocNfcV2EngagementHelper connectionMethod
                            }
                        }
                    }
                    return@MdocNfcV2EngagementHelper connectionMethods.first()
                } else {
                    connectionMethods.find { it is MdocConnectionMethodNfcV2 }!!
                }
            },
            capabilities = settings.capabilities
        )
    }

    private suspend fun startTransaction(
        transport: NfcHybridTransportMdoc,
        settings: Settings,
        connectionMethod: MdocConnectionMethod,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        eDeviceKey: EcPrivateKey,
        engagementDuration: Duration,
    ) {
        val transactionJobContext = currentCoroutineContext()
        if (connectionMethod is MdocConnectionMethodNfcV2) {
            transport.setExpectTransport(false)
            // Nothing to do
        } else {
            transport.setExpectTransport(true)
            // Wait for the non-NFC transport in a coroutine so we are not blocking
            // initiating presentment....
            waitForTransportJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    val negotiatedTransport = MdocTransportFactory.Default.createTransport(
                        connectionMethod = connectionMethod,
                        role = MdocRole.MDOC,
                        options = settings.transportOptions
                    )
                    negotiatedTransport.open(eSenderKey = eDeviceKey.publicKey)
                    transport.setTransport(negotiatedTransport)

                    // Monitor the secondary transport
                    launch {
                        negotiatedTransport.state.collect { state ->
                            if (state == MdocTransport.State.FAILED || state == MdocTransport.State.CLOSED) {
                                transactionJobContext.cancel(CancellationException("Secondary transport $state"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Error opening non-NFC transport", e)
                }
            }
        }

        try {
            settings.presentmentModel?.setConnecting()
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                handover = handover,
                source = settings.source,
                keyAgreementPossible = listOf(eDeviceKey.curve),
                insertSequenceNumbers = true,
                onWaitingForRequest = { settings.presentmentModel?.setWaitingForReader() },
                onWaitingForUserInput = { settings.presentmentModel?.setWaitingForUserInput() },
                onDocumentsInFocus = { documents ->
                    settings.presentmentModel?.setDocumentsSelected(selectedDocuments = documents)
                },
                onSendingResponse = { settings.presentmentModel?.setSending() }
            )
            settings.presentmentModel?.setCompleted(null)
        } catch (e: Exception) {
            Logger.w(TAG, "Caught error while performing 18013-5 transaction", e)
            if (e is CancellationException) {
                settings.presentmentModel?.setCompleted(PresentmentCanceledException("Presentment was cancelled"))
            } else {
                settings.presentmentModel?.setCompleted(e)
            }
        } finally {
            listenForCancellationFromUiJob?.cancel()
            listenForCancellationFromUiJob = null
        }
    }

    private fun cancelEngagementJobs() {
        listenForCancellationFromUiJob?.cancel()
        listenForCancellationFromUiJob = null
    }

    private val CommandApdu.isApplicationSelect: Boolean
        get() = ins == Nfc.INS_SELECT && p1 == Nfc.INS_SELECT_P1_APPLICATION

    private val firstCommandResponse = ResponseApdu(
        status = Nfc.RESPONSE_STATUS_SUCCESS,
        payload = ByteString(Cbor.encode(buildCborMap {
            put(0, 65536L /* apduCommandMaxSize */)
        }))
    )
    private var applicationSelectProcessed = false

    // Called by coroutine running in I/O thread, see onCreate() for details
    private suspend fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu? {
        // TODO: maybe need replay and super-fast response as in mdocReaderNfcHandover.kt function processCommandApdu()
        if (!engagementStarted) {
            engagementStarted = true
            startEngagement()
        }
        try {
            engagement?.let {
                val responseApdu = it.processApdu(commandApdu)
                if (commandApdu.isApplicationSelect && applicationSelectProcessed) {
                    Logger.i(TAG, "Skipping response to APPLICATION SELECT since it's already been processed")
                } else {
                    return responseApdu
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing APDU in MdocNfcV2EngagementHelper", e)
        }
        return null
    }

    // Called by coroutine running in I/O thread, see onCreate() for details
    private suspend fun processDeactivated(reason: Int) {
        try {
            engagement?.processDeactivated(reason)
            hybridTransport?.onNfcDeactivated(reason)

            // Android might reuse this service for the next tap. That is, we can't rely on onDestroy()
            // firing right after this, then onCreate(). So reset everything so next time the OS calls
            // processCommandApdu() we'll start processing a new engagement.
            //
            engagement = null
            engagementStarted = false
            engagementComplete = false
            hybridTransport = null

            cancelEngagementJobs()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing deactivation event in MdocNfcV2EngagementHelper", e)
        }
    }

    // Called by OS when an APDU arrives
    override fun processCommandApdu(encodedCommandApdu: ByteArray, extras: Bundle?): ByteArray? {
        // Bounce the APDU to processCommandApdu() above via the coroutine in I/O thread set up in onCreate().
        //
        // With Extended APDUs it's possible we get a partial APDU so gracefully handle if decoding fails. Simply
        // log and discard, we'll likely get hit with an onDeactivated call soon anyway.
        //
        val commandApdu = try {
            CommandApdu.decode(encodedCommandApdu)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Error decoding APDU", e)
            return null
        }
        if (!engagementComplete) {
            if (commandApdu.isApplicationSelect) {
                applicationSelectProcessed = true
                Logger.i(TAG, "Processing APPLICATION SELECT synchronously")
                val unused = dispatchChannel.trySend(CommandApduData(commandApdu))
                return firstCommandResponse.encode()
            } else {
                val unused = dispatchChannel.trySend(CommandApduData(commandApdu))
            }
        } else {
            Logger.w(TAG, "Engagement complete but received APDU $commandApdu")
        }
        return null
    }

    // Called by OS when NFC tag reader deactivates
    override fun onDeactivated(reason: Int) {
        Logger.i(TAG, "onDeactivated: reason=$reason")
        // Important: we must call this here to unblock dispatchJob if it is suspended in
        // engagement.processApdu() waiting for a response to send back to the reader.
        engagement?.processDeactivated(reason)
        // Bounce the event to processDeactivated() above via the coroutine in I/O thread set up in onCreate()
        val unused = dispatchChannel.trySend(DeactivatedData(reason))
    }
}