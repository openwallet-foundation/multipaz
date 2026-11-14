package org.multipaz.presentment

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.EReaderKey
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.NfcCceTransportMdoc
import org.multipaz.mdoc.transport.TransportWarning
import org.multipaz.mdoc.transport.TransportWarningCode
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val TAG = "Iso180135Presentment"

private data class CompletedProximityExchange(
    val presentmentData: EventPresentmentData,
    val request: DataItem,
    val response: DataItem,
    val sessionTranscript: DataItem,
)

private fun inferEngagementType(
    transport: MdocTransport,
    handover: DataItem,
): EngagementType = when {
    transport is NfcCceTransportMdoc -> EngagementType.NFC_CONCURRENT_CHANNEL_ENGAGEMENT
    handover is CborArray && handover.items.size == 2 &&
            handover.items[0] is Tagged && handover.items[1] is Tagged -> EngagementType.NFC_CONCURRENT_CHANNEL_ENGAGEMENT
    handover == Simple.NULL -> EngagementType.QR_CODE
    handover is Bstr -> EngagementType.READER_QR_CODE
    handover is CborArray && handover.items.getOrNull(1) == Simple.NULL -> EngagementType.NFC_STATIC_HANDOVER
    else -> EngagementType.NFC_NEGOTIATED_HANDOVER
}

