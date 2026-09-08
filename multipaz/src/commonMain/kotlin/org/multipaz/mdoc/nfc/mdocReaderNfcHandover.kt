package org.multipaz.mdoc.nfc

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.nfc.HandoverRequestRecord
import org.multipaz.nfc.HandoverSelectRecord
import org.multipaz.nfc.NdefMessage
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcCommandFailedException
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ServiceParameterRecord
import org.multipaz.nfc.ServiceSelectRecord
import org.multipaz.nfc.TnepStatusRecord
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.encapsulateInDo53
import org.multipaz.mdoc.transport.extractFromDo53
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.getUInt16
import org.multipaz.util.toHex
import kotlin.math.min

const private val TAG = "mdocReaderNfcHandover"

/**
 * The type of handover which occurred.
 */
enum class MdocHandoverType {
    /** Static handover according to ISO/IEC 18013-5:2021 */
    STATIC_HANDOVER,
    /** Negotiated handover according to ISO/IEC 18013-5:2021 */
    NEGOTIATED_HANDOVER,
    /** Handover according to ISO/IEC 18013-5 Second Edition */
    V2_HANDOVER
}

/**
 * The result of a successful NFC handover operation
 *
 * @property connectionMethods the possible connection methods for the mdoc reader to connect to.
 * @property encodedDeviceEngagement the bytes of DeviceEngagement.
 * @property handover the handover value.
 * @property type the type of NFC handover which occurred.
 */
data class MdocReaderNfcHandoverResult(
    val connectionMethods: List<MdocConnectionMethod>,
    val encodedDeviceEngagement: ByteString,
    val handover: DataItem,
    val type: MdocHandoverType,
)

/**
 * Options for when performing handover as a mdoc reader.
 *
 * @property useNfcV2 if `true`, will attempt to use NFCv2 handover from ISO 18013-5 Second Edition.
 */
data class MdocReaderNfcHandoverOptions(
    val useNfcV2: Boolean = false
)

/**
 * Perform NFC Engagement as a mdoc reader.
 *
 * @param tag the [NfcIsoTag] representing a NFC connection to the mdoc.
 * @param negotiatedHandoverConnectionMethods the connection methods to offer if the remote mdoc is using NFC
 * negotiated handover.
 * @param options a [MdocReaderNfcHandoverOptions]
 * @return a [MdocReaderNfcHandoverResult] if the handover was successful or `null` if the tag isn't an NDEF tag.
 * @throws Throwable if an error occurs during handover.
 */
