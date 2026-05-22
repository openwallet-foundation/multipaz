package org.multipaz.mdoc.nfc

import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.encapsulateInDo53
import org.multipaz.mdoc.transport.extractFromDo53
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.math.min

/**
 * Helper used for NFC engagement on the mdoc side.
 *
 * This implements NFC engagement v2 according to ISO/IEC 18013 Second Edition
 *
 * APDUs received from the NFC tag reader should be passed to the [processApdu] method.
 *
 * The [apduCommandMaxSize] parameter is used to tell the mdoc reader that it needs to send its commands in APDUs no
 * larger than this size and this value must be smaller or equal to 65536, the maximum size of an Extended APDU.
 * Since this class is usually not used in an environment where a 64 KiB buffer is a showstopper we default to the
 * maximum size.
 *
 * @param eDeviceKey EDeviceKey as per ISO/IEC 18013-5:2021.
 * @param onHandoverComplete the function to call when handover is complete.
 * @param onMessageReceived the function to call when a data message has been received over NFC.
 * @param onError the function to call if an error occurs.
 * @param negotiatedHandoverPicker a function to choose one of the connection methods from the mdoc reader. This
 * always contain a [MdocConnectionMethodNfcV2] instance and contains others if the mdoc reader is capable of data
 * transfer over e.g. BLE or Wifi Aware. If the mdoc only supports data transfer over NFC, it should return the element
 * for [MdocConnectionMethodNfcV2].
 * @property capabilities the capabilities to convey to the mdoc reader.
 * @param apduCommandMaxSize the maximum length of the command data field.
 */