private class Iso18013PresentmentSession(
    private val transport: MdocTransport,
    private val engagementParams: StateFlow<EngagementParams>,
    private val source: PresentmentSource,
    private val keyAgreementPossible: List<EcCurve>,
    private val preselectedDocuments: List<Document>,
    private val insertSequenceNumbers: Boolean,
    private val timeout: Duration?,
    private val timeoutSubsequentRequests: Duration?,
    private val onWaitingForRequest: () -> Unit,
    private val onWaitingForUserInput: () -> Unit,
    private val onDocumentsInFocus: (documents: List<Document>) -> Unit,
    private val onSendingResponse: () -> Unit,
    private val onDeviceRequest: (deviceRequest: DeviceRequest) -> Unit,
    private val onTelemetry: (telemetry: PresentmentTelemetry) -> Unit,
    private val startTime: Instant = Clock.System.now(),
) {
    private var connectedTime: Instant? = null
    private var durationEngagementToConnected: Duration? = null
    private var durationConnectedToRequestReceived: Duration? = null
    private var durationUserConsentAndAuth: Duration? = null
    private var durationResponseGeneration: Duration? = null
    private var durationResponseTransmission: Duration? = null
    private var numRequestsServed = 0
    private var numDocumentsPresented = 0
    private var numNfcReTaps = 0
    private var lastRequestSizeBytes: Int? = null
    private var lastResponseSizeBytes: Int? = null
    private var lastRequesterInTrustList: Boolean? = null
    private var lastSessionTranscript: DataItem? = null
    private var lastDeviceRequest: DeviceRequest? = null
    private val presentmentWarnings = mutableListOf<TransportWarning>()
    private val completedExchanges = mutableListOf<CompletedProximityExchange>()
    private var finalOutcome = PresentmentOutcome.SUCCESS
    private var sendSessionTermination = false
    private var sessionEncryption: SessionEncryption? = null
    private var activeEReaderKey: EReaderKey? = null
    private lateinit var sessionTranscript: DataItem
    private lateinit var encodedSessionTranscript: ByteArray

    private fun buildTelemetry(outcome: PresentmentOutcome): PresentmentTelemetry {
        val currentStats = transport.stats
        val engagementType = inferEngagementType(transport, engagementParams.value.handover)
        val mergedWarnings = (presentmentWarnings + currentStats.warnings).distinct()
        val effectiveNfcReTaps = maxOf(numNfcReTaps, (transport as? NfcCceTransportMdoc)?.numNfcReTaps ?: 0)
        return PresentmentTelemetry(
            protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
            engagementType = engagementType,
            outcome = outcome,
            warnings = mergedWarnings,
            requesterInTrustList = lastRequesterInTrustList,
            requestSizeBytes = lastRequestSizeBytes,
            responseSizeBytes = lastResponseSizeBytes,
            numRequestsServed = numRequestsServed,
            numDocumentsPresented = numDocumentsPresented,
            numNfcReTaps = effectiveNfcReTaps,
            durationEngagementToConnected = durationEngagementToConnected,
            durationConnectedToRequestReceived = durationConnectedToRequestReceived,
            durationUserConsentAndAuth = durationUserConsentAndAuth,
            durationResponseGeneration = durationResponseGeneration,
            durationResponseTransmission = durationResponseTransmission,
            durationTotal = Clock.System.now() - startTime,
            transportStats = currentStats,
        )
    }

    private suspend fun receiveReaderMessage(): ByteArray {
        Logger.i(TAG, "Waiting for message from reader...")
        onWaitingForRequest()
        if (transport is NfcCceTransportMdoc && transport.isNfcOnly && !transport.isNfcConnected.value) {
            if (numRequestsServed > 0) {
                Logger.i(TAG, "NFC disconnected after response sent. Failing presentment session.")
                throw Iso18013PresentmentNfcDisconnectedException()
            }
            Logger.i(TAG, "NFC disconnected while waiting for reader message. Waiting for re-tap...")
            presentmentWarnings.add(TransportWarning(TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP))
            transport.isNfcConnected.first { it }
            numNfcReTaps += 1
            Logger.i(TAG, "Re-tapped! Continuing presentment session...")
        }
        val timeoutToUse = if (numRequestsServed == 0) timeout else timeoutSubsequentRequests
        return try {
            if (transport is NfcCceTransportMdoc && transport.isNfcOnly && numRequestsServed > 0) {
                coroutineScope {
                    val nfcDisconnectJob = launch {
                        transport.isNfcConnected.first { !it }
                        Logger.i(TAG, "NFC disconnected while waiting for reader message after response sent")
                        throw Iso18013PresentmentNfcDisconnectedException()
                    }
                    try {
                        val msg = if (timeoutToUse == null) {
                            transport.waitForMessage()
                        } else {
                            withTimeout(timeoutToUse) {
                                transport.waitForMessage()
                            }
                        }
                        nfcDisconnectJob.cancel()
                        msg
                    } catch (e: Exception) {
                        nfcDisconnectJob.cancel()
                        throw e
                    }
                }
            } else if (timeoutToUse == null) {
                transport.waitForMessage()
            } else {
                withTimeout(timeoutToUse) {
                    transport.waitForMessage()
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw Iso18013PresentmentTimeoutException("Timed out waiting for message from remote reader", e)
        }
    }

    private fun resolveEngagementEReaderKey(params: EngagementParams): EReaderKey? {
        val matchingKey = params.eReaderKey?.takeIf { it.curve == params.eDeviceKey.curve } ?: return null
        val encodedCoseKey = params.eReaderKeyBytes?.let {
            Cbor.decode(it.toByteArray()).asTagged.asBstr
        } ?: Cbor.encode(matchingKey.toCoseKey().toDataItem())
        return EReaderKey(matchingKey, encodedCoseKey)
    }

    private suspend fun updateSessionForLatestEngagement(
        currentParams: EngagementParams,
        currentDeviceRequest: DeviceRequest,
    ): DeviceRequest {
        val latestParams = engagementParams.value
        val retapMessage = (transport as? NfcCceTransportMdoc)?.consumeLastRetapMessage()
        if (latestParams == currentParams && retapMessage == null) {
            return currentDeviceRequest
        }
        if (latestParams.eDeviceKey !== currentParams.eDeviceKey) {
            currentParams.eDeviceKey.close()
        }
        val latestEDeviceKey = latestParams.eDeviceKey
        val latestDeviceEngagement = latestParams.deviceEngagement
        val latestHandover = latestParams.handover
        val resolvedEReaderKey = resolveEngagementEReaderKey(latestParams) ?: retapMessage?.let {
            SessionEncryption.getEReaderKey(it.toByteArray())
        } ?: activeEReaderKey!!
        activeEReaderKey = resolvedEReaderKey

        sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(latestDeviceEngagement))))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(resolvedEReaderKey.encodedCoseKey)))
            add(latestHandover)
        }
        lastSessionTranscript = sessionTranscript
        sessionEncryption?.close()
        val newSessionEncryption = SessionEncryption(
            role = MdocRole.MDOC,
            eSelfKey = latestEDeviceKey.duplicate(),
            remotePublicKey = resolvedEReaderKey.publicKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = insertSequenceNumbers
        )
        sessionEncryption = newSessionEncryption

        if (retapMessage == null) {
            throw IllegalStateException("Expected SessionEstablishment message on NFC re-tap")
        }
        val (encodedRetapRequest, status) = newSessionEncryption.decryptMessage(retapMessage.toByteArray())
        if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION || encodedRetapRequest == null) {
            throw IllegalStateException("No DeviceRequest in message from reader on NFC re-tap")
        }
        lastRequestSizeBytes = encodedRetapRequest.size
        val retapDeviceRequest = DeviceRequest.fromDataItem(Cbor.decode(encodedRetapRequest))
        lastDeviceRequest = retapDeviceRequest
        retapDeviceRequest.verifyReaderAuthentication(sessionTranscript)
        if (!retapDeviceRequest.isSameAuthenticatedReader(currentDeviceRequest, source)) {
            throw IllegalStateException(
                "DeviceRequest on NFC re-tap is not equivalent or not from the same authenticated reader"
            )
        }
        onDeviceRequest(retapDeviceRequest)
        return retapDeviceRequest
    }

    private suspend fun finalizeSession() {
        try {
            val currentSessionEncryption = sessionEncryption
            if (sendSessionTermination &&
                currentSessionEncryption != null &&
                currentCoroutineContext().isActive &&
                (transport !is NfcCceTransportMdoc || !transport.isNfcOnly || transport.isNfcConnected.value)
            ) {
                Logger.i(TAG, "Sending session-termination")
                try {
                    val disconnectNfc = transport is NfcCceTransportMdoc && transport.shouldSendDisconnectNfc
                    transport.sendMessage(
                        currentSessionEncryption.encryptMessage(
                            messagePlaintext = null,
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            disconnectNfc = disconnectNfc
                        )
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Caught error while sending session-termination", e)
                    presentmentWarnings.add(TransportWarning(TransportWarningCode.SESSION_TERMINATION_SEND_FAILED))
                }
            }
        } finally {
            val resolvedTelemetry = buildTelemetry(outcome = finalOutcome)
            if (completedExchanges.isNotEmpty()) {
                for (exchange in completedExchanges) {
                    source.eventLogger?.addEventAsync(
                        EventPresentmentIso18013Proximity(
                            presentmentData = exchange.presentmentData,
                            request = exchange.request,
                            response = exchange.response,
                            sessionTranscript = exchange.sessionTranscript,
                            telemetry = resolvedTelemetry,
                        )
                    )
                }
            } else if (finalOutcome != PresentmentOutcome.SUCCESS) {
                source.eventLogger?.addEventAsync(
                    EventPresentmentIso18013Proximity(
                        presentmentData = null,
                        request = lastDeviceRequest?.toDataItem(),
                        response = null,
                        sessionTranscript = lastSessionTranscript,
                        telemetry = resolvedTelemetry,
                    )
                )
            }
            onTelemetry(resolvedTelemetry)
            withContext(NonCancellable) {
                sessionEncryption?.close()
                engagementParams.value.eDeviceKey.close()
                Logger.i(TAG, "Closing transport")
                transport.close()
            }
        }
    }

    suspend fun run() {
        try {
            // Wait until state changes to CONNECTED, FAILED, or CLOSED
            transport.state.first {
                it == MdocTransport.State.CONNECTED ||
                        it == MdocTransport.State.FAILED ||
                        it == MdocTransport.State.CLOSED
            }
            val nowConnected = Clock.System.now()
            connectedTime = nowConnected
            durationEngagementToConnected = nowConnected - startTime
            if (transport.state.value != MdocTransport.State.CONNECTED) {
                throw MdocTransportException("Expected state CONNECTED but found ${transport.state.value}")
            }
            sendSessionTermination = true
            val initialParams = engagementParams.value
            Logger.dCbor(TAG, "Handover", initialParams.handover)
            Logger.dCbor(TAG, "DeviceEngagement", initialParams.deviceEngagement)
            var initialEncodedDeviceEngagement: ByteArray? = null
            if (inferEngagementType(transport, initialParams.handover) == EngagementType.READER_QR_CODE) {
                val initialEReaderKey = requireNotNull(initialParams.eReaderKey) {
                    "eReaderKey is required in EngagementParams for READER_QR_CODE"
                }
                require(initialParams.eDeviceKey.curve == initialEReaderKey.curve) {
                    "EDeviceKey curve (${initialParams.eDeviceKey.curve}) must match " +
                        "EReaderKey curve (${initialEReaderKey.curve}) for READER_QR_CODE"
                }
                val encodedDe = Cbor.encode(initialParams.deviceEngagement)
                initialEncodedDeviceEngagement = encodedDe
                Logger.i(TAG, "Sending initial DeviceEngagement message for Reader QR")
                transport.sendMessage(SessionEncryption.encodeDeviceEngagementMessage(encodedDe))
            }
            while (true) {
                val sessionData = receiveReaderMessage()
                if (sessionData.isEmpty()) {
                    Logger.i(TAG, "Received transport-specific session termination message from reader")
                    sendSessionTermination = false
                    break
                }

                val requestReceivedTime = Clock.System.now()
                if (durationConnectedToRequestReceived == null) {
                    durationConnectedToRequestReceived = requestReceivedTime - connectedTime!!
                }

                val currentParams = engagementParams.value
                val eDeviceKeyToUse = currentParams.eDeviceKey
                val deviceEngagementToUse = currentParams.deviceEngagement
                val handoverToUse = currentParams.handover

                // Per ISO/IEC 18013-5 Second Edition clause 12.2.4, if the mdoc received an EReaderKey in
                // ReaderEngagement (step 0) matching the curve of EDeviceKey (step 1), it uses that key and
                // ignores any EReaderKey in the first SessionMessage (step 2); otherwise it extracts EReaderKey
                // from the first SessionMessage.
                val engagementEReaderKey = resolveEngagementEReaderKey(currentParams)
                if (sessionEncryption == null ||
                    (engagementEReaderKey != null && engagementEReaderKey.publicKey != activeEReaderKey?.publicKey)
                ) {
                    val currentEReaderKey = engagementEReaderKey ?: SessionEncryption.getEReaderKey(sessionData)
                    activeEReaderKey = currentEReaderKey
                    val encodedDeviceEngagement = if (
                        initialEncodedDeviceEngagement != null &&
                        deviceEngagementToUse === initialParams.deviceEngagement
                    ) {
                        initialEncodedDeviceEngagement
                    } else {
                        Cbor.encode(deviceEngagementToUse)
                    }
                    sessionTranscript = buildCborArray {
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeviceEngagement)))
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(currentEReaderKey.encodedCoseKey)))
                        add(handoverToUse)
                    }
                    lastSessionTranscript = sessionTranscript
                    Logger.dCbor(TAG, "SessionTranscript", sessionTranscript)
                    encodedSessionTranscript = Cbor.encode(sessionTranscript)
                    sessionEncryption?.close()
                    sessionEncryption = SessionEncryption(
                        role = MdocRole.MDOC,
                        eSelfKey = eDeviceKeyToUse.duplicate(),
                        remotePublicKey = currentEReaderKey.publicKey,
                        encodedSessionTranscript = encodedSessionTranscript,
                        insertSequenceNumbers = insertSequenceNumbers
                    )
                }
                val (encodedDeviceRequest, status) = sessionEncryption!!.decryptMessage(sessionData)

                if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                    Logger.i(TAG, "Received session termination message from reader")
                    sendSessionTermination = false
                    break
                }

                if (encodedDeviceRequest == null) {
                    throw IllegalStateException("No data in message from reader")
                }

                lastRequestSizeBytes = encodedDeviceRequest.size
                Logger.dCbor(TAG, "DeviceRequest", encodedDeviceRequest)
                val deviceRequestCbor = Cbor.decode(encodedDeviceRequest)
                val deviceRequest = DeviceRequest.fromDataItem(deviceRequestCbor)
                lastDeviceRequest = deviceRequest
                deviceRequest.verifyReaderAuthentication(sessionTranscript)
                onDeviceRequest(deviceRequest)
                if (transport is NfcCceTransportMdoc && transport.isNfcOnly) {
                    transport.markResponsePending()
                }

                val consentStartTime = Clock.System.now()
                val selection = mdocPresentmentObtainConsent(
                    deviceRequest = deviceRequest,
                    source = source,
                    keyAgreementPossible = keyAgreementPossible,
                    requesterAppId = null,
                    requesterOrigin = null,
                    preselectedDocuments = preselectedDocuments,
                    onWaitingForUserInput = onWaitingForUserInput,
                    onDocumentsInFocus = onDocumentsInFocus
                )
                val keyUnlockDataProvider = mdocPresentmentAuthenticateUser(selection)
                val faceMatchedCredentials = mdocPerformFaceMatching(
                    selection = selection,
                    source = source,
                    onWaitingForUserInput = onWaitingForUserInput,
                )
                durationUserConsentAndAuth = (durationUserConsentAndAuth ?: Duration.ZERO) + (Clock.System.now() - consentStartTime)

                onSendingResponse()

                if (transport is NfcCceTransportMdoc && transport.isNfcOnly) {
                    transport.markResponsePending()
                    if (!transport.isNfcConnected.value) {
                        Logger.i(TAG, "NFC disconnected after user consent/auth. Waiting for re-tap before generating response...")
                        presentmentWarnings.add(TransportWarning(TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP))
                        transport.isNfcConnected.first { it }
                        numNfcReTaps += 1
                        Logger.i(TAG, "Re-tapped! Proceeding with response generation using latest engagement parameters.")
                    }
                }

                val effectiveDeviceRequest = updateSessionForLatestEngagement(currentParams, deviceRequest)
                val sessionTranscriptToUse = sessionTranscript
                val sessionEncryptionToUse = sessionEncryption!!

                val genStartTime = Clock.System.now()
                val (responseObject, commitState) = withContext(keyUnlockDataProvider) {
                    mdocPresentmentGenerateResponseInternal(
                        selection = selection,
                        deviceRequest = effectiveDeviceRequest,
                        eReaderKey = activeEReaderKey!!.publicKey,
                        sessionTranscript = sessionTranscriptToUse,
                        source = source,
                        requesterAppId = null,
                        requesterOrigin = null,
                        faceMatchedCredentials = faceMatchedCredentials,
                        deferStateCommit = true,
                    )
                }
                val encodedResponsePlaintext = Cbor.encode(responseObject.deviceResponse.toDataItem())
                val disconnectNfc = transport is NfcCceTransportMdoc && transport.shouldSendDisconnectNfc
                val encryptedResponse = sessionEncryptionToUse.encryptMessage(
                    messagePlaintext = encodedResponsePlaintext,
                    statusCode = null,
                    disconnectNfc = disconnectNfc
                )
                val responseReadyTime = Clock.System.now()
                val durationRequestToResponseReady = responseReadyTime - requestReceivedTime
                durationResponseGeneration =
                    (durationResponseGeneration ?: Duration.ZERO) + (responseReadyTime - genStartTime)
                lastResponseSizeBytes = encodedResponsePlaintext.size
                lastRequesterInTrustList = responseObject.eventData.trustMetadata != null
                Logger.i(
                    TAG,
                    "DeviceResponse ready to send in ${durationRequestToResponseReady.inWholeMilliseconds} ms " +
                        "(DeviceRequest: $lastRequestSizeBytes bytes, DeviceResponse: $lastResponseSizeBytes bytes)"
                )

                val txStartTime = Clock.System.now()
                transport.sendMessage(encryptedResponse)
                durationResponseTransmission = (durationResponseTransmission ?: Duration.ZERO) + (Clock.System.now() - txStartTime)
                numRequestsServed += 1
                numDocumentsPresented += responseObject.eventData.requestedDocuments.size
                completedExchanges.add(
                    CompletedProximityExchange(
                        presentmentData = responseObject.eventData,
                        request = effectiveDeviceRequest.toDataItem(),
                        response = responseObject.deviceResponse.toDataItem(),
                        sessionTranscript = sessionTranscriptToUse,
                    )
                )
                withContext(NonCancellable) {
                    commitState()
                }

                Logger.i(TAG, "Response sent, keeping connection open")
            }
        } catch (error: Throwable) {
            finalOutcome = classifyPresentmentException(error)
            throw error
        } finally {
            finalizeSession()
        }
    }
}

