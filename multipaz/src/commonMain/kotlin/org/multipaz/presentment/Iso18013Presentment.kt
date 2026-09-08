package org.multipaz.presentment

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.EReaderKey
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "Iso180135Presentment"

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
 */
@Throws(
    CancellationException::class,
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
) {
    // Wait until state changes to CONNECTED, FAILED, or CLOSED
    transport.state.first {
        it == MdocTransport.State.CONNECTED ||
                it == MdocTransport.State.FAILED ||
                it == MdocTransport.State.CLOSED
    }
    if (transport.state.value != MdocTransport.State.CONNECTED) {
        throw IllegalStateException("Expected state CONNECTED but found ${transport.state.value}")
    }
    val initialParams = engagementParams.value
    Logger.dCbor(TAG, "Handover", initialParams.handover)
    Logger.dCbor(TAG, "DeviceEngagement", initialParams.deviceEngagement)
    var numRequestsServed = 0
    var sendSessionTermination = true
    var sessionEncryption: SessionEncryption? = null
    try {
        var activeEReaderKey: EReaderKey? = null
        var activeDeviceRequest: DeviceRequest? = null
        var cachedSelection: CredentialSelection? = null
        var cachedKeyUnlockDataProvider: KeyUnlockDataProvider? = null
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
                transport.isNfcConnected.first { it }
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
                Logger.dCbor(TAG, "SessionTranscript", sessionTranscript)
                encodedSessionTranscript = Cbor.encode(sessionTranscript)
                sessionEncryption = SessionEncryption(
                    role = MdocRole.MDOC,
                    eSelfKey = eDeviceKeyToUse,
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

            Logger.dCbor(TAG, "DeviceRequest", encodedDeviceRequest)
            val deviceRequestCbor = Cbor.decode(encodedDeviceRequest)
            val deviceRequest = DeviceRequest.fromDataItem(deviceRequestCbor)
            deviceRequest.verifyReaderAuthentication(sessionTranscript)
            onDeviceRequest(deviceRequest)

            val selection: CredentialSelection
            val keyUnlockDataProvider: KeyUnlockDataProvider

            val currentActiveRequest = activeDeviceRequest
            if (currentActiveRequest != null &&
                deviceRequest.isStructurallyEquivalent(currentActiveRequest) &&
                cachedSelection != null &&
                cachedKeyUnlockDataProvider != null
            ) {
                Logger.i(TAG, "Reusing existing consent and key authentication for structurally equivalent DeviceRequest on re-tap")
                selection = cachedSelection
                keyUnlockDataProvider = cachedKeyUnlockDataProvider
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
                cachedSelection = selection
                cachedKeyUnlockDataProvider = keyUnlockDataProvider
            }

            onSendingResponse()

            if (transport is NfcHybridTransportMdoc && transport.isNfcOnly) {
                transport.markResponsePending()
                if (!transport.isNfcConnected.value) {
                    Logger.i(TAG, "NFC disconnected after user consent/auth. Waiting for re-tap before generating response...")
                    transport.isNfcConnected.first { it }
                    Logger.i(TAG, "Re-tapped! Proceeding with response generation using latest engagement parameters.")
                }
            }

            // Dynamic session transcript & session encryption update if engagementParams updated (e.g. re-tap)
            val latestParams = engagementParams.value
            if (latestParams != currentParams) {
                val latestEDeviceKey = latestParams.eDeviceKey
                val latestDeviceEngagement = latestParams.deviceEngagement
                val latestHandover = latestParams.handover
                val latestEReaderKey = latestParams.eReaderKey ?: activeEReaderKey!!.publicKey
                val encodedCoseKeyToUse = latestParams.eReaderKey?.let { Cbor.encode(it.toCoseKey().toDataItem()) }
                    ?: activeEReaderKey!!.encodedCoseKey

                sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(latestDeviceEngagement))))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCoseKeyToUse)))
                    add(latestHandover)
                }
                sessionEncryption = SessionEncryption(
                    role = MdocRole.MDOC,
                    eSelfKey = latestEDeviceKey,
                    remotePublicKey = latestEReaderKey,
                    encodedSessionTranscript = Cbor.encode(sessionTranscript),
                    insertSequenceNumbers = insertSequenceNumbers
                )
            }
            val sessionTranscriptToUse = sessionTranscript
            val sessionEncryptionToUse = sessionEncryption!!

            val responseObject = withContext(keyUnlockDataProvider) {
                mdocPresentmentGenerateResponse(
                    selection = selection,
                    deviceRequest = deviceRequest,
                    eReaderKey = (latestParams.eReaderKey ?: activeEReaderKey!!.publicKey),
                    sessionTranscript = sessionTranscriptToUse,
                    source = source,
                    requesterAppId = null,
                    requesterOrigin = null,
                )
            }
            transport.sendMessage(
                sessionEncryptionToUse.encryptMessage(
                    messagePlaintext = Cbor.encode(responseObject.deviceResponse.toDataItem()),
                    statusCode = null
                )
            )
            numRequestsServed += 1

            source.eventLogger?.addEventAsync(
                EventPresentmentIso18013Proximity(
                    presentmentData = responseObject.eventData,
                    request = deviceRequest.toDataItem(),
                    response = responseObject.deviceResponse.toDataItem(),
                    sessionTranscript = sessionTranscriptToUse,
                )
            )

            Logger.i(TAG, "Response sent, keeping connection open")
        }
    } finally {
        if (sendSessionTermination && (transport !is NfcHybridTransportMdoc || !transport.isNfcOnly || transport.isNfcConnected.value)) {
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
            }
        }
        Logger.i(TAG, "Closing transport")
        transport.close()
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
 */
@Throws(
    CancellationException::class,
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
)
