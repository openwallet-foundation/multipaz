package org.multipaz.legacytestapp

import android.content.Context
import android.content.Intent
import com.android.identity.android.legacy.Crypto.createEcPrivateKey
import com.android.identity.android.mdoc.deviceretrieval.DeviceRetrievalHelper
import com.android.identity.android.mdoc.engagement.NfcEngagementHelper
import com.android.identity.android.mdoc.engagement.QrEngagementHelper
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransportOptions
import com.android.identity.android.util.NfcUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.presentment.mdocPresentment
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Orchestrates ISO/IEC 18013-5 proximity engagement and presentment using the legacy
 * `multipaz-android-legacy` APIs ([QrEngagementHelper], [NfcEngagementHelper], [DeviceRetrievalHelper]).
 */
object LegacyPresentmentManager {
    private const val TAG = "LegacyPresentmentMgr"

    sealed class SessionState {
        data object Idle : SessionState()
        data class QrEngaging(val qrCodeUri: String) : SessionState()
        data class NfcEngaging(val handoverType: String) : SessionState()
        data class Connecting(val engagementSource: String) : SessionState()
        data class Connected(
            val engagementSource: String,
            val transportDescription: String
        ) : SessionState()
        data class ProcessingRequest(
            val engagementSource: String,
            val transportDescription: String,
            val requestedDocTypes: List<String>
        ) : SessionState()
        data class Completed(
            val engagementSource: String,
            val transportDescription: String,
            val responseSizeBytes: Int,
            val numRequestsServed: Int
        ) : SessionState()
        data class Failed(val message: String) : SessionState()
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // Engagement & transport settings
    val nfcStaticHandover = MutableStateFlow(false)
    val bleCentralClientMode = MutableStateFlow(true)
    val blePeripheralServerMode = MutableStateFlow(false)
    val nfcDataTransfer = MutableStateFlow(false)
    val bleUseL2Cap = MutableStateFlow(false)
    val bleExperimentalL2CapPsmInEngagement = MutableStateFlow(false)

    // Presentment settings
    val requireAuthentication = MutableStateFlow(true)
    val showConsentPrompt = MutableStateFlow(true)
    val preferSignatureToKeyAgreement = MutableStateFlow(true)
    val keepConnectionOpen = MutableStateFlow(false)

    private var qrEngagementHelper: QrEngagementHelper? = null
    private var nfcEngagementHelper: NfcEngagementHelper? = null
    private var deviceRetrievalHelper: DeviceRetrievalHelper? = null
    private var nfcEngagementTimeoutJob: Job? = null