internal suspend fun runIso18013Presentment(
    transport: MdocTransport,
    engagementParams: StateFlow<EngagementParams>,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    preselectedDocuments: List<Document>,
    insertSequenceNumbers: Boolean,
    timeout: Duration?,
    timeoutSubsequentRequests: Duration?,
    onWaitingForRequest: () -> Unit,
    onWaitingForUserInput: () -> Unit,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onSendingResponse: () -> Unit,
    onDeviceRequest: (deviceRequest: DeviceRequest) -> Unit,
    onTelemetry: (telemetry: PresentmentTelemetry) -> Unit,
    startTime: Instant = Clock.System.now(),
) {
    Iso18013PresentmentSession(
        transport = transport,
        engagementParams = engagementParams,
        source = source,
        keyAgreementPossible = keyAgreementPossible,
        preselectedDocuments = preselectedDocuments,
        insertSequenceNumbers = insertSequenceNumbers,
        timeout = timeout,
        timeoutSubsequentRequests = timeoutSubsequentRequests,
        onWaitingForRequest = onWaitingForRequest,
        onWaitingForUserInput = onWaitingForUserInput,
        onDocumentsInFocus = onDocumentsInFocus,
        onSendingResponse = onSendingResponse,
        onDeviceRequest = onDeviceRequest,
        onTelemetry = onTelemetry,
        startTime = startTime,
    ).run()
}

