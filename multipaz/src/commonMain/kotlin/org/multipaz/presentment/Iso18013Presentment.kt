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
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
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
    transport is NfcHybridTransportMdoc -> EngagementType.NFC_CONCURRENT_CHANNEL_ENGAGEMENT
    handover == Simple.NULL -> EngagementType.QR_CODE
    handover is CborArray && handover.items.getOrNull(1) == Simple.NULL -> EngagementType.NFC_STATIC_HANDOVER
    else -> EngagementType.NFC_NEGOTIATED_HANDOVER
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
    val startTime = Clock.System.now()
    var connectedTime: Instant? = null
    var durationEngagementToConnected: Duration? = null
    var durationConnectedToRequestReceived: Duration? = null
    var durationUserConsentAndAuth: Duration? = null
    var durationResponseGeneration: Duration? = null
    var durationResponseTransmission: Duration? = null
    var numRequestsServed = 0
    var numDocumentsPresented = 0
    var numNfcReTaps = 0
    var lastRequestSizeBytes: Int? = null
    var lastResponseSizeBytes: Int? = null
    var lastRequesterInTrustList: Boolean? = null
    var lastSessionTranscript: DataItem? = null
    var lastDeviceRequest: DeviceRequest? = null
    val presentmentWarnings = mutableListOf<TransportWarning>()
    val completedExchanges = mutableListOf<CompletedProximityExchange>()
    var finalOutcome = PresentmentOutcome.SUCCESS
    var sendSessionTermination = false
    var sessionEncryption: SessionEncryption? = null

    fun buildTelemetry(
        outcome: PresentmentOutcome,
    ): PresentmentTelemetry {
        val currentStats = transport.stats
        val engagementType = inferEngagementType(transport, engagementParams.value.handover)
        val mergedWarnings = (presentmentWarnings + currentStats.warnings).distinct()
        val effectiveNfcReTaps = maxOf(numNfcReTaps, (transport as? NfcHybridTransportMdoc)?.numNfcReTaps ?: 0)
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
        var activeEReaderKey: EReaderKey? = null
        var activeDeviceRequest: DeviceRequest? = null
        var cachedSelection: CredentialSelection? = null
        var cachedKeyUnlockDataProvider: KeyUnlockDataProvider? = null
        var cachedFaceMatchedCredentials: List<Credential>? = null
        lateinit var sessionTranscript: DataItem
        lateinit var encodedSessionTranscript: ByteArray
        while (true) {
            Logger.i(TAG, "Waiting for message from reader...")
            onWaitingForRequest()
            if (transport is NfcHybridTransportMdoc && transport.isNfcOnly && !transport.isNfcConnected.value) {
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
            val sessionData = try {
                if (transport is NfcHybridTransportMdoc && transport.isNfcOnly && numRequestsServed > 0) {
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
            if (sessionData.isEmpty()) {
                Logger.i(TAG, "Received transport-specific session termination message from reader")
                sendSessionTermination = false
                break
            }

            if (durationConnectedToRequestReceived == null) {
                durationConnectedToRequestReceived = Clock.System.now() - connectedTime
            }

            val currentParams = engagementParams.value
            val eDeviceKeyToUse = currentParams.eDeviceKey
            val deviceEngagementToUse = currentParams.deviceEngagement
            val handoverToUse = currentParams.handover
            val eReaderKeyToUse = currentParams.eReaderKey

            if (sessionEncryption == null || (eReaderKeyToUse != null && eReaderKeyToUse != activeEReaderKey?.publicKey)) {
                val currentEReaderKey = eReaderKeyToUse?.let {
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
            if (transport is NfcHybridTransportMdoc && transport.isNfcOnly) {
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
                selection = cachedSelection
                keyUnlockDataProvider = cachedKeyUnlockDataProvider
                faceMatchedCredentials = cachedFaceMatchedCredentials
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

            if (transport is NfcHybridTransportMdoc && transport.isNfcOnly) {
                transport.markResponsePending()
                if (!transport.isNfcConnected.value) {
                    Logger.i(TAG, "NFC disconnected after user consent/auth. Waiting for re-tap before generating response...")
                    presentmentWarnings.add(TransportWarning(TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP))
                    transport.isNfcConnected.first { it }
                    numNfcReTaps += 1
                    Logger.i(TAG, "Re-tapped! Proceeding with response generation using latest engagement parameters.")
                }
            }

            // Dynamic session transcript & session encryption update if engagementParams updated (e.g. re-tap)
            val latestParams = engagementParams.value
            if (latestParams != currentParams) {
                if (latestParams.eDeviceKey !== currentParams.eDeviceKey) {
                    currentParams.eDeviceKey.close()
                }
                val latestEDeviceKey = latestParams.eDeviceKey
                val latestDeviceEngagement = latestParams.deviceEngagement
                val latestHandover = latestParams.handover
                val latestEReaderKey = latestParams.eReaderKey ?: activeEReaderKey!!.publicKey
                val encodedCoseKeyToUse = latestParams.eReaderKey?.let { Cbor.encode(it.toCoseKey().toDataItem()) }
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
                )
            }
            val sessionTranscriptToUse = sessionTranscript
            val sessionEncryptionToUse = sessionEncryption!!

            val genStartTime = Clock.System.now()
            val responseObject = withContext(keyUnlockDataProvider) {
                mdocPresentmentGenerateResponse(
                    selection = selection,
                    deviceRequest = deviceRequest,
                    eReaderKey = (latestParams.eReaderKey ?: activeEReaderKey!!.publicKey),
                    sessionTranscript = sessionTranscriptToUse,
                    source = source,
                    requesterAppId = null,
                    requesterOrigin = null,
                    faceMatchedCredentials = faceMatchedCredentials,
                )
            }
            val encodedResponsePlaintext = Cbor.encode(responseObject.deviceResponse.toDataItem())
            val encryptedResponse = sessionEncryptionToUse.encryptMessage(
                messagePlaintext = encodedResponsePlaintext,
                statusCode = null
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
        try {
            if (sendSessionTermination &&
                currentCoroutineContext().isActive &&
                (transport !is NfcHybridTransportMdoc || !transport.isNfcOnly || transport.isNfcConnected.value)
            ) {
                Logger.i(TAG, "Sending session-termination")
                try {
                    transport.sendMessage(
                        SessionEncryption.encodeStatus(
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            sequenceNumber = if (insertSequenceNumbers) {
                                sessionEncryption?.nextSequenceNumber
                            } else {
                                null
                            }
                        )
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Caught error while sending session-termination", e)
                    presentmentWarnings.add(TransportWarning(TransportWarningCode.SESSION_TERMINATION_SEND_FAILED))
                }
            }
        } finally {
            val resolvedTelemetry = buildTelemetry(
                outcome = finalOutcome,
            )
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
