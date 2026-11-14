package org.multipaz.bthci

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import kotlinx.io.IOException
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocEngagementType
import org.multipaz.mdoc.transport.BleCentralConfig
import org.multipaz.mdoc.transport.BleCentralHal
import org.multipaz.mdoc.transport.BleL2capChannel
import org.multipaz.mdoc.transport.BlePeripheralHal
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.mdoc.transport.TransportWarning
import org.multipaz.mdoc.transport.TransportWarningCode
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.presentment.EngagementParams
import org.multipaz.presentment.FaceMatchingMode
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentOutcome
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.PresentmentTelemetry
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for [BleCentralHalHci], [BlePeripheralHalHci], [BleHciEngine], and [HciBleL2capChannel]
 * connected over an in-memory [VirtualHciBus] that emulates two Bluetooth LE controllers at the H4 HCI
 * packet boundary (`0x01` Command, `0x02` ACL Data, `0x04` Event).
 */
class BleHciTransportTest {

    internal class BleTranscriptLogger {
        private val loggerLock = HciLock()
        private val sb = StringBuilder()
        private val gattWriteChunkBuffer = HciByteBuffer()
        private val gattNotifyChunkBuffer = HciByteBuffer()
        private var l2capSduExpectedLen = -1
        private var l2capSduBuffer = HciByteBuffer()
        private val charNameByValueHandle = mutableMapOf<Int, String>()
        private val charNameByCccdHandle = mutableMapOf<Int, String>()
        private var lastReadHandle = -1
        private var lastWriteHandle = -1
        private var scanEnableCount = 0
        private var scanCurrentlyEnabled = false
        private var advReportReceivedInScanWindow = false
        private var advEnableCount = 0
        private var createConnectionCount = 0
        private var mtuExchangeCountInConnection = 0
        private var findByTypeValueCount = 0
        private val readByTypeCountByStartHandle = mutableMapOf<Int, Int>()
        private val readReqCountByHandle = mutableMapOf<Int, Int>()
        private val writeReqCountByHandle = mutableMapOf<Int, Int>()
        private var l2capConnReqCount = 0

        private fun isoCharacteristicName(uuid: UUID): String {
            return when (uuid.toString().lowercase()) {
                "00000005-a123-48ce-896b-4c76973373e6" -> "State"
                "00000006-a123-48ce-896b-4c76973373e6" -> "Client2Server"
                "00000007-a123-48ce-896b-4c76973373e6" -> "Server2Client"
                "00000008-a123-48ce-896b-4c76973373e6" -> "Ident"
                "0000000b-a123-48ce-896b-4c76973373e6" -> "L2CAP"
                else -> uuid.toString()
            }
        }

        private fun appendCborDiagnosticCommentLocked(cborBytes: ByteArray) {
            val diag = Cbor.toDiagnostics(
                cborBytes,
                setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
            )
            for (line in diag.lines()) {
                if (line.isBlank()) {
                    sb.appendLine("#")
                } else {
                    sb.appendLine("# $line")
                }
            }
        }

        fun appendHciCommand(role: String, packet: ByteArray): Unit = loggerLock.withLock {
            val opcode = u16le(packet, 1)
            val params = packet.copyOfRange(4, packet.size)
            val comment = when (opcode) {
                BleHciConstants.HCI_OP_RESET -> "$role sends HCI_Reset (0x0c03)."
                BleHciConstants.HCI_OP_SET_EVENT_MASK -> "$role sends HCI_Set_Event_Mask (0x0c01)."
                BleHciConstants.HCI_OP_LE_SET_EVENT_MASK -> "$role sends HCI_LE_Set_Event_Mask (0x2001)."
                BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE -> "$role sends HCI_LE_Read_Buffer_Size (0x2002)."
                BleHciConstants.HCI_OP_LE_SET_RANDOM_ADDRESS -> "$role sends HCI_LE_Set_Random_Address (0x2005)."
                BleHciConstants.HCI_OP_LE_SET_ADVERTISING_PARAMETERS ->
                    "$role sends HCI_LE_Set_Advertising_Parameters (0x2006)."
                BleHciConstants.HCI_OP_LE_SET_ADVERTISING_DATA ->
                    "$role sends HCI_LE_Set_Advertising_Data (0x2008)."
                BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE -> {
                    val enable = params[0].toInt() and 0xFF
                    if (enable != 0) {
                        val enableNum = advEnableCount++
                        if (enableNum == 0) {
                            "$role sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1)."
                        } else {
                            "$role sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1) (re-enabling advertising after mid-handshake disconnect)."
                        }
                    } else {
                        "$role sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0)."
                    }
                }
                BleHciConstants.HCI_OP_LE_SET_SCAN_PARAMETERS ->
                    "$role sends HCI_LE_Set_Scan_Parameters (0x200b)."
                BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE -> {
                    val enable = params[0].toInt() and 0xFF
                    if (enable != 0) {
                        val restartNum = scanEnableCount++
                        scanCurrentlyEnabled = true
                        advReportReceivedInScanWindow = false
                        if (restartNum == 0) {
                            "$role sends HCI_LE_Set_Scan_Enable (0x200c, enable=1)."
                        } else {
                            "$role sends HCI_LE_Set_Scan_Enable (0x200c, enable=1) (scan restart #$restartNum)."
                        }
                    } else {
                        val timedOut = scanCurrentlyEnabled && !advReportReceivedInScanWindow
                        scanCurrentlyEnabled = false
                        if (timedOut) {
                            "ERROR: Scan timed out with no advertising report received; $role sends HCI_LE_Set_Scan_Enable (0x200c, enable=0)."
                        } else {
                            "$role sends HCI_LE_Set_Scan_Enable (0x200c, enable=0)."
                        }
                    }
                }
                BleHciConstants.HCI_OP_LE_CREATE_CONNECTION -> {
                    mtuExchangeCountInConnection = 0
                    findByTypeValueCount = 0
                    val retryNum = createConnectionCount++
                    if (retryNum == 0) {
                        "$role sends HCI_LE_Create_Connection (0x200d)."
                    } else {
                        "$role sends HCI_LE_Create_Connection (0x200d) (GATT connect retry #$retryNum)."
                    }
                }
                BleHciConstants.HCI_OP_DISCONNECT -> {
                    val handle = if (params.size >= 2) u16le(params, 0) and 0x0FFF else 0
                    val reason = if (params.size >= 3) params[2].toInt() and 0xFF else 0
                    "$role sends HCI_Disconnect (0x0406, handle=0x${handle.toString(16).padStart(4, '0')}, reason=0x${reason.toString(16)})."
                }
                else -> "$role sends HCI command 0x${opcode.toString(16)}."
            }
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("# $comment")
            sb.appendLine("HCI-Cmd ($role): ${packet.toHex()}")
        }

        fun appendHciEvent(role: String, comment: String, packet: ByteArray): Unit = loggerLock.withLock {
            if (packet.size >= 4 &&
                (packet[1].toInt() and 0xFF) == BleHciConstants.HCI_EV_LE_META &&
                (packet[3].toInt() and 0xFF) == BleHciConstants.HCI_EV_LE_ADVERTISING_REPORT
            ) {
                advReportReceivedInScanWindow = true
            }
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("# $comment")
            sb.appendLine("HCI-Evt ($role): ${packet.toHex()}")
        }

        fun appendL2capPdu(senderRole: String, receiverRole: String, cid: Int, pdu: ByteArray): Unit = loggerLock.withLock {
            if (sb.isNotEmpty()) sb.appendLine()
            when (cid) {
                BleHciConstants.L2CAP_CID_ATT -> {
                    val opcode = pdu[0].toInt() and 0xFF
                    var cborToPrint: ByteArray? = null
                    val extraCommentLines = mutableListOf<String>()
                    val comment = when (opcode) {
                        BleHciConstants.ATT_OP_ERROR_RSP -> {
                            val reqOpcode = pdu[1].toInt() and 0xFF
                            val handle = u16le(pdu, 2)
                            val error = pdu[4].toInt() and 0xFF
                            when (reqOpcode) {
                                BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ ->
                                    "ERROR: $senderRole responds with ATT_ERROR_RSP (0x01, reqOpcode=0x${reqOpcode.toString(16)}, handle=0x${handle.toString(16)}, error=0x${error.toString(16)} Request Not Supported)."
                                BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ ->
                                    "ERROR: $senderRole responds with ATT_ERROR_RSP (0x01, reqOpcode=0x${reqOpcode.toString(16)}, handle=0x${handle.toString(16)}, error=0x${error.toString(16)} Attribute Not Found)."
                                BleHciConstants.ATT_OP_READ_BY_TYPE_REQ ->
                                    if (handle == 1) {
                                        "ERROR: $senderRole responds with ATT_ERROR_RSP (0x01, reqOpcode=0x${reqOpcode.toString(16)}, handle=0x${handle.toString(16)}, error=0x${error.toString(16)} Attribute Not Found)."
                                    } else {
                                        "$senderRole responds with ATT_ERROR_RSP (0x01, reqOpcode=0x${reqOpcode.toString(16)}, handle=0x${handle.toString(16)}, error=0x${error.toString(16)})."
                                    }
                                BleHciConstants.ATT_OP_READ_REQ,
                                BleHciConstants.ATT_OP_WRITE_REQ ->
                                    "ERROR: $senderRole responds with ATT_ERROR_RSP (0x01, reqOpcode=0x${reqOpcode.toString(16)}, handle=0x${handle.toString(16)}, error=0x${error.toString(16)} Unlikely Error)."
                                else ->
                                    "$senderRole responds with ATT_ERROR_RSP (0x01, reqOpcode=0x${reqOpcode.toString(16)}, handle=0x${handle.toString(16)}, error=0x${error.toString(16)})."
                            }
                        }
                        BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ -> {
                            val retryNum = mtuExchangeCountInConnection++
                            if (retryNum == 0) {
                                "$senderRole sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=${u16le(pdu, 1)})."
                            } else {
                                "$senderRole sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=${u16le(pdu, 1)}) (MTU exchange retry #$retryNum)."
                            }
                        }
                        BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP ->
                            "$senderRole responds with ATT_EXCHANGE_MTU_RSP (0x03, mtu=${u16le(pdu, 1)})."
                        BleHciConstants.ATT_OP_FIND_INFO_REQ -> {
                            val start = u16le(pdu, 1)
                            val end = u16le(pdu, 3)
                            val cccdOwner = charNameByCccdHandle[start]
                            if (start == end && cccdOwner != null) {
                                "$senderRole sends ATT_FIND_INFO_REQ (0x04, start=0x${start.toString(16)}, end=0x${end.toString(16)}) to discover $cccdOwner descriptors."
                            } else {
                                "$senderRole sends ATT_FIND_INFO_REQ (0x04, start=0x${start.toString(16)}, end=0x${end.toString(16)})."
                            }
                        }
                        BleHciConstants.ATT_OP_FIND_INFO_RSP -> {
                            val handle = u16le(pdu, 2)
                            val uuid16 = u16le(pdu, 4)
                            val cccdOwner = charNameByCccdHandle[handle]
                            if (uuid16 == BleHciConstants.GATT_UUID_CCCD && cccdOwner != null) {
                                "$senderRole responds with ATT_FIND_INFO_RSP (0x05, handle=0x${handle.toString(16)}, uuid=0x2902 CCCD for $cccdOwner)."
                            } else {
                                "$senderRole responds with ATT_FIND_INFO_RSP (0x05)."
                            }
                        }
                        BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ -> {
                            val serviceUuid = uuidFromLittleEndianBytes(pdu, 7)
                            val retryNum = findByTypeValueCount++
                            if (retryNum == 0) {
                                "$senderRole sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID $serviceUuid."
                            } else {
                                "$senderRole sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID $serviceUuid (service discovery retry #$retryNum)."
                            }
                        }
                        BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_RSP ->
                            "$senderRole responds with ATT_FIND_BY_TYPE_VALUE_RSP (0x07, start=0x${u16le(pdu, 1).toString(16)}, end=0x${u16le(pdu, 3).toString(16)})."
                        BleHciConstants.ATT_OP_READ_BY_TYPE_REQ -> {
                            val start = u16le(pdu, 1)
                            val end = u16le(pdu, 3)
                            val retryNum = readByTypeCountByStartHandle[start] ?: 0
                            readByTypeCountByStartHandle[start] = retryNum + 1
                            if (retryNum == 0) {
                                "$senderRole sends ATT_READ_BY_TYPE_REQ (0x08, start=0x${start.toString(16)}, end=0x${end.toString(16)}, type=0x2803 Characteristic Declaration)."
                            } else {
                                "$senderRole sends ATT_READ_BY_TYPE_REQ (0x08, start=0x${start.toString(16)}, end=0x${end.toString(16)}, type=0x2803 Characteristic Declaration) (characteristic discovery retry #$retryNum)."
                            }
                        }
                        BleHciConstants.ATT_OP_READ_BY_TYPE_RSP -> {
                            val entryLen = pdu[1].toInt() and 0xFF
                            if (entryLen == 21) {
                                val count = (pdu.size - 2) / 21
                                var offset = 2
                                repeat(count) {
                                    val props = pdu[offset + 2].toInt() and 0xFF
                                    val valHandle = u16le(pdu, offset + 3)
                                    val charUuid = uuidFromLittleEndianBytes(pdu, offset + 5)
                                    val charName = isoCharacteristicName(charUuid)
                                    charNameByValueHandle[valHandle] = charName
                                    if ((props and BleHciConstants.GATT_PROP_NOTIFY) != 0) {
                                        charNameByCccdHandle[valHandle + 1] = charName
                                    }
                                    val propsHex = "0x" + props.toString(16).padStart(2, '0')
                                    extraCommentLines.add(
                                        "  - $charName ($charUuid): valueHandle=0x${valHandle.toString(16)}, props=$propsHex"
                                    )
                                    offset += 21
                                }
                                "$senderRole responds with ATT_READ_BY_TYPE_RSP (0x09) declaring $count characteristics:"
                            } else {
                                "$senderRole responds with ATT_READ_BY_TYPE_RSP (0x09)."
                            }
                        }
                        BleHciConstants.ATT_OP_READ_REQ -> {
                            val handle = u16le(pdu, 1)
                            lastReadHandle = handle
                            val retryNum = readReqCountByHandle[handle] ?: 0
                            readReqCountByHandle[handle] = retryNum + 1
                            val charName = charNameByValueHandle[handle]
                            val retrySuffix = if (retryNum > 0) " (characteristic read retry #$retryNum)" else ""
                            if (charName != null) {
                                "$senderRole sends ATT_READ_REQ (0x0a, handle=0x${handle.toString(16)}) for $charName characteristic$retrySuffix."
                            } else {
                                "$senderRole sends ATT_READ_REQ (0x0a, handle=0x${handle.toString(16)})$retrySuffix."
                            }
                        }
                        BleHciConstants.ATT_OP_READ_RSP -> {
                            val value = pdu.copyOfRange(1, pdu.size)
                            val charName = charNameByValueHandle[lastReadHandle]
                            when (charName) {
                                "Ident" ->
                                    "$senderRole responds with ATT_READ_RSP (0x0b, ${value.size} bytes) for Ident characteristic (BLEIdent=${value.toHex()})."
                                "L2CAP" -> {
                                    val psm = ((value[0].toInt() and 0xFF) shl 24) or
                                        ((value[1].toInt() and 0xFF) shl 16) or
                                        ((value[2].toInt() and 0xFF) shl 8) or
                                        (value[3].toInt() and 0xFF)
                                    "$senderRole responds with ATT_READ_RSP (0x0b, ${value.size} bytes) for L2CAP characteristic (psm=0x${psm.toString(16)})."
                                }
                                else ->
                                    "$senderRole responds with ATT_READ_RSP (0x0b, ${value.size} bytes)."
                            }
                        }
                        BleHciConstants.ATT_OP_WRITE_REQ -> {
                            val handle = u16le(pdu, 1)
                            lastWriteHandle = handle
                            val retryNum = writeReqCountByHandle[handle] ?: 0
                            writeReqCountByHandle[handle] = retryNum + 1
                            val retrySuffix = if (retryNum > 0) " (CCCD write retry #$retryNum)" else ""
                            val cccdOwner = charNameByCccdHandle[handle]
                            if (cccdOwner != null && pdu.size == 5 && u16le(pdu, 3) == 0x0001) {
                                "$senderRole sends ATT_WRITE_REQ (0x12, handle=0x${handle.toString(16)}, value=0x0001) to enable notifications on $cccdOwner CCCD$retrySuffix."
                            } else {
                                "$senderRole sends ATT_WRITE_REQ (0x12, handle=0x${handle.toString(16)})$retrySuffix."
                            }
                        }
                        BleHciConstants.ATT_OP_WRITE_RSP -> {
                            val cccdOwner = charNameByCccdHandle[lastWriteHandle]
                            if (cccdOwner != null) {
                                "$senderRole responds with ATT_WRITE_RSP (0x13) for $cccdOwner CCCD."
                            } else {
                                "$senderRole responds with ATT_WRITE_RSP (0x13)."
                            }
                        }
                        BleHciConstants.ATT_OP_WRITE_CMD -> {
                            val handle = u16le(pdu, 1)
                            val value = pdu.copyOfRange(3, pdu.size)
                            if (handle == 3) {
                                "$senderRole writes 0x${(value[0].toInt() and 0xFF).toString(16)} to State characteristic (handle=0x03) via ATT_WRITE_CMD (0x52)."
                            } else {
                                val more = value[0].toInt() and 0xFF
                                gattWriteChunkBuffer.write(value, 1, value.size - 1)
                                if (more == 0x00) {
                                    cborToPrint = gattWriteChunkBuffer.toByteArray()
                                    gattWriteChunkBuffer.reset()
                                    "$senderRole sends final Client2Server GATT chunk (0x00, ${value.size - 1} payload bytes) via ATT_WRITE_CMD (0x52)."
                                } else {
                                    "$senderRole sends continuation Client2Server GATT chunk (0x01, ${value.size - 1} payload bytes) via ATT_WRITE_CMD (0x52)."
                                }
                            }
                        }
                        BleHciConstants.ATT_OP_HANDLE_VALUE_NTF -> {
                            val handle = u16le(pdu, 1)
                            val value = pdu.copyOfRange(3, pdu.size)
                            if (handle == 3) {
                                "$senderRole notifies 0x${(value[0].toInt() and 0xFF).toString(16)} on State characteristic (handle=0x03) via ATT_HANDLE_VALUE_NTF (0x1b)."
                            } else {
                                val more = value[0].toInt() and 0xFF
                                gattNotifyChunkBuffer.write(value, 1, value.size - 1)
                                if (more == 0x00) {
                                    cborToPrint = gattNotifyChunkBuffer.toByteArray()
                                    gattNotifyChunkBuffer.reset()
                                    "$senderRole sends final Server2Client GATT notification chunk (0x00, ${value.size - 1} payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b)."
                                } else {
                                    "$senderRole sends continuation Server2Client GATT notification chunk (0x01, ${value.size - 1} payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b)."
                                }
                            }
                        }
                        else -> "$senderRole sends ATT PDU 0x${opcode.toString(16)}."
                    }
                    sb.appendLine("# $comment")
                    for (line in extraCommentLines) {
                        sb.appendLine("# $line")
                    }
                    if (cborToPrint != null && cborToPrint.isNotEmpty()) {
                        appendCborDiagnosticCommentLocked(cborToPrint)
                    }
                    sb.appendLine("ATT $senderRole->$receiverRole: ${pdu.toHex()}")
                }
                BleHciConstants.L2CAP_CID_LE_SIGNALING -> {
                    val code = pdu[0].toInt() and 0xFF
                    val comment = when (code) {
                        BleHciConstants.L2CAP_LE_CREDIT_CONN_REQ -> {
                            val retryNum = l2capConnReqCount++
                            if (retryNum == 0) {
                                "$senderRole sends L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14, psm=0x${u16le(pdu, 4).toString(16)}, scid=0x${u16le(pdu, 6).toString(16)})."
                            } else {
                                "$senderRole sends L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14, psm=0x${u16le(pdu, 4).toString(16)}, scid=0x${u16le(pdu, 6).toString(16)}) (L2CAP connect retry #$retryNum)."
                            }
                        }
                        BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP -> {
                            val dcid = u16le(pdu, 4)
                            val result = u16le(pdu, 12)
                            if (result == 0) {
                                "$senderRole responds with L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, dcid=0x${dcid.toString(16)}, result=0x${result.toString(16)})."
                            } else {
                                "ERROR: $senderRole responds with L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, dcid=0x${dcid.toString(16)}, result=0x${result.toString(16)} No resources available)."
                            }
                        }
                        else -> "$senderRole sends L2CAP signaling code 0x${code.toString(16)}."
                    }
                    sb.appendLine("# $comment")
                    sb.appendLine("L2CAP-Sig $senderRole->$receiverRole: ${pdu.toHex()}")
                }
                else -> {
                    var completedSdu: ByteArray? = null
                    if (l2capSduExpectedLen < 0) {
                        val totalLen = u16le(pdu, 0)
                        val dataLen = pdu.size - 2
                        if (dataLen >= totalLen) {
                            completedSdu = pdu.copyOfRange(2, 2 + totalLen)
                        } else {
                            l2capSduExpectedLen = totalLen
                            l2capSduBuffer = HciByteBuffer(totalLen)
                            l2capSduBuffer.write(pdu, 2, dataLen)
                        }
                    } else {
                        val needed = l2capSduExpectedLen - l2capSduBuffer.size
                        val toCopy = min(needed, pdu.size)
                        l2capSduBuffer.write(pdu, 0, toCopy)
                        if (l2capSduBuffer.size >= l2capSduExpectedLen) {
                            completedSdu = l2capSduBuffer.toByteArray()
                            l2capSduExpectedLen = -1
                            l2capSduBuffer = HciByteBuffer()
                        }
                    }
                    if (completedSdu != null && completedSdu.size > 4) {
                        val cborBytes = completedSdu.copyOfRange(4, completedSdu.size)
                        sb.appendLine("# $senderRole sends L2CAP CoC K-frame completing ${cborBytes.size}-byte ISO 18013-5 message.")
                        appendCborDiagnosticCommentLocked(cborBytes)
                    } else {
                        sb.appendLine("# $senderRole sends L2CAP CoC K-frame (${pdu.size} bytes).")
                    }
                    sb.appendLine("L2CAP-CoC $senderRole->$receiverRole: ${pdu.toHex()}")
                }
            }
        }

        override fun toString(): String = loggerLock.withLock { sb.toString().trim() }
    }