suspend fun mdocReaderNfcHandover(
    tag: NfcIsoTag,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
    options: MdocReaderNfcHandoverOptions = MdocReaderNfcHandoverOptions()
): MdocReaderNfcHandoverResult? {
    // First try the new engagement method, if requested...
    if (options.useNfcV2) {
        val selectApplicationResponse = try {
            tag.selectApplication(Nfc.MDOC_NFC_ENGAGEMENT_V2_AID)
        } catch (e: NfcCommandFailedException) {
            if (e.status == Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND) {
                Logger.i(TAG, "Selecting NFCv2 AID returned FILE_NOT_FOUND")
            }
            null
        }
        if (selectApplicationResponse != null) {
            Logger.i(TAG, "Successfully selected NFCv2 AID")
            Logger.d(TAG, "payload: ${selectApplicationResponse.payload.toByteArray().toHex()}")
            val payload = Cbor.decode(selectApplicationResponse.payload.toByteArray())
            val mdocNfcMaxCommandApduSize = payload.get(0).asNumber.toInt()
            Logger.i(TAG, "mdoc indicates APDU max command length is $mdocNfcMaxCommandApduSize")
            return mdocReaderNfcV2Handover(tag, mdocNfcMaxCommandApduSize, negotiatedHandoverConnectionMethods)
        }
    }

    // Fall back to NDEF...
    try {
        tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
        Logger.i(TAG, "Successfully selected NDEF AID")
    } catch (e: NfcCommandFailedException) {
        // This is returned by Android when locked phone is being tapped by an mdoc reader. Once unlocked the
        // user will be shown UI to convey another tap should happen. So since we're the mdoc reader, we
        // want to keep scanning...
        //
        if (e.status == Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND) {
            Logger.i(TAG, "NDEF application not found, continuing scanning")
            return null
        }
    }

    tag.selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID)
    // CC file is 15 bytes long
    val ccFile = tag.readBinary(0, 15)
    check(ccFile.size == 15) { "CC file is ${ccFile.size} bytes, expected 15" }

    val ndefFileId = ccFile.getUInt16(9).toInt()

    tag.selectFile(ndefFileId)

    val initialNdefMessage = tag.ndefReadMessage()
    if (Logger.isDebugEnabled) {
        Logger.dHex(TAG, "Initial NDEF message", initialNdefMessage.encode())
    }

    // First see if we should use negotiated handover by looking for the Handover Service parameter record...
    val hspr = initialNdefMessage.records.mapNotNull {
        val parsed = ServiceParameterRecord.fromNdefRecord(it)
        if (parsed?.serviceNameUri == "urn:nfc:sn:handover") parsed else null
    }.firstOrNull()
    if (hspr == null) {
        Logger.i(TAG, "No service parameter record, assuming Static Handover")
        val (encodedDeviceEngagement, connectionMethods) = parseHandoverSelectMessage(initialNdefMessage, null)
        check(!connectionMethods.isEmpty()) { "No connection methods in Handover Select" }
        val handover = buildCborArray {
            add(initialNdefMessage.encode()) // Handover Select message
            add(Simple.NULL)                 // Handover Request message
        }
        val disambiguatedConnectionMethods = MdocConnectionMethod.disambiguate(
            connectionMethods,
            MdocRole.MDOC_READER
        )
        return MdocReaderNfcHandoverResult(
            connectionMethods = disambiguatedConnectionMethods,
            encodedDeviceEngagement = ByteString(encodedDeviceEngagement),
            handover = handover,
            type = MdocHandoverType.STATIC_HANDOVER
        )
    }

    // Select the service, the resulting NDEF message is specified in
    // in Tag NDEF Exchange Protocol Technical Specification Version 1.0
    // section 4.3 TNEP Status Message
    Logger.i(TAG, "Service parameter record found, continuing with Static Handover")
    val serviceSelectionResponse = tag.ndefTransact(
        NdefMessage(listOf(
            ServiceSelectRecord(Nfc.SERVICE_NAME_CONNECTION_HANDOVER).toNdefRecord()
        )),
        hspr.wtInt,
        hspr.nWait
    )

    val tnepStatusRecord = serviceSelectionResponse.records.find { TnepStatusRecord.fromNdefRecord(it) != null }
        ?: throw IllegalArgumentException("Service selection: no TNEP status record")
    val tnepStatus = TnepStatusRecord.fromNdefRecord(tnepStatusRecord)!!
    require(tnepStatus.status == 0x00) { "Service selection: Unexpected status ${tnepStatus.status}" }

    // Now send Handover Request message, the resulting NDEF message is Handover Response..
    //
    val combinedNegotiatedHandoverConnectionMethods = MdocConnectionMethod.combine(negotiatedHandoverConnectionMethods)
    val hrMessage = generateHandoverRequestMessage(combinedNegotiatedHandoverConnectionMethods)
    val hsMessage = tag.ndefTransact(hrMessage, hspr.wtInt, hspr.nWait)

    val bleUuid = extractBleUuid(negotiatedHandoverConnectionMethods)
    Logger.i(TAG, "Supplementing with UUID $bleUuid")
    val (encodedDeviceEngagement, connectionMethods) = parseHandoverSelectMessage(hsMessage, bleUuid)
    check(connectionMethods.size >= 1) { "No Alternative Carriers in HS message" }

    val handover = buildCborArray {
        add(hsMessage.encode()) // Handover Select message
        add(hrMessage.encode()) // Handover Request message
    }

    return MdocReaderNfcHandoverResult(
        connectionMethods = MdocConnectionMethod.disambiguate(
            connectionMethods,
            MdocRole.MDOC_READER
        ),
        encodedDeviceEngagement = ByteString(encodedDeviceEngagement),
        handover = handover,
        type = MdocHandoverType.NEGOTIATED_HANDOVER
    )
}

