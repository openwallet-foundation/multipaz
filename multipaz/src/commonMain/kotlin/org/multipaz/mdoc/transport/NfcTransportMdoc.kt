package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.nfc.MdocNfcPresentmentService
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class NfcTransportMdoc(
    override val role: MdocRole,
    private val options: MdocTransportOptions,
    override val connectionMethod: MdocConnectionMethod
) : MdocTransport() {
    companion object {
        private const val TAG = "NfcTransportMdoc"

        /**
         * Processes a [CommandApdu] received for [Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID].
         *
         * @param commandApdu the command APDU to process.
         * @return the [ResponseApdu] to send back to the NFC reader.
         */
        suspend fun processCommandApdu(
            commandApdu: CommandApdu,
        ): ResponseApdu {
            var currentInstances = instances.value
            if (currentInstances.isEmpty() &&
                commandApdu.isApplicationSelect &&
                MdocNfcPresentmentService.transactionJob?.isActive == true
            ) {
                currentInstances = withTimeoutOrNull(500.milliseconds) {
                    instances.first { it.isNotEmpty() }
                } ?: emptyList()
            }
            if (currentInstances.isEmpty()) {
                Logger.w(TAG, "No NfcTransportMdoc instances, returning 6a82")
                return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
            }
            if (currentInstances.size > 1) {
                Logger.w(TAG, "${currentInstances.size} NfcTransportMdoc instances, expected just one")
            }
            return currentInstances.first().processApdu(commandApdu)
        }

        /**
         * Must be called when NFC is deactivated for [Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID].
         */
        suspend fun onDeactivated() {
            Logger.i(TAG, "onDeactivated")
            val currentInstances = instances.value
            if (currentInstances.isEmpty()) {
                Logger.w(TAG, "No NfcTransportMdoc instances")
            } else {
                if (currentInstances.size > 1) {
                    Logger.w(TAG, "${currentInstances.size} NfcTransportMdoc instances, expected just one")
                }
                currentInstances.forEach { instance ->
                    instance.onDeactivated()
                }
            }
        }

        private val instances = MutableStateFlow<List<NfcTransportMdoc>>(emptyList())
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val scanningTime: Duration?
        get() = null

    private var _bytesSent: Long = 0
    private var _bytesReceived: Long = 0
    private var _numMessagesSent: Int = 0
    private var _numMessagesReceived: Int = 0

    override val stats: MdocTransportStats
        get() = MdocTransportStats(
            transportType = MdocTransportType.NFC_DATA_TRANSFER_MODE,
            role = role,
            bytesSent = _bytesSent,
            bytesReceived = _bytesReceived,
            numMessagesSent = _numMessagesSent,
            numMessagesReceived = _numMessagesReceived,
        )

    override suspend fun advertise() {
    }

    private var leReceived = 0
    private val outgoingChunks = mutableListOf<ByteString>()
    private var outgoingChunksRemainingBytesAvailable = 0
    private var deferredEnvelopeResponse: CompletableDeferred<ResponseApdu>? = null

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
            if (outgoingChunksRemainingBytesAvailable <= 255) {
                /* If Le < the number of available bytes ≤ Le + 255, the mdoc shall
                 * include as many bytes in the response as indicated by Le and shall
                 * set the status words to ’61 XX’, where XX is the number of available
                 * bytes remaining. The mdoc reader shall respond with a GET RESPONSE
                 * command where Le is set to XX.
                 */
                val numBytesRemaining = outgoingChunksRemainingBytesAvailable
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
            instances.update { it + this }
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

            if (message.isNotEmpty()) {
                _bytesSent += message.size
                _numMessagesSent += 1
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

            deferredEnvelopeResponse?.let { deferred ->
                Logger.i(TAG, "deferredEnvelopeResponse != null, completing")
                deferredEnvelopeResponse = null
                deferred.complete(getNextOutgoingChunkResponse()!!)
            }
        }
    }

    override suspend fun waitForMessage(): ByteArray {
        mutex.withLock {
            if (_state.value == State.FAILED) {
                throw MdocTransportException("Transport already failed")
            }
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
            Logger.i(TAG, "waitForMessage")
        }
        try {
            val msg = incomingMessages.receive().toByteArray()
            if (msg.isNotEmpty()) {
                _bytesReceived += msg.size
                _numMessagesReceived += 1
            }
            return msg
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (_state.value == State.CLOSED) {
                throw MdocTransportClosedException("Transport was closed while waiting for message")
            } else {
                mutex.withLock {
                    failTransport(error)
                }
                throw MdocTransportException("Failed while waiting for message", error)
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            instances.update { it - this }
            deferredEnvelopeResponse?.complete(
                ResponseApdu(
                    status = Nfc.RESPONSE_STATUS_SUCCESS,
                    payload = encapsulateInDo53(ByteString())
                )
            )
            deferredEnvelopeResponse = null
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
        deferredEnvelopeResponse?.complete(ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS))
        deferredEnvelopeResponse = null
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

    private suspend fun processSelectApplication(command: CommandApdu): ResponseApdu {
        return mutex.withLock {
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
            ResponseApdu(Nfc.RESPONSE_STATUS_SUCCESS)
        }
    }

    private var currentIncomingEncapsulatedMessage = ByteStringBuilder()
    private val incomingMessages = Channel<ByteString>(Channel.UNLIMITED)

    private suspend fun processEnvelope(command: CommandApdu): ResponseApdu {
        Logger.i(TAG, "processEnvelope")
        val deferred = mutex.withLock {
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
                if (chunkResponse != null) {
                    return chunkResponse
                }
                CompletableDeferred<ResponseApdu>().also {
                    deferredEnvelopeResponse = it
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
        return deferred.await()
    }

    private suspend fun processGetResponse(command: CommandApdu): ResponseApdu {
        Logger.i(TAG, "processGetResponse")
        return mutex.withLock {
            check(applicationSelected) { "Application not selected" }
            val chunkResponse = getNextOutgoingChunkResponse()
            check(chunkResponse != null)
            chunkResponse
        }
    }

    internal suspend fun processApdu(
        command: CommandApdu,
    ): ResponseApdu {
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
            mutex.withLock {
                failTransport(IllegalStateException("Command APDU $command not supported, returning 6d00"))
            }
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            error.printStackTrace()
            mutex.withLock {
                failTransport(IllegalStateException("Error processing APDU: ${error.message}", error))
            }
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
            instances.update { it - this }
            failTransport(IllegalStateException("onDeactivated"))
        }
    }
}

