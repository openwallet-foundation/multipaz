package org.multipaz.compose.mdoc

import android.app.Activity
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.context.initializeApplication
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.nfc.MdocNfcEngagementHelper
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentCanceled
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlin.time.Duration

/**
 * Base class for implementing NFC engagement according to ISO/IEC 18013-5:2021.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 * for binding to the NDEF Type 4 tag AID (D2760000850101).
 *
 * See `ComposeWallet` in [Multipaz Samples](https://github.com/openwallet-foundation/multipaz-samples)
 * for an example.
 */
abstract class MdocNdefService: HostApduService() {
    companion object {
        private val TAG = "MdocNdefService"
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

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy")
        super.onDestroy()
        engagementJob?.cancel()
    }

    // A job started when the reader has selected us and used for establishing
    // NFC engagement. Runs until NFC engagement completes successfully or fails.
    private var engagementJob: Job? = null

    // A job started when the reader has selected us and used for listening for
    // a signal from the UI that it wants to cancel.
    private var listenForCancellationFromUiJob: Job? = null

    // A job started after NFC engagement completes successfully and
    // runs until the remote reader disconnects.
    private var transactionJob: Job? = null

    private var engagement: MdocNfcEngagementHelper? = null

    // Channel used for bouncing data from processCommandApdu() and onDeactivated() to engagementJob coroutine.
    private val channel = Channel<Data>(Channel.Factory.UNLIMITED)

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
     * @property staticHandoverBleCentralClientModeEnabled `true` if mdoc BLE Central Client mode should be offered
     *   when using NFC static handover.
     * @property staticHandoverBlePeripheralServerModeEnabled `true` if mdoc BLE Peripheral Server mode should be
     *   offered when using NFC static handover.
     * @property staticHandoverNfcDataTransferEnabled `true` if NFC data transfer should be offered when using NFC
     *   static handover
     * @property transportOptions the [MdocTransportOptions] to use for newly created connections.
     */
    data class Settings(
        val source: PresentmentSource,
        val promptModel: PromptModel,
        val presentmentModel: PresentmentModel?,
        val activityClass: Class<out Activity>?,
        val sessionEncryptionCurve: EcCurve,

        val useNegotiatedHandover: Boolean,
        val negotiatedHandoverPreferredOrder: List<String>,

        val staticHandoverBleCentralClientModeEnabled: Boolean,
        val staticHandoverBlePeripheralServerModeEnabled: Boolean,
        val staticHandoverNfcDataTransferEnabled: Boolean,

        val transportOptions: MdocTransportOptions
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

    override fun onCreate() {
        Logger.i(TAG, "onCreate")
        super.onCreate()

        initializeApplication(applicationContext)

        engagement = null
        transactionJob = null

        // Start a coroutine on an I/O thread for handling incoming APDUs and deactivation events
        // from the OS in processCommandApdu() and onDeactivated() overrides. This is so we can
        // use suspend functions.
        //
        engagementJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val data = channel.receive()
                when (data) {
                    is CommandApduData -> {
                        processCommandApdu(data.commandApdu)?.let { responseApdu ->
                            sendResponseApdu(responseApdu.encode())
                        }
                    }
                    is DeactivatedData -> {
                        processDeactivated(data.reason)
                    }
                }
            }
        }
    }

    private var engagementStarted = false
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

        val eDeviceKey = Crypto.createEcPrivateKey(settings.sessionEncryptionCurve)
        val timeStarted = Clock.System.now()

        listenForCancellationFromUiJob = CoroutineScope(Dispatchers.IO).launch {
            settings.presentmentModel?.state?.collect { state ->
                if (state == PresentmentModel.State.CanceledByUser) {
                    engagementJob?.cancel()
                    engagementJob = null
                    transactionJob?.cancel()
                    transactionJob = null
                    listenForCancellationFromUiJob?.cancel()
                    listenForCancellationFromUiJob = null
                }
            }
        }

        settings.presentmentModel?.setConnecting()

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
            val bleUuid = UUID.Companion.randomUUID()
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
                // Start the activity and also launch a new job for handling the transaction...
                //
                //engagementComplete = true
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

                transactionJob = CoroutineScope(Dispatchers.IO + settings.promptModel).launch {
                    val duration = Clock.System.now() - timeStarted
                    startTransaction(
                        settings = settings,
                        connectionMethods = connectionMethods,
                        encodedDeviceEngagement = encodedDeviceEngagement,
                        handover = handover,
                        eDeviceKey = eDeviceKey,
                        engagementDuration = duration
                    )
                }
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
            staticHandoverMethods = staticHandoverConnectionMethods,
            negotiatedHandoverPicker = negotiatedHandoverPicker
        )
    }

    private suspend fun startTransaction(
        settings: Settings,
        connectionMethods: List<MdocConnectionMethod>,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        eDeviceKey: EcPrivateKey,
        engagementDuration: Duration,
    ) {
        Logger.i(TAG, "startEngagement - advertising and waiting for connection")
        val transports = connectionMethods.advertise(
            role = MdocRole.MDOC,
            transportFactory = MdocTransportFactory.Default,
            options = settings.transportOptions,
        )
        val transport = transports.waitForConnection(
            eSenderKey = eDeviceKey.publicKey,
        )

        try {
            settings.presentmentModel?.setConnecting()
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                handover = handover,
                source = settings.source,
                keyAgreementPossible = listOf(eDeviceKey.curve),
                onWaitingForRequest = { settings.presentmentModel?.setWaitingForReader() },
                onWaitingForUserInput = { settings.presentmentModel?.setWaitingForUserInput() },
                onDocumentsInFocus = { documents ->
                    settings.presentmentModel?.setDocumentsSelected(selectedDocuments = documents)
                },
                onSendingResponse = { settings.presentmentModel?.setSending() }
            )
            settings.presentmentModel?.setCompleted(null)
        } catch (e: Throwable) {
            Logger.w(TAG, "Caught error while performing 18013-5 transaction", e)
            if (e is CancellationException) {
                settings.presentmentModel?.setCompleted(PresentmentCanceled("Presentment was cancelled"))
            } else {
                settings.presentmentModel?.setCompleted(e)
            }
        } finally {
            listenForCancellationFromUiJob?.cancel()
            listenForCancellationFromUiJob = null
        }
    }

    private var numApdusReceived = 0
    private var firstCommandApdu: CommandApdu? = null

    // Called by coroutine running in I/O thread, see onCreate() for details
    private suspend fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu? {
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
                                " got ${responseApdu}")
                    }
                }
                val responseApdu = it.processApdu(commandApdu)
                return responseApdu
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "Error processing APDU in MdocNfcEngagementHelper", e)
        }
        return null
    }

    // Called by coroutine running in I/O thread, see onCreate() for details
    private suspend fun processDeactivated(reason: Int) {
        try {
            engagement?.processDeactivated(reason)
        } catch (e: Throwable) {
            Logger.e(TAG, "Error processing deactivation event in MdocNfcEngagementHelper", e)
        }
    }

    // Called by OS when an APDU arrives
    override fun processCommandApdu(encodedCommandApdu: ByteArray, extras: Bundle?): ByteArray? {
        // Bounce the APDU to processCommandApdu() above via the coroutine in I/O thread set up in onCreate()
        val commandApdu = CommandApdu.decode(encodedCommandApdu)
        if (!engagementComplete) {
            val unused = channel.trySend(CommandApduData(commandApdu))
        } else {
            Logger.w(TAG, "Engagement complete but received APDU $commandApdu")
        }
        return null
    }

    // Called by OS when NFC tag reader deactivates
    override fun onDeactivated(reason: Int) {
        Logger.i(TAG, "onDeactivated: reason=$reason")
        // Bounce the event to processDeactivated() above via the coroutine in I/O thread set up in onCreate()
        if (!engagementComplete) {
            val unused = channel.trySend(DeactivatedData(reason))
        }

        // Android might reuse this service for the next tap. That is, we can't rely on onDestroy()
        // firing right after this, then onCreate(). So reset everything so next time processCommandApdu()
        // is called we're ready to go with a new engagement...
        engagement = null
        engagementStarted = false
        engagementComplete = false
        numApdusReceived = 0

    }
}