    /**
     * Deterministic fault-injection configuration for [VirtualHciBus].
     *
     * In Bluetooth LE (Core Spec Vol 6, Part B, Section 4.5.9), once an LE ACL connection is established,
     * the Link Layer uses 1-bit Sequence Numbers (`SN`) and Next Expected Sequence Numbers (`NESN`) with
     * mandatory hardware/firmware ARQ retransmission at every connection event (`7.5ms–15ms`) until
     * `connSupervisionTimeout` (`5000ms`). Because of this Link Layer ARQ, individual ACL/L2CAP/ATT fragments
     * are never silently dropped mid-connection at the HCI boundary: either the Link Layer retransmits them
     * transparently over the air, or RF loss exceeds the supervision timeout and tears down the entire link
     * with `HCI_Disconnection_Complete (0x05, reason=0x08 Connection Timeout)` (tested separately via
     * [Endpoint.simulateDisconnect]).
     *
     * Instead, recoverable protocol retries in `BleCentralManagerImpl` and [MdocTransportStats] occur at the
     * four pre-connection and handshake boundaries modeled here:
     *
     * 1. **Advertising Broadcast Loss ([dropScanAttempts] -> [MdocTransportStats.bleNumScanRestarts]):**
     *    Advertising PDUs (`ADV_IND` on channels 37/38/39) are unacknowledged broadcasts with no Link Layer
     *    ARQ. For the first [dropScanAttempts] scan windows enabled by the Central (`HCI_LE_Set_Scan_Enable(1)`),
     *    [VirtualHciBus] drops the over-the-air `ADV_IND` so the Central's controller emits no
     *    `HCI_LE_Advertising_Report` before `scanRestartTimeout` expires, causing `BleCentralManagerImpl` to
     *    stop and restart scanning [dropScanAttempts] times.
     *
     * 2. **Connection Handshake Failure ([failConnectionAttempts] -> [MdocTransportStats.bleNumGattConnectRetries]):**
     *    When the Central sends `HCI_LE_Create_Connection`, the Link Layer transmits `CONNECT_IND` and attempts
     *    to exchange data-channel packets across the first 6 connection events. If `CONNECT_IND` or the initial
     *    data-channel ACK is lost to RF interference, the Central's controller aborts connection establishment
     *    with `HCI_LE_Connection_Complete (subevent=0x01, status=0x3e Connection Failed to be Established)`
     *    (the underlying cause of Android's infamous `BluetoothGatt` `status=133`), while the Peripheral
     *    continues advertising. For the first [failConnectionAttempts] `HCI_LE_Create_Connection` commands,
     *    [VirtualHciBus] emits `status=0x3e` to the Central, causing `BleCentralManagerImpl` to retry
     *    `connectToPeripheral()` [failConnectionAttempts] times.
     *
     * 3. **Delayed GATT Service Population ([failServiceDiscoveryAttempts] -> [MdocTransportStats.bleNumServiceDiscoveryRetries]):**
     *    If the Central sends `ATT_FIND_BY_TYPE_VALUE_REQ (0x06)` for the Primary Service UUID before the
     *    Peripheral's GATT server has finished registering its service table, the Peripheral responds with
     *    `ATT_ERROR_RSP (0x01, reqOpcode=0x06, handle=0x0001, error=0x0a Attribute Not Found)`. For the first
     *    [failServiceDiscoveryAttempts] service discovery requests, [VirtualHciBus] replies with `ATT_ERROR_RSP`
     *    (`0x0a`), causing `BleCentralManagerImpl` to retry `peripheralDiscoverServices()`
     *    [failServiceDiscoveryAttempts] times.
     *
     * 4. **Transient L2CAP CoC Resource/Server Unavailability ([failL2capConnectAttempts] -> [MdocTransportStats.bleNumL2capConnectRetries]):**
     *    If `L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14)` arrives before the Peripheral's L2CAP server is ready
     *    to accept a channel on the PSM, the Peripheral responds with
     *    `L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, result=0x0004 No resources available)`. For the first
     *    [failL2capConnectAttempts] L2CAP connection requests, [VirtualHciBus] replies with `result=0x0004`,
     *    causing `BleCentralManagerImpl` to back off and retry `connectL2cap()` [failL2capConnectAttempts] times.
     *
     * 5. **Mid-Handshake Disconnection & GATT Step Faults ([disconnectMidHandshakeAttempts],
     *    [failMtuExchangeAttempts], [failCharacteristicDiscoveryAttempts], [failCharacteristicReadAttempts],
     *    [failCccdWriteAttempts]):**
     *    Simulates `0x3E Connection Failed to be Established` firing *after* `HCI_LE_Connection_Complete`
     *    during GATT setup, as well as `ATT_ERROR_RSP` rejections during MTU exchange, characteristic
     *    discovery (`0x08`), characteristic read (`0x0a`), and CCCD descriptor write (`0x12`).
     */
    internal data class FaultConfig(
        val dropScanAttempts: Int = 0,
        val failConnectionAttempts: Int = 0,
        val failServiceDiscoveryAttempts: Int = 0,
        val failL2capConnectAttempts: Int = 0,
        val disconnectMidHandshakeAttempts: Int = 0,
        val failMtuExchangeAttempts: Int = 0,
        val failCharacteristicDiscoveryAttempts: Int = 0,
        val failCharacteristicReadAttempts: Int = 0,
        val failCccdWriteAttempts: Int = 0,
        val interceptMtuExchangeAtController: Boolean = false,
        val injectPeripheralGapClientRequestsOnConnect: Boolean = false,
    )

    /**
     * Simulates two Bluetooth LE controllers connected over the air at the HCI H4 packet layer.
     *
     * Intentionally defaults to a small 27-byte controller ACL buffer length and 4 ACL buffers so
     * multi-fragment ACL reassembly (`PB=0x00`/`0x02` + `PB=0x01`) and `HCI_Number_Of_Completed_Packets`
     * (`0x13`) credit flow control are exercised on every message.
     *
     * See [FaultConfig] for how Link Layer, ATT, and L2CAP signaling faults are deterministically injected.
     */
    internal class VirtualHciBus(
        private val aclDataPacketLength: Int = 27,
        private val totalNumAclDataPackets: Int = 4,
        private val useBrEdrBufferFallback: Boolean = false,
        private val useExtendedAdvertisingReports: Boolean = false,
        private val faultConfig: FaultConfig = FaultConfig(),
        val transcript: BleTranscriptLogger? = null
    ) {
        private var scanAttemptCount = 0
        private var connectionAttemptCount = 0
        private var serviceDiscoveryAttemptCount = 0
        private var l2capConnectAttemptCount = 0
        private var midHandshakeDisconnectCount = 0
        @Volatile
        private var pendingMidHandshakeDisconnectReason = -1
        private var mtuExchangeAttemptCount = 0
        private var charDiscoveryAttemptCount = 0
        private var charReadAttemptCount = 0
        private var cccdWriteAttemptCount = 0
        val l2capCreditIndCount = MutableStateFlow(0)
        val centralAttResponsesLock = HciLock()
        val centralAttResponsesToPeripheralGapClient = mutableListOf<ByteArray>()

        inner class Endpoint(val name: String) : HciPacketIo {
            lateinit var peer: Endpoint
            val rxQueue = Channel<ByteArray>(Channel.UNLIMITED)

            @Volatile
            var randomAddr = ByteArray(6)

            @Volatile
            var advData = ByteArray(0)

            @Volatile
            var advEnabled = false

            @Volatile
            var scanEnabled = false

            @Volatile
            var closed = false

            private var aclReassemblyCid = -1
            private var aclReassemblyLen = -1
            private var aclReassemblyBuf = HciByteBuffer()
            private val pendingAclFragments = mutableListOf<ByteArray>()

            override suspend fun readPacket(): ByteArray? {
                if (closed) return null
                return rxQueue.receiveCatching().getOrNull()
            }

            override suspend fun writePacket(packet: ByteArray) {
                if (closed || packet.isEmpty()) return
                when (packet[0]) {
                    BleHciConstants.HCI_COMMAND_PKT -> handleCommand(packet)
                    BleHciConstants.HCI_ACL_DATA_PKT -> handleAcl(packet)
                }
            }

            override fun close() {
                closed = true
                rxQueue.close()
            }

            private fun buildEventPacket(eventCode: Int, params: ByteArray): ByteArray {
                val ev = ByteArray(3 + params.size)
                ev[0] = BleHciConstants.HCI_EVENT_PKT
                ev[1] = (eventCode and 0xFF).toByte()
                ev[2] = (params.size and 0xFF).toByte()
                params.copyInto(ev, destinationOffset = 3)
                return ev
            }

            private fun sendEvent(eventCode: Int, params: ByteArray) {
                if (closed) return
                rxQueue.trySend(buildEventPacket(eventCode, params))
            }

            private fun sendCommandComplete(opcode: Int, returnParams: ByteArray = byteArrayOf(0x00)) {
                val params = ByteArray(3 + returnParams.size)
                params[0] = 0x01 // Num_HCI_Command_Packets
                putU16le(params, 1, opcode)
                returnParams.copyInto(params, destinationOffset = 3)
                sendEvent(BleHciConstants.HCI_EV_CMD_COMPLETE, params)
            }

            private fun sendCommandStatus(opcode: Int, status: Int = 0x00) {
                val params = ByteArray(4)
                params[0] = (status and 0xFF).toByte()
                params[1] = 0x01 // Num_HCI_Command_Packets
                putU16le(params, 2, opcode)
                sendEvent(BleHciConstants.HCI_EV_CMD_STATUS, params)
            }

            private fun emitAdvertisingReportTo(scanner: Endpoint) {
                val data = advData
                if (useExtendedAdvertisingReports) {
                    // HCI_LE_Extended_Advertising_Report (subevent 0x0D):
                    // [0]=subevent(0x0D), [1]=numReports(1), [2..3]=eventType, [4]=addrType(0x01),
                    // [5..10]=addr(6), [11..24]=phy/sid/txPower/rssi/etc, [25]=dataLen, [26..]=data
                    val params = ByteArray(26 + data.size)
                    params[0] = BleHciConstants.HCI_EV_LE_EXT_ADVERTISING_REPORT.toByte()
                    params[1] = 0x01
                    putU16le(params, 2, 0x0013) // Connectable + Scannable + Legacy
                    params[4] = 0x01 // Random Address
                    randomAddr.copyInto(params, destinationOffset = 5, startIndex = 0, endIndex = 6)
                    params[25] = (data.size and 0xFF).toByte()
                    data.copyInto(params, destinationOffset = 26)
                    scanner.sendEvent(BleHciConstants.HCI_EV_LE_META, params)
                } else {
                    // HCI_LE_Advertising_Report:
                    // [0]=subevent(0x02), [1]=numReports(1), [2]=eventType(0x00), [3]=addrType(0x01),
                    // [4..9]=addr(6), [10]=dataLen, [11..11+dataLen]=advData, [last]=RSSI(-45)
                    val params = ByteArray(11 + data.size + 1)
                    params[0] = BleHciConstants.HCI_EV_LE_ADVERTISING_REPORT.toByte()
                    params[1] = 0x01
                    params[2] = 0x00 // ADV_IND
                    params[3] = 0x01 // Random Address
                    randomAddr.copyInto(params, destinationOffset = 4, startIndex = 0, endIndex = 6)
                    params[10] = (data.size and 0xFF).toByte()
                    data.copyInto(params, destinationOffset = 11)
                    params[11 + data.size] = (-45).toByte()
                    val evPkt = buildEventPacket(BleHciConstants.HCI_EV_LE_META, params)
                    transcript?.appendHciEvent(
                        scanner.name,
                        "${scanner.name} receives HCI_LE_Advertising_Report (subevent=0x02) from $name.",
                        evPkt
                    )
                    scanner.rxQueue.trySend(evPkt)
                }
            }

            private fun emitLeConnectionComplete(
                role: Int,
                peerAddrType: Int,
                peerAddr: ByteArray,
                status: Int = 0x00
            ) {
                // [0]=subevent(0x01), [1]=status, [2..3]=handle, [4]=role,
                // [5]=peerAddrType, [6..11]=peerAddr, [12..13]=interval, [14..15]=latency, [16..17]=timeout, [18]=mca
                val params = ByteArray(19)
                params[0] = BleHciConstants.HCI_EV_LE_CONN_COMPLETE.toByte()
                params[1] = (status and 0xFF).toByte()
                putU16le(params, 2, if (status == 0x00) 0x0040 else 0x0000)
                params[4] = (role and 0xFF).toByte()
                params[5] = (peerAddrType and 0xFF).toByte()
                peerAddr.copyInto(params, destinationOffset = 6, startIndex = 0, endIndex = 6)
                putU16le(params, 12, if (status == 0x00) 0x0006 else 0x0000)
                putU16le(params, 14, 0x0000)
                putU16le(params, 16, if (status == 0x00) 0x01F4 else 0x0000)
                params[18] = 0x00
                val evPkt = buildEventPacket(BleHciConstants.HCI_EV_LE_META, params)
                if (role == 0x00) {
                    val comment = if (status == 0x00) {
                        "$name receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040)."
                    } else {
                        "ERROR: $name receives HCI_LE_Connection_Complete (subevent=0x01, status=0x${status.toString(16)} Connection Failed to be Established)."
                    }
                    transcript?.appendHciEvent(name, comment, evPkt)
                }
                rxQueue.trySend(evPkt)
            }

            private fun deliverSynthesizedL2capPduToSelf(cid: Int, pdu: ByteArray) {
                transcript?.appendL2capPdu(peer.name, name, cid, pdu)
                val totalLen = 4 + pdu.size
                val pkt = ByteArray(5 + totalLen)
                pkt[0] = BleHciConstants.HCI_ACL_DATA_PKT
                putU16le(pkt, 1, 0x0040 or (0x02 shl 12))
                putU16le(pkt, 3, totalLen)
                putU16le(pkt, 5, pdu.size)
                putU16le(pkt, 7, cid)
                pdu.copyInto(pkt, destinationOffset = 9)
                rxQueue.trySend(pkt)
            }

            private fun handleCommand(packet: ByteArray) {
                if (packet.size < 4) return
                val opcode = u16le(packet, 1)
                val paramLen = packet[3].toInt() and 0xFF
                val params = packet.copyOfRange(4, 4 + paramLen)
                transcript?.appendHciCommand(name, packet)

                when (opcode) {
                    BleHciConstants.HCI_OP_RESET,
                    BleHciConstants.HCI_OP_SET_EVENT_MASK,
                    BleHciConstants.HCI_OP_LE_SET_EVENT_MASK,
                    BleHciConstants.HCI_OP_LE_SET_ADVERTISING_PARAMETERS,
                    BleHciConstants.HCI_OP_LE_SET_SCAN_RESPONSE_DATA,
                    BleHciConstants.HCI_OP_LE_SET_SCAN_PARAMETERS,
                    BleHciConstants.HCI_OP_LE_CREATE_CONNECTION_CANCEL -> {
                        sendCommandComplete(opcode)
                    }
                    BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE -> {
                        val ret = ByteArray(4)
                        ret[0] = 0x00 // status
                        if (!useBrEdrBufferFallback) {
                            putU16le(ret, 1, aclDataPacketLength)
                            ret[3] = (totalNumAclDataPackets and 0xFF).toByte()
                        }
                        sendCommandComplete(opcode, ret)
                    }
                    BleHciConstants.HCI_OP_READ_BUFFER_SIZE -> {
                        val ret = ByteArray(8)
                        ret[0] = 0x00 // status
                        putU16le(ret, 1, aclDataPacketLength)
                        ret[3] = 64 // scoPacketLen
                        putU16le(ret, 4, totalNumAclDataPackets)
                        putU16le(ret, 6, 4) // totalNumScoPackets
                        sendCommandComplete(opcode, ret)
                    }
                    BleHciConstants.HCI_OP_LE_SET_RANDOM_ADDRESS -> {
                        if (params.size >= 6) {
                            randomAddr = params.copyOfRange(0, 6)
                        }
                        sendCommandComplete(opcode)
                    }
                    BleHciConstants.HCI_OP_LE_SET_ADVERTISING_DATA -> {
                        if (params.isNotEmpty()) {
                            val len = (params[0].toInt() and 0xFF).coerceAtMost(params.size - 1)
                            advData = params.copyOfRange(1, 1 + len)
                        }
                        sendCommandComplete(opcode)
                    }
                    BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE -> {
                        advEnabled = params.isNotEmpty() && params[0].toInt() != 0
                        sendCommandComplete(opcode)
                        val disconnectReason = pendingMidHandshakeDisconnectReason
                        if (advEnabled && disconnectReason >= 0) {
                            pendingMidHandshakeDisconnectReason = -1
                            val disconnParams = ByteArray(4)
                            disconnParams[0] = 0x00
                            putU16le(disconnParams, 1, 0x0040)
                            disconnParams[3] = (disconnectReason and 0xFF).toByte()
                            val evPkt = buildEventPacket(BleHciConstants.HCI_EV_DISCONN_COMPLETE, disconnParams)
                            transcript?.appendHciEvent(
                                peer.name,
                                "ERROR: ${peer.name} receives HCI_Disconnection_Complete (0x05, status=0x00, handle=0x0040, reason=0x${disconnectReason.toString(16)} Connection Failed to be Established).",
                                evPkt
                            )
                            peer.rxQueue.trySend(evPkt)
                        } else if (advEnabled && peer.scanEnabled && scanAttemptCount > faultConfig.dropScanAttempts) {
                            emitAdvertisingReportTo(peer)
                        }
                    }
                    BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE -> {
                        scanEnabled = params.isNotEmpty() && params[0].toInt() != 0
                        sendCommandComplete(opcode)
                        if (scanEnabled) {
                            scanAttemptCount++
                            if (peer.advEnabled && scanAttemptCount > faultConfig.dropScanAttempts) {
                                peer.emitAdvertisingReportTo(this)
                            }
                        }
                    }
                    BleHciConstants.HCI_OP_LE_CREATE_CONNECTION -> {
                        sendCommandStatus(opcode, 0x00)
                        scanEnabled = false
                        connectionAttemptCount++
                        if (connectionAttemptCount <= faultConfig.failConnectionAttempts) {
                            // Simulate CONNECT_IND handshake failing over the air (status = 0x3E);
                            // Peripheral never receives CONNECT_IND and remains advertising.
                            emitLeConnectionComplete(
                                role = 0x00,
                                peerAddrType = 0x01,
                                peerAddr = peer.randomAddr,
                                status = 0x3E
                            )
                        } else {
                            peer.advEnabled = false
                            emitLeConnectionComplete(role = 0x00, peerAddrType = 0x01, peerAddr = peer.randomAddr)
                            peer.emitLeConnectionComplete(role = 0x01, peerAddrType = 0x01, peerAddr = randomAddr)
                        }
                    }
                    BleHciConstants.HCI_OP_DISCONNECT -> {
                        sendCommandStatus(opcode, 0x00)
                        simulateDisconnect()
                    }
                    else -> {
                        sendCommandComplete(opcode)
                    }
                }
            }

            private fun handleAcl(packet: ByteArray) {
                if (packet.size < 5) return
                val handleAndFlags = u16le(packet, 1)
                val handle = handleAndFlags and 0x0FFF
                val pbFlag = (handleAndFlags ushr 12) and 0x03
                val dlen = u16le(packet, 3)

                // Emit HCI_Number_Of_Completed_Packets (0x13) back to sender
                val compParams = ByteArray(5)
                compParams[0] = 0x01 // numHandles = 1
                putU16le(compParams, 1, handle)
                putU16le(compParams, 3, 1) // 1 completed packet
                sendEvent(BleHciConstants.HCI_EV_NUM_COMP_PKTS, compParams)

                // Convert Host->Controller PB=0x00 start flag to Controller->Host PB=0x02
                val rxPbFlag = if (pbFlag == 0x00) 0x02 else pbFlag
                val rxPacket = packet.copyOf()
                putU16le(rxPacket, 1, handle or (rxPbFlag shl 12))

                var completedCid = -1
                var completedPdu: ByteArray? = null

                if (packet.size >= 5 + dlen) {
                    if (pbFlag == 0x00 || pbFlag == 0x02) {
                        pendingAclFragments.clear()
                        pendingAclFragments.add(rxPacket)
                        if (dlen >= 4) {
                            val l2capLen = u16le(packet, 5)
                            val cid = u16le(packet, 7)
                            val chunkLen = dlen - 4
                            if (chunkLen >= l2capLen) {
                                completedCid = cid
                                completedPdu = packet.copyOfRange(9, 9 + l2capLen)
                            } else {
                                aclReassemblyCid = cid
                                aclReassemblyLen = l2capLen
                                aclReassemblyBuf = HciByteBuffer(l2capLen)
                                aclReassemblyBuf.write(packet, 9, chunkLen)
                            }
                        }
                    } else if (pbFlag == 0x01 && aclReassemblyLen >= 0) {
                        pendingAclFragments.add(rxPacket)
                        val needed = aclReassemblyLen - aclReassemblyBuf.size
                        val toCopy = min(needed, dlen)
                        aclReassemblyBuf.write(packet, 5, toCopy)
                        if (aclReassemblyBuf.size >= aclReassemblyLen) {
                            completedCid = aclReassemblyCid
                            completedPdu = aclReassemblyBuf.toByteArray()
                            aclReassemblyLen = -1
                            aclReassemblyBuf = HciByteBuffer()
                        }
                    }
                }

                if (completedPdu != null) {
                    transcript?.appendL2capPdu(name, peer.name, completedCid, completedPdu)

                    // Check deterministic fault injection rules on Central->Peripheral requests
                    if (name == "Central" &&
                        completedCid == BleHciConstants.L2CAP_CID_ATT &&
                        completedPdu.isNotEmpty()
                    ) {
                        val attOpcode = completedPdu[0].toInt() and 0xFF
                        when (attOpcode) {
                            BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ -> {
                                if (faultConfig.injectPeripheralGapClientRequestsOnConnect) {
                                    // Simulate AOSP peripheral GAP client sending ATT_READ_BY_TYPE_REQ
                                    // (0x08) for GAP Device Name (0x2A00) and ATT_EXCHANGE_MTU_REQ (0x02)
                                    // while Central's ATT_EXCHANGE_MTU_REQ is in flight.
                                    deliverSynthesizedL2capPduToSelf(
                                        BleHciConstants.L2CAP_CID_ATT,
                                        byteArrayOf(0x08, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x2A)
                                    )
                                    deliverSynthesizedL2capPduToSelf(
                                        BleHciConstants.L2CAP_CID_ATT,
                                        byteArrayOf(0x02, 0x05, 0x02)
                                    )
                                }
                                if (faultConfig.interceptMtuExchangeAtController) {
                                    pendingAclFragments.clear()
                                    val mtuRsp = byteArrayOf(
                                        BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP.toByte(),
                                        0x03,
                                        0x02
                                    )
                                    deliverSynthesizedL2capPduToSelf(BleHciConstants.L2CAP_CID_ATT, mtuRsp)
                                    return
                                }
                                mtuExchangeAttemptCount++
                                if (mtuExchangeAttemptCount <= faultConfig.failMtuExchangeAttempts) {
                                    pendingAclFragments.clear()
                                    val errRsp = byteArrayOf(
                                        BleHciConstants.ATT_OP_ERROR_RSP.toByte(),
                                        BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ.toByte(),
                                        0x00,
                                        0x00,
                                        BleHciConstants.ATT_ERR_REQUEST_NOT_SUPPORTED.toByte()
                                    )
                                    deliverSynthesizedL2capPduToSelf(BleHciConstants.L2CAP_CID_ATT, errRsp)
                                    return
                                }
                            }
                            BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ -> {
                                if (faultConfig.injectPeripheralGapClientRequestsOnConnect) {
                                    // Simulate AOSP peripheral GAP client sending ATT_READ_BY_TYPE_REQ
                                    // (0x08) for GAP Appearance (0x2A01) while Central's
                                    // ATT_FIND_BY_TYPE_VALUE_REQ (0x06) is in flight.
                                    deliverSynthesizedL2capPduToSelf(
                                        BleHciConstants.L2CAP_CID_ATT,
                                        byteArrayOf(0x08, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x01, 0x2A)
                                    )
                                }
                                if (midHandshakeDisconnectCount < faultConfig.disconnectMidHandshakeAttempts) {
                                    midHandshakeDisconnectCount++
                                    pendingAclFragments.clear()
                                    pendingMidHandshakeDisconnectReason = 0x3E
                                    val disconnParams = ByteArray(4)
                                    disconnParams[0] = 0x00
                                    putU16le(disconnParams, 1, 0x0040)
                                    disconnParams[3] = 0x3E
                                    val evPkt = buildEventPacket(BleHciConstants.HCI_EV_DISCONN_COMPLETE, disconnParams)
                                    transcript?.appendHciEvent(
                                        peer.name,
                                        "ERROR: ${peer.name} receives HCI_Disconnection_Complete (0x05, status=0x00, handle=0x0040, reason=0x3e Connection Failed to be Established).",
                                        evPkt
                                    )
                                    peer.rxQueue.trySend(evPkt)
                                    return
                                }
                                serviceDiscoveryAttemptCount++
                                if (serviceDiscoveryAttemptCount <= faultConfig.failServiceDiscoveryAttempts) {
                                    pendingAclFragments.clear()
                                    val errRsp = byteArrayOf(
                                        BleHciConstants.ATT_OP_ERROR_RSP.toByte(),
                                        BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ.toByte(),
                                        0x01,
                                        0x00,
                                        BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND.toByte()
                                    )
                                    deliverSynthesizedL2capPduToSelf(BleHciConstants.L2CAP_CID_ATT, errRsp)
                                    return
                                }
                            }
                            BleHciConstants.ATT_OP_READ_BY_TYPE_REQ -> {
                                charDiscoveryAttemptCount++
                                if (charDiscoveryAttemptCount <= faultConfig.failCharacteristicDiscoveryAttempts) {
                                    pendingAclFragments.clear()
                                    val errRsp = byteArrayOf(
                                        BleHciConstants.ATT_OP_ERROR_RSP.toByte(),
                                        BleHciConstants.ATT_OP_READ_BY_TYPE_REQ.toByte(),
                                        completedPdu.getOrElse(1) { 0x01 },
                                        completedPdu.getOrElse(2) { 0x00 },
                                        BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND.toByte()
                                    )
                                    deliverSynthesizedL2capPduToSelf(BleHciConstants.L2CAP_CID_ATT, errRsp)
                                    return
                                }
                            }
                            BleHciConstants.ATT_OP_READ_REQ -> {
                                charReadAttemptCount++
                                if (charReadAttemptCount <= faultConfig.failCharacteristicReadAttempts) {
                                    pendingAclFragments.clear()
                                    val errRsp = byteArrayOf(
                                        BleHciConstants.ATT_OP_ERROR_RSP.toByte(),
                                        BleHciConstants.ATT_OP_READ_REQ.toByte(),
                                        completedPdu.getOrElse(1) { 0x01 },
                                        completedPdu.getOrElse(2) { 0x00 },
                                        BleHciConstants.ATT_ERR_UNLIKELY_ERROR.toByte()
                                    )
                                    deliverSynthesizedL2capPduToSelf(BleHciConstants.L2CAP_CID_ATT, errRsp)
                                    return
                                }
                            }
                            BleHciConstants.ATT_OP_WRITE_REQ -> {
                                cccdWriteAttemptCount++
                                if (cccdWriteAttemptCount <= faultConfig.failCccdWriteAttempts) {
                                    pendingAclFragments.clear()
                                    val errRsp = byteArrayOf(
                                        BleHciConstants.ATT_OP_ERROR_RSP.toByte(),
                                        BleHciConstants.ATT_OP_WRITE_REQ.toByte(),
                                        completedPdu.getOrElse(1) { 0x01 },
                                        completedPdu.getOrElse(2) { 0x00 },
                                        BleHciConstants.ATT_ERR_UNLIKELY_ERROR.toByte()
                                    )
                                    deliverSynthesizedL2capPduToSelf(BleHciConstants.L2CAP_CID_ATT, errRsp)
                                    return
                                }
                            }
                            BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP,
                            BleHciConstants.ATT_OP_READ_BY_TYPE_RSP,
                            BleHciConstants.ATT_OP_ERROR_RSP -> {
                                if (faultConfig.injectPeripheralGapClientRequestsOnConnect) {
                                    centralAttResponsesLock.withLock {
                                        centralAttResponsesToPeripheralGapClient.add(completedPdu)
                                    }
                                    pendingAclFragments.clear()
                                    return
                                }
                            }
                        }
                    } else if (completedCid == BleHciConstants.L2CAP_CID_LE_SIGNALING &&
                        completedPdu.size >= 4
                    ) {
                        val sigCode = completedPdu[0].toInt() and 0xFF
                        if (sigCode == BleHciConstants.L2CAP_FLOW_CONTROL_CREDIT_IND) {
                            l2capCreditIndCount.update { it + 1 }
                        } else if (name == "Central" && sigCode == BleHciConstants.L2CAP_LE_CREDIT_CONN_REQ) {
                            l2capConnectAttemptCount++
                            if (l2capConnectAttemptCount <= faultConfig.failL2capConnectAttempts) {
                                pendingAclFragments.clear()
                                val rejectRsp = ByteArray(14)
                                rejectRsp[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP.toByte()
                                rejectRsp[1] = completedPdu[1] // matching identifier
                                putU16le(rejectRsp, 2, 10)
                                putU16le(rejectRsp, 4, 0x0000) // dcid = 0
                                putU16le(rejectRsp, 6, 0x0000) // mtu = 0
                                putU16le(rejectRsp, 8, 0x0000) // mps = 0
                                putU16le(rejectRsp, 10, 0x0000) // initial credits = 0
                                putU16le(rejectRsp, 12, 0x0004) // result = 0x0004 (No resources available)
                                deliverSynthesizedL2capPduToSelf(BleHciConstants.L2CAP_CID_LE_SIGNALING, rejectRsp)
                                return
                            }
                        }
                    }

                    for (frag in pendingAclFragments) {
                        peer.rxQueue.trySend(frag)
                    }
                    pendingAclFragments.clear()
                }
            }

            fun simulateDisconnect(reason: Int = 0x13) {
                val params = ByteArray(4)
                params[0] = 0x00 // status
                putU16le(params, 1, 0x0040) // handle
                params[3] = (reason and 0xFF).toByte()
                sendEvent(BleHciConstants.HCI_EV_DISCONN_COMPLETE, params)
                peer.sendEvent(BleHciConstants.HCI_EV_DISCONN_COMPLETE, params)
            }
        }

        val centralIo = Endpoint("Central")
        val peripheralIo = Endpoint("Peripheral")

        init {
            centralIo.peer = peripheralIo
            peripheralIo.peer = centralIo
        }
    }

    private fun createCentralManager(
        io: HciPacketIo,
        randomAddress: ByteArray? = null,
        gapDeviceName: String = "Multipaz"
    ): MdocTransportFactory =
        BtHciMdocTransportFactory(
            gapDeviceName = gapDeviceName,
            randomAddress = randomAddress,
            centralConfig = BleCentralConfig(
                scanRestartTimeout = 20.milliseconds,
                postScanDelay = 1.milliseconds,
                connectRetryDelay = 10.milliseconds,
                discoverServicesRetryDelay = 10.milliseconds,
                requireL2capPeripheralConnectedFirst = true,
                l2capRetryInitialDelay = 10.milliseconds,
                l2capRetryMaxDelay = 40.milliseconds,
            ),
            packetIoFactory = { io },
        )

    private fun createPeripheralManager(
        io: HciPacketIo,
        randomAddress: ByteArray? = null
    ): MdocTransportFactory =
        BtHciMdocTransportFactory(
            randomAddress = randomAddress,
            packetIoFactory = { io },
        )

    private fun BleTransportCentralMdoc(
        role: MdocRole,
        options: MdocTransportOptions,
        centralManager: MdocTransportFactory,
        uuid: UUID,
        psm: Int?
    ): MdocTransport = centralManager.createTransport(
        if (psm != null) {
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = uuid, psm = psm)
        } else {
            MdocConnectionMethod.Ble.GattCentral(uuid = uuid)
        },
        role,
        options,
    )

