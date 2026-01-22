package org.multipaz.presentment.model

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.document.Document
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.EReaderKey
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "Iso180135Presentment"

/**
 * Performs proximity presentment according to ISO/IEC 18013-5:2021.
 *
 * This implementation allows handling multiple requests from the reader and expects the reader
 * to close the connection.
 *
 * @param transport a [MdocTransport] in the [MdocTransport.State.CONNECTING] or [MdocTransport.State.CONNECTED] state.
 * @param eDeviceKey the ephemeral device key.
 * @param deviceEngagement the device engagement.
 * @param handover the handover.
 * @param source the source of truth used for presentment.
 * @param keyAgreementPossible the list of curves for which key agreement is possible.
 * @param timeout the maximum time to wait for the first message from the remote reader or `null` to wait indefinitely.
 * @param timeoutSubsequentRequests the maximum time to wait for subsequent messages or `null` to wait indefinitely.
 * @param onWaitingForRequest called when waiting for a request from the remote reader.
 * @param onWaitingForUserInput called when waiting for input from the user (consent or authentication)
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @param onSendingResponse called when sending a response to the remote reader.
 * @throws MdocTransportClosedException if [transport] was closed.
 * @throws Iso18013PresentmentTimeoutException if the reader didn't send a message without the given [timeout].
 * @throws PresentmentCanceled if the user canceled in a consent prompt.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    MdocTransportClosedException::class,
    Iso18013PresentmentTimeoutException::class,
    PresentmentCanceled::class
)
suspend fun Iso18013Presentment(
    transport: MdocTransport,
    eDeviceKey: EcPrivateKey,
    deviceEngagement: DataItem,
    handover: DataItem,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    timeout: Duration? = 10.seconds,
    timeoutSubsequentRequests: Duration? = 30.seconds,
    onWaitingForRequest: () -> Unit = {},
    onWaitingForUserInput: () -> Unit = {},
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    onSendingResponse: () -> Unit = {},
) {
    // Wait until state changes to CONNECTED, FAILED, or CLOSED
    transport.state.first {
        it == MdocTransport.State.CONNECTED ||
                it == MdocTransport.State.FAILED ||
                it == MdocTransport.State.CLOSED
    }
    if (transport.state.value != MdocTransport.State.CONNECTED) {
        throw Error("Expected state CONNECTED but found ${transport.state.value}")
    }
    //Logger.iCbor(TAG, "DeviceEngagement", mechanism.encodedDeviceEngagement.toByteArray())
    var numRequestsServed = 0
    var sendSessionTermination = true
    try {
        var sessionEncryption: SessionEncryption? = null
        lateinit var eReaderKey: EReaderKey
        lateinit var sessionTranscript: DataItem
        lateinit var encodedSessionTranscript: ByteArray
        while (true) {
            Logger.i(TAG, "Waiting for message from reader...")
            onWaitingForRequest()
            val timeoutToUse = if (numRequestsServed == 0) timeout else timeoutSubsequentRequests
            val sessionData = if (timeoutToUse == null) {
                transport.waitForMessage()
            } else {
                try {
                    withTimeout(timeoutToUse) {
                        transport.waitForMessage()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw Iso18013PresentmentTimeoutException("Timed out waiting for message from remote reader", e)
                }
            }
            if (sessionData.isEmpty()) {
                Logger.i(TAG, "Received transport-specific session termination message from reader")
                sendSessionTermination = false
                break
            }

            if (sessionEncryption == null) {
                eReaderKey = SessionEncryption.getEReaderKey(sessionData)
                sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceEngagement))))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(eReaderKey.encodedCoseKey)))
                    add(handover)
                }
                encodedSessionTranscript = Cbor.encode(sessionTranscript)
                sessionEncryption = SessionEncryption(
                    MdocRole.MDOC,
                    eDeviceKey,
                    eReaderKey.publicKey,
                    encodedSessionTranscript,
                )
            }
            val (encodedDeviceRequest, status) = sessionEncryption.decryptMessage(sessionData)

            if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                Logger.i(TAG, "Received session termination message from reader")
                sendSessionTermination = false
                break
            }

            val deviceRequestCbor = Cbor.decode(encodedDeviceRequest!!)
            Logger.iCbor(TAG, "DeviceRequest", deviceRequestCbor)
            val deviceRequest = DeviceRequest.fromDataItem(deviceRequestCbor)
            val deviceResponse = mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = eReaderKey.publicKey,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = keyAgreementPossible,
                onWaitingForUserInput = onWaitingForUserInput,
                onDocumentsInFocus = onDocumentsInFocus
            )
            onSendingResponse()
            transport.sendMessage(
                sessionEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceResponse.toDataItem()),
                    statusCode = null
                )
            )
            numRequestsServed += 1
            Logger.i(TAG, "Response sent, keeping connection open")
        }
    } finally {
        if (sendSessionTermination) {
            Logger.i(TAG, "Sending session-termination")
            try {
                transport.sendMessage(
                    SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                )
            } catch (e: Throwable) {
                Logger.w(TAG, "Caught error while sending session-termination", e)
            }
        }
        Logger.i(TAG, "Closing transport")
        transport.close()
    }
}
