package org.multipaz.mdoc.nfc

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.engagement.buildReaderEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.encapsulateInDo53
import org.multipaz.mdoc.transport.extractFromDo53
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.HandoverRequestRecord
import org.multipaz.nfc.HandoverSelectRecord
import org.multipaz.nfc.NdefMessage
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcCommandFailedException
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ResponseApdu
import org.multipaz.nfc.ServiceParameterRecord
import org.multipaz.nfc.ServiceSelectRecord
import org.multipaz.nfc.TnepStatusRecord
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.getUInt16
import org.multipaz.util.toHex
import kotlin.math.min

private const val TAG = "mdocReaderNfcHandover"

/**
 * The type of handover which occurred.
 */
enum class MdocHandoverType {
    /** Static handover according to ISO/IEC 18013-5:2021 */
    STATIC_HANDOVER,
    /** Negotiated handover according to ISO/IEC 18013-5:2021 */
    NEGOTIATED_HANDOVER,
    /** Concurrent Channel Engagement (CCE) handover according to ISO/IEC 18013-5 Second Edition */
    CCE_HANDOVER
}

/**
 * The result of a successful NFC handover operation.
 *
 * @property connectionMethods the possible connection methods for the mdoc reader to connect to (empty for NFC-only CCE).
 * @property encodedDeviceEngagement the bytes of `DeviceEngagement`.
 * @property handover the handover value.
 * @property type the type of NFC handover which occurred.
 * @property eReaderKey the ephemeral reader private key generated/used during CCE handover, or `null` for NDEF handovers.
 * @property apduCommandMaxSize the maximum command APDU data length indicated by the mdoc during CCE handover.
 * @property apduResponseMaxSize the maximum response APDU data length indicated by the mdoc during CCE handover.
 */
data class MdocReaderNfcHandoverResult(
    val connectionMethods: List<MdocConnectionMethod>,
    val encodedDeviceEngagement: ByteString,
    val handover: DataItem,
    val type: MdocHandoverType,
    val eReaderKey: EcPrivateKey? = null,
    val apduCommandMaxSize: Int = 65535,
    val apduResponseMaxSize: Int = 65536,
)

/**
 * Options for when performing handover as a mdoc reader.
 *
 * @property useCce if `true`, will attempt to use NFC Concurrent Channel Engagement (CCE) from ISO/IEC 18013-5 Second Edition.
 * @property eReaderKeyCurve the curve to use when generating the ephemeral reader key for CCE if [eReaderKey] is `null`.
 * @property eReaderKey optional explicit ephemeral reader private key to include in `ReaderEngagement` during CCE handover.
 */
data class MdocReaderNfcHandoverOptions(
    val useCce: Boolean = false,
    val eReaderKeyCurve: EcCurve = EcCurve.P256,
    val eReaderKey: EcPrivateKey? = null,
)

/**
 * Perform NFC Engagement as a mdoc reader.
 *
 * @param tag the [NfcIsoTag] representing a NFC connection to the mdoc.
 * @param negotiatedHandoverConnectionMethods the connection methods to offer if the remote mdoc is using NFC
 * negotiated handover or CCE.
 * @param options a [MdocReaderNfcHandoverOptions]
 * @return a [MdocReaderNfcHandoverResult] if the handover was successful or `null` if the tag isn't an NDEF tag.
 * @throws Throwable if an error occurs during handover.
 */