    private fun BleTransportCentralMdocReader(
        role: MdocRole,
        options: MdocTransportOptions,
        peripheralManager: MdocTransportFactory,
        uuid: UUID,
        directL2capEnabled: Boolean = false,
    ): MdocTransport = peripheralManager.createTransport(
        if (directL2capEnabled) {
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = uuid)
        } else {
            MdocConnectionMethod.Ble.GattCentral(uuid = uuid)
        },
        role,
        options,
    )

    private fun BleTransportPeripheralMdoc(
        role: MdocRole,
        options: MdocTransportOptions,
        peripheralManager: MdocTransportFactory,
        uuid: UUID,
        directL2capEnabled: Boolean = false,
    ): MdocTransport = peripheralManager.createTransport(
        if (directL2capEnabled) {
            MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = uuid)
        } else {
            MdocConnectionMethod.Ble.GattPeripheral(uuid = uuid)
        },
        role,
        options,
    )

    private fun BleTransportPeripheralMdocReader(
        role: MdocRole,
        options: MdocTransportOptions,
        centralManager: MdocTransportFactory,
        uuid: UUID,
        psm: Int?
    ): MdocTransport = centralManager.createTransport(
        if (psm != null) {
            MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = uuid, psm = psm)
        } else {
            MdocConnectionMethod.Ble.GattPeripheral(uuid = uuid)
        },
        role,
        options,
    )

    @Test
    fun centralClientModeGattEndToEnd() = runTest {
        withContext(Dispatchers.Default) {
            val bus = VirtualHciBus(aclDataPacketLength = 27, totalNumAclDataPackets = 4)
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val mdocTransport = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = false),
                centralManager = createCentralManager(bus.centralIo),
                uuid = serviceUuid,
                psm = null
            )
            val readerTransport = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = false),
                peripheralManager = createPeripheralManager(bus.peripheralIo),
                uuid = serviceUuid
            )

            try {
                coroutineScope {
                    val readerOpen = async { readerTransport.open(eSenderKey) }
                    val mdocOpen = async { mdocTransport.open(eSenderKey) }
                    readerOpen.await()
                    mdocOpen.await()
                }

                assertEquals(MdocTransport.State.CONNECTED, mdocTransport.state.value)
                assertEquals(MdocTransport.State.CONNECTED, readerTransport.state.value)

                // Send multi-chunk SessionEstablishment message (2,500 bytes > 512-byte GATT chunk limit)
                val requestMsg = Random(42).nextBytes(2500)
                val responseMsg = Random(43).nextBytes(3200)

                coroutineScope {
                    val recvJob = async { readerTransport.waitForMessage() }
                    mdocTransport.sendMessage(requestMsg)
                    assertContentEquals(requestMsg, recvJob.await())
                }

                coroutineScope {
                    val recvJob = async { mdocTransport.waitForMessage() }
                    readerTransport.sendMessage(responseMsg)
                    assertContentEquals(responseMsg, recvJob.await())
                }

                // Transport-specific termination via empty message (State characteristic 0x02)
                coroutineScope {
                    val recvEnd = async { readerTransport.waitForMessage() }
                    mdocTransport.sendMessage(byteArrayOf())
                    val term = recvEnd.await()
                    assertTrue(term.isEmpty())
                }
            } finally {
                mdocTransport?.close()
                readerTransport.close()
            }
        }
    }

    @Test
    fun peripheralServerModeGattEndToEnd() = runTest {
        withContext(Dispatchers.Default) {
            val bus = VirtualHciBus(aclDataPacketLength = 27, totalNumAclDataPackets = 4)
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val mdocTransport = BleTransportPeripheralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = false),
                peripheralManager = createPeripheralManager(bus.peripheralIo),
                uuid = serviceUuid
            )
            val readerTransport = BleTransportPeripheralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = false),
                centralManager = createCentralManager(bus.centralIo),
                uuid = serviceUuid,
                psm = null
            )

            try {
                mdocTransport.advertise()
                assertEquals(MdocTransport.State.ADVERTISING, mdocTransport.state.value)

                coroutineScope {
                    val mdocOpen = async { mdocTransport.open(eSenderKey) }
                    val readerOpen = async { readerTransport.open(eSenderKey) }
                    mdocOpen.await()
                    readerOpen.await()
                }

                val requestMsg = Random(101).nextBytes(1800)
                val responseMsg = Random(102).nextBytes(4100)

                coroutineScope {
                    val recvJob = async { mdocTransport.waitForMessage() }
                    readerTransport.sendMessage(requestMsg)
                    assertContentEquals(requestMsg, recvJob.await())
                }

                coroutineScope {
                    val recvJob = async { readerTransport.waitForMessage() }
                    mdocTransport.sendMessage(responseMsg)
                    assertContentEquals(responseMsg, recvJob.await())
                }

                // Reader sends transport-specific termination (State = 0x02)
                coroutineScope {
                    val recvEnd = async { mdocTransport.waitForMessage() }
                    readerTransport.sendMessage(byteArrayOf())
                    assertTrue(recvEnd.await().isEmpty())
                }
            } finally {
                mdocTransport?.close()
                readerTransport.close()
            }
        }
    }

    @Test
    fun centralClientModeL2capViaGattCharacteristicEndToEnd() = runTest {
        withContext(Dispatchers.Default) {
            val bus = VirtualHciBus(aclDataPacketLength = 27, totalNumAclDataPackets = 4)
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val centralManager = createCentralManager(bus.centralIo)
            val peripheralManager = createPeripheralManager(bus.peripheralIo)
            val mdocTransport = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = true),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )
            val readerTransport = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = true),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )

            try {
                coroutineScope {
                    val readerOpen = async { readerTransport.open(eSenderKey) }
                    val mdocOpen = async { mdocTransport.open(eSenderKey) }
                    readerOpen.await()
                    mdocOpen.await()
                }

                assertEquals(true, mdocTransport.stats.bleUsingL2cap)
                assertEquals(true, readerTransport.stats.bleUsingL2cap)

                // Send large 20 KB payload over L2CAP CoC to exercise K-frame segmentation, ACL slicing,
                // and L2CAP_FLOW_CONTROL_CREDIT_IND credit replenishment.
                val requestMsg = Random(201).nextBytes(20_000)
                val responseMsg = Random(202).nextBytes(25_000)

                coroutineScope {
                    val recvJob = async { readerTransport.waitForMessage() }
                    mdocTransport.sendMessage(requestMsg)
                    assertContentEquals(requestMsg, recvJob.await())
                }

                coroutineScope {
                    val recvJob = async { mdocTransport.waitForMessage() }
                    readerTransport.sendMessage(responseMsg)
                    assertContentEquals(responseMsg, recvJob.await())
                }
            } finally {
                mdocTransport?.close()
                readerTransport.close()
            }
        }
    }

    @Test
    fun peripheralServerModeDirectL2capInEngagementEndToEnd() = runTest {
        withContext(Dispatchers.Default) {
            val bus = VirtualHciBus(aclDataPacketLength = 27, totalNumAclDataPackets = 4)
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val peripheralManager = createPeripheralManager(bus.peripheralIo)
            val mdocTransport = BleTransportPeripheralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(),
                peripheralManager = peripheralManager,
                uuid = serviceUuid,
                directL2capEnabled = true,
            )

            try {
                mdocTransport.advertise()
                val engagedPsm = (mdocTransport.connectionMethod as MdocConnectionMethod.Ble.DirectL2capPeripheral).psm
                assertTrue(engagedPsm != null && engagedPsm > 0)

                val centralManager = createCentralManager(bus.centralIo)
                val readerTransport = BleTransportPeripheralMdocReader(
                    role = MdocRole.MDOC_READER,
                    options = MdocTransportOptions(),
                    centralManager = centralManager,
                    uuid = serviceUuid,
                    psm = engagedPsm
                )
                try {
                    coroutineScope {
                        val mdocOpen = async { mdocTransport.open(eSenderKey) }
                        val readerOpen = async { readerTransport.open(eSenderKey) }
                        mdocOpen.await()
                        readerOpen.await()
                    }

                    assertEquals(true, mdocTransport.stats.bleUsingL2cap)
                    assertEquals(true, readerTransport.stats.bleUsingL2cap)

                    val requestMsg = Random(301).nextBytes(12_000)
                    val responseMsg = Random(302).nextBytes(18_000)

                    coroutineScope {
                        val recvJob = async { mdocTransport.waitForMessage() }
                        readerTransport.sendMessage(requestMsg)
                        assertContentEquals(requestMsg, recvJob.await())
                    }

                    coroutineScope {
                        val recvJob = async { readerTransport.waitForMessage() }
                        mdocTransport.sendMessage(responseMsg)
                        assertContentEquals(responseMsg, recvJob.await())
                    }
                } finally {
                    readerTransport.close()
                }
            } finally {
                mdocTransport.close()
            }
        }
    }

    @Test
    fun delayedAdvertisingDiscoveredDuringActiveScan() = runTest {
        withContext(Dispatchers.Default) {
            val bus = VirtualHciBus()
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val mdocTransport = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = false),
                centralManager = createCentralManager(bus.centralIo),
                uuid = serviceUuid,
                psm = null
            )
            val readerTransport = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = false),
                peripheralManager = createPeripheralManager(bus.peripheralIo),
                uuid = serviceUuid
            )

            try {
                coroutineScope {
                    // Start Central scanning first before Peripheral begins advertising
                    val mdocOpen = async { mdocTransport.open(eSenderKey) }
                    delay(20.milliseconds)
                    val readerOpen = async { readerTransport.open(eSenderKey) }
                    mdocOpen.await()
                    readerOpen.await()
                }

                val payload = Random(77).nextBytes(600)
                coroutineScope {
                    val recv = async { readerTransport.waitForMessage() }
                    mdocTransport.sendMessage(payload)
                    assertContentEquals(payload, recv.await())
                }
            } finally {
                mdocTransport?.close()
                readerTransport.close()
            }
        }
    }

    @Test
    fun peripheralServerModeL2capViaCharacteristicAndExtAdvAndBrEdrBufferFallback() = runTest {
        withContext(Dispatchers.Default) {
            val bus = VirtualHciBus(
                aclDataPacketLength = 27,
                totalNumAclDataPackets = 4,
                useBrEdrBufferFallback = true,
                useExtendedAdvertisingReports = true
            )
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val peripheralManager = createPeripheralManager(bus.peripheralIo)
            val centralManager = createCentralManager(bus.centralIo)

            val mdocTransport = BleTransportPeripheralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = true),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )
            val readerTransport = BleTransportPeripheralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = true),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )

            try {
                mdocTransport.advertise()
                coroutineScope {
                    val mdocOpen = async { mdocTransport.open(eSenderKey) }
                    val readerOpen = async { readerTransport.open(eSenderKey) }
                    mdocOpen.await()
                    readerOpen.await()
                }

                assertEquals(true, mdocTransport.stats.bleUsingL2cap)
                assertEquals(true, readerTransport.stats.bleUsingL2cap)

                val requestMsg = Random(401).nextBytes(8_000)
                val responseMsg = Random(402).nextBytes(10_000)

                coroutineScope {
                    val recvJob = async { mdocTransport.waitForMessage() }
                    readerTransport.sendMessage(requestMsg)
                    assertContentEquals(requestMsg, recvJob.await())
                }

                coroutineScope {
                    val recvJob = async { readerTransport.waitForMessage() }
                    mdocTransport.sendMessage(responseMsg)
                    assertContentEquals(responseMsg, recvJob.await())
                }
            } finally {
                mdocTransport?.close()
                readerTransport.close()
            }
        }
    }

    @Test
    fun unexpectedHciDisconnectionFailsWaitForMessage() = runTest {
        withContext(Dispatchers.Default) {
            val bus = VirtualHciBus()
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val mdocTransport = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = false),
                centralManager = createCentralManager(bus.centralIo),
                uuid = serviceUuid,
                psm = null
            )
            val readerTransport = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = false),
                peripheralManager = createPeripheralManager(bus.peripheralIo),
                uuid = serviceUuid
            )

            try {
                coroutineScope {
                    val readerOpen = async { readerTransport.open(eSenderKey) }
                    val mdocOpen = async { mdocTransport.open(eSenderKey) }
                    readerOpen.await()
                    mdocOpen.await()
                }

                val waitReader = async {
                    assertFailsWith<MdocTransportException> {
                        readerTransport.waitForMessage()
                    }
                }
                val waitMdoc = async {
                    assertFailsWith<MdocTransportException> {
                        mdocTransport.waitForMessage()
                    }
                }
                delay(20.milliseconds)
                bus.centralIo.simulateDisconnect()

                waitReader.await()
                waitMdoc.await()
                assertEquals(MdocTransport.State.FAILED, readerTransport.state.value)
                assertEquals(MdocTransport.State.FAILED, mdocTransport.state.value)
            } finally {
                mdocTransport?.close()
                readerTransport.close()
            }
        }
    }

    @Test
    fun btHciMdocTransportFactoryCreatesBleAndFallbackTransports() = runTest {
        val bus = VirtualHciBus()
        val nfcFallbackInvoked = HciAtomicInt(0)
        val fallbackFactory = object : MdocTransportFactory {
            override fun createTransport(
                connectionMethod: MdocConnectionMethod,
                role: MdocRole,
                options: MdocTransportOptions,
            ): MdocTransport {
                nfcFallbackInvoked.incrementAndGet()
                return NfcTransportMdocReader(role, options, connectionMethod as MdocConnectionMethod.Nfc)
            }
        }
        val factory = BtHciMdocTransportFactory(
            fallbackFactory = fallbackFactory,
            packetIoFactory = { bus.centralIo }
        )
        assertEquals(
            emptyList(),
            factory.createTransports(emptyList(), MdocRole.MDOC_READER, MdocTransportOptions())
        )
        val uuid = UUID.randomUUID()
        val cmCentral = MdocConnectionMethod.Ble.GattCentral(uuid = uuid)
        val t1 = factory.createTransport(
            cmCentral,
            MdocRole.MDOC_READER,
            MdocTransportOptions()
        )
        assertEquals(MdocTransport.State.IDLE, t1.state.value)
        t1.close()

        val cmPeripheral = MdocConnectionMethod.Ble.GattPeripheral(uuid = uuid)
        val t2 = factory.createTransport(
            cmPeripheral,
            MdocRole.MDOC_READER,
            MdocTransportOptions()
        )
        assertEquals(MdocTransport.State.IDLE, t2.state.value)
        t2.close()

        val dualTransports = factory.createTransports(
            connectionMethods = listOf(
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = uuid),
                cmCentral,
            ),
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(bleUseL2CAP = true),
        )
        val tDual = dualTransports.single()
        assertEquals(MdocTransport.State.IDLE, tDual.state.value)
        assertEquals(listOf<MdocConnectionMethod>(cmCentral), tDual.connectionMethods)
        tDual.advertise()
        assertEquals(
            listOf<MdocConnectionMethod>(
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = uuid, psm = 0x80),
                cmCentral,
            ),
            tDual.connectionMethods
        )
        tDual.close()

        val cmNfc = MdocConnectionMethod.Nfc(
            commandDataFieldMaxLength = 0xFF,
            responseDataFieldMaxLength = 0x100
        )
        val t3 = factory.createTransport(cmNfc, MdocRole.MDOC_READER, MdocTransportOptions())
        assertTrue(t3 is NfcTransportMdocReader)
        assertEquals(1, nfcFallbackInvoked.get())
        t3.close()
        bus.centralIo.close()
        bus.peripheralIo.close()
    }

    private class StaticTestPresentmentSource(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository
    ) : PresentmentSource(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository
    ) {
        override suspend fun resolveTrust(requester: Requester): TrustedRequesterIdentity? = null

        override suspend fun showConsentPrompt(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            presentmentData: PresentmentData,
            preselectedDocuments: List<Document>,
            onDocumentsInFocus: (documents: List<Document>) -> Unit
        ): PresentmentSelection? {
            return promptModelSilentConsent(
                requester = requester,
                trustedRequesterIdentity = trustedRequesterIdentity,
                presentmentData = presentmentData,
                preselectedDocuments = preselectedDocuments,
                onDocumentsInFocus = onDocumentsInFocus
            )
        }

        override suspend fun selectCredential(
            document: Document,
            requestedClaims: List<RequestedClaim>,
            keyAgreementPossible: List<EcCurve>,
            credential: Credential?
        ): Credential? {
            return document.getCertifiedCredentials().firstOrNull()
        }

        override suspend fun getBadges(document: Document): List<DocumentBadge> = emptyList()

        override suspend fun getFaceMatchingMode(
            credential: Credential,
            requesterIdentities: List<RequesterIdentity>
        ): FaceMatchingMode = FaceMatchingMode.NEVER
    }

    private fun getAnnexDEDeviceKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_Y.fromHex()
    )

    private fun getAnnexDEReaderKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex()
    )

    private fun getAnnexDStaticDeviceKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex()
    )

    private suspend fun createAnnexDPresentmentSource(): PresentmentSource {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder()
            .add(secureArea)
            .build()
        val documentTypeRepository = DocumentTypeRepository().apply {
            addDocumentType(DrivingLicense.getDocumentType())
        }
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {}

        val document = documentStore.createDocument(
            displayName = "Annex D mDL",
            typeDisplayName = "Driving License"
        )
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc_key_agreement",
            secureArea = secureArea,
            docType = DrivingLicense.MDL_DOCTYPE,
            createKeySettings = SoftwareCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ECDH_P256)
                .setPrivateKey(getAnnexDStaticDeviceKey())
                .build()
        )

        val annexDResponseItem = Cbor.decode(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex())
        val annexDIssuerSigned = annexDResponseItem["documents"].asArray.first()["issuerSigned"]
        val annexDMdlItems = annexDIssuerSigned["nameSpaces"][DrivingLicense.MDL_NAMESPACE].asArray
        val mandatoryItems = annexDMdlItems.filter { taggedItem ->
            val elementId = taggedItem.asTaggedEncodedCbor["elementIdentifier"].asTstr
            elementId in setOf("family_name", "issue_date", "expiry_date", "document_number")
        }
        val issuerSigned = buildCborMap {
            putCborMap("nameSpaces") {
                put(
                    DrivingLicense.MDL_NAMESPACE,
                    buildCborArray {
                        mandatoryItems.forEach { add(it) }
                    }
                )
            }
            put("issuerAuth", annexDIssuerSigned["issuerAuth"])
        }
        credential.certify(ByteString(Cbor.encode(issuerSigned)))

        return StaticTestPresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository
        )
    }

    @Test
    fun fullIso18013ProximityTransactionGatt() = runTest {
        val transcript = runFullIso18013ProximityTransaction(useL2cap = false)
        val expectedTranscript = """
            # Peripheral sends HCI_Reset (0x0c03).
            HCI-Cmd (Peripheral): 01030c00

            # Peripheral sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Peripheral): 01010c08fffffbff07f8bf3d

            # Peripheral sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Peripheral): 01012008ff1f000000000000

            # Peripheral sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Peripheral): 01022000

            # Peripheral sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Peripheral): 01052006aabbccddeecf

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral sends HCI_LE_Set_Advertising_Parameters (0x2006).
            HCI-Cmd (Peripheral): 0106200f200040000001000000000000000700

            # Peripheral sends HCI_LE_Set_Advertising_Data (0x2008).
            HCI-Cmd (Peripheral): 01082020150201061107e6733397764c6b89ce4823a10100000000000000000000000000

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1).
            HCI-Cmd (Peripheral): 010a200101

            # Central sends HCI_Reset (0x0c03).
            HCI-Cmd (Central): 01030c00

            # Central sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Central): 01010c08fffffbff07f8bf3d

            # Central sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Central): 01012008ff1f000000000000

            # Central sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Central): 01022000

            # Central sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Central): 010520061122334455c6

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1).
            HCI-Cmd (Central): 010c20020100

            # Central receives HCI_LE_Advertising_Report (subevent=0x02) from Peripheral.
            HCI-Evt (Central): 043e2102010001aabbccddeecf150201061107e6733397764c6b89ce4823a101000000d3

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Create_Connection (0x200d).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # Central receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040).
            HCI-Evt (Central): 043e13010040000001aabbccddeecf06000000f40100

            # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515).
            ATT Central->Peripheral: 020302

            # Peripheral responds with ATT_EXCHANGE_MTU_RSP (0x03, mtu=515).
            ATT Peripheral->Central: 030302

            # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 00000001-a123-48ce-896b-4c76973373e6.
            ATT Central->Peripheral: 060100ffff0028e6733397764c6b89ce4823a101000000

            # Peripheral responds with ATT_FIND_BY_TYPE_VALUE_RSP (0x07, start=0x1, end=0xb).
            ATT Peripheral->Central: 0701000b00

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x1, end=0xb, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 0801000b000328

            # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 4 characteristics:
            #   - State (00000005-a123-48ce-896b-4c76973373e6): valueHandle=0x3, props=0x14
            #   - Client2Server (00000006-a123-48ce-896b-4c76973373e6): valueHandle=0x6, props=0x04
            #   - Server2Client (00000007-a123-48ce-896b-4c76973373e6): valueHandle=0x8, props=0x10
            #   - Ident (00000008-a123-48ce-896b-4c76973373e6): valueHandle=0xb, props=0x02
            ATT Peripheral->Central: 09150200140300e6733397764c6b89ce4823a1050000000500040600e6733397764c6b89ce4823a1060000000700100800e6733397764c6b89ce4823a1070000000a00020b00e6733397764c6b89ce4823a108000000

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0xb, end=0xb, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 080b000b000328

            # Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x8, handle=0xb, error=0xa).
            ATT Peripheral->Central: 01080b000a

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x4, end=0x4) to discover State descriptors.
            ATT Central->Peripheral: 0404000400

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x4, uuid=0x2902 CCCD for State).
            ATT Peripheral->Central: 050104000229

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x9, end=0x9) to discover Server2Client descriptors.
            ATT Central->Peripheral: 0409000900

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x9, uuid=0x2902 CCCD for Server2Client).
            ATT Peripheral->Central: 050109000229

            # Central sends ATT_READ_REQ (0x0a, handle=0xb) for Ident characteristic.
            ATT Central->Peripheral: 0a0b00

            # Peripheral responds with ATT_READ_RSP (0x0b, 16 bytes) for Ident characteristic (BLEIdent=8da0b7a85919f05673054ea996bbd124).
            ATT Peripheral->Central: 0b8da0b7a85919f05673054ea996bbd124

            # Central sends ATT_WRITE_REQ (0x12, handle=0x4, value=0x0001) to enable notifications on State CCCD.
            ATT Central->Peripheral: 1204000100

            # Peripheral responds with ATT_WRITE_RSP (0x13) for State CCCD.
            ATT Peripheral->Central: 13

            # Central sends ATT_WRITE_REQ (0x12, handle=0x9, value=0x0001) to enable notifications on Server2Client CCCD.
            ATT Central->Peripheral: 1209000100

            # Peripheral responds with ATT_WRITE_RSP (0x13) for Server2Client CCCD.
            ATT Peripheral->Central: 13

            # Central writes 0x1 to State characteristic (handle=0x03) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 52030001

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral sends final Server2Client GATT notification chunk (0x00, 233 payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b).
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5'
            # }
            ATT Peripheral->Central: 1b080000a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5

            # Central sends continuation Client2Server GATT chunk (0x01, 511 payload bytes) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 52060001a1646461746159072fa3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac37a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7dab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566

            # Central sends continuation Client2Server GATT chunk (0x01, 511 payload bytes) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 52060001562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b45

            # Central sends continuation Client2Server GATT chunk (0x01, 511 payload bytes) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 520600010bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112

            # Central sends final Client2Server GATT chunk (0x00, 315 payload bytes) via ATT_WRITE_CMD (0x52).
            # {
            #   "data": h'a3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac37a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7dab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b450bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fcf5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a'
            # }
            ATT Central->Peripheral: 52060000e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fcf5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a

            # Peripheral sends final Server2Client GATT notification chunk (0x00, 9 payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b).
            # {
            #   "status": 20
            # }
            ATT Peripheral->Central: 1b080000a16673746174757314

            # Central sends HCI_Disconnect (0x0406, handle=0x0040, reason=0x13).
            HCI-Cmd (Central): 01060403400013
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    @Test
    fun fullIso18013ProximityTransactionL2cap() = runTest {
        val transcript = runFullIso18013ProximityTransaction(useL2cap = true)
        val expectedTranscript = """
            # Peripheral sends HCI_Reset (0x0c03).
            HCI-Cmd (Peripheral): 01030c00

            # Peripheral sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Peripheral): 01010c08fffffbff07f8bf3d

            # Peripheral sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Peripheral): 01012008ff1f000000000000

            # Peripheral sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Peripheral): 01022000

            # Peripheral sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Peripheral): 01052006aabbccddeecf

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral sends HCI_LE_Set_Advertising_Parameters (0x2006).
            HCI-Cmd (Peripheral): 0106200f200040000001000000000000000700

            # Peripheral sends HCI_LE_Set_Advertising_Data (0x2008).
            HCI-Cmd (Peripheral): 01082020150201061107e6733397764c6b89ce4823a10100000000000000000000000000

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1).
            HCI-Cmd (Peripheral): 010a200101

            # Central sends HCI_Reset (0x0c03).
            HCI-Cmd (Central): 01030c00

            # Central sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Central): 01010c08fffffbff07f8bf3d

            # Central sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Central): 01012008ff1f000000000000

            # Central sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Central): 01022000

            # Central sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Central): 010520061122334455c6

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1).
            HCI-Cmd (Central): 010c20020100

            # Central receives HCI_LE_Advertising_Report (subevent=0x02) from Peripheral.
            HCI-Evt (Central): 043e2102010001aabbccddeecf150201061107e6733397764c6b89ce4823a101000000d3

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Create_Connection (0x200d).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # Central receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040).
            HCI-Evt (Central): 043e13010040000001aabbccddeecf06000000f40100

            # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515).
            ATT Central->Peripheral: 020302

            # Peripheral responds with ATT_EXCHANGE_MTU_RSP (0x03, mtu=515).
            ATT Peripheral->Central: 030302

            # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 00000001-a123-48ce-896b-4c76973373e6.
            ATT Central->Peripheral: 060100ffff0028e6733397764c6b89ce4823a101000000

            # Peripheral responds with ATT_FIND_BY_TYPE_VALUE_RSP (0x07, start=0x1, end=0xd).
            ATT Peripheral->Central: 0701000d00

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x1, end=0xd, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 0801000d000328

            # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 5 characteristics:
            #   - State (00000005-a123-48ce-896b-4c76973373e6): valueHandle=0x3, props=0x14
            #   - Client2Server (00000006-a123-48ce-896b-4c76973373e6): valueHandle=0x6, props=0x04
            #   - Server2Client (00000007-a123-48ce-896b-4c76973373e6): valueHandle=0x8, props=0x10
            #   - Ident (00000008-a123-48ce-896b-4c76973373e6): valueHandle=0xb, props=0x02
            #   - L2CAP (0000000b-a123-48ce-896b-4c76973373e6): valueHandle=0xd, props=0x02
            ATT Peripheral->Central: 09150200140300e6733397764c6b89ce4823a1050000000500040600e6733397764c6b89ce4823a1060000000700100800e6733397764c6b89ce4823a1070000000a00020b00e6733397764c6b89ce4823a1080000000c00020d00e6733397764c6b89ce4823a10b000000

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0xd, end=0xd, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 080d000d000328

            # Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x8, handle=0xd, error=0xa).
            ATT Peripheral->Central: 01080d000a

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x4, end=0x4) to discover State descriptors.
            ATT Central->Peripheral: 0404000400

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x4, uuid=0x2902 CCCD for State).
            ATT Peripheral->Central: 050104000229

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x9, end=0x9) to discover Server2Client descriptors.
            ATT Central->Peripheral: 0409000900

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x9, uuid=0x2902 CCCD for Server2Client).
            ATT Peripheral->Central: 050109000229

            # Central sends ATT_READ_REQ (0x0a, handle=0xd) for L2CAP characteristic.
            ATT Central->Peripheral: 0a0d00

            # Peripheral responds with ATT_READ_RSP (0x0b, 4 bytes) for L2CAP characteristic (psm=0x80).
            ATT Peripheral->Central: 0b00000080

            # Central sends ATT_READ_REQ (0x0a, handle=0xb) for Ident characteristic.
            ATT Central->Peripheral: 0a0b00

            # Peripheral responds with ATT_READ_RSP (0x0b, 16 bytes) for Ident characteristic (BLEIdent=8da0b7a85919f05673054ea996bbd124).
            ATT Peripheral->Central: 0b8da0b7a85919f05673054ea996bbd124

            # Central sends L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14, psm=0x80, scid=0x40).
            L2CAP-Sig Central->Peripheral: 14010a0080004000fffff7000001

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral responds with L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, dcid=0x40, result=0x0).
            L2CAP-Sig Peripheral->Central: 15010a004000fffff70000010000

            # Peripheral sends L2CAP CoC K-frame completing 233-byte ISO 18013-5 message.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5'
            # }
            L2CAP-CoC Peripheral->Central: ed00000000e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 3c0700000738a1646461746159072fa3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac3

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 7a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7d

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: ab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b450bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fc

            # Central sends L2CAP CoC K-frame completing 1848-byte ISO 18013-5 message.
            # {
            #   "data": h'a3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac37a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7dab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b450bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fcf5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a'
            # }
            L2CAP-CoC Central->Peripheral: f5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a

            # Peripheral sends L2CAP CoC K-frame completing 9-byte ISO 18013-5 message.
            # {
            #   "status": 20
            # }
            L2CAP-CoC Peripheral->Central: 0d0000000009a16673746174757314

            # Central sends HCI_Disconnect (0x0406, handle=0x0040, reason=0x13).
            HCI-Cmd (Central): 01060403400013
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    @Test
    fun fullIso18013ProximityTransactionL2capInEngagement() = runTest {
        val transcript = runFullIso18013ProximityTransaction(
            useL2cap = true,
            useL2capInEngagement = true
        )
        val expectedTranscript = """
            # Peripheral sends HCI_Reset (0x0c03).
            HCI-Cmd (Peripheral): 01030c00

            # Peripheral sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Peripheral): 01010c08fffffbff07f8bf3d

            # Peripheral sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Peripheral): 01012008ff1f000000000000

            # Peripheral sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Peripheral): 01022000

            # Peripheral sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Peripheral): 01052006aabbccddeecf

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral sends HCI_LE_Set_Advertising_Parameters (0x2006).
            HCI-Cmd (Peripheral): 0106200f200040000001000000000000000700

            # Peripheral sends HCI_LE_Set_Advertising_Data (0x2008).
            HCI-Cmd (Peripheral): 01082020150201061107e6733397764c6b89ce4823a10100000000000000000000000000

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1).
            HCI-Cmd (Peripheral): 010a200101

            # Central sends HCI_Reset (0x0c03).
            HCI-Cmd (Central): 01030c00

            # Central sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Central): 01010c08fffffbff07f8bf3d

            # Central sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Central): 01012008ff1f000000000000

            # Central sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Central): 01022000

            # Central sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Central): 010520061122334455c6

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1).
            HCI-Cmd (Central): 010c20020100

            # Central receives HCI_LE_Advertising_Report (subevent=0x02) from Peripheral.
            HCI-Evt (Central): 043e2102010001aabbccddeecf150201061107e6733397764c6b89ce4823a101000000d3

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Create_Connection (0x200d).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # Central receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040).
            HCI-Evt (Central): 043e13010040000001aabbccddeecf06000000f40100

            # Central sends L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14, psm=0x80, scid=0x40).
            L2CAP-Sig Central->Peripheral: 14010a0080004000fffff7000001

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral responds with L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, dcid=0x40, result=0x0).
            L2CAP-Sig Peripheral->Central: 15010a004000fffff70000010000

            # Peripheral sends L2CAP CoC K-frame completing 233-byte ISO 18013-5 message.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'3d711b134434dfc655490529afab9353c43c39d7a209f34dc283db9eefb156762299d5cda3acff92f355b5a3c04962d45d6eaa64a5e23b13cf77033909fc00ea7ba77ab2d6c5734fe1c6c3d5f7ee30e6aab529f7e9e17bd76d28d428c51ce916cca8954925ca5d3f4263b0c2ab407de87a0cc135e08e38e1521c8cfbe755534aef40808337eb6c'
            # }
            L2CAP-CoC Peripheral->Central: ed00000000e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158873d711b134434dfc655490529afab9353c43c39d7a209f34dc283db9eefb156762299d5cda3acff92f355b5a3c04962d45d6eaa64a5e23b13cf77033909fc00ea7ba77ab2d6c5734fe1c6c3d5f7ee30e6aab529f7e9e17bd76d28d428c51ce916cca8954925ca5d3f4263b0c2ab407de87a0cc135e08e38e1521c8cfbe755534aef40808337eb6c

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 3c0700000738a1646461746159072f574b3adb1dd1e6dbd2045703b71cc11484f79d8fca692a4bc82a1d10e0a030112a9a60042571b96461c871dde3b92023a728294ef42db9adcab1cb36cf3d4cbd57ab1258a88819e2bb6ab87b5c91ede4f0b276a2cb862a298446a7bd22d4901d45eafd03414359a91646998bb456a20ac9cfca419f3001153b5367a544000eed7d4edd63449bd6d1ad1c6b5b0c3eb24bcd53a60c9e5b21020328058c4e51cef0bc59cf354097321d1bef5ed5828471b8d2dcd4f8684e3309ec094ab63f0eb01a5ba25e2b06078f0989d500066c8e89a514bdffb83131a6df37cf436f23b3d9bba692df525fa4d961

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: dce8a30e05e2449e9129247d7133c54d545c1722f09fd62bd354ede2036f623c946a868c0d07ce6f1d3a2a30e600c5f8a0ffc2106af86443905e56885be7b7301fa39509c2000cdbe2ca9b9dc499fe416f8d7c49772df42bc2bbe3de573419938d16f9a2bebd280deab17f6659f49e014231889768acdb7d914f4ec66e1d74e0cb6e3b39c1f030c4239ce2498feeba315f0027807016bf5a50c90fad480b5d2597bcb4fd461c292dedf04cf3609f9373a35b1f0bc60ea3248df7d3b4ed43d4441c1bf7f6412b7b3130bf90314a69b3cb195affe317b526ac5a620a86e4764165c22f397a74f179bfa8e850dac7ec35ab75a154977d8629

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 3310d88cdaf34f783a24bba1c55317a6a1f585816d7831065fe70ae9dae3deffc3f4aab0dba8b7035ef7208e051d4f98f6cdc8f4a590cfb629b107dbaf155b52d3aef795b8eac62ab0d29d0350d3c1c5c7f422702c68d9be1e01374b8f051c5ee58292e79e4111715a45eb796165493ec26b8341adf3a034e0f67fb0d74298fe2c108fc7aabc92e64afd1918c557eb8cefe72a20bda3b277fd6996da02264f021de06ecbb6e4a70696ebb7e9efb91540b59c1bbf492af5bef4626d3bc8a82d962d595c1935dd40cca805a6f4a3130870e2037ff8891a2d773d977aed40ec9defc0e6016ce82f3f26fe4d2535bffd4f43487dbb72cccca8

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: c155bac20cf69a943e17160fda40349b23fc251b972874b3e8b2a07642da9e4a3fd0fca85e690834a5e891286663dafeeab258816cfb5b5e7b200e8bbf10d0a2d2853f2d548823e690df8a0de7a3fbf536a9eebfc264718aa5959d200887665216654e52d05ed2efc46a065b997106d83cf26c90412a895a44a021c872dad1fbdbfa3cc5fe87f80b591c272e55e084fc1016e4b1d3bf36ed8f5280b221e0c1e3481f3be8a6bb660c15438154616c0f52a1c525d1129a7d355d17d57693a234801957ffdb30544e8923c8d6ebf87da22aeb47594a0256ca7ecdc751527f66b57e48ca760b7ff67e1e3e0bd11737bf3a7e4274e76e3670e5

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 9427683d89e84de9ac1d6525da79320066a2e0cd6bca1dd181db4bf6cd090b86af9d80934b0be9a9f3598d36f721834f06abb67368abfd77c78444405d8908a2a4f3ff4f33875c4327e5dec60b6c297aa9907bf993f344fc71a8b5681e7408dc10af7182ed3d92b2c547ba2c1230d447b6328e168cf9cec81f5bd2c7bd6ed7bcb5e05cf947c91edcf9657d1853fa6fbcbba795c7b4126c246d1c8dfd6dacb24caed9ef9d4e4549c0f000a4c9bd0e59b7eb8904417e0b8db4ed9cf25ff5c28ccd071e84ee7747f9788c4c99e24e41489fa1df6bf89f65bd61f46e1d7dff8789b89992ab57229e80a72721ebd79de1d9bb28199bac1e1831

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 722e2cdf9cd3a6d45b9eda57572282767f89db1d51bce38f3df91e335dc02c556088a3da4ab3f94b589d2525648c44bc92ecfb6389ca48e449e64fcf614f973bd9ae2ab93a2b32aa976c51bc6848f330d3070d742fcbb10d90a07a7fb3669ae4df79e633313209c617895261fb230f734ef090788cb25b771a21df6979c4b23db7c525d321cf73ebbf8dbff4120fba82abf8e5fd6072cec17e579c9f7a89e65437fe2b070026f62201fab0dd3e0ae2fb67d762b8e7d99bef3a9106cbe30f28168c26b044e11771b7c80babf0234ff5fb1ad89d4029add59055667a6b68556b17add91db8acd2c0e9bd44fe5c78bdf93718992b3c3b2226

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 6974fbc07680254f0018a8918593701c63a345a6f146e10cd43746b049544c32010eae4a2e05ede0cadd702925507e9dea534f9d7d92ff31bd5c7dfb414acd2ea2dd2eeee0c231dd9b11dbc6326bb1086133bfbe715cb9abea6a51d243ff805ebf263b6c9c446284f7b348429d13bd5bedb27c073de0f08ef5f32c4309a997f18d289563d5459f8a7994b32fb23f09554415c659c78dbd7a01c9cb84a6297685f43c8a619803b43b7562facc70f418304d465cf65ab77509082e4967616a3e956a0c1f92c3cc20aeb580d04c9114cac67aa966fb8cca5c2de46f2127a9b52c83cef179c27f235d489824d1250228ee5b6c969ed84c6499

            # Central sends L2CAP CoC K-frame completing 1848-byte ISO 18013-5 message.
            # {
            #   "data": h'574b3adb1dd1e6dbd2045703b71cc11484f79d8fca692a4bc82a1d10e0a030112a9a60042571b96461c871dde3b92023a728294ef42db9adcab1cb36cf3d4cbd57ab1258a88819e2bb6ab87b5c91ede4f0b276a2cb862a298446a7bd22d4901d45eafd03414359a91646998bb456a20ac9cfca419f3001153b5367a544000eed7d4edd63449bd6d1ad1c6b5b0c3eb24bcd53a60c9e5b21020328058c4e51cef0bc59cf354097321d1bef5ed5828471b8d2dcd4f8684e3309ec094ab63f0eb01a5ba25e2b06078f0989d500066c8e89a514bdffb83131a6df37cf436f23b3d9bba692df525fa4d961dce8a30e05e2449e9129247d7133c54d545c1722f09fd62bd354ede2036f623c946a868c0d07ce6f1d3a2a30e600c5f8a0ffc2106af86443905e56885be7b7301fa39509c2000cdbe2ca9b9dc499fe416f8d7c49772df42bc2bbe3de573419938d16f9a2bebd280deab17f6659f49e014231889768acdb7d914f4ec66e1d74e0cb6e3b39c1f030c4239ce2498feeba315f0027807016bf5a50c90fad480b5d2597bcb4fd461c292dedf04cf3609f9373a35b1f0bc60ea3248df7d3b4ed43d4441c1bf7f6412b7b3130bf90314a69b3cb195affe317b526ac5a620a86e4764165c22f397a74f179bfa8e850dac7ec35ab75a154977d86293310d88cdaf34f783a24bba1c55317a6a1f585816d7831065fe70ae9dae3deffc3f4aab0dba8b7035ef7208e051d4f98f6cdc8f4a590cfb629b107dbaf155b52d3aef795b8eac62ab0d29d0350d3c1c5c7f422702c68d9be1e01374b8f051c5ee58292e79e4111715a45eb796165493ec26b8341adf3a034e0f67fb0d74298fe2c108fc7aabc92e64afd1918c557eb8cefe72a20bda3b277fd6996da02264f021de06ecbb6e4a70696ebb7e9efb91540b59c1bbf492af5bef4626d3bc8a82d962d595c1935dd40cca805a6f4a3130870e2037ff8891a2d773d977aed40ec9defc0e6016ce82f3f26fe4d2535bffd4f43487dbb72cccca8c155bac20cf69a943e17160fda40349b23fc251b972874b3e8b2a07642da9e4a3fd0fca85e690834a5e891286663dafeeab258816cfb5b5e7b200e8bbf10d0a2d2853f2d548823e690df8a0de7a3fbf536a9eebfc264718aa5959d200887665216654e52d05ed2efc46a065b997106d83cf26c90412a895a44a021c872dad1fbdbfa3cc5fe87f80b591c272e55e084fc1016e4b1d3bf36ed8f5280b221e0c1e3481f3be8a6bb660c15438154616c0f52a1c525d1129a7d355d17d57693a234801957ffdb30544e8923c8d6ebf87da22aeb47594a0256ca7ecdc751527f66b57e48ca760b7ff67e1e3e0bd11737bf3a7e4274e76e3670e59427683d89e84de9ac1d6525da79320066a2e0cd6bca1dd181db4bf6cd090b86af9d80934b0be9a9f3598d36f721834f06abb67368abfd77c78444405d8908a2a4f3ff4f33875c4327e5dec60b6c297aa9907bf993f344fc71a8b5681e7408dc10af7182ed3d92b2c547ba2c1230d447b6328e168cf9cec81f5bd2c7bd6ed7bcb5e05cf947c91edcf9657d1853fa6fbcbba795c7b4126c246d1c8dfd6dacb24caed9ef9d4e4549c0f000a4c9bd0e59b7eb8904417e0b8db4ed9cf25ff5c28ccd071e84ee7747f9788c4c99e24e41489fa1df6bf89f65bd61f46e1d7dff8789b89992ab57229e80a72721ebd79de1d9bb28199bac1e1831722e2cdf9cd3a6d45b9eda57572282767f89db1d51bce38f3df91e335dc02c556088a3da4ab3f94b589d2525648c44bc92ecfb6389ca48e449e64fcf614f973bd9ae2ab93a2b32aa976c51bc6848f330d3070d742fcbb10d90a07a7fb3669ae4df79e633313209c617895261fb230f734ef090788cb25b771a21df6979c4b23db7c525d321cf73ebbf8dbff4120fba82abf8e5fd6072cec17e579c9f7a89e65437fe2b070026f62201fab0dd3e0ae2fb67d762b8e7d99bef3a9106cbe30f28168c26b044e11771b7c80babf0234ff5fb1ad89d4029add59055667a6b68556b17add91db8acd2c0e9bd44fe5c78bdf93718992b3c3b22266974fbc07680254f0018a8918593701c63a345a6f146e10cd43746b049544c32010eae4a2e05ede0cadd702925507e9dea534f9d7d92ff31bd5c7dfb414acd2ea2dd2eeee0c231dd9b11dbc6326bb1086133bfbe715cb9abea6a51d243ff805ebf263b6c9c446284f7b348429d13bd5bedb27c073de0f08ef5f32c4309a997f18d289563d5459f8a7994b32fb23f09554415c659c78dbd7a01c9cb84a6297685f43c8a619803b43b7562facc70f418304d465cf65ab77509082e4967616a3e956a0c1f92c3cc20aeb580d04c9114cac67aa966fb8cca5c2de46f2127a9b52c83cef179c27f235d489824d1250228ee5b6c969ed84c6499ce8d4e74ea842a4ba0523ee2e148e47475932e6078b28564ae5d687dad34c5593caee9d0b1ba41ba448117221e8f1190b6fc077fb58c6411c3f04c58e7f40fca5ca6d12655680100f56ba49d5112fa3973826a0ee73e70a7399690ca3e510c6d7b6810b41c34ade17ff678523cefa095a80d6136fc3f4e01cba222f6ec'
            # }
            L2CAP-CoC Central->Peripheral: ce8d4e74ea842a4ba0523ee2e148e47475932e6078b28564ae5d687dad34c5593caee9d0b1ba41ba448117221e8f1190b6fc077fb58c6411c3f04c58e7f40fca5ca6d12655680100f56ba49d5112fa3973826a0ee73e70a7399690ca3e510c6d7b6810b41c34ade17ff678523cefa095a80d6136fc3f4e01cba222f6ec

            # Peripheral sends L2CAP CoC K-frame completing 9-byte ISO 18013-5 message.
            # {
            #   "status": 20
            # }
            L2CAP-CoC Peripheral->Central: 0d0000000009a16673746174757314

            # Central sends HCI_Disconnect (0x0406, handle=0x0040, reason=0x13).
            HCI-Cmd (Central): 01060403400013
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    @Test
    fun fullIso18013ProximityTransactionGattWithRetries() = runTest {
        val transcript = runFullIso18013ProximityTransaction(
            useL2cap = false,
            faultConfig = FaultConfig(
                dropScanAttempts = 2,
                failConnectionAttempts = 2,
                failServiceDiscoveryAttempts = 2
            )
        )
        val expectedTranscript = """
            # Peripheral sends HCI_Reset (0x0c03).
            HCI-Cmd (Peripheral): 01030c00

            # Peripheral sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Peripheral): 01010c08fffffbff07f8bf3d

            # Peripheral sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Peripheral): 01012008ff1f000000000000

            # Peripheral sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Peripheral): 01022000

            # Peripheral sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Peripheral): 01052006aabbccddeecf

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral sends HCI_LE_Set_Advertising_Parameters (0x2006).
            HCI-Cmd (Peripheral): 0106200f200040000001000000000000000700

            # Peripheral sends HCI_LE_Set_Advertising_Data (0x2008).
            HCI-Cmd (Peripheral): 01082020150201061107e6733397764c6b89ce4823a10100000000000000000000000000

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1).
            HCI-Cmd (Peripheral): 010a200101

            # Central sends HCI_Reset (0x0c03).
            HCI-Cmd (Central): 01030c00

            # Central sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Central): 01010c08fffffbff07f8bf3d

            # Central sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Central): 01012008ff1f000000000000

            # Central sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Central): 01022000

            # Central sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Central): 010520061122334455c6

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1).
            HCI-Cmd (Central): 010c20020100

            # ERROR: Scan timed out with no advertising report received; Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1) (scan restart #1).
            HCI-Cmd (Central): 010c20020100

            # ERROR: Scan timed out with no advertising report received; Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1) (scan restart #2).
            HCI-Cmd (Central): 010c20020100

            # Central receives HCI_LE_Advertising_Report (subevent=0x02) from Peripheral.
            HCI-Evt (Central): 043e2102010001aabbccddeecf150201061107e6733397764c6b89ce4823a101000000d3

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Create_Connection (0x200d).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # ERROR: Central receives HCI_LE_Connection_Complete (subevent=0x01, status=0x3e Connection Failed to be Established).
            HCI-Evt (Central): 043e13013e00000001aabbccddeecf00000000000000

            # Central sends HCI_LE_Create_Connection (0x200d) (GATT connect retry #1).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # ERROR: Central receives HCI_LE_Connection_Complete (subevent=0x01, status=0x3e Connection Failed to be Established).
            HCI-Evt (Central): 043e13013e00000001aabbccddeecf00000000000000

            # Central sends HCI_LE_Create_Connection (0x200d) (GATT connect retry #2).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # Central receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040).
            HCI-Evt (Central): 043e13010040000001aabbccddeecf06000000f40100

            # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515).
            ATT Central->Peripheral: 020302

            # Peripheral responds with ATT_EXCHANGE_MTU_RSP (0x03, mtu=515).
            ATT Peripheral->Central: 030302

            # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 00000001-a123-48ce-896b-4c76973373e6.
            ATT Central->Peripheral: 060100ffff0028e6733397764c6b89ce4823a101000000

            # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x6, handle=0x1, error=0xa Attribute Not Found).
            ATT Peripheral->Central: 010601000a

            # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 00000001-a123-48ce-896b-4c76973373e6 (service discovery retry #1).
            ATT Central->Peripheral: 060100ffff0028e6733397764c6b89ce4823a101000000

            # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x6, handle=0x1, error=0xa Attribute Not Found).
            ATT Peripheral->Central: 010601000a

            # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 00000001-a123-48ce-896b-4c76973373e6 (service discovery retry #2).
            ATT Central->Peripheral: 060100ffff0028e6733397764c6b89ce4823a101000000

            # Peripheral responds with ATT_FIND_BY_TYPE_VALUE_RSP (0x07, start=0x1, end=0xb).
            ATT Peripheral->Central: 0701000b00

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x1, end=0xb, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 0801000b000328

            # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 4 characteristics:
            #   - State (00000005-a123-48ce-896b-4c76973373e6): valueHandle=0x3, props=0x14
            #   - Client2Server (00000006-a123-48ce-896b-4c76973373e6): valueHandle=0x6, props=0x04
            #   - Server2Client (00000007-a123-48ce-896b-4c76973373e6): valueHandle=0x8, props=0x10
            #   - Ident (00000008-a123-48ce-896b-4c76973373e6): valueHandle=0xb, props=0x02
            ATT Peripheral->Central: 09150200140300e6733397764c6b89ce4823a1050000000500040600e6733397764c6b89ce4823a1060000000700100800e6733397764c6b89ce4823a1070000000a00020b00e6733397764c6b89ce4823a108000000

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0xb, end=0xb, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 080b000b000328

            # Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x8, handle=0xb, error=0xa).
            ATT Peripheral->Central: 01080b000a

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x4, end=0x4) to discover State descriptors.
            ATT Central->Peripheral: 0404000400

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x4, uuid=0x2902 CCCD for State).
            ATT Peripheral->Central: 050104000229

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x9, end=0x9) to discover Server2Client descriptors.
            ATT Central->Peripheral: 0409000900

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x9, uuid=0x2902 CCCD for Server2Client).
            ATT Peripheral->Central: 050109000229

            # Central sends ATT_READ_REQ (0x0a, handle=0xb) for Ident characteristic.
            ATT Central->Peripheral: 0a0b00

            # Peripheral responds with ATT_READ_RSP (0x0b, 16 bytes) for Ident characteristic (BLEIdent=8da0b7a85919f05673054ea996bbd124).
            ATT Peripheral->Central: 0b8da0b7a85919f05673054ea996bbd124

            # Central sends ATT_WRITE_REQ (0x12, handle=0x4, value=0x0001) to enable notifications on State CCCD.
            ATT Central->Peripheral: 1204000100

            # Peripheral responds with ATT_WRITE_RSP (0x13) for State CCCD.
            ATT Peripheral->Central: 13

            # Central sends ATT_WRITE_REQ (0x12, handle=0x9, value=0x0001) to enable notifications on Server2Client CCCD.
            ATT Central->Peripheral: 1209000100

            # Peripheral responds with ATT_WRITE_RSP (0x13) for Server2Client CCCD.
            ATT Peripheral->Central: 13

            # Central writes 0x1 to State characteristic (handle=0x03) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 52030001

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral sends final Server2Client GATT notification chunk (0x00, 233 payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b).
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5'
            # }
            ATT Peripheral->Central: 1b080000a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5

            # Central sends continuation Client2Server GATT chunk (0x01, 511 payload bytes) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 52060001a1646461746159072fa3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac37a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7dab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566

            # Central sends continuation Client2Server GATT chunk (0x01, 511 payload bytes) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 52060001562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b45

            # Central sends continuation Client2Server GATT chunk (0x01, 511 payload bytes) via ATT_WRITE_CMD (0x52).
            ATT Central->Peripheral: 520600010bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112

            # Central sends final Client2Server GATT chunk (0x00, 315 payload bytes) via ATT_WRITE_CMD (0x52).
            # {
            #   "data": h'a3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac37a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7dab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b450bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fcf5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a'
            # }
            ATT Central->Peripheral: 52060000e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fcf5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a

            # Peripheral sends final Server2Client GATT notification chunk (0x00, 9 payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b).
            # {
            #   "status": 20
            # }
            ATT Peripheral->Central: 1b080000a16673746174757314

            # Central sends HCI_Disconnect (0x0406, handle=0x0040, reason=0x13).
            HCI-Cmd (Central): 01060403400013
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    @Test
    fun fullIso18013ProximityTransactionL2capWithRetries() = runTest {
        val transcript = runFullIso18013ProximityTransaction(
            useL2cap = true,
            faultConfig = FaultConfig(
                dropScanAttempts = 1,
                failConnectionAttempts = 1,
                failServiceDiscoveryAttempts = 1,
                failL2capConnectAttempts = 2
            )
        )
        val expectedTranscript = """
            # Peripheral sends HCI_Reset (0x0c03).
            HCI-Cmd (Peripheral): 01030c00

            # Peripheral sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Peripheral): 01010c08fffffbff07f8bf3d

            # Peripheral sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Peripheral): 01012008ff1f000000000000

            # Peripheral sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Peripheral): 01022000

            # Peripheral sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Peripheral): 01052006aabbccddeecf

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral sends HCI_LE_Set_Advertising_Parameters (0x2006).
            HCI-Cmd (Peripheral): 0106200f200040000001000000000000000700

            # Peripheral sends HCI_LE_Set_Advertising_Data (0x2008).
            HCI-Cmd (Peripheral): 01082020150201061107e6733397764c6b89ce4823a10100000000000000000000000000

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1).
            HCI-Cmd (Peripheral): 010a200101

            # Central sends HCI_Reset (0x0c03).
            HCI-Cmd (Central): 01030c00

            # Central sends HCI_Set_Event_Mask (0x0c01).
            HCI-Cmd (Central): 01010c08fffffbff07f8bf3d

            # Central sends HCI_LE_Set_Event_Mask (0x2001).
            HCI-Cmd (Central): 01012008ff1f000000000000

            # Central sends HCI_LE_Read_Buffer_Size (0x2002).
            HCI-Cmd (Central): 01022000

            # Central sends HCI_LE_Set_Random_Address (0x2005).
            HCI-Cmd (Central): 010520061122334455c6

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1).
            HCI-Cmd (Central): 010c20020100

            # ERROR: Scan timed out with no advertising report received; Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
            HCI-Cmd (Central): 010b200701600030000100

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1) (scan restart #1).
            HCI-Cmd (Central): 010c20020100

            # Central receives HCI_LE_Advertising_Report (subevent=0x02) from Peripheral.
            HCI-Evt (Central): 043e2102010001aabbccddeecf150201061107e6733397764c6b89ce4823a101000000d3

            # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
            HCI-Cmd (Central): 010c20020000

            # Central sends HCI_LE_Create_Connection (0x200d).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # ERROR: Central receives HCI_LE_Connection_Complete (subevent=0x01, status=0x3e Connection Failed to be Established).
            HCI-Evt (Central): 043e13013e00000001aabbccddeecf00000000000000

            # Central sends HCI_LE_Create_Connection (0x200d) (GATT connect retry #1).
            HCI-Cmd (Central): 010d2019600030000001aabbccddeecf0106000c000000f40100000000

            # Central receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040).
            HCI-Evt (Central): 043e13010040000001aabbccddeecf06000000f40100

            # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515).
            ATT Central->Peripheral: 020302

            # Peripheral responds with ATT_EXCHANGE_MTU_RSP (0x03, mtu=515).
            ATT Peripheral->Central: 030302

            # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 00000001-a123-48ce-896b-4c76973373e6.
            ATT Central->Peripheral: 060100ffff0028e6733397764c6b89ce4823a101000000

            # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x6, handle=0x1, error=0xa Attribute Not Found).
            ATT Peripheral->Central: 010601000a

            # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 00000001-a123-48ce-896b-4c76973373e6 (service discovery retry #1).
            ATT Central->Peripheral: 060100ffff0028e6733397764c6b89ce4823a101000000

            # Peripheral responds with ATT_FIND_BY_TYPE_VALUE_RSP (0x07, start=0x1, end=0xd).
            ATT Peripheral->Central: 0701000d00

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x1, end=0xd, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 0801000d000328

            # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 5 characteristics:
            #   - State (00000005-a123-48ce-896b-4c76973373e6): valueHandle=0x3, props=0x14
            #   - Client2Server (00000006-a123-48ce-896b-4c76973373e6): valueHandle=0x6, props=0x04
            #   - Server2Client (00000007-a123-48ce-896b-4c76973373e6): valueHandle=0x8, props=0x10
            #   - Ident (00000008-a123-48ce-896b-4c76973373e6): valueHandle=0xb, props=0x02
            #   - L2CAP (0000000b-a123-48ce-896b-4c76973373e6): valueHandle=0xd, props=0x02
            ATT Peripheral->Central: 09150200140300e6733397764c6b89ce4823a1050000000500040600e6733397764c6b89ce4823a1060000000700100800e6733397764c6b89ce4823a1070000000a00020b00e6733397764c6b89ce4823a1080000000c00020d00e6733397764c6b89ce4823a10b000000

            # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0xd, end=0xd, type=0x2803 Characteristic Declaration).
            ATT Central->Peripheral: 080d000d000328

            # Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x8, handle=0xd, error=0xa).
            ATT Peripheral->Central: 01080d000a

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x4, end=0x4) to discover State descriptors.
            ATT Central->Peripheral: 0404000400

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x4, uuid=0x2902 CCCD for State).
            ATT Peripheral->Central: 050104000229

            # Central sends ATT_FIND_INFO_REQ (0x04, start=0x9, end=0x9) to discover Server2Client descriptors.
            ATT Central->Peripheral: 0409000900

            # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x9, uuid=0x2902 CCCD for Server2Client).
            ATT Peripheral->Central: 050109000229

            # Central sends ATT_READ_REQ (0x0a, handle=0xd) for L2CAP characteristic.
            ATT Central->Peripheral: 0a0d00

            # Peripheral responds with ATT_READ_RSP (0x0b, 4 bytes) for L2CAP characteristic (psm=0x80).
            ATT Peripheral->Central: 0b00000080

            # Central sends ATT_READ_REQ (0x0a, handle=0xb) for Ident characteristic.
            ATT Central->Peripheral: 0a0b00

            # Peripheral responds with ATT_READ_RSP (0x0b, 16 bytes) for Ident characteristic (BLEIdent=8da0b7a85919f05673054ea996bbd124).
            ATT Peripheral->Central: 0b8da0b7a85919f05673054ea996bbd124

            # Central sends L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14, psm=0x80, scid=0x40).
            L2CAP-Sig Central->Peripheral: 14010a0080004000fffff7000001

            # ERROR: Peripheral responds with L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, dcid=0x0, result=0x4 No resources available).
            L2CAP-Sig Peripheral->Central: 15010a0000000000000000000400

            # Central sends L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14, psm=0x80, scid=0x40) (L2CAP connect retry #1).
            L2CAP-Sig Central->Peripheral: 14020a0080004000fffff7000001

            # ERROR: Peripheral responds with L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, dcid=0x0, result=0x4 No resources available).
            L2CAP-Sig Peripheral->Central: 15020a0000000000000000000400

            # Central sends L2CAP_LE_CREDIT_BASED_CONNECTION_REQ (0x14, psm=0x80, scid=0x40) (L2CAP connect retry #2).
            L2CAP-Sig Central->Peripheral: 14030a0080004000fffff7000001

            # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
            HCI-Cmd (Peripheral): 010a200100

            # Peripheral responds with L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, dcid=0x40, result=0x0).
            L2CAP-Sig Peripheral->Central: 15030a004000fffff70000010000

            # Peripheral sends L2CAP CoC K-frame completing 233-byte ISO 18013-5 message.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5'
            # }
            L2CAP-CoC Peripheral->Central: ed00000000e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887f6792fc6288253a73267d1e2ec3fbfcfc5ce0fedb6f41e90af70cb225aaa6e902510695bba800663ecff60e7bce138d897832696780285aca91a60eadecaffefa45c0eaa0b946511e3b31e1f7eda7fb94595144f435ea09ee0a44678b11735b13e05621bf2437f7204140f03b1d7e21b54b25402954967e1ac365f38469b36142c57d2c60fd8c5

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 3c0700000738a1646461746159072fa3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac3

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 7a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7d

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: ab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: 350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b450bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265

            # Central sends L2CAP CoC K-frame (247 bytes).
            L2CAP-CoC Central->Peripheral: cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fc

            # Central sends L2CAP CoC K-frame completing 1848-byte ISO 18013-5 message.
            # {
            #   "data": h'a3fe254fae9747918bd420423bad6a0b2030fec5b503022cc6edf25051322af88e759845cd09df2a0a486aa51f1efba3a44dc63075cff6c34ece7d050628174ef269da41b12ea32c4f5b379c15df92850651a4d9129297fb7fc6039f201d53d5e055a0300ba4a3786e4f97d9f62606763bff8c5823320658885385ce44602da71d199822b32339aa318ed12ff38e98c2eaa2be2b284633035d7fe142bcdb33058318a0321b88c2b345eb55c23d120eae9653044762c559a0a1d62d00f066da176d95a97d12031657b108fcd8aaf844655a5374421521ac7c9d9d743edf2519f6468996184effeac37a474ce9593128e7fd5b181b1c70e6e8cd35bf645f8839eddef5d7224da99ef93b5d869d1d86411477ea537239ee65529fe5af5390480b05a1f439611d07de894673bdba0be80fa38326d87385516b71080871bf5b867af0e4893ff4d804b20c2a237a78fc2acc3fba2847ad4ab6949d791f5ca75ceb2a6d8984b026cec326a010a6d6393ca468c8f428451ca65ed66d04eaf4da2ca09bbb8dab474c9c8023d9ab8316e3d5acb9b3bfe259b640f534cf19baf4e2a5a14df4813dec52fd7d294187629da2ca18cde3513793445849356a17da2cee7fa81058ddcbdf672409998f705649039d691a2f0d05440e99e9703ea120c963dfdd7dab72d8de738725a7c4d5f52a72fa8a294cd1ba9cc52566562db76992f1eca5c3ff6b8a19d6dd57f1c2ab85ef8cef9fbe4790b7b714988970dded36f9b27057cee189acdc3f98f4bc595bbc73bc89138ea484db06cc21f520882cff23e323ff8aff39704aaf236b87ef533a5408dc3aef1ce274c0f29cdd2bfb23441fb47d810380738dbc59f8b0dba317e3f663b78c95654f8379f84a42e57fbd7e221c39b863cb3c771f1fa6d61dc2a08f577f9bc8f76ccafbec3a8429c1d75a8d294f7574541b607c82c72b048114e4225701539c232890ff51875eddc3ec3f523512960b2bc600c05a56369ae3b264fc5000844625b76d1e837655f5bf09a9c7993c9bf6f224e1bb2d796d29e0e3570e9fb837db05c9732a546e72d42013085ebf137682b86f9422d4504366848ca4efddcccb3e3478cf34db24f9d51c1e988b5ee98c02720fb40488cfc2f0addf6f9df8e7a3137d3c9b33a9fbfa7702c915887b01c6346be529101050a6e830d80dc0384161f8eae900ede9d73701a4606b0baa8581eb61ed966e3faf7309760dabc93d3aaa3eb0873673472f3a361d7e3d1a20ae684aebb0081c0d6741832ecaf19abc9c47fedcb0fa375ca03e17649d0aa3861c681928528bc6a127049ffce1f44407056e108f47d4bc7289904ea6ab0dea8c8d9bc7d60eb737a2f36e445d8bcdf05c9be7350ddadee1e57ce4d003021fe6867a50d681642d2a1c7b541bc31ad853b872f0790cef69a9dd4b450bd88fc96f5442ae11899066f6a9324a223bb3eb605ac78ad42b1f4c295c10d8473104bc9465f7c074b40884c78d3507337bedddfd29f031ef8fdda2b848d2ae4dd244a031a3094c4682d77eff4909be69f0a068311b11ef502e5c0a8ae11ca6d3e70c8bff3610c5b36ed230904eada9401eabe090f5f10eacbbfab18d2b4fa438149d03a4a455b51fdfc08760e52d75e1545529cf48079a9c93ef91c48cb5fb38ba5c7410e5435aca6ad905b2d276097a46d68a302d5d53a31dfda8af63b2c83cd15ab548b5e6680fc19e99da99d1a7a10266b3e332d4bd3ea850147943e3b3c48e9b1c3121b754d07e4df25cb42c0a0b1fda9292f73f036e6463908d0a24331b68169155a3200212f0ea1d468bab414b2a4e13426bd1707be2fab7dee27a3c3078fe934290013bc288ec66e96e971335fb1db5cd852f983baeb1f952c36ed9af7df07fe5c95d05f196da667ad13ae71f5ffa44328a26ca33877ac6635121bf871c5e81db69a83dc4b31b000630900c3c504a3361e42f3936aebc744eada61be8431d6c0ffb5f8c51f21edb8e01262e38b5a0cfa38cf758bb43c1449132cf494396e35f16657fddb011f364b909763f800ae9ef72c4005525c7ebb69e5c3b1acca47099e265cf0e5ebe4bb4507b8818c0f9b5961f8d2cf59ff5b9fbd60462145e180b3740d8f3ece5cf4e35590e5d8fd04de52234e968d92b368f4863b112e86e4592aaa1560b5bfd8f2999b8601548f66dea5212504d5167b1107ce4d5a92ea5bc1dc16fb164e50c94b9fbdb1d3f451c7ebd2481319a8c52017da89bd4d7e23ee0d8a210c166cb8684d8a3ac05f79f5f0e721840c304a6cfc9b3f4872c3fb4aec86ab5655668948db71b5418cc8c3dad1e775a8ad76a3876fc66d27edfb3c98c2182f131ba2d5bed63d6c605b57434e1695e36ce015a08670dc5c42dc985e0c2fdb7a0d970405d9188f6843160c90118fdba9765f75965e0408581fcf5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a'
            # }
            L2CAP-CoC Central->Peripheral: f5c10ceb43fee92f44f81b7343e509c926b3b3c972c3ef92ab30630e55d1664563034bcd1beea252e9f019b85866d2e9fadbbd03fb81ce026042884532d001ecf101b53a2b160b5920a48ce584564bfc01878a0249c2ff9c05a6bfabd9c4caad0c38c027a08055121f0c452b60f833e8961fbc811a4d1ba4b7a821e50a

            # Peripheral sends L2CAP CoC K-frame completing 9-byte ISO 18013-5 message.
            # {
            #   "status": 20
            # }
            L2CAP-CoC Peripheral->Central: 0d0000000009a16673746174757314

            # Central sends HCI_Disconnect (0x0406, handle=0x0040, reason=0x13).
            HCI-Cmd (Central): 01060403400013
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    private suspend fun runFullIso18013ProximityTransaction(
        useL2cap: Boolean,
        useL2capInEngagement: Boolean = false,
        faultConfig: FaultConfig = FaultConfig()
    ): String {
        return withContext(Dispatchers.Default) {
            val presentmentSource = createAnnexDPresentmentSource()
            val transcriptLogger = BleTranscriptLogger()

            val bus = VirtualHciBus(
                aclDataPacketLength = 27,
                totalNumAclDataPackets = 4,
                faultConfig = faultConfig,
                transcript = transcriptLogger
            )
            val serviceUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
            val eDeviceKey = getAnnexDEDeviceKey()
            val eReaderKey = getAnnexDEReaderKey()
            val centralRandomAddr = "1122334455c6".fromHex()
            val peripheralRandomAddr = "aabbccddeecf".fromHex()
            val options = MdocTransportOptions(
                bleUseL2CAP = useL2cap,
            )

            val centralManager = createCentralManager(bus.centralIo, randomAddress = centralRandomAddr)
            val peripheralManager = createPeripheralManager(bus.peripheralIo, randomAddress = peripheralRandomAddr)
            val readerTransport = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = options,
                peripheralManager = peripheralManager,
                uuid = serviceUuid,
                directL2capEnabled = useL2capInEngagement,
            )
            var mdocTransport: MdocTransport? = null

            try {
                // Start peripheral advertising first so HCI command ordering between Peripheral and Central
                // is 100% deterministic and the L2CAP PSM (if useL2capInEngagement is true) is allocated.
                readerTransport.advertise()
                val connectionMethod = readerTransport.connectionMethod as MdocConnectionMethod.Ble
                val engagedPsm = (connectionMethod as? MdocConnectionMethod.Ble.DirectL2capCentral)?.psm
                assertEquals(if (useL2capInEngagement) 0x80 else null, engagedPsm)

                val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
                    addConnectionMethod(connectionMethod)
                }.toDataItem(
                    if (useL2capInEngagement) MdocEngagementType.CCE_SELECT else MdocEngagementType.DEVICE_QR
                )
                val encodedDeviceEngagement = Cbor.encode(deviceEngagement)
                val handover = Simple.NULL

                val createdMdocTransport = BleTransportCentralMdoc(
                    role = MdocRole.MDOC,
                    options = options,
                    centralManager = centralManager,
                    uuid = serviceUuid,
                    psm = engagedPsm
                )
                mdocTransport = createdMdocTransport

                coroutineScope {
                    val readerOpen = async { readerTransport.open(eDeviceKey.publicKey) }
                    val mdocOpen = async { createdMdocTransport.open(eDeviceKey.publicKey) }
                    readerOpen.await()
                    mdocOpen.await()
                }

                assertEquals(useL2cap || useL2capInEngagement, createdMdocTransport.stats.bleUsingL2cap)
                assertEquals(useL2cap || useL2capInEngagement, readerTransport.stats.bleUsingL2cap)
                val mdocStats = createdMdocTransport.stats
                assertEquals(useL2capInEngagement, mdocStats.bleL2capPsmInEngagement)
                assertEquals(useL2capInEngagement, readerTransport.stats.bleL2capPsmInEngagement)
                assertEquals(faultConfig.dropScanAttempts, mdocStats.bleNumScanRestarts)
                assertEquals(
                    faultConfig.failConnectionAttempts + faultConfig.disconnectMidHandshakeAttempts,
                    mdocStats.bleNumGattConnectRetries
                )
                assertEquals(faultConfig.failMtuExchangeAttempts, mdocStats.bleNumMtuRequestRetries)
                assertEquals(faultConfig.failServiceDiscoveryAttempts, mdocStats.bleNumServiceDiscoveryRetries)
                assertEquals(
                    faultConfig.failCharacteristicDiscoveryAttempts,
                    mdocStats.bleNumCharacteristicDiscoveryRetries
                )
                assertEquals(faultConfig.failCharacteristicReadAttempts, mdocStats.bleNumCharacteristicReadRetries)
                assertEquals(faultConfig.failCccdWriteAttempts, mdocStats.bleNumDescriptorWriteRetries)
                assertEquals(faultConfig.failL2capConnectAttempts, mdocStats.bleNumL2capConnectRetries)

                val engagementParamsFlow = MutableStateFlow(
                    EngagementParams(
                        eDeviceKey = eDeviceKey,
                        deviceEngagement = deviceEngagement,
                        handover = handover,
                        eReaderKey = null
                    )
                )

                var telemetry: PresentmentTelemetry? = null
                val presentmentJob = launch {
                    Iso18013Presentment(
                        transport = createdMdocTransport,
                        engagementParams = engagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        timeout = null,
                        timeoutSubsequentRequests = null,
                        onTelemetry = { telemetry = it }
                    )
                }

                val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                val sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeviceEngagement)))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
                    add(handover)
                }
                val encodedSessionTranscript = Cbor.encode(sessionTranscript)

                val readerSessionEncryption = SessionEncryption(
                    role = MdocRole.MDOC_READER,
                    eSelfKey = eReaderKey,
                    remotePublicKey = eDeviceKey.publicKey,
                    encodedSessionTranscript = encodedSessionTranscript
                )

                val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "family_name" to false
                            )
                        )
                    )
                }
                val sessionEstablishment = readerSessionEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                    statusCode = null
                )

                readerTransport.sendMessage(sessionEstablishment)

                val encryptedResponse = readerTransport.waitForMessage()
                val (encodedDeviceResponse, status) = readerSessionEncryption.decryptMessage(encryptedResponse)
                assertEquals(null, status)
                assertNotNull(encodedDeviceResponse)

                val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                deviceResponse.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC)
                )
                assertEquals(1, deviceResponse.documents.size)
                val mdocDoc = deviceResponse.documents[0]
                assertEquals(DrivingLicense.MDL_DOCTYPE, mdocDoc.docType)
                assertEquals(
                    "Doe",
                    mdocDoc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["family_name"]!!.dataElementValue.asTstr
                )

                // Send session termination (status 20)
                readerTransport.sendMessage(
                    readerSessionEncryption.encryptMessage(
                        messagePlaintext = null,
                        statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                    )
                )
                presentmentJob.join()
                assertNotNull(telemetry)
                assertEquals(PresentmentOutcome.SUCCESS, telemetry!!.outcome)
                // Wait for the peripheral side to process HCI_Disconnection_Complete from Central's
                // close() before the finally block runs readerTransport.close(), ensuring deterministic
                // teardown transcript ordering across single-core and multi-core runners.
                withTimeout(5.seconds) {
                    readerTransport.state.first {
                        it == MdocTransport.State.FAILED || it == MdocTransport.State.CLOSED
                    }
                }
            } finally {
                mdocTransport?.close()
                readerTransport.close()
            }
            transcriptLogger.toString()
        }
    }

    /**
     * End-to-end test over [VirtualHciBus] exercising mid-handshake disconnection (`reason=0x3E`
     * firing *after* `HCI_LE_Connection_Complete`), persistent `ATT_EXCHANGE_MTU_REQ` failure
     * falling back to the 23-byte default `ATT_MTU` (`maxCharacteristicSize = 20`), and transient
     * `ATT_ERROR_RSP` failures on characteristic discovery (`0x08`), `Ident` characteristic read
     * (`0x0a`), and CCCD descriptor writes (`0x12`).
     */
    @Test
    fun midHandshakeDisconnectMtuFallbackAndGattStepRetriesOverVirtualHci() = runTest {
        withContext(Dispatchers.Default) {
            val transcriptLogger = BleTranscriptLogger()
            val bus = VirtualHciBus(
                faultConfig = FaultConfig(
                    disconnectMidHandshakeAttempts = 1,
                    // Attempt 1 & 2 (before mid-handshake disconnect) + Attempts 3 & 4 (after reconnect) all fail -> 23-byte MTU fallback
                    failMtuExchangeAttempts = 4,
                    failCharacteristicDiscoveryAttempts = 1,
                    failCharacteristicReadAttempts = 1,
                    failCccdWriteAttempts = 1
                ),
                transcript = transcriptLogger
            )
            val serviceUuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
            val eSenderKey = EcPrivateKeyDoubleCoordinate(
                EcCurve.P256,
                "3f0b81e0661a02c1f35022382829094643f5f812ef63218ff194902bc339c70f".fromHex(),
                "df961d0e5b9615a56f918d4be1f9354586a2f6e1e2ff8f014b6260b34132fa53".fromHex(),
                "2a190a15292d0160650c0156b2e6fb19645262cf4719813f6e40826992ef428f".fromHex()
            ).publicKey
            val centralAddr = byteArrayOf(0x55, 0x44, 0x33, 0x22, 0x11, 0xC0.toByte())
            val peripheralAddr = byteArrayOf(0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(), 0xC0.toByte())
            val centralManager = createCentralManager(bus.centralIo, randomAddress = centralAddr)
            val peripheralManager = createPeripheralManager(bus.peripheralIo, randomAddress = peripheralAddr)

            val mdoc = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = false),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )
            val reader = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = false),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )

            try {
                reader.advertise()
                coroutineScope {
                    val readerOpen = async { reader.open(eSenderKey) }
                    val mdocOpen = async { mdoc.open(eSenderKey) }
                    readerOpen.await()
                    mdocOpen.await()
                }

                assertEquals(MdocTransport.State.CONNECTED, mdoc.state.value)
                assertEquals(MdocTransport.State.CONNECTED, reader.state.value)
                val mdocStats = mdoc.stats
                assertEquals(0, mdocStats.bleNumScanRestarts)
                assertEquals(1, mdocStats.bleNumGattConnectRetries)
                assertEquals(2, mdocStats.bleNumMtuRequestRetries)
                assertEquals(0, mdocStats.bleNumServiceDiscoveryRetries)
                assertEquals(1, mdocStats.bleNumCharacteristicDiscoveryRetries)
                assertEquals(1, mdocStats.bleNumCharacteristicReadRetries)
                assertEquals(1, mdocStats.bleNumDescriptorWriteRetries)
                assertEquals(0, mdocStats.bleNumL2capConnectRetries)
                assertEquals(23, mdocStats.bleNegotiatedMtu)
                assertEquals(
                    listOf(
                        TransportWarning(TransportWarningCode.BLE_MTU_NOT_NEGOTIATED),
                        TransportWarning(TransportWarningCode.BLE_MTU_NOT_NEGOTIATED)
                    ),
                    mdocStats.warnings
                )

                // Verify multi-chunk GATT message transfer over the 23-byte default MTU (19 payload bytes per chunk):
                // Reader sends request first (matching ISO 18013-5 SessionEstablishment order so it is queued
                // on the Peripheral's actionQueue right after stopAdvertising()), then mdoc replies.
                val msgFromReader = Cbor.encode(
                    buildCborMap {
                        put("request", "hello from reader over 23-byte default ATT_MTU")
                    }
                )
                val msgFromMdoc = Cbor.encode(
                    buildCborMap {
                        put("response", "reply from mdoc over 23-byte default ATT_MTU")
                    }
                )

                coroutineScope {
                    val recvJob = async { mdoc.waitForMessage() }
                    reader.sendMessage(msgFromReader)
                    assertContentEquals(msgFromReader, recvJob.await())
                }

                coroutineScope {
                    val recvJob = async { reader.waitForMessage() }
                    mdoc.sendMessage(msgFromMdoc)
                    assertContentEquals(msgFromMdoc, recvJob.await())
                }

                val finalMdocStats = mdoc.stats
                val finalReaderStats = reader.stats
                assertEquals(3, finalMdocStats.bleNumChunksSent)
                assertEquals(3, finalMdocStats.bleNumChunksReceived)
                assertEquals(3, finalReaderStats.bleNumChunksSent)
                assertEquals(3, finalReaderStats.bleNumChunksReceived)
                assertNull(finalReaderStats.bleNegotiatedMtu)
                assertEquals(false, finalReaderStats.bleMtuInferredFromWrite)
                assertEquals(
                    listOf(TransportWarning(TransportWarningCode.BLE_MTU_NOT_NEGOTIATED)),
                    finalReaderStats.warnings
                )
                mdoc.close()
                withTimeout(5.seconds) {
                    reader.state.first {
                        it == MdocTransport.State.FAILED || it == MdocTransport.State.CLOSED
                    }
                }
            } finally {
                mdoc.close()
                reader.close()
            }

            assertEquals(
                """
                # Peripheral sends HCI_Reset (0x0c03).
                HCI-Cmd (Peripheral): 01030c00

                # Peripheral sends HCI_Set_Event_Mask (0x0c01).
                HCI-Cmd (Peripheral): 01010c08fffffbff07f8bf3d

                # Peripheral sends HCI_LE_Set_Event_Mask (0x2001).
                HCI-Cmd (Peripheral): 01012008ff1f000000000000

                # Peripheral sends HCI_LE_Read_Buffer_Size (0x2002).
                HCI-Cmd (Peripheral): 01022000

                # Peripheral sends HCI_LE_Set_Random_Address (0x2005).
                HCI-Cmd (Peripheral): 01052006eeddccbbaac0

                # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
                HCI-Cmd (Peripheral): 010a200100

                # Peripheral sends HCI_LE_Set_Advertising_Parameters (0x2006).
                HCI-Cmd (Peripheral): 0106200f200040000001000000000000000700

                # Peripheral sends HCI_LE_Set_Advertising_Data (0x2008).
                HCI-Cmd (Peripheral): 010820201502010611075555555555554444333322221111111100000000000000000000

                # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1).
                HCI-Cmd (Peripheral): 010a200101

                # Central sends HCI_Reset (0x0c03).
                HCI-Cmd (Central): 01030c00

                # Central sends HCI_Set_Event_Mask (0x0c01).
                HCI-Cmd (Central): 01010c08fffffbff07f8bf3d

                # Central sends HCI_LE_Set_Event_Mask (0x2001).
                HCI-Cmd (Central): 01012008ff1f000000000000

                # Central sends HCI_LE_Read_Buffer_Size (0x2002).
                HCI-Cmd (Central): 01022000

                # Central sends HCI_LE_Set_Random_Address (0x2005).
                HCI-Cmd (Central): 010520065544332211c0

                # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
                HCI-Cmd (Central): 010c20020000

                # Central sends HCI_LE_Set_Scan_Parameters (0x200b).
                HCI-Cmd (Central): 010b200701600030000100

                # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=1).
                HCI-Cmd (Central): 010c20020100

                # Central receives HCI_LE_Advertising_Report (subevent=0x02) from Peripheral.
                HCI-Evt (Central): 043e2102010001eeddccbbaac015020106110755555555555544443333222211111111d3

                # Central sends HCI_LE_Set_Scan_Enable (0x200c, enable=0).
                HCI-Cmd (Central): 010c20020000

                # Central sends HCI_LE_Create_Connection (0x200d).
                HCI-Cmd (Central): 010d2019600030000001eeddccbbaac00106000c000000f40100000000

                # Central receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040).
                HCI-Evt (Central): 043e13010040000001eeddccbbaac006000000f40100

                # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515).
                ATT Central->Peripheral: 020302

                # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x2, handle=0x0, error=0x6 Request Not Supported).
                ATT Peripheral->Central: 0102000006

                # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515) (MTU exchange retry #1).
                ATT Central->Peripheral: 020302

                # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x2, handle=0x0, error=0x6 Request Not Supported).
                ATT Peripheral->Central: 0102000006

                # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 11111111-2222-3333-4444-555555555555.
                ATT Central->Peripheral: 060100ffff002855555555555544443333222211111111

                # ERROR: Peripheral receives HCI_Disconnection_Complete (0x05, status=0x00, handle=0x0040, reason=0x3e Connection Failed to be Established).
                HCI-Evt (Peripheral): 0405040040003e

                # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=1) (re-enabling advertising after mid-handshake disconnect).
                HCI-Cmd (Peripheral): 010a200101

                # ERROR: Central receives HCI_Disconnection_Complete (0x05, status=0x00, handle=0x0040, reason=0x3e Connection Failed to be Established).
                HCI-Evt (Central): 0405040040003e

                # Central sends HCI_LE_Create_Connection (0x200d) (GATT connect retry #1).
                HCI-Cmd (Central): 010d2019600030000001eeddccbbaac00106000c000000f40100000000

                # Central receives HCI_LE_Connection_Complete (subevent=0x01, handle=0x0040).
                HCI-Evt (Central): 043e13010040000001eeddccbbaac006000000f40100

                # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515).
                ATT Central->Peripheral: 020302

                # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x2, handle=0x0, error=0x6 Request Not Supported).
                ATT Peripheral->Central: 0102000006

                # Central sends ATT_EXCHANGE_MTU_REQ (0x02, mtu=515) (MTU exchange retry #1).
                ATT Central->Peripheral: 020302

                # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x2, handle=0x0, error=0x6 Request Not Supported).
                ATT Peripheral->Central: 0102000006

                # Central sends ATT_FIND_BY_TYPE_VALUE_REQ (0x06) for Primary Service UUID 11111111-2222-3333-4444-555555555555.
                ATT Central->Peripheral: 060100ffff002855555555555544443333222211111111

                # Peripheral responds with ATT_FIND_BY_TYPE_VALUE_RSP (0x07, start=0x1, end=0xb).
                ATT Peripheral->Central: 0701000b00

                # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x1, end=0xb, type=0x2803 Characteristic Declaration).
                ATT Central->Peripheral: 0801000b000328

                # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x8, handle=0x1, error=0xa Attribute Not Found).
                ATT Peripheral->Central: 010801000a

                # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x1, end=0xb, type=0x2803 Characteristic Declaration) (characteristic discovery retry #1).
                ATT Central->Peripheral: 0801000b000328

                # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 1 characteristics:
                #   - State (00000005-a123-48ce-896b-4c76973373e6): valueHandle=0x3, props=0x14
                ATT Peripheral->Central: 09150200140300e6733397764c6b89ce4823a105000000

                # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x3, end=0xb, type=0x2803 Characteristic Declaration).
                ATT Central->Peripheral: 0803000b000328

                # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 1 characteristics:
                #   - Client2Server (00000006-a123-48ce-896b-4c76973373e6): valueHandle=0x6, props=0x04
                ATT Peripheral->Central: 09150500040600e6733397764c6b89ce4823a106000000

                # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x6, end=0xb, type=0x2803 Characteristic Declaration).
                ATT Central->Peripheral: 0806000b000328

                # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 1 characteristics:
                #   - Server2Client (00000007-a123-48ce-896b-4c76973373e6): valueHandle=0x8, props=0x10
                ATT Peripheral->Central: 09150700100800e6733397764c6b89ce4823a107000000

                # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0x8, end=0xb, type=0x2803 Characteristic Declaration).
                ATT Central->Peripheral: 0808000b000328

                # Peripheral responds with ATT_READ_BY_TYPE_RSP (0x09) declaring 1 characteristics:
                #   - Ident (00000008-a123-48ce-896b-4c76973373e6): valueHandle=0xb, props=0x02
                ATT Peripheral->Central: 09150a00020b00e6733397764c6b89ce4823a108000000

                # Central sends ATT_READ_BY_TYPE_REQ (0x08, start=0xb, end=0xb, type=0x2803 Characteristic Declaration).
                ATT Central->Peripheral: 080b000b000328

                # Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x8, handle=0xb, error=0xa).
                ATT Peripheral->Central: 01080b000a

                # Central sends ATT_FIND_INFO_REQ (0x04, start=0x4, end=0x4) to discover State descriptors.
                ATT Central->Peripheral: 0404000400

                # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x4, uuid=0x2902 CCCD for State).
                ATT Peripheral->Central: 050104000229

                # Central sends ATT_FIND_INFO_REQ (0x04, start=0x9, end=0x9) to discover Server2Client descriptors.
                ATT Central->Peripheral: 0409000900

                # Peripheral responds with ATT_FIND_INFO_RSP (0x05, handle=0x9, uuid=0x2902 CCCD for Server2Client).
                ATT Peripheral->Central: 050109000229

                # Central sends ATT_READ_REQ (0x0a, handle=0xb) for Ident characteristic.
                ATT Central->Peripheral: 0a0b00

                # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0xa, handle=0xb, error=0xe Unlikely Error).
                ATT Peripheral->Central: 010a0b000e

                # Central sends ATT_READ_REQ (0x0a, handle=0xb) for Ident characteristic (characteristic read retry #1).
                ATT Central->Peripheral: 0a0b00

                # Peripheral responds with ATT_READ_RSP (0x0b, 16 bytes) for Ident characteristic (BLEIdent=2622aa08c75220bc30db83d08a09b847).
                ATT Peripheral->Central: 0b2622aa08c75220bc30db83d08a09b847

                # Central sends ATT_WRITE_REQ (0x12, handle=0x4, value=0x0001) to enable notifications on State CCCD.
                ATT Central->Peripheral: 1204000100

                # ERROR: Peripheral responds with ATT_ERROR_RSP (0x01, reqOpcode=0x12, handle=0x4, error=0xe Unlikely Error).
                ATT Peripheral->Central: 011204000e

                # Central sends ATT_WRITE_REQ (0x12, handle=0x4, value=0x0001) to enable notifications on State CCCD (CCCD write retry #1).
                ATT Central->Peripheral: 1204000100

                # Peripheral responds with ATT_WRITE_RSP (0x13) for State CCCD.
                ATT Peripheral->Central: 13

                # Central sends ATT_WRITE_REQ (0x12, handle=0x9, value=0x0001) to enable notifications on Server2Client CCCD.
                ATT Central->Peripheral: 1209000100

                # Peripheral responds with ATT_WRITE_RSP (0x13) for Server2Client CCCD.
                ATT Peripheral->Central: 13

                # Central writes 0x1 to State characteristic (handle=0x03) via ATT_WRITE_CMD (0x52).
                ATT Central->Peripheral: 52030001

                # Peripheral sends HCI_LE_Set_Advertise_Enable (0x200a, enable=0).
                HCI-Cmd (Peripheral): 010a200100

                # Peripheral sends continuation Server2Client GATT notification chunk (0x01, 19 payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b).
                ATT Peripheral->Central: 1b080001a16772657175657374782e68656c6c6f206672

                # Peripheral sends continuation Server2Client GATT notification chunk (0x01, 19 payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b).
                ATT Peripheral->Central: 1b0800016f6d20726561646572206f7665722032332d62

                # Peripheral sends final Server2Client GATT notification chunk (0x00, 19 payload bytes) via ATT_HANDLE_VALUE_NTF (0x1b).
                # {
                #   "request": "hello from reader over 23-byte default ATT_MTU"
                # }
                ATT Peripheral->Central: 1b0800007974652064656661756c74204154545f4d5455

                # Central sends continuation Client2Server GATT chunk (0x01, 19 payload bytes) via ATT_WRITE_CMD (0x52).
                ATT Central->Peripheral: 52060001a168726573706f6e7365782c7265706c792066

                # Central sends continuation Client2Server GATT chunk (0x01, 19 payload bytes) via ATT_WRITE_CMD (0x52).
                ATT Central->Peripheral: 52060001726f6d206d646f63206f7665722032332d6279

                # Central sends final Client2Server GATT chunk (0x00, 18 payload bytes) via ATT_WRITE_CMD (0x52).
                # {
                #   "response": "reply from mdoc over 23-byte default ATT_MTU"
                # }
                ATT Central->Peripheral: 5206000074652064656661756c74204154545f4d5455

                # Central sends HCI_Disconnect (0x0406, handle=0x0040, reason=0x13).
                HCI-Cmd (Central): 01060403400013
                """.trimIndent(),
                transcriptLogger.toString()
            )
        }
    }

    /**
     * Verifies L2CAP Connection-Oriented Channel (CoC) credit replenishment
     * (`L2CAP_FLOW_CONTROL_CREDIT_IND = 0x16`) and multi-SDU segmentation/reassembly for payloads
     * exceeding the 65,535-byte L2CAP SDU MTU and the 256-credit initial window (`L2CAP_INITIAL_CREDITS`).
     */
    @Test
    fun l2capCreditReplenishmentAndLargeMultiSduPayloadOverVirtualHci() = runTest {
        withContext(Dispatchers.Default) {
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
            val bus = VirtualHciBus(aclDataPacketLength = 251, totalNumAclDataPackets = 16)
            val centralManager = createCentralManager(bus.centralIo)
            val peripheralManager = createPeripheralManager(bus.peripheralIo)

            val mdoc = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = true),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )
            val reader = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = true),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )

            try {
                reader.advertise()
                coroutineScope {
                    val readerOpen = async { reader.open(eSenderKey) }
                    val mdocOpen = async { mdoc.open(eSenderKey) }
                    readerOpen.await()
                    mdocOpen.await()
                }

                // 70,000-byte payload (+ 4-byte ISO 18013-5 length header = 70,004 bytes):
                // - Splits across 2 L2CAP SDUs (65,535 bytes + 4,469 bytes)
                // - Splits across 284 K-frames at MPS=247, exceeding L2CAP_INITIAL_CREDITS (256)
                //   and requiring L2CAP_FLOW_CONTROL_CREDIT_IND (0x16) replenishment mid-transfer.
                val largePayload1 = Random(42).nextBytes(70_000)
                val largePayload2 = Random(99).nextBytes(70_000)

                coroutineScope {
                    val recvJob = async { reader.waitForMessage() }
                    mdoc.sendMessage(largePayload1)
                    assertContentEquals(largePayload1, recvJob.await())
                }

                coroutineScope {
                    val recvJob = async { mdoc.waitForMessage() }
                    reader.sendMessage(largePayload2)
                    assertContentEquals(largePayload2, recvJob.await())
                }

                // 4 credit replenishment indications in each direction (every 64 K-frames across 284 K-frames).
                // The 4th replenishment in the second direction is sent asynchronously on engine.scope after
                // read() consumes the 256th K-frame, so wait for it to arrive on the virtual HCI bus.
                withTimeout(5.seconds) {
                    bus.l2capCreditIndCount.first { it >= 8 }
                }
                assertTrue(
                    bus.l2capCreditIndCount.value >= 8,
                    "Expected at least 8 L2CAP_FLOW_CONTROL_CREDIT_IND PDUs, got ${bus.l2capCreditIndCount.value}"
                )
            } finally {
                mdoc.close()
                reader.close()
            }
        }
    }

    /**
     * Verifies ISO/IEC 18013-5:2021 Clause 8.3.3.1.1.3 `Ident` validation: if the `Ident` characteristic
     * read from the Peripheral does not match `HKDF-SHA-256(E.SenderKeyBytes, "BLEIdent")`, the Central
     * immediately aborts the connection without retrying the characteristic read.
     */
    @Test
    fun identCharacteristicMismatchFailsImmediatelyWithoutRetryOverVirtualHci() = runTest {
        withContext(Dispatchers.Default) {
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
            val bus = VirtualHciBus()
            val centralManager = createCentralManager(bus.centralIo)
            val peripheralManager = createPeripheralManager(bus.peripheralIo)

            val wrongSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

            val mdoc = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = false),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )
            val reader = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = false),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )

            try {
                reader.advertise()
                val readerOpenJob = launch {
                    try {
                        reader.open(wrongSenderKey)
                    } catch (_: Throwable) {
                    }
                }
                val ex = assertFailsWith<MdocTransportException> {
                    mdoc.open(eSenderKey)
                }
                assertTrue(
                    ex.cause?.message?.contains("for ident, got") == true,
                    "Unexpected exception cause: ${ex.cause?.message}"
                )
                assertEquals(MdocTransport.State.FAILED, mdoc.state.value)
                assertEquals(0, mdoc.stats.bleNumCharacteristicReadRetries)
                readerOpenJob.cancel()
            } finally {
                mdoc.close()
                reader.close()
            }
        }
    }

    /**
     * Verifies that when `ATT_EXCHANGE_MTU_REQ` is intercepted/consumed by the controller without
     * firing `onMtuChanged` on the Peripheral (simulating the Android shared-ACL-link missed
     * `onMtuChanged` callback), the Peripheral infers the negotiated MTU from the Central's first
     * multi-byte `Client2Server` write (`bleMtuInferredFromWrite = true`, `BLE_MTU_INFERRED_FROM_WRITE`
     * warning) and uses that inferred MTU for its subsequent `Server2Client` notifications.
     */
    @Test
    fun peripheralInfersMtuFromWriteWhenMtuCallbackMissedOverVirtualHci() = runTest {
        withContext(Dispatchers.Default) {
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
            val bus = VirtualHciBus(
                aclDataPacketLength = 251,
                totalNumAclDataPackets = 8,
                faultConfig = FaultConfig(interceptMtuExchangeAtController = true)
            )
            val centralManager = createCentralManager(bus.centralIo)
            val peripheralManager = createPeripheralManager(bus.peripheralIo)

            val mdoc = BleTransportCentralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = false),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )
            val reader = BleTransportCentralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = false),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )

            try {
                reader.advertise()
                coroutineScope {
                    val readerOpen = async { reader.open(eSenderKey) }
                    val mdocOpen = async { mdoc.open(eSenderKey) }
                    readerOpen.await()
                    mdocOpen.await()
                }

                // Before any Client2Server write arrives, reader has not received onMtuChanged
                assertNull(reader.stats.bleNegotiatedMtu)
                assertEquals(false, reader.stats.bleMtuInferredFromWrite)
                assertEquals(515, mdoc.stats.bleNegotiatedMtu)
                assertEquals(false, mdoc.stats.bleMtuInferredFromWrite)

                // Central sends a 1,200-byte message over Client2Server in 3 chunks (511 + 511 + 178 payload bytes).
                // On the first 512-byte ATT_WRITE_CMD, Peripheral infers MTU = 512 + 3 = 515.
                val msgFromMdoc = ByteArray(1200) { (it and 0xFF).toByte() }
                coroutineScope {
                    val recvJob = async { reader.waitForMessage() }
                    mdoc.sendMessage(msgFromMdoc)
                    assertContentEquals(msgFromMdoc, recvJob.await())
                }

                val readerStatsAfterRx = reader.stats
                assertEquals(515, readerStatsAfterRx.bleNegotiatedMtu)
                assertEquals(true, readerStatsAfterRx.bleMtuInferredFromWrite)
                assertEquals(
                    listOf(TransportWarning(TransportWarningCode.BLE_MTU_INFERRED_FROM_WRITE)),
                    readerStatsAfterRx.warnings
                )

                // Peripheral replies with a 1,200-byte message over Server2Client; because it inferred
                // MTU = 515, it sends in 3 chunks of 512 bytes instead of 64 chunks of 20 bytes!
                val msgFromReader = ByteArray(1200) { ((it * 3) and 0xFF).toByte() }
                coroutineScope {
                    val recvJob = async { mdoc.waitForMessage() }
                    reader.sendMessage(msgFromReader)
                    assertContentEquals(msgFromReader, recvJob.await())
                }

                assertEquals(3, reader.stats.bleNumChunksSent)
                assertEquals(3, reader.stats.bleNumChunksReceived)
                assertEquals(3, mdoc.stats.bleNumChunksSent)
                assertEquals(3, mdoc.stats.bleNumChunksReceived)
            } finally {
                mdoc.close()
                reader.close()
            }
        }
    }

    /**
     * Verifies ISO/IEC 18013-5:2021 Clause 8.3.3.1.1.7 transport-specific session termination (`State = 0x02`)
     * in both GATT directions over `VirtualHciBus`:
     * 1. Central sends `sendMessage(byteArrayOf())` (`ATT_WRITE_CMD` `0x02` to `State`), causing Peripheral's
     *    `waitForMessage()` to return `byteArrayOf()`.
     * 2. Peripheral sends `sendMessage(byteArrayOf())` (`ATT_HANDLE_VALUE_NTF` `0x02` on `State`), causing
     *    Central's `waitForMessage()` to return `byteArrayOf()`.
     */
    @Test
    fun transportSpecificTerminationBothDirectionsOverVirtualHci() = runTest {
        withContext(Dispatchers.Default) {
            val serviceUuid = UUID.randomUUID()
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
            // Direction 1: Central -> Peripheral (ATT_WRITE_CMD 0x02 on State)
            run {
                val bus = VirtualHciBus()
                val centralManager = createCentralManager(bus.centralIo)
                val peripheralManager = createPeripheralManager(bus.peripheralIo)
                val mdoc = BleTransportCentralMdoc(
                    role = MdocRole.MDOC,
                    options = MdocTransportOptions(bleUseL2CAP = false),
                    centralManager = centralManager,
                    uuid = serviceUuid,
                    psm = null
                )
                val reader = BleTransportCentralMdocReader(
                    role = MdocRole.MDOC_READER,
                    options = MdocTransportOptions(bleUseL2CAP = false),
                    peripheralManager = peripheralManager,
                    uuid = serviceUuid
                )
                try {
                    reader.advertise()
                    coroutineScope {
                        val readerOpen = async { reader.open(eSenderKey) }
                        val mdocOpen = async { mdoc.open(eSenderKey) }
                        readerOpen.await()
                        mdocOpen.await()
                    }
                    coroutineScope {
                        val recvJob = async { reader.waitForMessage() }
                        mdoc.sendMessage(byteArrayOf())
                        val terminationMsg = recvJob.await()
                        assertEquals(0, terminationMsg.size)
                    }
                } finally {
                    mdoc.close()
                    reader.close()
                }
            }

            // Direction 2: Peripheral -> Central (ATT_HANDLE_VALUE_NTF 0x02 on State)
            run {
                val bus = VirtualHciBus()
                val centralManager = createCentralManager(bus.centralIo)
                val peripheralManager = createPeripheralManager(bus.peripheralIo)
                val mdoc = BleTransportCentralMdoc(
                    role = MdocRole.MDOC,
                    options = MdocTransportOptions(bleUseL2CAP = false),
                    centralManager = centralManager,
                    uuid = serviceUuid,
                    psm = null
                )
                val reader = BleTransportCentralMdocReader(
                    role = MdocRole.MDOC_READER,
                    options = MdocTransportOptions(bleUseL2CAP = false),
                    peripheralManager = peripheralManager,
                    uuid = serviceUuid
                )
                try {
                    reader.advertise()
                    coroutineScope {
                        val readerOpen = async { reader.open(eSenderKey) }
                        val mdocOpen = async { mdoc.open(eSenderKey) }
                        readerOpen.await()
                        mdocOpen.await()
                    }
                    coroutineScope {
                        val recvJob = async { mdoc.waitForMessage() }
                        reader.sendMessage(byteArrayOf())
                        val terminationMsg = recvJob.await()
                        assertEquals(0, terminationMsg.size)
                    }
                } finally {
                    mdoc.close()
                    reader.close()
                }
            }
        }
    }


    /**
     * Verifies [BleCentralHalHci]'s workaround for AOSP peripherals (such as Pixel 8 Pro) whose
     * GAP client sends `ATT_READ_BY_TYPE_REQ` (`0x08`) for `0x2A00` (GAP Device Name) and
     * `0x2A01` (GAP Appearance) as well as `ATT_EXCHANGE_MTU_REQ` (`0x02`) on `CID 0x0004`
     * while the Central's own `ATT_EXCHANGE_MTU_REQ` (`0x02`) and `ATT_FIND_BY_TYPE_VALUE_REQ`
     * (`0x06`) are in flight:
     * - [BleCentralHalHci] replies with valid GAP Device Name (`"Multipaz"` at handle `0x0003`),
     *   GAP Appearance (`0x0000` at handle `0x0005`), and `ATT_EXCHANGE_MTU_RSP` (`0x03`).
     * - In-flight Central ATT requests (`requestMtu`, `discoverServices`) are not completed with
     *   mismatched peripheral PDUs (`bleNumMtuExchangeRetries == 0`, `bleNumServiceDiscoveryRetries == 0`).
     */
    @Test
    fun aospPeripheralGapClientRequestsDuringCentralGattSetup() = runTest {
        withContext(Dispatchers.Default) {
            for (expectedDeviceName in listOf(null, "CustomReader")) {
                val bus = VirtualHciBus(
                    faultConfig = FaultConfig(injectPeripheralGapClientRequestsOnConnect = true)
                )
                val serviceUuid = UUID.randomUUID()
                val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

                val centralManager = if (expectedDeviceName == null) {
                    createCentralManager(bus.centralIo)
                } else {
                    createCentralManager(bus.centralIo, gapDeviceName = expectedDeviceName)
                }
                val peripheralManager = createPeripheralManager(bus.peripheralIo)

                val mdoc = BleTransportCentralMdoc(
                    role = MdocRole.MDOC,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                    centralManager = centralManager,
                    uuid = serviceUuid,
                    psm = null
                )
                val reader = BleTransportCentralMdocReader(
                    role = MdocRole.MDOC_READER,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                    peripheralManager = peripheralManager,
                    uuid = serviceUuid
                )

                try {
                    reader.advertise()
                    coroutineScope {
                        val readerOpen = async { reader.open(eSenderKey) }
                        val mdocOpen = async { mdoc.open(eSenderKey) }
                        readerOpen.await()
                        mdocOpen.await()
                    }

                    val payload = "pixel8-gap-client-race-test".encodeToByteArray()
                    coroutineScope {
                        val recvJob = async { reader.waitForMessage() }
                        mdoc.sendMessage(payload)
                        assertContentEquals(payload, recvJob.await())
                    }

                    val stats = mdoc.stats
                    assertNotNull(stats)
                    assertEquals(0, stats.bleNumMtuRequestRetries)
                    assertEquals(0, stats.bleNumServiceDiscoveryRetries)

                    val gapResponses = bus.centralAttResponsesLock.withLock {
                        bus.centralAttResponsesToPeripheralGapClient.map { it.toHex() }
                    }
                    val resolvedName = expectedDeviceName ?: "Multipaz"
                    val nameBytes = resolvedName.encodeToByteArray()
                    val expectedNameRsp = ByteArray(4 + nameBytes.size).also {
                        it[0] = BleHciConstants.ATT_OP_READ_BY_TYPE_RSP.toByte()
                        it[1] = (2 + nameBytes.size).toByte()
                        putU16le(it, 2, 0x0003)
                        nameBytes.copyInto(it, destinationOffset = 4)
                    }.toHex()
                    assertTrue(
                        gapResponses.contains(expectedNameRsp),
                        "Expected GAP Device Name response ($resolvedName -> $expectedNameRsp) in $gapResponses"
                    )
                    // 090405000000 = ATT_READ_BY_TYPE_RSP (handle=0x0005, Appearance=0x0000)
                    assertTrue(gapResponses.contains("090405000000"), "Expected GAP Appearance response in $gapResponses")
                    // 030302 = ATT_EXCHANGE_MTU_RSP (mtu=515)
                    assertTrue(gapResponses.contains("030302"), "Expected ATT_EXCHANGE_MTU_RSP in $gapResponses")
                } finally {
                    mdoc.close()
                    reader.close()
                    bus.centralIo.close()
                    bus.peripheralIo.close()
                }
            }
        }
    }

    /**
     * Verifies:
     * 1. `HCI_Read_Buffer_Size` (`0x1005`) shared BR/EDR buffer fallback reads `HC_Total_Num_ACL_Data_Packets`
     *    from bytes `[4..5]` (skipping the 1-byte `HC_Synchronous_Data_Packet_Length` at byte `[3]`).
     * 2. `Num_HCI_Command_Packets == 0` in `HCI_Command_Complete` pauses subsequent HCI commands until
     *    the controller emits `HCI_Command_Complete(opcode=0x0000, numPackets=1)`.
     * 3. Generated random static addresses satisfy Core Spec Vol 6 Part B 1.3.2.1 (`MSB[7:6] == 11` and
     *    46-bit random part neither all zeros nor all ones).
     * 4. Concurrent ACL fragment sends and `HCI_Number_Of_Completed_Packets` events never leak ACL credits.
     */
    @Test
    fun hciReadBufferSizeFallbackCommandFlowControlAndStaticAddress() = runTest {
        withContext(Dispatchers.Default) {
            val rxEvents = Channel<ByteArray>(Channel.UNLIMITED)
            var setRandomAddressPayload: ByteArray? = null
            var commandAfterZeroCreditsSentBeforeNop = false
            var nopCreditGranted = false

            val mockIo = object : HciPacketIo {
                override suspend fun readPacket(): ByteArray? = rxEvents.receiveCatching().getOrNull()

                override suspend fun writePacket(packet: ByteArray) {
                    when (packet[0]) {
                        BleHciConstants.HCI_COMMAND_PKT -> {
                            val opcode = u16le(packet, 1)
                            val paramLen = packet[3].toInt() and 0xFF
                            val params = packet.copyOfRange(4, 4 + paramLen)
                            when (opcode) {
                                BleHciConstants.HCI_OP_RESET -> {
                                    // Return Num_HCI_Command_Packets = 0 first, then emit NOP with 1 credit
                                    rxEvents.trySend(byteArrayOf(0x04, 0x0E, 0x04, 0x00, 0x03, 0x0C, 0x00))
                                    launch {
                                        delay(15.milliseconds)
                                        nopCreditGranted = true
                                        rxEvents.trySend(byteArrayOf(0x04, 0x0E, 0x03, 0x01, 0x00, 0x00))
                                    }
                                }
                                BleHciConstants.HCI_OP_SET_EVENT_MASK -> {
                                    if (!nopCreditGranted) {
                                        commandAfterZeroCreditsSentBeforeNop = true
                                    }
                                    rxEvents.trySend(byteArrayOf(0x04, 0x0E, 0x04, 0x01, 0x01, 0x0C, 0x00))
                                }
                                BleHciConstants.HCI_OP_LE_SET_EVENT_MASK -> {
                                    rxEvents.trySend(byteArrayOf(0x04, 0x0E, 0x04, 0x01, 0x01, 0x20, 0x00))
                                }
                                BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE -> {
                                    // Shared with BR/EDR: pktLen = 0, numPkts = 0
                                    rxEvents.trySend(byteArrayOf(0x04, 0x0E, 0x07, 0x01, 0x02, 0x20, 0x00, 0x00, 0x00, 0x00))
                                }
                                BleHciConstants.HCI_OP_READ_BUFFER_SIZE -> {
                                    // [status=0, aclLen=251(0x00fb), scoLen=64(0x40), numAcl=6(0x0006), numSco=4(0x0004)]
                                    rxEvents.trySend(
                                        byteArrayOf(
                                            0x04, 0x0E, 0x0B, 0x01, 0x05, 0x10,
                                            0x00, 0xFB.toByte(), 0x00, 0x40, 0x06, 0x00, 0x04, 0x00
                                        )
                                    )
                                }
                                BleHciConstants.HCI_OP_LE_SET_RANDOM_ADDRESS -> {
                                    setRandomAddressPayload = params
                                    rxEvents.trySend(byteArrayOf(0x04, 0x0E, 0x04, 0x01, 0x05, 0x20, 0x00))
                                }
                            }
                        }
                        BleHciConstants.HCI_ACL_DATA_PKT -> {
                            val handle = u16le(packet, 1) and 0x0FFF
                            launch {
                                rxEvents.trySend(
                                    byteArrayOf(
                                        0x04, 0x13, 0x05, 0x01,
                                        (handle and 0xFF).toByte(),
                                        ((handle ushr 8) and 0xFF).toByte(),
                                        0x01, 0x00
                                    )
                                )
                            }
                        }
                    }
                }

                override fun close() {
                    rxEvents.close()
                }
            }

            val engine = BleHciEngine(packetIo = mockIo)
            try {
                engine.initializeController()
                assertEquals(false, commandAfterZeroCreditsSentBeforeNop)
                assertEquals(251, engine.leAclDataPacketLength)
                assertEquals(6, engine.totalNumLeAclDataPackets)

                val randAddr = assertNotNull(setRandomAddressPayload)
                assertEquals(6, randAddr.size)
                assertEquals(0xC0, randAddr[5].toInt() and 0xC0)

                // Simulate connection on handle 0x0000 (verifying handle 0 is valid!) and stress ACL credits
                val connEv = ByteArray(22)
                connEv[0] = 0x04
                connEv[1] = 0x3E
                connEv[2] = 19
                connEv[3] = BleHciConstants.HCI_EV_LE_CONN_COMPLETE.toByte()
                connEv[4] = 0x00 // status = success, handle = 0x0000
                rxEvents.trySend(connEv)
                delay(10.milliseconds)

                // Send 40 multi-fragment PDUs across coroutines with only 6 controller ACL buffers;
                // if any credit were lost under CAS contention, this would deadlock.
                coroutineScope {
                    repeat(4) {
                        launch {
                            repeat(10) {
                                engine.sendL2capPdu(0x0000, BleHciConstants.L2CAP_CID_ATT, ByteArray(600))
                            }
                        }
                    }
                }
            } finally {
                engine.close()
            }
        }
    }

    /**
     * Verifies L2CAP signaling edge cases:
     * 1. When a peer probes `0x17` (`L2CAP_CREDIT_BASED_CONNECTION_REQ`, BT 5.2 ECFC) on `CID 0x0005`,
     *    `BlePeripheralHalHci` responds with `L2CAP_COMMAND_REJECT_RSP` (`0x01`, reason `0x0000`), allowing
     *    immediate fallback to `0x14` (`L2CAP_LE_CREDIT_BASED_CONNECTION_REQ`).
     * 2. When `HciBleL2capChannel.write()` is suspended with `0` peer credits, `L2CAP_DISCONN_REQ` (`0x06`)
     *    immediately wakes the writer with `IOException`.
     * 3. `nextNonZeroL2capIdentifier()` skips `0x00` on 8-bit wraparound.
     */
    @Test
    fun l2capEnhancedCreditBasedProbeRejectedAndDisconnUnblocksZeroCreditWrite() = runTest {
        withContext(Dispatchers.Default) {
            val idCounter = HciAtomicInt(255)
            assertEquals(255, nextNonZeroL2capIdentifier(idCounter))
            assertEquals(1, nextNonZeroL2capIdentifier(idCounter))

            val rxFromController = Channel<ByteArray>(Channel.UNLIMITED)
            val txSignalingPdus = Channel<ByteArray>(Channel.UNLIMITED)
            val io = object : HciPacketIo {
                override suspend fun readPacket(): ByteArray? = rxFromController.receiveCatching().getOrNull()
                override suspend fun writePacket(packet: ByteArray) {
                    when (packet[0]) {
                        BleHciConstants.HCI_COMMAND_PKT -> {
                            val op = u16le(packet, 1)
                            val ret = if (op == BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE) {
                                byteArrayOf(0x00, 0xFB.toByte(), 0x00, 0x08)
                            } else {
                                byteArrayOf(0x00)
                            }
                            val ev = ByteArray(6 + ret.size)
                            ev[0] = 0x04
                            ev[1] = 0x0E
                            ev[2] = (3 + ret.size).toByte()
                            ev[3] = 0x01
                            putU16le(ev, 4, op)
                            ret.copyInto(ev, destinationOffset = 6)
                            rxFromController.trySend(ev)
                        }
                        BleHciConstants.HCI_ACL_DATA_PKT -> {
                            val handle = u16le(packet, 1) and 0x0FFF
                            rxFromController.trySend(
                                byteArrayOf(
                                    0x04, 0x13, 0x05, 0x01,
                                    (handle and 0xFF).toByte(),
                                    ((handle ushr 8) and 0xFF).toByte(),
                                    0x01, 0x00
                                )
                            )
                            val cid = u16le(packet, 7)
                            if (cid == BleHciConstants.L2CAP_CID_LE_SIGNALING) {
                                val len = u16le(packet, 5)
                                txSignalingPdus.trySend(packet.copyOfRange(9, 9 + len))
                            }
                        }
                    }
                }
                override fun close() {
                    rxFromController.close()
                }
            }

            val connectedChannels = Channel<BleL2capChannel>(1)
            val hal = BlePeripheralHalHci(packetIoFactory = { io })
            hal.setCallback(object : BlePeripheralHal.Callback {
                override fun onPowerOn(error: Throwable?) {}
                override fun onL2capPublished(psm: Int?, error: Throwable?) {}
                override fun onServiceAdded(error: Throwable?) {}
                override fun onAdvertisingStarted(error: Throwable?) {}
                override fun onMtuChanged(mtu: Int) {}
                override fun onCharacteristicReadRequest(requestId: Int, uuid: UUID) {}
                override fun onCharacteristicWriteRequest(uuid: UUID, value: ByteArray, maxWriteSizeForCentral: Int?) {}
                override fun onNotificationSent(uuid: UUID, error: Throwable?) {}
                override fun onL2capClientConnected(channel: BleL2capChannel) {
                    connectedChannels.trySend(channel)
                }
                override fun onCentralDisconnected() {}
                override fun onError(error: Throwable) {}
            })

            try {
                hal.waitForPowerOn()
                hal.publishL2capChannel()
                delay(20.milliseconds)

                // Emit LE Connection Complete on handle = 0x0000
                val connEv = ByteArray(22)
                connEv[0] = 0x04
                connEv[1] = 0x3E
                connEv[2] = 19
                connEv[3] = 0x01
                rxFromController.trySend(connEv)

                fun sendSignalingToPeripheral(sigPdu: ByteArray) {
                    val acl = ByteArray(9 + sigPdu.size)
                    acl[0] = 0x02
                    putU16le(acl, 1, 0x0000 or (0x02 shl 12))
                    putU16le(acl, 3, 4 + sigPdu.size)
                    putU16le(acl, 5, sigPdu.size)
                    putU16le(acl, 7, BleHciConstants.L2CAP_CID_LE_SIGNALING)
                    sigPdu.copyInto(acl, destinationOffset = 9)
                    rxFromController.trySend(acl)
                }

                // 1. Probe 0x17 (L2CAP_CREDIT_BASED_CONNECTION_REQ) -> expect 0x01 Command Reject (reason 0x0000)
                sendSignalingToPeripheral(
                    byteArrayOf(0x17, 0x05, 0x0C, 0x00, 0x80.toByte(), 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xF7.toByte(), 0x00, 0x10, 0x00, 0x40, 0x00, 0x00, 0x00)
                )
                val rejectPdu = txSignalingPdus.receive()
                assertContentEquals(byteArrayOf(0x01, 0x05, 0x02, 0x00, 0x00, 0x00), rejectPdu)

                // 2. Fallback to 0x14 with initialCredits = 0
                val connReq = ByteArray(14)
                connReq[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_REQ.toByte()
                connReq[1] = 0x06
                putU16le(connReq, 2, 10)
                putU16le(connReq, 4, 0x0080)
                putU16le(connReq, 6, 0x0045) // peerScid
                putU16le(connReq, 8, 512)
                putU16le(connReq, 10, 247)
                putU16le(connReq, 12, 0) // 0 initial credits!
                sendSignalingToPeripheral(connReq)

                val connRsp = txSignalingPdus.receive()
                assertEquals(BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP, connRsp[0].toInt() and 0xFF)
                assertEquals(0x0000, u16le(connRsp, 12))
                val channel = connectedChannels.receive()

                // 3. Start write() with 0 credits; it must suspend until L2CAP_DISCONN_REQ wakes it with IOException
                val writeDeferred = async {
                    assertFailsWith<IOException> {
                        channel.write(byteArrayOf(1, 2, 3, 4))
                    }
                }
                delay(20.milliseconds)
                val disconnReq = ByteArray(8)
                disconnReq[0] = BleHciConstants.L2CAP_DISCONN_REQ.toByte()
                disconnReq[1] = 0x07
                putU16le(disconnReq, 2, 4)
                putU16le(disconnReq, 4, 0x0040) // dcid = peripheral's localCid
                putU16le(disconnReq, 6, 0x0045) // scid = central's peerScid
                sendSignalingToPeripheral(disconnReq)

                val disconnRsp = txSignalingPdus.receive()
                assertEquals(BleHciConstants.L2CAP_DISCONN_RSP, disconnRsp[0].toInt() and 0xFF)
                writeDeferred.await()
            } finally {
                hal.close()
            }
        }
    }

    /**
     * Verifies that when `BleCentralManagerImpl`'s `l2capConnectTimeout` expires while `L2CAP_LE_CREDIT_CONN_REQ`
     * is still awaiting a response:
     * - [BleCentralHalHci.closeConnectedL2capAttempt] immediately cancels the in-flight attempt on `actionQueue`
     *   and rotates the local dynamic SCID (`0x0040` -> `0x0041`).
     * - A delayed response for timed-out attempt #1 is ignored and does not fail or confuse attempt #2.
     */
    @Test
    fun l2capConnectTimeoutCancelsInFlightAttemptAndRotatesScid() = runTest {
        withContext(Dispatchers.Default) {
            val rxFromController = Channel<ByteArray>(Channel.UNLIMITED)
            val sentScidsLock = HciLock()
            val sentScids = mutableListOf<Int>()
            var connReqCount = 0
            var firstAttemptId: Byte = 0

            val io = object : HciPacketIo {
                override suspend fun readPacket(): ByteArray? = rxFromController.receiveCatching().getOrNull()
                override suspend fun writePacket(packet: ByteArray) {
                    when (packet[0]) {
                        BleHciConstants.HCI_COMMAND_PKT -> {
                            val op = u16le(packet, 1)
                            if (op == BleHciConstants.HCI_OP_LE_CREATE_CONNECTION) {
                                rxFromController.trySend(byteArrayOf(0x04, 0x0F, 0x04, 0x00, 0x01, 0x0D, 0x20))
                                val connEv = ByteArray(22)
                                connEv[0] = 0x04
                                connEv[1] = 0x3E
                                connEv[2] = 19
                                connEv[3] = 0x01
                                putU16le(connEv, 5, 0x0000)
                                rxFromController.trySend(connEv)
                            } else {
                                val ret = if (op == BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE) {
                                    byteArrayOf(0x00, 0xFB.toByte(), 0x00, 0x08)
                                } else {
                                    byteArrayOf(0x00)
                                }
                                val ev = ByteArray(6 + ret.size)
                                ev[0] = 0x04
                                ev[1] = 0x0E
                                ev[2] = (3 + ret.size).toByte()
                                ev[3] = 0x01
                                putU16le(ev, 4, op)
                                ret.copyInto(ev, destinationOffset = 6)
                                rxFromController.trySend(ev)
                                if (op == BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE && packet[4].toInt() != 0) {
                                    // Emit advertising report for target UUID
                                    val targetBytes = UUID(9UL, 9UL).toLittleEndianBytes()
                                    val adv = ByteArray(3 + 11 + 21 + 1)
                                    adv[0] = 0x04
                                    adv[1] = 0x3E
                                    adv[2] = (11 + 21 + 1).toByte()
                                    adv[3] = 0x02
                                    adv[4] = 0x01
                                    adv[5] = 0x00 // ADV_IND
                                    adv[6] = 0x01
                                    adv[13] = 21
                                    adv[14] = 0x02
                                    adv[15] = 0x01
                                    adv[16] = 0x06
                                    adv[17] = 0x11
                                    adv[18] = 0x07
                                    targetBytes.copyInto(adv, destinationOffset = 19)
                                    rxFromController.trySend(adv)
                                }
                            }
                        }
                        BleHciConstants.HCI_ACL_DATA_PKT -> {
                            val handle = u16le(packet, 1) and 0x0FFF
                            rxFromController.trySend(
                                byteArrayOf(
                                    0x04, 0x13, 0x05, 0x01,
                                    (handle and 0xFF).toByte(),
                                    ((handle ushr 8) and 0xFF).toByte(),
                                    0x01, 0x00
                                )
                            )
                            val cid = u16le(packet, 7)
                            if (cid == BleHciConstants.L2CAP_CID_LE_SIGNALING &&
                                (packet[9].toInt() and 0xFF) == BleHciConstants.L2CAP_LE_CREDIT_CONN_REQ
                            ) {
                                val id = packet[10]
                                val scid = u16le(packet, 15)
                                sentScidsLock.withLock { sentScids.add(scid) }
                                connReqCount++
                                if (connReqCount == 1) {
                                    // Attempt #1 gets no response before l2capConnectTimeout expires
                                    firstAttemptId = id
                                } else {
                                    // Deterministically deliver a stale RSP for attempt #1 first, followed
                                    // immediately by the valid RSP for the current attempt.
                                    val staleRsp = ByteArray(9 + 14)
                                    staleRsp[0] = 0x02
                                    putU16le(staleRsp, 1, 0x0000 or (0x02 shl 12))
                                    putU16le(staleRsp, 3, 18)
                                    putU16le(staleRsp, 5, 14)
                                    putU16le(staleRsp, 7, BleHciConstants.L2CAP_CID_LE_SIGNALING)
                                    staleRsp[9] = BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP.toByte()
                                    staleRsp[10] = firstAttemptId
                                    putU16le(staleRsp, 11, 10)
                                    putU16le(staleRsp, 13, 0x0050)
                                    putU16le(staleRsp, 15, 512)
                                    putU16le(staleRsp, 17, 247)
                                    putU16le(staleRsp, 19, 16)
                                    putU16le(staleRsp, 21, 0x0000)
                                    rxFromController.trySend(staleRsp)

                                    val rsp = ByteArray(9 + 14)
                                    rsp[0] = 0x02
                                    putU16le(rsp, 1, 0x0000 or (0x02 shl 12))
                                    putU16le(rsp, 3, 18)
                                    putU16le(rsp, 5, 14)
                                    putU16le(rsp, 7, BleHciConstants.L2CAP_CID_LE_SIGNALING)
                                    rsp[9] = BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP.toByte()
                                    rsp[10] = id
                                    putU16le(rsp, 11, 10)
                                    putU16le(rsp, 13, 0x0051)
                                    putU16le(rsp, 15, 512)
                                    putU16le(rsp, 17, 247)
                                    putU16le(rsp, 19, 16)
                                    putU16le(rsp, 21, 0x0000)
                                    rxFromController.trySend(rsp)
                                }
                            }
                        }
                    }
                }
                override fun close() {
                    rxFromController.close()
                }
            }

            val svcUuid = UUID(9UL, 9UL)
            val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
            val factory = BtHciMdocTransportFactory(
                centralConfig = BleCentralConfig(
                    postScanDelay = 1.milliseconds,
                    requireL2capPeripheralConnectedFirst = true,
                    l2capConnectTimeout = 80.milliseconds,
                    l2capRetryInitialDelay = 5.milliseconds,
                ),
                packetIoFactory = { io },
            )
            val readerTransport = factory.createTransport(
                MdocConnectionMethod.Ble.DirectL2capPeripheral(
                    uuid = svcUuid,
                    psm = 0x0080,
                ),
                MdocRole.MDOC_READER,
                MdocTransportOptions(),
            )

            try {
                readerTransport.open(eSenderKey)
                assertEquals(true, readerTransport.stats.bleUsingL2cap)
                assertTrue((readerTransport.stats.bleNumL2capConnectRetries ?: 0) >= 1)
                val scids = sentScidsLock.withLock { sentScids.toList() }
                assertTrue(scids.size >= 2 && scids[0] == 0x0040 && scids[1] == 0x0041, "Unexpected SCIDs: $scids")
            } finally {
                readerTransport.close()
            }
        }
    }

    /**
     * Verifies:
     * 1. [BlePeripheralHalHci] exposes the mandatory GAP Primary Service (`0x1800`) with `0x2A00`
     *    (Device Name) and `0x2A01` (Appearance), while enforcing `ATT_ERR_READ_NOT_PERMITTED` (`0x02`)
     *    when reading `State` and `ATT_ERR_WRITE_NOT_PERMITTED` (`0x03`) when writing `Ident`.
     * 2. [BleCentralHalHci] retains `negotiatedMtu = 515` when a peripheral initiates `ATT_EXCHANGE_MTU_REQ`
     *    first and subsequently rejects Central's `ATT_EXCHANGE_MTU_REQ` with `ATT_ERROR_RSP`.
     */
    @Test
    fun peripheralGapServiceAttPermissionsAndAospMtuRetention() = runTest {
        withContext(Dispatchers.Default) {
            // Part 1: Peripheral GAP service & ATT permission checks
            run {
                val rx = Channel<ByteArray>(Channel.UNLIMITED)
                val attReplies = Channel<ByteArray>(Channel.UNLIMITED)
                val io = object : HciPacketIo {
                    override suspend fun readPacket(): ByteArray? = rx.receiveCatching().getOrNull()
                    override suspend fun writePacket(packet: ByteArray) {
                        when (packet[0]) {
                            BleHciConstants.HCI_COMMAND_PKT -> {
                                val op = u16le(packet, 1)
                                val ret = if (op == BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE) {
                                    byteArrayOf(0x00, 0xFB.toByte(), 0x00, 0x08)
                                } else {
                                    byteArrayOf(0x00)
                                }
                                val ev = ByteArray(6 + ret.size)
                                ev[0] = 0x04
                                ev[1] = 0x0E
                                ev[2] = (3 + ret.size).toByte()
                                ev[3] = 0x01
                                putU16le(ev, 4, op)
                                ret.copyInto(ev, destinationOffset = 6)
                                rx.trySend(ev)
                            }
                            BleHciConstants.HCI_ACL_DATA_PKT -> {
                                val handle = u16le(packet, 1) and 0x0FFF
                                rx.trySend(
                                    byteArrayOf(
                                        0x04, 0x13, 0x05, 0x01,
                                        (handle and 0xFF).toByte(),
                                        ((handle ushr 8) and 0xFF).toByte(),
                                        0x01, 0x00
                                    )
                                )
                                if (u16le(packet, 7) == BleHciConstants.L2CAP_CID_ATT) {
                                    val len = u16le(packet, 5)
                                    attReplies.trySend(packet.copyOfRange(9, 9 + len))
                                }
                            }
                        }
                    }
                    override fun close() {
                        rx.close()
                    }
                }

                val hal = BlePeripheralHalHci(gapDeviceName = "LinuxWallet", packetIoFactory = { io })
                val added = Channel<Unit>(1)
                hal.setCallback(object : BlePeripheralHal.Callback {
                    override fun onPowerOn(error: Throwable?) {}
                    override fun onL2capPublished(psm: Int?, error: Throwable?) {}
                    override fun onServiceAdded(error: Throwable?) {
                        added.trySend(Unit)
                    }
                    override fun onAdvertisingStarted(error: Throwable?) {}
                    override fun onMtuChanged(mtu: Int) {}
                    override fun onCharacteristicReadRequest(requestId: Int, uuid: UUID) {}
                    override fun onCharacteristicWriteRequest(uuid: UUID, value: ByteArray, maxWriteSizeForCentral: Int?) {}
                    override fun onNotificationSent(uuid: UUID, error: Throwable?) {}
                    override fun onL2capClientConnected(channel: BleL2capChannel) {}
                    override fun onCentralDisconnected() {}
                    override fun onError(error: Throwable) {}
                })

                try {
                    hal.waitForPowerOn()
                    hal.setupGattServerAndAddService(
                        serviceUuid = UUID(1UL, 10UL),
                        stateCharacteristicUuid = UUID(1UL, 1UL),
                        client2ServerCharacteristicUuid = UUID(1UL, 2UL),
                        server2ClientCharacteristicUuid = UUID(1UL, 3UL),
                        identCharacteristicUuid = UUID(1UL, 4UL),
                        l2capCharacteristicUuid = null,
                        encodedL2capPsm = null
                    )
                    added.receive()

                    // Connect on handle 0x0000
                    val connEv = ByteArray(22).also {
                        it[0] = 0x04
                        it[1] = 0x3E
                        it[2] = 19
                        it[3] = 0x01
                    }
                    rx.trySend(connEv)

                    fun sendAtt(pdu: ByteArray) {
                        val acl = ByteArray(9 + pdu.size)
                        acl[0] = 0x02
                        putU16le(acl, 1, 0x0000 or (0x02 shl 12))
                        putU16le(acl, 3, 4 + pdu.size)
                        putU16le(acl, 5, pdu.size)
                        putU16le(acl, 7, BleHciConstants.L2CAP_CID_ATT)
                        pdu.copyInto(acl, destinationOffset = 9)
                        rx.trySend(acl)
                    }

                    // Query GAP Device Name (0x2A00) via ATT_READ_BY_TYPE_REQ
                    sendAtt(byteArrayOf(0x08, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x2A))
                    val nameRsp = attReplies.receive()
                    assertEquals(BleHciConstants.ATT_OP_READ_BY_TYPE_RSP, nameRsp[0].toInt() and 0xFF)
                    assertEquals("LinuxWallet", nameRsp.copyOfRange(4, nameRsp.size).decodeToString())

                    // Query GAP Primary Service (0x1800) via ATT_READ_BY_GROUP_TYPE_REQ starting after ISO service (0x000c..0xFFFF)
                    sendAtt(byteArrayOf(0x10, 0x0C, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x28))
                    val grpRsp = attReplies.receive()
                    assertEquals(BleHciConstants.ATT_OP_READ_BY_GROUP_TYPE_RSP, grpRsp[0].toInt() and 0xFF)
                    assertEquals(0x1800, u16le(grpRsp, 6))

                    // Reading State characteristic (handle 0x0003, no READ property) returns ATT_ERR_READ_NOT_PERMITTED (0x02)
                    sendAtt(byteArrayOf(0x0A, 0x03, 0x00))
                    val readErr = attReplies.receive()
                    assertContentEquals(
                        byteArrayOf(0x01, 0x0A, 0x03, 0x00, BleHciConstants.ATT_ERR_READ_NOT_PERMITTED.toByte()),
                        readErr
                    )

                    // Writing Ident characteristic (handle 0x000b, READ-only) returns ATT_ERR_WRITE_NOT_PERMITTED (0x03)
                    sendAtt(byteArrayOf(0x12, 0x0B, 0x00, 0x42))
                    val writeErr = attReplies.receive()
                    assertContentEquals(
                        byteArrayOf(0x01, 0x12, 0x0B, 0x00, BleHciConstants.ATT_ERR_WRITE_NOT_PERMITTED.toByte()),
                        writeErr
                    )
                } finally {
                    hal.close()
                }
            }

            // Part 2: Peripheral initiates ATT_EXCHANGE_MTU_REQ(517) first, then rejects Central's ATT_EXCHANGE_MTU_REQ
            run {
                val bus = VirtualHciBus(
                    faultConfig = FaultConfig(
                        injectPeripheralGapClientRequestsOnConnect = true,
                        failMtuExchangeAttempts = 1
                    )
                )
                val serviceUuid = UUID.randomUUID()
                val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
                val centralManager = createCentralManager(bus.centralIo)
                val peripheralManager = createPeripheralManager(bus.peripheralIo)
                val mdoc = BleTransportCentralMdoc(
                    role = MdocRole.MDOC,
                    options = MdocTransportOptions(bleUseL2CAP = false),
                    centralManager = centralManager,
                    uuid = serviceUuid,
                    psm = null
                )
                val reader = BleTransportCentralMdocReader(
                    role = MdocRole.MDOC_READER,
                    options = MdocTransportOptions(bleUseL2CAP = false),
                    peripheralManager = peripheralManager,
                    uuid = serviceUuid
                )
                try {
                    reader.advertise()
                    coroutineScope {
                        val r = async { reader.open(eSenderKey) }
                        val m = async { mdoc.open(eSenderKey) }
                        r.await()
                        m.await()
                    }
                    // Retained 515 MTU on first attempt without falling back to 23 or retrying!
                    assertEquals(515, mdoc.stats.bleNegotiatedMtu)
                    assertEquals(0, mdoc.stats.bleNumMtuRequestRetries)
                } finally {
                    mdoc.close()
                    reader.close()
                }
            }
        }
    }

    @Test
    fun l2capChannelEnforcesPeerCreditLimitAndReadBounds() = runTest {
        val bus = VirtualHciBus()
        val engine = BleHciEngine(packetIo = bus.centralIo)
        try {
            val channel = HciBleL2capChannel(
                engine = engine,
                connectionHandle = 1,
                localCid = 0x0040,
                peerCid = 0x0040,
                peerMtu = 512,
                peerMps = 247,
                initialPeerCredits = 1
            )

            assertFailsWith<IllegalArgumentException> {
                channel.read(ByteArray(4), offset = 3, length = 2)
            }

            // Sending 257 K-frames without reading (exceeding the 256-credit grant once backpressure
            // or exhaustion is reached; simulate buffered > 256 KB by sending large 247-byte K-frames
            // or exhausting credits directly) must disconnect and close the channel.
            val fullMpsPayload = ByteArray(245) { 0x42 }
            val singleSduKFrame = ByteArray(2 + fullMpsPayload.size).also {
                putU16le(it, 0, fullMpsPayload.size)
                fullMpsPayload.copyInto(it, destinationOffset = 2)
            }
            // Buffer > 256 KB (1050 * 245B = 257,250B) so replenishment pauses, then exhaust remaining credits.
            repeat(1400) {
                channel.onKFrameReceived(singleSduKFrame)
            }
            assertFailsWith<IOException> {
                channel.write(byteArrayOf(0x01))
            }
        } finally {
            engine.close()
            bus.centralIo.close()
            bus.peripheralIo.close()
        }
    }
}
