package org.multipaz.compose.mdoc

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
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
import org.multipaz.presentment.model.MdocPresentmentMechanism
import org.multipaz.presentment.model.PresentmentModel
import org.multipaz.presentment.model.PresentmentTimeout
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Base class for implementing NFC engagement according to ISO/IEC 18013-5:2021.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 * for binding to the NDEF Type 4 tag AID (D2760000850101).
 *
 * See the [MpzCmpWallet](https://github.com/davidz25/MpzCmpWallet) sample for an example.
 */
abstract class MdocNdefService: HostApduService() {
    companion object {
        private val TAG = "MdocNdefService"

        private var engagement: MdocNfcEngagementHelper? = null
        private var disableEngagementJob: Job? = null
        private var listenForCancellationFromUiJob: Job? = null

        /**
         * The [PresentmentModel] used for all ISO/IEC 18013-5:2021 presentments using NFC engagement.
         */
        val presentmentModel = PresentmentModel()
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
        commandApduListenJob?.cancel()
    }

    private var commandApduListenJob: Job? = null
    private val commandApduChannel = Channel<CommandApdu>(Channel.Factory.UNLIMITED)

    /**
     * Settings provided by the application for how to configure NFC engagement.
     *
     * @property sessionEncryptionCurve the Elliptic Curve Cryptography curve to use for session encryption.
     * @property allowMultipleRequests whether to allow multiple requests.
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
     * @property promptModel the [PromptModel] to use.
     * @property presentmentActivityClass the class of the activty to create for, must be derived
     *   from [MdocNfcPresentmentActivity].
     */
    data class Settings(
        val sessionEncryptionCurve: EcCurve,
        val allowMultipleRequests: Boolean,

        val useNegotiatedHandover: Boolean,
        val negotiatedHandoverPreferredOrder: List<String>,

        val staticHandoverBleCentralClientModeEnabled: Boolean,
        val staticHandoverBlePeripheralServerModeEnabled: Boolean,
        val staticHandoverNfcDataTransferEnabled: Boolean,

        val transportOptions: MdocTransportOptions,

        val promptModel: PromptModel,
        val presentmentActivityClass: Class<out MdocNfcPresentmentActivity>
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

        // Essentially, start a coroutine on an I/O thread for handling incoming APDUs
        commandApduListenJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val commandApdu = commandApduChannel.receive()
                val responseApdu = processCommandApdu(commandApdu)
                if (responseApdu != null) {
                    sendResponseApdu(responseApdu.encode())
                }
            }
        }
    }

    private var started = false

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

        disableEngagementJob?.cancel()
        disableEngagementJob = null
        listenForCancellationFromUiJob?.cancel()
        listenForCancellationFromUiJob = null

        val eDeviceKey = Crypto.createEcPrivateKey(settings.sessionEncryptionCurve)
        val timeStarted = Clock.System.now()

        presentmentModel.reset()
        presentmentModel.setPromptModel(settings.promptModel)
        presentmentModel.setConnecting()

        // The UI consuming [PresentationModel] - for example the [Presentment] composable in this library - may
        // have a cancel button which will trigger COMPLETED state when pressed. Need to listen for that.
        //
        listenForCancellationFromUiJob = presentmentModel.presentmentScope.launch {
            presentmentModel.state
                .collect { state ->
                    if (state == PresentmentModel.State.COMPLETED) {
                        disableEngagementJob?.cancel()
                        disableEngagementJob = null
                    }
                }
        }

        val intent = Intent(applicationContext, settings.presentmentActivityClass)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
        Logger.i(TAG, "startActivity on $intent")
        applicationContext.startActivity(intent)

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
                vibrateSuccess()
                val duration = Clock.System.now() - timeStarted
                listenOnMethods(
                    settings = settings,
                    connectionMethods = connectionMethods,
                    encodedDeviceEngagement = encodedDeviceEngagement,
                    handover = handover,
                    eDeviceKey = eDeviceKey,
                    engagementDuration = duration
                )
            },
            onError = { error ->
                Logger.w(TAG, "Engagement failed", error)
                error.printStackTrace()
                vibrateError()
                engagement = null
            },
            staticHandoverMethods = staticHandoverConnectionMethods,
            negotiatedHandoverPicker = negotiatedHandoverPicker
        )
    }

    private fun listenOnMethods(
        settings: Settings,
        connectionMethods: List<MdocConnectionMethod>,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        eDeviceKey: EcPrivateKey,
        engagementDuration: Duration,
    ) {
        presentmentModel.presentmentScope.launch {
            val transports = connectionMethods.advertise(
                role = MdocRole.MDOC,
                transportFactory = MdocTransportFactory.Default,
                options = settings.transportOptions,
            )
            val transport = transports.waitForConnection(
                eSenderKey = eDeviceKey.publicKey,
            )
            presentmentModel.setMechanism(
                MdocPresentmentMechanism(
                    transport = transport,
                    eDeviceKey = eDeviceKey,
                    encodedDeviceEngagement = encodedDeviceEngagement,
                    handover = handover,
                    engagementDuration = engagementDuration,
                    allowMultipleRequests = settings.allowMultipleRequests
                )
            )
            disableEngagementJob?.cancel()
            disableEngagementJob = null
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

        if (!started) {
            started = true
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
            Logger.e(TAG, "Error processing APDU in MdocNfcEngagementHandler", e)
            e.printStackTrace()
        }
        return null
    }

    // Called by OS when an APDU arrives
    override fun processCommandApdu(encodedCommandApdu: ByteArray, extras: Bundle?): ByteArray? {
        // Bounce the APDU to processCommandApdu() above via the coroutine in I/O thread set up in onCreate()
        commandApduChannel.trySend(CommandApdu.Companion.decode(encodedCommandApdu))
        return null
    }

    override fun onDeactivated(reason: Int) {
        Logger.i(TAG, "onDeactivated: reason=$reason")
        started = false
        numApdusReceived = 0
        // If the reader hasn't connected by the time NFC interaction ends, make sure we only
        // wait for a limited amount of time.
        if (presentmentModel.state.value == PresentmentModel.State.CONNECTING) {
            val timeout = 15.seconds
            Logger.i(TAG, "Reader hasn't connected at NFC deactivation time, scheduling $timeout timeout for closing")
            disableEngagementJob = CoroutineScope(Dispatchers.IO).launch {
                delay(timeout)
                if (presentmentModel.state.value == PresentmentModel.State.CONNECTING) {
                    presentmentModel.setCompleted(PresentmentTimeout("Reader didn't connect inside $timeout, closing"))
                }
                engagement = null
                disableEngagementJob = null
            }
        }
    }
}