class MdocNfcV2EngagementHelper(
    val eDeviceKey: EcPublicKey,
    val onHandoverComplete: (
        connectionMethod: MdocConnectionMethod,
        encodedDeviceEngagement: ByteString,
        handover: DataItem) -> Unit,
    val onMessageReceived: suspend (ByteString) -> Unit,
    val onError: (error: Exception) -> Unit,
    val negotiatedHandoverPicker: (connectionMethods: List<MdocConnectionMethod>) -> MdocConnectionMethod,
    val capabilities: Map<Capability, DataItem> = mapOf(
        Capability.READER_AUTH_ALL_SUPPORT to true.toDataItem(),
        Capability.EXTENDED_REQUEST_SUPPORT to true.toDataItem()
    ),
    val apduCommandMaxSize: Long = 65536L,
) {
    companion object {
        private const val TAG = "MdocNfcV2EngagementHelper"
    }

    private enum class HandoverState {
        NOT_STARTED,
        EXPECT_HANDOVER_REQUEST_MESSAGE,
        EXPECT_PAYLOAD_MESSAGES,
    }

    private var handoverState = HandoverState.NOT_STARTED

    private var inError = false

    private fun raiseError(errorMessage: String, cause: Exception? = null) {
        inError = true
        onError(IllegalStateException(errorMessage, cause))
    }

    private suspend fun processSelectApplication(command: CommandApdu): ResponseApdu {
        val requestedApplicationId = command.payload
        if (requestedApplicationId != Nfc.MDOC_NFC_ENGAGEMENT_V2_AID) {
            raiseError(
                "SelectApplication: Expected Engagement v2 AID but got " +
                        requestedApplicationId.toByteArray().toHex()
            )
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
        }
        handoverState = HandoverState.EXPECT_HANDOVER_REQUEST_MESSAGE

        val nfcV2SelectApplicationPayload = buildCborMap {
            put(0, apduCommandMaxSize)
        }
        return ResponseApdu(
            status = Nfc.RESPONSE_STATUS_SUCCESS,
            payload = ByteString(Cbor.encode(nfcV2SelectApplicationPayload))
        )
    }

    private suspend fun nfcV2TransactHandleHandoverRequest(message: ByteString): ByteString {
        val nfcV2HandoverRequest = Cbor.decode(message.toByteArray())
        Logger.iCbor(TAG, "Received nfcV2HandoverRequest", nfcV2HandoverRequest)

        // ReaderEngagement is at key 0
        val readerEngagementDataItem = nfcV2HandoverRequest.get(0)

        // DeviceRetrievalMethods is at key 2
        val availableConnectionMethods = readerEngagementDataItem.get(2).asArray.mapNotNull {
            val cm = MdocConnectionMethod.fromDeviceEngagement(Cbor.encode(it))
            if (cm == null) {
                Logger.iCbor(TAG, "Unknown data retrieval method", it)
            }
            cm
        }

        // The reader *MUST* always include MdocConnectionMethodNfcV2, check this
        if (availableConnectionMethods.find { it is MdocConnectionMethodNfcV2 } == null) {
            throw IllegalStateException(
                "No DeviceRetrievalMethodNfcV2DataTransfer element in DeviceRetrievalMethods in ReaderEngagement"
            )
        }

        val disambiguatedConnectionMethods = MdocConnectionMethod.disambiguate(
            availableConnectionMethods,
            MdocRole.MDOC
        )

        val selectedMethod = negotiatedHandoverPicker(disambiguatedConnectionMethods)

        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey) {
            addConnectionMethod(selectedMethod)
            capabilities.forEach { (capability, value) ->
                addCapability(capability, value)
            }
        }.toDataItem()
        val encodedDeviceEngagement = Cbor.encode(deviceEngagement)

        val nfcV2HandoverSelect = buildCborMap {
            put(0, deviceEngagement)
        }
        val encodedNfcV2HandoverSelect = Cbor.encode(nfcV2HandoverSelect)

        val handover = buildCborArray {
            add(encodedNfcV2HandoverSelect)    // Handover Select message
            add(message.toByteArray())           // Handover Request message
        }

        handoverState = HandoverState.EXPECT_PAYLOAD_MESSAGES

        onHandoverComplete(
            selectedMethod,
            ByteString(encodedDeviceEngagement),
            handover
        )

        return ByteString(encodedNfcV2HandoverSelect)
    }

    private val queueForPayloadReply = Channel<ByteString>(Channel.UNLIMITED)

    /**
     * Function for sending data via NFC.
     *
     * This data will be queued up and returned to the mdoc reader as a reply.
     *
     * @param message the data to send.
     */
    suspend fun sendMessage(message: ByteString) {
        try {
            queueForPayloadReply.send(message)
        } catch (e: Throwable) {
            Logger.w(TAG, "Ignoring failure sending message of ${message.size} over NFC", e)
        }
    }

    private suspend fun nfcV2TransactHandlePayloadMessages(message: ByteString): ByteString {
        onMessageReceived(message)
        val responsePayloadMessage = queueForPayloadReply.receive()
        return responsePayloadMessage
    }

    private suspend fun nfcV2Transact(message: ByteString): ByteString {
        return when (handoverState) {
            HandoverState.NOT_STARTED -> throw IllegalStateException("Unexpected message - Negotiated Handover not started")
            HandoverState.EXPECT_HANDOVER_REQUEST_MESSAGE -> nfcV2TransactHandleHandoverRequest(message)
            HandoverState.EXPECT_PAYLOAD_MESSAGES -> nfcV2TransactHandlePayloadMessages(message)
        }
    }

    private var leReceived = 0

    private var currentIncomingEncapsulatedMessage = ByteStringBuilder()
    private val outgoingChunks = mutableListOf<ByteString>()
    private var outgoingChunksRemainingBytesAvailable = 0

    private fun getNextOutgoingChunkResponse(): ResponseApdu {
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

    private suspend fun processEnvelope(command: CommandApdu): ResponseApdu {
        Logger.i(TAG, "processEnvelope")
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

            val responseMessage = nfcV2Transact(message)

            val encapsulatedMessage = encapsulateInDo53(responseMessage)
            val maxChunkSize = leReceived
            val offsets = 0 until encapsulatedMessage.size step maxChunkSize
            for (offset in offsets) {
                val chunkSize = min(maxChunkSize, encapsulatedMessage.size - offset)
                val chunk = encapsulatedMessage.substring(offset, offset + chunkSize)
                outgoingChunks.add(chunk)
            }
            outgoingChunksRemainingBytesAvailable += encapsulatedMessage.size

            val chunkResponse = getNextOutgoingChunkResponse()
            return chunkResponse
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
        val chunkResponse = getNextOutgoingChunkResponse()
        return chunkResponse
    }

    /**
     * Process APDUs received from the remote NFC tag reader.
     *
     * @param command The command received.
     * @return the response.
     */
    suspend fun processApdu(command: CommandApdu): ResponseApdu {
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
            raiseError("Command APDU $command not supported, returning 6d00")
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            raiseError("Error processing APDU: ${error.message}", error)
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS)
        }
    }

    /**
     * Must be called when the session is deactivated, e.g. when the NFC tag reader leaves the field.
     */
    fun processDeactivated(reason: Int) {
        queueForPayloadReply.cancel(CancellationException("The session was deactivated"))
    }
}

