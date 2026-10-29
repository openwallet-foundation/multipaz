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
import org.multipaz.credential.Credential
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
import org.multipaz.securearea.KeyUnlockDataProvider
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
) {
    private val startTime = Clock.System.now()
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
    private var activeDeviceRequest: DeviceRequest? = null
    private var cachedSelection: CredentialSelection? = null
    private var cachedKeyUnlockDataProvider: KeyUnlockDataProvider? = null
    private var cachedFaceMatchedCredentials: List<Credential>? = null
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

    private fun updateSessionForLatestEngagement(currentParams: EngagementParams) {
        val latestParams = engagementParams.value
        if (latestParams != currentParams) {
            if (latestParams.eDeviceKey !== currentParams.eDeviceKey) {
                currentParams.eDeviceKey.close()
            }
            val latestEDeviceKey = latestParams.eDeviceKey
            val latestDeviceEngagement = latestParams.deviceEngagement
            val latestHandover = latestParams.handover
            val matchingLatestEReaderKey = latestParams.eReaderKey?.takeIf { it.curve == latestEDeviceKey.curve }
            val latestEReaderKey = matchingLatestEReaderKey ?: activeEReaderKey!!.publicKey
            val encodedCoseKeyToUse = matchingLatestEReaderKey?.let { Cbor.encode(it.toCoseKey().toDataItem()) }
                ?: activeEReaderKey!!.encodedCoseKey
            activeEReaderKey = EReaderKey(latestEReaderKey, encodedCoseKeyToUse)

            sessionTranscript = buildCborArray {
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(latestDeviceEngagement))))
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCoseKeyToUse)))
                add(latestHandover)
            }
            lastSessionTranscript = sessionTranscript
            sessionEncryption?.close()
            sessionEncryption = SessionEncryption(
                role = MdocRole.MDOC,
                eSelfKey = latestEDeviceKey.duplicate(),
                remotePublicKey = latestEReaderKey,
                encodedSessionTranscript = Cbor.encode(sessionTranscript),
                insertSequenceNumbers = insertSequenceNumbers
            ).apply {
                if (insertSequenceNumbers) {
                    setEncryptionCounters(
                        decryptedCounter = 2,
                        encryptedCounter = 1,
                        nextSequenceNumber = 1
                    )
                } else {
                    setEncryptionCounters(
                        decryptedCounter = 2,
                        encryptedCounter = 1
                    )
                }
            }
        }
    }

    private suspend fun finalizeSession() {
        try {
            if (sendSessionTermination &&
                currentCoroutineContext().isActive &&
                (transport !is NfcCceTransportMdoc || !transport.isNfcOnly || transport.isNfcConnected.value)
            ) {
                Logger.i(TAG, "Sending session-termination")
                try {
                    val disconnectNfc = transport is NfcCceTransportMdoc && transport.shouldSendDisconnectNfc
                    transport.sendMessage(
                        SessionEncryption.encodeStatus(
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            sequenceNumber = if (insertSequenceNumbers) {
                                sessionEncryption?.nextSequenceNumber
                            } else {
                                null
                            },
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
            while (true) {
                val sessionData = receiveReaderMessage()
                if (sessionData.isEmpty()) {
                    Logger.i(TAG, "Received transport-specific session termination message from reader")
                    sendSessionTermination = false
                    break
                }

                if (durationConnectedToRequestReceived == null) {
                    durationConnectedToRequestReceived = Clock.System.now() - connectedTime!!
                }

                val currentParams = engagementParams.value
                val eDeviceKeyToUse = currentParams.eDeviceKey
                val deviceEngagementToUse = currentParams.deviceEngagement
                val handoverToUse = currentParams.handover
                val eReaderKeyToUse = currentParams.eReaderKey

                // Per ISO/IEC 18013-5 Second Edition clause 12.2.4, if the mdoc received an EReaderKey in
                // ReaderEngagement (step 0) matching the curve of EDeviceKey (step 1), it uses that key and
                // ignores any EReaderKey in the first SessionMessage (step 2); otherwise it extracts EReaderKey
                // from the first SessionMessage.
                val matchingEngagementEReaderKey = eReaderKeyToUse?.takeIf { it.curve == eDeviceKeyToUse.curve }
                if (sessionEncryption == null ||
                    (matchingEngagementEReaderKey != null && matchingEngagementEReaderKey != activeEReaderKey?.publicKey)
                ) {
                    val currentEReaderKey = matchingEngagementEReaderKey?.let {
                        EReaderKey(it, Cbor.encode(it.toCoseKey().toDataItem()))
                    } ?: SessionEncryption.getEReaderKey(sessionData)
                    activeEReaderKey = currentEReaderKey
                    sessionTranscript = buildCborArray {
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceEngagementToUse))))
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

                val selection: CredentialSelection
                val keyUnlockDataProvider: KeyUnlockDataProvider
                val faceMatchedCredentials: List<Credential>

                val consentStartTime = Clock.System.now()
                val currentActiveRequest = activeDeviceRequest
                if (currentActiveRequest != null &&
                    deviceRequest.isStructurallyEquivalent(currentActiveRequest) &&
                    cachedSelection != null &&
                    cachedKeyUnlockDataProvider != null &&
                    cachedFaceMatchedCredentials != null
                ) {
                    Logger.i(TAG, "Reusing existing consent and key authentication for structurally equivalent DeviceRequest on re-tap")
                    selection = cachedSelection!!
                    keyUnlockDataProvider = cachedKeyUnlockDataProvider!!
                    faceMatchedCredentials = cachedFaceMatchedCredentials!!
                } else {
                    activeDeviceRequest = deviceRequest
                    selection = mdocPresentmentObtainConsent(
                        deviceRequest = deviceRequest,
                        source = source,
                        keyAgreementPossible = keyAgreementPossible,
                        requesterAppId = null,
                        requesterOrigin = null,
                        preselectedDocuments = preselectedDocuments,
                        onWaitingForUserInput = onWaitingForUserInput,
                        onDocumentsInFocus = onDocumentsInFocus
                    )
                    keyUnlockDataProvider = mdocPresentmentAuthenticateUser(selection)
                    faceMatchedCredentials = mdocPerformFaceMatching(
                        selection = selection,
                        source = source,
                        onWaitingForUserInput = onWaitingForUserInput,
                    )
                    cachedSelection = selection
                    cachedKeyUnlockDataProvider = keyUnlockDataProvider
                    cachedFaceMatchedCredentials = faceMatchedCredentials
                }
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

                updateSessionForLatestEngagement(currentParams)
                val sessionTranscriptToUse = sessionTranscript
                val sessionEncryptionToUse = sessionEncryption!!

                val genStartTime = Clock.System.now()
                val responseObject = withContext(keyUnlockDataProvider) {
                    mdocPresentmentGenerateResponse(
                        selection = selection,
                        deviceRequest = deviceRequest,
                        eReaderKey = activeEReaderKey!!.publicKey,
                        sessionTranscript = sessionTranscriptToUse,
                        source = source,
                        requesterAppId = null,
                        requesterOrigin = null,
                        faceMatchedCredentials = faceMatchedCredentials,
                    )
                }
                val encodedResponsePlaintext = Cbor.encode(responseObject.deviceResponse.toDataItem())
                val disconnectNfc = transport is NfcCceTransportMdoc && transport.shouldSendDisconnectNfc
                val encryptedResponse = sessionEncryptionToUse.encryptMessage(
                    messagePlaintext = encodedResponsePlaintext,
                    statusCode = null,
                    disconnectNfc = disconnectNfc
                )
                durationResponseGeneration = (durationResponseGeneration ?: Duration.ZERO) + (Clock.System.now() - genStartTime)
                lastResponseSizeBytes = encodedResponsePlaintext.size
                lastRequesterInTrustList = responseObject.eventData.trustMetadata != null

                val txStartTime = Clock.System.now()
                transport.sendMessage(encryptedResponse)
                durationResponseTransmission = (durationResponseTransmission ?: Duration.ZERO) + (Clock.System.now() - txStartTime)
                numRequestsServed += 1
                numDocumentsPresented += responseObject.eventData.requestedDocuments.size
                completedExchanges.add(
                    CompletedProximityExchange(
                        presentmentData = responseObject.eventData,
                        request = deviceRequest.toDataItem(),
                        response = responseObject.deviceResponse.toDataItem(),
                        sessionTranscript = sessionTranscriptToUse,
                    )
                )

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
    ).run()
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
