package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.decodeToString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.connectionmethod.BlePeripheralAddress
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.engagement.buildReaderEngagement
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcTransportMdoc
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.mdoc.transport.extractFromDo53
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.HandoverRequestRecord
import org.multipaz.nfc.HandoverSelectRecord
import org.multipaz.nfc.NdefMessage
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagReader
import org.multipaz.nfc.ResponseApdu
import org.multipaz.nfc.ServiceParameterRecord
import org.multipaz.nfc.ServiceSelectRecord
import org.multipaz.nfc.TnepStatusRecord
import org.multipaz.presentment.EngagementParams
import org.multipaz.presentment.FaceMatchingMode
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.ByteDataReader
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import org.multipaz.util.getUInt16
import org.multipaz.util.toHex
import kotlin.coroutines.CoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic protocol transcript tests for ISO/IEC 18013-5:2021 NFC Engagement (Static and
 * Negotiated Handover via NDEF Type 4 Tag) and Data Retrieval (BLE and NFC Data Transfer).
 *
 * ### Goals
 * - Verify the end-to-end wire format and state machine behavior of Multipaz's ISO/IEC 18013-5:2021
 *   NFC engagement and transport primitives ([scanMdocReader], [MdocNfcEngagementHelper],
 *   [NfcTransportMdoc], [NfcTransportMdocReader], and [Iso18013Presentment]).
 * - Capture a human-readable, deterministic transcript of every exchanged NFC APDU (`C-APDU` and
 *   `R-APDU`) and BLE transport message (`BLE Reader->Mdoc` and `BLE Mdoc->Reader`), preceded by
 *   `# `-prefixed explanatory prose comments, pretty-printed NDEF messages (including TNEP records,
 *   Handover Request / Handover Select records, Alternative Carrier records, embedded CBOR
 *   `DeviceEngagement` / `ReaderEngagement` structures, and BLE / NFC Carrier Configuration
 *   records), and CBOR diagnostic notation comments for `SessionEstablishment` and `SessionData`
 *   messages.
 * - Use fixed cryptographic keying material (`eDeviceKey`, `eReaderKey`, `dDeviceKey`) and MSO /
 *   mandatory mDL data elements from [TestVectors] so that every encrypted payload is 100%
 *   reproducible.
 *
 * ### Test Scenarios
 * 1. [testStaticHandoverBle]: NFC Static Handover offering BLE Central Client Mode and NFC Data
 *    Transfer, where the reader selects BLE Central Client Mode and completes the session over BLE.
 * 2. [testStaticHandoverNfcDataTransfer]: NFC Static Handover offering NFC Data Transfer, where the
 *    reader selects the ISO mdoc NFC Data Transfer application (`A0000002480400`) on the same tap
 *    and completes the session over NFC.
 * 3. [testNegotiatedHandoverBle]: NFC Negotiated Handover via TNEP where the reader offers BLE
 *    Central Client Mode and BLE Peripheral Server Mode in `HandoverRequest`, the mdoc selects BLE
 *    Central Client Mode (omitting the BLE UUID in `HandoverSelect` per ISO/IEC 18013-5:2021
 *    Clause 8.3.3.1.1.2), and the session completes over BLE.
 * 4. [testNegotiatedHandoverNfcDataTransfer]: NFC Negotiated Handover via TNEP where the reader
 *    offers BLE Central Client Mode and NFC Data Transfer in `HandoverRequest`, the mdoc selects
 *    NFC Data Transfer in `HandoverSelect`, and the session completes over the ISO mdoc NFC Data
 *    Transfer application (`A0000002480400`) on the same tap.
 * 5. [testNfcDataTransferApduCommandAndResponseChaining]: NFC Static Handover with NFC Data
 *    Transfer using small APDU command and response data limits (`commandDataFieldMaxLength = 100`,
 *    `responseDataFieldMaxLength = 900`), exercising ISO/IEC 7816-4 command chaining (`CLA = 0x10`
 *    -> `0x00`) for `SessionEstablishment` and response chaining (`6100` -> `6139` -> `9000` via
 *    `GET RESPONSE`) for `SessionData`.
 * 6. [testNfcDataTransferMultipleRequestsInSingleSession]: NFC Static Handover with NFC Data
 *    Transfer where the reader sends two sequential requests (`SessionEstablishment` for
 *    `family_name`, followed by `SessionData` for `document_number`) within a single NFC session
 *    before sending `SessionData` (`status = 20`) to terminate.
 * 7. [testReaderWithCceEnabledFallsBackToNdef]: Reader configured with
 *    `MdocReaderNfcHandoverOptions(useCce = true)` tapping an ISO/IEC 18013-5:2021 NDEF-only mdoc,
 *    verifying that after `SELECT` for the NFC CCE AID (`A0000002480401`) returns `6a82`, the
 *    reader seamlessly falls back to NDEF Negotiated Handover (`D2760000850101`) and completes the
 *    session over BLE.
 */
class MdocNfcV1TranscriptTest {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private class TranscriptLogger {
        private val mutex = Mutex()
        private val sb = StringBuilder()
        private var lastEntryWasCommandApdu = false

        private fun appendMultilineCommentLocked(text: String) {
            for (line in text.lines()) {
                if (line.isBlank()) {
                    sb.appendLine("#")
                } else {
                    sb.appendLine("# $line")
                }
            }
        }

        private fun appendCborDiagnosticCommentLocked(cborBytes: ByteArray) {
            val diag = Cbor.toDiagnostics(
                cborBytes,
                setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
            )
            appendMultilineCommentLocked(diag)
        }

        suspend fun appendCommandApdu(
            comment: String,
            detailComment: String? = null,
            cborBytes: ByteArray? = null,
            apduHex: String,
        ) {
            mutex.withLock {
                if (sb.isNotEmpty()) {
                    sb.appendLine()
                }
                sb.appendLine("# $comment")
                if (detailComment != null) {
                    appendMultilineCommentLocked(detailComment)
                }
                if (cborBytes != null && cborBytes.isNotEmpty()) {
                    appendCborDiagnosticCommentLocked(cborBytes)
                }
                sb.appendLine("C-APDU: $apduHex")
                lastEntryWasCommandApdu = true
            }
        }

        suspend fun appendResponseApdu(
            comment: String,
            detailComment: String? = null,
            cborBytes: ByteArray? = null,
            apduHex: String,
        ) {
            mutex.withLock {
                if (sb.isNotEmpty() && !lastEntryWasCommandApdu) {
                    sb.appendLine()
                }
                sb.appendLine("# $comment")
                if (detailComment != null) {
                    appendMultilineCommentLocked(detailComment)
                }
                if (cborBytes != null && cborBytes.isNotEmpty()) {
                    appendCborDiagnosticCommentLocked(cborBytes)
                }
                sb.appendLine("R-APDU: $apduHex")
                lastEntryWasCommandApdu = false
            }
        }

        suspend fun appendBleMessage(fromReader: Boolean, messageBytes: ByteArray) {
            mutex.withLock {
                if (sb.isNotEmpty()) {
                    sb.appendLine()
                }
                val item = Cbor.decode(messageBytes)
                val comment = if (fromReader) {
                    if (item.hasKey("eReaderKey")) {
                        "The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE."
                    } else if (item.hasKey("status")) {
                        val status = item["status"].asNumber
                        "The mdoc reader sends SessionData (status=$status) to terminate the session over BLE."
                    } else {
                        "The mdoc reader sends SessionData with encrypted DeviceRequest over BLE."
                    }
                } else {
                    "The mdoc sends SessionData with encrypted DeviceResponse over BLE."
                }
                sb.appendLine("# $comment")
                if (messageBytes.isNotEmpty()) {
                    appendCborDiagnosticCommentLocked(messageBytes)
                }
                val prefix = if (fromReader) "BLE Reader->Mdoc" else "BLE Mdoc->Reader"
                sb.appendLine("$prefix: ${messageBytes.toHex()}")
                lastEntryWasCommandApdu = false
            }
        }

        override fun toString(): String = sb.toString().trim()
    }

    private companion object {
        fun formatNdefMessage(message: NdefMessage, senderRole: MdocRole): String {
            val encodedSize = message.encode().size
            val sb = StringBuilder()
            sb.append("NDEF Message (${message.records.size} record(s), $encodedSize bytes):")
            message.records.forEachIndexed { index, record ->
                sb.append("\n  Record #${index + 1}:")
                sb.append("\n    TNF: ${record.tnf.name}")
                val typeStr = try {
                    record.type.decodeToString()
                } catch (_: Throwable) {
                    ""
                }
                val typeDisplay = if (typeStr.isNotEmpty()) "\"$typeStr\"" else "Empty"
                sb.append("\n    Type: $typeDisplay (Hex: ${record.type.toByteArray().toHex()})")
                if (record.id.size > 0) {
                    val idStr = try {
                        record.id.decodeToString()
                    } catch (_: Throwable) {
                        ""
                    }
                    val idDisplay = if (idStr.isNotEmpty()) {
                        "\"$idStr\" (Hex: ${record.id.toByteArray().toHex()})"
                    } else {
                        record.id.toByteArray().toHex()
                    }
                    sb.append("\n    ID: $idDisplay")
                }
                sb.append("\n    Payload Size: ${record.payload.size} bytes")
                val payloadFormatted = formatNdefRecordPayload(
                    record = record,
                    senderRole = senderRole,
                    allRecords = message.records,
                ).replace("\n", "\n    ")
                sb.append("\n    ").append(payloadFormatted)
            }
            return sb.toString()
        }

        private fun formatNdefRecordPayload(
            record: NdefRecord,
            senderRole: MdocRole,
            allRecords: List<NdefRecord> = emptyList(),
        ): String {
            HandoverRequestRecord.fromNdefRecord(record)?.let { hr ->
                val sb = StringBuilder("Handover Request:\n  Version: ${hr.version}\n  Embedded NDEF Message:")
                hr.embeddedMessage.records.forEachIndexed { i, r ->
                    sb.append("\n    Record #${i + 1}:")
                    sb.append("\n      TNF: ${r.tnf.name}")
                    val typeStr = try { r.type.decodeToString() } catch (_: Throwable) { "" }
                    sb.append("\n      Type: \"$typeStr\" (Hex: ${r.type.toByteArray().toHex()})")
                    sb.append("\n      Payload Size: ${r.payload.size} bytes")
                    val formatted = formatNdefRecordPayload(
                        record = r,
                        senderRole = senderRole,
                        allRecords = hr.embeddedMessage.records,
                    ).replace("\n", "\n      ")
                    sb.append("\n      ").append(formatted)
                }
                return sb.toString()
            }

            HandoverSelectRecord.fromNdefRecord(record)?.let { hs ->
                val sb = StringBuilder("Handover Select:\n  Version: ${hs.version}\n  Embedded NDEF Message:")
                hs.embeddedMessage.records.forEachIndexed { i, r ->
                    sb.append("\n    Record #${i + 1}:")
                    sb.append("\n      TNF: ${r.tnf.name}")
                    val typeStr = try { r.type.decodeToString() } catch (_: Throwable) { "" }
                    sb.append("\n      Type: \"$typeStr\" (Hex: ${r.type.toByteArray().toHex()})")
                    sb.append("\n      Payload Size: ${r.payload.size} bytes")
                    val formatted = formatNdefRecordPayload(
                        record = r,
                        senderRole = senderRole,
                        allRecords = hs.embeddedMessage.records,
                    ).replace("\n", "\n      ")
                    sb.append("\n      ").append(formatted)
                }
                return sb.toString()
            }

            ServiceParameterRecord.fromNdefRecord(record)?.let { sp ->
                return """
                    Service Parameter (TNEP):
                      TNEP Version: ${sp.tnepVersion}
                      Service Name URI: "${sp.serviceNameUri}"
                      Communication Mode: ${sp.tnepCommunicationMode}
                      wtInt (Waiting Time Code): ${sp.wtInt}
                      nWait: ${sp.nWait}
                      Max NDEF Message Size: ${sp.maxNdefSize} bytes
                """.trimIndent()
            }

            ServiceSelectRecord.fromNdefRecord(record)?.let { ss ->
                return "Service Select:\n  Service Name: \"${ss.serviceName}\""
            }

            TnepStatusRecord.fromNdefRecord(record)?.let { ts ->
                return "TNEP Status:\n  Status Code: ${ts.status}"
            }

            if (record.tnf == NdefRecord.Tnf.WELL_KNOWN && record.type == Nfc.RTD_ALTERNATIVE_CARRIER) {
                val reader = ByteDataReader(record.payload)
                val cps = reader.getUInt8().toInt()
                val cdrLen = reader.getUInt8().toInt()
                val cdr = reader.getByteString(cdrLen).decodeToString()
                val numAux = reader.getUInt8().toInt()
                val auxRefs = mutableListOf<String>()
                repeat(numAux) {
                    val auxLen = reader.getUInt8().toInt()
                    auxRefs.add("\"${reader.getByteString(auxLen).decodeToString()}\"")
                }
                return """
                    Alternative Carrier:
                      Carrier Power State: $cps
                      Carrier Data Reference: "$cdr"
                      Auxiliary Data References: [${auxRefs.joinToString(", ")}]
                """.trimIndent()
            }

            val typeStr = try { record.type.decodeToString() } catch (_: Throwable) { "" }
            if (record.tnf == NdefRecord.Tnf.EXTERNAL_TYPE &&
                (typeStr == "iso.org:18013:deviceengagement" ||
                    typeStr == "iso.org:18013:readerengagement" ||
                    typeStr == "iso.org:18013:chbleoptions")
            ) {
                val label = when {
                    typeStr.endsWith("deviceengagement") -> "DeviceEngagement CBOR:"
                    typeStr.endsWith("readerengagement") -> "ReaderEngagement CBOR:"
                    else -> "ChBleOptions CBOR:"
                }
                val diag = Cbor.toDiagnostics(
                    record.payload.toByteArray(),
                    setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
                ).replace("\n", "\n  ")
                return "$label\n  $diag"
            }

            if (record.tnf == NdefRecord.Tnf.MIME_MEDIA || record.tnf == NdefRecord.Tnf.EXTERNAL_TYPE) {
                val cm = MdocConnectionMethod.fromNdefRecord(
                    record = record,
                    role = senderRole,
                    uuid = null,
                    auxiliaryRecords = allRecords,
                )
                if (cm != null) {
                    return "Carrier Configuration: $cm\nPayload Hex: ${record.payload.toByteArray().toHex(byteDivider = " ")}"
                }
            }

            return "Payload Hex:\n${record.payload.toByteArray().toHex(byteDivider = " ")}"
        }
    }

    /**
     * Loopback [NfcIsoTag] that routes NDEF Type 4 Tag APDUs (`D2760000850101`) to
     * [MdocNfcEngagementHelper.processApdu] and ISO mdoc NFC Data Transfer APDUs (`A0000002480400`)
     * to [NfcTransportMdoc.processCommandApdu], recording a human-readable transcript of every
     * `C-APDU` and `R-APDU` along with pretty-printed NDEF messages and CBOR diagnostic notation.
     */
    private class TranscriptRecordingNfcIsoTag(
        private val engagementHelper: MdocNfcEngagementHelper,
        val transcript: TranscriptLogger = TranscriptLogger(),
    ) : NfcIsoTag() {
        val closedDeferred = CompletableDeferred<Unit>()

        private var activeAid: ByteString? = null
        private var selectedFileId: Int = 0
        private var wasCommandChained: Boolean = false
        private var incomingDo53Builder = ByteStringBuilder()
        private var outgoingDo53Builder = ByteStringBuilder()
        private val ndefWriteBuffer = ByteArray(4096)

        override val maxTransceiveLength: Int = 65536

        override suspend fun close() {
            if (activeAid == Nfc.NDEF_APPLICATION_ID) {
                engagementHelper.processDeactivated(NfcDeactivationReason.DESELECTED)
            } else if (activeAid == Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID) {
                NfcTransportMdoc.onDeactivated()
            }
            activeAid = null
            closedDeferred.complete(Unit)
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            var commandDetailComment: String? = null
            var commandCborBytes: ByteArray? = null
            val commandComment: String

            if (command.isApplicationSelect) {
                val newAid = command.payload
                val previousAid = activeAid
                if (previousAid != null && previousAid != newAid) {
                    if (previousAid == Nfc.NDEF_APPLICATION_ID) {
                        engagementHelper.processDeactivated(NfcDeactivationReason.DESELECTED)
                    } else if (previousAid == Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID) {
                        NfcTransportMdoc.onDeactivated()
                    }
                }
                activeAid = newAid
                selectedFileId = 0
                commandComment = when (newAid) {
                    Nfc.NDEF_APPLICATION_ID ->
                        "The mdoc reader selects the NDEF Type 4 Tag application."
                    Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID ->
                        "The mdoc reader selects the ISO mdoc NFC data transfer application."
                    Nfc.ISO_MDOC_NFC_CCE_APPLICATION_ID ->
                        "The mdoc reader attempts to select the NFC CCE application."
                    else ->
                        "The mdoc reader selects application ${newAid.toHex()}."
                }
            } else {
                when (command.ins) {
                    Nfc.INS_SELECT -> {
                        selectedFileId = command.payload.getUInt16(0).toInt()
                        commandComment = when (selectedFileId) {
                            Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID ->
                                "The mdoc reader selects the Capability Container (CC) file (0xe103)."
                            0xe104 ->
                                "The mdoc reader selects the NDEF file (0xe104)."
                            else ->
                                "The mdoc reader selects file 0x${selectedFileId.toString(16)}."
                        }
                    }
                    Nfc.INS_READ_BINARY -> {
                        val offset = command.p1 * 0x100 + command.p2
                        val length = command.le
                        commandComment = when {
                            selectedFileId == Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID ->
                                "The mdoc reader reads the $length-byte Capability Container (CC) file."
                            offset == 0 && length == 2 ->
                                "The mdoc reader reads the 2-byte NDEF message length (NLEN)."
                            else ->
                                "The mdoc reader reads the $length-byte NDEF message."
                        }
                    }
                    0xd6 -> {
                        val offset = command.p1 * 0x100 + command.p2
                        command.payload.toByteArray().copyInto(ndefWriteBuffer, destinationOffset = offset)
                        if (offset == 0 && command.payload.size > 2) {
                            val nlen = command.payload.getUInt16(0).toInt()
                            val ndefMessage = NdefMessage.fromEncoded(command.payload.toByteArray(2))
                            commandDetailComment = formatNdefMessage(ndefMessage, MdocRole.MDOC_READER)
                            val firstRecord = ndefMessage.records.first()
                            commandComment = when {
                                ServiceSelectRecord.fromNdefRecord(firstRecord) != null ->
                                    "The mdoc reader writes the TNEP Service Select NDEF message (NLEN=$nlen)."
                                HandoverRequestRecord.fromNdefRecord(firstRecord) != null ->
                                    "The mdoc reader writes the NDEF Handover Request message (NLEN=$nlen)."
                                else ->
                                    "The mdoc reader writes an NDEF message (NLEN=$nlen)."
                            }
                        } else if (offset == 0 && command.payload.size == 2) {
                            val nlen = command.payload.getUInt16(0).toInt()
                            if (nlen == 0) {
                                commandComment =
                                    "The mdoc reader clears NLEN (0 bytes) before multi-chunk NDEF write."
                            } else {
                                val ndefMessage = NdefMessage.fromEncoded(ndefWriteBuffer.copyOfRange(2, 2 + nlen))
                                commandDetailComment = formatNdefMessage(ndefMessage, MdocRole.MDOC_READER)
                                val firstRecord = ndefMessage.records.first()
                                commandComment = when {
                                    HandoverRequestRecord.fromNdefRecord(firstRecord) != null ->
                                        "The mdoc reader finalizes NLEN=$nlen completing the NDEF Handover Request message."
                                    else ->
                                        "The mdoc reader finalizes NLEN=$nlen completing the NDEF message."
                                }
                            }
                        } else {
                            commandComment = "The mdoc reader updates binary data at offset $offset (${command.payload.size} bytes)."
                        }
                    }
                    Nfc.INS_ENVELOPE -> {
                        incomingDo53Builder.append(command.payload)
                        if (command.cla == Nfc.CLA_CHAIN_NOT_LAST) {
                            wasCommandChained = true
                            commandComment =
                                "The mdoc reader sends a command-chained ENVELOPE chunk (${command.payload.size} bytes, CLA=0x10)."
                        } else {
                            val chained = wasCommandChained
                            wasCommandChained = false
                            commandCborBytes = extractFromDo53(incomingDo53Builder.toByteString()).toByteArray()
                            incomingDo53Builder = ByteStringBuilder()
                            val item = Cbor.decode(commandCborBytes)
                            commandComment = if (chained) {
                                if (item.hasKey("eReaderKey")) {
                                    "The mdoc reader sends the final command-chained ENVELOPE chunk (${command.payload.size} bytes, CLA=0x00) completing SessionEstablishment with eReaderKey and encrypted DeviceRequest."
                                } else {
                                    "The mdoc reader sends the final command-chained ENVELOPE chunk (${command.payload.size} bytes, CLA=0x00) completing SessionData with encrypted DeviceRequest."
                                }
                            } else if (item.hasKey("eReaderKey")) {
                                "The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over NFC."
                            } else if (item.hasKey("status")) {
                                val status = item["status"].asNumber
                                "The mdoc reader sends SessionData (status=$status) to terminate the session over NFC."
                            } else {
                                "The mdoc reader sends SessionData with encrypted DeviceRequest over NFC."
                            }
                        }
                    }
                    Nfc.INS_GET_RESPONSE -> {
                        commandComment = "The mdoc reader sends GET RESPONSE requesting up to ${command.le} bytes."
                    }
                    else -> error("Unexpected INS: ${command.ins}")
                }
            }
            transcript.appendCommandApdu(
                comment = commandComment,
                detailComment = commandDetailComment,
                cborBytes = commandCborBytes,
                apduHex = command.encode().toHex(),
            )

            val response = when (activeAid) {
                Nfc.NDEF_APPLICATION_ID -> engagementHelper.processApdu(command)
                Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID -> NfcTransportMdoc.processCommandApdu(command)
                else -> ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
            }

            var responseDetailComment: String? = null
            var responseCborBytes: ByteArray? = null
            val responseComment: String

            if (command.isApplicationSelect) {
                responseComment = if (response.status == Nfc.RESPONSE_STATUS_SUCCESS) {
                    "The mdoc acknowledges the application selection."
                } else {
                    val statusHex = response.status.toString(16).padStart(4, '0')
                    "The mdoc responds with status $statusHex (application not found)."
                }
            } else {
                when (command.ins) {
                    Nfc.INS_SELECT -> {
                        responseComment = "The mdoc acknowledges the file selection."
                    }
                    Nfc.INS_READ_BINARY -> {
                        val offset = command.p1 * 0x100 + command.p2
                        val length = command.le
                        if (selectedFileId == Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID) {
                            val writeAccess = response.payload[14].toInt() and 0xff
                            val accessDesc = if (writeAccess == 0x00) "read-write" else "read-only"
                            responseComment =
                                "The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, $accessDesc)."
                        } else if (offset == 0 && length == 2) {
                            val nlen = response.payload.getUInt16(0).toInt()
                            responseComment = "The mdoc returns NLEN=$nlen bytes."
                        } else {
                            val ndefMessage = NdefMessage.fromEncoded(response.payload.toByteArray())
                            responseDetailComment = formatNdefMessage(ndefMessage, MdocRole.MDOC)
                            val firstRecord = ndefMessage.records.first()
                            responseComment = when {
                                ServiceParameterRecord.fromNdefRecord(firstRecord) != null ->
                                    "The mdoc returns the initial NDEF message with the TNEP Service Parameter record."
                                TnepStatusRecord.fromNdefRecord(firstRecord) != null ->
                                    "The mdoc returns the TNEP Status NDEF message."
                                HandoverSelectRecord.fromNdefRecord(firstRecord) != null ->
                                    "The mdoc returns the NDEF Handover Select message."
                                else ->
                                    "The mdoc returns the NDEF message."
                            }
                        }
                    }
                    0xd6 -> {
                        responseComment = "The mdoc acknowledges the NDEF write."
                    }
                    Nfc.INS_ENVELOPE -> {
                        if (command.cla == Nfc.CLA_CHAIN_NOT_LAST) {
                            responseComment = "The mdoc acknowledges the command-chained chunk."
                        } else {
                            outgoingDo53Builder.append(response.payload)
                            if (response.status == Nfc.RESPONSE_STATUS_SUCCESS) {
                                val encapsulated = outgoingDo53Builder.toByteString()
                                outgoingDo53Builder = ByteStringBuilder()
                                if (encapsulated.size > 0) {
                                    val extracted = extractFromDo53(encapsulated)
                                    if (extracted.size > 0) {
                                        responseCborBytes = extracted.toByteArray()
                                    }
                                }
                                responseComment = if (responseCborBytes == null) {
                                    "The mdoc responds with an empty DO'53' acknowledging session termination."
                                } else {
                                    "The mdoc responds with SessionData containing encrypted DeviceResponse."
                                }
                            } else {
                                val statusHex = response.status.toString(16).padStart(4, '0')
                                responseComment =
                                    "The mdoc returns a response chunk (${response.payload.size} bytes) with status $statusHex (more data available)."
                            }
                        }
                    }
                    Nfc.INS_GET_RESPONSE -> {
                        outgoingDo53Builder.append(response.payload)
                        if (response.status == Nfc.RESPONSE_STATUS_SUCCESS) {
                            val encapsulated = outgoingDo53Builder.toByteString()
                            outgoingDo53Builder = ByteStringBuilder()
                            if (encapsulated.size > 0) {
                                val extracted = extractFromDo53(encapsulated)
                                if (extracted.size > 0) {
                                    responseCborBytes = extracted.toByteArray()
                                }
                            }
                            responseComment =
                                "The mdoc returns the final response chunk (${response.payload.size} bytes) completing SessionData with encrypted DeviceResponse."
                        } else {
                            val statusHex = response.status.toString(16).padStart(4, '0')
                            responseComment =
                                "The mdoc returns a response chunk (${response.payload.size} bytes) with status $statusHex (more data available)."
                        }
                    }
                    else -> error("Unexpected INS: ${command.ins}")
                }
            }
            transcript.appendResponseApdu(
                comment = responseComment,
                detailComment = responseDetailComment,
                cborBytes = responseCborBytes,
                apduHex = response.encode().toHex(),
            )
            return response
        }
    }

    /**
     * [MdocTransportFactory] that uses an in-memory loopback channel for [MdocConnectionMethodBle]
     * (recording BLE messages to [transcript]) and delegates [MdocConnectionMethodNfc] to
     * [MdocTransportFactory.Default] (creating real [NfcTransportMdoc] and [NfcTransportMdocReader]
     * instances).
     */
    private class TestTransportFactory(
        val transcript: TranscriptLogger,
    ) : MdocTransportFactory {
        private class ChannelPair {
            val readerToMdocChannel = Channel<ByteArray>(Channel.UNLIMITED)
            val mdocToReaderChannel = Channel<ByteArray>(Channel.UNLIMITED)
        }

        private val channelPairs = mutableMapOf<Pair<Boolean, Boolean>, ChannelPair>()

        private fun getChannelPair(cm: MdocConnectionMethodBle): ChannelPair =
            channelPairs.getOrPut(Pair(cm.hasCentralMode, cm.hasPeripheralMode)) {
                ChannelPair()
            }

        override fun createTransport(
            connectionMethod: MdocConnectionMethod,
            role: MdocRole,
            options: MdocTransportOptions
        ): MdocTransport = when (connectionMethod) {
            is MdocConnectionMethodBle -> BleEndpoint(connectionMethod, role, getChannelPair(connectionMethod))
            is MdocConnectionMethodNfc -> when (role) {
                MdocRole.MDOC -> NfcTransportMdoc(role, options, connectionMethod)
                MdocRole.MDOC_READER -> NfcTransportMdocReader(role, options, connectionMethod)
            }
            else -> error("Unsupported connection method: $connectionMethod")
        }

        private inner class BleEndpoint(
            override val connectionMethod: MdocConnectionMethodBle,
            override val role: MdocRole,
            private val pair: ChannelPair,
        ) : MdocTransport() {
            private val _state = MutableStateFlow(State.IDLE)
            override val state: StateFlow<State> = _state.asStateFlow()
            override val scanningTime: Duration? = null
            private var wasOpened = false

            override suspend fun advertise() {
                if (_state.value == State.IDLE) {
                    _state.value = State.ADVERTISING
                }
            }

            override suspend fun open(eSenderKey: EcPublicKey) {
                wasOpened = true
                _state.value = State.CONNECTED
            }

            override suspend fun sendMessage(message: ByteArray) {
                if (role == MdocRole.MDOC_READER) {
                    transcript.appendBleMessage(fromReader = true, messageBytes = message)
                    pair.readerToMdocChannel.trySend(message)
                } else {
                    transcript.appendBleMessage(fromReader = false, messageBytes = message)
                    pair.mdocToReaderChannel.trySend(message)
                }
            }

            override suspend fun waitForMessage(): ByteArray {
                val ch = if (role == MdocRole.MDOC_READER) pair.mdocToReaderChannel else pair.readerToMdocChannel
                return try {
                    ch.receive()
                } catch (e: Throwable) {
                    throw MdocTransportClosedException("BLE transport closed", e)
                }
            }

            override suspend fun close() {
                _state.value = State.CLOSED
                if (wasOpened) {
                    if (role == MdocRole.MDOC_READER) {
                        pair.mdocToReaderChannel.close()
                    } else {
                        pair.readerToMdocChannel.close()
                    }
                }
            }
        }
    }