suspend fun mdocReaderNfcHandover(
    tag: NfcIsoTag,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
    options: MdocReaderNfcHandoverOptions = MdocReaderNfcHandoverOptions()
): MdocReaderNfcHandoverResult? {
    // First try Concurrent Channel Engagement (CCE), if requested...
    if (options.useCce) {
        val selectApplicationResponse = try {
            tag.selectApplication(Nfc.ISO_MDOC_NFC_CCE_APPLICATION_ID)
        } catch (e: NfcCommandFailedException) {
            if (e.status == Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND) {
                Logger.i(TAG, "Selecting NFC CCE AID returned FILE_NOT_FOUND")
            }
            null
        }
        if (selectApplicationResponse != null) {
            Logger.i(TAG, "Successfully selected NFC CCE AID")
            Logger.d(TAG, "payload: ${selectApplicationResponse.payload.toByteArray().toHex()}")
            val payload = Cbor.decode(selectApplicationResponse.payload.toByteArray())
            val apduCommandMaxSize = payload[0].asNumber.toInt()
            val apduResponseMaxSize = payload.getOrNull(1)?.asNumber?.toInt() ?: 65536
            Logger.i(
                TAG,
                "mdoc indicates APDU max command length is $apduCommandMaxSize, max response length is $apduResponseMaxSize"
            )
            return mdocReaderNfcCceHandover(
                tag = tag,
                apduCommandMaxSize = apduCommandMaxSize,
                apduResponseMaxSize = apduResponseMaxSize,
                negotiatedHandoverConnectionMethods = negotiatedHandoverConnectionMethods,
                options = options
            )
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
    Logger.i(TAG, "Service parameter record found, continuing with Negotiated Handover")
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
    val supplementedConnectionMethods = supplementBleConnectionMethods(
        connectionMethods,
        negotiatedHandoverConnectionMethods
    )

    val handover = buildCborArray {
        add(hsMessage.encode()) // Handover Select message
        add(hrMessage.encode()) // Handover Request message
    }

    return MdocReaderNfcHandoverResult(
        connectionMethods = MdocConnectionMethod.disambiguate(
            supplementedConnectionMethods,
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

private fun supplementBleConnectionMethods(
    methods: List<MdocConnectionMethod>,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>
): List<MdocConnectionMethod> {
    val bleUuid = extractBleUuid(negotiatedHandoverConnectionMethods)
    val readerCentralClientBle = negotiatedHandoverConnectionMethods
        .filterIsInstance<MdocConnectionMethodBle>()
        .firstOrNull { it.supportsCentralClientMode }
    return methods.map { cm ->
        if (cm is MdocConnectionMethodBle) {
            var peripheralUuid = cm.peripheralServerModeUuid
            var centralUuid = cm.centralClientModeUuid
            var psm = cm.peripheralServerModePsm
            var macAddress = cm.peripheralServerModeMacAddress
            if (cm.supportsPeripheralServerMode && peripheralUuid == null) {
                peripheralUuid = bleUuid
            }
            if (cm.supportsCentralClientMode && centralUuid == null) {
                centralUuid = readerCentralClientBle?.centralClientModeUuid ?: bleUuid
            }
            if (cm.supportsCentralClientMode && !cm.supportsPeripheralServerMode) {
                if (psm == null) {
                    psm = readerCentralClientBle?.peripheralServerModePsm
                }
                if (macAddress == null) {
                    macAddress = readerCentralClientBle?.peripheralServerModeMacAddress
                }
            }
            if (peripheralUuid != cm.peripheralServerModeUuid ||
                centralUuid != cm.centralClientModeUuid ||
                psm != cm.peripheralServerModePsm ||
                macAddress != cm.peripheralServerModeMacAddress
            ) {
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = cm.supportsPeripheralServerMode,
                    supportsCentralClientMode = cm.supportsCentralClientMode,
                    peripheralServerModeUuid = peripheralUuid,
                    centralClientModeUuid = centralUuid,
                    peripheralServerModePsm = psm,
                    peripheralServerModeMacAddress = macAddress
                )
            } else {
                cm
            }
        } else {
            cm
        }
    }
}

private suspend fun mdocReaderNfcCceHandover(
    tag: NfcIsoTag,
    apduCommandMaxSize: Int,
    apduResponseMaxSize: Int,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
    options: MdocReaderNfcHandoverOptions,
): MdocReaderNfcHandoverResult {
    val eReaderKey = options.eReaderKey ?: Crypto.createEcPrivateKey(options.eReaderKeyCurve)
    val combinedNegotiatedHandoverConnectionMethods = MdocConnectionMethod.combine(negotiatedHandoverConnectionMethods)

    val readerEngagement = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {
        for (method in combinedNegotiatedHandoverConnectionMethods) {
            addConnectionMethod(method)
        }
    }

    val cceHandoverRequest = buildCborMap {
        put(0, readerEngagement.toDataItem())
    }
    val encodedCceHandoverRequest = Cbor.encode(cceHandoverRequest)
    Logger.dCbor(TAG, "CCE Handover Request", cceHandoverRequest)

    val encodedCceHandoverSelect = tag.nfcCceTransact(
        message = ByteString(encodedCceHandoverRequest),
        commandDataFieldMaxLength = min(tag.maxTransceiveLength, apduCommandMaxSize),
        responseDataFieldMaxLength = min(tag.maxTransceiveLength, apduResponseMaxSize)
    ).toByteArray()
    Logger.dCbor(TAG, "CCE Handover Select", encodedCceHandoverSelect)

    Logger.i(TAG, "Handover complete")

    val cceHandoverSelect = Cbor.decode(encodedCceHandoverSelect)

    val deviceEngagementDataItem = cceHandoverSelect[0]
    val encodedDeviceEngagement = Cbor.encode(deviceEngagementDataItem)

    val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementDataItem)

    check(deviceEngagement.connectionMethods.size <= 1) {
        "Expected at most one Device Retrieval method in CCE engagement, found ${deviceEngagement.connectionMethods.size}"
    }

    val supplementedConnectionMethods = supplementBleConnectionMethods(
        deviceEngagement.connectionMethods,
        negotiatedHandoverConnectionMethods
    )

    val handover = buildCborArray {
        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCceHandoverSelect))) // CCEHandoverSelectBytes
        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCceHandoverRequest))) // CCEHandoverRequestBytes
    }

    return MdocReaderNfcHandoverResult(
        connectionMethods = MdocConnectionMethod.disambiguate(
            supplementedConnectionMethods,
            MdocRole.MDOC_READER
        ),
        encodedDeviceEngagement = ByteString(encodedDeviceEngagement),
        handover = handover,
        type = MdocHandoverType.CCE_HANDOVER,
        eReaderKey = eReaderKey,
        apduCommandMaxSize = apduCommandMaxSize,
        apduResponseMaxSize = apduResponseMaxSize,
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


internal suspend fun NfcIsoTag.nfcCceTransact(
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
