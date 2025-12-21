package org.multipaz.mdoc.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import org.multipaz.mdoc.role.MdocRole
import kotlin.math.min
import kotlin.time.Duration

class NfcTransportMdoc(
    override val role: MdocRole,
    private val options: MdocTransportOptions,
    override val connectionMethod: MdocConnectionMethod
) : MdocTransport() {
    companion object {
        private const val TAG = "NfcTransportMdoc"

        // Must be called by platform when receiving APDUs for Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID
        //
        fun processCommandApdu(
            commandApdu: ByteArray,
            sendResponse: (responseApdu: ByteArray) -> Unit,
        ) {
            val command = CommandApdu.decode(commandApdu)
            if (instances.isEmpty()) {
                Logger.w(TAG, "No NfcTransportMdoc instances")
            } else {
                if (instances.size > 1) {
                    Logger.w(TAG, "${instances.size} NfcTransportMdoc instances, expected just one")
                }
                CoroutineScope(Dispatchers.Default).launch {
                    instances.forEach { instance ->
                        instance.processApdu(
                            command = command,
                            sendResponse = { response ->
                                sendResponse(response.encode())
                            }
                        )
                    }
                }
            }
        }

        // Must be called by platform for deactivation event for Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID
        //
        fun onDeactivated() {
            Logger.i(TAG, "onDeactivated")
            if (instances.isEmpty()) {
                Logger.w(TAG, "No NfcTransportMdoc instances")
            } else {
                if (instances.size > 1) {
                    Logger.w(TAG, "${instances.size} NfcTransportMdoc instances, expected just one")
                }
                CoroutineScope(Dispatchers.Default).launch {
                    // Get a read-only copy since the caller may modify `instances` variable.
                    instances.toList().forEach { instance ->
                        instance.onDeactivated()
                    }
                }
            }
        }

        private val instances = mutableListOf<NfcTransportMdoc>()
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val scanningTime: Duration?
        get() = null

    override suspend fun advertise() {
    }

    private var leReceived = 0
    private val outgoingChunks = mutableListOf<ByteString>()
    private var outgoingChunksRemainingBytesAvailable = 0
    private var envelopeResponseDeferred = false

    private fun getNextOutgoingChunkResponse(): ResponseApdu? {
        if (outgoingChunks.isEmpty()) {
            return null
        }
        val chunk = outgoingChunks.removeAt(0)
        outgoingChunksRemainingBytesAvailable -= chunk.size

        /* Following excerpts are from ISO/IEC 18013-5:2021 clause 8.3.3.1.2 Data retrieval using
         * near field communication (NFC)
         */
        val isLastChunk = outgoingChunks.isEmpty()
        if (isLastChunk) {
            /* If Le ≥ the number of available bytes, the mdoc shall include all
             * available bytes in the response and set the status words to ’90 00’.
             */
            return ResponseApdu(
                status = Nfc.RESPONSE_STATUS_SUCCESS,
                payload = chunk
            )
        } else {
            if (outgoingChunksRemainingBytesAvailable <= leReceived + 255) {
                /* If Le < the number of available bytes ≤ Le + 255, the mdoc shall
                 * include as many bytes in the response as indicated by Le and shall
                 * set the status words to ’61 XX’, where XX is the number of available
                 * bytes remaining. The mdoc reader shall respond with a GET RESPONSE
                 * command where Le is set to XX.
                 */
                val numBytesRemaining = outgoingChunksRemainingBytesAvailable - leReceived
                return ResponseApdu(
                    status = Nfc.RESPONSE_STATUS_CHAINING_RESPONSE_BYTES_STILL_AVAILABLE + numBytesRemaining.and(0xff),
                    payload = chunk
                )
            } else {
                /* If the number of available bytes > Le + 255, the mdoc shall include
                 * as many bytes in the response as indicated by Le and shall set the
                 * status words to ’61 00’. The mdoc reader shall respond with a GET
                 * RESPONSE command where Le is set to the maximum length of the
                 * response data field that is supported by both the mdoc and the mdoc
                 * reader.
                 */
                return ResponseApdu(
                    status = Nfc.RESPONSE_STATUS_CHAINING_RESPONSE_BYTES_STILL_AVAILABLE,
                    payload = chunk
                )
            }
        }
    }

    override suspend fun open(eSenderKey: EcPublicKey) {
        mutex.withLock {
            check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
            Logger.i(TAG, "open")
            instances.add(this)
        }
    }

    override suspend fun sendMessage(message: ByteArray) {
        mutex.withLock {
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
            Logger.i(TAG, "sendMessage")

            if (leReceived == 0) {
                val e = IllegalStateException("Trying to send a message before having received one (leReceived is 0)")
                failTransport(e)
                throw e
            }

            val encapsulatedMessage = encapsulateInDo53(ByteString(message))
            val maxChunkSize = leReceived
            val offsets = 0 until encapsulatedMessage.size step maxChunkSize
            for (offset in offsets) {
                val chunkSize = min(maxChunkSize, encapsulatedMessage.size - offset)
                val chunk = encapsulatedMessage.substring(offset, offset + chunkSize)
                outgoingChunks.add(chunk)
            }
            outgoingChunksRemainingBytesAvailable += encapsulatedMessage.size

            if (envelopeResponseDeferred) {
                Logger.i(TAG, "envelopeResponseDeferred = true, sending")
                sendResponse(getNextOutgoingChunkResponse()!!)
                envelopeResponseDeferred = false
            }
        }
    }

    override suspend fun waitForMessage(): ByteArray {
        mutex.withLock {
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
            Logger.i(TAG, "waitForMessage")
        }
        return incomingMessages.receive().toByteArray()
    }

    override suspend fun close() {
        mutex.withLock {
            if (_state.value == State.FAILED || _state.value == State.CLOSED) {
                return
            }
            Logger.i(TAG, "close")
            incomingMessages.close()
            _state.value = State.CLOSED
        }
    }

    private var inError = false
    private var applicationSelected = false

    private fun failTransport(error: Throwable) {
        check(mutex.isLocked) { "failTransport called without holding lock" }
        inError = true
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        Logger.w(TAG, "Failing transport with error", error)
        incomingMessages.close()
        _state.value = State.FAILED
    }

    class NfcError(
        val status: Int,
        message: String
    ): Exception(message) {}

    private fun processSelectApplication(command: CommandApdu): ResponseApdu {
        check(!applicationSelected) { "Application already selected" }
        val requestedApplicationId = command.payload
        if (requestedApplicationId != Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID) {
            throw NfcError(
                status = Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND,
                message = "SelectApplication: Expected AID " +
                        "${Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID.toByteArray().toHex()} but got " +
                        "${requestedApplicationId.toByteArray().toHex()}"
            )
        }
        applicationSelected = true
        // We're open for business..
        _state.value = State.CONNECTED
        return ResponseApdu(Nfc.RESPONSE_STATUS_SUCCESS)
    }

    private var currentIncomingEncapsulatedMessage = ByteStringBuilder()
    private val incomingMessages = Channel<ByteString>(Channel.UNLIMITED)

    private fun processEnvelope(command: CommandApdu): ResponseApdu? {
        Logger.i(TAG, "processEnvelope")
        check(applicationSelected) { "Application not selected" }
        currentIncomingEncapsulatedMessage.append(command.payload)
        if (command.cla == Nfc.CLA_CHAIN_LAST) {

            // For the last ENVELOPE command in a chain, Le shall be set to the maximum length
            // of the response data field that is supported by both the mdoc and the mdoc reader.
            //
            // We'll need this for later.
            if (leReceived == 0) {
                leReceived = command.le
                Logger.i(TAG, "LE in last ENVELOPE is $leReceived")
            }

            // No more data coming.
            val message = extractFromDo53(currentIncomingEncapsulatedMessage.toByteString())
            currentIncomingEncapsulatedMessage = ByteStringBuilder()
            incomingMessages.trySend(message)

            val chunkResponse = getNextOutgoingChunkResponse()
            if (chunkResponse == null) {
                envelopeResponseDeferred = true
                return null
            } else {
                return chunkResponse
            }
        } else if (command.cla == Nfc.CLA_CHAIN_NOT_LAST) {
            // More data is coming
            check(command.le == 0) { "Expected LE 0 for non-last ENVELOPE, got ${command.le}" }
            Logger.i(TAG, "processEnvelope: returning SUCCESS")
            return ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS)
        } else {
            throw IllegalStateException("Expected CLA 0x00 or 0x10 for ENVELOPE, got ${command.cla}")
        }
    }

    private fun processGetResponse(command: CommandApdu): ResponseApdu {
        Logger.i(TAG, "processGetResponse")
        check(applicationSelected) { "Application not selected" }
        val chunkResponse = getNextOutgoingChunkResponse()
        check(chunkResponse != null)
        return chunkResponse
    }

    private lateinit var sendResponse: (response: ResponseApdu) -> Unit

    internal suspend fun processApdu(
        command: CommandApdu,
        sendResponse: (response: ResponseApdu) -> Unit,
    ) {
        this.sendResponse = sendResponse
        val response = processApdu(command)
        if (response != null) {
            mutex.withLock {
                sendResponse(response)
            }
        }
    }

    private suspend fun processApdu(
        command: CommandApdu,
    ): ResponseApdu? {
        if (inError) {
            Logger.w(TAG, "processApdu: Already in error state, responding to APDU with status 6f00")
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS)
        }
        try {
            when (command.ins) {
                Nfc.INS_SELECT -> {
                    when (command.p1) {
                        Nfc.INS_SELECT_P1_APPLICATION -> return processSelectApplication(command)
                    }
                }
                Nfc.INS_ENVELOPE -> return processEnvelope(command)
                Nfc.INS_GET_RESPONSE -> return processGetResponse(command)
            }
            failTransport(Error("Command APDU $command not supported, returning 6d00"))
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID)
        } catch (error: Throwable) {
            error.printStackTrace()
            failTransport(Error("Error processing APDU: ${error.message}", error))
            val status = if (error is NfcError) {
                error.status
            } else {
                Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS
            }
            return ResponseApdu(status)
        }
    }

    internal suspend fun onDeactivated() {
        mutex.withLock {
            instances.remove(this)
            failTransport(Error("onDeactivated"))
        }
    }
}