    private fun buildConnectionMethods(): List<MdocConnectionMethod> {
        val methods = mutableListOf<MdocConnectionMethod>()
        if (bleCentralClientMode.value) {
            methods.add(
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = false,
                    supportsCentralClientMode = true,
                    peripheralServerModeUuid = null,
                    centralClientModeUuid = UUID.randomUUID()
                )
            )
        }
        if (blePeripheralServerMode.value) {
            methods.add(
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = true,
                    supportsCentralClientMode = false,
                    peripheralServerModeUuid = UUID.randomUUID(),
                    centralClientModeUuid = null
                )
            )
        }
        if (nfcDataTransfer.value) {
            methods.add(
                MdocConnectionMethodNfc(
                    commandDataFieldMaxLength = 0xffff,
                    responseDataFieldMaxLength = 0x10000
                )
            )
        }
        return methods
    }

    private fun buildDataTransportOptions(): DataTransportOptions {
        return DataTransportOptions.Builder()
            .setBleUseL2CAP(bleUseL2Cap.value)
            .setExperimentalBleL2CAPPsmInEngagement(bleExperimentalL2CapPsmInEngagement.value)
            .build()
    }

    fun reset() {
        nfcEngagementTimeoutJob?.cancel()
        nfcEngagementTimeoutJob = null
        try {
            qrEngagementHelper?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing QrEngagementHelper", e)
        }
        qrEngagementHelper = null

        try {
            nfcEngagementHelper?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing NfcEngagementHelper", e)
        }
        nfcEngagementHelper = null

        try {
            deviceRetrievalHelper?.disconnect()
        } catch (e: Exception) {
            Logger.w(TAG, "Error disconnecting DeviceRetrievalHelper", e)
        }
        deviceRetrievalHelper = null

        _sessionState.value = SessionState.Idle
    }

    fun startQrEngagement(context: Context) {
        val connectionMethods = buildConnectionMethods()
        if (connectionMethods.isEmpty()) {
            _sessionState.value = SessionState.Failed(
                "Select at least one data transport (BLE Central, BLE Peripheral, or NFC)."
            )
            return
        }
        reset()

        val appContext = context.applicationContext
        val eDeviceKey = createEcPrivateKey(EcCurve.P256)
        val listener = object : QrEngagementHelper.Listener {
            override fun onDeviceConnecting() {
                Logger.i(TAG, "QR: onDeviceConnecting")
                _sessionState.value = SessionState.Connecting("QR Engagement")
            }

            override fun onDeviceConnected(transport: DataTransport) {
                Logger.i(TAG, "QR: onDeviceConnected via $transport")
                val helper = qrEngagementHelper ?: return
                val deviceEngagement = helper.deviceEngagement
                val handover = helper.handover
                helper.close()
                qrEngagementHelper = null
                startDeviceRetrieval(
                    context = appContext,
                    eDeviceKey = eDeviceKey,
                    transport = transport,
                    deviceEngagement = deviceEngagement,
                    handover = handover,
                    engagementSource = "QR Engagement"
                )
            }

            override fun onError(error: Throwable) {
                Logger.e(TAG, "QR: onError", error)
                qrEngagementHelper?.close()
                qrEngagementHelper = null
                _sessionState.value = SessionState.Failed(
                    "QR engagement error: ${error.message ?: error::class.simpleName}"
                )
            }
        }

        try {
            val helper = QrEngagementHelper.Builder(
                appContext,
                eDeviceKey.publicKey,
                buildDataTransportOptions(),
                listener,
                appContext.mainExecutor
            )
                .setConnectionMethods(connectionMethods)
                .build()
            qrEngagementHelper = helper
            _sessionState.value = SessionState.QrEngaging(helper.deviceEngagementUriEncoded)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start QrEngagementHelper", e)
            _sessionState.value = SessionState.Failed(
                "Failed to start QR engagement: ${e.message ?: e::class.simpleName}"
            )
        }
    }

    fun nfcProcessCommandApdu(context: Context, apdu: ByteArray): ByteArray {
        val appContext = context.applicationContext
        if (nfcEngagementHelper == null) {
            val currentState = _sessionState.value
            if (currentState is SessionState.Completed ||
                currentState is SessionState.Failed ||
                currentState is SessionState.QrEngaging
            ) {
                reset()
            } else if (deviceRetrievalHelper != null) {
                Logger.w(TAG, "Ignoring NFC engagement tap because a retrieval session is active")
                return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
            }

            val useStatic = nfcStaticHandover.value
            val connectionMethods = buildConnectionMethods()
            if (useStatic && connectionMethods.isEmpty()) {
                _sessionState.value = SessionState.Failed(
                    "NFC Static Handover requires at least one enabled transport method."
                )
                return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
            }

            // Bring MainActivity to the foreground so consent / biometric prompts can be shown.
            try {
                val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                appContext.startActivity(launchIntent)
            } catch (e: Exception) {
                Logger.w(TAG, "Unable to bring MainActivity to foreground on NFC tap", e)
            }

            val handoverLabel = if (useStatic) {
                "NFC Static Handover"
            } else {
                "NFC Negotiated Handover"
            }
            val eDeviceKey = createEcPrivateKey(EcCurve.P256)
            val listener = object : NfcEngagementHelper.Listener {
                override fun onTwoWayEngagementDetected() {
                    Logger.i(TAG, "NFC: onTwoWayEngagementDetected")
                }

                override fun onHandoverSelectMessageSent() {
                    Logger.i(TAG, "NFC: onHandoverSelectMessageSent")
                }

                override fun onDeviceConnecting() {
                    Logger.i(TAG, "NFC: onDeviceConnecting")
                    _sessionState.value = SessionState.Connecting(handoverLabel)
                }

                override fun onDeviceConnected(transport: DataTransport) {
                    Logger.i(TAG, "NFC: onDeviceConnected via $transport")
                    nfcEngagementTimeoutJob?.cancel()
                    nfcEngagementTimeoutJob = null
                    val helper = nfcEngagementHelper ?: return
                    val deviceEngagement = helper.deviceEngagement
                    val handover = helper.handover
                    helper.close()
                    nfcEngagementHelper = null
                    startDeviceRetrieval(
                        context = appContext,
                        eDeviceKey = eDeviceKey,
                        transport = transport,
                        deviceEngagement = deviceEngagement,
                        handover = handover,
                        engagementSource = handoverLabel
                    )
                }

                override fun onError(error: Throwable) {
                    Logger.e(TAG, "NFC: onError", error)
                    nfcEngagementTimeoutJob?.cancel()
                    nfcEngagementTimeoutJob = null
                    nfcEngagementHelper?.close()
                    nfcEngagementHelper = null
                    _sessionState.value = SessionState.Failed(
                        "NFC engagement error: ${error.message ?: error::class.simpleName}"
                    )
                }
            }

            val builder = NfcEngagementHelper.Builder(
                appContext,
                eDeviceKey.publicKey,
                buildDataTransportOptions(),
                listener,
                appContext.mainExecutor
            )
            if (useStatic) {
                builder.useStaticHandover(connectionMethods)
            } else {
                builder.useNegotiatedHandover()
            }
            nfcEngagementHelper = builder.build()
            _sessionState.value = SessionState.NfcEngaging(handoverLabel)

            nfcEngagementTimeoutJob?.cancel()
            nfcEngagementTimeoutJob = scope.launch {
                delay(15.seconds)
                if (nfcEngagementHelper != null && deviceRetrievalHelper == null) {
                    Logger.w(TAG, "Timed out waiting for reader connection after NFC engagement")
                    nfcEngagementHelper?.close()
                    nfcEngagementHelper = null
                    _sessionState.value = SessionState.Failed(
                        "Timed out waiting for reader to connect after NFC tap"
                    )
                }
            }
        }
        return nfcEngagementHelper?.nfcProcessCommandApdu(apdu)
            ?: NfcUtil.STATUS_WORD_FILE_NOT_FOUND
    }

    fun nfcOnDeactivated(reason: Int) {
        nfcEngagementHelper?.nfcOnDeactivated(reason)
    }

    private fun startDeviceRetrieval(
        context: Context,
        eDeviceKey: EcPrivateKey,
        transport: DataTransport,
        deviceEngagement: ByteArray,
        handover: ByteArray,
        engagementSource: String
    ) {
        val transportDescription = transport.connectionMethodForTransport.toString()
        _sessionState.value = SessionState.Connected(engagementSource, transportDescription)

        var lastResponseSizeBytes = 0
        var numRequestsServed = 0

        val listener = object : DeviceRetrievalHelper.Listener {
            override fun onEReaderKeyReceived(eReaderKey: EcPublicKey) {
                Logger.i(TAG, "DeviceRetrievalHelper: onEReaderKeyReceived")
            }

            override fun onDeviceRequest(deviceRequestBytes: ByteArray) {
                Logger.i(TAG, "DeviceRetrievalHelper: onDeviceRequest (${deviceRequestBytes.size} bytes)")
                val helper = deviceRetrievalHelper ?: return
                val appState = LegacyAppState.getInstance()
                CoroutineScope(Dispatchers.Main + appState.promptModel).launch {
                    try {
                        appState.initialize()
                        val sessionTranscript = Cbor.decode(helper.sessionTranscript)
                        val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(deviceRequestBytes))
                        deviceRequest.verifyReaderAuthentication(sessionTranscript)

                        val requestedDocTypes = deviceRequest.docRequests.map { it.docType }
                        _sessionState.value = SessionState.ProcessingRequest(
                            engagementSource = engagementSource,
                            transportDescription = transportDescription,
                            requestedDocTypes = requestedDocTypes
                        )

                        val presentmentSource = appState.getPresentmentSource(
                            requireAuthentication = requireAuthentication.value,
                            showConsentPrompt = showConsentPrompt.value,
                            preferSignatureToKeyAgreement = preferSignatureToKeyAgreement.value
                        )

                        val response = mdocPresentment(
                            deviceRequest = deviceRequest,
                            eReaderKey = helper.eReaderKey,
                            sessionTranscript = sessionTranscript,
                            source = presentmentSource,
                            keyAgreementPossible = listOf(EcCurve.P256),
                            requesterAppId = null,
                            requesterOrigin = null,
                            onWaitingForUserInput = {},
                            onDocumentsInFocus = {}
                        )

                        val encodedDeviceResponse = Cbor.encode(response.deviceResponse.toDataItem())
                        lastResponseSizeBytes = encodedDeviceResponse.size
                        numRequestsServed += 1

                        val status = if (keepConnectionOpen.value) {
                            null
                        } else {
                            Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                        }
                        helper.sendDeviceResponse(encodedDeviceResponse, status)

                        if (keepConnectionOpen.value) {
                            _sessionState.value = SessionState.Connected(
                                engagementSource = engagementSource,
                                transportDescription = "$transportDescription (Served $numRequestsServed request(s))"
                            )
                        } else {
                            _sessionState.value = SessionState.Completed(
                                engagementSource = engagementSource,
                                transportDescription = transportDescription,
                                responseSizeBytes = lastResponseSizeBytes,
                                numRequestsServed = numRequestsServed
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.e(TAG, "Error handling DeviceRequest", e)
                        try {
                            helper.sendDeviceResponse(
                                null,
                                Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                            )
                        } catch (_: Exception) {
                        }
                        helper.disconnect()
                        deviceRetrievalHelper = null
                        _sessionState.value = SessionState.Failed(
                            e.message ?: e::class.simpleName ?: "Presentment error"
                        )
                    }
                }
            }

            override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {
                Logger.i(
                    TAG,
                    "DeviceRetrievalHelper: onDeviceDisconnected (transportSpecific=$transportSpecificTermination)"
                )
                deviceRetrievalHelper?.disconnect()
                deviceRetrievalHelper = null
                if (_sessionState.value !is SessionState.Completed) {
                    _sessionState.value = SessionState.Completed(
                        engagementSource = engagementSource,
                        transportDescription = transportDescription,
                        responseSizeBytes = lastResponseSizeBytes,
                        numRequestsServed = numRequestsServed
                    )
                }
            }

            override fun onError(error: Throwable) {
                Logger.w(TAG, "DeviceRetrievalHelper: onError", error)
                deviceRetrievalHelper?.disconnect()
                deviceRetrievalHelper = null
                // If we already sent the response with SESSION_DATA_STATUS_SESSION_TERMINATION,
                // the reader closes the transport immediately without sending another message.
                if (_sessionState.value !is SessionState.Completed) {
                    _sessionState.value = SessionState.Failed(
                        "Retrieval error: ${error.message ?: error::class.simpleName}"
                    )
                }
            }
        }

        deviceRetrievalHelper = DeviceRetrievalHelper.Builder(
            context,
            listener,
            context.mainExecutor,
            eDeviceKey
        )
            .useForwardEngagement(transport, deviceEngagement, handover)
            .build()
    }
}