/**
 * Performs proximity presentment according to ISO/IEC 18013-5:2021.
 *
 * @param transport the transport to use for communicating with the reader.
 * @param engagementParams a [StateFlow] providing the current engagement parameters.
 * @param source the source of truth for documents to present.
 * @param keyAgreementPossible curves available for key agreement.
 * @param preselectedDocuments the list of documents the user may have preselected earlier or the
 *   empty list if the user didn't preselect.
 * @param insertSequenceNumbers whether sequence numbers should be inserted in session messages.
 * @param timeout timeout for initial message.
 * @param timeoutSubsequentRequests timeout for subsequent messages.
 * @param onWaitingForRequest callback when waiting for request.
 * @param onWaitingForUserInput callback when waiting for user input.
 * @param onDocumentsInFocus callback with selected documents.
 * @param onSendingResponse callback when sending response.
 * @param onDeviceRequest callback when a [DeviceRequest] is received.
 * @param onTelemetry callback invoked with the final [PresentmentTelemetry]. This is guaranteed to
 *   be called both before returning normally and before an exception is thrown.
 * @throws AuthenticationFailedException if user authentication failed or was canceled.
 * @throws FaceNotMatchedException if face matching failed or was canceled.
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    AuthenticationFailedException::class,
    CancellationException::class,
    Exception::class,
    FaceNotMatchedException::class,
    IllegalStateException::class,
    MdocTransportClosedException::class,
    Iso18013PresentmentTimeoutException::class,
    PresentmentCanceledException::class,
    PresentmentCannotSatisfyRequestException::class
)
suspend fun Iso18013Presentment(
    transport: MdocTransport,
    engagementParams: StateFlow<EngagementParams>,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    preselectedDocuments: List<Document> = emptyList(),
    insertSequenceNumbers: Boolean = false,
    timeout: Duration? = 15.seconds,
    timeoutSubsequentRequests: Duration? = 30.seconds,
    onWaitingForRequest: () -> Unit = {},
    onWaitingForUserInput: () -> Unit = {},
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    onSendingResponse: () -> Unit = {},
    onDeviceRequest: (deviceRequest: DeviceRequest) -> Unit = {},
    onTelemetry: (telemetry: PresentmentTelemetry) -> Unit = {},
) {
    runIso18013Presentment(
        transport = transport,
        engagementParams = engagementParams,
        source = source,
        keyAgreementPossible = keyAgreementPossible,
        preselectedDocuments = preselectedDocuments,
        insertSequenceNumbers = insertSequenceNumbers,
        timeout = timeout,
        timeoutSubsequentRequests = timeoutSubsequentRequests,
        onWaitingForRequest = onWaitingForRequest,
        onWaitingForUserInput = onWaitingForUserInput,
        onDocumentsInFocus = onDocumentsInFocus,
        onSendingResponse = onSendingResponse,
        onDeviceRequest = onDeviceRequest,
        onTelemetry = onTelemetry,
    )
}

/**
 * Performs proximity presentment according to ISO/IEC 18013-5:2021 using individual engagement parameters.
 *
 * @param transport the transport to use for communicating with the reader.
 * @param eDeviceKey the ephemeral device key generated for engagement.
 * @param deviceEngagement the encoded DeviceEngagement structure.
 * @param handover the handover structure.
 * @param source the source of truth for documents to present.
 * @param keyAgreementPossible curves available for key agreement.
 * @param preselectedDocuments the list of documents the user may have preselected earlier or the
 *   empty list if the user didn't preselect.
 * @param insertSequenceNumbers whether sequence numbers should be inserted in session messages.
 * @param timeout timeout for initial message.
 * @param timeoutSubsequentRequests timeout for subsequent messages.
 * @param onWaitingForRequest callback when waiting for request.
 * @param onWaitingForUserInput callback when waiting for user input.
 * @param onDocumentsInFocus callback with selected documents.
 * @param onSendingResponse callback when sending response.
 * @param onTelemetry callback invoked with the final [PresentmentTelemetry]. This is guaranteed to
 *   be called both before returning normally and before an exception is thrown.
 * @throws AuthenticationFailedException if user authentication failed or was canceled.
 * @throws FaceNotMatchedException if face matching failed or was canceled.
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    AuthenticationFailedException::class,
    CancellationException::class,
    Exception::class,
    FaceNotMatchedException::class,
    IllegalStateException::class,
    MdocTransportClosedException::class,
    Iso18013PresentmentTimeoutException::class,
    PresentmentCanceledException::class,
    PresentmentCannotSatisfyRequestException::class
)
suspend fun Iso18013Presentment(
    transport: MdocTransport,
    eDeviceKey: EcPrivateKey,
    deviceEngagement: DataItem,
    handover: DataItem,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    preselectedDocuments: List<Document> = emptyList(),
    insertSequenceNumbers: Boolean = false,
    timeout: Duration? = 15.seconds,
    timeoutSubsequentRequests: Duration? = 30.seconds,
    onWaitingForRequest: () -> Unit = {},
    onWaitingForUserInput: () -> Unit = {},
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    onSendingResponse: () -> Unit = {},
    onTelemetry: (telemetry: PresentmentTelemetry) -> Unit = {},
) = Iso18013Presentment(
    transport = transport,
    engagementParams = MutableStateFlow(
        EngagementParams(
            eDeviceKey = eDeviceKey,
            deviceEngagement = deviceEngagement,
            handover = handover
        )
    ),
    source = source,
    keyAgreementPossible = keyAgreementPossible,
    preselectedDocuments = preselectedDocuments,
    insertSequenceNumbers = insertSequenceNumbers,
    timeout = timeout,
    timeoutSubsequentRequests = timeoutSubsequentRequests,
    onWaitingForRequest = onWaitingForRequest,
    onWaitingForUserInput = onWaitingForUserInput,
    onDocumentsInFocus = onDocumentsInFocus,
    onSendingResponse = onSendingResponse,
    onTelemetry = onTelemetry,
)