    private class SingleTagNfcTagReader(
        private val tag: NfcIsoTag
    ) : NfcTagReader {
        override val external: Boolean = false
        override val dialogAlwaysShown: Boolean = false

        override suspend fun <T : Any> scan(
            message: String?,
            tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
            options: NfcScanOptions,
            context: CoroutineContext
        ): T {
            return checkNotNull(tagInteractionFunc(tag))
        }
    }

    private class StaticTestPresentmentSource(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
    ) : PresentmentSource(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository,
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
                onDocumentsInFocus = onDocumentsInFocus,
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
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_Y.fromHex(),
    )

    private fun getAnnexDEReaderKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex(),
    )

    private fun getAnnexDStaticDeviceKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex(),
    )

    private fun getStaticBleCentralClientMethod(): MdocConnectionMethodBle = MdocConnectionMethodBle(
        gattPeripheral = false,
        gattCentral = true,
        peripheralModeUuid = null,
        centralModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
    )

    private fun getStaticBlePeripheralServerMethod(): MdocConnectionMethodBle = MdocConnectionMethodBle(
        gattPeripheral = true,
        gattCentral = false,
        peripheralModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        centralModeUuid = null,
    )

    private fun getStaticNfcConnectionMethod(
        commandMax: Long = 65535L,
        responseMax: Long = 65536L,
    ): MdocConnectionMethodNfc = MdocConnectionMethodNfc(
        commandDataFieldMaxLength = commandMax,
        responseDataFieldMaxLength = responseMax,
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
                put(DrivingLicense.MDL_NAMESPACE, buildCborArray {
                    mandatoryItems.forEach { add(it) }
                })
            }
            put("issuerAuth", annexDIssuerSigned["issuerAuth"])
        }
        credential.certify(ByteString(Cbor.encode(issuerSigned)))

        return StaticTestPresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
        )
    }

    private suspend fun runSingleRequestScenario(
        staticHandoverMethods: List<MdocConnectionMethod>? = null,
        staticMdocMethodSelector: (List<MdocConnectionMethod>) -> MdocConnectionMethod = { it.first() },
        negotiatedHandoverPicker: (suspend (List<MdocConnectionMethod>) -> MdocConnectionMethod)? = null,
        readerNegotiatedMethods: List<MdocConnectionMethod> = emptyList(),
        readerSelectConnectionMethod: suspend (List<MdocConnectionMethod>) -> MdocConnectionMethod? = { it.firstOrNull() },
        handoverOptions: MdocReaderNfcHandoverOptions = MdocReaderNfcHandoverOptions(useCce = false),
        expectedHandoverType: MdocHandoverType,
    ): String {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val presentmentSource = createAnnexDPresentmentSource()
        val transcriptLogger = TranscriptLogger()
        val transportFactory = TestTransportFactory(transcriptLogger)
        val presentmentDone = CompletableDeferred<Unit>()
        lateinit var mdocTransport: MdocTransport

        val scope = CoroutineScope(Dispatchers.Default)
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                val chosenMethod = if (connectionMethods.size == 1) {
                    connectionMethods.first()
                } else {
                    staticMdocMethodSelector(connectionMethods)
                }
                mdocTransport = transportFactory.createTransport(
                    connectionMethod = chosenMethod,
                    role = MdocRole.MDOC,
                    options = MdocTransportOptions(),
                )
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = null,
                )
                val activeEngagementParamsFlow = MutableStateFlow(params)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    mdocTransport.open(eDeviceKey.publicKey)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = false,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcEngagementHelper: $error")
            },
            staticHandoverMethods = staticHandoverMethods,
            negotiatedHandoverPicker = negotiatedHandoverPicker,
        )

        val tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
        )
        val tagReader = SingleTagNfcTagReader(tag)

        withContext(Dispatchers.Default) {
            val scanResult = tagReader.scanMdocReader(
                message = null,
                options = MdocTransportOptions(),
                handoverOptions = handoverOptions,
                transportFactory = transportFactory,
                selectConnectionMethod = readerSelectConnectionMethod,
                negotiatedHandoverConnectionMethods = readerNegotiatedMethods,
                context = Dispatchers.Default,
                onHandover = { it }
            )
            assertNotNull(scanResult)
            assertEquals(expectedHandoverType, scanResult.type)
            assertNull(scanResult.eReaderKey)

            val readerTransport = scanResult.transport
            val deviceEngagement = DeviceEngagement.fromDataItem(
                Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
            )
            readerTransport.open(deviceEngagement.eDeviceKey)

            val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
            val sessionTranscript = buildCborArray {
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
                add(scanResult.handover)
            }
            val readerEncryption = SessionEncryption(
                role = MdocRole.MDOC_READER,
                eSelfKey = eReaderKey,
                remotePublicKey = deviceEngagement.eDeviceKey,
                encodedSessionTranscript = Cbor.encode(sessionTranscript),
            )

            val deviceRequest = buildDeviceRequest(
                sessionTranscript = sessionTranscript,
                version = "1.0",
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                    )
                )
            }

            val encryptedRequest = readerEncryption.encryptMessage(
                messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                statusCode = null,
            )
            readerTransport.sendMessage(encryptedRequest)

            val encryptedResponse = readerTransport.waitForMessage()
            val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
            assertNull(status)
            assertNotNull(encodedDeviceResponse)

            val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
            deviceResponse.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
            )

            val terminationMessage = readerEncryption.encryptMessage(
                messagePlaintext = null,
                statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            )
            readerTransport.sendMessage(terminationMessage)

            withTimeout(5.seconds) {
                presentmentDone.await()
            }
            readerTransport.close()
            mdocTransport.close()
            withTimeout(5.seconds) {
                tag.closedDeferred.await()
            }
        }

        return transcriptLogger.toString()
    }

    /**
     * Verifies ISO/IEC 18013-5:2021 NFC Static Handover with BLE data retrieval:
     * 1. The mdoc reader selects the NDEF Type 4 Tag application (`D2760000850101`), reads the
     *    15-byte Capability Container (`0xE103`, read-only), and reads the NDEF Handover Select
     *    (`Hs`) message from `0xE104` advertising BLE Central Client mode and NFC data transfer.
     * 2. The mdoc reader selects BLE Central Client mode, closes the NFC tag, and exchanges
     *    `SessionEstablishment` / `SessionData` over BLE.
     */
    @Test
    fun testStaticHandoverBle() = runTest {
        val bleCc = getStaticBleCentralClientMethod()
        val nfcCm = getStaticNfcConnectionMethod()
        val transcript = runSingleRequestScenario(
            staticHandoverMethods = listOf(bleCc, nfcCm),
            readerSelectConnectionMethod = { methods -> methods.filterIsInstance<MdocConnectionMethodBle>().first() },
            expectedHandoverType = MdocHandoverType.STATIC_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-only).
            R-APDU: 000f207fff7fff0406e1047fff00ff9000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=262 bytes.
            R-APDU: 01069000

            # The mdoc reader reads the 262-byte NDEF message.
            C-APDU: 00b00002000106
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (4 record(s), 262 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 31 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 9 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc"]
            #         Record #2:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 11 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 01 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            R-APDU: 91021f487315910209616301013001046d646f6351020b616301036e666301046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f51a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c011107e6733397764c6b89ce4823a1010000005c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'ba4f752e623392990fdebce3918a37298e7da5af25949932058492468ef4647e77327dbbc8a9baa2eff610c2bcaf06fc26fb5c27bea95422c26fad127e0b5ec5e0bbc0c65a25d2a2967f92de69f36e49038a8dfb3469aadf833a2d56d4680861c7687469cd114b04c3aef3014b151145b303c00066217570c3541dfc6eeb011edec308103885f6'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887ba4f752e623392990fdebce3918a37298e7da5af25949932058492468ef4647e77327dbbc8a9baa2eff610c2bcaf06fc26fb5c27bea95422c26fad127e0b5ec5e0bbc0c65a25d2a2967f92de69f36e49038a8dfb3469aadf833a2d56d4680861c7687469cd114b04c3aef3014b151145b303c00066217570c3541dfc6eeb011edec308103885f6

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'2c67bb268431614b219361f21d4cc399ef18c1bb98385879af1e0ac306acef3aac8bab82c92ebd7c04d4b9a2ccdc2eb4547c13fcac445126866078e2cafc66b4acc4dfc855950c5d7161536e8a711c9ac728d43fa179b49549ff891753d241438a168fdae77ec7eb2222e2b73dbfedd51265336f4c4e20de3b7d10a836feef931c41c89858220646ac880be7a78228a349c0230ee8539634b5166665aecfea1911f67bef57dccfe061ca4fe4e13088b7e6c26be81f9ebbc0c0ed15bdd19f3e34721d8af71b6454eb78e9070255eb269a9e0fba33c818b18a558c0e2a25c3c6f4fd8978a0b7281df5c587668904b829d1c715aa1ad43175f89855180be8740dbc2de472886e280f1290753cc6cdce0183d27a8a3c7aef4edacd9cfdedf8c7b3770bb8a2be21e132b1df946a4e686c9058a6fcd794a62d2034d17ab62e836410413f6c5ffc92ffbe76ec202979602c9966d0f4fef2857d002b413d9429f7fb05bb23bd80557b431303d5e69cc2cb233cc420566d48871d905fddcb6122b91647db4aa69522330899399f65b8f4f99cb7c267c4371d096e47c8fb2cf0e563f986e042ef5b2108935665bf016026b9bdbff04df134b29761000eecee37d8b689dd8996c760664458dba39bc7b7a7c01b2f8904fcbd076f997342bd4b8bfd4d03975e796c81b404ad78a3b31668ef046172f20ecece70fa8a609da53ecad879f516a2f4620d2e74ef73c3a0253375660b83dc20438a841193e4aeb9b258a23f5892bd9933d8bbac567911be152f02cdd26b5c2678bbe3820f567fad6cbf9d102dda9a57a1c8c2bf0b1ca0aca6432c82140e016cfae679b9733eec13098b00d6a253b21f96832d6a9e82eb7528c2b452cb24c0568e7b66ae98d29248f7da80aa8eb9c18836e79046ee4e03cd9798d829a6a4368100d754129b2ead48fef8feca9a4c588836f7db77b6d5d7d4976992ec88cd1427e02c8f4fbc219188f08dd3feab0c4fae9e763ad37c7816134188d9ff519fc332cc84bb51b28f93178d0eeca4c63c312b50d08c8363c79f0ea35e06d160ca93148c1fec752589e80237d5e76539a8553da1d5e621b241c3955b014029ac2eddeb1234e1a86a569dc3e6e2cc7ed1d437d8e75460e90fdd4423f527097fadd5a4a898faa35ff2a7b652dd9287133789a361e718f095947d470f51ac6801cf517b700d220c3a0ef500bbc304d8c0d2de812e6b4d3269d03b2ad741611ce5a70b9f93b71cd5fe43c4127c9ae8088794e553485ae485f7563433b74c6b7510242e7d79021ba59e118f8d34c54ce801ddc717603aedc8182dd27bf3617d3fab51317969864cb47eceab9ec17a6a1ebf8f2fd319a54cf95b499bab7b5c886469bd2091f3fbfb059ab8c729110c054c16e66a3996cf30ccd3a5786b78072e877f35fa6158b2c072fd6fde5dc3c272c99168ff5933cdcfb871069eb4dfa301f79638db41a7f41295f22aeee36ea372589ef9289d9598cee706ee457d5959ea27ca0b54fd45afa3fd24d1e53e1cda403407f6c839ce6e1c5f08a9cefc41412abf5dd61118a350f78fc56d2933b5d071d0a52a269b2ed501ee2a7bb367df17962293ef1effbfcab098dc60aa013714d6d1abfe37df55ea05d5d1f2b59b2809df57369e0fa4887de442411e1615eb2404d1924a03a81b9c7de4fdf66cd0d5f928429df677cfc2ce1aa9662580e41f7ba5227d96d4dd984963924414852d45fef7a6b9cb60908390dcdde61d44dcba769902653de4d2f44fd0a5c5283b295efb1607e322ea8365c67f5cac6721d70046d1dfeeb626494601cb879941cfefdbf5b934a1bb59993bb9c3ebb93e9087e4dc85735fe44d58a67a954756a06a92a86470bb459c07b5316aaabfe806d618f2e106e4ad6342c666ce72183bf9880c2162c586465832f8085afbd47bb86b5a0d47bb58eba79780f1d84adfbeb3a4e9697efa03a18371b9e176f9fb8238265e56b2588b3f72b1b6f8c7e1cb30b0a74ee9d24838dd297615deebee9f9aa7ee405720f8f8f6cc17ea355fb58a9e744c1e6298c6fb33bc5f07763c468e1ec007e738d329dfb641f84a102bd19f7f71340883ce0b73571ea072b47b0460247c38085744526039e0a43c6c6b74f1e527e6d47701a732af44a49883863123f97cf410882ed859f834ff7494b61fd8f1daf210f36632594361b9b83371151c296f953a37582c680a260d68efa5201a00d0912cf6544a10b9382203680774ed1b6fbb1f9f39bba2018603143e800e1cac5c48ebd2b053db8655f22ef8173bbbeadfd08961b7be0a98f892568912d28ab4163f455cbd5aec2a25e81cbb7baf2344be66f74db5f9d4a8ebe43fc0ccbe2d4bfc43a1a6652bb37a8b61058684fff13b07806427bb823a36e386e8ad97a5fec233f9136ea7c55d906c2cd016855e9ff0f3775ee62094e15e6b5d7b9c9955feed73a7fd76d37927a88608e3c4770e024e46a900317142e7af58eb83775e2f22e876b86aa308fe6494ae43877042e57f0d0f2c79c06fc27a22c31b887a33ebc295ab4a1dcc31962a168fce55efbf636cfc90e04f70e8025ec1e9a1333906f89711a63b6b85e68547ebda21'
            # }
            BLE Mdoc->Reader: a1646461746159072f2c67bb268431614b219361f21d4cc399ef18c1bb98385879af1e0ac306acef3aac8bab82c92ebd7c04d4b9a2ccdc2eb4547c13fcac445126866078e2cafc66b4acc4dfc855950c5d7161536e8a711c9ac728d43fa179b49549ff891753d241438a168fdae77ec7eb2222e2b73dbfedd51265336f4c4e20de3b7d10a836feef931c41c89858220646ac880be7a78228a349c0230ee8539634b5166665aecfea1911f67bef57dccfe061ca4fe4e13088b7e6c26be81f9ebbc0c0ed15bdd19f3e34721d8af71b6454eb78e9070255eb269a9e0fba33c818b18a558c0e2a25c3c6f4fd8978a0b7281df5c587668904b829d1c715aa1ad43175f89855180be8740dbc2de472886e280f1290753cc6cdce0183d27a8a3c7aef4edacd9cfdedf8c7b3770bb8a2be21e132b1df946a4e686c9058a6fcd794a62d2034d17ab62e836410413f6c5ffc92ffbe76ec202979602c9966d0f4fef2857d002b413d9429f7fb05bb23bd80557b431303d5e69cc2cb233cc420566d48871d905fddcb6122b91647db4aa69522330899399f65b8f4f99cb7c267c4371d096e47c8fb2cf0e563f986e042ef5b2108935665bf016026b9bdbff04df134b29761000eecee37d8b689dd8996c760664458dba39bc7b7a7c01b2f8904fcbd076f997342bd4b8bfd4d03975e796c81b404ad78a3b31668ef046172f20ecece70fa8a609da53ecad879f516a2f4620d2e74ef73c3a0253375660b83dc20438a841193e4aeb9b258a23f5892bd9933d8bbac567911be152f02cdd26b5c2678bbe3820f567fad6cbf9d102dda9a57a1c8c2bf0b1ca0aca6432c82140e016cfae679b9733eec13098b00d6a253b21f96832d6a9e82eb7528c2b452cb24c0568e7b66ae98d29248f7da80aa8eb9c18836e79046ee4e03cd9798d829a6a4368100d754129b2ead48fef8feca9a4c588836f7db77b6d5d7d4976992ec88cd1427e02c8f4fbc219188f08dd3feab0c4fae9e763ad37c7816134188d9ff519fc332cc84bb51b28f93178d0eeca4c63c312b50d08c8363c79f0ea35e06d160ca93148c1fec752589e80237d5e76539a8553da1d5e621b241c3955b014029ac2eddeb1234e1a86a569dc3e6e2cc7ed1d437d8e75460e90fdd4423f527097fadd5a4a898faa35ff2a7b652dd9287133789a361e718f095947d470f51ac6801cf517b700d220c3a0ef500bbc304d8c0d2de812e6b4d3269d03b2ad741611ce5a70b9f93b71cd5fe43c4127c9ae8088794e553485ae485f7563433b74c6b7510242e7d79021ba59e118f8d34c54ce801ddc717603aedc8182dd27bf3617d3fab51317969864cb47eceab9ec17a6a1ebf8f2fd319a54cf95b499bab7b5c886469bd2091f3fbfb059ab8c729110c054c16e66a3996cf30ccd3a5786b78072e877f35fa6158b2c072fd6fde5dc3c272c99168ff5933cdcfb871069eb4dfa301f79638db41a7f41295f22aeee36ea372589ef9289d9598cee706ee457d5959ea27ca0b54fd45afa3fd24d1e53e1cda403407f6c839ce6e1c5f08a9cefc41412abf5dd61118a350f78fc56d2933b5d071d0a52a269b2ed501ee2a7bb367df17962293ef1effbfcab098dc60aa013714d6d1abfe37df55ea05d5d1f2b59b2809df57369e0fa4887de442411e1615eb2404d1924a03a81b9c7de4fdf66cd0d5f928429df677cfc2ce1aa9662580e41f7ba5227d96d4dd984963924414852d45fef7a6b9cb60908390dcdde61d44dcba769902653de4d2f44fd0a5c5283b295efb1607e322ea8365c67f5cac6721d70046d1dfeeb626494601cb879941cfefdbf5b934a1bb59993bb9c3ebb93e9087e4dc85735fe44d58a67a954756a06a92a86470bb459c07b5316aaabfe806d618f2e106e4ad6342c666ce72183bf9880c2162c586465832f8085afbd47bb86b5a0d47bb58eba79780f1d84adfbeb3a4e9697efa03a18371b9e176f9fb8238265e56b2588b3f72b1b6f8c7e1cb30b0a74ee9d24838dd297615deebee9f9aa7ee405720f8f8f6cc17ea355fb58a9e744c1e6298c6fb33bc5f07763c468e1ec007e738d329dfb641f84a102bd19f7f71340883ce0b73571ea072b47b0460247c38085744526039e0a43c6c6b74f1e527e6d47701a732af44a49883863123f97cf410882ed859f834ff7494b61fd8f1daf210f36632594361b9b83371151c296f953a37582c680a260d68efa5201a00d0912cf6544a10b9382203680774ed1b6fbb1f9f39bba2018603143e800e1cac5c48ebd2b053db8655f22ef8173bbbeadfd08961b7be0a98f892568912d28ab4163f455cbd5aec2a25e81cbb7baf2344be66f74db5f9d4a8ebe43fc0ccbe2d4bfc43a1a6652bb37a8b61058684fff13b07806427bb823a36e386e8ad97a5fec233f9136ea7c55d906c2cd016855e9ff0f3775ee62094e15e6b5d7b9c9955feed73a7fd76d37927a88608e3c4770e024e46a900317142e7af58eb83775e2f22e876b86aa308fe6494ae43877042e57f0d0f2c79c06fc27a22c31b887a33ebc295ab4a1dcc31962a168fce55efbf636cfc90e04f70e8025ec1e9a1333906f89711a63b6b85e68547ebda21

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Verifies ISO/IEC 18013-5:2021 NFC Static Handover with NFC data retrieval on the same tag:
     * 1. The mdoc reader selects the NDEF Type 4 Tag application (`D2760000850101`), reads the
     *    Capability Container (`0xE103`, read-only), and reads the NDEF Handover Select (`Hs`)
     *    message from `0xE104` advertising NFC data transfer (`iso.org:18013:nfc`).
     * 2. The mdoc reader selects the ISO mdoc NFC data transfer application (`A0000002480400`) on
     *    the same tag and exchanges `SessionEstablishment` / `SessionData` via `ENVELOPE` (`0xC3`)
     *    with `DO'53'` encapsulation.
     */
    @Test
    fun testStaticHandoverNfcDataTransfer() = runTest {
        val nfcCm = getStaticNfcConnectionMethod()
        val transcript = runSingleRequestScenario(
            staticHandoverMethods = listOf(nfcCm),
            readerSelectConnectionMethod = { methods -> methods.filterIsInstance<MdocConnectionMethodNfc>().first() },
            expectedHandoverType = MdocHandoverType.STATIC_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-only).
            R-APDU: 000f207fff7fff0406e1047fff00ff9000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=190 bytes.
            R-APDU: 00be9000

            # The mdoc reader reads the 190-byte NDEF message.
            C-APDU: 00b00002be
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 190 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 17 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 11 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            R-APDU: 910211487315d1020b616301036e666301046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000

            # The mdoc reader selects the ISO mdoc NFC data transfer application.
            C-APDU: 00a4040007a0000002480400
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over NFC.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'2f45650b32da8336dbc5cffbd9a2a58547218b34e2ee7142b24b22bbc7d86c52887d8b86b4baf2e542d8f2f17d44c446afb872ed8f9b757fcb2ca1652505648ef267f1179e01a184571fb7343094d1465c25a1c5bec0d39ba3c57edfabf89504b846725d24edb42e2c03e3410acc98c673ed7531573090840182ea87ea6fbf33ee6fb2d75e8ab7'
            # }
            C-APDU: 00c300000000ec5381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158872f45650b32da8336dbc5cffbd9a2a58547218b34e2ee7142b24b22bbc7d86c52887d8b86b4baf2e542d8f2f17d44c446afb872ed8f9b757fcb2ca1652505648ef267f1179e01a184571fb7343094d1465c25a1c5bec0d39ba3c57edfabf89504b846725d24edb42e2c03e3410acc98c673ed7531573090840182ea87ea6fbf33ee6fb2d75e8ab7fff9
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e'
            # }
            R-APDU: 53820738a1646461746159072f57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e9000

            # The mdoc reader sends SessionData (status=20) to terminate the session over NFC.
            # {
            #   "status": 20
            # }
            C-APDU: 00c3000000000b5309a16673746174757314fff9
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Verifies ISO/IEC 18013-5:2021 NFC Negotiated Handover (TNEP) with BLE data retrieval:
     * 1. The mdoc reader selects the NDEF Type 4 Tag application (`D2760000850101`), reads the
     *    Capability Container (`0xE103`, read-write), and reads the initial NDEF message containing
     *    the TNEP Service Parameter (`Tp`) record for `urn:nfc:sn:handover`.
     * 2. The mdoc reader writes the TNEP Service Select (`Ts`) NDEF message and reads back the
     *    TNEP Status (`Te`, status=0) NDEF message.
     * 3. The mdoc reader writes the NDEF Handover Request (`Hr`) message advertising combined BLE
     *    Central Client and Peripheral Server modes, and reads back the NDEF Handover Select (`Hs`)
     *    message selecting BLE Central Client mode.
     * 4. The mdoc reader closes the NFC tag and completes `SessionEstablishment` / `SessionData`
     *    over BLE.
     */
    @Test
    fun testNegotiatedHandoverBle() = runTest {
        val bleCc = getStaticBleCentralClientMethod()
        val blePs = getStaticBlePeripheralServerMethod()
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                methods.filterIsInstance<MdocConnectionMethodBle>().first { it.gattCentral }
            },
            readerNegotiatedMethods = listOf(bleCc, blePs),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-write).
            R-APDU: 000f207fff7fff0406e1047fff00009000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=31 bytes.
            R-APDU: 001f9000

            # The mdoc reader reads the 31-byte NDEF message.
            C-APDU: 00b000021f
            # The mdoc returns the initial NDEF message with the TNEP Service Parameter record.
            # NDEF Message (1 record(s), 31 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Tp" (Hex: 5470)
            #     Payload Size: 26 bytes
            #     Service Parameter (TNEP):
            #       TNEP Version: 16
            #       Service Name URI: "urn:nfc:sn:handover"
            #       Communication Mode: 0
            #       wtInt (Waiting Time Code): 0
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff9000

            # The mdoc reader writes the TNEP Service Select NDEF message (NLEN=25).
            # NDEF Message (1 record(s), 25 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Ts" (Hex: 5473)
            #     Payload Size: 20 bytes
            #     Service Select:
            #       Service Name: "urn:nfc:sn:handover"
            C-APDU: 00d600001b0019d1021454731375726e3a6e66633a736e3a68616e646f766572
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=6 bytes.
            R-APDU: 00069000

            # The mdoc reader reads the 6-byte NDEF message.
            C-APDU: 00b0000206
            # The mdoc returns the TNEP Status NDEF message.
            # NDEF Message (1 record(s), 6 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Te" (Hex: 5465)
            #     Payload Size: 1 bytes
            #     TNEP Status:
            #       Status Code: 0
            R-APDU: d102015465009000

            # The mdoc reader writes the NDEF Handover Request message (NLEN=73).
            # NDEF Message (2 record(s), 73 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 10 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 4 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: []
            #   Record #2:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=null:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 02 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            C-APDU: 00d600004b004991020a487215d102046163010130005a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a101000000
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=194 bytes.
            R-APDU: 00c29000

            # The mdoc reader reads the 194-byte NDEF message.
            C-APDU: 00b00002c2
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 194 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 15 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 9 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=null
            #     Payload Hex: 02 1c 01
            R-APDU: 91020f487315d10209616301013001046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c019000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'2de959a5c9cbc5784c8d96d4487004d0a2d2e026812ff942c15f52b74075357c69bddab6159cfdd33dc91d74ed912a7d286fcbc03c26397cea03f2178e48235479a4ee382e29bb233d417a320f0a4f481e7f49ba8ac54f31368c08ee60b430fa365443f0ed869ded26cb1c0a2566011ac2c6dc36ac6a851bcff824b0dfce5623d9662b5c2729e5'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158872de959a5c9cbc5784c8d96d4487004d0a2d2e026812ff942c15f52b74075357c69bddab6159cfdd33dc91d74ed912a7d286fcbc03c26397cea03f2178e48235479a4ee382e29bb233d417a320f0a4f481e7f49ba8ac54f31368c08ee60b430fa365443f0ed869ded26cb1c0a2566011ac2c6dc36ac6a851bcff824b0dfce5623d9662b5c2729e5

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'017ded7835f63180c3768d19b7cc7ce56b486733a5eb50cd8c6201e413d5c9643c478ad4c0a5637d4a9ceb73c96dc1e5c408060f77caf60a531afd6bcb30bb3ce56652968a5c6a7353498155db1c3dd4bc2579910ef33ca07f88bc815f2ac5201eb5ca4ef55b79c5b391544be24b0b09d7faa376750b20e7e64f612b6131e53c94ffb0635cafb079d5e3720b86203a4c2a4d16872bc43a012620690f7589474017e782c4e50aa87910448fed89103996e1609b58f367b52593bcddca3981caa01375e3310902cbd3672784100213724e9c43fd584922d81fb564100102849545796018f5b215fec095770d8c5ef044592b7b9a45c601ecb46db84dd38a4d3c4cc480b2c21c79699249c85b996e3015d7ef6af9feefeec9528299487d746e6ec1ef5f7ab72c5fcb9ae3733bf08b2b4c65dabbafa78397c2dc297124adf25c2b9a5d5ec93ab8db8b57c9918343005cefb63781ec59cb24902226ade614ce58ef1ee781b8398f2c897e60e5d64df63dab085ceb38e45afcacdaa8781359c47b2ca615075fea9788924b74009facd969373c3adc7dbd627d23a259e4834785d3b5c3c4742a3fef2b62b149a6d5825b132d22049e4d8d5ddc26c265aee2bbf258df7ef98e42b864e90943132254e23e1a712aceb0eeefec81344c54153b2519f30d80306710359802c235c938546c44d3589ffdd89d9d1a77799de606ca6134877ad390af36b4deb8d51e469e5162c9d0151101f844c8812d637b0ad1849e1a563a04de5ad2cf9ea834e827fb039761902862ea1153bae5bd338a406839a8b76d51e463de8c9acefee086116cbd5879fe193cabb359ff2c05ea0fcfc2a99a2041906c54855f99080b9b49cc2241df883c254d4d6c8bfcb788183fa68dda50939f588e8e9f0ee9fba44052c4216044d220127d6336e1bbe2e253eab476c4204b0ac3c70a6179104472551bd493d3dc0315bd51950b8f6af36d031fefe4fa07b85c4c9ec0f9ae66a17b79c1fe309a39607bc0eda99f05d08c5e884ae65610046f5b42eee888d514e94816243bbf4dca68695c3607c317fd861e8fb34e430ec6c03ea29454bc6c6b48b318c892f1593a5beaa2fbee6a581fe21d274e83858967d7c2ae04f044d610fbc3181ed534af3015479b7d7b52a2d9f39380261b6c41d42fe4a272e6d605918a9d0e6abb0983dd5aff0257fe1da7aa6cc41578e01fa03f6d8e661db664bc86c364a581e594098e615e69adbca9ddefe499eb865d87aaa34b755060e02c424882620ad39f5cd6ab9d489cd82283520d22ec05d8c275b8ef50c3a6e5cc1398662900c5da8b2d0047568b63f8a4d81deb274612dd8b21f5f1a40ffd49d77fbdbffd42275788d5bb34124fca7ccae79907b92beb48371868a56d19fa7697635d8b320c48c5dd45895277e50739a5dd3ed22ac4d7a1478ed0724d57586f0f9aae22c63e06af16ff688a42b4df956882436d5bba69e98e12fb4ae980a92419ff545573b4f53d21829acaadf3cc99c5e4111e071ce391df55e278fca1ff948ccf4256731970d367882f4384d06aede9a0693f0dfea5ca88644e0eba2c574647b61ee856fea03e257c20ba0f1a8d0257d6c1e524b3eb3e12b6a64166e6adf0031234ce95cfdc0e0ab8ce15c3d48562ca77cef1e0f6902d6d22b299b61389111ed3115edaa29aa4b9393356c5318bd15e2246737bfee141cf74d3da4dd8d82c8a321f73f1b9a14ecd1ac3777c8a2e98f69ccef18efada8a40f3e5b9eb9172ade028c8a9f5f842b4255fbf309dcfc8ce063a91cf472597ebf1e5513dcc1d0fa25dc22961af0179be8a803c64cce544eceecd6d8bf98303dce001826f246c454a7b628f5376f0bf25631de75737a987db592b4a0a122f698dce1201302acad24f391ece08135aec0ff5aec45dd8613145027a86140f0449751e9fac09051371a79c921c62162947b31d922c6b92f8deda669a60c70a6607970632f134819f5e8bacda36a37eac3b5fa433aaf28569cd06510d4b7d8e31d3a13cebc6781e203ee933c8fe9105b514f37846f9a5a1aaa5ca4c3a1ef2e94f10dd274b858671898a395296749e90dbb67fca50ff00375d679afbaed7c75efb7d4c93548f4b80c491a06fe67ef0d1ec42e10dc5ac1632e1a0af375201a57512c65a27d0828bddea34b868d7a5e9429695d4a7aa06f86f74b343956a9565b7f3fecee4326e386cfeca73f8f252b189cb2b02b6ace7bb27bca715a4e387347ff18f25ecd5802bde12af85e26d246e8fe8be2cb7b76d2c6d4a9d5e6fe9e6aa29891ae4e56e5afd103d1854f91ba770e323ae39bf2b8853865439e5dcb2ac7ac4806a6a28a0cf59ece686f451a2e5e119a5265da37e354d80f1608f1d3735d10efe21705c74be2c68da579854e8ee70f57e42df1dc39015a428fcde51d207905e57544894f52de85c8728e791db5beac7fd47c902d9ff8ea38dc05b7b9b5db6ca904126daaa792df308b93ba1978251853d2cb83074f4cb5ad78a07dbc9a1242184ef654e1f5e4c265149130d38c7cc87eed3babeacd02b608b062b2284c096d7a99bbf71b8bc6f44d333590bf0883921634fc45fbb3312824e8a'
            # }
            BLE Mdoc->Reader: a1646461746159072f017ded7835f63180c3768d19b7cc7ce56b486733a5eb50cd8c6201e413d5c9643c478ad4c0a5637d4a9ceb73c96dc1e5c408060f77caf60a531afd6bcb30bb3ce56652968a5c6a7353498155db1c3dd4bc2579910ef33ca07f88bc815f2ac5201eb5ca4ef55b79c5b391544be24b0b09d7faa376750b20e7e64f612b6131e53c94ffb0635cafb079d5e3720b86203a4c2a4d16872bc43a012620690f7589474017e782c4e50aa87910448fed89103996e1609b58f367b52593bcddca3981caa01375e3310902cbd3672784100213724e9c43fd584922d81fb564100102849545796018f5b215fec095770d8c5ef044592b7b9a45c601ecb46db84dd38a4d3c4cc480b2c21c79699249c85b996e3015d7ef6af9feefeec9528299487d746e6ec1ef5f7ab72c5fcb9ae3733bf08b2b4c65dabbafa78397c2dc297124adf25c2b9a5d5ec93ab8db8b57c9918343005cefb63781ec59cb24902226ade614ce58ef1ee781b8398f2c897e60e5d64df63dab085ceb38e45afcacdaa8781359c47b2ca615075fea9788924b74009facd969373c3adc7dbd627d23a259e4834785d3b5c3c4742a3fef2b62b149a6d5825b132d22049e4d8d5ddc26c265aee2bbf258df7ef98e42b864e90943132254e23e1a712aceb0eeefec81344c54153b2519f30d80306710359802c235c938546c44d3589ffdd89d9d1a77799de606ca6134877ad390af36b4deb8d51e469e5162c9d0151101f844c8812d637b0ad1849e1a563a04de5ad2cf9ea834e827fb039761902862ea1153bae5bd338a406839a8b76d51e463de8c9acefee086116cbd5879fe193cabb359ff2c05ea0fcfc2a99a2041906c54855f99080b9b49cc2241df883c254d4d6c8bfcb788183fa68dda50939f588e8e9f0ee9fba44052c4216044d220127d6336e1bbe2e253eab476c4204b0ac3c70a6179104472551bd493d3dc0315bd51950b8f6af36d031fefe4fa07b85c4c9ec0f9ae66a17b79c1fe309a39607bc0eda99f05d08c5e884ae65610046f5b42eee888d514e94816243bbf4dca68695c3607c317fd861e8fb34e430ec6c03ea29454bc6c6b48b318c892f1593a5beaa2fbee6a581fe21d274e83858967d7c2ae04f044d610fbc3181ed534af3015479b7d7b52a2d9f39380261b6c41d42fe4a272e6d605918a9d0e6abb0983dd5aff0257fe1da7aa6cc41578e01fa03f6d8e661db664bc86c364a581e594098e615e69adbca9ddefe499eb865d87aaa34b755060e02c424882620ad39f5cd6ab9d489cd82283520d22ec05d8c275b8ef50c3a6e5cc1398662900c5da8b2d0047568b63f8a4d81deb274612dd8b21f5f1a40ffd49d77fbdbffd42275788d5bb34124fca7ccae79907b92beb48371868a56d19fa7697635d8b320c48c5dd45895277e50739a5dd3ed22ac4d7a1478ed0724d57586f0f9aae22c63e06af16ff688a42b4df956882436d5bba69e98e12fb4ae980a92419ff545573b4f53d21829acaadf3cc99c5e4111e071ce391df55e278fca1ff948ccf4256731970d367882f4384d06aede9a0693f0dfea5ca88644e0eba2c574647b61ee856fea03e257c20ba0f1a8d0257d6c1e524b3eb3e12b6a64166e6adf0031234ce95cfdc0e0ab8ce15c3d48562ca77cef1e0f6902d6d22b299b61389111ed3115edaa29aa4b9393356c5318bd15e2246737bfee141cf74d3da4dd8d82c8a321f73f1b9a14ecd1ac3777c8a2e98f69ccef18efada8a40f3e5b9eb9172ade028c8a9f5f842b4255fbf309dcfc8ce063a91cf472597ebf1e5513dcc1d0fa25dc22961af0179be8a803c64cce544eceecd6d8bf98303dce001826f246c454a7b628f5376f0bf25631de75737a987db592b4a0a122f698dce1201302acad24f391ece08135aec0ff5aec45dd8613145027a86140f0449751e9fac09051371a79c921c62162947b31d922c6b92f8deda669a60c70a6607970632f134819f5e8bacda36a37eac3b5fa433aaf28569cd06510d4b7d8e31d3a13cebc6781e203ee933c8fe9105b514f37846f9a5a1aaa5ca4c3a1ef2e94f10dd274b858671898a395296749e90dbb67fca50ff00375d679afbaed7c75efb7d4c93548f4b80c491a06fe67ef0d1ec42e10dc5ac1632e1a0af375201a57512c65a27d0828bddea34b868d7a5e9429695d4a7aa06f86f74b343956a9565b7f3fecee4326e386cfeca73f8f252b189cb2b02b6ace7bb27bca715a4e387347ff18f25ecd5802bde12af85e26d246e8fe8be2cb7b76d2c6d4a9d5e6fe9e6aa29891ae4e56e5afd103d1854f91ba770e323ae39bf2b8853865439e5dcb2ac7ac4806a6a28a0cf59ece686f451a2e5e119a5265da37e354d80f1608f1d3735d10efe21705c74be2c68da579854e8ee70f57e42df1dc39015a428fcde51d207905e57544894f52de85c8728e791db5beac7fd47c902d9ff8ea38dc05b7b9b5db6ca904126daaa792df308b93ba1978251853d2cb83074f4cb5ad78a07dbc9a1242184ef654e1f5e4c265149130d38c7cc87eed3babeacd02b608b062b2284c096d7a99bbf71b8bc6f44d333590bf0883921634fc45fbb3312824e8a

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Verifies ISO/IEC 18013-5:2021 NFC Negotiated Handover (TNEP) where the mdoc selects NFC data
     * retrieval over BLE:
     * 1. The mdoc reader performs TNEP service selection (`Tp` -> `Ts` -> `Te`) and writes a
     *    Handover Request (`Hr`) offering both BLE Central Client mode and NFC data transfer.
     * 2. The mdoc responds with a Handover Select (`Hs`) choosing NFC data transfer
     *    (`iso.org:18013:nfc`).
     * 3. The mdoc reader selects the ISO mdoc NFC data transfer application (`A0000002480400`) on
     *    the same tag and completes `SessionEstablishment` / `SessionData` over NFC.
     */
    @Test
    fun testNegotiatedHandoverNfcDataTransfer() = runTest {
        val bleCc = getStaticBleCentralClientMethod()
        val nfcCm = getStaticNfcConnectionMethod()
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                methods.filterIsInstance<MdocConnectionMethodNfc>().first()
            },
            readerNegotiatedMethods = listOf(bleCc, nfcCm),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-write).
            R-APDU: 000f207fff7fff0406e1047fff00009000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=31 bytes.
            R-APDU: 001f9000

            # The mdoc reader reads the 31-byte NDEF message.
            C-APDU: 00b000021f
            # The mdoc returns the initial NDEF message with the TNEP Service Parameter record.
            # NDEF Message (1 record(s), 31 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Tp" (Hex: 5470)
            #     Payload Size: 26 bytes
            #     Service Parameter (TNEP):
            #       TNEP Version: 16
            #       Service Name URI: "urn:nfc:sn:handover"
            #       Communication Mode: 0
            #       wtInt (Waiting Time Code): 0
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff9000

            # The mdoc reader writes the TNEP Service Select NDEF message (NLEN=25).
            # NDEF Message (1 record(s), 25 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Ts" (Hex: 5473)
            #     Payload Size: 20 bytes
            #     Service Select:
            #       Service Name: "urn:nfc:sn:handover"
            C-APDU: 00d600001b0019d1021454731375726e3a6e66633a736e3a68616e646f766572
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=6 bytes.
            R-APDU: 00069000

            # The mdoc reader reads the 6-byte NDEF message.
            C-APDU: 00b0000206
            # The mdoc returns the TNEP Status NDEF message.
            # NDEF Message (1 record(s), 6 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Te" (Hex: 5465)
            #     Payload Size: 1 bytes
            #     TNEP Status:
            #       Status Code: 0
            R-APDU: d102015465009000

            # The mdoc reader writes the NDEF Handover Request message (NLEN=118).
            # NDEF Message (3 record(s), 118 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 21 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 4 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: []
            #         Record #2:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 6 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: []
            #   Record #2:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            C-APDU: 00d60000780076910215487215910204616301013000510206616301036e6663001a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a1010000005c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff0402010000
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=190 bytes.
            R-APDU: 00be9000

            # The mdoc reader reads the 190-byte NDEF message.
            C-APDU: 00b00002be
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 190 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 17 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 11 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            R-APDU: 910211487315d1020b616301036e666301046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000

            # The mdoc reader selects the ISO mdoc NFC data transfer application.
            C-APDU: 00a4040007a0000002480400
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over NFC.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'0467bd28ff30005624e48cc26a9f2e69aaae877b1398c9b96f1e34580eaf3f704d99e65c3061900586b77898d4f41ed085c428de870c1cfc9750edebbccf92d3e7bda3bc758112759932eecc6328ba238324beb35e2e3af9b29a0e3cdca45aa8f3e6e46e610bb95ada115abb8ca741fd42da78e7069d3625e75100941d73191d6e402e202e66db'
            # }
            C-APDU: 00c300000000ec5381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158870467bd28ff30005624e48cc26a9f2e69aaae877b1398c9b96f1e34580eaf3f704d99e65c3061900586b77898d4f41ed085c428de870c1cfc9750edebbccf92d3e7bda3bc758112759932eecc6328ba238324beb35e2e3af9b29a0e3cdca45aa8f3e6e46e610bb95ada115abb8ca741fd42da78e7069d3625e75100941d73191d6e402e202e66dbfff9
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'be50502eb6a2bad1744222681399e0a639f8246e3a2e3a9029886e4afaf143b1cfe60a44eda66d3a2eba95ac9c2b3c72824641d6ac67539cbd60fbf72f8774faba6df3c0ad1b7da9356739f500459d366f47780d9c26d93c1e1e0d7de083bc6522f1fa2fa6ad6c7213ad7fbe8d3a6a1967fb758511372eb9278a337749d41e46aff56ac1ba2d503ba80248373e3fb7d8a8dffabfb808ddc9e51151c70cf8ee110e9de14f9e99cc9aeb3a1b8feebf4c31d2690501b41cb5ef2aedede74dbd2d9d16c1df46e65590e0f71b9ec0f123ecd977b25d60da647f153ec39802fc005faab6b3003b9e0db9b15bcfbea69008db555ffb724cf310383261d9379aecb85f01ac02b2a8d4c68420aa1fa43ffa397bfceb5a6549e3006a8a14d6a5027639dee50fedd988615922318542cc296e36e5605dc916d587b24cfb84cbb0b88f83b644cc4be9d3cfb00a57aa4e678bee023f8fecd55798a9ba49107169ecb776595b288e61449cb246b9411050e84838d462565a53126fd3ee1b292a7aaa74c5a641207198b9062792d3c97e9b8142f4ce04f9ab0b43f6f2fad4334fbb3a3fdcc8d41d95f2f22dfa597c9904e4723d09d8a09a514084d2a0db745a71390937b58837de8ce4e704aa8ed4da31d3bb2825ba61774b98e2a8b25341f102176c9f3d06d42e400d6b1add21c4f8af3cae9662b6ca861d65c13ddc363426de5ed8fc284642803540a06273be4f57ca9bf169ccd6ec1228d379a4fc94bdcdf01aa12efe312d575d93b7ae8efe1e38a9d50a54e67513e6137e71a8bd20c94d69c6790eb120553e660a2a8e30c7220eece428e1cf550d7cbf97ac7de083587fa8f0f6e935d41c9eaa702f36df891cecb661505af8a28c254d377249f846c27bd0991ffe49b0f5802da64ec275ca7df33879651f9da5a03ae4b8314312bfe7246556e9ffb6f0d7ef47a4e084f3b2069b5a723853346d282fe00b85fd14b0ea2c716546368af4a20de6886f9e47aa4b08fa48332415e24d846e2198a8b89e8891fe7d197d6ebedabe5a07c03bea5c5a36a193817880e0d68a2bab9a75ac865e38d54c4df094ecb9f2d856954025903bb3a48121a91d05fe4cc6e0e4cf5e2dc2c8712de82b9a2db9e2286998022be9180b40f6072c4516a29f4308aab60d7e9ae4411dd56135913ffdab36ccdce0a72b30ec748c9f3adc70a26afcc8f7c6334c6cbc695c060fbdf3a5233d3672183c67b61c0bb3fbed697718abf54f034286b6dd46ae3fbe2279b12395fdfc7335946d96d904d4790bff992d0fd3df3243e1870629606c36d0eb4756d46407ce1e589256d7a6dd53eacdb94a014c34b91bba093f9c85cfe6ee24229ed9d94050c59fdd7c60c6f749c343598714da8b86ec360e98d8d1804843c48c1caf6db69e5b22ef63a88c2e97341c383f810d1d8938fa1d82bf7803395d0ecf59d341f3a52bd9b0760976f893aea9fa3dd521a05dcdb95f58e1d0fb50dfe66564f8e6d3cd918899cd9e5c09b83385899e73d4cd988b7a368a04cb30a1d0335ae76ad82e61c5c3e5a70bb94375151068cb524650a2fc200ee3c8bbe379f4f1bbef785df7ce8893840b280e8250411b08ac7dad43c0c9d9bbc171503022fff4643f7cb2f6edabaeb76ab4eb1c7cfb5bf57ac109dce1f9f74b1b771aa273ba37f96b36e5f8fd7517ed6380c93f890a7e5009135129e8c89dce6168f951041e3687a68aa0eb01c905a46ea8599097a02ef73644008273eb9a5139d162acddc871c38c244f74d388944c1b1be911e9a1b771090f340818f53438957bfc03ff4d5a8fee81b61af4f9857b37ccc86aa3c74eb2e8de79d8fee6a9763d68151f1791183ba3056dbb60e61690232ad4cc983148da941c88d78ca476758eaeb92c1844661329e8a6ca5f9ee97cd82bce84e9e81664d237cd47e9a72c2f2354933944be124d58f5768356447deff589f57afa2c6f4c9d5c1122d12072de835a9eafb7f0b0aa71982f00556c3d8404b9054a46f04373c4e6af0ef07caf6d00b982745ee964416e4a92c44b3491d109a0e6b1a5c95515f2fc182362839b6e4569d786db37f1101aca0e6ae5103085caa9703e6a92310978ebced862efa0015e6e0a2435990137823e5d4a756ed32db2c84a4135ce02dcd3058fae8a3d3f6c2b3de818236d7492a79838279fc41b8c2abaaf9773adc96024813e6a826f8b46ccd12e1fae63093153786f497a9dfe37bffff2bf0da4902544d8223d35683364c19f5b77b70d57843e5990bae93027d46d017f6aa75aed1e39b8af792ebe42e72016a20d45edb092f5c2425e9edc9ec6684e5007450a61022e406e63cc1cbdc18d2c4a3906a5d0cc7e7604b705e186560b6e84ca03fb3bb4061b6375d4c2d797969cbcc51e10c337ae4ecbe7364d53930f462a49d54645093dbee6e9a0ad269f82267eaa1a3c91b0e910abbf371ce2580a6b255fd706cf64ace76ab3d14dfafd96cb0c183cd359391c9cde25a9f840e835b1e57b598b6b7a1b70f0fb3361045fe8cd5098697d8e07c9d39fed7c2867433dbeb3b09d976bf23d70ed96fa84ac2ccacb86b65714c9d05937a2ef488eac1f2ea76e8af0855f2e'
            # }
            R-APDU: 53820738a1646461746159072fbe50502eb6a2bad1744222681399e0a639f8246e3a2e3a9029886e4afaf143b1cfe60a44eda66d3a2eba95ac9c2b3c72824641d6ac67539cbd60fbf72f8774faba6df3c0ad1b7da9356739f500459d366f47780d9c26d93c1e1e0d7de083bc6522f1fa2fa6ad6c7213ad7fbe8d3a6a1967fb758511372eb9278a337749d41e46aff56ac1ba2d503ba80248373e3fb7d8a8dffabfb808ddc9e51151c70cf8ee110e9de14f9e99cc9aeb3a1b8feebf4c31d2690501b41cb5ef2aedede74dbd2d9d16c1df46e65590e0f71b9ec0f123ecd977b25d60da647f153ec39802fc005faab6b3003b9e0db9b15bcfbea69008db555ffb724cf310383261d9379aecb85f01ac02b2a8d4c68420aa1fa43ffa397bfceb5a6549e3006a8a14d6a5027639dee50fedd988615922318542cc296e36e5605dc916d587b24cfb84cbb0b88f83b644cc4be9d3cfb00a57aa4e678bee023f8fecd55798a9ba49107169ecb776595b288e61449cb246b9411050e84838d462565a53126fd3ee1b292a7aaa74c5a641207198b9062792d3c97e9b8142f4ce04f9ab0b43f6f2fad4334fbb3a3fdcc8d41d95f2f22dfa597c9904e4723d09d8a09a514084d2a0db745a71390937b58837de8ce4e704aa8ed4da31d3bb2825ba61774b98e2a8b25341f102176c9f3d06d42e400d6b1add21c4f8af3cae9662b6ca861d65c13ddc363426de5ed8fc284642803540a06273be4f57ca9bf169ccd6ec1228d379a4fc94bdcdf01aa12efe312d575d93b7ae8efe1e38a9d50a54e67513e6137e71a8bd20c94d69c6790eb120553e660a2a8e30c7220eece428e1cf550d7cbf97ac7de083587fa8f0f6e935d41c9eaa702f36df891cecb661505af8a28c254d377249f846c27bd0991ffe49b0f5802da64ec275ca7df33879651f9da5a03ae4b8314312bfe7246556e9ffb6f0d7ef47a4e084f3b2069b5a723853346d282fe00b85fd14b0ea2c716546368af4a20de6886f9e47aa4b08fa48332415e24d846e2198a8b89e8891fe7d197d6ebedabe5a07c03bea5c5a36a193817880e0d68a2bab9a75ac865e38d54c4df094ecb9f2d856954025903bb3a48121a91d05fe4cc6e0e4cf5e2dc2c8712de82b9a2db9e2286998022be9180b40f6072c4516a29f4308aab60d7e9ae4411dd56135913ffdab36ccdce0a72b30ec748c9f3adc70a26afcc8f7c6334c6cbc695c060fbdf3a5233d3672183c67b61c0bb3fbed697718abf54f034286b6dd46ae3fbe2279b12395fdfc7335946d96d904d4790bff992d0fd3df3243e1870629606c36d0eb4756d46407ce1e589256d7a6dd53eacdb94a014c34b91bba093f9c85cfe6ee24229ed9d94050c59fdd7c60c6f749c343598714da8b86ec360e98d8d1804843c48c1caf6db69e5b22ef63a88c2e97341c383f810d1d8938fa1d82bf7803395d0ecf59d341f3a52bd9b0760976f893aea9fa3dd521a05dcdb95f58e1d0fb50dfe66564f8e6d3cd918899cd9e5c09b83385899e73d4cd988b7a368a04cb30a1d0335ae76ad82e61c5c3e5a70bb94375151068cb524650a2fc200ee3c8bbe379f4f1bbef785df7ce8893840b280e8250411b08ac7dad43c0c9d9bbc171503022fff4643f7cb2f6edabaeb76ab4eb1c7cfb5bf57ac109dce1f9f74b1b771aa273ba37f96b36e5f8fd7517ed6380c93f890a7e5009135129e8c89dce6168f951041e3687a68aa0eb01c905a46ea8599097a02ef73644008273eb9a5139d162acddc871c38c244f74d388944c1b1be911e9a1b771090f340818f53438957bfc03ff4d5a8fee81b61af4f9857b37ccc86aa3c74eb2e8de79d8fee6a9763d68151f1791183ba3056dbb60e61690232ad4cc983148da941c88d78ca476758eaeb92c1844661329e8a6ca5f9ee97cd82bce84e9e81664d237cd47e9a72c2f2354933944be124d58f5768356447deff589f57afa2c6f4c9d5c1122d12072de835a9eafb7f0b0aa71982f00556c3d8404b9054a46f04373c4e6af0ef07caf6d00b982745ee964416e4a92c44b3491d109a0e6b1a5c95515f2fc182362839b6e4569d786db37f1101aca0e6ae5103085caa9703e6a92310978ebced862efa0015e6e0a2435990137823e5d4a756ed32db2c84a4135ce02dcd3058fae8a3d3f6c2b3de818236d7492a79838279fc41b8c2abaaf9773adc96024813e6a826f8b46ccd12e1fae63093153786f497a9dfe37bffff2bf0da4902544d8223d35683364c19f5b77b70d57843e5990bae93027d46d017f6aa75aed1e39b8af792ebe42e72016a20d45edb092f5c2425e9edc9ec6684e5007450a61022e406e63cc1cbdc18d2c4a3906a5d0cc7e7604b705e186560b6e84ca03fb3bb4061b6375d4c2d797969cbcc51e10c337ae4ecbe7364d53930f462a49d54645093dbee6e9a0ad269f82267eaa1a3c91b0e910abbf371ce2580a6b255fd706cf64ace76ab3d14dfafd96cb0c183cd359391c9cde25a9f840e835b1e57b598b6b7a1b70f0fb3361045fe8cd5098697d8e07c9d39fed7c2867433dbeb3b09d976bf23d70ed96fa84ac2ccacb86b65714c9d05937a2ef488eac1f2ea76e8af0855f2e9000

            # The mdoc reader sends SessionData (status=20) to terminate the session over NFC.
            # {
            #   "status": 20
            # }
            C-APDU: 00c3000000000b5309a16673746174757314fff9
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Verifies ISO/IEC 18013-5:2021 NFC data transfer with APDU command chaining (`CLA=0x10`) and
     * response chaining (`SW=61XX` + `GET RESPONSE` `0xC0`) when the negotiated NFC carrier
     * specifies constrained maximum command (`100` bytes) and response (`900` bytes) data lengths.
     */
    @Test
    fun testNfcDataTransferApduCommandAndResponseChaining() = runTest {
        val nfcCm = getStaticNfcConnectionMethod(commandMax = 100L, responseMax = 900L)
        val transcript = runSingleRequestScenario(
            staticHandoverMethods = listOf(nfcCm),
            readerSelectConnectionMethod = { methods -> methods.filterIsInstance<MdocConnectionMethodNfc>().first() },
            expectedHandoverType = MdocHandoverType.STATIC_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-only).
            R-APDU: 000f207fff7fff0406e1047fff00ff9000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=188 bytes.
            R-APDU: 00bc9000

            # The mdoc reader reads the 188-byte NDEF message.
            C-APDU: 00b00002bc
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 188 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 17 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 11 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 8 bytes
            #     Carrier Configuration: nfc:cmd_max_length=100:resp_max_length=900
            #     Payload Hex: 01 02 01 64 03 02 03 84
            R-APDU: 910211487315d1020b616301036e666301046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55c11080369736f2e6f72673a31383031333a6e66636e666301020164030203849000

            # The mdoc reader selects the ISO mdoc NFC data transfer application.
            C-APDU: 00a4040007a0000002480400
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader sends a command-chained ENVELOPE chunk (100 bytes, CLA=0x10).
            C-APDU: 10c30000645381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158
            # The mdoc acknowledges the command-chained chunk.
            R-APDU: 9000

            # The mdoc reader sends a command-chained ENVELOPE chunk (100 bytes, CLA=0x10).
            C-APDU: 10c300006487412602ae92f5df31d05b375b0a847b71083db27e52a8f9cc81b2265c6a33f62440359c6f8deffc902f5e739ef020b53a41d90d3930bb1fdad54e63c3e5e7524e8c2c060b5fe45f14d7c2bc761c914295c5f852624f1ad4d3f274dabd35ec44e11679d2
            # The mdoc acknowledges the command-chained chunk.
            R-APDU: 9000

            # The mdoc reader sends the final command-chained ENVELOPE chunk (36 bytes, CLA=0x00) completing SessionEstablishment with eReaderKey and encrypted DeviceRequest.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'412602ae92f5df31d05b375b0a847b71083db27e52a8f9cc81b2265c6a33f62440359c6f8deffc902f5e739ef020b53a41d90d3930bb1fdad54e63c3e5e7524e8c2c060b5fe45f14d7c2bc761c914295c5f852624f1ad4d3f274dabd35ec44e11679d21d6c4a54a45890b78d3c3927c3f0793766bd1f2db26d030e4c044af6f0ef6a6758be630f'
            # }
            C-APDU: 00c300000000241d6c4a54a45890b78d3c3927c3f0793766bd1f2db26d030e4c044af6f0ef6a6758be630f0384
            # The mdoc returns a response chunk (900 bytes) with status 6100 (more data available).
            R-APDU: 53820738a1646461746159072f21e0cf9ee26c714e51565b5004634518706274e8470bcaa35780492b8fee23f3fe8e8ce954e2e1a4eb0f57ea1dabccfdeffa577bab004caecb28464cc23d5ff315d8ae92ed7521e85984356e00fedc5487b8fabe509b8fec071cea48e31761142122428f1dca6bdecd8a2ddcd4e97364ab0eca40bde3337f66114b34f22e45759400a6cabf012cea51c979753b9dceee0f14f83106fd28a477c9a24806f835e678f213a4973310545480addc1292b534d858c037fcb81d28051abc1173dd365c83082a256f2d1b25fbc21661d83b384e374a57639effbe15e67804374916bb9c68a16da581347ee083eea12293f2a31ffd2c3f4c9a8c86cc545451e5bfd592da27032994d5fa1d78472ae7f894f65bcc937a8d77f15990124b035272798af02444095262481748ec502aaee2b97211e2c1baf98dcc102cd2047158ee42563e9e921ab4931dd187e35e83f0d80b45debf2eb9d29612d472c8ef7d67d0d300d8ee1059707c72e7639677d2577bb4613aacf53b5114ea65861c2ff586d72b96f46a942ad3c2e29efbe3788f7dbd66a2834d5247cc0db8fb43f29d2ce1feef4b272c491d826a8cf501c6e6dce9eda916501decab250affb8d229ac4b63df9eba4583d8312a0aa2c701048ec0f4638d36960f1ce3e4b36fecaddf471b7817b937aed30316c65a314663f3572f538831dd53a9437ef09a4fd21e19e8c5e70e881d7d3dffeaa9940cab5b92f839a6f3268690db3e7d0924e1439e91a731fb06baea5d6fd98cfbab1e254e6933bce538a176b418cc044f68e339ed204832dd6c33e37c5cf50bbcd5c5dd7fe892c3f7b5454e0fedbb05d14dad9b74d453713c36c14c0ae391b30ed0d89c567277b95d3338365c3850ada6d143db158521087245781dac6cfc1854a739722397dfe6c50bb9d55d9b87562475077c31cc879f84060efb253f415f48124fbe3eba9216556ad76c082f086423e2997a44809839eca53b9632e0ad7157f4a56d4a0a1b158611db1d57b9c90edca1127b9bfe73d6650f69f416be92c3e325db5db38293652e86725617f1283a2410aa2e8afe3b90bc259d6187ccd95ccda663a7a6edd047a21d4fed861b87de8c9a388cafc60ab6457a4571aec058f9ceafe159c42e493d01b3c02937394601fc555f4944bc3e0d8d52e3103eee32c263d2943a8f3d90db4f5586fa2ae89779c40a098636c45250b060e10f2256658b7a35eac675cd61f77d3c7f077eec11b44f742cda396100

            # The mdoc reader sends GET RESPONSE requesting up to 900 bytes.
            C-APDU: 00c00000000384
            # The mdoc returns a response chunk (900 bytes) with status 6134 (more data available).
            R-APDU: 3e62fbd5437dfae127b604bd4fd59cbd469623d5419e3d51af41ac4e353103b01dfa1007a1ae6b903b2e310f225d300dc0f8071f9f61898e55392bba9969e98b8101ee9f67db2ace8a199bc6845a8ada96273ab3d569f713ff81ee5da285ac951c74e4044d40e9bc14d927c29ffdef1f551a215a3e51685a63864cead20f4e0535a7cac841f4404353d4fac048bfcfab4fb2844493fbde8d7b5f79f3ee802210d62454ec161a3827c9b08b72f5c2dfb9ccc4b67690839c5d2e5bc409645268303f89ffa2fd58c65b51250855c18d9809d36c5a995a0418a4b78b67072e4724dc78f8bac5a6a9663be4afd25392391697407d430b6ce8c003af58c7a518489c80b915a5788d0d42af6ea1f917c908a98b77eb2c269ad1fbcf3f80c98ddef04653abbbbd3f49c72320df0fd867fe7dbc3aca423f516bb1e35ff3e417cfce76dba54bd75efe9ee90200c5a9f218373a1a1e57d91f2538aff8d33df2962a880ce463296f1b4ee950f8b5d4d4e067c903b81e043dfa4011c6640bcc622c1ff384f40318eeaf49df3454fa3d5c4540111af2bdb0470794411618cf5b7a66ad83d9721011333a3759611d070b075bb552203389b3cbf05c88b2880571c740d7caa2c4e83513d1585ca9daf981b956b7f2096a27836113008e670f7aad3dffb9268ac530d2d0caa941925c011ada4a82a7cf4f1cd17ba78adaf9e23f54f865c3a37ce405bd4e41f45edf2f18856f6ecb1ce572b15d16638824476d3197a4504280416f7d00a16f7097753783f350ffbb0808578d60c51fea07ced761ee5eb5e86d7ecdbff37924715758fdd387cf5cc2ce9bccd387a2cf3f0e4ba153820e760b3cbab5e7821333f85a8848c527cea45a98dddd0babe62e09220d5facf310b3b9559e02937a3ba3e7c72c8f8e89c5bef55d7a6505b9e0adb0d5887a0736587c82f288cc230b9b3cac1d0c9d5d6bb75229f47f1f40c5fd5d874e5219eada540baa8532b7e3f148226b37ee2df0b3caf50ca0ab95deebbda8a88a329b68f22dd623f4cc3e64e9640aac4d3bc33968a11d45992ab4b100d908a365e3e28d8086a17657349103cbbdf9bf5d4ff448b66dfe1275a55143ec779aa00e60b7f496a80635d83662b328a305c9cd471fce71886c41b7eb540894cc955d6f1ef29b833017a408b9e8081f5726b64b9603aff58fb2dc8ceffa396beb8c0630d14e05e8ad8c2ebb2f657418b442bd1dc9fea76c1da83a9902e606efa5529d450d89db4513783220ca168617089f0a6134

            # The mdoc reader sends GET RESPONSE requesting up to 52 bytes.
            C-APDU: 00c0000034
            # The mdoc returns the final response chunk (52 bytes) completing SessionData with encrypted DeviceResponse.
            # {
            #   "data": h'21e0cf9ee26c714e51565b5004634518706274e8470bcaa35780492b8fee23f3fe8e8ce954e2e1a4eb0f57ea1dabccfdeffa577bab004caecb28464cc23d5ff315d8ae92ed7521e85984356e00fedc5487b8fabe509b8fec071cea48e31761142122428f1dca6bdecd8a2ddcd4e97364ab0eca40bde3337f66114b34f22e45759400a6cabf012cea51c979753b9dceee0f14f83106fd28a477c9a24806f835e678f213a4973310545480addc1292b534d858c037fcb81d28051abc1173dd365c83082a256f2d1b25fbc21661d83b384e374a57639effbe15e67804374916bb9c68a16da581347ee083eea12293f2a31ffd2c3f4c9a8c86cc545451e5bfd592da27032994d5fa1d78472ae7f894f65bcc937a8d77f15990124b035272798af02444095262481748ec502aaee2b97211e2c1baf98dcc102cd2047158ee42563e9e921ab4931dd187e35e83f0d80b45debf2eb9d29612d472c8ef7d67d0d300d8ee1059707c72e7639677d2577bb4613aacf53b5114ea65861c2ff586d72b96f46a942ad3c2e29efbe3788f7dbd66a2834d5247cc0db8fb43f29d2ce1feef4b272c491d826a8cf501c6e6dce9eda916501decab250affb8d229ac4b63df9eba4583d8312a0aa2c701048ec0f4638d36960f1ce3e4b36fecaddf471b7817b937aed30316c65a314663f3572f538831dd53a9437ef09a4fd21e19e8c5e70e881d7d3dffeaa9940cab5b92f839a6f3268690db3e7d0924e1439e91a731fb06baea5d6fd98cfbab1e254e6933bce538a176b418cc044f68e339ed204832dd6c33e37c5cf50bbcd5c5dd7fe892c3f7b5454e0fedbb05d14dad9b74d453713c36c14c0ae391b30ed0d89c567277b95d3338365c3850ada6d143db158521087245781dac6cfc1854a739722397dfe6c50bb9d55d9b87562475077c31cc879f84060efb253f415f48124fbe3eba9216556ad76c082f086423e2997a44809839eca53b9632e0ad7157f4a56d4a0a1b158611db1d57b9c90edca1127b9bfe73d6650f69f416be92c3e325db5db38293652e86725617f1283a2410aa2e8afe3b90bc259d6187ccd95ccda663a7a6edd047a21d4fed861b87de8c9a388cafc60ab6457a4571aec058f9ceafe159c42e493d01b3c02937394601fc555f4944bc3e0d8d52e3103eee32c263d2943a8f3d90db4f5586fa2ae89779c40a098636c45250b060e10f2256658b7a35eac675cd61f77d3c7f077eec11b44f742cda393e62fbd5437dfae127b604bd4fd59cbd469623d5419e3d51af41ac4e353103b01dfa1007a1ae6b903b2e310f225d300dc0f8071f9f61898e55392bba9969e98b8101ee9f67db2ace8a199bc6845a8ada96273ab3d569f713ff81ee5da285ac951c74e4044d40e9bc14d927c29ffdef1f551a215a3e51685a63864cead20f4e0535a7cac841f4404353d4fac048bfcfab4fb2844493fbde8d7b5f79f3ee802210d62454ec161a3827c9b08b72f5c2dfb9ccc4b67690839c5d2e5bc409645268303f89ffa2fd58c65b51250855c18d9809d36c5a995a0418a4b78b67072e4724dc78f8bac5a6a9663be4afd25392391697407d430b6ce8c003af58c7a518489c80b915a5788d0d42af6ea1f917c908a98b77eb2c269ad1fbcf3f80c98ddef04653abbbbd3f49c72320df0fd867fe7dbc3aca423f516bb1e35ff3e417cfce76dba54bd75efe9ee90200c5a9f218373a1a1e57d91f2538aff8d33df2962a880ce463296f1b4ee950f8b5d4d4e067c903b81e043dfa4011c6640bcc622c1ff384f40318eeaf49df3454fa3d5c4540111af2bdb0470794411618cf5b7a66ad83d9721011333a3759611d070b075bb552203389b3cbf05c88b2880571c740d7caa2c4e83513d1585ca9daf981b956b7f2096a27836113008e670f7aad3dffb9268ac530d2d0caa941925c011ada4a82a7cf4f1cd17ba78adaf9e23f54f865c3a37ce405bd4e41f45edf2f18856f6ecb1ce572b15d16638824476d3197a4504280416f7d00a16f7097753783f350ffbb0808578d60c51fea07ced761ee5eb5e86d7ecdbff37924715758fdd387cf5cc2ce9bccd387a2cf3f0e4ba153820e760b3cbab5e7821333f85a8848c527cea45a98dddd0babe62e09220d5facf310b3b9559e02937a3ba3e7c72c8f8e89c5bef55d7a6505b9e0adb0d5887a0736587c82f288cc230b9b3cac1d0c9d5d6bb75229f47f1f40c5fd5d874e5219eada540baa8532b7e3f148226b37ee2df0b3caf50ca0ab95deebbda8a88a329b68f22dd623f4cc3e64e9640aac4d3bc33968a11d45992ab4b100d908a365e3e28d8086a17657349103cbbdf9bf5d4ff448b66dfe1275a55143ec779aa00e60b7f496a80635d83662b328a305c9cd471fce71886c41b7eb540894cc955d6f1ef29b833017a408b9e8081f5726b64b9603aff58fb2dc8ceffa396beb8c0630d14e05e8ad8c2ebb2f657418b442bd1dc9fea76c1da83a9902e606efa5529d450d89db4513783220ca168617089f0a56e7e0ac0c9ad6fc83e818cb7a2b3de77d3ff6eac85c52e468e0bb599750fcc6fa4ded35915beeec710c0a50352aa2d2596a5e38'
            # }
            R-APDU: 56e7e0ac0c9ad6fc83e818cb7a2b3de77d3ff6eac85c52e468e0bb599750fcc6fa4ded35915beeec710c0a50352aa2d2596a5e389000

            # The mdoc reader sends SessionData (status=20) to terminate the session over NFC.
            # {
            #   "status": 20
            # }
            C-APDU: 00c3000000000b5309a166737461747573140384
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Verifies multiple sequential `DeviceRequest` / `DeviceResponse` exchanges within a single
     * ISO/IEC 18013-5:2021 NFC data transfer session (`A0000002480400`), followed by session
     * termination (`status = 20`).
     */
    @Test
    fun testNfcDataTransferMultipleRequestsInSingleSession() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val presentmentSource = createAnnexDPresentmentSource()
        val transcriptLogger = TranscriptLogger()
        val transportFactory = TestTransportFactory(transcriptLogger)
        val presentmentDone = CompletableDeferred<Unit>()
        lateinit var mdocTransport: MdocTransport
        val nfcCm = getStaticNfcConnectionMethod()
        val scope = CoroutineScope(Dispatchers.Default)

        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                mdocTransport = transportFactory.createTransport(
                    connectionMethod = connectionMethods.first(),
                    role = MdocRole.MDOC,
                    options = MdocTransportOptions(),
                )
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = null,
                )
                val activeEngagementParamsFlow = MutableStateFlow(params)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    mdocTransport.open(eDeviceKey.publicKey)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = false,
                        timeout = null,
                        timeoutSubsequentRequests = 5.seconds,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcEngagementHelper: $error")
            },
            staticHandoverMethods = listOf(nfcCm),
        )

        val tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
        )
        val tagReader = SingleTagNfcTagReader(tag)

        withContext(Dispatchers.Default) {
            val scanResult = tagReader.scanMdocReader(
                message = null,
                options = MdocTransportOptions(),
                handoverOptions = MdocReaderNfcHandoverOptions(useCce = false),
                transportFactory = transportFactory,
                selectConnectionMethod = { it.firstOrNull() },
                negotiatedHandoverConnectionMethods = emptyList(),
                context = Dispatchers.Default,
                onHandover = { it }
            )
            assertNotNull(scanResult)
            assertEquals(MdocHandoverType.STATIC_HANDOVER, scanResult.type)

            val readerTransport = scanResult.transport
            assertIs<NfcTransportMdocReader>(readerTransport)
            val deviceEngagement = DeviceEngagement.fromDataItem(
                Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
            )
            readerTransport.open(deviceEngagement.eDeviceKey)

            val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
            val sessionTranscript = buildCborArray {
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
                add(scanResult.handover)
            }
            val readerEncryption = SessionEncryption(
                role = MdocRole.MDOC_READER,
                eSelfKey = eReaderKey,
                remotePublicKey = deviceEngagement.eDeviceKey,
                encodedSessionTranscript = Cbor.encode(sessionTranscript),
            )

            // Request 1: family_name
            val deviceRequest1 = buildDeviceRequest(
                sessionTranscript = sessionTranscript,
                version = "1.0",
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                    )
                )
            }
            readerTransport.sendMessage(
                readerEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest1.toDataItem()),
                    statusCode = null,
                )
            )
            val (encodedResponse1, status1) = readerEncryption.decryptMessage(readerTransport.waitForMessage())
            assertNull(status1)
            assertNotNull(encodedResponse1)
            DeviceResponse.fromDataItem(Cbor.decode(encodedResponse1)).verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
            )

            // Request 2: document_number
            val deviceRequest2 = buildDeviceRequest(
                sessionTranscript = sessionTranscript,
                version = "1.0",
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf("document_number" to false)
                    )
                )
            }
            readerTransport.sendMessage(
                readerEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest2.toDataItem()),
                    statusCode = null,
                )
            )
            val (encodedResponse2, status2) = readerEncryption.decryptMessage(readerTransport.waitForMessage())
            assertNull(status2)
            assertNotNull(encodedResponse2)
            DeviceResponse.fromDataItem(Cbor.decode(encodedResponse2)).verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
            )

            // Session termination
            readerTransport.sendMessage(
                readerEncryption.encryptMessage(
                    messagePlaintext = null,
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                )
            )

            withTimeout(5.seconds) {
                presentmentDone.await()
            }
            readerTransport.close()
            mdocTransport.close()
            withTimeout(5.seconds) {
                tag.closedDeferred.await()
            }
        }
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-only).
            R-APDU: 000f207fff7fff0406e1047fff00ff9000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=190 bytes.
            R-APDU: 00be9000

            # The mdoc reader reads the 190-byte NDEF message.
            C-APDU: 00b00002be
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 190 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 17 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 11 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            R-APDU: 910211487315d1020b616301036e666301046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000

            # The mdoc reader selects the ISO mdoc NFC data transfer application.
            C-APDU: 00a4040007a0000002480400
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over NFC.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'2f45650b32da8336dbc5cffbd9a2a58547218b34e2ee7142b24b22bbc7d86c52887d8b86b4baf2e542d8f2f17d44c446afb872ed8f9b757fcb2ca1652505648ef267f1179e01a184571fb7343094d1465c25a1c5bec0d39ba3c57edfabf89504b846725d24edb42e2c03e3410acc98c673ed7531573090840182ea87ea6fbf33ee6fb2d75e8ab7'
            # }
            C-APDU: 00c300000000ec5381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158872f45650b32da8336dbc5cffbd9a2a58547218b34e2ee7142b24b22bbc7d86c52887d8b86b4baf2e542d8f2f17d44c446afb872ed8f9b757fcb2ca1652505648ef267f1179e01a184571fb7343094d1465c25a1c5bec0d39ba3c57edfabf89504b846725d24edb42e2c03e3410acc98c673ed7531573090840182ea87ea6fbf33ee6fb2d75e8ab7fff9
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e'
            # }
            R-APDU: 53820738a1646461746159072f57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e9000

            # The mdoc reader sends SessionData with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'4dc42daa339744d18830bb12461b7e3c1b664f4c44673b8be6b11328da328ec8b6e21d41766611e09a935a6d808608eb6bc6501993a54fb44f87c6956d04e10d36959f1fbf0fe865680e12c99c887da9f53ef0d852b3594adbe651ae3244cf956e157e118e3ecf6bfeafd934f14bff4a89c1f94c39dc26cbe659961d71c87961ccebaff757a0ddbd1e6a7e'
            # }
            C-APDU: 00c30000000096538193a16464617461588b4dc42daa339744d18830bb12461b7e3c1b664f4c44673b8be6b11328da328ec8b6e21d41766611e09a935a6d808608eb6bc6501993a54fb44f87c6956d04e10d36959f1fbf0fe865680e12c99c887da9f53ef0d852b3594adbe651ae3244cf956e157e118e3ecf6bfeafd934f14bff4a89c1f94c39dc26cbe659961d71c87961ccebaff757a0ddbd1e6a7efff9
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'01aaa1be519f1579f21bcf7c04d0768b75b13b3ffc3591798efdf42cc34c51faef946d40fb496953e93f209f768987f8b434c080f82d98ca6b9c2f19ae31819d3d0aab49eb5c0621ff52bcf12039cf261bdbf4ec4b66e239a14b276688a4fc619a3cb19aeb2999b31808ae51e75ee1ebe5d5d41772266f7e4b7bd4d4ee5afb9891a9f5ce0ddfc05593fddc8421669f7ba938fb33d3de91b7b86d76969c59efff3d032746730726128e42a3ac49986e8a545907c04990226d76a6251b2466aa30b16a6adc224d44b6e21d574a7bbce73dccf3ce5ac932bf2a0d8b2857cfdf91603bbace3ac5ea9f79e66ae15bf2982695440ecd2d055a0dbe915720b1fbde11d4355e3ee2866d04b0262fb4b0b26f6c23a377e23c02b16840cdfb3f93930b029c835b1896808e3c9710618dabbec0935adfe08af8814c407b3ee3a9075c271ddefbe251eed4e4744218bb0a1e022fe6af2ae5f6b73adfd48483bf956177002fc39a949fced972d3919b5abf46940429b5384879289bd7f11fefb5de97140188d496db8679637640862490d6e5073e47ec0772bc0ff1b30685b56b6c65f2fb1952e3eae7e3245534c005a60441450a89952262f9076dde4c07a7b729038264fbd1b8a8fed74b94c18eb344b37968d5a7f9f845d771ffd5a30ce27c0b213649792a55b6120c69ec33517c56b9e58716dc677037f4475d1e04ad4bb43cde973b5246c182388cf55224864d56f846f98e92861bd8b29639daf69642b699080cf5399d5763b79d49ae26bbd0aabf676384345d162397d3914ac2282e99f140a36df7a970a72726d0b8934012807c21c76dbb31d807c4b890e3b8ba42c77cb9918680fd0e3e07aa2081019ba5fce737221c34a61a3952c5a401ce7d6be57b79389d7fd6d42b2e1a1127c72da1d72c653b42279c67fd6472d1568a76d6b332ae66f93631707834088e61c5916dc169e7b7a8f9cb0c55f351ded646cc6f1411138d4b5eeb24356d0f166127611c2ad3799577ba45680e14fd8da026ff82ca09ac1590fb39cfcf0adf17c223c84a1e4302462687800887b32db6214fba0294b880584530d40894e62d207c52156109b2eed623e129b69fbc706f150f1d921fd97a5d609c68c1b3e83f45e03fd7b850bdd640e58a79d8b4c290f44897837d69b5c90931447b44a46028fa2896dfbba6217ced1a1e6a343894e2fadd52429772e601fedf95127f2b12e0275615499f13103ff7db7144d39c7c9f9358bdca58bec8dbe39f64846b7f61650a79940257c8d775ec51ad796676db5cfa6b95918f3b088ee0990e7a15238a031be40fad88941d719695aff87be465557479868ce04589487c323e90a2e862809f45ae4bfb7276a7082921610b56ec0162fb1841b28a355fbf462d6f3e7e540f71c4687199ad4672b2a3cbf3fa3b92a3073d4b2e0d91f57b711685e418766a5d95912ae2609fecff0bf6e152949665e4d95240be002b90ddf31bd0d0f42c014a5c72f0fa44b4c745ab1b8c89a305901098e6aaff7cdd8950bec35b41c07f6ab5ef8fa43f0232ed657ce64365289bb61bb3fb05ee5fb2e867f6b8366ed8b015d4ad02df1ac986ae116231fbbb43f23fe965467ee3b1693b7e836dea4dfc4e6fadb02dde6de4f703b7669c22046271ef660a2564f13c33b2fc5141a5e588444109c413d96e9951705a8eaf97eb0dcba2522184607247b44d2018a9a80de317551c1afbcb83e27a1e9f3bf3629605b59a172cc4d23c8067b9f368df6502b5410e8a0abaae8d25dda6a409758064967ed9fdf65e6a8a7bafb5d7c6ff0e54cde33032e7343280b913b0170b7f93cbcddf5b46192906df9656e350746bb97df11da9b4fabc10cb0d81ff812aef104e1e7545dc5a3729ad4eb41e7a9697dd7f2b6b4760ed24787ae440da49fdabd21e7a90ec379a7bbef9656189ea8f763c2a7f89498a58f0ec0ce2924fb8b3de3c9e6fef3e88c5ee1566ac417499398a0ee6735793856f3dd1b6778e84bf74588331952affacba8a576616dc239f2ba3e09c082c4a4e18006a2925bdcc7e327cab440c4afa7112b7520a74af3c02243773b80c034c518207d4d6db09cdf880279e7fd6f6b8e1cb20eef581f92f76f80ca2a3f689cace0e947b43051f313e587b75b8d88471bf34cc78fa68d3831a569226f97394dd4365c265ead32623c163fe1008ff8a4dd2cf910aae155acf894812d41ed20cd1f0b7867de6f86dda11bd7b3e1c66877842f43d026b79c435bffdd301689d80ff9e0ef1e2c5f631961b7bb9eb06e283d8d40476c01640e6cffb4b115cc3ef6d59e825d3f1ce260a52f69e0eb264a8539700398a504d8387644b37944a66544c3825b31edee9572cd87576be57dda1482502c823bc476c4a8769a88f7fcd6563b1c01e9de52e8caacabd5ff7d4c49d6561f046715dbc8bfa086b836efe57353b9cade5bf43b6f6250b72024d3b01b2bc853dc3c837960953587d6d440cbcd27b308121744d4ffabc59d04b9364ac5e683517a9ff4e5ae0dec7cdac25862a9b94a9d17c284c63aff9f777da7710049d2d0bb9cdccc643366d508a92f992d9140d535bd7553b41eedcfa1a65dd05ab94df2f20c8e1a4757a'
            # }
            R-APDU: 53820742a1646461746159073901aaa1be519f1579f21bcf7c04d0768b75b13b3ffc3591798efdf42cc34c51faef946d40fb496953e93f209f768987f8b434c080f82d98ca6b9c2f19ae31819d3d0aab49eb5c0621ff52bcf12039cf261bdbf4ec4b66e239a14b276688a4fc619a3cb19aeb2999b31808ae51e75ee1ebe5d5d41772266f7e4b7bd4d4ee5afb9891a9f5ce0ddfc05593fddc8421669f7ba938fb33d3de91b7b86d76969c59efff3d032746730726128e42a3ac49986e8a545907c04990226d76a6251b2466aa30b16a6adc224d44b6e21d574a7bbce73dccf3ce5ac932bf2a0d8b2857cfdf91603bbace3ac5ea9f79e66ae15bf2982695440ecd2d055a0dbe915720b1fbde11d4355e3ee2866d04b0262fb4b0b26f6c23a377e23c02b16840cdfb3f93930b029c835b1896808e3c9710618dabbec0935adfe08af8814c407b3ee3a9075c271ddefbe251eed4e4744218bb0a1e022fe6af2ae5f6b73adfd48483bf956177002fc39a949fced972d3919b5abf46940429b5384879289bd7f11fefb5de97140188d496db8679637640862490d6e5073e47ec0772bc0ff1b30685b56b6c65f2fb1952e3eae7e3245534c005a60441450a89952262f9076dde4c07a7b729038264fbd1b8a8fed74b94c18eb344b37968d5a7f9f845d771ffd5a30ce27c0b213649792a55b6120c69ec33517c56b9e58716dc677037f4475d1e04ad4bb43cde973b5246c182388cf55224864d56f846f98e92861bd8b29639daf69642b699080cf5399d5763b79d49ae26bbd0aabf676384345d162397d3914ac2282e99f140a36df7a970a72726d0b8934012807c21c76dbb31d807c4b890e3b8ba42c77cb9918680fd0e3e07aa2081019ba5fce737221c34a61a3952c5a401ce7d6be57b79389d7fd6d42b2e1a1127c72da1d72c653b42279c67fd6472d1568a76d6b332ae66f93631707834088e61c5916dc169e7b7a8f9cb0c55f351ded646cc6f1411138d4b5eeb24356d0f166127611c2ad3799577ba45680e14fd8da026ff82ca09ac1590fb39cfcf0adf17c223c84a1e4302462687800887b32db6214fba0294b880584530d40894e62d207c52156109b2eed623e129b69fbc706f150f1d921fd97a5d609c68c1b3e83f45e03fd7b850bdd640e58a79d8b4c290f44897837d69b5c90931447b44a46028fa2896dfbba6217ced1a1e6a343894e2fadd52429772e601fedf95127f2b12e0275615499f13103ff7db7144d39c7c9f9358bdca58bec8dbe39f64846b7f61650a79940257c8d775ec51ad796676db5cfa6b95918f3b088ee0990e7a15238a031be40fad88941d719695aff87be465557479868ce04589487c323e90a2e862809f45ae4bfb7276a7082921610b56ec0162fb1841b28a355fbf462d6f3e7e540f71c4687199ad4672b2a3cbf3fa3b92a3073d4b2e0d91f57b711685e418766a5d95912ae2609fecff0bf6e152949665e4d95240be002b90ddf31bd0d0f42c014a5c72f0fa44b4c745ab1b8c89a305901098e6aaff7cdd8950bec35b41c07f6ab5ef8fa43f0232ed657ce64365289bb61bb3fb05ee5fb2e867f6b8366ed8b015d4ad02df1ac986ae116231fbbb43f23fe965467ee3b1693b7e836dea4dfc4e6fadb02dde6de4f703b7669c22046271ef660a2564f13c33b2fc5141a5e588444109c413d96e9951705a8eaf97eb0dcba2522184607247b44d2018a9a80de317551c1afbcb83e27a1e9f3bf3629605b59a172cc4d23c8067b9f368df6502b5410e8a0abaae8d25dda6a409758064967ed9fdf65e6a8a7bafb5d7c6ff0e54cde33032e7343280b913b0170b7f93cbcddf5b46192906df9656e350746bb97df11da9b4fabc10cb0d81ff812aef104e1e7545dc5a3729ad4eb41e7a9697dd7f2b6b4760ed24787ae440da49fdabd21e7a90ec379a7bbef9656189ea8f763c2a7f89498a58f0ec0ce2924fb8b3de3c9e6fef3e88c5ee1566ac417499398a0ee6735793856f3dd1b6778e84bf74588331952affacba8a576616dc239f2ba3e09c082c4a4e18006a2925bdcc7e327cab440c4afa7112b7520a74af3c02243773b80c034c518207d4d6db09cdf880279e7fd6f6b8e1cb20eef581f92f76f80ca2a3f689cace0e947b43051f313e587b75b8d88471bf34cc78fa68d3831a569226f97394dd4365c265ead32623c163fe1008ff8a4dd2cf910aae155acf894812d41ed20cd1f0b7867de6f86dda11bd7b3e1c66877842f43d026b79c435bffdd301689d80ff9e0ef1e2c5f631961b7bb9eb06e283d8d40476c01640e6cffb4b115cc3ef6d59e825d3f1ce260a52f69e0eb264a8539700398a504d8387644b37944a66544c3825b31edee9572cd87576be57dda1482502c823bc476c4a8769a88f7fcd6563b1c01e9de52e8caacabd5ff7d4c49d6561f046715dbc8bfa086b836efe57353b9cade5bf43b6f6250b72024d3b01b2bc853dc3c837960953587d6d440cbcd27b308121744d4ffabc59d04b9364ac5e683517a9ff4e5ae0dec7cdac25862a9b94a9d17c284c63aff9f777da7710049d2d0bb9cdccc643366d508a92f992d9140d535bd7553b41eedcfa1a65dd05ab94df2f20c8e1a4757a9000

            # The mdoc reader sends SessionData (status=20) to terminate the session over NFC.
            # {
            #   "status": 20
            # }
            C-APDU: 00c3000000000b5309a16673746174757314fff9
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Verifies backward-compatibility fallback when an mdoc reader has NFC Concurrent Channel
     * Engagement (`useCce = true`) enabled, but the mdoc only supports ISO/IEC 18013-5:2021 NDEF
     * engagement:
     * 1. The mdoc reader attempts to select the NFC CCE application (`A0000002480401`) and receives
     *    `6A82` (application not found).
     * 2. The mdoc reader transparently falls back to selecting the NDEF Type 4 Tag application
     *    (`D2760000850101`), completes Negotiated Handover, and retrieves the credential over BLE.
     */
    @Test
    fun testReaderWithCceEnabledFallsBackToNdef() = runTest {
        val bleCc = getStaticBleCentralClientMethod()
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods -> methods.first() },
            readerNegotiatedMethods = listOf(bleCc),
            handoverOptions = MdocReaderNfcHandoverOptions(useCce = true),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader attempts to select the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with status 6a82 (application not found).
            R-APDU: 6a82

            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-write).
            R-APDU: 000f207fff7fff0406e1047fff00009000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=31 bytes.
            R-APDU: 001f9000

            # The mdoc reader reads the 31-byte NDEF message.
            C-APDU: 00b000021f
            # The mdoc returns the initial NDEF message with the TNEP Service Parameter record.
            # NDEF Message (1 record(s), 31 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Tp" (Hex: 5470)
            #     Payload Size: 26 bytes
            #     Service Parameter (TNEP):
            #       TNEP Version: 16
            #       Service Name URI: "urn:nfc:sn:handover"
            #       Communication Mode: 0
            #       wtInt (Waiting Time Code): 0
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff9000

            # The mdoc reader writes the TNEP Service Select NDEF message (NLEN=25).
            # NDEF Message (1 record(s), 25 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Ts" (Hex: 5473)
            #     Payload Size: 20 bytes
            #     Service Select:
            #       Service Name: "urn:nfc:sn:handover"
            C-APDU: 00d600001b0019d1021454731375726e3a6e66633a736e3a68616e646f766572
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=6 bytes.
            R-APDU: 00069000

            # The mdoc reader reads the 6-byte NDEF message.
            C-APDU: 00b0000206
            # The mdoc returns the TNEP Status NDEF message.
            # NDEF Message (1 record(s), 6 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Te" (Hex: 5465)
            #     Payload Size: 1 bytes
            #     TNEP Status:
            #       Status Code: 0
            R-APDU: d102015465009000

            # The mdoc reader writes the NDEF Handover Request message (NLEN=73).
            # NDEF Message (2 record(s), 73 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 10 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 4 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: []
            #   Record #2:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            C-APDU: 00d600004b004991020a487215d102046163010130005a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a101000000
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=194 bytes.
            R-APDU: 00c29000

            # The mdoc reader reads the 194-byte NDEF message.
            C-APDU: 00b00002c2
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 194 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 15 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 9 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=null
            #     Payload Hex: 02 1c 01
            R-APDU: 91020f487315d10209616301013001046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c019000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'2ba458a32fd9486193d8c9e4c71617731d87b542064e0c03eb99cb14d11b7b43828954dd6b57f54066411e5d01e041fb1fbfa57bafa8e774d9e9bfca48d27cffed6e9621902624e884e0f0bae280f6ea4c883c27e8e2179322846a86dc49f30cb62232aa809a4ee2bbc93234cf6c8e93374673f33b58d109bb834748193c2cde576f18e533312b'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158872ba458a32fd9486193d8c9e4c71617731d87b542064e0c03eb99cb14d11b7b43828954dd6b57f54066411e5d01e041fb1fbfa57bafa8e774d9e9bfca48d27cffed6e9621902624e884e0f0bae280f6ea4c883c27e8e2179322846a86dc49f30cb62232aa809a4ee2bbc93234cf6c8e93374673f33b58d109bb834748193c2cde576f18e533312b

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'f05c446f8e8bdd0d4ad05b361cc076a35d12f9aaf865a9539f15ec162d2957c56b19005a5821c851bc3053892af88126341b595c0a026d2def6abab36578ac5f57cdf53581e5762e6b6e8edf9909bfbc7ea30f12326fe746b0ca8cf12fc9450718b63ffcb6c915e5807855df7530202771d7a3810d74ac6c14af8bfd8847ff53565ab763c15ceb87231afbb730eb3ee8bea56de0f5b409fd62a69337ad0656751c04f4264c91b2a34c9c80a02d03326a57696426f2e17310107363eaabc3fedbeb9afeb3323d238f66a31b93160d9a8877c41d6594db45630c1389d0542e7ac56c054e112c1353d8c7231dcbbf1c41b788b37002649eff2cfeacd83c74ebe6cf21d7bb871f5913773d22524bd46162678b51c8570df9fd25d92d652def82309e6288402c5600ee76dff2ee258788e77b530cad4e0ea28071d76f10599aa83fa032ccb2a30b0fde842c6fa5bdb666fc76895c3f22b3f9ff4f212ddc4118fe64c1ee3d3b271467dac322a9735f3fddcfeffec649cfb47b115a35ed562fbb9d906669afc48aafd9f095e7e9e4ae2cc0beeeeb62b51419335cd757e45eb42e5085fa668b8708943ef3572579ebf13abdf3f391d2ba7678a6ae44701c48b81159e0e2fc50f6220032b0ad776072631cca1fbd1708fb441ba35e1f1a053a1f78c8daa0925d6ded8365fc5d53597d3711c191807adc89ce1654f9edc784404f769cde15747ec58e57dd83643f3ec9c9ba71f5fdc2b2710df3bdde0c4112439877b358291e4e45cef79c2a942c17b31a768ec0ed3ce4067e9dc7216be63fc53b9f16610d3072530a439d4977a4463339f1e8cc6e026c9238cc5d20443095f4e3495cb9391ea278e77dda3fda565dd2158295619104a75172cc88fc4772914ba9a9347a306c08ccce4ad02a308eda03e4df38d8be9aef74c7f574d5087d0f2a6b074dec85710f00abf11596ba1f3260258ad820984c8a44ed2993a1167ff2e70d5c604a9d9c4b0c7d9ff1db694b721a41ed2d5ef016d9cce474cf3a490b1895c0fdaf8fc6f47712e4ee21726daba7d2dd3e9ff18a53f223ba15c17663e7f96835d230a5c15b86280102e1386999d7e0b8f4cf5d8803421daba7429540b23aeabc04f049150c8658249704c7204b90801a6e4bebb0817f02d0d40f5cb2aaf864f51dcc571f141a27d3a0869419b7207900cc0be5a3ea53396ca707a01741c4b88e0fd31a8bf5130880f3259ada73992f7f5a05b94ed53f77aafa12741fbc688aba449c7ed7b65f04cf3490139d1cf33c4b4dfbeabd17050103ba688a94ca78f075b1ebec00a28b2b08693e2bb4493cf59f57ee602818cfe70c0741f2dd5f8e61e630d10f3f9e6e42515c7566ce96a112ed340dd7fcb3ac2cf140c70c4a01516f5974211cd1cc7aab8d11aaeccef2885cdd81fc95cc19df16d6a41cba8eb3d5284d2ff5b4c4ecc7426b0fc55872879d14d81db1c19355c7520d410cec0034f073725175b5b26d9394b6277077d140915573fe695eec6515714a58d91de3cefc055208c13a5ace1b9f6acebece05de5640e6bff7792a276bc1230a8ce5d46e32e32fcf2829ff13aa3f848003c396c011d794692a808ac14ed579c79fe8a339a5fd6fbb730828058652fc6913c1e0381cb96efc28fd6103911731749874ed8a2d3946c4fd698ed1c7c18765d630e61fc69e2de22154f2a5d9b5116c205c7a4d3607fa00aa6a47d4474a98b22aec1dc2e2ca332acaf39b39e0ab5e52008e91dd562ace20af5ea69b3d15a75b92a8594a04fd019c5a06a260462e47dee1811c248fedea52b475b3e4293a751310f923cb3edbd6f4f239de72e3a5767af9d289698da175beec4251e0b17c51aa4242a6efc453cda9cedc3d3cd543cc60500d94452a7c124d7132fd9a22b02ffd1338d4596b89064929b72f6786fca3a83ee7879e0913d02ac87adbee18ee2e0ad8b6ab2972f2405160d7e05dea46fe42dd926829d843c030b7b8775ae140c6fb329540a7bf58c767437e7f66325c628d143e8bb108d63abddea748ae9ff988f350ef070f516d876f95d9d78c205ac36989be8a234e88640c90d07e542a15deaad83f11588b7c0d47a4d1f50e8833eed77e2f461795ea9aea19ae7d6f3ba124736d6923fe5056b42f4da252234d9ea131827cf6aebbd4d0a7e34ee4f7357ea66e83487f62b6847589322cc891035e21c3d4676683c31598b98dee8b3221610922b97ff39ea75e2918e6847336ddf969dde3357744958374df3c5a8c10f830ed44cebc9644abbefdac2597db36170703699553cbfe0b5229d5d6d45fc83182275d609371d58c3446c03d434bc7e79439eab59afa5b22804d8ade59d641c9d5434c66f538dc370de7bbe62f7e4c1fd8a68d180862ae80d75451393694987fa20d9398cb0db51c0356389134939747fb0cf4ffbdd3a6c2338d1a38d15b52aad58e53b387e4684635c0eaf1fbfa391fb7736318ade9ff49dc77e2889778b79dd721a362d99eb6b6ffcd2ba6a27dfeded6a1acf67d4878c3129677b7e948941fac9b2d20a2355a1ffac26157b1f514a17450e4e41da8917e11e3b8cddc113fcfea6df0cbc73bd035612eaddb39'
            # }
            BLE Mdoc->Reader: a1646461746159072ff05c446f8e8bdd0d4ad05b361cc076a35d12f9aaf865a9539f15ec162d2957c56b19005a5821c851bc3053892af88126341b595c0a026d2def6abab36578ac5f57cdf53581e5762e6b6e8edf9909bfbc7ea30f12326fe746b0ca8cf12fc9450718b63ffcb6c915e5807855df7530202771d7a3810d74ac6c14af8bfd8847ff53565ab763c15ceb87231afbb730eb3ee8bea56de0f5b409fd62a69337ad0656751c04f4264c91b2a34c9c80a02d03326a57696426f2e17310107363eaabc3fedbeb9afeb3323d238f66a31b93160d9a8877c41d6594db45630c1389d0542e7ac56c054e112c1353d8c7231dcbbf1c41b788b37002649eff2cfeacd83c74ebe6cf21d7bb871f5913773d22524bd46162678b51c8570df9fd25d92d652def82309e6288402c5600ee76dff2ee258788e77b530cad4e0ea28071d76f10599aa83fa032ccb2a30b0fde842c6fa5bdb666fc76895c3f22b3f9ff4f212ddc4118fe64c1ee3d3b271467dac322a9735f3fddcfeffec649cfb47b115a35ed562fbb9d906669afc48aafd9f095e7e9e4ae2cc0beeeeb62b51419335cd757e45eb42e5085fa668b8708943ef3572579ebf13abdf3f391d2ba7678a6ae44701c48b81159e0e2fc50f6220032b0ad776072631cca1fbd1708fb441ba35e1f1a053a1f78c8daa0925d6ded8365fc5d53597d3711c191807adc89ce1654f9edc784404f769cde15747ec58e57dd83643f3ec9c9ba71f5fdc2b2710df3bdde0c4112439877b358291e4e45cef79c2a942c17b31a768ec0ed3ce4067e9dc7216be63fc53b9f16610d3072530a439d4977a4463339f1e8cc6e026c9238cc5d20443095f4e3495cb9391ea278e77dda3fda565dd2158295619104a75172cc88fc4772914ba9a9347a306c08ccce4ad02a308eda03e4df38d8be9aef74c7f574d5087d0f2a6b074dec85710f00abf11596ba1f3260258ad820984c8a44ed2993a1167ff2e70d5c604a9d9c4b0c7d9ff1db694b721a41ed2d5ef016d9cce474cf3a490b1895c0fdaf8fc6f47712e4ee21726daba7d2dd3e9ff18a53f223ba15c17663e7f96835d230a5c15b86280102e1386999d7e0b8f4cf5d8803421daba7429540b23aeabc04f049150c8658249704c7204b90801a6e4bebb0817f02d0d40f5cb2aaf864f51dcc571f141a27d3a0869419b7207900cc0be5a3ea53396ca707a01741c4b88e0fd31a8bf5130880f3259ada73992f7f5a05b94ed53f77aafa12741fbc688aba449c7ed7b65f04cf3490139d1cf33c4b4dfbeabd17050103ba688a94ca78f075b1ebec00a28b2b08693e2bb4493cf59f57ee602818cfe70c0741f2dd5f8e61e630d10f3f9e6e42515c7566ce96a112ed340dd7fcb3ac2cf140c70c4a01516f5974211cd1cc7aab8d11aaeccef2885cdd81fc95cc19df16d6a41cba8eb3d5284d2ff5b4c4ecc7426b0fc55872879d14d81db1c19355c7520d410cec0034f073725175b5b26d9394b6277077d140915573fe695eec6515714a58d91de3cefc055208c13a5ace1b9f6acebece05de5640e6bff7792a276bc1230a8ce5d46e32e32fcf2829ff13aa3f848003c396c011d794692a808ac14ed579c79fe8a339a5fd6fbb730828058652fc6913c1e0381cb96efc28fd6103911731749874ed8a2d3946c4fd698ed1c7c18765d630e61fc69e2de22154f2a5d9b5116c205c7a4d3607fa00aa6a47d4474a98b22aec1dc2e2ca332acaf39b39e0ab5e52008e91dd562ace20af5ea69b3d15a75b92a8594a04fd019c5a06a260462e47dee1811c248fedea52b475b3e4293a751310f923cb3edbd6f4f239de72e3a5767af9d289698da175beec4251e0b17c51aa4242a6efc453cda9cedc3d3cd543cc60500d94452a7c124d7132fd9a22b02ffd1338d4596b89064929b72f6786fca3a83ee7879e0913d02ac87adbee18ee2e0ad8b6ab2972f2405160d7e05dea46fe42dd926829d843c030b7b8775ae140c6fb329540a7bf58c767437e7f66325c628d143e8bb108d63abddea748ae9ff988f350ef070f516d876f95d9d78c205ac36989be8a234e88640c90d07e542a15deaad83f11588b7c0d47a4d1f50e8833eed77e2f461795ea9aea19ae7d6f3ba124736d6923fe5056b42f4da252234d9ea131827cf6aebbd4d0a7e34ee4f7357ea66e83487f62b6847589322cc891035e21c3d4676683c31598b98dee8b3221610922b97ff39ea75e2918e6847336ddf969dde3357744958374df3c5a8c10f830ed44cebc9644abbefdac2597db36170703699553cbfe0b5229d5d6d45fc83182275d609371d58c3446c03d434bc7e79439eab59afa5b22804d8ade59d641c9d5434c66f538dc370de7bbe62f7e4c1fd8a68d180862ae80d75451393694987fa20d9398cb0db51c0356389134939747fb0cf4ffbdd3a6c2338d1a38d15b52aad58e53b387e4684635c0eaf1fbfa391fb7736318ade9ff49dc77e2889778b79dd721a362d99eb6b6ffcd2ba6a27dfeded6a1acf67d4878c3129677b7e948941fac9b2d20a2355a1ffac26157b1f514a17450e4e41da8917e11e3b8cddc113fcfea6df0cbc73bd035612eaddb39

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CH-S1`: NFC Static Handover where the mdoc offers dual-role GATT (`GattCentral` +
     * `GattPeripheral` sharing UUID `00000001-...`) plus optional 7-byte LE Bluetooth Device Address
     * (`0x1B`), producing `LE Role = 0x03` (Central & Peripheral supported, Central preferred).
     */
    @Test
    fun testGoldenStaticHandoverBleDualRole() = runTest {
        val sharedUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val mdocAddress = BlePeripheralAddress.fromEncoded("28128b37282801".fromHex())
        val dualRoleBle = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            peripheralModeUuid = sharedUuid,
            centralModeUuid = sharedUuid,
            peripheralAddress = mdocAddress,
        )
        val disambiguatedMdocMethods = MdocConnectionMethod.disambiguate(listOf(dualRoleBle), MdocRole.MDOC)
        val transcript = runSingleRequestScenario(
            staticHandoverMethods = disambiguatedMdocMethods,
            staticMdocMethodSelector = { methods ->
                methods.filterIsInstance<MdocConnectionMethodBle>().first { it.gattCentral }
            },
            readerSelectConnectionMethod = { methods ->
                assertEquals(2, methods.size)
                methods.filterIsInstance<MdocConnectionMethodBle>().first { it.gattCentral }
            },
            expectedHandoverType = MdocHandoverType.STATIC_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-only).
            R-APDU: 000f207fff7fff0406e1047fff00ff9000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=221 bytes.
            R-APDU: 00dd9000

            # The mdoc reader reads the 221-byte NDEF message.
            C-APDU: 00b00002dd
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 221 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 15 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 9 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 30 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:addr=28-28-37-8b-12-28/random
            #     Payload Hex: 02 1c 03 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00 08 1b 28 12 8b 37 28 28 01
            R-APDU: 91020f487315d10209616301013001046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55a201e016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c031107e6733397764c6b89ce4823a101000000081b28128b372828019000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'b892f3f7dfead1d98a1d82cc85415404aef725522da146448762ef2926a7119eaa0bbe0d8a7cdc25406479589157ee0a7809f4e0512c6c7c5837d0fc4cedc31e055504582add112312facace71601cdb78ca73ef12a3e1312765522c7bd1347b6508da11d0475d71fa752c394ba021512fa9200f111ab26eecafc56af16f3a822e1ad3d462f933'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887b892f3f7dfead1d98a1d82cc85415404aef725522da146448762ef2926a7119eaa0bbe0d8a7cdc25406479589157ee0a7809f4e0512c6c7c5837d0fc4cedc31e055504582add112312facace71601cdb78ca73ef12a3e1312765522c7bd1347b6508da11d0475d71fa752c394ba021512fa9200f111ab26eecafc56af16f3a822e1ad3d462f933

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'ab789404518119b7b347bf24184fb4efefcce84492d0eff0704d9ed5bcb13be14687030dd6f7dce0523ef63e75bd95c6d420d7a0bd172ed19031498f0a819aa8d3453740d95a49644fadb01d0fb07a9f1e0999294302291466617c7f147188c6d7a0638338211ce2ad85848674c301beb7420ba60778b14cd16b8d0c8bcc32ce9fba5274f2c2815ef50a680e50435e24acf3281e4e93c8cd91f55481a4167eb263e6c2c49721b63b366f02696d6adb9b16889bdcc2a7f0fc92cb1a9a1aee53e2014aac295ff51a31209a1ebfc993929e3212e6592dd1dfca922174ca2c6ed665528273bfc06103ec6583164e553661045bcd8862d0771af457da2ab354d4a1409abb9dc620532afe113caf28a001aaa1d1624af437f839683aa936434cd4afcdc93b31aaa66028b58c64fb60d986be83d6955c9f81377987f313bf574da88e9232991569d33869227a75177815304d8d7c1c133701f466fb45cdbaf32aba48efc6eb2245c127b789accf35c8993aedc58e8f98e621b4557f7700f0f2e1297947935c0be9de332a45310b5dc88ed6f9e8a7d7728d2fda6f7f43e85da350a810d616f9807cdb631beebdb216ca2e94dbf6f861ab4daf86f14bd63a31fd20d9ff4fc57daba67b27bb4d14e5181c2196b3b497cc5751aad21432d6ce539f74e473fb076125dbe7e069e3dd61e469aae001201b1c4a102e1a9d8d5696a0f06162db612141a38874af6ce82cdea6cdeb5773e518735b7147737007dc8eca21c365c3bebcfc08dee0e0fd7f995d357841106cc5063c3302ba1d83314d50edef5a6830f3ed8002f002413d88ee8dce900a7f5c2bfe01b742c7912fb20a3e1071c7c7dfb86ae09eef4a12af98d32a21b5b8f750886e9ffecbf53d30962782f10b844b2a2f6739234e007c0fad3b18f7dedb97ad7d64d3b5dfb463246c8211e8ed5ef8936e559385c6113724f25f41ec5de9a51e9a19fbdc305f82b72eed0e9c4d3e2b9cb6f1de896d6787890c2084af61ec0ff30384d445302fa7ad3d9bcec083bb2519f51e440da7b4dd14546a0d52e776cc4b5d4fc83dd2b013e6d2b0c87b58c62884ee622c4a1dffec5b73c31216f2a3d7e7a2695f1bbad9599df6819fc8b9a04379a90381e9310ec371142cf353a82c6e921eba16d0dde6b4e2fdf9cd57bda219dbf25d5b7f927e1072d9ded67dd66c3b950ef587e59b05cb958a52e0026290dc862062e6087f10fc7b8eb94d874ca522ea2c0a676442f10d1cc2387632339a65bc292c4f65277a5c8bd8e5051cb382c047611d6378d84c8996be8345d117c1b54514ab535b5c279713d005a725d16c782c2d7f8a268b92f837cbd1c6de1938f65f3a8a04b797e9d1e25710fa183fda0a7223c9f7d79a2e3bafc2ee7f3e2735db6dbd7f6b871c832d006270d6d136a27bfde35fe8e570f703340405fad489c7a71d5e6bce2124a98f2f2448a1caacd58ca449406ddc2a39de912d81427cc4ada78037514009f9b38cc8c7a121abf471619f1287bcf632d0fdbcd96e9ea9ad770c7b4eb69b4da150420900d300c93d015ccd8fb1c00657f0a03bb4d9cebb781546a1c48ccafd82d30be30d0583dbbcf3e8ac31a968c4f61777159c385150583cc8855bb40b78cb315c4a7fde63c09490419e56e16b6f1200e104ec8254f1a5814e432a149f5d390e8dce84d16b9291ec784dae54f55604f4ccf2d7aae3928bc727805c493af68bf77fcfc28f6f3801cd4e9ea041df3a8f6b5805aff1a856a47e2b6b2dd4a36aa62fcc3416f80f0869c2fffc4541489014125526376710b1dd6b287dd5e332cabdd2cbb76282ca51693311bc78935836d4151039aa2e0ae966dc9f45a19abdb8cee12ab1a953d0b52243796c4d66dc220c1cb08e527091b4c320a4ddcf1be10b8d8c3220c16be7cccecc8c277fed6620f910054a3951dbf50cc5f4484fedc3bf01b55c3aa44b6f8f608bf343beac58eacddcc25badb6554c036bfedf65b58454831ced7e96e1902f1bfdce51b1d4ad72489d6bf0742e1abe37aa2dbef27d154206875db157f0159b608bb6476a152cefbb490a98995db9411db9b8a462ecdd84d20511dab1e9421283fb4c537a093087c15c481b82c3abca91690f953d9abbc2d0758cd88118755f5531de770590b511529aa53d67e3c6f78b569760a46e66f85d13ce650500c8c28c966472294d1e7478498f6d8bd707de8dfe898df16c1caa46a81a71ee9fc7ebfb80a7c2b0e72c84ab6ffb63d64d5da2fa2bb207abaf21750a83594211bbfa5b5e2e986eb161f2bfd2af9f096103d50f5c753a595497fe3c83fc56028ce3e98e04df9e7d595b77f4dd2a24e62406ee822ebe7c410cefffbba59d1472c0d1f351f769a151f866fd3fa4d58a00aaccbe5c6a2bc633e021e143a12931523d96ee6e9927b6c5cd622d6bf8e86827d6df7e5e976a55df8acdde3ca8c625f31f344d46002778e4544696eb7548501f1ba19d8a546adbb188238fd6521e1d7e1557a8b161be034c1ba7797d5e6f944945f54200fd766fde3674478427e067a05da035f9590fee90eb569741b69efe876942f0bac4d0e50de646d278b871b2bb46562a16e264d2b7'
            # }
            BLE Mdoc->Reader: a1646461746159072fab789404518119b7b347bf24184fb4efefcce84492d0eff0704d9ed5bcb13be14687030dd6f7dce0523ef63e75bd95c6d420d7a0bd172ed19031498f0a819aa8d3453740d95a49644fadb01d0fb07a9f1e0999294302291466617c7f147188c6d7a0638338211ce2ad85848674c301beb7420ba60778b14cd16b8d0c8bcc32ce9fba5274f2c2815ef50a680e50435e24acf3281e4e93c8cd91f55481a4167eb263e6c2c49721b63b366f02696d6adb9b16889bdcc2a7f0fc92cb1a9a1aee53e2014aac295ff51a31209a1ebfc993929e3212e6592dd1dfca922174ca2c6ed665528273bfc06103ec6583164e553661045bcd8862d0771af457da2ab354d4a1409abb9dc620532afe113caf28a001aaa1d1624af437f839683aa936434cd4afcdc93b31aaa66028b58c64fb60d986be83d6955c9f81377987f313bf574da88e9232991569d33869227a75177815304d8d7c1c133701f466fb45cdbaf32aba48efc6eb2245c127b789accf35c8993aedc58e8f98e621b4557f7700f0f2e1297947935c0be9de332a45310b5dc88ed6f9e8a7d7728d2fda6f7f43e85da350a810d616f9807cdb631beebdb216ca2e94dbf6f861ab4daf86f14bd63a31fd20d9ff4fc57daba67b27bb4d14e5181c2196b3b497cc5751aad21432d6ce539f74e473fb076125dbe7e069e3dd61e469aae001201b1c4a102e1a9d8d5696a0f06162db612141a38874af6ce82cdea6cdeb5773e518735b7147737007dc8eca21c365c3bebcfc08dee0e0fd7f995d357841106cc5063c3302ba1d83314d50edef5a6830f3ed8002f002413d88ee8dce900a7f5c2bfe01b742c7912fb20a3e1071c7c7dfb86ae09eef4a12af98d32a21b5b8f750886e9ffecbf53d30962782f10b844b2a2f6739234e007c0fad3b18f7dedb97ad7d64d3b5dfb463246c8211e8ed5ef8936e559385c6113724f25f41ec5de9a51e9a19fbdc305f82b72eed0e9c4d3e2b9cb6f1de896d6787890c2084af61ec0ff30384d445302fa7ad3d9bcec083bb2519f51e440da7b4dd14546a0d52e776cc4b5d4fc83dd2b013e6d2b0c87b58c62884ee622c4a1dffec5b73c31216f2a3d7e7a2695f1bbad9599df6819fc8b9a04379a90381e9310ec371142cf353a82c6e921eba16d0dde6b4e2fdf9cd57bda219dbf25d5b7f927e1072d9ded67dd66c3b950ef587e59b05cb958a52e0026290dc862062e6087f10fc7b8eb94d874ca522ea2c0a676442f10d1cc2387632339a65bc292c4f65277a5c8bd8e5051cb382c047611d6378d84c8996be8345d117c1b54514ab535b5c279713d005a725d16c782c2d7f8a268b92f837cbd1c6de1938f65f3a8a04b797e9d1e25710fa183fda0a7223c9f7d79a2e3bafc2ee7f3e2735db6dbd7f6b871c832d006270d6d136a27bfde35fe8e570f703340405fad489c7a71d5e6bce2124a98f2f2448a1caacd58ca449406ddc2a39de912d81427cc4ada78037514009f9b38cc8c7a121abf471619f1287bcf632d0fdbcd96e9ea9ad770c7b4eb69b4da150420900d300c93d015ccd8fb1c00657f0a03bb4d9cebb781546a1c48ccafd82d30be30d0583dbbcf3e8ac31a968c4f61777159c385150583cc8855bb40b78cb315c4a7fde63c09490419e56e16b6f1200e104ec8254f1a5814e432a149f5d390e8dce84d16b9291ec784dae54f55604f4ccf2d7aae3928bc727805c493af68bf77fcfc28f6f3801cd4e9ea041df3a8f6b5805aff1a856a47e2b6b2dd4a36aa62fcc3416f80f0869c2fffc4541489014125526376710b1dd6b287dd5e332cabdd2cbb76282ca51693311bc78935836d4151039aa2e0ae966dc9f45a19abdb8cee12ab1a953d0b52243796c4d66dc220c1cb08e527091b4c320a4ddcf1be10b8d8c3220c16be7cccecc8c277fed6620f910054a3951dbf50cc5f4484fedc3bf01b55c3aa44b6f8f608bf343beac58eacddcc25badb6554c036bfedf65b58454831ced7e96e1902f1bfdce51b1d4ad72489d6bf0742e1abe37aa2dbef27d154206875db157f0159b608bb6476a152cefbb490a98995db9411db9b8a462ecdd84d20511dab1e9421283fb4c537a093087c15c481b82c3abca91690f953d9abbc2d0758cd88118755f5531de770590b511529aa53d67e3c6f78b569760a46e66f85d13ce650500c8c28c966472294d1e7478498f6d8bd707de8dfe898df16c1caa46a81a71ee9fc7ebfb80a7c2b0e72c84ab6ffb63d64d5da2fa2bb207abaf21750a83594211bbfa5b5e2e986eb161f2bfd2af9f096103d50f5c753a595497fe3c83fc56028ce3e98e04df9e7d595b77f4dd2a24e62406ee822ebe7c410cefffbba59d1472c0d1f351f769a151f866fd3fa4d58a00aaccbe5c6a2bc633e021e143a12931523d96ee6e9927b6c5cd622d6bf8e86827d6df7e5e976a55df8acdde3ca8c625f31f344d46002778e4544696eb7548501f1ba19d8a546adbb188238fd6521e1d7e1557a8b161be034c1ba7797d5e6f944945f54200fd766fde3674478427e067a05da035f9590fee90eb569741b69efe876942f0bac4d0e50de646d278b871b2bb46562a16e264d2b7

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CH-S2`: NFC Static Handover where the mdoc offers `GattPeripheral` +
     * `DirectL2capPeripheral` (`peripheralModeUuid = 00000002-...`, `directL2capPsm = 129`),
     * producing `ac` `["mdoc", "chble"]`, `LE Role = 0x00`, and `ChBleOptions = {0: 129, 1: true, 2: true}`.
     */
    @Test
    fun testGoldenStaticHandoverBleDirectL2capPeripheral() = runTest {
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val peripheralBle = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = null,
            directL2capPsm = 129,
        )
        val disambiguatedMdocMethods = MdocConnectionMethod.disambiguate(listOf(peripheralBle), MdocRole.MDOC)
        val transcript = runSingleRequestScenario(
            staticHandoverMethods = disambiguatedMdocMethods,
            staticMdocMethodSelector = { methods ->
                methods.filterIsInstance<MdocConnectionMethodBle>().first { it.directL2capPeripheral }
            },
            readerSelectConnectionMethod = { methods ->
                assertEquals(2, methods.size)
                val first = methods.first() as MdocConnectionMethodBle
                assertEquals(true, first.directL2capPeripheral)
                assertEquals(129, first.directL2capPsm)
                first
            },
            expectedHandoverType = MdocHandoverType.STATIC_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-only).
            R-APDU: 000f207fff7fff0406e1047fff00ff9000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=261 bytes.
            R-APDU: 01059000

            # The mdoc reader reads the 261-byte NDEF message.
            C-APDU: 00b00002000105
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (4 record(s), 261 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 21 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 15 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc", "chble"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=00000002-a123-48ce-896b-4c76973373e6:peripheral_l2cap_mode:uuid=00000002-a123-48ce-896b-4c76973373e6:psm=129
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 02 00 00 00
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 8 bytes
            #     ChBleOptions CBOR:
            #       {
            #         0: 129,
            #         1: true,
            #         2: true
            #       }
            R-APDU: 910215487315d1020f616301013002046d646f63056368626c651c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f51a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a1020000005c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a300188101f502f59000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'11c2447b6606fe74def41aacf7f6f853b8debb8454d5152e5f196748151b034795de802a9b209ffc9a0a1f6daef31901297a0336c0130af368fd6109c5f1b4186f446543ab420c95ebdc45afde723f7160a300624c5c2f3f80e214a092086abf97652c2909b15171b66a31609093ffa26bbb071ef7ed0f8e1e5ec9c3819d7aa071d01369aefe86'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa6464617461588711c2447b6606fe74def41aacf7f6f853b8debb8454d5152e5f196748151b034795de802a9b209ffc9a0a1f6daef31901297a0336c0130af368fd6109c5f1b4186f446543ab420c95ebdc45afde723f7160a300624c5c2f3f80e214a092086abf97652c2909b15171b66a31609093ffa26bbb071ef7ed0f8e1e5ec9c3819d7aa071d01369aefe86

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'65a76aa29c81e7da866cd7f08aa1105c6ad0832ceaa1437b2e6b6f02f93e7dbed5d34100835cdd4995fc0ee99de1064b210c3162f6c3d7907a6bdb43c3e1e96fe7ea0610459c436b16d06e28293bce008622ddf182c2f93a99d43b02e0a422c5eb87638d4486f8beb2353e626b5ea37395907ea861624302b28c02bcdf0496dc5442eec28c9fc5e1625fd7183b438e09b86b279b1aa76db0e0ee08a9e15f7afed74d45d0f432b586f9209a767e1a89ec4741d1563456dfc934dfe21a3368f73c55328a23ab651be3d4b0699dc92ab0b337f9933917365fdd045acb08b057d64942b05893b1f77ce1b64c76f3b1fa240ec6e7050ffc8dea171a770414d61157f168feebba780f331f2b1c203f9abf0bc7390efe2a63fe246f5168b80da38a3287067648da7a732aaf70cd67f5471407375d8f69d7414fe5b7f17e86493dc3e6bca9af34e1b4aece96de68ff8605af4fe98f946c57f1287c665d6e8cbe385a7da1d2805d8f86cfeec4de4df062effcfabd4e0bc6dfe1c071220430eebece118b1bd1d0c5e549dcd529006c4006a7f915819d7f94ba82b716a93363eb0751192bb657cbece7d0d38fac3bdc571549baf0679b938e35fee8d6f9832e9c1cde4cdc82f0bc8bd5a8e36bcf12eff38ad3ae0712ab00431abe94b050d17edd162266d23ca84ac2f5674efffdcc2debaf1e822cfd9cd4f3283688aeaa7a413366943e9a0cbc68d3347cedb24dcffbd6a5ab06e8672c465130455b0cb06b05469d3cd72aaafa136f26094dd415128b84e4f97b084301ec47719dd3e910106d2054781f3ad129345342a741b82d889a92302757ba26c779d25d6218908212568aca176c4066f89c5c7d63ddb808f45d72c0e9af139212b0c9ccca5f2e4324c028bca21fbe6539e0c99a51b5b62480163f1d6b738e86568d6e41a46b413920b5cbf63c642e7a45be5f7689b779bb1334b1d455461b0362581daf2fb71d5734d55012c2d41f4538a88a476460bb7b5d6140efda601df07d4efbe848f0356d4efc76ec445aaaf5152a68846fcbfc5c5c91c211a2a1aad53673b9d957a279397d6c6e1f534516a1dcfb23305d4a2e0ce52235f403ecf504ab4629d2f490678832cd81fd6b0b0478fdd867a907cbbe14032400681562060d8904c22c041b5baee78582dee5271bc205dbd0634f05bf4ee467c1b22ddf4ae285dc6d786d3aed403a88c0487b2696c78e7464ece60f9bb28896ea5f2b4d357833799d7cd903ec505362b08e8c59c139e4c10914e1592be814a4d5c496ee39618617bd4b482a76b0572eece5198c3d99d5df50b7a51b1ccc503d53ac5fcbd56d4d1ccfaaccd84e43d7b71c4a7e583780f77783f831f95ef9f16228ea9692f1a88e94ed694ac36f34718e52e51b5c3a65f7d71abf50713fbefdc63cd6d3e2d8165443c09aa50ef0c2f38b1711ed9fa68a0811ab507c2a572a8aff99c54802e7db1f8589a6135b6432539ebd8521c22dc344370222eddc93d4a99c33497caeaa3f2b031f9852fc397c845e4815853788ad0b635550ab2edb90f0e19f262c52ce738423cf82db760ecf082dc6b8141b6419bf0c6c9cca981a6391a1e2b33a7ab2cbf57d1dc88751a56e0dcf18e2aa61b05764213d3c29861e7baa3da5ebe06aa2e9f1e4b70b58506484572c68c9a34d4bd7e394bb98c84545ed11745990bdc441005427476afbf0e785f22885835b132d59936139ed4f8aed49383ea7efa6f0d179ac46e97b009619be4beb638f2f55d55db1a9c867e371290673e173ab2e8b921a14649f7b3587a6104422782dfe41c507e89e08d8e6efc0a272dcd873ec07482ba5d70641c2b184a09a8ffa0c449da095e75c27554ef9e4e6712473a5b531129e3f7d696d875c98df66b3ca8038aa0e3d1950d7282fec02389fc62f01482e7dd87f18bb95eb18b52affbaf13572fca9984b3adff406c5b47762439ea5bf7438c8b963aeb8ccde33f815935b0ebd1b9cd780b6e5b9bacd0a18642f20d01f9f5d7e4242ffe05a44be2328f737ff5ff9eebfaea3c0006669c1ccb10ed7522f5fcf9d0498779626eac8e20fb1b3b09e6fca87f34a6273a49a32aad15b63ad131c04df33320161ee0d947a5c8fab1d8461d130f75214c3a7586accf604a8fe85b0d386e559c72d72553ff7877f7eb047946aee71489cac3d33947e42774b3050e9db1a5b2b8934aea7f47dfc59e20c7af016fba0f798c859f29926427715f521d18603ff98ea249ddaba8f81817c11241cfff2dc640e13d06a72f5a476509ffb590422f189329526b00cadd7ab005b9e23761b921edaff3b0fd0f75f142a66af408e4e8f879827004cc2597a30c2ec28aa2d4dd0b4ef5239ece176b3051c46d84651d7640f66a42597b5a89ce98f76182a3832b4fd6e0d6bafd213f58744f2de5717acfe04230bb553ada8e57efa8739653a1510645ec9c66574c433001426aae17526217647b65d23bb5c96cc6ab4b8dda699545bd902888da94c1e40f7a85e64eeea270ec77a2ddbe9d0747c9b1d44c27fa734d5873873be4895892a814a8a00ae8a0138af8f3fd7b4f20bfc06beec5e618d9b2dafa3d586142f42d98991a116ae'
            # }
            BLE Mdoc->Reader: a1646461746159072f65a76aa29c81e7da866cd7f08aa1105c6ad0832ceaa1437b2e6b6f02f93e7dbed5d34100835cdd4995fc0ee99de1064b210c3162f6c3d7907a6bdb43c3e1e96fe7ea0610459c436b16d06e28293bce008622ddf182c2f93a99d43b02e0a422c5eb87638d4486f8beb2353e626b5ea37395907ea861624302b28c02bcdf0496dc5442eec28c9fc5e1625fd7183b438e09b86b279b1aa76db0e0ee08a9e15f7afed74d45d0f432b586f9209a767e1a89ec4741d1563456dfc934dfe21a3368f73c55328a23ab651be3d4b0699dc92ab0b337f9933917365fdd045acb08b057d64942b05893b1f77ce1b64c76f3b1fa240ec6e7050ffc8dea171a770414d61157f168feebba780f331f2b1c203f9abf0bc7390efe2a63fe246f5168b80da38a3287067648da7a732aaf70cd67f5471407375d8f69d7414fe5b7f17e86493dc3e6bca9af34e1b4aece96de68ff8605af4fe98f946c57f1287c665d6e8cbe385a7da1d2805d8f86cfeec4de4df062effcfabd4e0bc6dfe1c071220430eebece118b1bd1d0c5e549dcd529006c4006a7f915819d7f94ba82b716a93363eb0751192bb657cbece7d0d38fac3bdc571549baf0679b938e35fee8d6f9832e9c1cde4cdc82f0bc8bd5a8e36bcf12eff38ad3ae0712ab00431abe94b050d17edd162266d23ca84ac2f5674efffdcc2debaf1e822cfd9cd4f3283688aeaa7a413366943e9a0cbc68d3347cedb24dcffbd6a5ab06e8672c465130455b0cb06b05469d3cd72aaafa136f26094dd415128b84e4f97b084301ec47719dd3e910106d2054781f3ad129345342a741b82d889a92302757ba26c779d25d6218908212568aca176c4066f89c5c7d63ddb808f45d72c0e9af139212b0c9ccca5f2e4324c028bca21fbe6539e0c99a51b5b62480163f1d6b738e86568d6e41a46b413920b5cbf63c642e7a45be5f7689b779bb1334b1d455461b0362581daf2fb71d5734d55012c2d41f4538a88a476460bb7b5d6140efda601df07d4efbe848f0356d4efc76ec445aaaf5152a68846fcbfc5c5c91c211a2a1aad53673b9d957a279397d6c6e1f534516a1dcfb23305d4a2e0ce52235f403ecf504ab4629d2f490678832cd81fd6b0b0478fdd867a907cbbe14032400681562060d8904c22c041b5baee78582dee5271bc205dbd0634f05bf4ee467c1b22ddf4ae285dc6d786d3aed403a88c0487b2696c78e7464ece60f9bb28896ea5f2b4d357833799d7cd903ec505362b08e8c59c139e4c10914e1592be814a4d5c496ee39618617bd4b482a76b0572eece5198c3d99d5df50b7a51b1ccc503d53ac5fcbd56d4d1ccfaaccd84e43d7b71c4a7e583780f77783f831f95ef9f16228ea9692f1a88e94ed694ac36f34718e52e51b5c3a65f7d71abf50713fbefdc63cd6d3e2d8165443c09aa50ef0c2f38b1711ed9fa68a0811ab507c2a572a8aff99c54802e7db1f8589a6135b6432539ebd8521c22dc344370222eddc93d4a99c33497caeaa3f2b031f9852fc397c845e4815853788ad0b635550ab2edb90f0e19f262c52ce738423cf82db760ecf082dc6b8141b6419bf0c6c9cca981a6391a1e2b33a7ab2cbf57d1dc88751a56e0dcf18e2aa61b05764213d3c29861e7baa3da5ebe06aa2e9f1e4b70b58506484572c68c9a34d4bd7e394bb98c84545ed11745990bdc441005427476afbf0e785f22885835b132d59936139ed4f8aed49383ea7efa6f0d179ac46e97b009619be4beb638f2f55d55db1a9c867e371290673e173ab2e8b921a14649f7b3587a6104422782dfe41c507e89e08d8e6efc0a272dcd873ec07482ba5d70641c2b184a09a8ffa0c449da095e75c27554ef9e4e6712473a5b531129e3f7d696d875c98df66b3ca8038aa0e3d1950d7282fec02389fc62f01482e7dd87f18bb95eb18b52affbaf13572fca9984b3adff406c5b47762439ea5bf7438c8b963aeb8ccde33f815935b0ebd1b9cd780b6e5b9bacd0a18642f20d01f9f5d7e4242ffe05a44be2328f737ff5ff9eebfaea3c0006669c1ccb10ed7522f5fcf9d0498779626eac8e20fb1b3b09e6fca87f34a6273a49a32aad15b63ad131c04df33320161ee0d947a5c8fab1d8461d130f75214c3a7586accf604a8fe85b0d386e559c72d72553ff7877f7eb047946aee71489cac3d33947e42774b3050e9db1a5b2b8934aea7f47dfc59e20c7af016fba0f798c859f29926427715f521d18603ff98ea249ddaba8f81817c11241cfff2dc640e13d06a72f5a476509ffb590422f189329526b00cadd7ab005b9e23761b921edaff3b0fd0f75f142a66af408e4e8f879827004cc2597a30c2ec28aa2d4dd0b4ef5239ece176b3051c46d84651d7640f66a42597b5a89ce98f76182a3832b4fd6e0d6bafd213f58744f2de5717acfe04230bb553ada8e57efa8739653a1510645ec9c66574c433001426aae17526217647b65d23bb5c96cc6ab4b8dda699545bd902888da94c1e40f7a85e64eeea270ec77a2ddbe9d0747c9b1d44c27fa734d5873873be4895892a814a8a00ae8a0138af8f3fd7b4f20bfc06beec5e618d9b2dafa3d586142f42d98991a116ae

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CH-N2`: NFC Negotiated Handover where the reader offers `GattPeripheral` only
     * (`Hr` has `LE Role = 0x01` and NO `0x07` UUID), and the mdoc selects `GattPeripheral` with
     * `peripheralModeUuid = 00000002-...` (`Hs` has `LE Role = 0x00` and `0x07` UUID `00000002-...`).
     */
    @Test
    fun testGoldenNegotiatedHandoverGattPeripheralOnly() = runTest {
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            peripheralModeUuid = null,
            centralModeUuid = null,
        )
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                val chosen = methods.single() as MdocConnectionMethodBle
                assertNull(chosen.peripheralModeUuid)
                chosen.copy(peripheralModeUuid = peripheralUuid)
            },
            readerNegotiatedMethods = listOf(readerOffer),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-write).
            R-APDU: 000f207fff7fff0406e1047fff00009000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=31 bytes.
            R-APDU: 001f9000

            # The mdoc reader reads the 31-byte NDEF message.
            C-APDU: 00b000021f
            # The mdoc returns the initial NDEF message with the TNEP Service Parameter record.
            # NDEF Message (1 record(s), 31 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Tp" (Hex: 5470)
            #     Payload Size: 26 bytes
            #     Service Parameter (TNEP):
            #       TNEP Version: 16
            #       Service Name URI: "urn:nfc:sn:handover"
            #       Communication Mode: 0
            #       wtInt (Waiting Time Code): 0
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff9000

            # The mdoc reader writes the TNEP Service Select NDEF message (NLEN=25).
            # NDEF Message (1 record(s), 25 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Ts" (Hex: 5473)
            #     Payload Size: 20 bytes
            #     Service Select:
            #       Service Name: "urn:nfc:sn:handover"
            C-APDU: 00d600001b0019d1021454731375726e3a6e66633a736e3a68616e646f766572
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=6 bytes.
            R-APDU: 00069000

            # The mdoc reader reads the 6-byte NDEF message.
            C-APDU: 00b0000206
            # The mdoc returns the TNEP Status NDEF message.
            # NDEF Message (1 record(s), 6 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Te" (Hex: 5465)
            #     Payload Size: 1 bytes
            #     TNEP Status:
            #       Status Code: 0
            R-APDU: d102015465009000

            # The mdoc reader writes the NDEF Handover Request message (NLEN=55).
            # NDEF Message (2 record(s), 55 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 10 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 4 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: []
            #   Record #2:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=null
            #     Payload Hex: 02 1c 01
            C-APDU: 00d6000039003791020a487215d102046163010130005a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c01
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=212 bytes.
            R-APDU: 00d49000

            # The mdoc reader reads the 212-byte NDEF message.
            C-APDU: 00b00002d4
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 212 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 15 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 9 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=00000002-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 02 00 00 00
            R-APDU: 91020f487315d10209616301013001046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f55a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a1020000009000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'41bd6c9a0741bb9e131301df3e2368456fbf0374b715973c000e792df1f563201c6a4cc60a6ebf4e089e8941a7dc05924d924dc21ab96fa2814ff06594d9c67915b81bdfd38bd976e425c564b00ce431ad4d8bf241fc0137d230e9d7aeaaf9a21bfe5e431fec3090581665b7170160eb6d58df74fa6921f65752c87626532236eda024a53cad7d'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa6464617461588741bd6c9a0741bb9e131301df3e2368456fbf0374b715973c000e792df1f563201c6a4cc60a6ebf4e089e8941a7dc05924d924dc21ab96fa2814ff06594d9c67915b81bdfd38bd976e425c564b00ce431ad4d8bf241fc0137d230e9d7aeaaf9a21bfe5e431fec3090581665b7170160eb6d58df74fa6921f65752c87626532236eda024a53cad7d

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'c1045ff03243483e65ee9cc8d744b83f024dff2858085c5eb5967d1d661220675358f98288911fcd894ce0bc32ff80cb8e06a07aec64f6680bcd6fd9b0055218267e785afb1291c5b57e7148ebbfb8ca2d362f4f0acaf8db567b7bd3f97e9fbfe6dfd6063ef5833cbb9fd3edfcd7edf2b1fb431d50522723b54f51e9cee674ce5d88502dbb88ed8d42e8327a9ccfef267b5aaf4e84b37d7a9b4f6895072e3473619895b04b212373b93dd56e4463632dcbdb2bb8b148f65d6b9e05721ae23f05749b81fafccb8a719f19f968c3c22488a0e3c8c9c7c0f68708c33eb4325a22625714a6320bb53b351d622e31da08e91259a3496c0ee151d33189e66bb639a039afd8893bb39ce97088a79aec4b2164d0bb60d2a53087d8d7fde4379381ec517bcafeccbcc36fece6cfb55cc4285ebfadc110e7858010771efe5397809c66acd172e006ff1f1471fc763b140b5c183047125a01037f6d7d72a52127d35eada8665e4f09266aed500e41a6060fbe7e393ceaa6c818f6569d6b5ec1c88a4b73bf6f5b384fc3b79a2351ad1123b812a8b2622fa7eb3ea988c83f3163f9bc30bd2b0b150afb9ad48618403456d943807946189c5fbd748d30e51a88cd216419dd0c660ccf7370a06df9de5573618bb5d4de2cacdf0a8c6422741f1846c9d447da34348a2ce161b1b9262532c05ea6ca5fa2f0d1baf94a972e6b51c8a4316b75cadc563db6c91888bad7269a33be7c3012764f118e86a2e268290cd21db1fa43c5aed728ce1ce4f775fe0e5bb14832d4130f1ba64b773c3b6b3b3ecb1f3258a0f80c76857fc0da98f8106ad108cb38172dc48ed4a65e6de8bf047b2364090a59bec04c277384f4696ff467ae4e1dbbe649b90cbd038c4d79d808591786a6783f1f14db5ad005962a28cc72ab005374663379d21af285b77bce4c8e81a116f3d83db8b33285cb072fde803f5cd222d3a0b5002eb7e48f87ea606c2b1fba4730cd866a03c4a5253b0d9ead834cd4282bd0cba2651a7af4aac2eda2825411d1143639652cef18d7f2e0936b91989853dc88648af7e39fd7540f97abd911ab13330db20d6505ba0bffa468dd7905ed256eba4c07828dcc8e6d447814243538af5fec5df33e8f660d39c2ebf270917c4e03108a93ed4aa1d45db81d514aeee5cb3f2b3bb8f4530d93eb43bc6d41911cc97c3ad4ae7e4857a65fb778a5c23489cb7d3d59184a310b4a0d739291d7adb233573cde89f6df82adca370948444d128688c1c746fa3bd303ff4a7e73d754c8df0358d1b50f8e131738b0e3dfbc87766b95739cac26c763093b20bbcf7d63d1550dd71825a07521e7b5515582d20447e847d33515db8fc624eab8149f7ba3d2a44b605bcfe914f025c06add6afbca55553b27615571461e2dd87dca37a0d8443f9c7ffecfeaeae0f919651612b2dd4b9208613e77f191e822840b2c6445d380bdbe60579dc2c85caef7cba2e425f5929a38b7d14961f797c81ccfdbd06ee0073901787880780c29f4f522b832d19b61da3d320df0f2c79b66e645ac3a42a4aa3186d0d4079b8ea99e09073f6fecb5fce61fafa1aa874452054f0931aaf321581bf2c1d36577a02a46e5b729c96b0ab539e220581cf77bcbb50ca9cbd97d67d61024c11687ac86c9a6b712d14c62a643c10dce3107750357461df205d5a6106f0136ef3d153329fdd1fa6385624666060cf00771497a649ab64dc42c3ebeead66b822e930a6776519b679e42467c11b839b10cb208a88f47beb16eef315add099ad777f17fffcd43657f5e8438e95ed001c35a8d8eab61e64b6465994e561554ed3aeb69f95fc56d961477fd7a3b60d275f0d8095da8bd4de9f796e66e4d35551c870afee0b43c5468c994dd5bf135034f78cd4c8adc1d6c8ef1bf6399cc46ec18c6a4d9bfaf6f4eccfa073d675d28afc0bac49502d053ae4d182c593d953b46c3ae9e16b8926982d904b3e075c1501cbe873b46eab9ec8cc699af0170f4bc63a04fa3196feb93e04372074bd5aca320ee6d53ed239237fa7128889431eccb851676b858c2c870ec63c3cd0bd7f1cba1eda681e05dcaeeaf3603a781bd58cb67487c8a3c0bedcaa7cdf7bd4766de7481294e81c56b97c50f8dd48787266560f575e684e832d45098b7c34a99a197a1594e9042aac0630fcb13597518120e11f3b90768c9361252753a538c4196bacb875b1ac848d7cc5816d0c299c440eba59a931cdc9cb805d53c8de8949c35b2ecd341b62703d35aa3001355e13315c5d174a3caa7f8b6b16267dae920c2afc7e7ed366d88026d9475b2c41862840da7caa42bcabd945102bd7f9db6541dbb3070c9c533f9be4a69fb813f0be23d3b8282c76296b2bc8fadd641d782e71c6ecb749b4db4eb78156e81f38e84cb2ec64ab1622752b4aa294c7a18fe0fc03545dc73d1013be4c371b50a6386689724607587f2ae6cfce7c59293a5473b9f954d6e9c2e91f753daa104c5a1fbf4a32d1ee2545d44c0bced4c4bb3101540ff06065fe8717725af82408aa460e096db41b145a9c5aec8c92c3b5c227cc1185c1307cb45f73ff475054ddd045d6a6cc766cc1d2a422f9d4f4529'
            # }
            BLE Mdoc->Reader: a1646461746159072fc1045ff03243483e65ee9cc8d744b83f024dff2858085c5eb5967d1d661220675358f98288911fcd894ce0bc32ff80cb8e06a07aec64f6680bcd6fd9b0055218267e785afb1291c5b57e7148ebbfb8ca2d362f4f0acaf8db567b7bd3f97e9fbfe6dfd6063ef5833cbb9fd3edfcd7edf2b1fb431d50522723b54f51e9cee674ce5d88502dbb88ed8d42e8327a9ccfef267b5aaf4e84b37d7a9b4f6895072e3473619895b04b212373b93dd56e4463632dcbdb2bb8b148f65d6b9e05721ae23f05749b81fafccb8a719f19f968c3c22488a0e3c8c9c7c0f68708c33eb4325a22625714a6320bb53b351d622e31da08e91259a3496c0ee151d33189e66bb639a039afd8893bb39ce97088a79aec4b2164d0bb60d2a53087d8d7fde4379381ec517bcafeccbcc36fece6cfb55cc4285ebfadc110e7858010771efe5397809c66acd172e006ff1f1471fc763b140b5c183047125a01037f6d7d72a52127d35eada8665e4f09266aed500e41a6060fbe7e393ceaa6c818f6569d6b5ec1c88a4b73bf6f5b384fc3b79a2351ad1123b812a8b2622fa7eb3ea988c83f3163f9bc30bd2b0b150afb9ad48618403456d943807946189c5fbd748d30e51a88cd216419dd0c660ccf7370a06df9de5573618bb5d4de2cacdf0a8c6422741f1846c9d447da34348a2ce161b1b9262532c05ea6ca5fa2f0d1baf94a972e6b51c8a4316b75cadc563db6c91888bad7269a33be7c3012764f118e86a2e268290cd21db1fa43c5aed728ce1ce4f775fe0e5bb14832d4130f1ba64b773c3b6b3b3ecb1f3258a0f80c76857fc0da98f8106ad108cb38172dc48ed4a65e6de8bf047b2364090a59bec04c277384f4696ff467ae4e1dbbe649b90cbd038c4d79d808591786a6783f1f14db5ad005962a28cc72ab005374663379d21af285b77bce4c8e81a116f3d83db8b33285cb072fde803f5cd222d3a0b5002eb7e48f87ea606c2b1fba4730cd866a03c4a5253b0d9ead834cd4282bd0cba2651a7af4aac2eda2825411d1143639652cef18d7f2e0936b91989853dc88648af7e39fd7540f97abd911ab13330db20d6505ba0bffa468dd7905ed256eba4c07828dcc8e6d447814243538af5fec5df33e8f660d39c2ebf270917c4e03108a93ed4aa1d45db81d514aeee5cb3f2b3bb8f4530d93eb43bc6d41911cc97c3ad4ae7e4857a65fb778a5c23489cb7d3d59184a310b4a0d739291d7adb233573cde89f6df82adca370948444d128688c1c746fa3bd303ff4a7e73d754c8df0358d1b50f8e131738b0e3dfbc87766b95739cac26c763093b20bbcf7d63d1550dd71825a07521e7b5515582d20447e847d33515db8fc624eab8149f7ba3d2a44b605bcfe914f025c06add6afbca55553b27615571461e2dd87dca37a0d8443f9c7ffecfeaeae0f919651612b2dd4b9208613e77f191e822840b2c6445d380bdbe60579dc2c85caef7cba2e425f5929a38b7d14961f797c81ccfdbd06ee0073901787880780c29f4f522b832d19b61da3d320df0f2c79b66e645ac3a42a4aa3186d0d4079b8ea99e09073f6fecb5fce61fafa1aa874452054f0931aaf321581bf2c1d36577a02a46e5b729c96b0ab539e220581cf77bcbb50ca9cbd97d67d61024c11687ac86c9a6b712d14c62a643c10dce3107750357461df205d5a6106f0136ef3d153329fdd1fa6385624666060cf00771497a649ab64dc42c3ebeead66b822e930a6776519b679e42467c11b839b10cb208a88f47beb16eef315add099ad777f17fffcd43657f5e8438e95ed001c35a8d8eab61e64b6465994e561554ed3aeb69f95fc56d961477fd7a3b60d275f0d8095da8bd4de9f796e66e4d35551c870afee0b43c5468c994dd5bf135034f78cd4c8adc1d6c8ef1bf6399cc46ec18c6a4d9bfaf6f4eccfa073d675d28afc0bac49502d053ae4d182c593d953b46c3ae9e16b8926982d904b3e075c1501cbe873b46eab9ec8cc699af0170f4bc63a04fa3196feb93e04372074bd5aca320ee6d53ed239237fa7128889431eccb851676b858c2c870ec63c3cd0bd7f1cba1eda681e05dcaeeaf3603a781bd58cb67487c8a3c0bedcaa7cdf7bd4766de7481294e81c56b97c50f8dd48787266560f575e684e832d45098b7c34a99a197a1594e9042aac0630fcb13597518120e11f3b90768c9361252753a538c4196bacb875b1ac848d7cc5816d0c299c440eba59a931cdc9cb805d53c8de8949c35b2ecd341b62703d35aa3001355e13315c5d174a3caa7f8b6b16267dae920c2afc7e7ed366d88026d9475b2c41862840da7caa42bcabd945102bd7f9db6541dbb3070c9c533f9be4a69fb813f0be23d3b8282c76296b2bc8fadd641d782e71c6ecb749b4db4eb78156e81f38e84cb2ec64ab1622752b4aa294c7a18fe0fc03545dc73d1013be4c371b50a6386689724607587f2ae6cfce7c59293a5473b9f954d6e9c2e91f753daa104c5a1fbf4a32d1ee2545d44c0bced4c4bb3101540ff06065fe8717725af82408aa460e096db41b145a9c5aec8c92c3b5c227cc1185c1307cb45f73ff475054ddd045d6a6cc766cc1d2a422f9d4f4529

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CH-N3`: NFC Negotiated Handover where the reader offers all 4 BLE modes
     * (`Hr` has `ac` `["chble"]`, `LE Role = 0x02`, `0x07` UUID `00000001-...`,
     * `ChBleOptions = {0: 128, 1: true, 2: true}`), and the mdoc selects `DirectL2capCentral`
     * (`Hs` has `ac` `["mdoc", "chble"]`, `LE Role = 0x01`, NO UUID, `ChBleOptions = {1: false, 2: true}`).
     */
    @Test
    fun testGoldenNegotiatedHandoverAllFourModesSelectDirectL2capCentral() = runTest {
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            directL2capPeripheral = true,
            directL2capCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
        )
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                assertEquals(4, methods.size)
                val first = methods.first() as MdocConnectionMethodBle
                assertEquals(true, first.directL2capCentral)
                assertEquals(128, first.directL2capPsm)
                first
            },
            readerNegotiatedMethods = listOf(readerOffer),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-write).
            R-APDU: 000f207fff7fff0406e1047fff00009000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=31 bytes.
            R-APDU: 001f9000

            # The mdoc reader reads the 31-byte NDEF message.
            C-APDU: 00b000021f
            # The mdoc returns the initial NDEF message with the TNEP Service Parameter record.
            # NDEF Message (1 record(s), 31 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Tp" (Hex: 5470)
            #     Payload Size: 26 bytes
            #     Service Parameter (TNEP):
            #       TNEP Version: 16
            #       Service Name URI: "urn:nfc:sn:handover"
            #       Communication Mode: 0
            #       wtInt (Waiting Time Code): 0
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff9000

            # The mdoc reader writes the TNEP Service Select NDEF message (NLEN=25).
            # NDEF Message (1 record(s), 25 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Ts" (Hex: 5473)
            #     Payload Size: 20 bytes
            #     Service Select:
            #       Service Name: "urn:nfc:sn:handover"
            C-APDU: 00d600001b0019d1021454731375726e3a6e66633a736e3a68616e646f766572
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=6 bytes.
            R-APDU: 00069000

            # The mdoc reader reads the 6-byte NDEF message.
            C-APDU: 00b0000206
            # The mdoc returns the TNEP Status NDEF message.
            # NDEF Message (1 record(s), 6 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Te" (Hex: 5465)
            #     Payload Size: 1 bytes
            #     TNEP Status:
            #       Status Code: 0
            R-APDU: d102015465009000

            # The mdoc reader writes the NDEF Handover Request message (NLEN=122).
            # NDEF Message (3 record(s), 122 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 16 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 10 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["chble"]
            #   Record #2:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=null:peripheral_l2cap_mode:uuid=null:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:central_l2cap_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:psm=128
            #     Payload Hex: 02 1c 02 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 8 bytes
            #     ChBleOptions CBOR:
            #       {
            #         0: 128,
            #         1: true,
            #         2: true
            #       }
            C-APDU: 00d600007c007a910210487215d1020a616301013001056368626c651a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a1010000005c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a300188001f502f5
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=240 bytes.
            R-APDU: 00f09000

            # The mdoc reader reads the 240-byte NDEF message.
            C-APDU: 00b00002f0
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (4 record(s), 240 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 21 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 15 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc", "chble"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:central_l2cap_mode:uuid=null
            #     Payload Hex: 02 1c 01
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 5 bytes
            #     ChBleOptions CBOR:
            #       {
            #         1: false,
            #         2: true
            #       }
            R-APDU: 910215487315d1020f616301013002046d646f63056368626c651c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f51a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c015c1a050569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a201f402f59000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'4691272204f52cf51634538de7c3619bd5ed0f98c6c169c1e462f6b64e6e963ca2a713638ed9f3d66cb7c4432c766d1b74e45e64545a5af0cb7cdbd86fbaa3c278699a328a09d5bcdf9de5b50af9c3e2b41088e81e944c87deac4cd63a5ff54a035fc0679576dbfb202d03d0d3e12e3fa049f7c22dce5cdfed0e38c269aa82f27308bbd7f31575'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158874691272204f52cf51634538de7c3619bd5ed0f98c6c169c1e462f6b64e6e963ca2a713638ed9f3d66cb7c4432c766d1b74e45e64545a5af0cb7cdbd86fbaa3c278699a328a09d5bcdf9de5b50af9c3e2b41088e81e944c87deac4cd63a5ff54a035fc0679576dbfb202d03d0d3e12e3fa049f7c22dce5cdfed0e38c269aa82f27308bbd7f31575

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'ef41c66b91cb59c484e6bd21f90a167efbd1f346a73428215626e7dd224ec15dedc2576049e945e373511f169659a642b92df9f0bbc157262bd42de58e401a18847e3997a6688d7f673ae7759f472e41bc4e834d8bd458f7e7927ed2dabedfe6cc587b75b1835892d1bd0f1ba66660f5c73a139c64ebcbe270ebfe261344eee2775e51d82987afb990c4529babae93fd9d81e1887285ac66b3e31aa957bcbe6830bc874331b968c7659fbcbdb56feb7d24d626b1e42ee033b852ef25fa44f2456be10b6450c94be88904e5c0c2e8592d3346e53557279cd59162d840f68740e9817c15aebf0889a97ab07257217d0fb2622572f9b93f15b6fde610322444c9ddbf92389e65debf4841efabe4fdf723c3b22527eb1c471ef1f64f3c2563ebd160501404e1ae2969d8443553968130b8f9ebbef9b9c4bce85b96241495f88054da2421b456cce27cd96e225e742a9293f981b6c5a2e3651c28d1c3d3026471ca6c86f0833a8679f23532e19b52050213e1c0dbc60cc63c4406bf8abf09816d42e9b5efdaf2f4bc30b0b80a2930a90e9104069b12145ffc3de5960b780bb59cf3cff34bc865b54c9506d1e270c6e58a9a3aae43838341a1f72571ec9d16a61e74d0097b9f37269103a383453a4e988e5542b5fe0fb6d04c4b352e8172b893e4b712d6e5c3b590f5546d47b3089896c0625c3d30c45c9c6f11eae91277a343d979a62c23a433cc70bfc0fdc2ee5751096dfdb195985552ddc497e6deb0cdd0be6cf791c3cd3c7a939c35bb0f79a5504dc868c76255f476ad2391d5943c74402fcb88a0a002f2226b0631ccaa41b3acdf4e3ae6071c765d66af392e854ecedd65985b498963459d71241ede5fcb6d235395f7426213e16b36242734a5ca470000a907520ed064b13216bf37c058e40634c84944085164f1f5cec589239f610301e6cd5c06245d0098a27f358f7d3a6f66d0d27283675093c7b7ab76451f0b17f4e9c028ebab8c6bbbb98a18adbecbf2ab86cbb03a58f4abc530646c47d0a88eb2468c927944574612d62fa01f85e902e85c23bc87b7f5aba05d54544da701a31cb1121144332fd9ab21146416b7f07b3d922734913c7729f17bcb01bd6b237dc88743363c9f89cf363e29ead68fe337eda848d916782f573f15834ff4d046d8e236aeac377da087688cecb247cdb9d958599a9ed815913f0ac63ffa7d01a3f1479744e891db515372181f92a31a192b8e6225e86a6c83ea277f7b23e031e6034ab44580a535528861b8755a0b20b87633237454b3afd17ca9cf48fdd31c3458a84c6828097d17b996cb86093a51f911b9cc081abd318c24cc2c6062035c798198fd35a1bb1c4b60a3be67b6215e64abcbfeec8157d95c23dd6f9ba4fe40ac75ce1978f910c4d86a10430998e7a4148a15c753e5d7e5bc6e32827ea5d3ddabb14d4db44fa0cd4c47cb4f8acf5e85b5dc8b11d0847d1f30d5a76ad3c798efa588a51eda52d903d3b4bd9c36d1437eec8a3b0ffcb6a89ef924b96c66ec3ebac37e9b6febf6fb2d915583486c7d1a406def041d0c2717dd01a9abed8167b802e4b44ef0ecddb7dea48a6c46dbfe691714a5d31585fe0a30f1de3f1ede668dea6b374bf26c51c91f618f22c8ff5c5c4916e0d55373d5df1d47638b86050b85b50be26ea86b0e1abac8c6f08b858d430391a906a7e913ad2a8428f4f1f1122ae8a0c052666af8c8da9b978db0da9fec8f16c9735455a1037010c43944b70dcb8a129c460012f2d0d7775c8e3f849347ac8c859d3a74681faf1f7cc54ee93d09bd7247944169e6199c87f3c8acf6c6f4895bf2e47307c9c5b9f208443fad6f3b52e153e780fab3109b2fb580c638c0517e65ff88d85ffd4198fe0f1507036ddde013ed703594eed3963a9193cb1bde9aaed0350b770491bbc5dd60b4ce5538298fae75a9e01044de145e1eadccbfd35b82d58b059a8a22bb64c67767319f299409b5bc21e1542991596964a42619097251f2a0b5e8d53fe81a54aee468e4e2bdd558ceba1a0e489989cbec38446deae06ce1e1271b976e5ea4e734310d6701ac62a8b073e6a1faa60e91553fd848ef0a94184326512e2c89035037dc36f3c6752fde09e649240ce7d08230ac3f925bda0ef03470acea1a0751cc51329af28a5e2f677e350f959d013660b845b3b897dde732cb0f7ecc363198e0545221fe0b3a0a47767e913420f2472c967f88578b83cbe3fad73b0d9dc3be1de6a662c4ee950c6b5bc55b46c8fb6bbb933279cf6c51998d2b4f5210630aba81cd9436240ae2ffc9a692f34be6b6f82a80bffd39c7c0c4303b7eb0e2e31203ad84087c6953f381464af8027270b167c28df260b397f77b3d32f78cf9a438f597d2a71fcc648d4396deaef0bc7ca0c0fd7e8f89c3d74c8fcfbc20e7a297c4c5ddde387c72b2802436475325b47660f4f65ee0a2f7f8d001ed40f39bb685975d685b963f73fdc6d6cfd7c0609500abe27454448be2dbf0dece1bf2b74ff0b40e12b9815655ce9da96f249c9537d242caaa3be449fe3796a629f49e50248a2c9fd2f8a7d79fb9d443a92e74e91825e71fde19d14b1787ee6b2426ac6b'
            # }
            BLE Mdoc->Reader: a1646461746159072fef41c66b91cb59c484e6bd21f90a167efbd1f346a73428215626e7dd224ec15dedc2576049e945e373511f169659a642b92df9f0bbc157262bd42de58e401a18847e3997a6688d7f673ae7759f472e41bc4e834d8bd458f7e7927ed2dabedfe6cc587b75b1835892d1bd0f1ba66660f5c73a139c64ebcbe270ebfe261344eee2775e51d82987afb990c4529babae93fd9d81e1887285ac66b3e31aa957bcbe6830bc874331b968c7659fbcbdb56feb7d24d626b1e42ee033b852ef25fa44f2456be10b6450c94be88904e5c0c2e8592d3346e53557279cd59162d840f68740e9817c15aebf0889a97ab07257217d0fb2622572f9b93f15b6fde610322444c9ddbf92389e65debf4841efabe4fdf723c3b22527eb1c471ef1f64f3c2563ebd160501404e1ae2969d8443553968130b8f9ebbef9b9c4bce85b96241495f88054da2421b456cce27cd96e225e742a9293f981b6c5a2e3651c28d1c3d3026471ca6c86f0833a8679f23532e19b52050213e1c0dbc60cc63c4406bf8abf09816d42e9b5efdaf2f4bc30b0b80a2930a90e9104069b12145ffc3de5960b780bb59cf3cff34bc865b54c9506d1e270c6e58a9a3aae43838341a1f72571ec9d16a61e74d0097b9f37269103a383453a4e988e5542b5fe0fb6d04c4b352e8172b893e4b712d6e5c3b590f5546d47b3089896c0625c3d30c45c9c6f11eae91277a343d979a62c23a433cc70bfc0fdc2ee5751096dfdb195985552ddc497e6deb0cdd0be6cf791c3cd3c7a939c35bb0f79a5504dc868c76255f476ad2391d5943c74402fcb88a0a002f2226b0631ccaa41b3acdf4e3ae6071c765d66af392e854ecedd65985b498963459d71241ede5fcb6d235395f7426213e16b36242734a5ca470000a907520ed064b13216bf37c058e40634c84944085164f1f5cec589239f610301e6cd5c06245d0098a27f358f7d3a6f66d0d27283675093c7b7ab76451f0b17f4e9c028ebab8c6bbbb98a18adbecbf2ab86cbb03a58f4abc530646c47d0a88eb2468c927944574612d62fa01f85e902e85c23bc87b7f5aba05d54544da701a31cb1121144332fd9ab21146416b7f07b3d922734913c7729f17bcb01bd6b237dc88743363c9f89cf363e29ead68fe337eda848d916782f573f15834ff4d046d8e236aeac377da087688cecb247cdb9d958599a9ed815913f0ac63ffa7d01a3f1479744e891db515372181f92a31a192b8e6225e86a6c83ea277f7b23e031e6034ab44580a535528861b8755a0b20b87633237454b3afd17ca9cf48fdd31c3458a84c6828097d17b996cb86093a51f911b9cc081abd318c24cc2c6062035c798198fd35a1bb1c4b60a3be67b6215e64abcbfeec8157d95c23dd6f9ba4fe40ac75ce1978f910c4d86a10430998e7a4148a15c753e5d7e5bc6e32827ea5d3ddabb14d4db44fa0cd4c47cb4f8acf5e85b5dc8b11d0847d1f30d5a76ad3c798efa588a51eda52d903d3b4bd9c36d1437eec8a3b0ffcb6a89ef924b96c66ec3ebac37e9b6febf6fb2d915583486c7d1a406def041d0c2717dd01a9abed8167b802e4b44ef0ecddb7dea48a6c46dbfe691714a5d31585fe0a30f1de3f1ede668dea6b374bf26c51c91f618f22c8ff5c5c4916e0d55373d5df1d47638b86050b85b50be26ea86b0e1abac8c6f08b858d430391a906a7e913ad2a8428f4f1f1122ae8a0c052666af8c8da9b978db0da9fec8f16c9735455a1037010c43944b70dcb8a129c460012f2d0d7775c8e3f849347ac8c859d3a74681faf1f7cc54ee93d09bd7247944169e6199c87f3c8acf6c6f4895bf2e47307c9c5b9f208443fad6f3b52e153e780fab3109b2fb580c638c0517e65ff88d85ffd4198fe0f1507036ddde013ed703594eed3963a9193cb1bde9aaed0350b770491bbc5dd60b4ce5538298fae75a9e01044de145e1eadccbfd35b82d58b059a8a22bb64c67767319f299409b5bc21e1542991596964a42619097251f2a0b5e8d53fe81a54aee468e4e2bdd558ceba1a0e489989cbec38446deae06ce1e1271b976e5ea4e734310d6701ac62a8b073e6a1faa60e91553fd848ef0a94184326512e2c89035037dc36f3c6752fde09e649240ce7d08230ac3f925bda0ef03470acea1a0751cc51329af28a5e2f677e350f959d013660b845b3b897dde732cb0f7ecc363198e0545221fe0b3a0a47767e913420f2472c967f88578b83cbe3fad73b0d9dc3be1de6a662c4ee950c6b5bc55b46c8fb6bbb933279cf6c51998d2b4f5210630aba81cd9436240ae2ffc9a692f34be6b6f82a80bffd39c7c0c4303b7eb0e2e31203ad84087c6953f381464af8027270b167c28df260b397f77b3d32f78cf9a438f597d2a71fcc648d4396deaef0bc7ca0c0fd7e8f89c3d74c8fcfbc20e7a297c4c5ddde387c72b2802436475325b47660f4f65ee0a2f7f8d001ed40f39bb685975d685b963f73fdc6d6cfd7c0609500abe27454448be2dbf0dece1bf2b74ff0b40e12b9815655ce9da96f249c9537d242caaa3be449fe3796a629f49e50248a2c9fd2f8a7d79fb9d443a92e74e91825e71fde19d14b1787ee6b2426ac6b

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CH-N4`: NFC Negotiated Handover where the reader offers all 4 BLE modes and the mdoc
     * selects `DirectL2capPeripheral` with `peripheralModeUuid = 00000002-...` and `directL2capPsm = 129`
     * (`Hs` has `ac` `["mdoc", "chble"]`, `LE Role = 0x00`, `0x07` UUID `00000002-...`,
     * `ChBleOptions = {0: 129, 1: false, 2: true}`).
     */
    @Test
    fun testGoldenNegotiatedHandoverAllFourModesSelectDirectL2capPeripheral() = runTest {
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            directL2capPeripheral = true,
            directL2capCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
        )
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                assertEquals(4, methods.size)
                val chosen = methods.filterIsInstance<MdocConnectionMethodBle>()
                    .first { it.directL2capPeripheral }
                assertNull(chosen.peripheralModeUuid)
                assertNull(chosen.directL2capPsm)
                chosen.copy(
                    peripheralModeUuid = peripheralUuid,
                    directL2capPsm = 129,
                )
            },
            readerNegotiatedMethods = listOf(readerOffer),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-write).
            R-APDU: 000f207fff7fff0406e1047fff00009000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=31 bytes.
            R-APDU: 001f9000

            # The mdoc reader reads the 31-byte NDEF message.
            C-APDU: 00b000021f
            # The mdoc returns the initial NDEF message with the TNEP Service Parameter record.
            # NDEF Message (1 record(s), 31 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Tp" (Hex: 5470)
            #     Payload Size: 26 bytes
            #     Service Parameter (TNEP):
            #       TNEP Version: 16
            #       Service Name URI: "urn:nfc:sn:handover"
            #       Communication Mode: 0
            #       wtInt (Waiting Time Code): 0
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff9000

            # The mdoc reader writes the TNEP Service Select NDEF message (NLEN=25).
            # NDEF Message (1 record(s), 25 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Ts" (Hex: 5473)
            #     Payload Size: 20 bytes
            #     Service Select:
            #       Service Name: "urn:nfc:sn:handover"
            C-APDU: 00d600001b0019d1021454731375726e3a6e66633a736e3a68616e646f766572
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=6 bytes.
            R-APDU: 00069000

            # The mdoc reader reads the 6-byte NDEF message.
            C-APDU: 00b0000206
            # The mdoc returns the TNEP Status NDEF message.
            # NDEF Message (1 record(s), 6 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Te" (Hex: 5465)
            #     Payload Size: 1 bytes
            #     TNEP Status:
            #       Status Code: 0
            R-APDU: d102015465009000

            # The mdoc reader writes the NDEF Handover Request message (NLEN=122).
            # NDEF Message (3 record(s), 122 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 16 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 10 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["chble"]
            #   Record #2:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=null:peripheral_l2cap_mode:uuid=null:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:central_l2cap_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:psm=128
            #     Payload Hex: 02 1c 02 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 8 bytes
            #     ChBleOptions CBOR:
            #       {
            #         0: 128,
            #         1: true,
            #         2: true
            #       }
            C-APDU: 00d600007c007a910210487215d1020a616301013001056368626c651a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a1010000005c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a300188001f502f5
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=261 bytes.
            R-APDU: 01059000

            # The mdoc reader reads the 261-byte NDEF message.
            C-APDU: 00b00002000105
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (4 record(s), 261 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 21 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 15 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc", "chble"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_l2cap_mode:uuid=00000002-a123-48ce-896b-4c76973373e6:psm=129
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 02 00 00 00
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 8 bytes
            #     ChBleOptions CBOR:
            #       {
            #         0: 129,
            #         1: false,
            #         2: true
            #       }
            R-APDU: 910215487315d1020f616301013002046d646f63056368626c651c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f51a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a1020000005c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a300188101f402f59000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'7df925d1952ef3ac6424afda0ff801e3cce34d0b52bd619325fbb89af5d9df7071e408b70e1d6a751e484f783388663fe507751aa28275a3a9ee07d13bbb982dbdc19bbd56efdd0636392364f9623975a86dba493c3b73719ed05de0b55d0778f0eb73425e1e39941b1887b47e88ff727759ec131d026512198ad111e9430e465b241ff98205e0'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158877df925d1952ef3ac6424afda0ff801e3cce34d0b52bd619325fbb89af5d9df7071e408b70e1d6a751e484f783388663fe507751aa28275a3a9ee07d13bbb982dbdc19bbd56efdd0636392364f9623975a86dba493c3b73719ed05de0b55d0778f0eb73425e1e39941b1887b47e88ff727759ec131d026512198ad111e9430e465b241ff98205e0

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'f06449189162648a9bbbc57342990c237e6af364aab0bf524b8650dba801cdc4378674259ace161bfe4d3f9ecef5cb068046a16b1d35aac9bb73198ce861711195319d00334813a892dbbd58c1dd0c36c724fd1c5f36b67e82ab2005f2c802cc0f0b045ebc4dd26b76462d541a41d6f9bf9ff71e111bcd005754616855461d6d8231104af38b49540cfbfc47b3f0c09ba176f06d38888d727a0ca6d3afd1e495f6d3c9d635a3fbe0a02966b9c3837d45fc3181b76549fc0392a9d7dafb953f2002bf99b189dff1f611affc193bd9d5ec97498643d6200a99731237cd49a1a080c7c65e1cb661c5019a39e3f2a8acb341265d635b38dc0149b5917e90ce2bababc6c976d636925557ab484619a213c037758d89e8d2ac74d98085fdbbf0d19ee0503b13fe8efb92fcc8623fd67956d1139c26358418c0446cc3bc6812ca3eb2c622bf84069b5cdc5dbea968bcd67a179925da208b7e1d7cc7c7457abf495ca478f941165fffee34d7da1e73aecfc443465ec8dcae0c87dabad88d6f29be6461eb3bb53cafcfdc8bd4860ba668012a9490733bbb50688c258dedb531708d330dacd930f0351734ef8d742ad8c11b3cb192fd8144244d71806337f1c6037b16861a80af2eea7419deef4b08cb06358a95b9a6e1bff9bdaa1ff876f036974a51953ddf9752781b2defa3d443c4e0a27217625e4704e724dcba54b84c0e2aa9c7d0822999575b2e94e2aee09666394737b7cf11352f5c3a1024eb5e44ae5e8e23399c13cf859f67b2645b6badd626918ebdf39307c586f5e19725920c4bbfbf26db76099b88eaae3569f42f1b92747c997c8753b19fa0d6efd8d768818019e1101a2ef151b20cbfe4be48b5e44210c749cfc4280bf9fcfde4f23b04314b75693c4496f1996c9f3cf82c845dec6cea7733923544b1a049dc5033545e51fc0466fc6e6f9fe19c028d8538b6ec38eabfec31d8911a953d66dc21a2496f6bd2fa0bc5a9c2ff166bd14d9c84fc625a7fc28498c168b5e8e034b22df809295b5785afb6aeafc49e72aad2d8e5ab6ffc068434acad6e0ac04dbbc6d7655f31f5ab3c33e300d63c9378da9d05fb943e988eb3ed010ddd8030861a29bf1817161f66e4c61d627b8632f853dfc10c2368d6b58c499d03cc826fa97346f52d80582f77fbeb6282f1da17d016605ce7fa64a4703174656f0e00d5c9b0fec0bac905571ee2a01529baf8d3395669df75f76af652bf8f45467f4530cd16d70ca86d69171c9e74a67f454a583f61a380452baf226bbb745bee01796b20a5a46df6fc1be140728a8ad4bb80618b71bfc67a8e8ea4175a4ad90e4b74a432781d7d077a22c9180e95d8fa1ac79de9293495b5ff85384d32284d5cf9fb3abbed020b147f923ed020742826b191f933858389cddb9fa0a7ae80c56d5b47cb85c78f2d05b671c6b3df6bb6bc5be47ebf24ce5a891e46b1635421de2c6c4921549ba35260be880a2954fd5f3a9b7bd138733ae982f3753b6ecea37099efefd50977cf53ab966bf872444730a8bdf95cf5d96a8432084b80e0b45a383165534805fd8bcf6e5f1c68df8bf2ab90aa966136ce66151ffe0ca4b3ff5b2a194b0ee3dc584d20d727db159288b0ebd2886046b4f55153d39f4db284df2c4891c657768b954e81ad702e034cb3626291a29e6e44d226e170776ea417d167260250c38751c4b890a835d9907114f13a680366533245fdab1d800877b58935a874ee2649539eb10206e401766ec41730d2333749e558a9bb62d6631afbc95959c723aeb4a2f17e9fb108982f0e25b69ad6990f068d962968a2f913a3e7de243775c9beb960ea0549f36cdee14e98aa79dc1d237db14cfff472863a93923aa355e2a0f2fdae0ccd72f487c2031253a2d7bd71769c4520b23993b6d4c1a065a6906c4f8c4d1a3e5578a5796b0ba222afc22e2e097fb0f1da69221c54564ba0eb8530896a18ec3a4baddd11230495eac4aa3965f724e83904ba68cb27ec54d3105f22e5ddf30907156d2685cf8bd129232f758ad794acbcf7e0e4cf6720a188cff1d142e2fff8438e2de5020c501cd1917c39d70526f087f26b55a34ba1b005f8a349e11daba00bad7bfcfa9069ea51cee05902690b0be385c161fd48980d3ad93873bf9cf05a8ac4c6c18a8d8eda05d08b6c5809c686ac710835d7c79cdc856477381d2d3551479bd3120efaa642be35b90f2d10d906890c3eee6abe70fab897629c4ce70a361bc491714ed848e7f30942ed31864d96fe18c16b53efecff1f8b3ad73c2ab373a59e917ac730a42f75aa105bb5fc2b0b1cd18795e3d4e8b2508df918d37ff6b31f31cca9d922e33eb2a3b347503bcf73f0c0af4d6dffb65d8f6c4bd2468270d91d8905a46a5eef5183f17ec28b79e1a102531a19beca5fdc78b691baf7be64f2f214fd134836601301a95ea42c0f207125e91e9c7198bd4eacbd191b8c7d5f41aff39780a1b755ce84001f3e2cd515722a9936ee2fcd18c5306771fbf72c4c757a0da5fd685ca614fd1ff189b41c549401547f121d3c0c40812cd38a594515bbff2f9866ad74dfc53c2753e379c43d45a4ebd811f12e22898478dbba'
            # }
            BLE Mdoc->Reader: a1646461746159072ff06449189162648a9bbbc57342990c237e6af364aab0bf524b8650dba801cdc4378674259ace161bfe4d3f9ecef5cb068046a16b1d35aac9bb73198ce861711195319d00334813a892dbbd58c1dd0c36c724fd1c5f36b67e82ab2005f2c802cc0f0b045ebc4dd26b76462d541a41d6f9bf9ff71e111bcd005754616855461d6d8231104af38b49540cfbfc47b3f0c09ba176f06d38888d727a0ca6d3afd1e495f6d3c9d635a3fbe0a02966b9c3837d45fc3181b76549fc0392a9d7dafb953f2002bf99b189dff1f611affc193bd9d5ec97498643d6200a99731237cd49a1a080c7c65e1cb661c5019a39e3f2a8acb341265d635b38dc0149b5917e90ce2bababc6c976d636925557ab484619a213c037758d89e8d2ac74d98085fdbbf0d19ee0503b13fe8efb92fcc8623fd67956d1139c26358418c0446cc3bc6812ca3eb2c622bf84069b5cdc5dbea968bcd67a179925da208b7e1d7cc7c7457abf495ca478f941165fffee34d7da1e73aecfc443465ec8dcae0c87dabad88d6f29be6461eb3bb53cafcfdc8bd4860ba668012a9490733bbb50688c258dedb531708d330dacd930f0351734ef8d742ad8c11b3cb192fd8144244d71806337f1c6037b16861a80af2eea7419deef4b08cb06358a95b9a6e1bff9bdaa1ff876f036974a51953ddf9752781b2defa3d443c4e0a27217625e4704e724dcba54b84c0e2aa9c7d0822999575b2e94e2aee09666394737b7cf11352f5c3a1024eb5e44ae5e8e23399c13cf859f67b2645b6badd626918ebdf39307c586f5e19725920c4bbfbf26db76099b88eaae3569f42f1b92747c997c8753b19fa0d6efd8d768818019e1101a2ef151b20cbfe4be48b5e44210c749cfc4280bf9fcfde4f23b04314b75693c4496f1996c9f3cf82c845dec6cea7733923544b1a049dc5033545e51fc0466fc6e6f9fe19c028d8538b6ec38eabfec31d8911a953d66dc21a2496f6bd2fa0bc5a9c2ff166bd14d9c84fc625a7fc28498c168b5e8e034b22df809295b5785afb6aeafc49e72aad2d8e5ab6ffc068434acad6e0ac04dbbc6d7655f31f5ab3c33e300d63c9378da9d05fb943e988eb3ed010ddd8030861a29bf1817161f66e4c61d627b8632f853dfc10c2368d6b58c499d03cc826fa97346f52d80582f77fbeb6282f1da17d016605ce7fa64a4703174656f0e00d5c9b0fec0bac905571ee2a01529baf8d3395669df75f76af652bf8f45467f4530cd16d70ca86d69171c9e74a67f454a583f61a380452baf226bbb745bee01796b20a5a46df6fc1be140728a8ad4bb80618b71bfc67a8e8ea4175a4ad90e4b74a432781d7d077a22c9180e95d8fa1ac79de9293495b5ff85384d32284d5cf9fb3abbed020b147f923ed020742826b191f933858389cddb9fa0a7ae80c56d5b47cb85c78f2d05b671c6b3df6bb6bc5be47ebf24ce5a891e46b1635421de2c6c4921549ba35260be880a2954fd5f3a9b7bd138733ae982f3753b6ecea37099efefd50977cf53ab966bf872444730a8bdf95cf5d96a8432084b80e0b45a383165534805fd8bcf6e5f1c68df8bf2ab90aa966136ce66151ffe0ca4b3ff5b2a194b0ee3dc584d20d727db159288b0ebd2886046b4f55153d39f4db284df2c4891c657768b954e81ad702e034cb3626291a29e6e44d226e170776ea417d167260250c38751c4b890a835d9907114f13a680366533245fdab1d800877b58935a874ee2649539eb10206e401766ec41730d2333749e558a9bb62d6631afbc95959c723aeb4a2f17e9fb108982f0e25b69ad6990f068d962968a2f913a3e7de243775c9beb960ea0549f36cdee14e98aa79dc1d237db14cfff472863a93923aa355e2a0f2fdae0ccd72f487c2031253a2d7bd71769c4520b23993b6d4c1a065a6906c4f8c4d1a3e5578a5796b0ba222afc22e2e097fb0f1da69221c54564ba0eb8530896a18ec3a4baddd11230495eac4aa3965f724e83904ba68cb27ec54d3105f22e5ddf30907156d2685cf8bd129232f758ad794acbcf7e0e4cf6720a188cff1d142e2fff8438e2de5020c501cd1917c39d70526f087f26b55a34ba1b005f8a349e11daba00bad7bfcfa9069ea51cee05902690b0be385c161fd48980d3ad93873bf9cf05a8ac4c6c18a8d8eda05d08b6c5809c686ac710835d7c79cdc856477381d2d3551479bd3120efaa642be35b90f2d10d906890c3eee6abe70fab897629c4ce70a361bc491714ed848e7f30942ed31864d96fe18c16b53efecff1f8b3ad73c2ab373a59e917ac730a42f75aa105bb5fc2b0b1cd18795e3d4e8b2508df918d37ff6b31f31cca9d922e33eb2a3b347503bcf73f0c0af4d6dffb65d8f6c4bd2468270d91d8905a46a5eef5183f17ec28b79e1a102531a19beca5fdc78b691baf7be64f2f214fd134836601301a95ea42c0f207125e91e9c7198bd4eacbd191b8c7d5f41aff39780a1b755ce84001f3e2cd515722a9936ee2fcd18c5306771fbf72c4c757a0da5fd685ca614fd1ff189b41c549401547f121d3c0c40812cd38a594515bbff2f9866ad74dfc53c2753e379c43d45a4ebd811f12e22898478dbba

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CH-N5` + `T-CH-N6`: NFC Negotiated Handover where the reader offers all 4 BLE modes
     * AND includes optional `ReaderEngagement` (`"mdocreader"`, `{0: "1.1", 1: [1, 24(<< EReaderKey >>)]}`,
     * `ac` `["mdocreader", "chble"]`), and the mdoc selects `GattCentral` (`Hs` has `ac` `["mdoc", "chble"]`,
     * `LE Role = 0x01`, NO UUID, `ChBleOptions = {1: true, 2: false}`).
     */
    @Test
    fun testGoldenNegotiatedHandoverAllFourModesWithReaderEngagementSelectGattCentral() = runTest {
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val eReaderKey = getAnnexDEReaderKey()
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            directL2capPeripheral = true,
            directL2capCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
        )
        val readerEngagement = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {}
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                assertEquals(4, methods.size)
                methods.filterIsInstance<MdocConnectionMethodBle>().first { it.gattCentral }
            },
            readerNegotiatedMethods = listOf(readerOffer),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = false,
                readerEngagement = readerEngagement,
            ),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader selects the NDEF Type 4 Tag application.
            C-APDU: 00a4040007d2760000850101
            # The mdoc acknowledges the application selection.
            R-APDU: 9000

            # The mdoc reader selects the Capability Container (CC) file (0xe103).
            C-APDU: 00a4000c02e103
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 15-byte Capability Container (CC) file.
            C-APDU: 00b000000f
            # The mdoc returns the Capability Container (CC) file (NDEF file ID=0xe104, read-write).
            R-APDU: 000f207fff7fff0406e1047fff00009000

            # The mdoc reader selects the NDEF file (0xe104).
            C-APDU: 00a4000c02e104
            # The mdoc acknowledges the file selection.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=31 bytes.
            R-APDU: 001f9000

            # The mdoc reader reads the 31-byte NDEF message.
            C-APDU: 00b000021f
            # The mdoc returns the initial NDEF message with the TNEP Service Parameter record.
            # NDEF Message (1 record(s), 31 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Tp" (Hex: 5470)
            #     Payload Size: 26 bytes
            #     Service Parameter (TNEP):
            #       TNEP Version: 16
            #       Service Name URI: "urn:nfc:sn:handover"
            #       Communication Mode: 0
            #       wtInt (Waiting Time Code): 0
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff9000

            # The mdoc reader writes the TNEP Service Select NDEF message (NLEN=25).
            # NDEF Message (1 record(s), 25 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Ts" (Hex: 5473)
            #     Payload Size: 20 bytes
            #     Service Select:
            #       Service Name: "urn:nfc:sn:handover"
            C-APDU: 00d600001b0019d1021454731375726e3a6e66633a736e3a68616e646f766572
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=6 bytes.
            R-APDU: 00069000

            # The mdoc reader reads the 6-byte NDEF message.
            C-APDU: 00b0000206
            # The mdoc returns the TNEP Status NDEF message.
            # NDEF Message (1 record(s), 6 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Te" (Hex: 5465)
            #     Payload Size: 1 bytes
            #     TNEP Status:
            #       Status Code: 0
            R-APDU: d102015465009000

            # The mdoc reader clears NLEN (0 bytes) before multi-chunk NDEF write.
            C-APDU: 00d60000020000
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader updates binary data at offset 2 (255 bytes).
            C-APDU: 00d60002ff91021b487215d102156163010130020a6d646f63726561646572056368626c651c1e580a69736f2e6f72673a31383031333a726561646572656e676167656d656e746d646f63726561646572a20063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa1a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a1010000005c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e73636862
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader updates binary data at offset 257 (10 bytes).
            C-APDU: 00d601010a6c65a300188001f502f5
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader finalizes NLEN=265 completing the NDEF Handover Request message.
            # NDEF Message (4 record(s), 265 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 27 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 21 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdocreader", "chble"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:readerengagement" (Hex: 69736f2e6f72673a31383031333a726561646572656e676167656d656e74)
            #     ID: "mdocreader" (Hex: 6d646f63726561646572)
            #     Payload Size: 88 bytes
            #     ReaderEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #             -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=null:peripheral_l2cap_mode:uuid=null:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:central_l2cap_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:psm=128
            #     Payload Hex: 02 1c 02 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 8 bytes
            #     ChBleOptions CBOR:
            #       {
            #         0: 128,
            #         1: true,
            #         2: true
            #       }
            C-APDU: 00d60000020109
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=240 bytes.
            R-APDU: 00f09000

            # The mdoc reader reads the 240-byte NDEF message.
            C-APDU: 00b00002f0
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (4 record(s), 240 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hs" (Hex: 4873)
            #     Payload Size: 21 bytes
            #     Handover Select:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 15 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: ["mdoc", "chble"]
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:deviceengagement" (Hex: 69736f2e6f72673a31383031333a646576696365656e676167656d656e74)
            #     ID: "mdoc" (Hex: 6d646f63)
            #     Payload Size: 96 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.1",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ],
            #         5: [],
            #         6: {
            #           3: true,
            #           4: true
            #         }
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=null
            #     Payload Hex: 02 1c 01
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 5 bytes
            #     ChBleOptions CBOR:
            #       {
            #         1: true,
            #         2: false
            #       }
            R-APDU: 910215487315d1020f616301013002046d646f63056368626c651c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f51a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c015c1a050569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a201f502f49000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'480962912f30d5228f1146f93e8ff66b5a7a5b7f35bde508538f9a17fbeb87c5843bc506f8c57376a41b841cf83e98394068ca7ff43f68e6a68fa5d67fe59d2a93962967e4fc9d51628d1cf3947cb03ee291d31d79ced188bec6e2074ee6a1c426d5cd509422470686288783416df0409da37de9191948691e7485cf31f7ac4b4417edd43085a6'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887480962912f30d5228f1146f93e8ff66b5a7a5b7f35bde508538f9a17fbeb87c5843bc506f8c57376a41b841cf83e98394068ca7ff43f68e6a68fa5d67fe59d2a93962967e4fc9d51628d1cf3947cb03ee291d31d79ced188bec6e2074ee6a1c426d5cd509422470686288783416df0409da37de9191948691e7485cf31f7ac4b4417edd43085a6

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'946d38eca281e696f5d6c956724ba1c26b05212a6b5c242bdbfce1a947bcdd81902f6deaef1422051b607eb824720108ad31bd41d7485395a4a88e8430a5b9dfe2af83679cedafde213a4718565cd9e52fe4d122db399e7e65ffc02ececfcde12b8e6199088d0117a6d529eecb7cdf70b2731da1a461f84887e6c4570f8237e065e604fdfecc0ea14c5453990faed9299e125baaac15c557939ba1c2223027dfc80a94db6403ba03b3e7cebf5484a504a78cc03f36b1010a2bba2f7befc7c28c5cdd3df6a00f3b3c9a585b0098a1cea52465b8e6e225a4b3cb4a242898d092edded599e4a2c8f565c263a96eda5dca63dd009a16c8ed36634d0c3c6cd3b41ddc907c663e1c56dbbb27c17eedf9ad0be6214b778f636cefba53a6976de0a44bc1660a9c219453c413ca289f0abb52f53fe1a95e23738753cc07774f4d5b20fe055e75380f62d0697fedb74fa98af8de2d4b2f4aec75667f1e8ca941417f6257b9fe9d0f2cf1845512c0e2fd2d269177c622f02b8462d3ba8ee88220328bc6671fcd78e0f854914e163c3b5abdb03a98eab834d348f8b7495eabd8c5227dc7d6ff0fe65e214698082fa514f910b5de212f37e9ef78ed811502f9a314cc1b34e6954da5c2272b6210e6ef8b67ecce4b1d6e328ae92d857edbb9a2a09df68b07ddeeeb2456f70b19d6ff04684e6d5ebc3ad19805d5fc5a580400ad0a08fdca250c59470937e3d7f9d08b1c69e8e3a4c709fd2085515a85d15686d811e4f8d1d7dc99d5bbccbe212ef43c7b3b29f6688b1801a45a2aba6283acee4329fd4f76b66e20cab742645ba1d9ef4ae07a0d75ef1fef38419a41d1ead614551895634c3450a10c7dbe142eebc36f0a922fa980cce3e492be9cf99e358dfceafd588e505dc20f4c13b98c22929f8b884330e28e81c28021ff77b3a068f540cccd01f1c1d2c92101269cbb206ad1067f434371f3ebdcb2815a99328d6ccfebc11dd050862a14221f5b94dfc3c728eb829d2d5ba6f247a5d11e83103d4fbbc39f57f733de89190f766ece45ed484ee952a601d80621403b2956a767e800ed25bc35131cfec9d0dddd17d454a6f5e79afabe369f30034d7e28af16f410670ef9301e3fa64458c483f8a0c3c2573150b073e86d6d5ccd7af23c3de876f86076eb60b5c2435884bc0d9db1ac7956456703dc5ac5768c8c0ee3a6d4a70028a4a142dd9a80a3e482e9ce8dbf950d24dc9825255d5bde1c5327bfa72e1ecc7333f87c1d3a6f0dc4a777ca5a8d01434e2317fcc5655790faa2a5b41f8a7de4c6b56bffc9b9068dc2867474a1b4a239f6c49fe95e2c4daebaa39d4d9f87d15b8c3f532918f0fabc5c1f03b6f1aa584933d38204860df0f16f327150a23a9a56783629ecd383e92e870f78d5056ed817a45bbd4499fa0128330c43e24a5c7b87ce22160285acf37c8cd2a8dce261c17d1194575593cecbfc29ac30df076e04ce3366aa7d3fa758c1bc25d583b353d19c9c6fccbd3a45d02e2acaf0d4d85ca66687748bf40b0136f497bd4e30edaf9a456e89468333bf1e0c037a74c41661bcdb79c8a3c18cede89fc8f0186f206f8a4fad94fe66d39b00c8b6edb37ead8cd3116d5fa187e5b0c8abe9c3404dbce4342dceed86c51467f44178c98f3b3d7b7e22d963d8ab663979da68d76d0f2252f0e85080dc51493fe8a838fc574db2095d8a43a14130a3f480353188b30cec394b830261a76bc9ed35b8ab1f8b0912c00182444b1bf9ad558b4d85acffe0df2d950f2c376af857cd6a68fad73eb4523d4c6bc5052ba90075850600f0525d07568ae38e7522a490bc1876fb3bdbb5f3d291bde6e1c5b9323dc0704d31485aa1959552561fe9d6741d6b95ed90e5f01504de2310a39f47bb21ba1e20fe52478b3efb7d2f428908b55ee3e2b28c5beee5206bce9ecac96fb440bf8e0ce4d725e819e67e793f5cd16b0f38c264ac099eecb401f10e6d740a9e8cbf2c16e50ca7ff2e4c7d81a62f70830167da031d3a424e3ed4b289b37f1ae195c5a76e819e8b563d89e9d754aaa3b7737b661eb4aa8e2b168889b54284482a59efc14941a0cc95d20ed63c10c280594749cd00355f4c4218271f10879e9aee068e44d742f2609965d1649acea4a3d931b5cbfb91aea8e021a5705003e850e19164ccde8599e75ae155d5ec5caa116c9257557b350ba6e4a961e93eb0dad39c91d785c6b8060aaceaa68db9469cdc287ff3ea00cf28a32e88ad7133af0e07bcf57e59560300562c573aa8fbbc70311a3788b839283974ae6565305c476569c30aa27b7cc21263ba286869255909a92d1034cf396930e5655cff607f3407571f785f2b5fd436bbf117545fff8bd2f4c074ec9cd8e0d1669e1bea2ab8352315d147bd4841f7a30f574917d0bc46a2cad6a487bedf5f873394aa01baef0116536d798f15d29f1aa0e7be983272d700c704c500ec53a9ee7262b08297f72c28b926ce0e956d0f6e38a85a0d6aac34cdca47f890ee26ccf24ab5b2041af26ca5b3f332c9a3fbb7445196aff2c65ae2016d780845234a3068355d818fcd0884624afd1331ab15b58c1953e0f57e3f9ab7ca8ad0bdaa6ec01a'
            # }
            BLE Mdoc->Reader: a1646461746159072f946d38eca281e696f5d6c956724ba1c26b05212a6b5c242bdbfce1a947bcdd81902f6deaef1422051b607eb824720108ad31bd41d7485395a4a88e8430a5b9dfe2af83679cedafde213a4718565cd9e52fe4d122db399e7e65ffc02ececfcde12b8e6199088d0117a6d529eecb7cdf70b2731da1a461f84887e6c4570f8237e065e604fdfecc0ea14c5453990faed9299e125baaac15c557939ba1c2223027dfc80a94db6403ba03b3e7cebf5484a504a78cc03f36b1010a2bba2f7befc7c28c5cdd3df6a00f3b3c9a585b0098a1cea52465b8e6e225a4b3cb4a242898d092edded599e4a2c8f565c263a96eda5dca63dd009a16c8ed36634d0c3c6cd3b41ddc907c663e1c56dbbb27c17eedf9ad0be6214b778f636cefba53a6976de0a44bc1660a9c219453c413ca289f0abb52f53fe1a95e23738753cc07774f4d5b20fe055e75380f62d0697fedb74fa98af8de2d4b2f4aec75667f1e8ca941417f6257b9fe9d0f2cf1845512c0e2fd2d269177c622f02b8462d3ba8ee88220328bc6671fcd78e0f854914e163c3b5abdb03a98eab834d348f8b7495eabd8c5227dc7d6ff0fe65e214698082fa514f910b5de212f37e9ef78ed811502f9a314cc1b34e6954da5c2272b6210e6ef8b67ecce4b1d6e328ae92d857edbb9a2a09df68b07ddeeeb2456f70b19d6ff04684e6d5ebc3ad19805d5fc5a580400ad0a08fdca250c59470937e3d7f9d08b1c69e8e3a4c709fd2085515a85d15686d811e4f8d1d7dc99d5bbccbe212ef43c7b3b29f6688b1801a45a2aba6283acee4329fd4f76b66e20cab742645ba1d9ef4ae07a0d75ef1fef38419a41d1ead614551895634c3450a10c7dbe142eebc36f0a922fa980cce3e492be9cf99e358dfceafd588e505dc20f4c13b98c22929f8b884330e28e81c28021ff77b3a068f540cccd01f1c1d2c92101269cbb206ad1067f434371f3ebdcb2815a99328d6ccfebc11dd050862a14221f5b94dfc3c728eb829d2d5ba6f247a5d11e83103d4fbbc39f57f733de89190f766ece45ed484ee952a601d80621403b2956a767e800ed25bc35131cfec9d0dddd17d454a6f5e79afabe369f30034d7e28af16f410670ef9301e3fa64458c483f8a0c3c2573150b073e86d6d5ccd7af23c3de876f86076eb60b5c2435884bc0d9db1ac7956456703dc5ac5768c8c0ee3a6d4a70028a4a142dd9a80a3e482e9ce8dbf950d24dc9825255d5bde1c5327bfa72e1ecc7333f87c1d3a6f0dc4a777ca5a8d01434e2317fcc5655790faa2a5b41f8a7de4c6b56bffc9b9068dc2867474a1b4a239f6c49fe95e2c4daebaa39d4d9f87d15b8c3f532918f0fabc5c1f03b6f1aa584933d38204860df0f16f327150a23a9a56783629ecd383e92e870f78d5056ed817a45bbd4499fa0128330c43e24a5c7b87ce22160285acf37c8cd2a8dce261c17d1194575593cecbfc29ac30df076e04ce3366aa7d3fa758c1bc25d583b353d19c9c6fccbd3a45d02e2acaf0d4d85ca66687748bf40b0136f497bd4e30edaf9a456e89468333bf1e0c037a74c41661bcdb79c8a3c18cede89fc8f0186f206f8a4fad94fe66d39b00c8b6edb37ead8cd3116d5fa187e5b0c8abe9c3404dbce4342dceed86c51467f44178c98f3b3d7b7e22d963d8ab663979da68d76d0f2252f0e85080dc51493fe8a838fc574db2095d8a43a14130a3f480353188b30cec394b830261a76bc9ed35b8ab1f8b0912c00182444b1bf9ad558b4d85acffe0df2d950f2c376af857cd6a68fad73eb4523d4c6bc5052ba90075850600f0525d07568ae38e7522a490bc1876fb3bdbb5f3d291bde6e1c5b9323dc0704d31485aa1959552561fe9d6741d6b95ed90e5f01504de2310a39f47bb21ba1e20fe52478b3efb7d2f428908b55ee3e2b28c5beee5206bce9ecac96fb440bf8e0ce4d725e819e67e793f5cd16b0f38c264ac099eecb401f10e6d740a9e8cbf2c16e50ca7ff2e4c7d81a62f70830167da031d3a424e3ed4b289b37f1ae195c5a76e819e8b563d89e9d754aaa3b7737b661eb4aa8e2b168889b54284482a59efc14941a0cc95d20ed63c10c280594749cd00355f4c4218271f10879e9aee068e44d742f2609965d1649acea4a3d931b5cbfb91aea8e021a5705003e850e19164ccde8599e75ae155d5ec5caa116c9257557b350ba6e4a961e93eb0dad39c91d785c6b8060aaceaa68db9469cdc287ff3ea00cf28a32e88ad7133af0e07bcf57e59560300562c573aa8fbbc70311a3788b839283974ae6565305c476569c30aa27b7cc21263ba286869255909a92d1034cf396930e5655cff607f3407571f785f2b5fd436bbf117545fff8bd2f4c074ec9cd8e0d1669e1bea2ab8352315d147bd4841f7a30f574917d0bc46a2cad6a487bedf5f873394aa01baef0116536d798f15d29f1aa0e7be983272d700c704c500ec53a9ee7262b08297f72c28b926ce0e956d0f6e38a85a0d6aac34cdca47f890ee26ccf24ab5b2041af26ca5b3f332c9a3fbb7445196aff2c65ae2016d780845234a3068355d818fcd0884624afd1331ab15b58c1953e0f57e3f9ab7ca8ad0bdaa6ec01a

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }
}
