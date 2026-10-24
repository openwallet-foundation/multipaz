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
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.engagement.DeviceEngagement
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
                val payloadFormatted = formatNdefRecordPayload(record, senderRole).replace("\n", "\n    ")
                sb.append("\n    ").append(payloadFormatted)
            }
            return sb.toString()
        }

        private fun formatNdefRecordPayload(record: NdefRecord, senderRole: MdocRole): String {
            HandoverRequestRecord.fromNdefRecord(record)?.let { hr ->
                val sb = StringBuilder("Handover Request:\n  Version: ${hr.version}\n  Embedded NDEF Message:")
                hr.embeddedMessage.records.forEachIndexed { i, r ->
                    sb.append("\n    Record #${i + 1}:")
                    sb.append("\n      TNF: ${r.tnf.name}")
                    val typeStr = try { r.type.decodeToString() } catch (_: Throwable) { "" }
                    sb.append("\n      Type: \"$typeStr\" (Hex: ${r.type.toByteArray().toHex()})")
                    sb.append("\n      Payload Size: ${r.payload.size} bytes")
                    val formatted = formatNdefRecordPayload(r, senderRole).replace("\n", "\n      ")
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
                    val formatted = formatNdefRecordPayload(r, senderRole).replace("\n", "\n      ")
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
                (typeStr == "iso.org:18013:deviceengagement" || typeStr == "iso.org:18013:readerengagement")
            ) {
                val label = if (typeStr.endsWith("deviceengagement")) {
                    "DeviceEngagement CBOR:"
                } else {
                    "ReaderEngagement CBOR:"
                }
                val diag = Cbor.toDiagnostics(
                    record.payload.toByteArray(),
                    setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
                ).replace("\n", "\n  ")
                return "$label\n  $diag"
            }

            if (record.tnf == NdefRecord.Tnf.MIME_MEDIA || record.tnf == NdefRecord.Tnf.EXTERNAL_TYPE) {
                val cm = MdocConnectionMethod.fromNdefRecord(record, senderRole, null)
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
            channelPairs.getOrPut(Pair(cm.supportsCentralClientMode, cm.supportsPeripheralServerMode)) {
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

            override suspend fun advertise() {
                if (_state.value == State.IDLE) {
                    _state.value = State.ADVERTISING
                }
            }

            override suspend fun open(eSenderKey: EcPublicKey) {
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
                if (role == MdocRole.MDOC_READER) {
                    pair.mdocToReaderChannel.close()
                } else {
                    pair.readerToMdocChannel.close()
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
        supportsPeripheralServerMode = false,
        supportsCentralClientMode = true,
        peripheralServerModeUuid = null,
        centralClientModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
    )

    private fun getStaticBlePeripheralServerMethod(): MdocConnectionMethodBle = MdocConnectionMethodBle(
        supportsPeripheralServerMode = true,
        supportsCentralClientMode = false,
        peripheralServerModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        centralClientModeUuid = null,
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
        negotiatedHandoverPicker: ((List<MdocConnectionMethod>) -> MdocConnectionMethod)? = null,
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
                    // Match what readerSelectConnectionMethod will pick
                    connectionMethods.first()
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

            val terminationMessage = SessionEncryption.encodeStatus(
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
                methods.filterIsInstance<MdocConnectionMethodBle>().first { it.supportsCentralClientMode }
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=123).
            # NDEF Message (3 record(s), 123 bytes):
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
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:readerengagement" (Hex: 69736f2e6f72673a31383031333a726561646572656e676167656d656e74)
            #     ID: "mdocreader" (Hex: 6d646f63726561646572)
            #     Payload Size: 6 bytes
            #     ReaderEngagement CBOR:
            #       {
            #         0: "1.0"
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:peripheral_server_mode:uuid=00000001-a123-48ce-896b-4c76973373e6:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 03 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            C-APDU: 00d600007d007b91020a487215d102046163010130001c1e060a69736f2e6f72673a31383031333a726561646572656e676167656d656e746d646f63726561646572a10063312e305a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c031107e6733397764c6b89ce4823a101000000
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=186 bytes.
            R-APDU: 00ba9000

            # The mdoc reader reads the 186-byte NDEF message.
            C-APDU: 00b00002ba
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 186 bytes):
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
            #     Payload Size: 88 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.0",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=null
            #     Payload Hex: 02 1c 01
            R-APDU: 91020f487315d10209616301013001046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc675a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c019000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'4dec43b91989606af61fd48f484509a767b709988a49fcfb90b5a916485e27eaff40f63c51c3cd344ddbcdbcb23cb8dcf2dc88ce1757047a0f51830f1f44d0fa6f107f6873e6247568bd6be5232d57f5179edd114e5704d062b5e2663629ccc60be75aae74878d6cbda3a78f216e3512eba461cfe2ad9ea9e97a7f38fdb07b291a92dc321018df'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158874dec43b91989606af61fd48f484509a767b709988a49fcfb90b5a916485e27eaff40f63c51c3cd344ddbcdbcb23cb8dcf2dc88ce1757047a0f51830f1f44d0fa6f107f6873e6247568bd6be5232d57f5179edd114e5704d062b5e2663629ccc60be75aae74878d6cbda3a78f216e3512eba461cfe2ad9ea9e97a7f38fdb07b291a92dc321018df

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'a08fd53775436462e744007bc8761bfeabfe1624d33df4a970989ff65a12a9281d72dfe0b0ad1dc6391f1d6c10988548821e86df512dea81bf282335552429477676eac31ce09495b3fdac8b4e8abce4c7a7dd0f791b4ada17ec955524169f7a5df8d5ca1d61534246e8635f68dc7f3d6725ac32b50e1e74dd9037819d357eb36ed0ed5a81f6b50314c9d4a589affdd815ac792af476bf4e25adb578d9e265a5a74185f93905b8491043fdefb31deb974aca2a1505d2ccca97d859b226e42ec062452bac438f63e400dfe4754665063e2f5b3e71922b35ed0e4632c440461b6959446b726836ae805fd30aa892b4dbef5fc3962735d651813e27f46f23921cb9c5208c373d49995312cfcd7298fb0f2afafb1abd7fce065a339d2a9cab7b0af77f5fad1bd26d0a76e53269be154a23501181f59a2bfaad700fb3a1893077e843fd4b5073772938b558d2f8c87ac39fbe294c64bb3398a9a3e83f55c93955a87a1340b34a54b13de45aabc07c3410f16aafc7135ebb89f413c1b23371d00cce4155d869f7d95cbc9843cc4a9aeafac4c52e6344813521b4abb26cb20aff53fedcbd224aa000c17dbfb0604e86c878c8b49d8b2b9de345e38669a4354303065fd724078e55406155e1fbe57528bedac356ce88d11f7b2f12eff89f05db017251c86d19fa622235f2faf4c21c6f68cac62dc71a1fcfb95a7f7754a06be42e21ce4b751de718ce32818d14b9cddd8da150c61aad864f534304e0bbfc1b1e952f1d973e74f4111358c9f950417d30a68195c584720a4e3bd007ea34d610e7a2caeb32906ca5d6f90c80e8a1c71f04a7e9a87ae9875f978e06cd16959d76c7733cc1ad948e974eb12e1e8517826bda317c611338c866ea97bfcd2a586d1cd99ec557513df317aeafaa5d2f110e4bbf3665215c34965a01a140c4e59fc5e3482a70e22f0ea5bfa9401e38ca438905a35918d5a7b1f5e13484c4c1bad5eaedffc783a333c67cf8122e5dff9fbe689330bb69d248953c285f02ee827b074308641eeeeb792db2511462acbebb7470e242ad9e76dba54a52a3c90236d9103be834ecb17843e4596230ec072a421fd5c9747817b9bf65be4eff5b0261cee700764a6d2485015fc2b3225cf2682ee316b81d1c8a4983c788eecf2b2ef8532aa13d78bd5b8d7f778c06ba42b7344584e6674581e05a156e35bc517dc5203399a61165d9e70714a5a0e9c22aca78527599262c926371cc619f5a5e43acecdf523c98fa71c733cfe0ec3642525651ae87da3d29bff6013c4e63ecd0ca4cb2c676c1683399cd3767656ecce4894c2bbd76d4742c3ae789536b5ff73d71ce45d6a27d6aa2ff4f74cc624b07e3c5e2d722ff513b94337cdc8f16ca249329afc55ac86d620a102876edbc43cedcb682beabe3a094f47757995707daf43e81afeb7f21f0cb2fa5dcb687837eb01a80fec813ec29d5774d168f22283c069095d82f118ef15cad6c358186526aca448d8d5169d48873cf55d97d2de9e42ad3be88d208e8e48d701505463192a15c68ccc2a067ed6a0e965200547f52918ca1952256f4bed2535d71c29b18eda800bfffe72374027aa068905c9306806e34aa7c9e1e0a750d5d5401b85e0bcc0e6ce141304c7a52a92db512569fa1d6bdab8a6db48cee1445d668fdbe9e7906dfe9e16eac946c50ae1a714f8a2edd49e7aac41a23b3f21ffdb7c659d398cb7f9c64806202a50b042f1841fe1f61512d61c98b456ed5c480500df10bcd6db7e5dcee14c885ffe6875070822502a0be404f3aab6098d52fd70ecdbb5aaaa23261dd0ea2c51650b9b2b4451a5e8fe829894efb9f4f3a7f2ae8ab19351bbf6dd9902e8e81d8c2ea60d6ee2d4df03a8d2f69aff30d3df22ae28c65428b9d15e05c90967da6a7e5190342b36722f3cc812f970abe01da76b8824966c88d5d4c8c8c3b693f97d70b2e3f3d5b448a5e1c6ad8be2b53131de98a69dfbe1be882e2e1aece3fc0b7a5f4f429f6b3f0c24cfcee3a2dadcd746516314f17004a74e06fa79b6405d3d3d90673e5a8bd89bcf9815e45582385ea4d5e8d22b0c62705089056303606c1df2fc6293f6f7e3a9ecbdc747c678b8a690841937b973838cf3b3fbe7e7c4074a453909ade04ad8b144867581c7e7beeea874f1dae2ee8b8f7b982e468106561fa070392dcad05891703dd8572ea2b7e4791a01c3d8a38c9c96efa5e909ae14d563547f935b4c22f75e012e8d29ec2fed08fe61f299ffb754d66c49bb0608a2907d6d4da0592cb56fd1b61cbfd284962e15dfd3fcb05d1fca18932a575c2ff58e7de8f8e0ce58c30c4f71fbe5febb190c7bfc3cbbe55b462e3bad7adda94541bf92f0618fcb234841276b20ad4bc879ce5c5083af28f976aae75a47665c7d003d54d4774a9e2a64716964ee02d34328bb1d42dfb1d5b59518d5fc87b69c53279ec636b6feff18c2a0e2b87fe3d9d004b4d0a92428b4b7203ecc3ff732871f085b1fe3e3f3a4ad2c403e7b2940004c2312e80cb8ada249083969b1d296882c817c952f9063630da5b8468a2d3a95d0af0b83fec33ba439d39d01381b507a64eb8100348b3ed83f98817929922'
            # }
            BLE Mdoc->Reader: a1646461746159072fa08fd53775436462e744007bc8761bfeabfe1624d33df4a970989ff65a12a9281d72dfe0b0ad1dc6391f1d6c10988548821e86df512dea81bf282335552429477676eac31ce09495b3fdac8b4e8abce4c7a7dd0f791b4ada17ec955524169f7a5df8d5ca1d61534246e8635f68dc7f3d6725ac32b50e1e74dd9037819d357eb36ed0ed5a81f6b50314c9d4a589affdd815ac792af476bf4e25adb578d9e265a5a74185f93905b8491043fdefb31deb974aca2a1505d2ccca97d859b226e42ec062452bac438f63e400dfe4754665063e2f5b3e71922b35ed0e4632c440461b6959446b726836ae805fd30aa892b4dbef5fc3962735d651813e27f46f23921cb9c5208c373d49995312cfcd7298fb0f2afafb1abd7fce065a339d2a9cab7b0af77f5fad1bd26d0a76e53269be154a23501181f59a2bfaad700fb3a1893077e843fd4b5073772938b558d2f8c87ac39fbe294c64bb3398a9a3e83f55c93955a87a1340b34a54b13de45aabc07c3410f16aafc7135ebb89f413c1b23371d00cce4155d869f7d95cbc9843cc4a9aeafac4c52e6344813521b4abb26cb20aff53fedcbd224aa000c17dbfb0604e86c878c8b49d8b2b9de345e38669a4354303065fd724078e55406155e1fbe57528bedac356ce88d11f7b2f12eff89f05db017251c86d19fa622235f2faf4c21c6f68cac62dc71a1fcfb95a7f7754a06be42e21ce4b751de718ce32818d14b9cddd8da150c61aad864f534304e0bbfc1b1e952f1d973e74f4111358c9f950417d30a68195c584720a4e3bd007ea34d610e7a2caeb32906ca5d6f90c80e8a1c71f04a7e9a87ae9875f978e06cd16959d76c7733cc1ad948e974eb12e1e8517826bda317c611338c866ea97bfcd2a586d1cd99ec557513df317aeafaa5d2f110e4bbf3665215c34965a01a140c4e59fc5e3482a70e22f0ea5bfa9401e38ca438905a35918d5a7b1f5e13484c4c1bad5eaedffc783a333c67cf8122e5dff9fbe689330bb69d248953c285f02ee827b074308641eeeeb792db2511462acbebb7470e242ad9e76dba54a52a3c90236d9103be834ecb17843e4596230ec072a421fd5c9747817b9bf65be4eff5b0261cee700764a6d2485015fc2b3225cf2682ee316b81d1c8a4983c788eecf2b2ef8532aa13d78bd5b8d7f778c06ba42b7344584e6674581e05a156e35bc517dc5203399a61165d9e70714a5a0e9c22aca78527599262c926371cc619f5a5e43acecdf523c98fa71c733cfe0ec3642525651ae87da3d29bff6013c4e63ecd0ca4cb2c676c1683399cd3767656ecce4894c2bbd76d4742c3ae789536b5ff73d71ce45d6a27d6aa2ff4f74cc624b07e3c5e2d722ff513b94337cdc8f16ca249329afc55ac86d620a102876edbc43cedcb682beabe3a094f47757995707daf43e81afeb7f21f0cb2fa5dcb687837eb01a80fec813ec29d5774d168f22283c069095d82f118ef15cad6c358186526aca448d8d5169d48873cf55d97d2de9e42ad3be88d208e8e48d701505463192a15c68ccc2a067ed6a0e965200547f52918ca1952256f4bed2535d71c29b18eda800bfffe72374027aa068905c9306806e34aa7c9e1e0a750d5d5401b85e0bcc0e6ce141304c7a52a92db512569fa1d6bdab8a6db48cee1445d668fdbe9e7906dfe9e16eac946c50ae1a714f8a2edd49e7aac41a23b3f21ffdb7c659d398cb7f9c64806202a50b042f1841fe1f61512d61c98b456ed5c480500df10bcd6db7e5dcee14c885ffe6875070822502a0be404f3aab6098d52fd70ecdbb5aaaa23261dd0ea2c51650b9b2b4451a5e8fe829894efb9f4f3a7f2ae8ab19351bbf6dd9902e8e81d8c2ea60d6ee2d4df03a8d2f69aff30d3df22ae28c65428b9d15e05c90967da6a7e5190342b36722f3cc812f970abe01da76b8824966c88d5d4c8c8c3b693f97d70b2e3f3d5b448a5e1c6ad8be2b53131de98a69dfbe1be882e2e1aece3fc0b7a5f4f429f6b3f0c24cfcee3a2dadcd746516314f17004a74e06fa79b6405d3d3d90673e5a8bd89bcf9815e45582385ea4d5e8d22b0c62705089056303606c1df2fc6293f6f7e3a9ecbdc747c678b8a690841937b973838cf3b3fbe7e7c4074a453909ade04ad8b144867581c7e7beeea874f1dae2ee8b8f7b982e468106561fa070392dcad05891703dd8572ea2b7e4791a01c3d8a38c9c96efa5e909ae14d563547f935b4c22f75e012e8d29ec2fed08fe61f299ffb754d66c49bb0608a2907d6d4da0592cb56fd1b61cbfd284962e15dfd3fcb05d1fca18932a575c2ff58e7de8f8e0ce58c30c4f71fbe5febb190c7bfc3cbbe55b462e3bad7adda94541bf92f0618fcb234841276b20ad4bc879ce5c5083af28f976aae75a47665c7d003d54d4774a9e2a64716964ee02d34328bb1d42dfb1d5b59518d5fc87b69c53279ec636b6feff18c2a0e2b87fe3d9d004b4d0a92428b4b7203ecc3ff732871f085b1fe3e3f3a4ad2c403e7b2940004c2312e80cb8ada249083969b1d296882c817c952f9063630da5b8468a2d3a95d0af0b83fec33ba439d39d01381b507a64eb8100348b3ed83f98817929922

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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=168).
            # NDEF Message (4 record(s), 168 bytes):
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
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:readerengagement" (Hex: 69736f2e6f72673a31383031333a726561646572656e676167656d656e74)
            #     ID: "mdocreader" (Hex: 6d646f63726561646572)
            #     Payload Size: 6 bytes
            #     ReaderEngagement CBOR:
            #       {
            #         0: "1.0"
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            C-APDU: 00d60000aa00a8910215487215910204616301013000510206616301036e6663001c1e060a69736f2e6f72673a31383031333a726561646572656e676167656d656e746d646f63726561646572a10063312e301a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a1010000005c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff0402010000
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=182 bytes.
            R-APDU: 00b69000

            # The mdoc reader reads the 182-byte NDEF message.
            C-APDU: 00b00002b6
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 182 bytes):
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
            #     Payload Size: 88 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.0",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            R-APDU: 910211487315d1020b616301036e666301046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc675c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000

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
            #   "data": h'2b201a42d159806b3df6add1be06af23276d7fc607f72a6450b7fde360d7fa05ee49aeb530b4d6005e9c69178467c429f031500730e743e3a2f02d47a6221a478f4b48e6cd94161142f6604708269fd6c18c73f101a622d5c416fd2edaa2d8d88579440ccb000af7b75d2c6eb5c4341ddf202195f5f04c85b783d324d8888f47759b0fa60f0289'
            # }
            C-APDU: 00c300000000ec5381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158872b201a42d159806b3df6add1be06af23276d7fc607f72a6450b7fde360d7fa05ee49aeb530b4d6005e9c69178467c429f031500730e743e3a2f02d47a6221a478f4b48e6cd94161142f6604708269fd6c18c73f101a622d5c416fd2edaa2d8d88579440ccb000af7b75d2c6eb5c4341ddf202195f5f04c85b783d324d8888f47759b0fa60f0289fff9
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'252e41fbe22e4a5fb0388e372f40ed44b1dc08e88446cc0379120beb5964c47beaac0e4d2a9ee30209890d99c1240676848e37de454a8e8fe97ae0cba1c3cab11a3572c46c479bd055307f65676d87596d3f07595e45c457f4cdb30423996013d0a4d916017fc6498b3c4309e8d43de1bbf2ff1bdd8fcf50368de22cdc0d1aa71a1532638d63f43d2ba91ecc2ded969dde81e1c2f831d0693ae782b2eec2cee27be5e503da7a970aa8fbcea67efd179a276e7e605cb2f21bdce72cffd6e31f0d09607ab15fe90ff1c3a76ae7067ed51db320a3695d9ceb13c98f11e63a2f9aef3e0c8e1dac4b9e1740cdde76064fc665b6cd72e6d9571c3c732c60a0c3a6908ce8295d83c2f91d06ecaf6facec785b01475965e559d26c784f6004526f965c9997fb945e6a9faf661a9a21c67ebbb6afdf6bc87ecf07f6f3df41a8e8104e9ae880f6cddb59614696bb76a020c643374bc63f3f9217aa27632e0f8cc9f6afed49254071db182bd5205a1fb13f441461a20df24a1870cd6da9f0c16acc431bc9c152430f1492da7524a6487832ee2d62d4a7f6827ce40972ad490a22cbe3ec44d2e6188ba0936875e1c532f5dbe472e5ff9b4699e751658af503292c7b12cf096a4f625fafe1ffb9803b3cf48a7218201c2acd7ca223407baf47f33cb308b4ee94cf02ffc45bd315f769d00e87f19f82f91952d9a0adad8e7e265faa5e0f17a0d5c94be20bb4cf5af97fd7ddb5d8bd6b9d64c3c4131f8995326dbb2478c904d47de814273802ea46b819c96323e93f0782043b9376e6568328a4080dffe46cd70f2d63ec4ba5bbc9bfcdd8bac177e9001c2b68cfdf605a99ea38a3032885895dc7a97dc3bac79b02a947fcae66a0828d646e89287cb094bc1b8c6535f794671dc6bc3332e7692678d0ca2893b7149abaae588b2475d020bcc61d01d749893a611f3fb1105b9f0b61985663383dd6632cec0f9a0a3b6c5d3f4d08dd2838e973f59c3174d96314243af0d332c1b16bae033a41cb836e4544c0beda27c205394482d665ddb10e09d74e933b2339106b62e34f919592a5d08687631d4a8f1fd571f98dc2e4df9a52a299f7154c03b386da2e8eaedcab4cf911535212652e62a5282685e4a6f138540b7978232e281f2834b9e4f7231a6a4836c3c6596d091894c79103ee51f64cf435150d7326114088216f26c0af62615c390f6b46c0ba268b1ce40e5261e7d275301959ff4a4c42a48f07709283338b35d12277b729e3b7d148ffdde1acab50d92561a8f3593afdf385bd21868115dd02a7db8ce14387da40a0bbff6301b4b208c69bcf4e42fdf49d19a9be973f393be9bb563900e39db245b2ea69ba6169fa1c2b1fae4386145ffbb3055cd013cbbf039a7d9efe0ff044796443dab7bc7178f1ce8d1d4ea8eb873c13d302364f6bfb0b2671c2c367bda9b0e099512baf519129a849770536eaf790f7f4db7e67b541e867a56daf018c3783fc67081d9baddfbd87cc6eda93159a7de8700d55c36524af95f181acdde13454d485d9e852737958d8e08c76a3aa4f03eb47354024fcb9258add2ce73126acd5ed92d2171099862ddce12cf718e9243ecaf2285563a66f2fa1d436efc418fcb114c7dfc2807353af25c01bd2cd438035401577215c69b055bec28bc37a268977dd6b637e1ba8fed20fffc29091b54b9a5d410df70833cfcf1017f1d4c50d3b6c02f01acca67964d9b8ecd87732b3eb2f011e38dcdf6634b7944b322829469fe5d9a29afdf58eb8562d86d87cefb6b592f32f509ac8159a0012f8e37abd49da375d9bc2d4a9f33e471781f518d7d3f7c35510e53225613560c2339b64d6b5c37e7f072243df6d4a79a63fa5d7eab3e1e763b3920fa77ad9c12ec81927086f4757f3e23132958734083958c550d20ca1ea68fb37fd6acbabc60eb54bf22ea2d08841c62999c0bf32621c07df02b119a90896451d27b9e50ff6aa3167004c5417e2131eb517f453bdaeb4e4c250a35be70e0bd4e0fd8e6dfb485b2e61ab131b2d3170cafe6a455aa88e619b27d732c34bcbc8b5c01ad3d95aa936440e1fb841865dfb75a9e13f7d3795124b8bf56629d1500883bf44c52b6ff4372f2dcb943664e0ef5c5f35cd3157abd45c492b43e1cfee6d4994e2ba239f976c26211a1b1b44fbc54cbdd8142c3c5baddb95e3c71448f6562485cce9038d9b6a9d80452865c36d3291d51d8f179e718626354f9532b996fa51b72c65afd3fd3d6ef87e4f33c6786d11af73a36c04acc3a35466557bfc3f9143b918f3f0bb808d674807c1717e3516a1f8204a0c5f2680985746caebac6dd6997de4aa4f4381f68e4789d92cc3522d065bb8bdd94ce129d86a73e9896f69e3abd56439eb1d30f63fc1c6769d7679125f625d424c91f30c5f0f9115cdf6be50ef7ce4266286f558c57186f684e7f23be7e41eb13e83277591b2beecb4277f04b9362865ca875096fe35d3ab494b9d0ed2a21016bd4f86d9f962119b485bd0c3d92efb908b6c39191c3c30ff33fa497c2e529261f55979f9625b4316f2c24e9b04315aba1660694619255498377f6a6742030d8b1e54dfb9728c4e9d12188e8697'
            # }
            R-APDU: 53820738a1646461746159072f252e41fbe22e4a5fb0388e372f40ed44b1dc08e88446cc0379120beb5964c47beaac0e4d2a9ee30209890d99c1240676848e37de454a8e8fe97ae0cba1c3cab11a3572c46c479bd055307f65676d87596d3f07595e45c457f4cdb30423996013d0a4d916017fc6498b3c4309e8d43de1bbf2ff1bdd8fcf50368de22cdc0d1aa71a1532638d63f43d2ba91ecc2ded969dde81e1c2f831d0693ae782b2eec2cee27be5e503da7a970aa8fbcea67efd179a276e7e605cb2f21bdce72cffd6e31f0d09607ab15fe90ff1c3a76ae7067ed51db320a3695d9ceb13c98f11e63a2f9aef3e0c8e1dac4b9e1740cdde76064fc665b6cd72e6d9571c3c732c60a0c3a6908ce8295d83c2f91d06ecaf6facec785b01475965e559d26c784f6004526f965c9997fb945e6a9faf661a9a21c67ebbb6afdf6bc87ecf07f6f3df41a8e8104e9ae880f6cddb59614696bb76a020c643374bc63f3f9217aa27632e0f8cc9f6afed49254071db182bd5205a1fb13f441461a20df24a1870cd6da9f0c16acc431bc9c152430f1492da7524a6487832ee2d62d4a7f6827ce40972ad490a22cbe3ec44d2e6188ba0936875e1c532f5dbe472e5ff9b4699e751658af503292c7b12cf096a4f625fafe1ffb9803b3cf48a7218201c2acd7ca223407baf47f33cb308b4ee94cf02ffc45bd315f769d00e87f19f82f91952d9a0adad8e7e265faa5e0f17a0d5c94be20bb4cf5af97fd7ddb5d8bd6b9d64c3c4131f8995326dbb2478c904d47de814273802ea46b819c96323e93f0782043b9376e6568328a4080dffe46cd70f2d63ec4ba5bbc9bfcdd8bac177e9001c2b68cfdf605a99ea38a3032885895dc7a97dc3bac79b02a947fcae66a0828d646e89287cb094bc1b8c6535f794671dc6bc3332e7692678d0ca2893b7149abaae588b2475d020bcc61d01d749893a611f3fb1105b9f0b61985663383dd6632cec0f9a0a3b6c5d3f4d08dd2838e973f59c3174d96314243af0d332c1b16bae033a41cb836e4544c0beda27c205394482d665ddb10e09d74e933b2339106b62e34f919592a5d08687631d4a8f1fd571f98dc2e4df9a52a299f7154c03b386da2e8eaedcab4cf911535212652e62a5282685e4a6f138540b7978232e281f2834b9e4f7231a6a4836c3c6596d091894c79103ee51f64cf435150d7326114088216f26c0af62615c390f6b46c0ba268b1ce40e5261e7d275301959ff4a4c42a48f07709283338b35d12277b729e3b7d148ffdde1acab50d92561a8f3593afdf385bd21868115dd02a7db8ce14387da40a0bbff6301b4b208c69bcf4e42fdf49d19a9be973f393be9bb563900e39db245b2ea69ba6169fa1c2b1fae4386145ffbb3055cd013cbbf039a7d9efe0ff044796443dab7bc7178f1ce8d1d4ea8eb873c13d302364f6bfb0b2671c2c367bda9b0e099512baf519129a849770536eaf790f7f4db7e67b541e867a56daf018c3783fc67081d9baddfbd87cc6eda93159a7de8700d55c36524af95f181acdde13454d485d9e852737958d8e08c76a3aa4f03eb47354024fcb9258add2ce73126acd5ed92d2171099862ddce12cf718e9243ecaf2285563a66f2fa1d436efc418fcb114c7dfc2807353af25c01bd2cd438035401577215c69b055bec28bc37a268977dd6b637e1ba8fed20fffc29091b54b9a5d410df70833cfcf1017f1d4c50d3b6c02f01acca67964d9b8ecd87732b3eb2f011e38dcdf6634b7944b322829469fe5d9a29afdf58eb8562d86d87cefb6b592f32f509ac8159a0012f8e37abd49da375d9bc2d4a9f33e471781f518d7d3f7c35510e53225613560c2339b64d6b5c37e7f072243df6d4a79a63fa5d7eab3e1e763b3920fa77ad9c12ec81927086f4757f3e23132958734083958c550d20ca1ea68fb37fd6acbabc60eb54bf22ea2d08841c62999c0bf32621c07df02b119a90896451d27b9e50ff6aa3167004c5417e2131eb517f453bdaeb4e4c250a35be70e0bd4e0fd8e6dfb485b2e61ab131b2d3170cafe6a455aa88e619b27d732c34bcbc8b5c01ad3d95aa936440e1fb841865dfb75a9e13f7d3795124b8bf56629d1500883bf44c52b6ff4372f2dcb943664e0ef5c5f35cd3157abd45c492b43e1cfee6d4994e2ba239f976c26211a1b1b44fbc54cbdd8142c3c5baddb95e3c71448f6562485cce9038d9b6a9d80452865c36d3291d51d8f179e718626354f9532b996fa51b72c65afd3fd3d6ef87e4f33c6786d11af73a36c04acc3a35466557bfc3f9143b918f3f0bb808d674807c1717e3516a1f8204a0c5f2680985746caebac6dd6997de4aa4f4381f68e4789d92cc3522d065bb8bdd94ce129d86a73e9896f69e3abd56439eb1d30f63fc1c6769d7679125f625d424c91f30c5f0f9115cdf6be50ef7ce4266286f558c57186f684e7f23be7e41eb13e83277591b2beecb4277f04b9362865ca875096fe35d3ab494b9d0ed2a21016bd4f86d9f962119b485bd0c3d92efb908b6c39191c3c30ff33fa497c2e529261f55979f9625b4316f2c24e9b04315aba1660694619255498377f6a6742030d8b1e54dfb9728c4e9d12188e86979000

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
                SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=123).
            # NDEF Message (3 record(s), 123 bytes):
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
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:readerengagement" (Hex: 69736f2e6f72673a31383031333a726561646572656e676167656d656e74)
            #     ID: "mdocreader" (Hex: 6d646f63726561646572)
            #     Payload Size: 6 bytes
            #     ReaderEngagement CBOR:
            #       {
            #         0: "1.0"
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            C-APDU: 00d600007d007b91020a487215d102046163010130001c1e060a69736f2e6f72673a31383031333a726561646572656e676167656d656e746d646f63726561646572a10063312e305a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a101000000
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader reads the 2-byte NDEF message length (NLEN).
            C-APDU: 00b0000002
            # The mdoc returns NLEN=186 bytes.
            R-APDU: 00ba9000

            # The mdoc reader reads the 186-byte NDEF message.
            C-APDU: 00b00002ba
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 186 bytes):
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
            #     Payload Size: 88 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.0",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #             -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:central_client_mode:uuid=null
            #     Payload Hex: 02 1c 01
            R-APDU: 91020f487315d10209616301013001046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc675a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c019000

            # The mdoc reader sends SessionEstablishment with eReaderKey and encrypted DeviceRequest over BLE.
            # {
            #   "eReaderKey": 24(<< {
            #     1: 2,
            #     -1: 1,
            #     -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #     -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #   } >>),
            #   "data": h'96f9e40fcbe7edd26ddeddb003acca94873c7672ded4dfcf1a3599c0331a9010c529925abae243d355bf5025543edf695750e97a477f866f0895cd83e2d3fc345841ca450429ef7c5905f73233f12ad7f218b0050d703220166768ff8a207d8bb1eced965dfc96c6d83329681483d58c781c6bd85a83a863ca0ab9b38fdb4439ee2ded99ba611b'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa6464617461588796f9e40fcbe7edd26ddeddb003acca94873c7672ded4dfcf1a3599c0331a9010c529925abae243d355bf5025543edf695750e97a477f866f0895cd83e2d3fc345841ca450429ef7c5905f73233f12ad7f218b0050d703220166768ff8a207d8bb1eced965dfc96c6d83329681483d58c781c6bd85a83a863ca0ab9b38fdb4439ee2ded99ba611b

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'36a759362e5eeaf612d37002e1d801ef3503c24d177473baf2267fa46153ae605a3246989631a09eab6e51c3f3f5fcdfb993ecf7040f717b87a76f5c8c1cd0c57de6f014d2a1b77bda4c2b8249e60263b96af647336f7b3e1fbc22366bb730358a71409d74c747502e4d97175ae419eaf8a21d2d90ccf774643b87f2a21a1c3ba63eb0d6c6fe4d9ad02c76c8dccfe876ff58b70063c4ba9d99fe0504e1b1abd4991e6a59b939e2c5fce2a6c6060c99b15f98b7a7230e37f840ac612964ce6f946b93dc0ad9d1fbe4ed1e5b4d9bd43b597c47b7b5550eed051a7e8bb16af29cefe5ec8d4ca799ac4a5759b0eb6bd8b25b6608b4230d323b25011f20747c9bcacd02017791e2726d6d58bbdda29c4499ae8aa4f1729bd219d170e22f03a4c5449cd7c5a808cc9e534f798acf27b285195926e366c40ae98bcaa0021d9e59baf95417e5a91a74d2aeab6f3a5ed4e524fe4c253fe65dbc70354a94e21eba06736500775d9a6d7926c286d0bbf84cf5cbb64db5eaaafa1f209b254824d66da062662eb1325a3327f6df1bf52053f804207158f411d951bbe90b3858fccd2e1e706269c80a7f3e4a3b17948903bb4f8c9649696235d94368165f17bcbf7c80ef3bcd11c1096683ff62e96471f1678e7d899d57850a5a6bb9e884a7abe3bc5a09aac0b1d44c52e0b0386c6914474083087b571b2467117b179ac9de1680e5996feb1aec37a38bb67e6e8605d79df8126fd2260b9dd13dc63cb7c1eb6487b12b46c464ce9a1e3fd2d4390d7718d58a93911f8fbd12def82209a18c3f9c6b56c57b88b53ff8de60e0c4922cb1423053a0631551b356fa7f404488fa632a5a0e45d4445f3abde162477f9effba3629f4a56490ac76bdf02da5e5b550717ec0a62044cd9c66c70d1a242880147e7db4ee6ea3083452d6130854660ba465f56f754e765d09491df82fe43295f7bbdaebab91e5a5c48fe4ade7a59bc59c22a3ed85718411a6dc71c8533fde1c8c0e6a9684520140f96052382eb2c43d45911b47fc8d238869acac2152f9f0c5fa539d3836c41309c90856577373b3552355b36ed2421e6cf2914f0ad9e6ee939f4d79eff242dadffe8965497f1ad0013115e086f6a133c85b49b80ad5fc440bb01d10208bb1ae326d59c70f43d2ac1b68845677462ab7e44701c09cec95490e5a0c7529ea224d21a31612eede0cf618c2769362e3a7bb08a72a3c0eefcd0b7c42846a53d2e67662c12b578a00fca2ca05ebf31a6bda526c0d0ceb358e0eec6a2253a66aa52f37a778ebffdda55f03e2a670ad19484dbccec8518f940f45e2d7411efc9ccb930aaf27f8daa6f371ef202d29bc2fb7b1fa70815aa3771a5af32d13c37190a360062ad07f1ae08c491264025fe87f2c7a08552d29262d2a5e47b635658c7a63439ff93d08c19798477608be502afba6324c08e6bf0361e72e62ae88e2a0082ac623ce81af3d0563344a66c2c29d84a7e7ea8f99e28f39f57a171c4c8a2bfdddbf2e0631e89d59ab298d1064b909e9b5704cc181966c5983f8de06c69bdcf92d4fd22e3bebb09ff1d52d8366a453af7c91dd0103a385b495d83d5456fbd82c26916a1e209e8a09fdb20d961dbf96b05eea09e92a33c409e35d589a0772fae51f6d59e7f6001f2e390fadf7a9039bf149415ee8d84439578dba72ce221253452e11426c16e911ca58d2412e9850453764a65bce47615f7991f4b2de5b02ca75b2d2ae18354b7aea8a9ffcbe94fef47a6adf7f97a020828e230bafdf0eae9ae70617cd2f680483de3fd75ccb7084456b55fa679e9a98a884013abba6e40cd18aff226443748b79126a355c4d5bcd846faf8089853d5d4e3aa9e5a3d1aac9121a837998b4d3574d13b52f671f8b423276c17df544f11f7b87bd55d796a34a3fb31073450277fd0a27b4cd69109c36eee5155f9515657be017e6034e78e642e997b3b0f3a2b1f8632c4f43fdf6b02d66383d19bafff6c7ecc1776bc6f3e41b5fc4cd8b95316666f44fcebc3334084a8f2bc8008adda3f9c1ed69f4c6b48bafaf72c2a1418d4167225a9aad32c8cf723f499e1793dca13c255ecdfe1c16c0d35ff08ab76bdcac2f89b782629b3aec2acd415803d0d8cf54bceb085dc0f5318c9aa6e2d1f2fbe5529b7a77233590902f4acea97c93a1fd3ea9152d4f01b1b5c58768d6e0127b016a15554bea8e46047cae22c18bbff5a42a47b559793aca1708129b7b49580bde6fb976765f48763257b67e5a136060d1dc203abb3b7f98032c2281093b44799663e43ab114037f52665bffe5d4c0d4922f19628d9a824ee45916f5247bc2a47fbff790a20c28b5a7a9cc4ebc42f61302e65dc977becab6474abed1632bd1c1e784361abed5a3add0264834647aae18605c76a79b6926dc13c56bc5710c1251d2532ececcd3202d46090ec49bf22334de53ca257b4953f712133393d024afb9879d3c2eaf8a776b4deb73ff835c90fefdeb50667f4b827328637dfd33696b9691123f6d4d0e0f0fd7dee5028674fe13d52be45b545f8d9fcc143f266027c0477b890334294284decfa360248a7357c06e5af048d0c681a72244354de1389426b4'
            # }
            BLE Mdoc->Reader: a1646461746159072f36a759362e5eeaf612d37002e1d801ef3503c24d177473baf2267fa46153ae605a3246989631a09eab6e51c3f3f5fcdfb993ecf7040f717b87a76f5c8c1cd0c57de6f014d2a1b77bda4c2b8249e60263b96af647336f7b3e1fbc22366bb730358a71409d74c747502e4d97175ae419eaf8a21d2d90ccf774643b87f2a21a1c3ba63eb0d6c6fe4d9ad02c76c8dccfe876ff58b70063c4ba9d99fe0504e1b1abd4991e6a59b939e2c5fce2a6c6060c99b15f98b7a7230e37f840ac612964ce6f946b93dc0ad9d1fbe4ed1e5b4d9bd43b597c47b7b5550eed051a7e8bb16af29cefe5ec8d4ca799ac4a5759b0eb6bd8b25b6608b4230d323b25011f20747c9bcacd02017791e2726d6d58bbdda29c4499ae8aa4f1729bd219d170e22f03a4c5449cd7c5a808cc9e534f798acf27b285195926e366c40ae98bcaa0021d9e59baf95417e5a91a74d2aeab6f3a5ed4e524fe4c253fe65dbc70354a94e21eba06736500775d9a6d7926c286d0bbf84cf5cbb64db5eaaafa1f209b254824d66da062662eb1325a3327f6df1bf52053f804207158f411d951bbe90b3858fccd2e1e706269c80a7f3e4a3b17948903bb4f8c9649696235d94368165f17bcbf7c80ef3bcd11c1096683ff62e96471f1678e7d899d57850a5a6bb9e884a7abe3bc5a09aac0b1d44c52e0b0386c6914474083087b571b2467117b179ac9de1680e5996feb1aec37a38bb67e6e8605d79df8126fd2260b9dd13dc63cb7c1eb6487b12b46c464ce9a1e3fd2d4390d7718d58a93911f8fbd12def82209a18c3f9c6b56c57b88b53ff8de60e0c4922cb1423053a0631551b356fa7f404488fa632a5a0e45d4445f3abde162477f9effba3629f4a56490ac76bdf02da5e5b550717ec0a62044cd9c66c70d1a242880147e7db4ee6ea3083452d6130854660ba465f56f754e765d09491df82fe43295f7bbdaebab91e5a5c48fe4ade7a59bc59c22a3ed85718411a6dc71c8533fde1c8c0e6a9684520140f96052382eb2c43d45911b47fc8d238869acac2152f9f0c5fa539d3836c41309c90856577373b3552355b36ed2421e6cf2914f0ad9e6ee939f4d79eff242dadffe8965497f1ad0013115e086f6a133c85b49b80ad5fc440bb01d10208bb1ae326d59c70f43d2ac1b68845677462ab7e44701c09cec95490e5a0c7529ea224d21a31612eede0cf618c2769362e3a7bb08a72a3c0eefcd0b7c42846a53d2e67662c12b578a00fca2ca05ebf31a6bda526c0d0ceb358e0eec6a2253a66aa52f37a778ebffdda55f03e2a670ad19484dbccec8518f940f45e2d7411efc9ccb930aaf27f8daa6f371ef202d29bc2fb7b1fa70815aa3771a5af32d13c37190a360062ad07f1ae08c491264025fe87f2c7a08552d29262d2a5e47b635658c7a63439ff93d08c19798477608be502afba6324c08e6bf0361e72e62ae88e2a0082ac623ce81af3d0563344a66c2c29d84a7e7ea8f99e28f39f57a171c4c8a2bfdddbf2e0631e89d59ab298d1064b909e9b5704cc181966c5983f8de06c69bdcf92d4fd22e3bebb09ff1d52d8366a453af7c91dd0103a385b495d83d5456fbd82c26916a1e209e8a09fdb20d961dbf96b05eea09e92a33c409e35d589a0772fae51f6d59e7f6001f2e390fadf7a9039bf149415ee8d84439578dba72ce221253452e11426c16e911ca58d2412e9850453764a65bce47615f7991f4b2de5b02ca75b2d2ae18354b7aea8a9ffcbe94fef47a6adf7f97a020828e230bafdf0eae9ae70617cd2f680483de3fd75ccb7084456b55fa679e9a98a884013abba6e40cd18aff226443748b79126a355c4d5bcd846faf8089853d5d4e3aa9e5a3d1aac9121a837998b4d3574d13b52f671f8b423276c17df544f11f7b87bd55d796a34a3fb31073450277fd0a27b4cd69109c36eee5155f9515657be017e6034e78e642e997b3b0f3a2b1f8632c4f43fdf6b02d66383d19bafff6c7ecc1776bc6f3e41b5fc4cd8b95316666f44fcebc3334084a8f2bc8008adda3f9c1ed69f4c6b48bafaf72c2a1418d4167225a9aad32c8cf723f499e1793dca13c255ecdfe1c16c0d35ff08ab76bdcac2f89b782629b3aec2acd415803d0d8cf54bceb085dc0f5318c9aa6e2d1f2fbe5529b7a77233590902f4acea97c93a1fd3ea9152d4f01b1b5c58768d6e0127b016a15554bea8e46047cae22c18bbff5a42a47b559793aca1708129b7b49580bde6fb976765f48763257b67e5a136060d1dc203abb3b7f98032c2281093b44799663e43ab114037f52665bffe5d4c0d4922f19628d9a824ee45916f5247bc2a47fbff790a20c28b5a7a9cc4ebc42f61302e65dc977becab6474abed1632bd1c1e784361abed5a3add0264834647aae18605c76a79b6926dc13c56bc5710c1251d2532ececcd3202d46090ec49bf22334de53ca257b4953f712133393d024afb9879d3c2eaf8a776b4deb73ff835c90fefdeb50667f4b827328637dfd33696b9691123f6d4d0e0f0fd7dee5028674fe13d52be45b545f8d9fcc143f266027c0477b890334294284decfa360248a7357c06e5af048d0c681a72244354de1389426b4

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }
}