private fun extractBleUuid(methods: List<MdocConnectionMethod>): UUID? {
    for (cm in methods) {
        if (cm is MdocConnectionMethodBle) {
            if (cm.peripheralServerModeUuid != null) {
                return cm.peripheralServerModeUuid
            }
            if (cm.centralClientModeUuid != null) {
                return cm.centralClientModeUuid
            }
        }
    }
    return null
}

private fun supplementBleUuid(
    methods: List<MdocConnectionMethod>,
    bleUuid: UUID?
): List<MdocConnectionMethod> {
    return methods.map { cm ->
        if (cm is MdocConnectionMethodBle) {
            var peripheralUuid = cm.peripheralServerModeUuid
            var centralUuid = cm.centralClientModeUuid
            if (cm.supportsPeripheralServerMode && peripheralUuid == null) {
                peripheralUuid = bleUuid
            }
            if (cm.supportsCentralClientMode && centralUuid == null) {
                centralUuid = bleUuid
            }
            if (peripheralUuid != cm.peripheralServerModeUuid || centralUuid != cm.centralClientModeUuid) {
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = cm.supportsPeripheralServerMode,
                    supportsCentralClientMode = cm.supportsCentralClientMode,
                    peripheralServerModeUuid = peripheralUuid,
                    centralClientModeUuid = centralUuid,
                    peripheralServerModePsm = cm.peripheralServerModePsm,
                    peripheralServerModeMacAddress = cm.peripheralServerModeMacAddress
                )
            } else {
                cm
            }
        } else {
            cm
        }
    }
}

private suspend fun mdocReaderNfcV2Handover(
    tag: NfcIsoTag,
    mdocNfcMaxCommandApduSize: Int,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
): MdocReaderNfcHandoverResult? {
    // Send Handover Request message, the resulting NDEF message is Handover Response
    //
    val combinedNegotiatedHandoverConnectionMethods = listOf(MdocConnectionMethodNfcV2()) +
            MdocConnectionMethod.combine(negotiatedHandoverConnectionMethods)

    val nfcV2HandoverRequest = buildCborMap {
        // ReaderEngagement is at key 0
        putCborMap(0) {
            // DeviceRetrievalMethods is at key 2
            putCborArray(2) {
                for (method in combinedNegotiatedHandoverConnectionMethods) {
                    val encodedDeviceRetrievalMethod = method.toDeviceEngagement()
                    add(Cbor.decode(encodedDeviceRetrievalMethod))
                }
            }
        }
    }
    val encodedNfcV2HandoverRequest = Cbor.encode(nfcV2HandoverRequest)
    Logger.dCbor(TAG, "NFCv2 Handover Request", nfcV2HandoverRequest)

    val encodedNfcV2HandoverSelect = tag.nfcV2Transact(
        message = ByteString(encodedNfcV2HandoverRequest),
        commandDataFieldMaxLength = tag.maxTransceiveLength,
        responseDataFieldMaxLength = tag.maxTransceiveLength
    ).toByteArray()
    Logger.dCbor(TAG, "NFCv2 Handover Select", encodedNfcV2HandoverSelect)

    Logger.i(TAG, "Handover complete")

    val nfcV2HandoverSelect = Cbor.decode(encodedNfcV2HandoverSelect)

    val deviceEngagementDataItem = nfcV2HandoverSelect.get(0)
    val encodedDeviceEngagement = Cbor.encode(deviceEngagementDataItem)

    val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementDataItem)

    check(deviceEngagement.connectionMethods.size == 1) {
        "Expected exactly one Device Retrieval methods in engagement, found ${deviceEngagement.connectionMethods.size}"
    }

    val bleUuid = extractBleUuid(negotiatedHandoverConnectionMethods)
    Logger.i(TAG, "Supplementing with UUID $bleUuid")
    val supplementedConnectionMethods = supplementBleUuid(deviceEngagement.connectionMethods, bleUuid)

    val handover = buildCborArray {
        add(encodedNfcV2HandoverSelect) // Handover Select message
        add(encodedNfcV2HandoverRequest)  // Handover Request message
    }

    return MdocReaderNfcHandoverResult(
        connectionMethods = MdocConnectionMethod.disambiguate(
            supplementedConnectionMethods,
            MdocRole.MDOC_READER
        ),
        encodedDeviceEngagement = ByteString(encodedDeviceEngagement),
        handover = handover,
        type = MdocHandoverType.V2_HANDOVER
    )
}

