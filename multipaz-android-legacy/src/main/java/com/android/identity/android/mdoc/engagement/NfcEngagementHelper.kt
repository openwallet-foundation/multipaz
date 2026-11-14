package com.android.identity.android.mdoc.engagement

import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.android.identity.android.mdoc.engagement.NfcEngagementHelper.Listener
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransport.Companion.fromConnectionMethod
import com.android.identity.android.mdoc.transport.DataTransportOptions
import com.android.identity.android.util.NfcUtil
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.EcPublicKey
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod.Companion.combine
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod.Companion.disambiguate
import com.android.identity.mdoc.engagement.EngagementGenerator
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.Executor

/**
 * Helper used for NFC engagement.
 *
 *
 * This implements NFC engagement as defined in ISO/IEC 18013-5:2021.
 *
 *
 * Applications can instantiate a [NfcEngagementHelper] using
 * [NfcEngagementHelper.Builder] to specify the NFC engagement
 * type (static or negotiated) and other details, such as which device
 * retrieval methods to offer with static handover.
 *
 *
 * If negotiated handover is used, [Listener.onTwoWayEngagementDetected]
 * is called when the NFC tag reader has selected the connection handover service.
 *
 *
 * When a remote mdoc reader connects to either one of the transports advertised
 * via static handover or one of the transports offered by the reader via
 * negotiated handover, [Listener.onDeviceConnected] is called
 * and the application can use the passed-in [DataTransport] to create a
 * [com.android.identity.android.mdoc.deviceretrieval.DeviceRetrievalHelper]
 * to start the transaction.
 */
