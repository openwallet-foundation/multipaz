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
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
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
import org.multipaz.nfc.AlternativeCarrierRecord
import org.multipaz.nfc.CollisionResolutionRecord
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
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import org.multipaz.util.getUInt16
import org.multipaz.util.toHex
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
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
 *    [MdocReaderNfcEngagementType.NFC_CCE_AND_NFC_CONNECTION_HANDOVER] tapping an ISO/IEC
 *    18013-5:2021 NDEF-only mdoc,
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

            CollisionResolutionRecord.fromNdefRecord(record)?.let { cr ->
                return "Collision Resolution:\n  Random Number: ${cr.randomNumber} (0x${cr.randomNumber.toString(16).padStart(4, '0')})"
            }

            AlternativeCarrierRecord.fromNdefRecord(record)?.let { ac ->
                val auxRefs = ac.auxiliaryDataReferences.map { "\"$it\"" }
                return """
                    Alternative Carrier:
                      Carrier Power State: ${ac.cps}
                      Carrier Data Reference: "${ac.carrierDataReference}"
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
                val cms = MdocConnectionMethod.decodeNdefHandoverCarrier(
                    record = record,
                    senderRole = senderRole,
                    fallbackUuid = null,
                    auxiliaryRecords = allRecords,
                )
                if (cms.isNotEmpty()) {
                    return "Carrier Configuration: ${cms.joinToString(", ")}\nPayload Hex: ${record.payload.toByteArray().toHex(byteDivider = " ")}"
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
            val encodedCommand = command.encode()
            check(encodedCommand.size <= maxTransceiveLength) {
                "Command APDU is ${encodedCommand.size} bytes which exceeds maxTransceiveLength of $maxTransceiveLength bytes"
            }
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
            val encodedResponse = response.encode()
            check(encodedResponse.size <= maxTransceiveLength) {
                "Response APDU is ${encodedResponse.size} bytes which exceeds maxTransceiveLength of $maxTransceiveLength bytes"
            }
            transcript.appendResponseApdu(
                comment = responseComment,
                detailComment = responseDetailComment,
                cborBytes = responseCborBytes,
                apduHex = encodedResponse.toHex(),
            )
            return response
        }
    }

    /**
     * [MdocTransportFactory] that uses an in-memory loopback channel for [MdocConnectionMethod.Ble]
     * (recording BLE messages to [transcript]) and delegates [MdocConnectionMethod.Nfc] to
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

        private fun getChannelPair(cm: MdocConnectionMethod.Ble): ChannelPair {
            val isCentral = cm is MdocConnectionMethod.Ble.GattCentral ||
                cm is MdocConnectionMethod.Ble.DirectL2capCentral
            return channelPairs.getOrPut(Pair(isCentral, !isCentral)) {
                ChannelPair()
            }
        }

        override fun createTransport(
            connectionMethod: MdocConnectionMethod,
            role: MdocRole,
            options: MdocTransportOptions
        ): MdocTransport = when (connectionMethod) {
            is MdocConnectionMethod.Ble -> BleEndpoint(connectionMethod, role, getChannelPair(connectionMethod))
            is MdocConnectionMethod.Nfc -> when (role) {
                MdocRole.MDOC -> NfcTransportMdoc(role, options, connectionMethod)
                MdocRole.MDOC_READER -> NfcTransportMdocReader(role, options, connectionMethod)
            }
            else -> error("Unsupported connection method: $connectionMethod")
        }

        private inner class BleEndpoint(
            override val connectionMethod: MdocConnectionMethod.Ble,
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

            override suspend fun open(eSenderKeyBytes: ByteString) {
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
            credential: Credential?,
            keyBindingRequired: Boolean,
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

    private fun getStaticBleCentralClientMethod(): MdocConnectionMethod.Ble.GattCentral =
        MdocConnectionMethod.Ble.GattCentral(
            uuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        )

    private fun getStaticBlePeripheralServerMethod(): MdocConnectionMethod.Ble.GattPeripheral =
        MdocConnectionMethod.Ble.GattPeripheral(
            uuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        )

    private fun getStaticNfcConnectionMethod(
        commandMax: Long = 65535L,
        responseMax: Long = 65536L,
    ): MdocConnectionMethod.Nfc = MdocConnectionMethod.Nfc(
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
        val credential = KeyBoundMdocCredential.create(
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
        handoverOptions: MdocReaderNfcHandoverOptions = MdocReaderNfcHandoverOptions(
            engagementType = MdocReaderNfcEngagementType.NFC_CONNECTION_HANDOVER_ONLY,
            random = Random(42),
        ),
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
                    mdocTransport.open(DeviceEngagement.fromDataItem(params.deviceEngagement).eDeviceKeyBytes)
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
            readerTransport.open(deviceEngagement.eDeviceKeyBytes)

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
            readerSelectConnectionMethod = { methods -> methods.filterIsInstance<MdocConnectionMethod.Ble>().first() },
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
            #     Carrier Configuration: ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6
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
            readerSelectConnectionMethod = { methods -> methods.filterIsInstance<MdocConnectionMethod.Nfc>().first() },
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
            C-APDU: 00a4040c07a0000002480400
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
            C-APDU: 00c300000000ec5381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158872f45650b32da8336dbc5cffbd9a2a58547218b34e2ee7142b24b22bbc7d86c52887d8b86b4baf2e542d8f2f17d44c446afb872ed8f9b757fcb2ca1652505648ef267f1179e01a184571fb7343094d1465c25a1c5bec0d39ba3c57edfabf89504b846725d24edb42e2c03e3410acc98c673ed7531573090840182ea87ea6fbf33ee6fb2d75e8ab7fff7
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e'
            # }
            R-APDU: 53820738a1646461746159072f57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e9000

            # The mdoc reader sends SessionData (status=20) to terminate the session over NFC.
            # {
            #   "status": 20
            # }
            C-APDU: 00c3000000000b5309a16673746174757314fff7
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
                methods.filterIsInstance<MdocConnectionMethod.Ble.GattCentral>().first()
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=80).
            # NDEF Message (2 record(s), 80 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 17 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "cr" (Hex: 6372)
            #           Payload Size: 2 bytes
            #           Collision Resolution:
            #             Random Number: 14831 (0x39ef)
            #         Record #2:
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
            #     Carrier Configuration: ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6, ble:gatt_peripheral:uuid=null
            #     Payload Hex: 02 1c 02 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            C-APDU: 00d60000520050910211487215910202637239ef5102046163010130005a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a101000000
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
            #     Carrier Configuration: ble:gatt_central:uuid=null
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
            #   "data": h'3cfb9d16be5fd0c9e7e5367ab37b424bcd77525621058051277751dda1218d2813482a1691ee538670083ef0a9b9e520059d4367bee58339d33f3a2a32d1444612a1da0a5c0689692413b2442d8e926e5127373b3788d37a0d3d64c826fd744831a46c9419fe00b5ba07c5a19a01ac000e47ac92ba46afcdecbf0b892906062bf57778256d5d91'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158873cfb9d16be5fd0c9e7e5367ab37b424bcd77525621058051277751dda1218d2813482a1691ee538670083ef0a9b9e520059d4367bee58339d33f3a2a32d1444612a1da0a5c0689692413b2442d8e926e5127373b3788d37a0d3d64c826fd744831a46c9419fe00b5ba07c5a19a01ac000e47ac92ba46afcdecbf0b892906062bf57778256d5d91

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'f9f80aa8d75a0f4a8c194c0b7b146e6bb76586b7cd07936a4904972fbb3eccdab27047209cda94f462211b5bb3b8b5ecaa687bfc9723e3a8f169a61fc0488c74b1b3e2429a6b6340bc991d1bd7a530ba91b5abaef2a45ce4c6468b5076c6e98d9e832c65b709e816f98c7110fe25691ee0755b10bbe01ac851d79dee50608da037ab6b942a2532940a8f593f732ec8de4f32ab3afd2fa5360f20bfa408282d561664cb5e95712125e874923ea796d86fc34a18dc3342dc56bcee7a2987dd773d483919575351e4c14d50e2451820f26f4ced79b78439ada4859655b387b7aabd2a2d410369c79008845b89af079fffac52939fedac49b2f51bb23300b66ac4a9eda9986beb618a609a5678066573db92e30d75ba18a472725fbc94d088d4ddf014aa4d84822d90ddb5e11955736ff87d58332089469b4411bae5bd2bac378478d4f1db655d439be963ef9517ecb3b325ff0d3dd5fe54520a0e454b510dd45b585a9359ac6ce0241c6b2e6c53e7196a94643721947d8be48097d1e17017667280e9683984dbe77e64a3022df7deb493ddc72657e6e06e0581acf7b3b55bca28a964de4fc07010615a0ebd5fa39f55726bc5d2027050d0716473b8a953404b2f484f104d917a3fb6efdaef10674ac5e84dd9c7d32be265008ea87645b39e36c7db78c4505faa9b9be440f05050d2245b691f46a8b33e9b60321d29bf5a71a451de913554c9a4164100edd4320ed7ed49960e2cd7a3a4b3a3adc9a37b5ea8d0b9c212229075a43d3ef8f912fbcd8c090c59cef70fe9c73c90567c2ac6e581087502ed29af059d0aaa4243cfd7d055cbdc4566169272c54196d79dbeae807bf8ba4f9eb847a19005104b4c1ec21697cb5a4c9f79ec23e0dae9da2bccbe200307ad8dd11c144afd14c765bbe2390ee57edba13e3795d6ff4cbc24d931e6fc750ad46799cb778fafdf8abdead6e7747a7d4382282b33c496c1938794b2932449fecde7e5ca086240c8a86e46b6f4edfef43a7ce1f46d63808d3d006ab14a98525f37fe0e97e61d1e7aa56a3af3b43a9ba94cefdc7f329a6910d9ffcebe627d167f20fbc5fa865a1df1adcbbf62f4f0ad5c80460c79470121595b1f1fe91be9d2ba4f0b6b5dc9af4af227b54184af0c68352a3c84dba41f48be9ce2fbd4da8165daf49bfda9c26220dc89858113764084e85bc150d9720492e6d12a2adbfcb95ca44165e811775d469ac444d75cb85735ec8e8d3ace2dfb157527f765809b45d1ac085a85c8a074a177aa9818e5e49faaf2e4db7909e9f4228be38d5da32311ed6585ee7d6ec833a1b5d64b33f7ed835f0260f40cfe5e00f284d49e401167605c1ed945a23ab75a2e7a388e2e4091aef35ccbe60e821f88760506eb3efe3624ffd428e851a5ea096b657d213b864e4f6427691e1277cd43e5d4d1949a91c6e542eceee32e3d7991e62cab145605ca7bc73bee8981082a78cf660399d8dc9153d087e39b928954925ea55470088126decf5e6ea196ee428d85a0ca7120167856e31a24fa6b8a68181f5d9847d52a8351d18bc3406339915e109c25182fe34ea877b00226e288ad59b78fe58135d6db56387d35542c7804c114689e5782c2ccce1c0d4f3180174a164c92ae3f3ac0060caca45cb4de5f4a774b427b15fce44308b4e68f27624917a4324540ee8bc6d5b13ca2ec0754d35adea83992190ebd9bcd6b955e24bf2e51c1766b8d5931d34e8c0779480ae56d43c878823bebdd6e0334bba51b47aed2495a47893bb82a4c8014f67793b2c5c3fc0a32a16a0ae1d9f4875d82ebe76afe985ce1a6428075f833234ec0957df9513632c29ad7c4c6a8fbec8e5fa884413b416f590f853fb18cd4ab3f6c77582d23335f6b1932c0ba04bc98d5baea88a3289de1d1d6176fddd655c101db8e50e0a90f2a5739c2f0e6c60cd827456b188f4a9eeb577ef2ecdca59991a3b5bbb704bd54d25b7c2520ad527c7f432bfafee987b48ebd38908b4913dcf7b184d3142ddc110f47bd4570a462f82406022309c9c028dcd989e1b7002c6cf4791256afea79771cac82ed482b963eac90dd90ec8003495eb6ff816c076d3795342e353cdaa5067a94e644e3efba275d051c49daf7738d312c2f32d45e6c2a4fa51068f5e7574644f605518947b5132981868d17cc6cedfa8d59342927c8d5e68edaa4b347caf73ccf457f908fee41b74f6ef04e51b048609a765de997233e1843a066405e2375ff6ee776a65cd74acc9ff9d0f2f3995dda3c40cac7ec2ca3f1b646d31f9de39ca2f6b3975b74a890d2d803849ae860a3b5fc9b80416c1574b457180d5467856287c01e9e746868057f8f037deee6a3a800e14e549e4d3724f952843fbcfdd8af621354a926eefabff7a644feca62c142b92c704cc74b6e88275c3bbfe5424a640aed22c2b5324e7bed5ff701a09652e07cf4cf88c0c22cccd57460681688ab1ffced23d254506b6e355ad0482ae4934da33fc8e5576d8e92b075c286c0d538a2b4dbc6a13bd445df6b04c7830af3b6c62185f90dd26728012f9d15ecf09821efc8de213a7918d575364cbfcfccf3eba96117cd284327663213d998e1'
            # }
            BLE Mdoc->Reader: a1646461746159072ff9f80aa8d75a0f4a8c194c0b7b146e6bb76586b7cd07936a4904972fbb3eccdab27047209cda94f462211b5bb3b8b5ecaa687bfc9723e3a8f169a61fc0488c74b1b3e2429a6b6340bc991d1bd7a530ba91b5abaef2a45ce4c6468b5076c6e98d9e832c65b709e816f98c7110fe25691ee0755b10bbe01ac851d79dee50608da037ab6b942a2532940a8f593f732ec8de4f32ab3afd2fa5360f20bfa408282d561664cb5e95712125e874923ea796d86fc34a18dc3342dc56bcee7a2987dd773d483919575351e4c14d50e2451820f26f4ced79b78439ada4859655b387b7aabd2a2d410369c79008845b89af079fffac52939fedac49b2f51bb23300b66ac4a9eda9986beb618a609a5678066573db92e30d75ba18a472725fbc94d088d4ddf014aa4d84822d90ddb5e11955736ff87d58332089469b4411bae5bd2bac378478d4f1db655d439be963ef9517ecb3b325ff0d3dd5fe54520a0e454b510dd45b585a9359ac6ce0241c6b2e6c53e7196a94643721947d8be48097d1e17017667280e9683984dbe77e64a3022df7deb493ddc72657e6e06e0581acf7b3b55bca28a964de4fc07010615a0ebd5fa39f55726bc5d2027050d0716473b8a953404b2f484f104d917a3fb6efdaef10674ac5e84dd9c7d32be265008ea87645b39e36c7db78c4505faa9b9be440f05050d2245b691f46a8b33e9b60321d29bf5a71a451de913554c9a4164100edd4320ed7ed49960e2cd7a3a4b3a3adc9a37b5ea8d0b9c212229075a43d3ef8f912fbcd8c090c59cef70fe9c73c90567c2ac6e581087502ed29af059d0aaa4243cfd7d055cbdc4566169272c54196d79dbeae807bf8ba4f9eb847a19005104b4c1ec21697cb5a4c9f79ec23e0dae9da2bccbe200307ad8dd11c144afd14c765bbe2390ee57edba13e3795d6ff4cbc24d931e6fc750ad46799cb778fafdf8abdead6e7747a7d4382282b33c496c1938794b2932449fecde7e5ca086240c8a86e46b6f4edfef43a7ce1f46d63808d3d006ab14a98525f37fe0e97e61d1e7aa56a3af3b43a9ba94cefdc7f329a6910d9ffcebe627d167f20fbc5fa865a1df1adcbbf62f4f0ad5c80460c79470121595b1f1fe91be9d2ba4f0b6b5dc9af4af227b54184af0c68352a3c84dba41f48be9ce2fbd4da8165daf49bfda9c26220dc89858113764084e85bc150d9720492e6d12a2adbfcb95ca44165e811775d469ac444d75cb85735ec8e8d3ace2dfb157527f765809b45d1ac085a85c8a074a177aa9818e5e49faaf2e4db7909e9f4228be38d5da32311ed6585ee7d6ec833a1b5d64b33f7ed835f0260f40cfe5e00f284d49e401167605c1ed945a23ab75a2e7a388e2e4091aef35ccbe60e821f88760506eb3efe3624ffd428e851a5ea096b657d213b864e4f6427691e1277cd43e5d4d1949a91c6e542eceee32e3d7991e62cab145605ca7bc73bee8981082a78cf660399d8dc9153d087e39b928954925ea55470088126decf5e6ea196ee428d85a0ca7120167856e31a24fa6b8a68181f5d9847d52a8351d18bc3406339915e109c25182fe34ea877b00226e288ad59b78fe58135d6db56387d35542c7804c114689e5782c2ccce1c0d4f3180174a164c92ae3f3ac0060caca45cb4de5f4a774b427b15fce44308b4e68f27624917a4324540ee8bc6d5b13ca2ec0754d35adea83992190ebd9bcd6b955e24bf2e51c1766b8d5931d34e8c0779480ae56d43c878823bebdd6e0334bba51b47aed2495a47893bb82a4c8014f67793b2c5c3fc0a32a16a0ae1d9f4875d82ebe76afe985ce1a6428075f833234ec0957df9513632c29ad7c4c6a8fbec8e5fa884413b416f590f853fb18cd4ab3f6c77582d23335f6b1932c0ba04bc98d5baea88a3289de1d1d6176fddd655c101db8e50e0a90f2a5739c2f0e6c60cd827456b188f4a9eeb577ef2ecdca59991a3b5bbb704bd54d25b7c2520ad527c7f432bfafee987b48ebd38908b4913dcf7b184d3142ddc110f47bd4570a462f82406022309c9c028dcd989e1b7002c6cf4791256afea79771cac82ed482b963eac90dd90ec8003495eb6ff816c076d3795342e353cdaa5067a94e644e3efba275d051c49daf7738d312c2f32d45e6c2a4fa51068f5e7574644f605518947b5132981868d17cc6cedfa8d59342927c8d5e68edaa4b347caf73ccf457f908fee41b74f6ef04e51b048609a765de997233e1843a066405e2375ff6ee776a65cd74acc9ff9d0f2f3995dda3c40cac7ec2ca3f1b646d31f9de39ca2f6b3975b74a890d2d803849ae860a3b5fc9b80416c1574b457180d5467856287c01e9e746868057f8f037deee6a3a800e14e549e4d3724f952843fbcfdd8af621354a926eefabff7a644feca62c142b92c704cc74b6e88275c3bbfe5424a640aed22c2b5324e7bed5ff701a09652e07cf4cf88c0c22cccd57460681688ab1ffced23d254506b6e355ad0482ae4934da33fc8e5576d8e92b075c286c0d538a2b4dbc6a13bd445df6b04c7830af3b6c62185f90dd26728012f9d15ecf09821efc8de213a7918d575364cbfcfccf3eba96117cd284327663213d998e1

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
                methods.filterIsInstance<MdocConnectionMethod.Nfc>().first()
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=116).
            # NDEF Message (3 record(s), 116 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 28 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "cr" (Hex: 6372)
            #           Payload Size: 2 bytes
            #           Collision Resolution:
            #             Random Number: 14831 (0x39ef)
            #         Record #2:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 4 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "0"
            #             Auxiliary Data References: []
            #         Record #3:
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
            #     Carrier Configuration: ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 1 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01
            C-APDU: 00d6000076007491021c487215910202637239ef110204616301013000510206616301036e6663001a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a1010000005c11010369736f2e6f72673a31383031333a6e66636e666301
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
            C-APDU: 00a4040c07a0000002480400
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
            #   "data": h'09f0309087b8506535b206e5a5545892173c8b27eedf1b7676804637a84e5ee15fce985a5b0dcef313a03bc59283c3e9614e51846b8022e7f52ae90c8bbe07904ef95249c84aea5f59d4737dd8378a1847b1fdfbe293df92843d63691f0020fa4eabdd33f2ca2658af532626ff7e7fa9b0957478e778c3511ad6b4e2016104308e7408854165f9'
            # }
            C-APDU: 00c300000000ec5381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa6464617461588709f0309087b8506535b206e5a5545892173c8b27eedf1b7676804637a84e5ee15fce985a5b0dcef313a03bc59283c3e9614e51846b8022e7f52ae90c8bbe07904ef95249c84aea5f59d4737dd8378a1847b1fdfbe293df92843d63691f0020fa4eabdd33f2ca2658af532626ff7e7fa9b0957478e778c3511ad6b4e2016104308e7408854165f9fff7
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'9534ba1266590b2f506f52cfda55da498614672bb3613daf5800a46e8eded18a7b5300279001f064cc663f8fe98ec6c48e0ae3e83c6e8092a4f6fe908e1aa32377c8dd5019595b2f1ceefa0292fd867b4e3a0c2b964fc0eecef891f666336aa862de7596da13021c53afbab4f6829fb92822c5d2c9150134ede2e756137dbecad9965a6dfb9c13ef45df738085d000eac89e428d569165b88157c52431be22a2dca7fb5ead4e1ccb838b5ed9699d901e05e55f0738f23b44380b132277db88e9bba7bffc88b9fb0d8a8152bdd72b26ff55e2f5ed385168049bd1679c9828dca523fbb364e08ca3bdfee31e64d238d5588c54efc3a94310fcf5b7261ecc2cdc416031a9eaa49c201241bf2d1cbfcf9dde72be15617df9887b2025c8c920dfe39c045be672ed8c0aeab8545a8e3bce33612b46605b7f10ab18479f15fc80267831f1327c7bd68858112b67fc86680bdc7f89bc5f3cd8d8de3a2ca4ce719e690810a8ef0917950304d1602fac6e1157eeb96f05659ec5bbc8bbb89c036e3c36af94933f327a0d63fa709761a2a368c5e1e4f990b00cceacea9faddbc2d9604db3de7d967e7c333e5f9cfffa1c8c843bbb475d86d5f6efd2940b2c213a5dddab28aead1366f858294ef888a5c9bf4b89b4ef94c5d9a6d821026fea9f99c3873c15dd98ffb0397256b8f6826d455e956446d103d5a5981fb49138d66c247c8338d143fba53eda031f4491806e381601cb6c0d8d678f2ea38a2d4d2a3f9cd6312406a83360509946a8468379a8f7ff59cea0667bd391a812224667213c2bc9a0807cd1f9cf61f7280b79405feef69bae7ccf8bc0577324fb514b2c64a181068ea674061c3b10713067b904cae8350f8275d41e1c32a59d20d56d56007f2bf73d1e7ec7e69f513a9d448d8f38ae9c5afec8df60499463830814bf4c6f92d1f9652e64c0eb3b63e499eabbdf8320068be3133b500c5cfc3ecc5ea5e0f55b917f1d3ab9572ed38580fc15fd1e7ee39c446269a770f2516eba3b7b8ad609815d3482c5d9718ceb3b1d0580d630df08802e816751a55d6eabb6a181dc6e337c6d27c8a3143388a64f7f2bdc83cc31b623154a187899b47278dd4bcaec60472034f9835790417008f9523032c43ccf2affd334d2f3c4c9037a6676bd88ba840e74bf60392f674831769b2d80e0c0d206d315f45cc7da58fa11b365e96c2742e04c813059d25b210938aede52b7e12f18d2b5f0e7e663ff3dc143586d8a0e1c1779764eceefecfbe245421039f7452c90150b22cc45b5f88c6055f89c63aaaf94d48971aadf646ad0e49184354121037aa5210d75f6dc7b4266162ce76a88dd11ae06ff82c6bf6e37032e533f59aa4a1b317f75a15941dcb9c7b2e0ab341056d35f0e2ba6386f2bd5970acb2e02ee9e03358e4003d1d4f39c06fce808856be44ff667d856e283e7e36a5c9ac3150d46e2a9f5adddd04ac2f8e608458fa260c914b14147d98569f44fbaeef6a67254cc46fa4eabead1e767aa2a4e2a73332e91e817d7ea483e2603078c08008c08a5fbd63fcaa350b1c915e1d9b55ffdf57edb32de402307a2784688bd7ca748145d368af4d5af1fb9f43d584a35ce5d1f230f1fb692cc29616a05aab895a421562f3c4e2f34776877f754cc2c4ae69eb1673860d1f346a62bc276a0dbf302d6d3adb0a4668517296eafcd2f6be83b2de5de34adf0cf12cce6afe3e1270139ef666226758c16b9c2729c709f0abfbf20cdbb0f6f77bd5feb78f4c0c110b965dd2b40fa1a06e351b8a8818a15f06e7aee82167468eb5ce61959a7c073fa755dc767767e1ef74a1ba574ef77377ad6bae8680fcade0b879193f0c41b4208454b6206bda2f1c03909ce87de74cad87a55cb42c9216b1b3ab180ae93b93aea5222c8d3a12704c80f1872535a1def9e3e58e6b7a218fc744bfc43e7d6788ed4220cd498b1e12851ce30639d78868ba3a40fef162726d408d7d2ad968012ac95d0089aaa1dd9f69a92f82ac7a42c950339a27af41cccd6aa3ca21d79605ad5bfd3381a87e36d7ee4d352d4237626cf89fc6f1ac0bf9dc8a2f427cddf7d68fc918aceb05397df8ba32cd0f60e9ab0c845dc32fff291bbf9b8a29a805c0c30bcb38aa36248d8dbb98a7231cfcd2a46ad2cb44c068ffe2567cfc912c43511b265d50cd16b86f41a93e14ec450d3e5d7cbe33bf079ff5250cffed69277d46d66854681569e46883ecb5fa098cd3d61d534af8a08dad1d15ac09d39e97430c1b82f31aed002e434b7ca6feea8284b132197c3364428a05fed7c79845e1f9fc1fb2a15ff47c056b5cf6174cd3051d3c0d796d5be29bdc65378957cda1012f4c7acb23e9f9d6cdbb4895ff0f7fafefab4153d9735e2237bc4ee9e108185670b95bf1cec05d4d2ef409ee2485d5a3aaee230546e42b0d3b639e1c0c5583986e240252c27f06ebefa3cc292eb65fc49856f9610e8a921cd40842094f2f8597fa324c845155331fe09403fa9db309c4c15f3560d1169929b9f44322c8fb787448f2054661c22035fcc7d7a0285c8cf835c8085132da0502e0322f248013e00d43851f1751948f98e89fff3f06d5b16de55'
            # }
            R-APDU: 53820738a1646461746159072f9534ba1266590b2f506f52cfda55da498614672bb3613daf5800a46e8eded18a7b5300279001f064cc663f8fe98ec6c48e0ae3e83c6e8092a4f6fe908e1aa32377c8dd5019595b2f1ceefa0292fd867b4e3a0c2b964fc0eecef891f666336aa862de7596da13021c53afbab4f6829fb92822c5d2c9150134ede2e756137dbecad9965a6dfb9c13ef45df738085d000eac89e428d569165b88157c52431be22a2dca7fb5ead4e1ccb838b5ed9699d901e05e55f0738f23b44380b132277db88e9bba7bffc88b9fb0d8a8152bdd72b26ff55e2f5ed385168049bd1679c9828dca523fbb364e08ca3bdfee31e64d238d5588c54efc3a94310fcf5b7261ecc2cdc416031a9eaa49c201241bf2d1cbfcf9dde72be15617df9887b2025c8c920dfe39c045be672ed8c0aeab8545a8e3bce33612b46605b7f10ab18479f15fc80267831f1327c7bd68858112b67fc86680bdc7f89bc5f3cd8d8de3a2ca4ce719e690810a8ef0917950304d1602fac6e1157eeb96f05659ec5bbc8bbb89c036e3c36af94933f327a0d63fa709761a2a368c5e1e4f990b00cceacea9faddbc2d9604db3de7d967e7c333e5f9cfffa1c8c843bbb475d86d5f6efd2940b2c213a5dddab28aead1366f858294ef888a5c9bf4b89b4ef94c5d9a6d821026fea9f99c3873c15dd98ffb0397256b8f6826d455e956446d103d5a5981fb49138d66c247c8338d143fba53eda031f4491806e381601cb6c0d8d678f2ea38a2d4d2a3f9cd6312406a83360509946a8468379a8f7ff59cea0667bd391a812224667213c2bc9a0807cd1f9cf61f7280b79405feef69bae7ccf8bc0577324fb514b2c64a181068ea674061c3b10713067b904cae8350f8275d41e1c32a59d20d56d56007f2bf73d1e7ec7e69f513a9d448d8f38ae9c5afec8df60499463830814bf4c6f92d1f9652e64c0eb3b63e499eabbdf8320068be3133b500c5cfc3ecc5ea5e0f55b917f1d3ab9572ed38580fc15fd1e7ee39c446269a770f2516eba3b7b8ad609815d3482c5d9718ceb3b1d0580d630df08802e816751a55d6eabb6a181dc6e337c6d27c8a3143388a64f7f2bdc83cc31b623154a187899b47278dd4bcaec60472034f9835790417008f9523032c43ccf2affd334d2f3c4c9037a6676bd88ba840e74bf60392f674831769b2d80e0c0d206d315f45cc7da58fa11b365e96c2742e04c813059d25b210938aede52b7e12f18d2b5f0e7e663ff3dc143586d8a0e1c1779764eceefecfbe245421039f7452c90150b22cc45b5f88c6055f89c63aaaf94d48971aadf646ad0e49184354121037aa5210d75f6dc7b4266162ce76a88dd11ae06ff82c6bf6e37032e533f59aa4a1b317f75a15941dcb9c7b2e0ab341056d35f0e2ba6386f2bd5970acb2e02ee9e03358e4003d1d4f39c06fce808856be44ff667d856e283e7e36a5c9ac3150d46e2a9f5adddd04ac2f8e608458fa260c914b14147d98569f44fbaeef6a67254cc46fa4eabead1e767aa2a4e2a73332e91e817d7ea483e2603078c08008c08a5fbd63fcaa350b1c915e1d9b55ffdf57edb32de402307a2784688bd7ca748145d368af4d5af1fb9f43d584a35ce5d1f230f1fb692cc29616a05aab895a421562f3c4e2f34776877f754cc2c4ae69eb1673860d1f346a62bc276a0dbf302d6d3adb0a4668517296eafcd2f6be83b2de5de34adf0cf12cce6afe3e1270139ef666226758c16b9c2729c709f0abfbf20cdbb0f6f77bd5feb78f4c0c110b965dd2b40fa1a06e351b8a8818a15f06e7aee82167468eb5ce61959a7c073fa755dc767767e1ef74a1ba574ef77377ad6bae8680fcade0b879193f0c41b4208454b6206bda2f1c03909ce87de74cad87a55cb42c9216b1b3ab180ae93b93aea5222c8d3a12704c80f1872535a1def9e3e58e6b7a218fc744bfc43e7d6788ed4220cd498b1e12851ce30639d78868ba3a40fef162726d408d7d2ad968012ac95d0089aaa1dd9f69a92f82ac7a42c950339a27af41cccd6aa3ca21d79605ad5bfd3381a87e36d7ee4d352d4237626cf89fc6f1ac0bf9dc8a2f427cddf7d68fc918aceb05397df8ba32cd0f60e9ab0c845dc32fff291bbf9b8a29a805c0c30bcb38aa36248d8dbb98a7231cfcd2a46ad2cb44c068ffe2567cfc912c43511b265d50cd16b86f41a93e14ec450d3e5d7cbe33bf079ff5250cffed69277d46d66854681569e46883ecb5fa098cd3d61d534af8a08dad1d15ac09d39e97430c1b82f31aed002e434b7ca6feea8284b132197c3364428a05fed7c79845e1f9fc1fb2a15ff47c056b5cf6174cd3051d3c0d796d5be29bdc65378957cda1012f4c7acb23e9f9d6cdbb4895ff0f7fafefab4153d9735e2237bc4ee9e108185670b95bf1cec05d4d2ef409ee2485d5a3aaee230546e42b0d3b639e1c0c5583986e240252c27f06ebefa3cc292eb65fc49856f9610e8a921cd40842094f2f8597fa324c845155331fe09403fa9db309c4c15f3560d1169929b9f44322c8fb787448f2054661c22035fcc7d7a0285c8cf835c8085132da0502e0322f248013e00d43851f1751948f98e89fff3f06d5b16de559000

            # The mdoc reader sends SessionData (status=20) to terminate the session over NFC.
            # {
            #   "status": 20
            # }
            C-APDU: 00c3000000000b5309a16673746174757314fff7
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
            readerSelectConnectionMethod = { methods -> methods.filterIsInstance<MdocConnectionMethod.Nfc>().first() },
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
            C-APDU: 00a4040c07a0000002480400
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
                    mdocTransport.open(DeviceEngagement.fromDataItem(params.deviceEngagement).eDeviceKeyBytes)
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
                handoverOptions = MdocReaderNfcHandoverOptions(
                    engagementType = MdocReaderNfcEngagementType.NFC_CONNECTION_HANDOVER_ONLY
                ),
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
            readerTransport.open(deviceEngagement.eDeviceKeyBytes)

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
            C-APDU: 00a4040c07a0000002480400
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
            C-APDU: 00c300000000ec5381e9a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158872f45650b32da8336dbc5cffbd9a2a58547218b34e2ee7142b24b22bbc7d86c52887d8b86b4baf2e542d8f2f17d44c446afb872ed8f9b757fcb2ca1652505648ef267f1179e01a184571fb7343094d1465c25a1c5bec0d39ba3c57edfabf89504b846725d24edb42e2c03e3410acc98c673ed7531573090840182ea87ea6fbf33ee6fb2d75e8ab7fff7
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e'
            # }
            R-APDU: 53820738a1646461746159072f57c1fab3b3fc767df8b43176ccd8dc98063197a799eab804009af3e18b9134ca0b41d99fa272b9034fb2acdc1ec868e53288de032f8a4dedbb5db9d5cb6b807b096e93d483a1cf6af8e79a450918dbba3bcef5d63d93f38b64538791cb840d5158de3d6976e43f70de740ea7f60b051f23aba0bf6db4fa87e4e5753ae5d01dc4b9302c76122ca3d7ee6dbcff634e01a0db6395c99bedef46b54f00fca069e00501fd2cc9603e2cecd9d9266dfebb8003c5d630e79b05e9c639c1396824895b74eb0f42b437099742c1efa3130e2eba7abcbe8843336910c5c04340efa36240550a72eede9967e2859e826f922ba4bfabd9bfea7b6efeeb163b816b3652b7c8a24c034f2d950d09bb1391b1106af65408bacf818379a800fc739c4e0d1940cc93d88ad5b204a16fb913485dd17cbc9f86336a30fab1bdabe2e9122c498d17d1d7aed25415733464d598975abf7fe8a503a123013811361dd6295eec4389f2053da05f11844efec9225570ea0799560ae0ba70457cdd6779f21110aa81d17fa8770a2d5665374c8017ac20a61238c3219b119c8c511587b3d2c7248c5d8d1e0ce2bcefd3864423562e650abdb156114b4c7e783758d3e080fbc0ac2b0dc878aa75f09c3703f533756dd0a3fa271895772761cdc17f6fe2d6f32b80cac9ee60e2cc3cbf340f4ea80693fd59a74fd7c120a31763297aba77e996045cafa7982afab684e16297b943b3a41800a6b66d37b22edfdf91f24d87577278c72d8356c0aa20cd53a448947bb9b90dd300a0f2e7687bd29c5af3bd637652c755d3381fae9428bfcd963fd9b13c1d04976881dfa4462d6a3fc2f18d28a71d03ad0da37125fd3e54ca892035511cbd020a5e92e57f013d6398fc9a196738c25c4efc98f5e74a4c46b2e6c64990c2a7c013a7708241a21d8821e8984442006e2e49d7e482e43b7f877b7abe95c1bc86d74a2de4adbc12131a5360385c04bfe2a47368cbe71c18d0e5225f684d92b213b72e11346b8f58dd50430538bced5b0aaf9569d98f753c2a1b9f90dfab76383fbd89394714ec2504b4ad672e8894083b0454b0446ca1113efa1384424238c7eb91ca8c698b8721600b9ea571b1b4e74e36c693eee7f6a987df683aaa99d1ef0368e81bd1f730a39f78e41614ed27cadcf2a7ab0841f650ee85d90c955e3986c98515350a9853e32987d78a54affcab1873f49f64a0447364a90838d387d62867d2d89ac8e0bbe47f7c8c830d2b7c081d863c4619c46dfd4b1355dea8bbd751570cbfa87890a79a4c742dec990b91e6dbcd8abf9479b66b13d6c4016785ff4e0c9ef7534d5a46e079553e86bf35aa23e18c3c496da9b6fa50b244abe39f75e16c880cef0d91f2b7076e33a80bc69f41b1c79b41eda7a8eda65e19b57faf997f24a7501d60156b836b489f00e1143e241af4fff0bae5b9bb69d385cc4b08bd1ac268cc2fa81f8b10e23923160c66cf15d3b6696bebed3ebc6ec18aadc78f5ec444e370f8a98028bc9dddb1ab37ce5dff586a78338525948a260ebdf5724cdbda4e883f687994f1783b928f5b1fc72fe1a664678c362086511f520905bfa14bcfef10329b6343af61ccd49a30c0bdef11c9af5539f1358da805250d6f6b5c84666a3fda9ca8ee7eec4a1de3505a173d2f8a2fad2d065441d3f06a6a592606b5f8b1a0424c14cf0b61844fb81b13c4421e4bebd164e30ba4214a4c69946e7f318e6e1f9f0001b6af9043c61ffabc51e1b238598143a2730f702b248ccf8fc4867ed9890bb0ce2c86675ca74d3aa5668c9687fe7623cee3e5b4f4c5a4589d9af1a9d99afe482a3f52c6f10ac53dd958f250ff1650e8284dff0346ab9fb227b2c53c62dbfb9a4902be4859861a4e72e5082ffe2868b5ade3dbb3e9f48e5def2f340d25245a3ed0151f0d01ca28405c628da778c3dfaed7618060f9578be474917e0aa08221bd95472f139666b266dec7cd4b8e05324a81c73f9fb616d50348e90dff20adb3e6c9d94a1911c5e27911ceadbd936194d38c797aafd5179fc0ffbd6331cb6a158df3a7b010ef5b928acd4aae0b1bde685e12d114b64a5beafee6125cf4bab6bfb1ab676cd2872b84e0a50443b94d8f2087e01886a145d0f16f9b9b650ac9cdead40034a6a81646e4d5989ddb68d3ef9ae230c6d47951efe1833315800ce2720b78a70e2c369b2697b06e488208153482e750348059cef2ed548d10322d5306acbaf6625fb6093fbc4615229c6b2e5d8846e659af0c5d9c3c166effd671186d4296c2e17715e25851ca2cde76f54b55e4d9f7b047ae25fc66466c850c30c746a43672fbda0a4acaad95fe28f2043d4376624ff231716778a1a99036fe309028b168b6e248781c5172c43fc28a6f41d7e3a0dc539087c80fd680bc027f2c922f961a81cb4fc52c5791b1427a4e2eb8abf940dd19e8b437abd723f7c7f3f0d43c1ddc595abb644c388571e36d86055cf42f370ba5b768e421982e9ad3f9bce3fe4834e40e43c2755645df924c82d12f2b28c42b0a719fbeeaaf1bb95055863e1757b71bf0dcd32bc467ed113a11d7ea79fe3f8e57d314ff1e769cf80a7d841361622d435e056e9000

            # The mdoc reader sends SessionData with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'4dc42daa339744d18830bb12461b7e3c1b664f4c44673b8be6b11328da328ec8b6e21d41766611e09a935a6d808608eb6bc6501993a54fb44f87c6956d04e10d36959f1fbf0fe865680e12c99c887da9f53ef0d852b3594adbe651ae3244cf956e157e118e3ecf6bfeafd934f14bff4a89c1f94c39dc26cbe659961d71c87961ccebaff757a0ddbd1e6a7e'
            # }
            C-APDU: 00c30000000096538193a16464617461588b4dc42daa339744d18830bb12461b7e3c1b664f4c44673b8be6b11328da328ec8b6e21d41766611e09a935a6d808608eb6bc6501993a54fb44f87c6956d04e10d36959f1fbf0fe865680e12c99c887da9f53ef0d852b3594adbe651ae3244cf956e157e118e3ecf6bfeafd934f14bff4a89c1f94c39dc26cbe659961d71c87961ccebaff757a0ddbd1e6a7efff7
            # The mdoc responds with SessionData containing encrypted DeviceResponse.
            # {
            #   "data": h'01aaa1be519f1579f21bcf7c04d0768b75b13b3ffc3591798efdf42cc34c51faef946d40fb496953e93f209f768987f8b434c080f82d98ca6b9c2f19ae31819d3d0aab49eb5c0621ff52bcf12039cf261bdbf4ec4b66e239a14b276688a4fc619a3cb19aeb2999b31808ae51e75ee1ebe5d5d41772266f7e4b7bd4d4ee5afb9891a9f5ce0ddfc05593fddc8421669f7ba938fb33d3de91b7b86d76969c59efff3d032746730726128e42a3ac49986e8a545907c04990226d76a6251b2466aa30b16a6adc224d44b6e21d574a7bbce73dccf3ce5ac932bf2a0d8b2857cfdf91603bbace3ac5ea9f79e66ae15bf2982695440ecd2d055a0dbe915720b1fbde11d4355e3ee2866d04b0262fb4b0b26f6c23a377e23c02b16840cdfb3f93930b029c835b1896808e3c9710618dabbec0935adfe08af8814c407b3ee3a9075c271ddefbe251eed4e4744218bb0a1e022fe6af2ae5f6b73adfd48483bf956177002fc39a949fced972d3919b5abf46940429b5384879289bd7f11fefb5de97140188d496db8679637640862490d6e5073e47ec0772bc0ff1b30685b56b6c65f2fb1952e3eae7e3245534c005a60441450a89952262f9076dde4c07a7b729038264fbd1b8a8fed74b94c18eb344b37968d5a7f9f845d771ffd5a30ce27c0b213649792a55b6120c69ec33517c56b9e58716dc677037f4475d1e04ad4bb43cde973b5246c182388cf55224864d56f846f98e92861bd8b29639daf69642b699080cf5399d5763b79d49ae26bbd0aabf676384345d162397d3914ac2282e99f140a36df7a970a72726d0b8934012807c21c76dbb31d807c4b890e3b8ba42c77cb9918680fd0e3e07aa2081019ba5fce737221c34a61a3952c5a401ce7d6be57b79389d7fd6d42b2e1a1127c72da1d72c653b42279c67fd6472d1568a76d6b332ae66f93631707834088e61c5916dc169e7b7a8f9cb0c55f351ded646cc6f1411138d4b5eeb24356d0f166127611c2ad3799577ba45680e14fd8da026ff82ca09ac1590fb39cfcf0adf17c223c84a1e4302462687800887b32db6214fba0294b880584530d40894e62d207c52156109b2eed623e129b69fbc706f150f1d921fd97a5d609c68c1b3e83f45e03fd7b850bdd640e58a79d8b4c290f44897837d69b5c90931447b44a46028fa2896dfbba6217ced1a1e6a343894e2fadd52429772e601fedf95127f2b12e0275615499f13103ff7db7144d39c7c9f9358bdca58bec8dbe39f64846b7f61650a79940257c8d775ec51ad796676db5cfa6b95918f3b088ee0990e7a15238a031be40fad88941d719695aff87be465557479868ce04589487c323e90a2e862809f45ae4bfb7276a7082921610b56ec0162fb1841b28a355fbf462d6f3e7e540f71c4687199ad4672b2a3cbf3fa3b92a3073d4b2e0d91f57b711685e418766a5d95912ae2609fecff0bf6e152949665e4d95240be002b90ddf31bd0d0f42c014a5c72f0fa44b4c745ab1b8c89a305901098e6aaff7cdd8950bec35b41c07f6ab5ef8fa43f0232ed657ce64365289bb61bb3fb05ee5fb2e867f6b8366ed8b015d4ad02df1ac986ae116231fbbb43f23fe965467ee3b1693b7e836dea4dfc4e6fadb02dde6de4f703b7669c22046271ef660a2564f13c33b2fc5141a5e588444109c413d96e9951705a8eaf97eb0dcba2522184607247b44d2018a9a80de317551c1afbcb83e27a1e9f3bf3629605b59a172cc4d23c8067b9f368df6502b5410e8a0abaae8d25dda6a409758064967ed9fdf65e6a8a7bafb5d7c6ff0e54cde33032e7343280b913b0170b7f93cbcddf5b46192906df9656e350746bb97df11da9b4fabc10cb0d81ff812aef104e1e7545dc5a3729ad4eb41e7a9697dd7f2b6b4760ed24787ae440da49fdabd21e7a90ec379a7bbef9656189ea8f763c2a7f89498a58f0ec0ce2924fb8b3de3c9e6fef3e88c5ee1566ac417499398a0ee6735793856f3dd1b6778e84bf74588331952affacba8a576616dc239f2ba3e09c082c4a4e18006a2925bdcc7e327cab440c4afa7112b7520a74af3c02243773b80c034c518207d4d6db09cdf880279e7fd6f6b8e1cb20eef581f92f76f80ca2a3f689cace0e947b43051f313e587b75b8d88471bf34cc78fa68d3831a569226f97394dd4365c265ead32623c163fe1008ff8a4dd2cf910aae155acf894812d41ed20cd1f0b7867de6f86dda11bd7b3e1c66877842f43d026b79c435bffdd301689d80ff9e0ef1e2c5f631961b7bb9eb06e283d8d40476c01640e6cffb4b115cc3ef6d59e825d3f1ce260a52f69e0eb264a8539700398a504d8387644b37944a66544c3825b31edee9572cd87576be57dda1482502c823bc476c4a8769a88f7fcd6563b1c01e9de52e8caacabd5ff7d4c49d6561f046715dbc8bfa086b836efe57353b9cade5bf43b6f6250b72024d3b01b2bc853dc3c837960953587d6d440cbcd27b308121744d4ffabc59d04b9364ac5e683517a9ff4e5ae0dec7cdac25862a9b94a9d17c284c63aff9f777da7710049d2d0bb9cdccc643366d508a92f992d9140d535bd7553b41eedcfa1a65dd05ab94df2f20c8e1a4757a'
            # }
            R-APDU: 53820742a1646461746159073901aaa1be519f1579f21bcf7c04d0768b75b13b3ffc3591798efdf42cc34c51faef946d40fb496953e93f209f768987f8b434c080f82d98ca6b9c2f19ae31819d3d0aab49eb5c0621ff52bcf12039cf261bdbf4ec4b66e239a14b276688a4fc619a3cb19aeb2999b31808ae51e75ee1ebe5d5d41772266f7e4b7bd4d4ee5afb9891a9f5ce0ddfc05593fddc8421669f7ba938fb33d3de91b7b86d76969c59efff3d032746730726128e42a3ac49986e8a545907c04990226d76a6251b2466aa30b16a6adc224d44b6e21d574a7bbce73dccf3ce5ac932bf2a0d8b2857cfdf91603bbace3ac5ea9f79e66ae15bf2982695440ecd2d055a0dbe915720b1fbde11d4355e3ee2866d04b0262fb4b0b26f6c23a377e23c02b16840cdfb3f93930b029c835b1896808e3c9710618dabbec0935adfe08af8814c407b3ee3a9075c271ddefbe251eed4e4744218bb0a1e022fe6af2ae5f6b73adfd48483bf956177002fc39a949fced972d3919b5abf46940429b5384879289bd7f11fefb5de97140188d496db8679637640862490d6e5073e47ec0772bc0ff1b30685b56b6c65f2fb1952e3eae7e3245534c005a60441450a89952262f9076dde4c07a7b729038264fbd1b8a8fed74b94c18eb344b37968d5a7f9f845d771ffd5a30ce27c0b213649792a55b6120c69ec33517c56b9e58716dc677037f4475d1e04ad4bb43cde973b5246c182388cf55224864d56f846f98e92861bd8b29639daf69642b699080cf5399d5763b79d49ae26bbd0aabf676384345d162397d3914ac2282e99f140a36df7a970a72726d0b8934012807c21c76dbb31d807c4b890e3b8ba42c77cb9918680fd0e3e07aa2081019ba5fce737221c34a61a3952c5a401ce7d6be57b79389d7fd6d42b2e1a1127c72da1d72c653b42279c67fd6472d1568a76d6b332ae66f93631707834088e61c5916dc169e7b7a8f9cb0c55f351ded646cc6f1411138d4b5eeb24356d0f166127611c2ad3799577ba45680e14fd8da026ff82ca09ac1590fb39cfcf0adf17c223c84a1e4302462687800887b32db6214fba0294b880584530d40894e62d207c52156109b2eed623e129b69fbc706f150f1d921fd97a5d609c68c1b3e83f45e03fd7b850bdd640e58a79d8b4c290f44897837d69b5c90931447b44a46028fa2896dfbba6217ced1a1e6a343894e2fadd52429772e601fedf95127f2b12e0275615499f13103ff7db7144d39c7c9f9358bdca58bec8dbe39f64846b7f61650a79940257c8d775ec51ad796676db5cfa6b95918f3b088ee0990e7a15238a031be40fad88941d719695aff87be465557479868ce04589487c323e90a2e862809f45ae4bfb7276a7082921610b56ec0162fb1841b28a355fbf462d6f3e7e540f71c4687199ad4672b2a3cbf3fa3b92a3073d4b2e0d91f57b711685e418766a5d95912ae2609fecff0bf6e152949665e4d95240be002b90ddf31bd0d0f42c014a5c72f0fa44b4c745ab1b8c89a305901098e6aaff7cdd8950bec35b41c07f6ab5ef8fa43f0232ed657ce64365289bb61bb3fb05ee5fb2e867f6b8366ed8b015d4ad02df1ac986ae116231fbbb43f23fe965467ee3b1693b7e836dea4dfc4e6fadb02dde6de4f703b7669c22046271ef660a2564f13c33b2fc5141a5e588444109c413d96e9951705a8eaf97eb0dcba2522184607247b44d2018a9a80de317551c1afbcb83e27a1e9f3bf3629605b59a172cc4d23c8067b9f368df6502b5410e8a0abaae8d25dda6a409758064967ed9fdf65e6a8a7bafb5d7c6ff0e54cde33032e7343280b913b0170b7f93cbcddf5b46192906df9656e350746bb97df11da9b4fabc10cb0d81ff812aef104e1e7545dc5a3729ad4eb41e7a9697dd7f2b6b4760ed24787ae440da49fdabd21e7a90ec379a7bbef9656189ea8f763c2a7f89498a58f0ec0ce2924fb8b3de3c9e6fef3e88c5ee1566ac417499398a0ee6735793856f3dd1b6778e84bf74588331952affacba8a576616dc239f2ba3e09c082c4a4e18006a2925bdcc7e327cab440c4afa7112b7520a74af3c02243773b80c034c518207d4d6db09cdf880279e7fd6f6b8e1cb20eef581f92f76f80ca2a3f689cace0e947b43051f313e587b75b8d88471bf34cc78fa68d3831a569226f97394dd4365c265ead32623c163fe1008ff8a4dd2cf910aae155acf894812d41ed20cd1f0b7867de6f86dda11bd7b3e1c66877842f43d026b79c435bffdd301689d80ff9e0ef1e2c5f631961b7bb9eb06e283d8d40476c01640e6cffb4b115cc3ef6d59e825d3f1ce260a52f69e0eb264a8539700398a504d8387644b37944a66544c3825b31edee9572cd87576be57dda1482502c823bc476c4a8769a88f7fcd6563b1c01e9de52e8caacabd5ff7d4c49d6561f046715dbc8bfa086b836efe57353b9cade5bf43b6f6250b72024d3b01b2bc853dc3c837960953587d6d440cbcd27b308121744d4ffabc59d04b9364ac5e683517a9ff4e5ae0dec7cdac25862a9b94a9d17c284c63aff9f777da7710049d2d0bb9cdccc643366d508a92f992d9140d535bd7553b41eedcfa1a65dd05ab94df2f20c8e1a4757a9000

            # The mdoc reader sends SessionData (status=20) to terminate the session over NFC.
            # {
            #   "status": 20
            # }
            C-APDU: 00c3000000000b5309a16673746174757314fff7
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Verifies backward-compatibility fallback when an mdoc reader has
     * [MdocReaderNfcEngagementType.NFC_CCE_AND_NFC_CONNECTION_HANDOVER] enabled, but the mdoc only
     * supports ISO/IEC 18013-5:2021 NDEF
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
            handoverOptions = MdocReaderNfcHandoverOptions(
                engagementType = MdocReaderNfcEngagementType.NFC_CCE_AND_NFC_CONNECTION_HANDOVER,
                random = Random(42),
            ),
            expectedHandoverType = MdocHandoverType.NEGOTIATED_HANDOVER,
        )
        val expectedTranscript = """
            # The mdoc reader attempts to select the NFC CCE application.
            C-APDU: 00a4040007a000000248040100
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=80).
            # NDEF Message (2 record(s), 80 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 17 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "cr" (Hex: 6372)
            #           Payload Size: 2 bytes
            #           Collision Resolution:
            #             Random Number: 14831 (0x39ef)
            #         Record #2:
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
            #     Carrier Configuration: ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 01 00 00 00
            C-APDU: 00d60000520050910211487215910202637239ef5102046163010130005a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a101000000
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
            #     Carrier Configuration: ble:gatt_central:uuid=null
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
            #   "data": h'24ba815414e118d0d7e1ad858eda1a97dc0e24a8542e340b9f410dadc73da0976dddc4748dde6ebd1108c755cb7681a091e7501e59388341295e189aa935fc32d8b1e3d8f6da5f77b7723af8cd541c747b328b469b2def7b86af0a4013b9611f221e03e79729155d0df99251a679194fea228fef3876379c141e75f79c91e18aa299c55c41e632'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa6464617461588724ba815414e118d0d7e1ad858eda1a97dc0e24a8542e340b9f410dadc73da0976dddc4748dde6ebd1108c755cb7681a091e7501e59388341295e189aa935fc32d8b1e3d8f6da5f77b7723af8cd541c747b328b469b2def7b86af0a4013b9611f221e03e79729155d0df99251a679194fea228fef3876379c141e75f79c91e18aa299c55c41e632

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'25d954ed2786bb50c104fb4357c1526af01aa74a608b0777037b823635bc7c02f178b48f8ee6a7fe69b1333593bfde4f6282e5e5bd09ce38f6ed6892575d5caf55cd72f2563a9eaaeffc17146c78452c03ee36dd1a813198683444181945343031daa638d33cdb082358b89b3aeee5d9a0330471dc238d544398dcf87fc8f31d1708892154b871bd3a246ea3915c1c6670d5a2c5189d075a92a5d35d47d953966b81ccbbc49fbb02c6d70689d22f6b7d4af8c1feb6820e1f9bd677870cf2cdcb4323f9ccfac9563c7fc6d1c422677c84999964312fde81e6ff0de19b26980c47b007123e500da97de6c5e6af07ff4b8933735e30eee44551bca5c6883ec2a5d8898e45b0fbd7aa16cf604423970aaae813055c8ebeb9186e7099810e4979a3ec871090342ca9dc08214daf2527f20f27a2f87cc791b6495a1176c1e1282fb3cd7b4155b1d16865eb3cce71a0c0372135d8d7fde3ebb753c66e892c9ddf55c3a593a6a20a9ed0df64985eadccd70aa91d680f38fa01a0a283b5635167bd7d9bfb60a2ba589d4cfe9e3d4df37b98179d790d75ab5ad5e2838bf72e8cc527c81f70190f7788be48c8481d21131d58f34138a65f61e9c0c1f6b4f23a1c9997a5e34da50c8a8a50acafd291064045e89e3811260c924b850e6bca0d1bc49e71c1ce08cf7b3f667a0eab496b735d6373d15f964e8d1fdd353d70e613b4b1b137afbe4b12015577a69881d4327d8caf3569078a447bc61571e60a5e2118088219608367eb1af7dadfb253082919258f99de80537e21e450f5f3290d8c4ce5f701fc77215be45dcb160fb6f3d2aa1cd09d30c2e0f69ecb8267792c64aaa2e94f13fe41acf1ac658169f32d591c6b61f00db509cc7ece160dfbb88f7c47da44d587ce28a2f33c9aac000cec2fc6ecddc77cae9bcc98a2b6ccc2f91295c59716f73200d4f6215c400101de81371349bccdacd0894cc494cde6f5793e0939a8455eae27b18837472985f5cc7aa8a392b9763851c3eebaaeeb550c27a4bad04c066f334527f5f789e0a1e6798e5a0c9f8fff86a4b3451676158f6b66b2eeb0ab062db9f8d9babfe0455ee91c299e5bdde2f0a5a20f479e66e10ce189428abfab62d5d3df12dbdbb2c6135f7a8137753774c91c3725787a83b1bc97a9f8f243e703acf0f80811fd2364db8b9ef6325a616dbc3f9dfa79d3c859f2c589a057cf7860f19cce91fcf703adf41c74b6e891d94b62e7842868dce5154e43f62b74d21c3d8ac6a97cbc7939c4d49493a979d07782887adb741b32f2780365aac5f5a59771ba9bc49b10d06b57c0b57c5fa3c9d5f1d70879520e6a9349371426a393ff27446a62c1e24117dae7f00c250e3f4764721680fef63ae3a858d85faaf9177d3cbc0a78c643c255494d108bbd812f38a91dc47d2b92792f5e92e151ac7608aa91b3be5c1a9363a066f7150329d251b175a6f917652ee9867874f3c3d8c635665aa0ff88d525fd3854b00ec762d4db7853e38d78adc1eba3b24ad44941b40edc05895add19e0fbb0d8594d1d92e908c6dedf8b96d39e0e53ab77865f506ccde7b044bb65f6b39051ddb986ef6c6c8e02b467e93206cceed054aa795cccbf998935c7887bfb37b486b0a38c75f0ac8d129d251072d5f914660306f467852ec124edf56d0bd01d78e7aad72fb75bcdfb50e0aa2f002de02d55328a59f0e23e9f236fc9266bce99296d18ea44574601c47708a961d65fb08fffa63ea06e44496085e2194dc18876d83d2050ddcd18c642e487ffef2800786df09db1e02aaf031eee879cae1b50668347df6642c7c2265d5c0dcb727ed43f90719aa233cf3e7eb29d6053a43b0e0212d1bd2ed77ad1b072ad221306215a6d29baef904d537ef89a4149382a15f21bd9772e7899cb45223ca516284cc3cfd61802ebfc60fe1e44087a6b2422a4874c69d884809cb7316ef2558f90a10cd7483f3d85bb1e6ac0fae70b34ca2ce1b84e986c3d8dd65b7a09dc60ae82e32bf133ac901f7cfcc04d35814a272ce87a6424144ed2aced46e658e3232a170b37200021a60b60ca74cb48407f82d078b5c9d31720f1a2db06c1cfdd658da8a684d3a9684d70c3fe4d521ee3fec8d951c32033366e7d10e395c8c59b14ab2d1b5166ae91c30ddd2912262afaf974c11cbef3b575b096e6316127a98814147c766af3a3fa8bc4bc44399c467435b608069d43a0018d27b5423702470ff477a5fcf8eabcd1df4dfe63c69cf245152cb0899a712c0115713fe61e6276d383f54045b750fb0ed3916da8edd922c04acb5a0e40a027eb59ecddb2f09f89ce6b1d882effdbc1364dafeb8cda3a8975727a707958c82ae263fd775e3da084d93da58d326e6393e0f2684457c084ae505be4a1ab3d408e047e3071d4cd53eab3a210cdaec194a449f169290cb57a8f25ee9e177c4dfaba277bc67da0a8d0170e6cc4128206d3c17423bfd19fe3dc6fdddc385708da2fa18adc7b3a1e4c006fde951a1ccbedf7e1c0ef2fe39bdd208e06f08700d0d3504f5096c186bee4d7c2ed5e6db59577ed3e1f7773cfaf77fbeb3b9c88b2d0c23088d2abe022ea03f902cf684259872ee77c'
            # }
            BLE Mdoc->Reader: a1646461746159072f25d954ed2786bb50c104fb4357c1526af01aa74a608b0777037b823635bc7c02f178b48f8ee6a7fe69b1333593bfde4f6282e5e5bd09ce38f6ed6892575d5caf55cd72f2563a9eaaeffc17146c78452c03ee36dd1a813198683444181945343031daa638d33cdb082358b89b3aeee5d9a0330471dc238d544398dcf87fc8f31d1708892154b871bd3a246ea3915c1c6670d5a2c5189d075a92a5d35d47d953966b81ccbbc49fbb02c6d70689d22f6b7d4af8c1feb6820e1f9bd677870cf2cdcb4323f9ccfac9563c7fc6d1c422677c84999964312fde81e6ff0de19b26980c47b007123e500da97de6c5e6af07ff4b8933735e30eee44551bca5c6883ec2a5d8898e45b0fbd7aa16cf604423970aaae813055c8ebeb9186e7099810e4979a3ec871090342ca9dc08214daf2527f20f27a2f87cc791b6495a1176c1e1282fb3cd7b4155b1d16865eb3cce71a0c0372135d8d7fde3ebb753c66e892c9ddf55c3a593a6a20a9ed0df64985eadccd70aa91d680f38fa01a0a283b5635167bd7d9bfb60a2ba589d4cfe9e3d4df37b98179d790d75ab5ad5e2838bf72e8cc527c81f70190f7788be48c8481d21131d58f34138a65f61e9c0c1f6b4f23a1c9997a5e34da50c8a8a50acafd291064045e89e3811260c924b850e6bca0d1bc49e71c1ce08cf7b3f667a0eab496b735d6373d15f964e8d1fdd353d70e613b4b1b137afbe4b12015577a69881d4327d8caf3569078a447bc61571e60a5e2118088219608367eb1af7dadfb253082919258f99de80537e21e450f5f3290d8c4ce5f701fc77215be45dcb160fb6f3d2aa1cd09d30c2e0f69ecb8267792c64aaa2e94f13fe41acf1ac658169f32d591c6b61f00db509cc7ece160dfbb88f7c47da44d587ce28a2f33c9aac000cec2fc6ecddc77cae9bcc98a2b6ccc2f91295c59716f73200d4f6215c400101de81371349bccdacd0894cc494cde6f5793e0939a8455eae27b18837472985f5cc7aa8a392b9763851c3eebaaeeb550c27a4bad04c066f334527f5f789e0a1e6798e5a0c9f8fff86a4b3451676158f6b66b2eeb0ab062db9f8d9babfe0455ee91c299e5bdde2f0a5a20f479e66e10ce189428abfab62d5d3df12dbdbb2c6135f7a8137753774c91c3725787a83b1bc97a9f8f243e703acf0f80811fd2364db8b9ef6325a616dbc3f9dfa79d3c859f2c589a057cf7860f19cce91fcf703adf41c74b6e891d94b62e7842868dce5154e43f62b74d21c3d8ac6a97cbc7939c4d49493a979d07782887adb741b32f2780365aac5f5a59771ba9bc49b10d06b57c0b57c5fa3c9d5f1d70879520e6a9349371426a393ff27446a62c1e24117dae7f00c250e3f4764721680fef63ae3a858d85faaf9177d3cbc0a78c643c255494d108bbd812f38a91dc47d2b92792f5e92e151ac7608aa91b3be5c1a9363a066f7150329d251b175a6f917652ee9867874f3c3d8c635665aa0ff88d525fd3854b00ec762d4db7853e38d78adc1eba3b24ad44941b40edc05895add19e0fbb0d8594d1d92e908c6dedf8b96d39e0e53ab77865f506ccde7b044bb65f6b39051ddb986ef6c6c8e02b467e93206cceed054aa795cccbf998935c7887bfb37b486b0a38c75f0ac8d129d251072d5f914660306f467852ec124edf56d0bd01d78e7aad72fb75bcdfb50e0aa2f002de02d55328a59f0e23e9f236fc9266bce99296d18ea44574601c47708a961d65fb08fffa63ea06e44496085e2194dc18876d83d2050ddcd18c642e487ffef2800786df09db1e02aaf031eee879cae1b50668347df6642c7c2265d5c0dcb727ed43f90719aa233cf3e7eb29d6053a43b0e0212d1bd2ed77ad1b072ad221306215a6d29baef904d537ef89a4149382a15f21bd9772e7899cb45223ca516284cc3cfd61802ebfc60fe1e44087a6b2422a4874c69d884809cb7316ef2558f90a10cd7483f3d85bb1e6ac0fae70b34ca2ce1b84e986c3d8dd65b7a09dc60ae82e32bf133ac901f7cfcc04d35814a272ce87a6424144ed2aced46e658e3232a170b37200021a60b60ca74cb48407f82d078b5c9d31720f1a2db06c1cfdd658da8a684d3a9684d70c3fe4d521ee3fec8d951c32033366e7d10e395c8c59b14ab2d1b5166ae91c30ddd2912262afaf974c11cbef3b575b096e6316127a98814147c766af3a3fa8bc4bc44399c467435b608069d43a0018d27b5423702470ff477a5fcf8eabcd1df4dfe63c69cf245152cb0899a712c0115713fe61e6276d383f54045b750fb0ed3916da8edd922c04acb5a0e40a027eb59ecddb2f09f89ce6b1d882effdbc1364dafeb8cda3a8975727a707958c82ae263fd775e3da084d93da58d326e6393e0f2684457c084ae505be4a1ab3d408e047e3071d4cd53eab3a210cdaec194a449f169290cb57a8f25ee9e177c4dfaba277bc67da0a8d0170e6cc4128206d3c17423bfd19fe3dc6fdddc385708da2fa18adc7b3a1e4c006fde951a1ccbedf7e1c0ef2fe39bdd208e06f08700d0d3504f5096c186bee4d7c2ed5e6db59577ed3e1f7773cfaf77fbeb3b9c88b2d0c23088d2abe022ea03f902cf684259872ee77c

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
        val mdocMethods = listOf(
            MdocConnectionMethod.Ble.GattCentral(uuid = sharedUuid),
            MdocConnectionMethod.Ble.GattPeripheral(uuid = sharedUuid, peripheralAddress = mdocAddress),
        )
        val transcript = runSingleRequestScenario(
            staticHandoverMethods = mdocMethods,
            staticMdocMethodSelector = { methods ->
                methods.filterIsInstance<MdocConnectionMethod.Ble.GattCentral>().first()
            },
            readerSelectConnectionMethod = { methods ->
                assertEquals(2, methods.size)
                methods.filterIsInstance<MdocConnectionMethod.Ble.GattCentral>().first()
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
            #     Carrier Configuration: ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6, ble:gatt_peripheral:uuid=00000001-a123-48ce-896b-4c76973373e6:addr=28-28-37-8b-12-28/random
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
        val mdocMethods = listOf(
            MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = peripheralUuid, psm = 129),
            MdocConnectionMethod.Ble.GattPeripheral(uuid = peripheralUuid),
        )
        val transcript = runSingleRequestScenario(
            staticHandoverMethods = mdocMethods,
            staticMdocMethodSelector = { methods ->
                methods.filterIsInstance<MdocConnectionMethod.Ble.DirectL2capPeripheral>().first()
            },
            readerSelectConnectionMethod = { methods ->
                assertEquals(2, methods.size)
                val first = assertIs<MdocConnectionMethod.Ble.DirectL2capPeripheral>(methods.first())
                assertEquals(129, first.psm)
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
            #     Carrier Configuration: ble:direct_l2cap_peripheral:uuid=00000002-a123-48ce-896b-4c76973373e6:psm=129, ble:gatt_peripheral:uuid=00000002-a123-48ce-896b-4c76973373e6
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
        val readerOffer = MdocConnectionMethod.Ble.GattPeripheral(uuid = null)
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                val chosen = assertIs<MdocConnectionMethod.Ble.GattPeripheral>(methods.single())
                assertNull(chosen.uuid)
                chosen.copy(uuid = peripheralUuid)
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=62).
            # NDEF Message (2 record(s), 62 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 17 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "cr" (Hex: 6372)
            #           Payload Size: 2 bytes
            #           Collision Resolution:
            #             Random Number: 14831 (0x39ef)
            #         Record #2:
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
            #     Carrier Configuration: ble:gatt_peripheral:uuid=null
            #     Payload Hex: 02 1c 01
            C-APDU: 00d6000040003e910211487215910202637239ef5102046163010130005a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c01
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
            #     Carrier Configuration: ble:gatt_peripheral:uuid=00000002-a123-48ce-896b-4c76973373e6
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
            #   "data": h'07a6ba0e9279319172a0d1d90e97ab373a923ec28d4a6356f1e0af869aedd98308e35c2a3b8c477ac61021b1b800428e07cafda2d2bc69af76206bdacde2b0c4d4ae73484e501e19eaa0baa0dd733c9c0a50d14cc5b0924edaf2141ca49a7c61c5a4d2f3012f502e30f74859893fb8e5f1fd3ef11d734c75c1052401c90154ee5b121e5839d822'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa6464617461588707a6ba0e9279319172a0d1d90e97ab373a923ec28d4a6356f1e0af869aedd98308e35c2a3b8c477ac61021b1b800428e07cafda2d2bc69af76206bdacde2b0c4d4ae73484e501e19eaa0baa0dd733c9c0a50d14cc5b0924edaf2141ca49a7c61c5a4d2f3012f502e30f74859893fb8e5f1fd3ef11d734c75c1052401c90154ee5b121e5839d822

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'4fefee82c4bb227c0204770ba5a5e56baacee0211c28b9cfdb3f1d8af377fc6943290555fab4a654a26410d49919c68b46608858fc86583a27c320b8dfda68891f39acba1cda1026a450814ba94b5d64e403a616fc33e9d43981a4c1de31380994d5417b2aa17e65b79aefe61f7a1092c6612d5632153e06961c57ce0cbaae7d71fc654b28d362ca13bc87174b60b3fed0cb5327fca7c5215107ecca14a1c922ba7f61b3f9dffc72f09efe9c639123a9f67c52fd43fa1fbca67eab7c3a939f94169720d76ceb133ba64fea7798000c8b34b024c66e28b483f9e52b7f95f7f0a2a45c7c2017fb456195fae5010b3b67daaba1fe66ca010ac2f0162b2b2baa1cde557be44bda1e17f45a81e5334021b0580a63c7aca63fe147f939c3a7c24fad128e7d45b786cbdd21ba11748d1ae386be8f4fd0c80a0fc139bbffe6a2a9a674efb086a2195fabef3100de3cd6e1012bca67dd988aff91dbe229238bcbcbdc2870e4ea980557998b0f2233d1d5d4d98a2816b0409a23a0b45084b330806a5ee80b9aa6ab712b84bcdecb1b6f277ecf6ec112d5b9ffddbfda693a5ec5baa782cb37f288fbc61ae9a799333909cb2223575dbe402e7ca75861d9d2413447a3ecf53b60eb3ccdb7353340d42c8db84238ecb3702c44840cdcf529508e64febffcf368f332c3cbcaf545772303987930bbf703f134b0c31400b193eb141d57bbc3afc782f1507d9e4b0bf2437c39d24421ee3cc49747f9f3faf8c87bf90c86a23f49ad1c7a9e7f648af0f7e2be610ec9cf87bbbc43f3bdd7562f2d5ebcc32cb273fddadaf78337eb890da4c6da372099a3e877cc476f7e0ffc567b403b5c72cbc281405fef567d6e36112295b3c6896e0258b319dfbc7f33e9d16cfb3b80c1b6032a0590db3bf4726922621d321cdaf69abbd7af2c8039b90ebba9a47f5b122ed563e4c87d8bcf974fe29608ee148dfef32fcd4011d0f9bd2fb51f5360ef28037ea1343e7d7ab9dc98c8d248c86bd3268875c11961b0891dc27d448404286eaa7676ab350830c66c57557422c788ce9584c4079b8da0375a4071d1f8797be588dee73bb01cc67b2020d37f76fa8827be4d4f635661e50360904ba53b215e773837b3f7a6a90efcfb125193e775e6c64bb4d1b3b8ea1d333333dd327eefa5c5e394f62134ab76bcf64ac0eb89cd23bd3572b26e3226c77b86450ec8790e9a855329f03381420e35687630fe2c39dc540976f17098833dae4ab235ee9aa926436813150ab599f29b24f941f689bacf9eaf1f8d1a6b227d9120dab7f29edbe7e7aaa48ce362bc72d0f21021df278114ce5b89c20afc143a71212f137984f72ef35bc8c4225ca3f09e3b7804841d8fad95bee58f88e2dd8f9fa4aeb5cea878b1127e6646c442e269839e8610243bf7687f59444ab9eb730cbcb17de21fd21f101233b2692748a28ae3889e12042fd47447b6ab2350d9b4a2cf3adf68c7a61f572fdfbb12d4a325d2993eb517291c942e2baed54db2dfd79ea5e31830711befcf0b82d1bd437aacdc45cc6eb8396eecff830a376c16612ef814843c0f8e0807e3f73a1fe84e6bf695b5df17278e30f3046b457836f3e1e6dbc782dfd754138a41f5b82f08ba633c6f49f9a6609dfc0a91c4f442d2db24dfbcd62a12478943867bc12ff91096cc357360d97795ad8eadb9d0f47a5cf0d55a398857442148afb24eb47d42dc87e04961571e2d54d9fccf59a16db140c39829b28471812025b800725c2d8240a14d64b754148c10fef368c1caa7abc0f1d0b30cbad804a437bd5067161c98b47b57478f5053e99a5259489119294b6883f0a1c966848336cbe92a310b53a1babd33f01b0c28dc9cedc4c851cebdac80c9a639f4490f1d533fcd49e022d51aa965f20537ec86713dae684abc11503db62ed253aeb2eba8127d9c693131d992697085925e292c0bec0ed56a188ee85266a9be0d11ddee0d3c53b9784708e150d2c8560f61ddf198c446d2a647843de32b9fe215aff87a76507d68fb08ad3bdb97023af0b965f704bd3353669a99c90d23abe59f16fef134450049adaba0b078e2c19e732a30f4a8b441a3a92998547fcf85612d7c2784f9054890ff1194be3470003cee8eb82c830cb4ad74480f0188fd4557cf542e75b34516dcb79b9cd00fbf82b622c6dc3470eaa90c5cd3caac25dc83e75e2e3bbf92c1c1cf4640d09e3cb3993569fb09580c1d41ab7163bca4ca3b0cc5808c54c812b0240e707aa4e6c97faf973e45eceaca193a3f76604861da7eda5895c909ef4e6edef6e6f957d974a34781a247a6b4a7414035ce4ab7946111562d5d4b00a99f9b43858eccdb95b64377799bdd49d404f8e59ee503a633fab8093c5d9ea0f87978d42ef0a376f663a5f16da4fe138d5b42866c57ef5363d5bdc4de5bbfedd07b1fa47725d531173466a7f5b3be2ce53b0c2dd49100db8a783235860658c0f1da21318e83c83f8ca3091471d7e51cac7b1d41b259a442495c5c16dd193ef0721fdd4e87297cdb4d0d27d084ce087dcb3684ebdfcabcb63743c3a9c5d91415bb782a7fab00a577ea25c2d7a463a3959b9862c93cb5e68d907c43'
            # }
            BLE Mdoc->Reader: a1646461746159072f4fefee82c4bb227c0204770ba5a5e56baacee0211c28b9cfdb3f1d8af377fc6943290555fab4a654a26410d49919c68b46608858fc86583a27c320b8dfda68891f39acba1cda1026a450814ba94b5d64e403a616fc33e9d43981a4c1de31380994d5417b2aa17e65b79aefe61f7a1092c6612d5632153e06961c57ce0cbaae7d71fc654b28d362ca13bc87174b60b3fed0cb5327fca7c5215107ecca14a1c922ba7f61b3f9dffc72f09efe9c639123a9f67c52fd43fa1fbca67eab7c3a939f94169720d76ceb133ba64fea7798000c8b34b024c66e28b483f9e52b7f95f7f0a2a45c7c2017fb456195fae5010b3b67daaba1fe66ca010ac2f0162b2b2baa1cde557be44bda1e17f45a81e5334021b0580a63c7aca63fe147f939c3a7c24fad128e7d45b786cbdd21ba11748d1ae386be8f4fd0c80a0fc139bbffe6a2a9a674efb086a2195fabef3100de3cd6e1012bca67dd988aff91dbe229238bcbcbdc2870e4ea980557998b0f2233d1d5d4d98a2816b0409a23a0b45084b330806a5ee80b9aa6ab712b84bcdecb1b6f277ecf6ec112d5b9ffddbfda693a5ec5baa782cb37f288fbc61ae9a799333909cb2223575dbe402e7ca75861d9d2413447a3ecf53b60eb3ccdb7353340d42c8db84238ecb3702c44840cdcf529508e64febffcf368f332c3cbcaf545772303987930bbf703f134b0c31400b193eb141d57bbc3afc782f1507d9e4b0bf2437c39d24421ee3cc49747f9f3faf8c87bf90c86a23f49ad1c7a9e7f648af0f7e2be610ec9cf87bbbc43f3bdd7562f2d5ebcc32cb273fddadaf78337eb890da4c6da372099a3e877cc476f7e0ffc567b403b5c72cbc281405fef567d6e36112295b3c6896e0258b319dfbc7f33e9d16cfb3b80c1b6032a0590db3bf4726922621d321cdaf69abbd7af2c8039b90ebba9a47f5b122ed563e4c87d8bcf974fe29608ee148dfef32fcd4011d0f9bd2fb51f5360ef28037ea1343e7d7ab9dc98c8d248c86bd3268875c11961b0891dc27d448404286eaa7676ab350830c66c57557422c788ce9584c4079b8da0375a4071d1f8797be588dee73bb01cc67b2020d37f76fa8827be4d4f635661e50360904ba53b215e773837b3f7a6a90efcfb125193e775e6c64bb4d1b3b8ea1d333333dd327eefa5c5e394f62134ab76bcf64ac0eb89cd23bd3572b26e3226c77b86450ec8790e9a855329f03381420e35687630fe2c39dc540976f17098833dae4ab235ee9aa926436813150ab599f29b24f941f689bacf9eaf1f8d1a6b227d9120dab7f29edbe7e7aaa48ce362bc72d0f21021df278114ce5b89c20afc143a71212f137984f72ef35bc8c4225ca3f09e3b7804841d8fad95bee58f88e2dd8f9fa4aeb5cea878b1127e6646c442e269839e8610243bf7687f59444ab9eb730cbcb17de21fd21f101233b2692748a28ae3889e12042fd47447b6ab2350d9b4a2cf3adf68c7a61f572fdfbb12d4a325d2993eb517291c942e2baed54db2dfd79ea5e31830711befcf0b82d1bd437aacdc45cc6eb8396eecff830a376c16612ef814843c0f8e0807e3f73a1fe84e6bf695b5df17278e30f3046b457836f3e1e6dbc782dfd754138a41f5b82f08ba633c6f49f9a6609dfc0a91c4f442d2db24dfbcd62a12478943867bc12ff91096cc357360d97795ad8eadb9d0f47a5cf0d55a398857442148afb24eb47d42dc87e04961571e2d54d9fccf59a16db140c39829b28471812025b800725c2d8240a14d64b754148c10fef368c1caa7abc0f1d0b30cbad804a437bd5067161c98b47b57478f5053e99a5259489119294b6883f0a1c966848336cbe92a310b53a1babd33f01b0c28dc9cedc4c851cebdac80c9a639f4490f1d533fcd49e022d51aa965f20537ec86713dae684abc11503db62ed253aeb2eba8127d9c693131d992697085925e292c0bec0ed56a188ee85266a9be0d11ddee0d3c53b9784708e150d2c8560f61ddf198c446d2a647843de32b9fe215aff87a76507d68fb08ad3bdb97023af0b965f704bd3353669a99c90d23abe59f16fef134450049adaba0b078e2c19e732a30f4a8b441a3a92998547fcf85612d7c2784f9054890ff1194be3470003cee8eb82c830cb4ad74480f0188fd4557cf542e75b34516dcb79b9cd00fbf82b622c6dc3470eaa90c5cd3caac25dc83e75e2e3bbf92c1c1cf4640d09e3cb3993569fb09580c1d41ab7163bca4ca3b0cc5808c54c812b0240e707aa4e6c97faf973e45eceaca193a3f76604861da7eda5895c909ef4e6edef6e6f957d974a34781a247a6b4a7414035ce4ab7946111562d5d4b00a99f9b43858eccdb95b64377799bdd49d404f8e59ee503a633fab8093c5d9ea0f87978d42ef0a376f663a5f16da4fe138d5b42866c57ef5363d5bdc4de5bbfedd07b1fa47725d531173466a7f5b3be2ce53b0c2dd49100db8a783235860658c0f1da21318e83c83f8ca3091471d7e51cac7b1d41b259a442495c5c16dd193ef0721fdd4e87297cdb4d0d27d084ce087dcb3684ebdfcabcb63743c3a9c5d91415bb782a7fab00a577ea25c2d7a463a3959b9862c93cb5e68d907c43

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
        val readerOffers = listOf(
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128),
            MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = null),
            MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid),
            MdocConnectionMethod.Ble.GattPeripheral(uuid = null),
        )
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                assertEquals(4, methods.size)
                val first = assertIs<MdocConnectionMethod.Ble.DirectL2capCentral>(methods.first())
                assertEquals(128, first.psm)
                first
            },
            readerNegotiatedMethods = readerOffers,
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=129).
            # NDEF Message (3 record(s), 129 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 23 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "cr" (Hex: 6372)
            #           Payload Size: 2 bytes
            #           Collision Resolution:
            #             Random Number: 14831 (0x39ef)
            #         Record #2:
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
            #     Carrier Configuration: ble:direct_l2cap_central:uuid=00000001-a123-48ce-896b-4c76973373e6:psm=128, ble:direct_l2cap_peripheral:uuid=null, ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6, ble:gatt_peripheral:uuid=null
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
            C-APDU: 00d60000830081910217487215910202637239ef51020a616301013001056368626c651a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a1010000005c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a300188001f502f5
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
            #     Carrier Configuration: ble:direct_l2cap_central:uuid=null
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
            #   "data": h'b8cdf82ad870da2a31fc7e42ad1278b85031d54ba4767aac70977016db11ba06be7cf17053c6642d342b9826809eaad73156bbee6f7f0a4a4560126cbc70c2580f3a44f55751a81d97ad1a274e02a7c20622283ab229f7387ecfd88e6b59e44975499450c0df3800fffe541b1606752ca166c46ed741cb972c7a2f366864986ddad861f2a74ba5'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa64646174615887b8cdf82ad870da2a31fc7e42ad1278b85031d54ba4767aac70977016db11ba06be7cf17053c6642d342b9826809eaad73156bbee6f7f0a4a4560126cbc70c2580f3a44f55751a81d97ad1a274e02a7c20622283ab229f7387ecfd88e6b59e44975499450c0df3800fffe541b1606752ca166c46ed741cb972c7a2f366864986ddad861f2a74ba5

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'c4576ddfbc762a808f2de69c65de8f085c73221c0defd4666c722f985fb2dc2b1fdafa4af5e09e27e696f4ff3e03892a3e7737beb8f5ba49745fdab877939ba5c366f14cadadb6be780290375546eb0509dea4fe11b3059fa70577869fc4b469042185182f2054e8f4af7bf780e85e0b1836616ed0baa84a48e028d7fcb5cb87dae6d411d6d695348b0e50b752b061b868862618e5191585b2ae307cf15fff705a05402d1f6284c6c05e3705ea3c502673ea0fce36bb33f926b27bc1ce587818b5cfb18931df104b480b413d74d42a694c8a764cff6499a17ed58a23d6f78caf46a0dc1a5c1699ed8c2fdfd92f0212d04dc5400f26464f8641d6d27d70c1dbf2c2046842d5738e031b7c8bf20fd67ea37d05d9b170c57004355017b6fb249d5b935ede001a05627baf6ef57aac4f9c860afeb0f755f94904140ba96bb393ce35ec7cb5b905dc62780d375c78576871b6a9c461b5e53fac4426cd6e26f637888e0b059129ac0c59d9556f28473187915b041a02840fe6d511f2793e5ef878caf4534cd911a013321ff30eda11f845a719e784b8969b3153e6bca349901476de3a385cdb3b70ced5ec555d8a2516e448d405cb8fcc16c9bab4db22cef8a7e1fa39a1f023d15ba634034ac2fd66b5194d4ea5e3658699a6ae91bc25068486af6b92e5e50d0f9350bb83e16cf13839925fc9f27b86925e394992c576f6ae1115cffdb65418f567e4d93c6daf63e81e6e17f1b002bfd35f8872c9a574c59f68b3c49be1bd55610d5967f4756ec6eb92cbbf1d7a3ef42c5ba5f6e4e062f7f6b3617b5bc45a24c25bac12d1683b9a0ce6171591e25812a0f0a8f169ea47720274d6453bf958ebb434a4c2e19d3c153cd7ccf7b68564f5d4922bb567c3876f54c95c707c37a63c5e39a59cb43cc18707079610282dc2b59242f49a085932e835151dae9b0141fbe4b8e13a2278b05f9263382c631bdb6b36774da2a654935fc069f67b5c126946cb8b64c70a7e3f0ff1f90202a9372b18fb6362b47b0f06be07ebfef41a285d6f1201fc9dc62f0ecc9c721fa4f59b7aca397057c25d1a6948009b8fb17bac627bfef2338018b2310498174159abbb8c5179993702d6c2d11c921fcf54662fb51de2bd19bd36ab5437c06703e34d7083bc26e2b4c292ba77f54a942fafe9c76a94d5dfc227c21d22bdc6339c75c17e64b69a59e875dffb84407afbfa9620514d0f199f5d6318f4c69e994d60150376785e78122a6721f73e6c113e1e6e02470ed5c1d52ba6390a86f9db23098bc0aa5c46dbe49283706ab9ea02269f2807ca2bbdd458d69a6888c6083c064a758eb28fdb20194917e104305ac3ae737399a8c77d2b6370cc9cd72e521b3d039e981db4fb37dff943905322620351f929ade266f6f017bcf9c219ac3edbf972507c59f04366e35626062a4a4df3eafed0666ee50d75bd222e3805b8c3a68c6a63cb5ec087d2f5c952833500d3add6b22bb6e5fdf00ea1acd8bb4eca123cd5ece9e3ef523e46d37c35ce45a3cbab5e08afedb0b84b06ce4c4ba6f2efa0011f1637f30ec3645f14bbe510ce95ddead31fa51b4a29a0c51cabdf81397088c87e6f8b17f1025e02d67e348f5246db09f33bf33160b51aad3534620a08cc937066ea0158690645f426d24e2b6ae4917c462dd2a93b0b9b429488f566d7466c22fba5457651ff10c415f32e00faf76233e3027d8828306fdbe9793eeccf0641362704e138c4aea3161110c8cb6be086fe7fe07443489d7691d342230dc41dfae1478c36f09e9ff4b097031644a1084cb12b3970026fd814c0a207304c8d3446ad4b08e93b807820c5342abe4ca147fbef3b1612a2c2638a1d809e471ac5a1ecd1a36b61d71ed540504e9a096750c4d89b52d9da311ea0989109a81ae2fc16ced5efc5a4395acfb389cffe21808befd920983e4b3ace8c7d121ed9705a0da99d0fef3870e1051bbecc8e45069aaa60e5bb9c774e808bcde6979ae98a0dbc57d1dcc9ee336f03e9596031269fdb635622783e8ae63e223cbf5a206c3ca5350882250d1810d3b01f63e1dee57aebd5d74be4356e7923c4e630ad4c87ddc4189d0677a56cee3e47c3beac2c653217d90a1b608e75508aeed95f65aa97543677a89bd120a7746ebd42c3813fa3e6614e230ccd30ced06dd48becf4c16e892941d217c54444e9422b04264bfc825e0ffa44276529008519812c88fae567483b9a77a7f029c12f77cb43c01efc44acdf57cd956807ddffbaf11b2e12af89330362ffbbdd3735cadc69d76ed5b0e7f81df90b425e6525fa1b3b07f191e2c6961b9010dd658754db674e453a4be85accab14406e62342715442820d1a21f3a28f3971c6dd43befef3314faf917e36bc0251d75d3d4d01870e65c0cd5477bccc2aa6a5526938fb34794357cc3798537f5309b1dd68acecf03dc2e239ad3e133745e888874b6762525b8ad24e5afead6e57fd94dbee5ba3e7d5e091e876aafbe50c9838888487ca09bbeb6978cc63031c98aa8abf6289c56014c00c84862d763010a8b21fe08fabf11d5526e5a8e4bf444a77c5f2f656eb057268019cd8e4a1fa76f8aa50a08a5b483'
            # }
            BLE Mdoc->Reader: a1646461746159072fc4576ddfbc762a808f2de69c65de8f085c73221c0defd4666c722f985fb2dc2b1fdafa4af5e09e27e696f4ff3e03892a3e7737beb8f5ba49745fdab877939ba5c366f14cadadb6be780290375546eb0509dea4fe11b3059fa70577869fc4b469042185182f2054e8f4af7bf780e85e0b1836616ed0baa84a48e028d7fcb5cb87dae6d411d6d695348b0e50b752b061b868862618e5191585b2ae307cf15fff705a05402d1f6284c6c05e3705ea3c502673ea0fce36bb33f926b27bc1ce587818b5cfb18931df104b480b413d74d42a694c8a764cff6499a17ed58a23d6f78caf46a0dc1a5c1699ed8c2fdfd92f0212d04dc5400f26464f8641d6d27d70c1dbf2c2046842d5738e031b7c8bf20fd67ea37d05d9b170c57004355017b6fb249d5b935ede001a05627baf6ef57aac4f9c860afeb0f755f94904140ba96bb393ce35ec7cb5b905dc62780d375c78576871b6a9c461b5e53fac4426cd6e26f637888e0b059129ac0c59d9556f28473187915b041a02840fe6d511f2793e5ef878caf4534cd911a013321ff30eda11f845a719e784b8969b3153e6bca349901476de3a385cdb3b70ced5ec555d8a2516e448d405cb8fcc16c9bab4db22cef8a7e1fa39a1f023d15ba634034ac2fd66b5194d4ea5e3658699a6ae91bc25068486af6b92e5e50d0f9350bb83e16cf13839925fc9f27b86925e394992c576f6ae1115cffdb65418f567e4d93c6daf63e81e6e17f1b002bfd35f8872c9a574c59f68b3c49be1bd55610d5967f4756ec6eb92cbbf1d7a3ef42c5ba5f6e4e062f7f6b3617b5bc45a24c25bac12d1683b9a0ce6171591e25812a0f0a8f169ea47720274d6453bf958ebb434a4c2e19d3c153cd7ccf7b68564f5d4922bb567c3876f54c95c707c37a63c5e39a59cb43cc18707079610282dc2b59242f49a085932e835151dae9b0141fbe4b8e13a2278b05f9263382c631bdb6b36774da2a654935fc069f67b5c126946cb8b64c70a7e3f0ff1f90202a9372b18fb6362b47b0f06be07ebfef41a285d6f1201fc9dc62f0ecc9c721fa4f59b7aca397057c25d1a6948009b8fb17bac627bfef2338018b2310498174159abbb8c5179993702d6c2d11c921fcf54662fb51de2bd19bd36ab5437c06703e34d7083bc26e2b4c292ba77f54a942fafe9c76a94d5dfc227c21d22bdc6339c75c17e64b69a59e875dffb84407afbfa9620514d0f199f5d6318f4c69e994d60150376785e78122a6721f73e6c113e1e6e02470ed5c1d52ba6390a86f9db23098bc0aa5c46dbe49283706ab9ea02269f2807ca2bbdd458d69a6888c6083c064a758eb28fdb20194917e104305ac3ae737399a8c77d2b6370cc9cd72e521b3d039e981db4fb37dff943905322620351f929ade266f6f017bcf9c219ac3edbf972507c59f04366e35626062a4a4df3eafed0666ee50d75bd222e3805b8c3a68c6a63cb5ec087d2f5c952833500d3add6b22bb6e5fdf00ea1acd8bb4eca123cd5ece9e3ef523e46d37c35ce45a3cbab5e08afedb0b84b06ce4c4ba6f2efa0011f1637f30ec3645f14bbe510ce95ddead31fa51b4a29a0c51cabdf81397088c87e6f8b17f1025e02d67e348f5246db09f33bf33160b51aad3534620a08cc937066ea0158690645f426d24e2b6ae4917c462dd2a93b0b9b429488f566d7466c22fba5457651ff10c415f32e00faf76233e3027d8828306fdbe9793eeccf0641362704e138c4aea3161110c8cb6be086fe7fe07443489d7691d342230dc41dfae1478c36f09e9ff4b097031644a1084cb12b3970026fd814c0a207304c8d3446ad4b08e93b807820c5342abe4ca147fbef3b1612a2c2638a1d809e471ac5a1ecd1a36b61d71ed540504e9a096750c4d89b52d9da311ea0989109a81ae2fc16ced5efc5a4395acfb389cffe21808befd920983e4b3ace8c7d121ed9705a0da99d0fef3870e1051bbecc8e45069aaa60e5bb9c774e808bcde6979ae98a0dbc57d1dcc9ee336f03e9596031269fdb635622783e8ae63e223cbf5a206c3ca5350882250d1810d3b01f63e1dee57aebd5d74be4356e7923c4e630ad4c87ddc4189d0677a56cee3e47c3beac2c653217d90a1b608e75508aeed95f65aa97543677a89bd120a7746ebd42c3813fa3e6614e230ccd30ced06dd48becf4c16e892941d217c54444e9422b04264bfc825e0ffa44276529008519812c88fae567483b9a77a7f029c12f77cb43c01efc44acdf57cd956807ddffbaf11b2e12af89330362ffbbdd3735cadc69d76ed5b0e7f81df90b425e6525fa1b3b07f191e2c6961b9010dd658754db674e453a4be85accab14406e62342715442820d1a21f3a28f3971c6dd43befef3314faf917e36bc0251d75d3d4d01870e65c0cd5477bccc2aa6a5526938fb34794357cc3798537f5309b1dd68acecf03dc2e239ad3e133745e888874b6762525b8ad24e5afead6e57fd94dbee5ba3e7d5e091e876aafbe50c9838888487ca09bbeb6978cc63031c98aa8abf6289c56014c00c84862d763010a8b21fe08fabf11d5526e5a8e4bf444a77c5f2f656eb057268019cd8e4a1fa76f8aa50a08a5b483

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
        val readerOffers = listOf(
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128),
            MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = null),
            MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid),
            MdocConnectionMethod.Ble.GattPeripheral(uuid = null),
        )
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                assertEquals(4, methods.size)
                val chosen = methods.filterIsInstance<MdocConnectionMethod.Ble.DirectL2capPeripheral>().first()
                assertNull(chosen.uuid)
                assertNull(chosen.psm)
                chosen.copy(
                    uuid = peripheralUuid,
                    psm = 129,
                )
            },
            readerNegotiatedMethods = readerOffers,
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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=129).
            # NDEF Message (3 record(s), 129 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 23 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "cr" (Hex: 6372)
            #           Payload Size: 2 bytes
            #           Collision Resolution:
            #             Random Number: 14831 (0x39ef)
            #         Record #2:
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
            #     Carrier Configuration: ble:direct_l2cap_central:uuid=00000001-a123-48ce-896b-4c76973373e6:psm=128, ble:direct_l2cap_peripheral:uuid=null, ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6, ble:gatt_peripheral:uuid=null
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
            C-APDU: 00d60000830081910217487215910202637239ef51020a616301013001056368626c651a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a1010000005c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a300188001f502f5
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
            #     Carrier Configuration: ble:direct_l2cap_peripheral:uuid=00000002-a123-48ce-896b-4c76973373e6:psm=129
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
            #   "data": h'0d4d2674f0fcd00231c8325cd34f489100efb7dfa7d72ba2ccf4d357229797c6ccc71fbca32630ca63b1ea6231fac92390c8056f365f75c36e842496a15326923c3014a05ba40fb64d45268c908f2ee10b003c91269a4bdd6604e10d9d3f9200f6dec074367669a15da01bcc54019b334817000031db2d060a9ab8fae6ee9c2829d23e60c42fdb'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa646461746158870d4d2674f0fcd00231c8325cd34f489100efb7dfa7d72ba2ccf4d357229797c6ccc71fbca32630ca63b1ea6231fac92390c8056f365f75c36e842496a15326923c3014a05ba40fb64d45268c908f2ee10b003c91269a4bdd6604e10d9d3f9200f6dec074367669a15da01bcc54019b334817000031db2d060a9ab8fae6ee9c2829d23e60c42fdb

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'80c28e15ac5e722202524342cc7f740e43788431a6b77201f007b3d59f0d354df4d6840e5eecf44f4d2c1c494d91027cffbc519864821c8a5bd9c09ecaab4e2f19e40f39590a659ff46114d72ef4baa7bcc0c97c87d93c6ea6dd1c8dc7f4054ebf4bed81c0e24833b18fd2d0a02c4094d93276b7c8b0ed0cab9eb53afd8f822086c96091e2d5272430ec8663ca9224c44f56da9fbff7a01011b9e3289de474e9d91b4ae6c2eff777d80f1036b46577135b701411ed829d700858e8bd4c9f8ea699c05fd7b19e3e8824e8416cbcac2ae7a1bb0c9373f3295bb3653046ddf200d65469971ca3d6a21e83d368979aaeed82f1593fabb005922ad7d309c0b224e29798ada7ff9bdde6722a5f287494fcac0605c04baaad21b85b2abc7a74addc8e33f72efbe6d7d72502a16153d14a6f23243e0e52993e5be5b55f4e449de0fb5171710e0afb97334ba39b1989e4f40615e6234a7c313b8d1b4097eab769b30a8e8302bea3aef0725f16f35042e816bc4cd0137ee9ee007c52769d5e9266caa12e7be2f2337843cfbd52b1e27a5723ba3dd3642ff704632dad46f66ba57edf9369e57f45d6ff86ec253586a08948e79833e2979bc21da6211b7c5e8043bcb9e463261916b6829c01824e8c77ed73a3b7a0b5fd56838f1cfd7b3b7bf37791d015e547dfc6e6b645142b197bd93f698cacefc48adfbca271aa2cc436052220724e7b1131b3024dbf9f1f39ba648dd717c3b45643a9277b65ca6dbffdfb123fe76c86798d4a597edbe946653600b097f3d99a9f3f96f9cbc0c2923170a6e47e651915fea9076ac30e6fb4b4e07d8d35ed92fb3a34f177794d9544ba11c44437c3b8fc828631bc2a61ec78b546c278dcc1219df951dfcd6a0d980b808ddd5dee98d093d12b807730ee9217260c9229387c774a25bd5bc3e663cd8759f73a984bb7ea75fc8a1c451413ec60cd78305b2b7e6e23503fe428ff47b64a8948e4e8155a03d6040f1185892cba81db13dc89a9349728229e97f617228e2760494d51f100c6263459f0e1851e632787fc69368b5a59cbc10c697b6a34d8096b716bfa07c20869fd289ee13a1554552034087ef3ca854582ece951744896a755c168ba4b0445c4b6a4640e52f871adb890001827f4919b56d761932cf4893f585a0d97a36b145e1efab49f303735b4b3720d6d82d15c27af64fd8fdfd9b3abfb61e38ade7f09322f63cca5be71f2aacf0a25c135fd752b26f5c5b95b91c01efe82cf06eb804ea7f7364a3b02b4d9d09692d6c4f8b9359eabf6629ddb6cdf28a7ce92dd406dc7b7ff0527cfe1175accb631dc5c3f5a1a1951d7ee9fa4c9d07cdbee4369b821a39349e9d8fcbc5827d39a702b6bbd56e961f984e2bc00bdc41e10f1744a9892b19137b3fdb526fece065adf0b9b5ac2ad8e4d2dcab6ccd000349a425e90594d6e5814d414d4a916e0d05079e75008b585b84d1cbde1d3d6d2e5023ce2abab2ae8916ecb54deef18cf4a897afff02f137a0bfd9d3cc87ffc02244200e167e196a80d68e448c195e6965ea643e5112c4083da477e47c9301b51735d07f274249b0f351411ec408cfa6a437afca234b25266e3591ac51fd6b6da40d5fc4ba598710d87ae83ae848c722fb2134611e7357080360cb50800c091ee5a4d3ee11a6c41da38a7a1dca70f1ec32292da84570f5b99c131459f36e2d5247ca445a7fab0d3906b251a446896da38a6b4e459df5b03cf75961671c846c18c35be5c50879883a4edba938cb744a991318db13c506a202bce1d226e4fe54e93c3d7dd5097bb39577b42d10a7830a1baeb5bf9747af5a23ac1e97f6b86e393e9366e0d193aff82848f79f125fb3ba3347702660ac7dedc9cb368bc5c2ac4dd5d90d39ec66672ec4d4121ccbae8c1ed6e314054c7c63fcc4ae9241c585af14834ba8f087b8eaf5ee81faf2f89f08544d3be7d0b2c36d360c0ea20ca93ce29199cb6c5f724b5730e91651e14909cd7eedc15454881e5b9527c6c38484c53c27acffc029f59930451507ffd80df7af87c6e2da7fd8bda1c4ecbf69a2d937bacc9a7f394655fff870b200893cd9a385985cbdbbf73837335c28a179517fdeb5d5d574ab2f810d36069f92e5152b029cd77879482f641b43c9228cef40efc5e8fddcc436fa3aeec89c33b5e4dbd4a3e0b7fac15ed6251221526aea7be14c3b06d70c8017d3b7cbb20849906914487f35402ffc92651702ccd1f096d3b90b7289edc826bc1d8f3777cb39e687303e493c1af4d2819d4dc5ce26c8482f5b4b21366c787b7bd6d9915e833dd91c10673b6baefceeca1c3f1d70fc507c245fdebe14cc5e1a6786ecd384aff56aec50d7f955b3f6178b3c8fcb75881985f8f98754a22248140330d8ec2406bca16b534fc07c123c9d9a833dbe22b2071369f98e5d4df50fbb359f67585fdc47886edf5541ddda14f9e24210a09c8b159db53b5571ad0cd905f9fdbf15db74b281c5b08d01174c2d27a503c98f570222977e7418efd413da76dda813d241d94b98edae7d3c4f798c176ce8ca51840bbd3279c669515f93cc117dcf4992af2d3aa08696c1efc5d240ba098ba1cc272fcb1bc'
            # }
            BLE Mdoc->Reader: a1646461746159072f80c28e15ac5e722202524342cc7f740e43788431a6b77201f007b3d59f0d354df4d6840e5eecf44f4d2c1c494d91027cffbc519864821c8a5bd9c09ecaab4e2f19e40f39590a659ff46114d72ef4baa7bcc0c97c87d93c6ea6dd1c8dc7f4054ebf4bed81c0e24833b18fd2d0a02c4094d93276b7c8b0ed0cab9eb53afd8f822086c96091e2d5272430ec8663ca9224c44f56da9fbff7a01011b9e3289de474e9d91b4ae6c2eff777d80f1036b46577135b701411ed829d700858e8bd4c9f8ea699c05fd7b19e3e8824e8416cbcac2ae7a1bb0c9373f3295bb3653046ddf200d65469971ca3d6a21e83d368979aaeed82f1593fabb005922ad7d309c0b224e29798ada7ff9bdde6722a5f287494fcac0605c04baaad21b85b2abc7a74addc8e33f72efbe6d7d72502a16153d14a6f23243e0e52993e5be5b55f4e449de0fb5171710e0afb97334ba39b1989e4f40615e6234a7c313b8d1b4097eab769b30a8e8302bea3aef0725f16f35042e816bc4cd0137ee9ee007c52769d5e9266caa12e7be2f2337843cfbd52b1e27a5723ba3dd3642ff704632dad46f66ba57edf9369e57f45d6ff86ec253586a08948e79833e2979bc21da6211b7c5e8043bcb9e463261916b6829c01824e8c77ed73a3b7a0b5fd56838f1cfd7b3b7bf37791d015e547dfc6e6b645142b197bd93f698cacefc48adfbca271aa2cc436052220724e7b1131b3024dbf9f1f39ba648dd717c3b45643a9277b65ca6dbffdfb123fe76c86798d4a597edbe946653600b097f3d99a9f3f96f9cbc0c2923170a6e47e651915fea9076ac30e6fb4b4e07d8d35ed92fb3a34f177794d9544ba11c44437c3b8fc828631bc2a61ec78b546c278dcc1219df951dfcd6a0d980b808ddd5dee98d093d12b807730ee9217260c9229387c774a25bd5bc3e663cd8759f73a984bb7ea75fc8a1c451413ec60cd78305b2b7e6e23503fe428ff47b64a8948e4e8155a03d6040f1185892cba81db13dc89a9349728229e97f617228e2760494d51f100c6263459f0e1851e632787fc69368b5a59cbc10c697b6a34d8096b716bfa07c20869fd289ee13a1554552034087ef3ca854582ece951744896a755c168ba4b0445c4b6a4640e52f871adb890001827f4919b56d761932cf4893f585a0d97a36b145e1efab49f303735b4b3720d6d82d15c27af64fd8fdfd9b3abfb61e38ade7f09322f63cca5be71f2aacf0a25c135fd752b26f5c5b95b91c01efe82cf06eb804ea7f7364a3b02b4d9d09692d6c4f8b9359eabf6629ddb6cdf28a7ce92dd406dc7b7ff0527cfe1175accb631dc5c3f5a1a1951d7ee9fa4c9d07cdbee4369b821a39349e9d8fcbc5827d39a702b6bbd56e961f984e2bc00bdc41e10f1744a9892b19137b3fdb526fece065adf0b9b5ac2ad8e4d2dcab6ccd000349a425e90594d6e5814d414d4a916e0d05079e75008b585b84d1cbde1d3d6d2e5023ce2abab2ae8916ecb54deef18cf4a897afff02f137a0bfd9d3cc87ffc02244200e167e196a80d68e448c195e6965ea643e5112c4083da477e47c9301b51735d07f274249b0f351411ec408cfa6a437afca234b25266e3591ac51fd6b6da40d5fc4ba598710d87ae83ae848c722fb2134611e7357080360cb50800c091ee5a4d3ee11a6c41da38a7a1dca70f1ec32292da84570f5b99c131459f36e2d5247ca445a7fab0d3906b251a446896da38a6b4e459df5b03cf75961671c846c18c35be5c50879883a4edba938cb744a991318db13c506a202bce1d226e4fe54e93c3d7dd5097bb39577b42d10a7830a1baeb5bf9747af5a23ac1e97f6b86e393e9366e0d193aff82848f79f125fb3ba3347702660ac7dedc9cb368bc5c2ac4dd5d90d39ec66672ec4d4121ccbae8c1ed6e314054c7c63fcc4ae9241c585af14834ba8f087b8eaf5ee81faf2f89f08544d3be7d0b2c36d360c0ea20ca93ce29199cb6c5f724b5730e91651e14909cd7eedc15454881e5b9527c6c38484c53c27acffc029f59930451507ffd80df7af87c6e2da7fd8bda1c4ecbf69a2d937bacc9a7f394655fff870b200893cd9a385985cbdbbf73837335c28a179517fdeb5d5d574ab2f810d36069f92e5152b029cd77879482f641b43c9228cef40efc5e8fddcc436fa3aeec89c33b5e4dbd4a3e0b7fac15ed6251221526aea7be14c3b06d70c8017d3b7cbb20849906914487f35402ffc92651702ccd1f096d3b90b7289edc826bc1d8f3777cb39e687303e493c1af4d2819d4dc5ce26c8482f5b4b21366c787b7bd6d9915e833dd91c10673b6baefceeca1c3f1d70fc507c245fdebe14cc5e1a6786ecd384aff56aec50d7f955b3f6178b3c8fcb75881985f8f98754a22248140330d8ec2406bca16b534fc07c123c9d9a833dbe22b2071369f98e5d4df50fbb359f67585fdc47886edf5541ddda14f9e24210a09c8b159db53b5571ad0cd905f9fdbf15db74b281c5b08d01174c2d27a503c98f570222977e7418efd413da76dda813d241d94b98edae7d3c4f798c176ce8ca51840bbd3279c669515f93cc117dcf4992af2d3aa08696c1efc5d240ba098ba1cc272fcb1bc

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
        val readerOffers = listOf(
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128),
            MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = null),
            MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid),
            MdocConnectionMethod.Ble.GattPeripheral(uuid = null),
        )
        val readerEngagement = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {}
        val transcript = runSingleRequestScenario(
            negotiatedHandoverPicker = { methods ->
                assertEquals(4, methods.size)
                methods.filterIsInstance<MdocConnectionMethod.Ble.GattCentral>().first()
            },
            readerNegotiatedMethods = readerOffers,
            handoverOptions = MdocReaderNfcHandoverOptions(
                engagementType = MdocReaderNfcEngagementType.NFC_CONNECTION_HANDOVER_ONLY,
                readerEngagement = readerEngagement,
                random = Random(42),
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
            C-APDU: 00d60002ff910222487215910202637239ef5102156163010130020a6d646f63726561646572056368626c651c1e580a69736f2e6f72673a31383031333a726561646572656e676167656d656e746d646f63726561646572a20063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa1a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107e6733397764c6b89ce4823a1010000005c1a080569736f2e6f72673a31383031333a6368626c656f7074
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader updates binary data at offset 257 (17 bytes).
            C-APDU: 00d6010111696f6e736368626c65a300188001f502f5
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader finalizes NLEN=272 completing the NDEF Handover Request message.
            # NDEF Message (4 record(s), 272 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 34 bytes
            #     Handover Request:
            #       Version: 21
            #       Embedded NDEF Message:
            #         Record #1:
            #           TNF: WELL_KNOWN
            #           Type: "cr" (Hex: 6372)
            #           Payload Size: 2 bytes
            #           Collision Resolution:
            #             Random Number: 14831 (0x39ef)
            #         Record #2:
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
            #     Carrier Configuration: ble:direct_l2cap_central:uuid=00000001-a123-48ce-896b-4c76973373e6:psm=128, ble:direct_l2cap_peripheral:uuid=null, ble:gatt_central:uuid=00000001-a123-48ce-896b-4c76973373e6, ble:gatt_peripheral:uuid=null
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
            C-APDU: 00d60000020110
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
            #     Carrier Configuration: ble:gatt_central:uuid=null
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
            #   "data": h'53cc6f94cf00962e6a8e1de4a5be3628e2301903b0937c4cfc430682afd18958d8530800dd8ec1d4dc3747c8312e1bd85bc48b9def395b93fddad64c4c9fe680bb7c326f468675a79b2b4223bd473a594a5fc3e8a05f3f192bf3aecef16e08f0b464fe33debacff51d6b9d95de4fe2fd5e00521d405c095479af57e87f12b44a00fb1553684d24'
            # }
            BLE Reader->Mdoc: a26a655265616465724b6579d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa6464617461588753cc6f94cf00962e6a8e1de4a5be3628e2301903b0937c4cfc430682afd18958d8530800dd8ec1d4dc3747c8312e1bd85bc48b9def395b93fddad64c4c9fe680bb7c326f468675a79b2b4223bd473a594a5fc3e8a05f3f192bf3aecef16e08f0b464fe33debacff51d6b9d95de4fe2fd5e00521d405c095479af57e87f12b44a00fb1553684d24

            # The mdoc sends SessionData with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'55542f8148ff0f79a7bf9024b4706ee364c7fe4714779ed94e6bde7ba6fe0eb783aaa31d59e3e84db4ad2f804f9637df1ce3dc773af5049002f10803c3e3a7e068f51c303a47868a368a5aa53908cdccf172b8ba71cff51becfed2f9e81336d76b994541f16c42938c831a03bda72e06341584075503d024c4ff4574f5e70651f9737419b9a47d0d1de36d488049a8c05b7460d5ec91ddcd794aaf50e80dbe9b5882f0c379023e36cf7c25d2834ccae7694596b3feee83d8c17c908f0201d4ae45a851552f076c3db555e150e32c5c00b08be1636f1e79cd24e7895133c48d71f7eb5696fb0856b08c95fd0872fc22c2b2194f723349b397df5369e38d0f13f812217ec690114fba622c7b374a9b89ad92270955f49c4251f8d9921dbbd8eca25a2ba4af5debde572db5d0bc9aac25db81f74f7c330a9157d1e868284a90cb5f59900e75743f12b8ea6678f3c39b42cbcb63e98fdde9f3d4d7951d5ec58ae281c21f204f4c677e743e630a1be1d58a9429ec38590ed6112e00ed78175de08756ee8be9e6ab3d72d22f0d0b128bf6543f26f1a9a47af768953677f6641678eee24860161511e07211c0e644782abb42c5279b324b665bc9dc50b35ba73085f6435fd2586998664283588533c5f36e299c48de762bc703df3f33a0ca58dbbcd1126e6c29d034432e411c288a80b674896c0bbe6d6bc55763e6c9de42e7099c1cf166b2b261de1c15db6b18d075dd33aa57a357929c251caf1eafe26284bfeb9e818ae54f0398239e8b1e2cb0e399d5354b3dfd5d820088b12871202ae14e34d65d2b6f09ebcb00265d1b610011c5a3bc0b962aa6f0abf499fc93afe956209b8f694330e374bfb3ede34c041761eea001d561e74d780c994d552bbf3a47e4521880574f0f2eb43463de17011b4c06602dc082408189a1a363780a2efbf5cdc61e63752d103efd4bded4d838350feadc290ca37208a0e01bf386bba71086cc8d178e21a2fca843417129e9fa837aeac5414269d2ca7b366cc9968b289c98c535cf2492a079737b68e832f51430e4a023bef482b5ef6c4ce598a728a084ed818f90aabf524c0075c0cc0adb56000cce36eb1016105818c022b34a9d1f7c186be53bc1ddeae91a53150011c2dc6c6577e8324516b4390dd3a749183804e162bde122a7517d2b05618f33ed6570994a2420584b19a0bc1d47f8828c4414e3c97be07556e23b08de258e4451dce08ba9c92b6166359f908ae3b529671c31d2dad3120d578625f963988848aae7ce128dfb070e205583ab6a24ae4dcca18017cc70fbf639f469c13c89862fa19e420939a6c944f67cf2b5ce2d8216ccfcaa09b823ba0df04de64e5913bfced0df2172bac1f95ce0acdcafd8d43f5072a4eddf24e5dc9ed30e17938e011bd1e8c42dc3232380f8c527ce83244251f4fb115e9ac236190738fd5eb73206d0b391835ffb66ba534947f8ccf1b310e4368187de5c18eb07555c61d743c066d9447356e1ef603ea03e5d88c1f3fbc4daef002ac15643084ea40dd18c8b48daea77bdacb3e76bf75d0655f0e62976dbf2f4bf084ecea47f3f6cc2e5c7b37c80ca65bd62bb87bdca2e9c30ed50d36b0448c6be0787910b23778d564cad62ff68a5322691d0d30690ba392f1126efe0cc16037017169ee0d51f640fbfe03edea6a665f4abd6dedb174abf717507720a1760b3371a9b10ee5ced42bd7b4e450204b67bacf0b63ede4572aa60dcfea0a32eb678d27de36f377bc40bc230634c07c9711322aa3f58b3bd36bce57566da4a7f801338ccf03a063ebce8b4bdd9360f4ea1778f1b577dba8c7743a5cb88e1ff4adecaf57e178de237af4ddeb7626e13f704a60c3ec5ec06bcc525bcab11a7720d025509451f5abe750b753084ac3b4de010ddd463cb0047c5090519ccdab86656eae1564dba6e274c1d3db3d762762809169c3c885b87f7429aaf7ac2ac546a879c3613c3bc5c84f74b75b4484b2f8dfe92462b126beafb360d7a5d0cf311f26064023677dccf3364367256374ac210146abd876c8ec9a538461f10401b4223dc7ef1bf51dc1a7e83f4f785e4c8359bf4c1bd138e7d0127dca8bc73e08643dc0ac416e07ea63eb630b8a33e56a4beeaa2d360d95c91655ffe780578f3362a520a6405ed540e0f0007ae05102b96836b7c65b13893dd6a0bad63c37f04ce90d6687d656b2df6d1e62a549edb690dc0f60b59a7dfcce6b70873d372806160c866f8f9882cc6029b3b12f2e1f2dd78bbc65e92a4c420c03473f39e0964c244f65128af7dee3eca73b80bf925c4dd8774a986da37580add560be1a25a886745461c7d1a1b924836e0264121e76fb20076ecdd1b253d13718092e9cd01115195ff989f061cb0eae6086e01d5a2414caee9f35d2cbe05318f1c3045159ae91acb32b6266872f7bcfc9d47cd42f1d491287b6fc2ad4d5273d83ddff3af0fd2a996f4f08760774a4df3684b70b177cb4bc3d31b54a68805bbf8ef6871db8aaa0832a9d68e9b6cf26c36695979f3ad9e4b70c670f79b447bae3622b43511121ee287d4770402315ab2316eb392fad492927574febc9dc5d0d5d78133d94707'
            # }
            BLE Mdoc->Reader: a1646461746159072f55542f8148ff0f79a7bf9024b4706ee364c7fe4714779ed94e6bde7ba6fe0eb783aaa31d59e3e84db4ad2f804f9637df1ce3dc773af5049002f10803c3e3a7e068f51c303a47868a368a5aa53908cdccf172b8ba71cff51becfed2f9e81336d76b994541f16c42938c831a03bda72e06341584075503d024c4ff4574f5e70651f9737419b9a47d0d1de36d488049a8c05b7460d5ec91ddcd794aaf50e80dbe9b5882f0c379023e36cf7c25d2834ccae7694596b3feee83d8c17c908f0201d4ae45a851552f076c3db555e150e32c5c00b08be1636f1e79cd24e7895133c48d71f7eb5696fb0856b08c95fd0872fc22c2b2194f723349b397df5369e38d0f13f812217ec690114fba622c7b374a9b89ad92270955f49c4251f8d9921dbbd8eca25a2ba4af5debde572db5d0bc9aac25db81f74f7c330a9157d1e868284a90cb5f59900e75743f12b8ea6678f3c39b42cbcb63e98fdde9f3d4d7951d5ec58ae281c21f204f4c677e743e630a1be1d58a9429ec38590ed6112e00ed78175de08756ee8be9e6ab3d72d22f0d0b128bf6543f26f1a9a47af768953677f6641678eee24860161511e07211c0e644782abb42c5279b324b665bc9dc50b35ba73085f6435fd2586998664283588533c5f36e299c48de762bc703df3f33a0ca58dbbcd1126e6c29d034432e411c288a80b674896c0bbe6d6bc55763e6c9de42e7099c1cf166b2b261de1c15db6b18d075dd33aa57a357929c251caf1eafe26284bfeb9e818ae54f0398239e8b1e2cb0e399d5354b3dfd5d820088b12871202ae14e34d65d2b6f09ebcb00265d1b610011c5a3bc0b962aa6f0abf499fc93afe956209b8f694330e374bfb3ede34c041761eea001d561e74d780c994d552bbf3a47e4521880574f0f2eb43463de17011b4c06602dc082408189a1a363780a2efbf5cdc61e63752d103efd4bded4d838350feadc290ca37208a0e01bf386bba71086cc8d178e21a2fca843417129e9fa837aeac5414269d2ca7b366cc9968b289c98c535cf2492a079737b68e832f51430e4a023bef482b5ef6c4ce598a728a084ed818f90aabf524c0075c0cc0adb56000cce36eb1016105818c022b34a9d1f7c186be53bc1ddeae91a53150011c2dc6c6577e8324516b4390dd3a749183804e162bde122a7517d2b05618f33ed6570994a2420584b19a0bc1d47f8828c4414e3c97be07556e23b08de258e4451dce08ba9c92b6166359f908ae3b529671c31d2dad3120d578625f963988848aae7ce128dfb070e205583ab6a24ae4dcca18017cc70fbf639f469c13c89862fa19e420939a6c944f67cf2b5ce2d8216ccfcaa09b823ba0df04de64e5913bfced0df2172bac1f95ce0acdcafd8d43f5072a4eddf24e5dc9ed30e17938e011bd1e8c42dc3232380f8c527ce83244251f4fb115e9ac236190738fd5eb73206d0b391835ffb66ba534947f8ccf1b310e4368187de5c18eb07555c61d743c066d9447356e1ef603ea03e5d88c1f3fbc4daef002ac15643084ea40dd18c8b48daea77bdacb3e76bf75d0655f0e62976dbf2f4bf084ecea47f3f6cc2e5c7b37c80ca65bd62bb87bdca2e9c30ed50d36b0448c6be0787910b23778d564cad62ff68a5322691d0d30690ba392f1126efe0cc16037017169ee0d51f640fbfe03edea6a665f4abd6dedb174abf717507720a1760b3371a9b10ee5ced42bd7b4e450204b67bacf0b63ede4572aa60dcfea0a32eb678d27de36f377bc40bc230634c07c9711322aa3f58b3bd36bce57566da4a7f801338ccf03a063ebce8b4bdd9360f4ea1778f1b577dba8c7743a5cb88e1ff4adecaf57e178de237af4ddeb7626e13f704a60c3ec5ec06bcc525bcab11a7720d025509451f5abe750b753084ac3b4de010ddd463cb0047c5090519ccdab86656eae1564dba6e274c1d3db3d762762809169c3c885b87f7429aaf7ac2ac546a879c3613c3bc5c84f74b75b4484b2f8dfe92462b126beafb360d7a5d0cf311f26064023677dccf3364367256374ac210146abd876c8ec9a538461f10401b4223dc7ef1bf51dc1a7e83f4f785e4c8359bf4c1bd138e7d0127dca8bc73e08643dc0ac416e07ea63eb630b8a33e56a4beeaa2d360d95c91655ffe780578f3362a520a6405ed540e0f0007ae05102b96836b7c65b13893dd6a0bad63c37f04ce90d6687d656b2df6d1e62a549edb690dc0f60b59a7dfcce6b70873d372806160c866f8f9882cc6029b3b12f2e1f2dd78bbc65e92a4c420c03473f39e0964c244f65128af7dee3eca73b80bf925c4dd8774a986da37580add560be1a25a886745461c7d1a1b924836e0264121e76fb20076ecdd1b253d13718092e9cd01115195ff989f061cb0eae6086e01d5a2414caee9f35d2cbe05318f1c3045159ae91acb32b6266872f7bcfc9d47cd42f1d491287b6fc2ad4d5273d83ddff3af0fd2a996f4f08760774a4df3684b70b177cb4bc3d31b54a68805bbf8ef6871db8aaa0832a9d68e9b6cf26c36695979f3ad9e4b70c670f79b447bae3622b43511121ee287d4770402315ab2316eb392fad492927574febc9dc5d0d5d78133d94707

            # The mdoc reader sends SessionData (status=20) to terminate the session over BLE.
            # {
            #   "status": 20
            # }
            BLE Reader->Mdoc: a16673746174757314
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }
}