private fun generateHandoverRequestMessage(
    methods: List<MdocConnectionMethod>,
): NdefMessage {
    val auxiliaryReferences = listOf<String>()
    val carrierConfigurationRecords = mutableListOf<NdefRecord>()
    val alternativeCarrierRecords = mutableListOf<NdefRecord>()
    for (method in methods) {
        val ndefRecordAndAlternativeCarrier = method.toNdefRecord(
            auxiliaryReferences = auxiliaryReferences,
            role = MdocRole.MDOC_READER,
            skipUuids = false
        )
        if (ndefRecordAndAlternativeCarrier != null) {
            carrierConfigurationRecords.add(ndefRecordAndAlternativeCarrier.first)
            alternativeCarrierRecords.add(ndefRecordAndAlternativeCarrier.second)
        }
    }
    val handoverRequestRecord = HandoverRequestRecord(
        version = 0x15,
        embeddedMessage = NdefMessage(alternativeCarrierRecords)
    )
    // TODO: make it possible for caller to specify readerEngagement
    val encodedReaderEngagement = Cbor.encode(
        buildCborMap {
            put(0L, "1.0")
        }
    )
    return NdefMessage(
        listOf(
            handoverRequestRecord.generateNdefRecord(),
            NdefRecord(
                tnf = NdefRecord.Tnf.EXTERNAL_TYPE,
                type = "iso.org:18013:readerengagement".encodeToByteString(),
                id = "mdocreader".encodeToByteString(),
                payload = ByteString(encodedReaderEngagement)
            )
        ) + carrierConfigurationRecords
    )
}

@OptIn(ExperimentalStdlibApi::class)
private fun parseHandoverSelectMessage(
    message: NdefMessage,
    uuid: UUID?,
): Pair<ByteArray, List<MdocConnectionMethod>> {
    var hasHandoverSelectRecord = false

    var encodedDeviceEngagement: ByteString? = null
    val connectionMethods = mutableListOf<MdocConnectionMethod>()
    for (r in message.records) {
        // Handle Handover Select record for NFC Forum Connection Handover specification
        // version 1.5 (encoded as 0x15 below).
        val hsRecord = HandoverSelectRecord.fromNdefRecord(r)
        if (hsRecord != null) {
            check(hsRecord.version == 0x15) { "Only Connection Handover version 1.5 is supported" }
            hasHandoverSelectRecord = true
        }
        if (r.tnf == NdefRecord.Tnf.EXTERNAL_TYPE &&
            r.type.decodeToString() == "iso.org:18013:deviceengagement" &&
            r.id.decodeToString() == "mdoc") {
            encodedDeviceEngagement = r.payload
        }

        if (r.tnf == NdefRecord.Tnf.MIME_MEDIA || r.tnf == NdefRecord.Tnf.EXTERNAL_TYPE) {
            val cm = MdocConnectionMethod.fromNdefRecord(r, MdocRole.MDOC, uuid)
            if (cm != null) {
                connectionMethods.add(cm)
            }
        }
    }
    if (!hasHandoverSelectRecord) {
        throw IllegalStateException("Handover Select record not found")
    }
    if (encodedDeviceEngagement == null) {
        throw IllegalStateException("DeviceEngagement record not found")
    }
    return Pair(encodedDeviceEngagement.toByteArray(), connectionMethods)
}