class NfcEngagementHelper private constructor(
    private val context: Context,
    private val eDeviceKey: EcPublicKey,
    private val options: DataTransportOptions,
    private val negotiatedHandoverWtInt: Int,
    private val negotiatedHandoverMaxNumWaitingTimeExtensions: Int,
    private val listener: Listener?,
    private val executor: Executor?
) {
    private var staticHandoverConnectionMethods: List<MdocConnectionMethod>? = null
    private var inhibitCallbacks = false

    // Dynamically created when a NFC tag reader is in the field
    private var transports = mutableListOf<DataTransport>()

    /**
     * Gets the bytes of the `DeviceEngagement` CBOR.
     *
     * This returns the bytes of the `DeviceEngagement` CBOR according to
     * ISO/IEC 18013-5:2021 section 8.2.2.1.
     */
    val deviceEngagement: ByteArray

    /**
     * Gets the bytes of the `Handover` CBOR.
     *
     * This returns the bytes of the `Handover` CBOR according to
     * ISO/IEC 18013-5:2021 section 9.1.5.1.
     */
    lateinit var handover: ByteArray
    
    private var reportedDeviceConnecting = false
    private var handoverSelectMessage: ByteArray? = null
    private var handoverRequestMessage: ByteArray? = null
    
    private var mUsingNegotiatedHandover = false

    private var negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
    private var selectedNfcFile: ByteArray? = null
    private var testingDoNotStartTransports = false

    /**
     * Close all transports currently being listened on.
     *
     * No callbacks will be done on a listener after calling this.
     *
     * This method is idempotent so it is safe to call multiple times.
     */
    fun close() {
        inhibitCallbacks = true
        if (transports.isNotEmpty()) {
            var numTransportsClosed = 0
            for (transport in transports) {
                transport.close()
                numTransportsClosed += 1
            }
            Logger.d(TAG, "Closed $numTransportsClosed transports")
            transports.clear()
        }
        negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
        selectedNfcFile = null
    }

    fun testingDoNotStartTransports() {
        testingDoNotStartTransports = true
    }

    // Used by both static and negotiated handover... safe to be called multiple times.
    private fun setupTransports(connectionMethods: List<MdocConnectionMethod>): List<MdocConnectionMethod> {
        if (testingDoNotStartTransports) {
            Logger.d(TAG, "Test mode, not setting up transports")
            return connectionMethods
        }
        val setupConnectionMethods = mutableListOf<MdocConnectionMethod>()
        Logger.d(TAG, "Setting up transports")
        val timeStartedSettingUpTransports = System.currentTimeMillis()
        val encodedEDeviceKeyBytes = Cbor.encode(
            Tagged(24, Bstr(Cbor.encode(eDeviceKey.toCoseKey().toDataItem()))))

        // Need to disambiguate the connection methods here to get e.g. two ConnectionMethods
        // if both BLE modes are available at the same time.
        val disambiguatedMethods = disambiguate(connectionMethods, MdocRole.MDOC)
        for (cm in disambiguatedMethods) {
            val transport = fromConnectionMethod(
                context, cm, DataTransport.Role.MDOC, options
            )
            transport.setEDeviceKeyBytes(encodedEDeviceKeyBytes)
            transports.add(transport)
            Logger.d(TAG, "Added transport for $cm")
        }

        // Careful, we're using the user-provided Executor below so these callbacks might happen
        // in another thread than we're in right now. For example this happens if using
        // ThreadPoolExecutor.
        //
        for (transport in transports) {
            transport.setListener(object : DataTransport.Listener {
                override fun onConnecting() {
                    Logger.d(TAG, "onConnecting for $transport")
                    peerIsConnecting(transport)
                }

                override fun onConnected() {
                    Logger.d(TAG, "onConnected for $transport")
                    peerHasConnected(transport)
                }

                override fun onDisconnected() {
                    Logger.d(TAG, "onDisconnected for $transport")
                    transport.close()
                }

                override fun onError(error: Throwable) {
                    transport.close()
                    reportError(error)
                }

                override fun onMessageReceived() {
                    Logger.d(TAG, "onMessageReceived for $transport")
                }

                override fun onTransportSpecificSessionTermination() {
                    Logger.d(TAG, "Received transport-specific session termination")
                    transport.close()
                }
            }, executor)
            Logger.d(TAG, "Connecting to transport $transport")
            transport.connect()
            setupConnectionMethods.add(transport.connectionMethodForTransport)
        }
        val setupTimeMillis = System.currentTimeMillis() - timeStartedSettingUpTransports
        Logger.d(TAG, "All transports set up in $setupTimeMillis msec")
        return setupConnectionMethods
    }

    /**
     * Method to call when link has been lost or if a different NFC AID has been selected.
     *
     * This should be called from the application's implementation of
     * [android.nfc.cardemulation.HostApduService.onDeactivated].
     *
     * @param reason Either [android.nfc.cardemulation.HostApduService.DEACTIVATION_LINK_LOSS]
     * or [android.nfc.cardemulation.HostApduService.DEACTIVATION_DESELECTED].
     */
    fun nfcOnDeactivated(reason: Int) {
        Logger.d(TAG, "nfcOnDeactivated reason $reason")
    }

    /**
     * Method to call when a command APDU has been received.
     *
     *
     * This should be called from the application's implementation of
     * [android.nfc.cardemulation.HostApduService.processCommandApdu].
     *
     * @param apdu The APDU that was received from the remote device.
     * @return a byte-array containing the response APDU.
     */
    fun nfcProcessCommandApdu(apdu: ByteArray): ByteArray {
        if (Logger.isDebugEnabled) {
            Logger.dHex(TAG, "nfcProcessCommandApdu: apdu", apdu)
        }
        val commandType = NfcUtil.nfcGetCommandType(apdu)
        return when (commandType) {
            NfcUtil.COMMAND_TYPE_SELECT_BY_AID -> handleSelectByAid(apdu)
            NfcUtil.COMMAND_TYPE_SELECT_FILE -> handleSelectFile(apdu)
            NfcUtil.COMMAND_TYPE_READ_BINARY -> handleReadBinary(apdu)
            NfcUtil.COMMAND_TYPE_UPDATE_BINARY -> handleUpdateBinary(apdu)
            else -> {
                Logger.w(TAG, "nfcProcessCommandApdu: command type $commandType not handled")
                NfcUtil.STATUS_WORD_INSTRUCTION_NOT_SUPPORTED
            }
        }
    }

    private fun handleSelectByAid(apdu: ByteArray): ByteArray {
        if (apdu.size < 12) {
            Logger.w(TAG, "handleSelectByAid: unexpected APDU length ${apdu.size}")
            return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }
        if (Arrays.equals(
                Arrays.copyOfRange(apdu, 5, 12),
                NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION
            )
        ) {
            Logger.d(TAG, "handleSelectByAid: NDEF application selected")
            updateBinaryData = null
            return NfcUtil.STATUS_WORD_OK
        }
        Logger.dHex(TAG, "handleSelectByAid: Unexpected AID selected in APDU", apdu)
        return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
    }

    private fun calculateNegotiatedHandoverInitialNdefMessage(): ByteArray {
        // From 18013-5: When Negotiated Handover is used, the mdoc shall include the
        // "urn:nfc:sn:handover" service in a Service Parameter record in the Initial NDEF
        // message provided to the mdoc reader

        // From Connection Handover 1.5 section 4.1.2: For Negotiated Handover in
        // Reader/Writer Mode, handover messages SHALL be exchanged as described for the
        // Single response communication mode in [TNEP]. The Service name URI for the
        // service announced in the Service Parameter record SHALL be "urn:nfc:sn:handover"

        // From Tag NDEF Exchange Protocol 1.0 section 4.1.2: The Service Parameter Record
        // is a short NDEF Record that does not include an ID field, but its Type field
        // contains the NFC Forum Well Known Type (see [RTD]) “Tp”.
        //
        val serviceNameUriUtf8 = "urn:nfc:sn:handover".toByteArray()
        val baos = ByteArrayOutputStream()
        try {
            // The payload of the record is defined in Tag NDEF Exchange Protocol 1.0 section 4.1.2:
            baos.write(0x10) // TNEP version: 1.0
            baos.write(serviceNameUriUtf8.size)
            baos.write(serviceNameUriUtf8)
            baos.write(0x00) // TNEP Communication Mode: Single Response communication mode
            baos.write(negotiatedHandoverWtInt) // Minimum Waiting Time
            baos.write(negotiatedHandoverMaxNumWaitingTimeExtensions) // Maximum Number of Waiting Time Extensions
            baos.write(0xff) // Maximum NDEF Message Size (upper 8 bits)
            baos.write(0xff) // Maximum NDEF Message Size (lower 8 bits)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
        val payload = baos.toByteArray()
        val record = NdefRecord(
            NdefRecord.TNF_WELL_KNOWN,
            "Tp".toByteArray(),
            null,
            payload
        )
        val arrayOfRecords = arrayOfNulls<NdefRecord>(1)
        arrayOfRecords[0] = record
        val message = NdefMessage(arrayOfRecords)
        return message.toByteArray()
    }

    private fun handleSelectFile(apdu: ByteArray): ByteArray {
        if (apdu.size < 7) {
            Logger.w(TAG, "handleSelectFile: unexpected APDU length " + apdu.size)
            return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }
        val fileId = (apdu[5].toInt() and 0xff) * 256 + (apdu[6].toInt() and 0xff)
        Logger.d(TAG, "handleSelectFile: fileId $fileId")
        // We only support two files
        if (fileId == NfcUtil.CAPABILITY_CONTAINER_FILE_ID) {
            // This is defined in NFC Forum Type 4 Tag Technical Specification v1.2 table 6
            // and section 4.7.3 NDEF-File_Ctrl_TLV
            val fileWriteAccessCondition = if (mUsingNegotiatedHandover) 0x00 else 0xff.toByte()
            selectedNfcFile = byteArrayOf(
                0x00.toByte(),
                0x0f.toByte(),
                0x20.toByte(),
                0x7f.toByte(),
                0xff.toByte(),
                0x7f.toByte(),
                0xff.toByte(),
                0x04.toByte(),
                0x06.toByte(),
                0xe1.toByte(),
                0x04.toByte(),
                0x7f.toByte(),
                0xff.toByte(),
                0x00.toByte(),  // file read access condition (allow read)
                fileWriteAccessCondition // file write access condition (allow/disallow write)
            )
            Logger.d(TAG, "handleSelectFile: CAPABILITY file selected")
        } else if (fileId == NfcUtil.NDEF_FILE_ID) {
            if (mUsingNegotiatedHandover) {
                Logger.d(TAG, "handleSelectFile: NDEF file selected and using negotiated handover")
                val message = calculateNegotiatedHandoverInitialNdefMessage()
                Logger.dHex(TAG, "handleSelectFile: Initial NDEF message", message)
                val fileContents = ByteArray(message.size + 2)
                fileContents[0] = (message.size / 256).toByte()
                fileContents[1] = (message.size and 0xff).toByte()
                System.arraycopy(message, 0, fileContents, 2, message.size)
                selectedNfcFile = fileContents
                negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_EXPECT_SERVICE_SELECT
            } else {
                val cmsFromTransports = setupTransports(
                    staticHandoverConnectionMethods!!
                )
                Logger.d(TAG, "handleSelectFile: NDEF file selected and using static "
                    + "handover - calculating handover message")
                val hsMessage = NfcUtil.createNdefMessageHandoverSelect(
                    cmsFromTransports,
                    deviceEngagement,
                    options
                )
                Logger.dHex(TAG, "handleSelectFile: Handover Select", hsMessage)
                val fileContents = ByteArray(hsMessage.size + 2)
                fileContents[0] = (hsMessage.size / 256).toByte()
                fileContents[1] = (hsMessage.size and 0xff).toByte()
                System.arraycopy(hsMessage, 0, fileContents, 2, hsMessage.size)
                selectedNfcFile = fileContents
                handoverSelectMessage = hsMessage
                handoverRequestMessage = null
                handover = Cbor.encode(
                    CborArray.builder()
                        .add(handoverSelectMessage!!) // Handover Select message
                        .add(Simple.NULL)             // Handover Request message
                        .end()
                        .build()
                )
                Logger.dCbor(TAG, "NFC static DeviceEngagement", deviceEngagement)
                Logger.dCbor(TAG, "NFC static Handover", handover)

                // TODO: We're reporting this just a bit early, we should move this
                //  to handleReadBinary() instead and emit it once all bytes from
                //  mSelectedNfcFile has been read
                reportHandoverSelectMessageSent()
            }
        } else {
            Logger.w(TAG, "handleSelectFile: Unknown file selected with id $fileId")
            return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }
        return NfcUtil.STATUS_WORD_OK
    }

    private fun handleReadBinary(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) {
            Logger.w(TAG, "handleReadBinary: unexpected APDU length ${apdu.size}")
            return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }
        if (selectedNfcFile == null) {
            Logger.w(TAG, "handleReadBinary: no file selected -> STATUS_WORD_FILE_NOT_FOUND")
            return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }
        val contents: ByteArray = selectedNfcFile!!
        val offset = (apdu[2].toInt() and 0xff) * 256 + (apdu[3].toInt() and 0xff)
        var size = apdu[4].toInt() and 0xff
        if (size == 0) {
            // Handle Extended Length encoding
            if (apdu.size < 7) {
                return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
            }
            size = (apdu[5].toInt() and 0xff) * 256
            size += apdu[6].toInt() and 0xff
        }
        if (offset >= contents.size) {
            Logger.w(TAG, "handleReadBinary: starting offset $offset beyond file " +
                    "end ${contents.size} -> STATUS_WORD_WRONG_PARAMETERS")
            return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
        }
        if (offset + size > contents.size) {
            Logger.w(TAG, "handleReadBinary: ending offset ${offset + size} beyond file" +
                    "end ${contents.size} -> STATUS_WORD_END_OF_FILE_REACHED")
            return NfcUtil.STATUS_WORD_END_OF_FILE_REACHED
        }
        val response = ByteArray(size + NfcUtil.STATUS_WORD_OK.size)
        System.arraycopy(contents, offset, response, 0, size)
        System.arraycopy(NfcUtil.STATUS_WORD_OK, 0, response, size, NfcUtil.STATUS_WORD_OK.size)
        Logger.d(TAG, "handleReadBinary: returning $size bytes from offset $offset " +
                "(file size ${contents.size})")
        return response
    }

    private var updateBinaryData: ByteArray? = null

    init {
        deviceEngagement = EngagementGenerator(
            eDeviceKey,
            EngagementGenerator.ENGAGEMENT_VERSION_1_0
        ).generate()
        Logger.dCbor(TAG, "NFC DeviceEngagement", deviceEngagement)
        Logger.d(TAG, "Starting")
    }

    private fun handleUpdateBinary(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) {
            return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }
        val offset = (apdu[2].toInt() and 0xff) * 256 + (apdu[3].toInt() and 0xff)
        val size = apdu[4].toInt() and 0xff
        val dataSize = apdu.size - 5
        if (dataSize != size) {
            Logger.e(TAG, "Expected length embedded in APDU to be $dataSize but found $size")
            return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }

        // This code implements the procedure specified by
        //
        //  Type 4 Tag Technical Specification Version 1.2 section 7.5.5 NDEF Write Procedure
        val payload = ByteArray(dataSize)
        System.arraycopy(apdu, 5, payload, 0, dataSize)
        Logger.dHex(TAG, "handleUpdateBinary: payload", payload)
        return if (offset == 0) {
            if (payload.size == 2) {
                if (payload[0].toInt() == 0x00 && payload[1].toInt() == 0x00) {
                    Logger.d(
                        TAG,
                        "handleUpdateBinary: Reset length message"
                    )
                    if (updateBinaryData != null) {
                        Logger.w(
                            TAG,
                            "Got reset but we are already active"
                        )
                        return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
                    }
                    updateBinaryData = ByteArray(0)
                    NfcUtil.STATUS_WORD_OK
                } else {
                    val length = (apdu[5].toInt() and 0xff) * 256 + (apdu[6].toInt() and 0xff)
                    Logger.d(TAG, "handleUpdateBinary: Update length message with length $length")
                    if (updateBinaryData == null) {
                        Logger.w(TAG, "Got length but we are not active")
                        return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
                    }
                    if (length != updateBinaryData!!.size) {
                        Logger.w(TAG, "Length $length doesn't match received data of " +
                                "${updateBinaryData!!.size} bytes")
                        return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
                    }

                    // At this point we got the whole NDEF message that the reader wanted to send.
                    val ndefMessage = updateBinaryData!!
                    updateBinaryData = null
                    handleUpdateBinaryNdefMessage(ndefMessage)
                }
            } else {
                if (updateBinaryData != null) {
                    Logger.w(TAG,"Got data in single UPDATE_BINARY but we are already active")
                    return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
                }
                Logger.dHex(TAG, "handleUpdateBinary: single UPDATE_BINARY message " +
                        "with payload: ", payload)
                val ndefMessage = payload.copyOfRange(2, payload.size)
                handleUpdateBinaryNdefMessage(ndefMessage)
            }
        } else if (offset == 1) {
            Logger.w(TAG, "Unexpected offset $offset")
            NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        } else {
            // offset >= 2
            if (updateBinaryData == null) {
                Logger.w(TAG, "Got data but we are not active")
                return NfcUtil.STATUS_WORD_FILE_NOT_FOUND
            }
            Logger.dHex(
                TAG,
                "handleUpdateBinary: Data message offset $offset with payload: ",
                payload
            )
            val newLength = offset - 2 + payload.size
            if (updateBinaryData!!.size < newLength) {
                updateBinaryData = Arrays.copyOf(updateBinaryData!!, newLength)
            }
            System.arraycopy(payload, 0, updateBinaryData!!, offset - 2, payload.size)
            NfcUtil.STATUS_WORD_OK
        }
    }

    private fun handleUpdateBinaryNdefMessage(ndefMessage: ByteArray): ByteArray {
        // Falls through here only if we have a full NDEF message.
        return if (negotiatedHandoverState == NEGOTIATED_HANDOVER_STATE_EXPECT_SERVICE_SELECT) {
            handleServiceSelect(ndefMessage)
        } else if (negotiatedHandoverState == NEGOTIATED_HANDOVER_STATE_EXPECT_HANDOVER_REQUEST) {
            handleHandoverRequest(ndefMessage)
        } else {
            Logger.w(TAG, "Unexpected state $negotiatedHandoverState")
            NfcUtil.STATUS_WORD_FILE_NOT_FOUND
        }
    }

    private fun handleServiceSelect(ndefMessagePayload: ByteArray): ByteArray {
        Logger.dHex(TAG, "handleServiceSelect: payload", ndefMessagePayload)
        // NDEF message specified in NDEF Exchange Protocol 1.0: 4.2.2 Service Select Record
        val message = try {
            NdefMessage(ndefMessagePayload)
        } catch (e: FormatException) {
            Logger.e(TAG, "handleServiceSelect: Error parsing NdefMessage", e)
            negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
            return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
        }
        val records = message.records
        if (records.size != 1) {
            Logger.e(TAG, "handleServiceSelect: Expected one NdefRecord, found ${records.size}")
            negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
            return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
        }
        val record = records[0]
        val expectedPayload = " urn:nfc:sn:handover".toByteArray()
        expectedPayload[0] = (expectedPayload.size - 1).toByte()
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN ||
            !Arrays.equals(record.type, "Ts".toByteArray()) ||
            record.payload == null ||
            !Arrays.equals(record.payload, expectedPayload)
        ) {
            Logger.e(TAG, "handleServiceSelect: NdefRecord is malformed")
            negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
            return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
        }
        Logger.d(TAG, "Service Select NDEF message has been validated")
        reportTwoWayEngagementDetected()

        // From NDEF Exchange Protocol 1.0: 4.3 TNEP Status Message
        // If the NFC Tag Device has received a Service Select Message with a known
        // Service, it will return a TNEP Status Message to confirm a successful
        // Service selection.
        val statusMessage = calculateStatusMessage(0x00)
        Logger.dHex(TAG, "handleServiceSelect: Status message", statusMessage)
        val fileContents = ByteArray(statusMessage.size + 2)
        fileContents[0] = (statusMessage.size / 256).toByte()
        fileContents[1] = (statusMessage.size and 0xff).toByte()
        System.arraycopy(statusMessage, 0, fileContents, 2, statusMessage.size)
        selectedNfcFile = fileContents
        negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_EXPECT_HANDOVER_REQUEST
        return NfcUtil.STATUS_WORD_OK
    }

    private fun handleHandoverRequest(ndefMessagePayload: ByteArray): ByteArray {
        Logger.dHex(TAG, "handleHandoverRequest: payload", ndefMessagePayload)
        val  message = try {
            NdefMessage(ndefMessagePayload)
        } catch (e: FormatException) {
            Logger.e(TAG, "handleHandoverRequest: Error parsing NdefMessage", e)
            negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
            return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
        }
        val records = message.records
        if (records.size < 2) {
            Logger.e(TAG, "handleServiceSelect: Expected at least two NdefRecords, found ${records.size}")
            negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
            return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
        }
        val parsedCms = mutableListOf<MdocConnectionMethod>()
        for (r in records) {
            // Handle Handover Request record for NFC Forum Connection Handover specification
            // version 1.5 (encoded as 0x15 below).
            //
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN
                && Arrays.equals(r.type, "Hr".toByteArray())
            ) {
                val payload = r.payload
                if (payload.isNotEmpty() && payload[0].toInt() == 0x15) {
                    val hrEmbMessageData = Arrays.copyOfRange(payload, 1, payload.size)
                    try {
                        NdefMessage(hrEmbMessageData)
                    } catch (e: FormatException) {
                        Logger.e(TAG, "handleHandoverRequest: Error parsing embedded HR NdefMessage", e)
                        negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
                        return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
                    }
                }
                // TODO: actually look at these records:
                //  hrEmbMessageRecords = message.records
            }

            // This parses the various carrier specific NDEF records, see
            // DataTransport.parseNdefRecord() for details.
            //
            if (r.tnf == NdefRecord.TNF_MIME_MEDIA || r.tnf == NdefRecord.TNF_EXTERNAL_TYPE) {
                val cm = NfcUtil.fromNdefRecord(r, false)
                if (cm != null) {
                    Logger.d(TAG, "Found connectionMethod: $cm")
                    parsedCms.add(cm)
                }
            }
        }
        if (parsedCms.size < 1) {
            Logger.w(TAG, "No connection methods found. Bailing.")
            negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_NOT_STARTED
            return NfcUtil.STATUS_WORD_WRONG_PARAMETERS
        }
        val disambiguatedCms = disambiguate(parsedCms, MdocRole.MDOC)
        for (cm in disambiguatedCms) {
            Logger.d(TAG, "Have connectionMethod: $cm")
        }

        // TODO: add a method to the Listener so the application can select which one to use.
        //  For now we just pick the first method.
        val method = disambiguatedCms[0]
        val listWithSelectedConnectionMethod = mutableListOf<MdocConnectionMethod>()
        listWithSelectedConnectionMethod.add(method)
        val hsMessage = NfcUtil.createNdefMessageHandoverSelect(
            listWithSelectedConnectionMethod,
            deviceEngagement,
            options
        )
        val fileContents = ByteArray(hsMessage.size + 2)
        fileContents[0] = (hsMessage.size / 256).toByte()
        fileContents[1] = (hsMessage.size and 0xff).toByte()
        System.arraycopy(hsMessage, 0, fileContents, 2, hsMessage.size)
        selectedNfcFile = fileContents
        negotiatedHandoverState = NEGOTIATED_HANDOVER_STATE_EXPECT_HANDOVER_SELECT
        handoverSelectMessage = hsMessage
        handoverRequestMessage = ndefMessagePayload
        handover = Cbor.encode(
            CborArray.builder()
                .add(handoverSelectMessage!!)    // Handover Select message
                .add(handoverRequestMessage!!)   // Handover Request message
                .end()
                .build()
        )
        Logger.dCbor(TAG, "NFC negotiated DeviceEngagement", deviceEngagement)
        Logger.dCbor(TAG, "NFC negotiated Handover", handover)

        // TODO: We're reporting this just a bit early, we should move this
        //  to handleReadBinary() instead and emit it once all bytes from
        //  mSelectedNfcFile has been read
        reportHandoverSelectMessageSent()

        // Technically we should ensure the transports are up until sending the response...
        setupTransports(listWithSelectedConnectionMethod)
        return NfcUtil.STATUS_WORD_OK
    }

    private fun calculateStatusMessage(@Suppress("SameParameterValue") statusCode: Int): ByteArray {
        val payload = byteArrayOf(statusCode.toByte())
        val record = NdefRecord(
            NdefRecord.TNF_WELL_KNOWN,
            "Te".toByteArray(),
            null,
            payload
        )
        val arrayOfRecords = arrayOfNulls<NdefRecord>(1)
        arrayOfRecords[0] = record
        val message = NdefMessage(arrayOfRecords)
        return message.toByteArray()
    }

    fun peerIsConnecting(transport: DataTransport) {
        if (!reportedDeviceConnecting) {
            reportedDeviceConnecting = true
            reportDeviceConnecting(transport)
        }
    }

    fun peerHasConnected(transport: DataTransport) {
        // Stop listening on other transports.
        Logger.d(
            TAG, "Peer has connected on transport $transport - shutting down other transports"
        )
        for (t in transports) {
            t.setListener(null, null)
            if (t !== transport) {
                t.close()
            }
        }
        transports.clear()
        reportDeviceConnected(transport)
    }

    // Note: The report*() methods are safe to call from any thread.
    private fun reportTwoWayEngagementDetected() {
        reportEvent("reportTwoWayEngagementDetected") { listener -> listener.onTwoWayEngagementDetected() }
    }

    private fun reportHandoverSelectMessageSent() {
        reportEvent("onHandoverSelectMessageSent") { listener -> listener.onHandoverSelectMessageSent() }
    }

    private fun reportDeviceConnecting(transport: DataTransport) {
        reportEvent("reportDeviceConnecting $transport") { listener -> listener.onDeviceConnecting() }
    }

    private fun reportDeviceConnected(transport: DataTransport) {
        reportEvent("reportDeviceConnected $transport") { listener -> listener.onDeviceConnected(transport) }
    }

    private fun reportError(error: Throwable) {
        reportEvent("reportError: error: ", error) { listener -> listener.onError(error) }
    }

    /** Common reporting code. */
    private fun reportEvent(
        logMessage: String,
        logError: Throwable? = null,
        event: (Listener) -> Unit
    ) {
        if (logError != null) {
            Logger.d(TAG, logMessage, logError)
        } else {
            Logger.d(TAG, logMessage)
        }
        val currentListener: Listener? = listener
        val currentExecutor: Executor? = executor
        if (currentListener != null && currentExecutor != null) {
            currentExecutor.execute {
                if (!inhibitCallbacks) {
                    event(currentListener)
                }
            }
        }
    }

    /**
     * Listener for [NfcEngagementHelper].
     */
    interface Listener {
        /**
         * Called when two-way engagement has been detected.
         *
         *
         * If negotiated handover is used, this is called when the NFC tag reader has
         * selected the connection handover service.
         */
        fun onTwoWayEngagementDetected()

        /**
         * Called when the Handover Select message has been sent to the NFC tag reader.
         *
         *
         * This is a good point for an app to notify the user that an mdoc transaction
         * is about to take place and they can start removing the device from the field.
         */
        fun onHandoverSelectMessageSent()

        /**
         * Called when a remote mdoc reader is starting to connect.
         */
        fun onDeviceConnecting()

        /**
         * Called when a remote mdoc reader has connected.
         *
         *
         * The application should use the passed-in [DataTransport] with
         * [com.android.identity.android.mdoc.deviceretrieval.DeviceRetrievalHelper]
         * to start the transaction.
         *
         *
         * After this is called, no more callbacks will be done on listener and all other
         * listening transports will be closed. Calling [.close] will not close the
         * passed-in transport.
         *
         * @param transport a [DataTransport] for the connection to the remote mdoc reader.
         */
        fun onDeviceConnected(transport: DataTransport)

        /**
         * Called when an irrecoverable error has occurred.
         *
         * @param error details of what error has occurred.
         */
        fun onError(error: Throwable)
    }

    /**
     * A builder for [NfcEngagementHelper].
     *
     * @param context application context.
     * @param eDeviceKey the public part of `EDeviceKey` for *mdoc session
     * encryption* according to ISO/IEC 18013-5:2021 section 9.1.1.4.
     * @param options set of options for creating [DataTransport] instances.
     * @param listener the listener.
     * @param executor a [Executor] to use with the listener.
    */
    class Builder(
        context: Context,
        eDeviceKey: EcPublicKey,
        options: DataTransportOptions,
        listener: Listener,
        executor: Executor
    ) {
        var helper: NfcEngagementHelper

        init {
            // For now we just hardcode wt_int to 16 meaning the Minimum Waiting Time shall
            // be 8 ms as per the table in [TNEP] 4.1.6 Minimum Waiting Time. We also include
            // the maximum number of waiting time extensions to be set at 15 which is the
            // maximum allowed. This is only used for negotiated handover and - if needed - we
            // could expose these settings to applications.
            //
            val negotiatedHandoverWtInt = 16
            val negotiatedHandoverMaxNumWaitingTimeExtensions = 15
            helper = NfcEngagementHelper(
                context,
                eDeviceKey,
                options,
                negotiatedHandoverWtInt,
                negotiatedHandoverMaxNumWaitingTimeExtensions,
                listener,
                executor
            )
        }

        /**
         * Configures the builder so NFC Static Handover is used.
         *
         * @param connectionMethods a list of connection methods to use.
         * @return the builder.
         */
        fun useStaticHandover(connectionMethods: List<MdocConnectionMethod>) = apply {
            helper.staticHandoverConnectionMethods = combine(connectionMethods)
        }

        /**
         * Configures the builder so NFC Negotiated Handover is used.
         *
         * Note: there is currently no way to specify which of the connection
         * methods offered by the mdoc reader should be used. This will be added
         * in a future version.
         *
         * @return the buider.
         */
        fun useNegotiatedHandover() = apply {
            helper.mUsingNegotiatedHandover = true
        }

        /**
         * Builds the [NfcEngagementHelper] and starts listening for connections.
         *
         *]
         * and deactivation events using [.nfcOnDeactivated].
         *
         * @return the helper, ready to be used.
         */
        fun build(): NfcEngagementHelper {
            check(!(helper.mUsingNegotiatedHandover &&
                    helper.staticHandoverConnectionMethods != null)) {
                "Can't use static and negotiated handover at the same time."
            }
            return helper
        }
    }

    companion object {
        private const val TAG = "NfcEngagementHelper"
        private const val NEGOTIATED_HANDOVER_STATE_NOT_STARTED = 0
        private const val NEGOTIATED_HANDOVER_STATE_EXPECT_SERVICE_SELECT = 1
        private const val NEGOTIATED_HANDOVER_STATE_EXPECT_HANDOVER_REQUEST = 2
        private const val NEGOTIATED_HANDOVER_STATE_EXPECT_HANDOVER_SELECT = 3
    }
}