internal suspend fun NfcIsoTag.nfcV2Transact(
    message: ByteString,
    commandDataFieldMaxLength: Int,
    responseDataFieldMaxLength: Int,
    onMessageSent: (suspend () -> Unit)? = null
): ByteString {
    val encMessage = encapsulateInDo53(message)

    val maxChunkSize = commandDataFieldMaxLength
    val offsets = 0 until encMessage.size step maxChunkSize
    var lastEnvelopeResponse: ResponseApdu? = null
    for (offset in offsets) {
        val moreDataComing = (offset != offsets.last)
        val size = min(maxChunkSize, encMessage.size - offset)
        val le = if (!moreDataComing) {
            responseDataFieldMaxLength
        } else {
            0
        }
        val response = transceive(
            CommandApdu(
                cla = if (moreDataComing) Nfc.CLA_CHAIN_NOT_LAST else Nfc.CLA_CHAIN_LAST,
                ins = Nfc.INS_ENVELOPE,
                p1 = 0x00,
                p2 = 0x00,
                payload = ByteString(encMessage.toByteArray(), offset, offset + size),
                le = le
            )
        )
        if (response.status != Nfc.RESPONSE_STATUS_SUCCESS && response.status.and(0xff00) != 0x6100) {
            throw NfcCommandFailedException("Unexpected ENVELOPE status ${response.statusHexString}", response.status)
        }
        lastEnvelopeResponse = response
    }
    Logger.i(TAG, "Successfully sent message, waiting for response")

    if (onMessageSent != null) {
        onMessageSent()
    }

    check(lastEnvelopeResponse != null)
    val encapsulatedMessageBuilder = ByteStringBuilder()
    encapsulatedMessageBuilder.append(lastEnvelopeResponse.payload)
    if (lastEnvelopeResponse.status == Nfc.RESPONSE_STATUS_SUCCESS) {
        // Woohoo, entire response fits
        Logger.i(TAG, "Entire response fits")
    } else {
        // More bytes are coming, have to use GET RESPONSE to get the rest

        var leForGetResponse = responseDataFieldMaxLength
        if (lastEnvelopeResponse.status.and(0xff) != 0) {
            leForGetResponse = lastEnvelopeResponse.status.and(0xff)
        }
        while (true) {
            Logger.i(TAG, "Sending GET RESPONSE")
            val response = transceive(
                CommandApdu(
                    cla = 0x00,
                    ins = Nfc.INS_GET_RESPONSE,
                    p1 = 0x00,
                    p2 = 0x00,
                    payload = ByteString(),
                    le = leForGetResponse
                )
            )
            encapsulatedMessageBuilder.append(response.payload)
            if (response.status == Nfc.RESPONSE_STATUS_SUCCESS) {
                /* If Le ≥ the number of available bytes, the mdoc shall include
                 * all available bytes in the response and set the status words
                 * to ’90 00’.
                 */
                break
            } else if (response.status == 0x6100) {
                /* If the number of available bytes > Le + 255, the mdoc shall
                 * include as many bytes in the response as indicated by Le and
                 * shall set the status words to ’61 00’. The mdoc reader shall
                 * respond with a GET RESPONSE command where Le is set to the
                 * maximum length of the response data field that is supported
                 * by both the mdoc and the mdoc reader.
                 */
                leForGetResponse = responseDataFieldMaxLength
            } else if (response.status.and(0xff00) == 0x6100) {
                /* If Le < the number of available bytes ≤ Le + 255, the
                 * mdoc shall include as many bytes in the response as
                 * indicated by Le and shall set the status words to ’61 XX’,
                 * where XX is the number of available bytes remaining. The
                 * mdoc reader shall respond with a GET RESPONSE command where
                 * Le is set to XX.
                 */
                leForGetResponse = response.status.and(0xff)
            } else {
                throw NfcCommandFailedException(
                    "Unexpected GET RESPONSE status ${response.statusHexString}",
                    response.status
                )
            }
        }
    }
    val encapsulatedMessage = encapsulatedMessageBuilder.toByteString()
    val extractedMessage = extractFromDo53(encapsulatedMessage)
    return extractedMessage
}
