package com.android.identity.android.mdoc.engagement

import android.nfc.NdefRecord as AndroidNdefRecord
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransportBle
import com.android.identity.android.mdoc.transport.DataTransportNfc
import com.android.identity.android.mdoc.transport.DataTransportOptions
import com.android.identity.android.util.NfcUtil
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod as LegacyMdocConnectionMethod
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethodBle as LegacyMdocConnectionMethodBle
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethodNfc as LegacyMdocConnectionMethodNfc
import com.android.identity.mdoc.engagement.EngagementParser
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.decodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.mdoc.connectionmethod.BlePeripheralAddress
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod as CoreMdocConnectionMethod
import org.multipaz.mdoc.engagement.buildReaderEngagement
import org.multipaz.mdoc.nfc.MdocHandoverType
import org.multipaz.mdoc.nfc.MdocNfcEngagementHelper
import org.multipaz.mdoc.nfc.MdocReaderNfcEngagementType
import org.multipaz.mdoc.nfc.MdocReaderNfcHandoverOptions
import org.multipaz.mdoc.nfc.mdocReaderNfcHandover
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.AlternativeCarrierRecord
import org.multipaz.nfc.CollisionResolutionRecord
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.HandoverRequestRecord
import org.multipaz.nfc.HandoverSelectRecord
import org.multipaz.nfc.NdefMessage
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ResponseApdu
import org.multipaz.nfc.ServiceParameterRecord
import org.multipaz.nfc.ServiceSelectRecord
import org.multipaz.nfc.TnepStatusRecord
import org.multipaz.util.UUID
import org.multipaz.util.appendUInt16
import org.multipaz.util.fromHex
import org.multipaz.util.getUInt16
import org.multipaz.util.toHex
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * Deterministic protocol interoperability and golden-transcript unit tests for legacy
 * [NfcEngagementHelper] (and [NfcUtil] / [DataTransportNfc] / [DataTransportBle]) in
 * `multipaz-android-legacy` against the core SDK's [mdocReaderNfcHandover] and
 * [MdocNfcEngagementHelper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NfcEngagementHelperTest {

    private val directExecutor = Executor { runnable -> runnable.run() }

    private fun getEDeviceKeyPub(): EcPublicKey = EcPublicKeyDoubleCoordinate(
        curve = EcCurve.P256,
        x = "7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d".fromHex(),
        y = "f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2".fromHex()
    )

    private fun getEReaderKeyPub(): EcPublicKey = EcPublicKeyDoubleCoordinate(
        curve = EcCurve.P256,
        x = "a3f591c48b2d6e0f147c8a9e3b5d7f2018293a4b5c6d7e8f90a1b2c3d4e5f607".fromHex(),
        y = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef".fromHex()
    )

    private fun getStaticBleUuid(): UUID =
        UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")

    private fun getMdocGeneratedBleUuid(): UUID =
        UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")

    private fun getCoreConnectionMethods(): List<CoreMdocConnectionMethod> {
        val bleUuid = getStaticBleUuid()
        return listOf(
            CoreMdocConnectionMethod.Ble.GattCentral(uuid = bleUuid),
            CoreMdocConnectionMethod.Ble.GattPeripheral(uuid = bleUuid),
            CoreMdocConnectionMethod.Nfc(
                commandDataFieldMaxLength = 0xffff,
                responseDataFieldMaxLength = 0x10000
            )
        )
    }

    private fun getLegacyConnectionMethods(): List<LegacyMdocConnectionMethod> {
        val bleUuid = getStaticBleUuid()
        return listOf(
            LegacyMdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = bleUuid,
                centralClientModeUuid = bleUuid
            ),
            LegacyMdocConnectionMethodNfc(
                commandDataFieldMaxLength = 0xffff,
                responseDataFieldMaxLength = 0x10000
            )
        )
    }

    private class TranscriptLogger {
        private val sb = StringBuilder()
        private var lastEntryWasCommandApdu = false

        private fun appendMultilineComment(text: String) {
            for (line in text.lines()) {
                if (line.isBlank()) {
                    sb.appendLine("#")
                } else {
                    sb.appendLine("# $line")
                }
            }
        }

        fun appendCommandApdu(
            comment: String,
            detailComment: String? = null,
            apduHex: String,
        ) {
            if (sb.isNotEmpty()) {
                sb.appendLine()
            }
            sb.appendLine("# $comment")
            if (detailComment != null) {
                appendMultilineComment(detailComment)
            }
            sb.appendLine("C-APDU: $apduHex")
            lastEntryWasCommandApdu = true
        }

        fun appendResponseApdu(
            comment: String,
            detailComment: String? = null,
            apduHex: String,
        ) {
            if (sb.isNotEmpty() && !lastEntryWasCommandApdu) {
                sb.appendLine()
            }
            sb.appendLine("# $comment")
            if (detailComment != null) {
                appendMultilineComment(detailComment)
            }
            sb.appendLine("R-APDU: $apduHex")
            lastEntryWasCommandApdu = false
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
                val cms = CoreMdocConnectionMethod.decodeNdefHandoverCarrier(
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
     * Loopback [NfcIsoTag] that encodes each [CommandApdu] to raw bytes, passes it to a handler
     * (either legacy [NfcEngagementHelper.nfcProcessCommandApdu] or core SDK
     * [MdocNfcEngagementHelper.processApdu]), decodes the [ResponseApdu], and logs a deterministic
     * human-readable APDU transcript with decoded NDEF messages.
     */
    private class TranscriptRecordingLoopbackTag(
        private val apduHandler: suspend (CommandApdu) -> ResponseApdu,
        val transcript: TranscriptLogger = TranscriptLogger(),
    ) : NfcIsoTag() {
        private var selectedFileId: Int = 0
        private val ndefWriteBuffer = ByteArray(4096)

        override val maxTransceiveLength: Int = 65536

        override suspend fun close() {}

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            var commandDetailComment: String? = null
            val commandComment: String

            if (command.isApplicationSelect) {
                selectedFileId = 0
                commandComment = when (command.payload) {
                    Nfc.NDEF_APPLICATION_ID ->
                        "The mdoc reader selects the NDEF Type 4 Tag application."
                    Nfc.ISO_MDOC_NFC_CCE_APPLICATION_ID ->
                        "The mdoc reader attempts to select the NFC CCE application."
                    else ->
                        "The mdoc reader selects application ${command.payload.toHex()}."
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
                    else -> error("Unexpected INS: ${command.ins}")
                }
            }
            transcript.appendCommandApdu(
                comment = commandComment,
                detailComment = commandDetailComment,
                apduHex = command.encode().toHex(),
            )

            val response = apduHandler(command)

            var responseDetailComment: String? = null
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
                    else -> error("Unexpected INS: ${command.ins}")
                }
            }
            transcript.appendResponseApdu(
                comment = responseComment,
                detailComment = responseDetailComment,
                apduHex = response.encode().toHex(),
            )
            return response
        }
    }

    private fun createLegacyTag(helper: NfcEngagementHelper): TranscriptRecordingLoopbackTag =
        TranscriptRecordingLoopbackTag(
            apduHandler = { command ->
                val rawResponse = helper.nfcProcessCommandApdu(command.encode())
                ResponseApdu.decode(rawResponse)
            }
        )

    private fun createRecordingListener(
        onTwoWay: () -> Unit = {},
        onHandoverSelectSent: () -> Unit = {},
    ): NfcEngagementHelper.Listener = object : NfcEngagementHelper.Listener {
        override fun onTwoWayEngagementDetected() = onTwoWay()
        override fun onHandoverSelectMessageSent() = onHandoverSelectSent()
        override fun onDeviceConnecting() {}
        override fun onDeviceConnected(transport: DataTransport) {}
        override fun onError(error: Throwable) {
            fail("Unexpected onError: $error")
        }
    }

    private suspend fun legacyTransceive(
        tag: TranscriptRecordingLoopbackTag,
        rawApdu: ByteArray
    ): ByteArray {
        val response = tag.transceive(CommandApdu.decode(rawApdu))
        return response.encode()
    }

    private suspend fun legacyNdefTransact(
        tag: TranscriptRecordingLoopbackTag,
        ndefMessage: ByteArray
    ): ByteArray {
        if (ndefMessage.size < 256 - 2) {
            val data = buildByteString {
                appendUInt16(ndefMessage.size)
                append(ndefMessage)
            }.toByteArray()
            val ret = legacyTransceive(tag, NfcUtil.createApduUpdateBinary(0, data))
            assertArrayEquals(NfcUtil.STATUS_WORD_OK, ret)
        } else {
            assertArrayEquals(
                NfcUtil.STATUS_WORD_OK,
                legacyTransceive(tag, NfcUtil.createApduUpdateBinary(0, byteArrayOf(0x00, 0x00)))
            )
            var offset = 0
            var remaining = ndefMessage.size
            while (remaining > 0) {
                val chunkSize = remaining.coerceAtMost(255)
                val chunk = ndefMessage.copyOfRange(offset, offset + chunkSize)
                assertArrayEquals(
                    NfcUtil.STATUS_WORD_OK,
                    legacyTransceive(tag, NfcUtil.createApduUpdateBinary(offset + 2, chunk))
                )
                remaining -= chunkSize
                offset += chunkSize
            }
            val encodedLen = buildByteString { appendUInt16(ndefMessage.size) }.toByteArray()
            assertArrayEquals(
                NfcUtil.STATUS_WORD_OK,
                legacyTransceive(tag, NfcUtil.createApduUpdateBinary(0, encodedLen))
            )
        }

        val lenResp = legacyTransceive(tag, NfcUtil.createApduReadBinary(0, 2))
        assertEquals(4, lenResp.size)
        assertEquals(0x9000, lenResp.getUInt16(2).toInt())
        val replyLen = lenResp.getUInt16(0).toInt()

        val msgResp = legacyTransceive(tag, NfcUtil.createApduReadBinary(2, replyLen))
        assertEquals(replyLen + 2, msgResp.size)
        assertEquals(0x9000, msgResp.getUInt16(replyLen).toInt())
        return msgResp.copyOf(replyLen)
    }

    @Test
    fun testStaticHandoverAgainstCoreReader() = runTest {
        var handoverSelectSentCount = 0
        val context = RuntimeEnvironment.getApplication()
        val helper = NfcEngagementHelper.Builder(
            context,
            getEDeviceKeyPub(),
            DataTransportOptions.Builder().build(),
            createRecordingListener(onHandoverSelectSent = { handoverSelectSentCount++ }),
            directExecutor
        )
            .useStaticHandover(getLegacyConnectionMethods())
            .build()
        helper.testingDoNotStartTransports()

        val tag = createLegacyTag(helper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = getCoreConnectionMethods(),
            options = MdocReaderNfcHandoverOptions(
                engagementType = MdocReaderNfcEngagementType.NFC_CONNECTION_HANDOVER_ONLY,
            ),
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.STATIC_HANDOVER, handoverResult!!.type)
        assertEquals(getCoreConnectionMethods(), handoverResult.connectionMethods)
        assertArrayEquals(helper.deviceEngagement, handoverResult.encodedDeviceEngagement.toByteArray())
        assertArrayEquals(helper.handover, Cbor.encode(handoverResult.handover))
        assertEquals(1, handoverSelectSentCount)

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
            # The mdoc returns NLEN=254 bytes.
            R-APDU: 00fe9000

            # The mdoc reader reads the 254-byte NDEF message.
            C-APDU: 00b00002fe
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (4 record(s), 254 bytes):
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
            #     Payload Size: 88 bytes
            #     DeviceEngagement CBOR:
            #       {
            #         0: "1.0",
            #         1: [
            #           1,
            #           24(<< {
            #             1: 2,
            #             -1: 1,
            #             -2: h'7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d',
            #             -3: h'f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:gatt_central:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6, ble:gatt_peripheral:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6
            #     Payload Hex: 02 1c 03 11 07 b6 6e ef 55 ee 78 2e a2 51 4b b6 a1 c4 2a d5 b3
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            R-APDU: 91021f487315910209616301013001046d646f6351020b616301036e666301046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba21a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c031107b66eef55ee782ea2514bb6a1c42ad5b35c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000
        """.trimIndent().trim()
        assertEquals(expectedTranscript, tag.transcript.toString())
        helper.close()
    }

    @Test
    fun testNegotiatedHandoverBleAndNfcWithCceFallbackAgainstCoreReader() = runTest {
        var twoWayDetectedCount = 0
        var handoverSelectSentCount = 0
        val context = RuntimeEnvironment.getApplication()
        val helper = NfcEngagementHelper.Builder(
            context,
            getEDeviceKeyPub(),
            DataTransportOptions.Builder().build(),
            createRecordingListener(
                onTwoWay = { twoWayDetectedCount++ },
                onHandoverSelectSent = { handoverSelectSentCount++ }
            ),
            directExecutor
        )
            .useNegotiatedHandover()
            .build()
        helper.testingDoNotStartTransports()

        val tag = createLegacyTag(helper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = getCoreConnectionMethods(),
            options = MdocReaderNfcHandoverOptions(
                engagementType = MdocReaderNfcEngagementType.NFC_CCE_AND_NFC_CONNECTION_HANDOVER,
                random = Random(42),
            ),
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.NEGOTIATED_HANDOVER, handoverResult!!.type)
        assertEquals(
            listOf(CoreMdocConnectionMethod.Ble.GattCentral(uuid = getStaticBleUuid())),
            handoverResult.connectionMethods
        )
        assertArrayEquals(helper.deviceEngagement, handoverResult.encodedDeviceEngagement.toByteArray())
        assertArrayEquals(helper.handover, Cbor.encode(handoverResult.handover))
        assertEquals(1, twoWayDetectedCount)
        assertEquals(1, handoverSelectSentCount)

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
            #       wtInt (Waiting Time Code): 16
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200100fffff9000

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
            #     Carrier Configuration: ble:gatt_central:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6, ble:gatt_peripheral:uuid=null
            #     Payload Hex: 02 1c 02 11 07 b6 6e ef 55 ee 78 2e a2 51 4b b6 a1 c4 2a d5 b3
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 1 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01
            C-APDU: 00d6000076007491021c487215910202637239ef110204616301013000510206616301036e6663001a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107b66eef55ee782ea2514bb6a1c42ad5b35c11010369736f2e6f72673a31383031333a6e66636e666301
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
            #             -2: h'7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d',
            #             -3: h'f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:gatt_central:uuid=null
            #     Payload Hex: 02 1c 01
            R-APDU: 91020f487315d10209616301013001046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba25a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c019000
        """.trimIndent().trim()
        assertEquals(expectedTranscript, tag.transcript.toString())
        helper.close()
    }

    @Test
    fun testNegotiatedHandoverNfcOnlyAgainstCoreReader() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val helper = NfcEngagementHelper.Builder(
            context,
            getEDeviceKeyPub(),
            DataTransportOptions.Builder().build(),
            createRecordingListener(),
            directExecutor
        )
            .useNegotiatedHandover()
            .build()
        helper.testingDoNotStartTransports()

        val nfcOnly = listOf(
            CoreMdocConnectionMethod.Nfc(
                commandDataFieldMaxLength = 0xffff,
                responseDataFieldMaxLength = 0x10000
            )
        )
        val tag = createLegacyTag(helper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = nfcOnly,
            options = MdocReaderNfcHandoverOptions(
                engagementType = MdocReaderNfcEngagementType.NFC_CONNECTION_HANDOVER_ONLY,
                random = Random(42),
            ),
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.NEGOTIATED_HANDOVER, handoverResult!!.type)
        assertEquals(nfcOnly, handoverResult.connectionMethods)
        assertArrayEquals(helper.deviceEngagement, handoverResult.encodedDeviceEngagement.toByteArray())
        assertArrayEquals(helper.handover, Cbor.encode(handoverResult.handover))

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
            #       wtInt (Waiting Time Code): 16
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200100fffff9000

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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=49).
            # NDEF Message (2 record(s), 49 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 19 bytes
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
            #           Payload Size: 6 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: []
            #   Record #2:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 1 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01
            C-APDU: 00d60000330031910213487215910202637239ef510206616301036e6663005c11010369736f2e6f72673a31383031333a6e66636e666301
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
            #             -2: h'7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d',
            #             -3: h'f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2'
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
            R-APDU: 910211487315d1020b616301036e666301046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba25c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000
        """.trimIndent().trim()
        assertEquals(expectedTranscript, tag.transcript.toString())
        helper.close()
    }

    @Test
    fun testNegotiatedHandoverBlePeripheralOnlyWithoutReaderUuidAgainstCoreReader() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val helper = NfcEngagementHelper.Builder(
            context,
            getEDeviceKeyPub(),
            DataTransportOptions.Builder().build(),
            createRecordingListener(),
            directExecutor
        )
            .useNegotiatedHandover()
            .build()
        helper.testingDoNotStartTransports()
        helper.testingSetUuidGenerator { getMdocGeneratedBleUuid() }

        val tag = createLegacyTag(helper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = listOf(CoreMdocConnectionMethod.Ble.GattPeripheral()),
            options = MdocReaderNfcHandoverOptions(
                engagementType = MdocReaderNfcEngagementType.NFC_CONNECTION_HANDOVER_ONLY,
                random = Random(42),
            ),
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.NEGOTIATED_HANDOVER, handoverResult!!.type)
        assertEquals(
            listOf(CoreMdocConnectionMethod.Ble.GattPeripheral(uuid = getMdocGeneratedBleUuid())),
            handoverResult.connectionMethods
        )
        assertArrayEquals(helper.deviceEngagement, handoverResult.encodedDeviceEngagement.toByteArray())
        assertArrayEquals(helper.handover, Cbor.encode(handoverResult.handover))

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
            #       wtInt (Waiting Time Code): 16
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200100fffff9000

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
            # The mdoc returns NLEN=204 bytes.
            R-APDU: 00cc9000

            # The mdoc reader reads the 204-byte NDEF message.
            C-APDU: 00b00002cc
            # The mdoc returns the NDEF Handover Select message.
            # NDEF Message (3 record(s), 204 bytes):
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
            #             -2: h'7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d',
            #             -3: h'f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 21 bytes
            #     Carrier Configuration: ble:gatt_peripheral:uuid=00000002-a123-48ce-896b-4c76973373e6
            #     Payload Hex: 02 1c 00 11 07 e6 73 33 97 76 4c 6b 89 ce 48 23 a1 02 00 00 00
            R-APDU: 91020f487315d10209616301013001046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba25a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c001107e6733397764c6b89ce4823a1020000009000
        """.trimIndent().trim()
        assertEquals(expectedTranscript, tag.transcript.toString())
        helper.close()
    }

    @Test
    fun testNegotiatedHandoverSecondEditionExtensionsAndMultiChunkWriteAgainstCoreReader() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val helper = NfcEngagementHelper.Builder(
            context,
            getEDeviceKeyPub(),
            DataTransportOptions.Builder().build(),
            createRecordingListener(),
            directExecutor
        )
            .useNegotiatedHandover()
            .build()
        helper.testingDoNotStartTransports()

        val bleUuid = getStaticBleUuid()
        val peripheralAddress = BlePeripheralAddress.fromEncoded("01020304050601".fromHex())
        val readerOffered = listOf(
            CoreMdocConnectionMethod.Ble.DirectL2capCentral(
                uuid = bleUuid,
                psm = 192,
                peripheralAddress = peripheralAddress,
            ),
            CoreMdocConnectionMethod.Ble.GattCentral(
                uuid = bleUuid,
                peripheralAddress = peripheralAddress,
            ),
            CoreMdocConnectionMethod.Ble.DirectL2capPeripheral(),
            CoreMdocConnectionMethod.Ble.GattPeripheral(),
            CoreMdocConnectionMethod.Nfc(
                commandDataFieldMaxLength = 0xffff,
                responseDataFieldMaxLength = 0x10000,
            ),
        )
        val readerEngagement = buildReaderEngagement(eReaderKey = getEReaderKeyPub()) {}

        val tag = createLegacyTag(helper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = readerOffered,
            options = MdocReaderNfcHandoverOptions(
                engagementType = MdocReaderNfcEngagementType.NFC_CONNECTION_HANDOVER_ONLY,
                readerEngagement = readerEngagement,
                random = Random(42),
            ),
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.NEGOTIATED_HANDOVER, handoverResult!!.type)
        assertEquals(
            listOf(
                CoreMdocConnectionMethod.Ble.GattCentral(
                    uuid = bleUuid,
                    peripheralAddress = peripheralAddress,
                )
            ),
            handoverResult.connectionMethods
        )
        assertArrayEquals(helper.deviceEngagement, handoverResult.encodedDeviceEngagement.toByteArray())
        assertArrayEquals(helper.handover, Cbor.encode(handoverResult.handover))

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
            #       wtInt (Waiting Time Code): 16
            #       nWait: 15
            #       Max NDEF Message Size: 65535 bytes
            R-APDU: d1021a5470101375726e3a6e66633a736e3a68616e646f76657200100fffff9000

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
            C-APDU: 00d60002ff910238487215910202637239ef1102156163010130020a6d646f63726561646572056368626c65510211616301036e6663010a6d646f637265616465721c1e580a69736f2e6f72673a31383031333a726561646572656e676167656d656e746d646f63726561646572a20063312e31018201d818584ba401022001215820a3f591c48b2d6e0f147c8a9e3b5d7f2018293a4b5c6d7e8f90a1b2c3d4e5f6072258201234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1a201e016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107b66eef55ee782ea2514bb6a1c42ad5b3081b0102
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader updates binary data at offset 257 (73 bytes).
            C-APDU: 00d601014903040506011c1a080569736f2e6f72673a31383031333a6368626c656f7074696f6e736368626c65a30018c001f502f55c11010369736f2e6f72673a31383031333a6e66636e666301
            # The mdoc acknowledges the NDEF write.
            R-APDU: 9000

            # The mdoc reader finalizes NLEN=328 completing the NDEF Handover Request message.
            # NDEF Message (5 record(s), 328 bytes):
            #   Record #1:
            #     TNF: WELL_KNOWN
            #     Type: "Hr" (Hex: 4872)
            #     Payload Size: 56 bytes
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
            #         Record #3:
            #           TNF: WELL_KNOWN
            #           Type: "ac" (Hex: 6163)
            #           Payload Size: 17 bytes
            #           Alternative Carrier:
            #             Carrier Power State: 1
            #             Carrier Data Reference: "nfc"
            #             Auxiliary Data References: ["mdocreader"]
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
            #             -2: h'a3f591c48b2d6e0f147c8a9e3b5d7f2018293a4b5c6d7e8f90a1b2c3d4e5f607',
            #             -3: h'1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 30 bytes
            #     Carrier Configuration: ble:direct_l2cap_central:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6:psm=192:addr=06-05-04-03-02-01/random, ble:direct_l2cap_peripheral:uuid=null, ble:gatt_central:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6:addr=06-05-04-03-02-01/random, ble:gatt_peripheral:uuid=null
            #     Payload Hex: 02 1c 02 11 07 b6 6e ef 55 ee 78 2e a2 51 4b b6 a1 c4 2a d5 b3 08 1b 01 02 03 04 05 06 01
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:chbleoptions" (Hex: 69736f2e6f72673a31383031333a6368626c656f7074696f6e73)
            #     ID: "chble" (Hex: 6368626c65)
            #     Payload Size: 8 bytes
            #     ChBleOptions CBOR:
            #       {
            #         0: 192,
            #         1: true,
            #         2: true
            #       }
            #   Record #5:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 1 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01
            C-APDU: 00d60000020148
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
            #             -2: h'7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d',
            #             -3: h'f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2'
            #           } >>)
            #         ]
            #       }
            #   Record #3:
            #     TNF: MIME_MEDIA
            #     Type: "application/vnd.bluetooth.le.oob" (Hex: 6170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62)
            #     ID: "0" (Hex: 30)
            #     Payload Size: 3 bytes
            #     Carrier Configuration: ble:gatt_central:uuid=null
            #     Payload Hex: 02 1c 01
            R-APDU: 91020f487315d10209616301013001046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba25a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c019000
        """.trimIndent().trim()
        assertEquals(expectedTranscript, tag.transcript.toString())
        helper.close()
    }

    @Test
    fun testLegacyReaderStaticHandoverAgainstCoreMdocNfcEngagementHelper() = runTest {
        var coreEncodedDeviceEngagement: ByteString? = null
        var coreHandoverBytes: ByteArray? = null
        val coreHelper = MdocNfcEngagementHelper(
            eDeviceKey = getEDeviceKeyPub(),
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(getCoreConnectionMethods(), connectionMethods)
                coreEncodedDeviceEngagement = encodedDeviceEngagement
                coreHandoverBytes = Cbor.encode(handover)
            },
            onError = { error -> fail("Unexpected core error: $error") },
            staticHandoverMethods = getCoreConnectionMethods(),
        )
        val tag = TranscriptRecordingLoopbackTag(apduHandler = { coreHelper.processApdu(it) })

        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            legacyTransceive(tag, NfcUtil.createApduApplicationSelect(NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION))
        )
        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            legacyTransceive(tag, NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID))
        )
        val ccResp = legacyTransceive(tag, NfcUtil.createApduReadBinary(0, 15))
        assertEquals("000f207fff7fff0406e1047fff00ff9000", ccResp.toHex())

        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            legacyTransceive(tag, NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID))
        )
        val lenResp = legacyTransceive(tag, NfcUtil.createApduReadBinary(0, 2))
        assertEquals(4, lenResp.size)
        val ndefLen = lenResp.getUInt16(0).toInt()
        val msgResp = legacyTransceive(tag, NfcUtil.createApduReadBinary(2, ndefLen))
        val initialNdef = msgResp.copyOf(ndefLen)

        assertNull(NfcUtil.findServiceParameterRecordWithName(initialNdef, "urn:nfc:sn:handover"))
        val parsedHs = NfcUtil.parseHandoverSelectMessage(initialNdef)
        assertNotNull(parsedHs)
        val engagement = EngagementParser(parsedHs!!.encodedDeviceEngagement).parse()
        assertEquals("1.1", engagement.version)
        assertEquals(getEDeviceKeyPub(), engagement.eSenderKey)
        assertEquals(2, parsedHs.connectionMethods.size)
        val bleCm = parsedHs.connectionMethods[0] as LegacyMdocConnectionMethodBle
        assertTrue(bleCm.supportsCentralClientMode)
        assertTrue(bleCm.supportsPeripheralServerMode)
        assertEquals(getStaticBleUuid(), bleCm.centralClientModeUuid)
        assertEquals(getStaticBleUuid(), bleCm.peripheralServerModeUuid)
        val nfcCm = parsedHs.connectionMethods[1] as LegacyMdocConnectionMethodNfc
        assertEquals(0xffffL, nfcCm.commandDataFieldMaxLength)
        assertEquals(0x10000L, nfcCm.responseDataFieldMaxLength)

        val legacyHandover = Cbor.encode(
            CborArray.builder()
                .add(initialNdef)
                .add(Simple.NULL)
                .end()
                .build()
        )
        assertArrayEquals(coreEncodedDeviceEngagement!!.toByteArray(), parsedHs.encodedDeviceEngagement)
        assertArrayEquals(coreHandoverBytes!!, legacyHandover)

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
            #             -2: h'7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d',
            #             -3: h'f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2'
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
            #     Carrier Configuration: ble:gatt_central:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6, ble:gatt_peripheral:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6
            #     Payload Hex: 02 1c 03 11 07 b6 6e ef 55 ee 78 2e a2 51 4b b6 a1 c4 2a d5 b3
            #   Record #4:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 10 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01 03 01 ff ff 04 02 01 00 00
            R-APDU: 91021f487315910209616301013001046d646f6351020b616301036e666301046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2058006a203f504f51a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c031107b66eef55ee782ea2514bb6a1c42ad5b35c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff04020100009000
        """.trimIndent().trim()
        assertEquals(expectedTranscript, tag.transcript.toString())
    }

    @Test
    fun testLegacyReaderNegotiatedHandoverAgainstCoreMdocNfcEngagementHelper() = runTest {
        var coreEncodedDeviceEngagement: ByteString? = null
        var coreHandoverBytes: ByteArray? = null
        var coreSelectedMethod: CoreMdocConnectionMethod? = null
        val coreHelper = MdocNfcEngagementHelper(
            eDeviceKey = getEDeviceKeyPub(),
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(1, connectionMethods.size)
                coreSelectedMethod = connectionMethods.single()
                coreEncodedDeviceEngagement = encodedDeviceEngagement
                coreHandoverBytes = Cbor.encode(handover)
            },
            onError = { error -> fail("Unexpected core error: $error") },
            negotiatedHandoverPicker = { methods -> methods.first() },
        )
        val tag = TranscriptRecordingLoopbackTag(apduHandler = { coreHelper.processApdu(it) })

        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            legacyTransceive(tag, NfcUtil.createApduApplicationSelect(NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION))
        )
        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            legacyTransceive(tag, NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID))
        )
        val ccResp = legacyTransceive(tag, NfcUtil.createApduReadBinary(0, 15))
        assertEquals("000f207fff7fff0406e1047fff00009000", ccResp.toHex())

        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            legacyTransceive(tag, NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID))
        )
        val lenResp = legacyTransceive(tag, NfcUtil.createApduReadBinary(0, 2))
        val initLen = lenResp.getUInt16(0).toInt()
        val initialNdef = legacyTransceive(tag, NfcUtil.createApduReadBinary(2, initLen)).copyOf(initLen)

        val spRecord = NfcUtil.findServiceParameterRecordWithName(initialNdef, "urn:nfc:sn:handover")
        assertNotNull(spRecord)
        val parsedSp = NfcUtil.parseServiceParameterRecord(spRecord!!)
        assertEquals(0x10, parsedSp.tnepVersion)
        assertEquals("urn:nfc:sn:handover", parsedSp.serviceNameUri)

        val statusResp = legacyNdefTransact(tag, NfcUtil.createNdefMessageServiceSelect("urn:nfc:sn:handover"))
        val tnepStatus = NfcUtil.findTnepStatusRecord(statusResp)
        assertNotNull(tnepStatus)
        assertArrayEquals(byteArrayOf(0x00), tnepStatus!!.payload)

        val hrMessage = NfcUtil.createNdefMessageHandoverRequest(
            getLegacyConnectionMethods(),
            null,
            DataTransportOptions.Builder().build()
        )
        val hsMessage = legacyNdefTransact(tag, hrMessage)
        val parsedHs = NfcUtil.parseHandoverSelectMessage(hsMessage)
        assertNotNull(parsedHs)

        val engagement = EngagementParser(parsedHs!!.encodedDeviceEngagement).parse()
        assertEquals("1.1", engagement.version)
        assertEquals(getEDeviceKeyPub(), engagement.eSenderKey)
        assertEquals(1, parsedHs.connectionMethods.size)
        val selectedBle = parsedHs.connectionMethods.single() as LegacyMdocConnectionMethodBle
        assertTrue(selectedBle.supportsCentralClientMode)
        assertFalse(selectedBle.supportsPeripheralServerMode)
        assertNull(selectedBle.centralClientModeUuid)

        assertEquals(
            CoreMdocConnectionMethod.Ble.GattCentral(uuid = getStaticBleUuid()),
            coreSelectedMethod
        )
        val legacyHandover = Cbor.encode(
            CborArray.builder()
                .add(hsMessage)
                .add(hrMessage)
                .end()
                .build()
        )
        assertArrayEquals(coreEncodedDeviceEngagement!!.toByteArray(), parsedHs.encodedDeviceEngagement)
        assertArrayEquals(coreHandoverBytes!!, legacyHandover)

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

            # The mdoc reader writes the NDEF Handover Request message (NLEN=109).
            # NDEF Message (3 record(s), 109 bytes):
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
            #     Carrier Configuration: ble:gatt_central:uuid=b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6, ble:gatt_peripheral:uuid=null
            #     Payload Hex: 02 1c 03 11 07 b6 6e ef 55 ee 78 2e a2 51 4b b6 a1 c4 2a d5 b3
            #   Record #3:
            #     TNF: EXTERNAL_TYPE
            #     Type: "iso.org:18013:nfc" (Hex: 69736f2e6f72673a31383031333a6e6663)
            #     ID: "nfc" (Hex: 6e6663)
            #     Payload Size: 1 bytes
            #     Carrier Configuration: nfc:cmd_max_length=65535:resp_max_length=65536
            #     Payload Hex: 01
            C-APDU: 00d600006f006d910215487215910204616301013000510206616301036e6663001a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c031107b66eef55ee782ea2514bb6a1c42ad5b35c11010369736f2e6f72673a31383031333a6e66636e666301
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
            #             -2: h'7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d',
            #             -3: h'f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2'
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
            R-APDU: 91020f487315d10209616301013001046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2058006a203f504f55a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c019000
        """.trimIndent().trim()
        assertEquals(expectedTranscript, tag.transcript.toString())
    }

    @Test
    fun testStaticHandoverReselectNdefFile() {
        var handoverSelectSentCount = 0
        val context = RuntimeEnvironment.getApplication()
        val helper = NfcEngagementHelper.Builder(
            context,
            getEDeviceKeyPub(),
            DataTransportOptions.Builder().build(),
            createRecordingListener(onHandoverSelectSent = { handoverSelectSentCount++ }),
            directExecutor
        )
            .useStaticHandover(getLegacyConnectionMethods())
            .build()
        helper.testingDoNotStartTransports()

        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            helper.nfcProcessCommandApdu(
                NfcUtil.createApduApplicationSelect(NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION)
            )
        )
        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            helper.nfcProcessCommandApdu(NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID))
        )
        val firstLen = helper.nfcProcessCommandApdu(NfcUtil.createApduReadBinary(0, 2))
        assertEquals(1, handoverSelectSentCount)

        // Re-select CC file, then re-select NDEF file; must serve cached HS without duplicate callbacks.
        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            helper.nfcProcessCommandApdu(NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID))
        )
        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            helper.nfcProcessCommandApdu(NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID))
        )
        val secondLen = helper.nfcProcessCommandApdu(NfcUtil.createApduReadBinary(0, 2))
        assertArrayEquals(firstLen, secondLen)
        assertEquals(1, handoverSelectSentCount)
        helper.close()
    }

    @Test
    fun testReadBinaryShortLe256() {
        val context = RuntimeEnvironment.getApplication()
        val bleUuid = getStaticBleUuid()
        // Static handover with BLE + NFC + Wi-Fi Aware yields an NDEF file larger than 256 bytes,
        // allowing a 5-byte short READ BINARY with Le=0x00 (256 bytes) to succeed.
        val methods = listOf(
            LegacyMdocConnectionMethodBle(true, true, bleUuid, bleUuid),
            LegacyMdocConnectionMethodNfc(0xffff, 0x10000),
            LegacyMdocConnectionMethodNfc(0x1000, 0x2000),
        )
        val helper = NfcEngagementHelper.Builder(
            context,
            getEDeviceKeyPub(),
            DataTransportOptions.Builder().build(),
            createRecordingListener(),
            directExecutor
        )
            .useStaticHandover(methods)
            .build()
        helper.testingDoNotStartTransports()

        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            helper.nfcProcessCommandApdu(
                NfcUtil.createApduApplicationSelect(NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION)
            )
        )
        assertArrayEquals(
            NfcUtil.STATUS_WORD_OK,
            helper.nfcProcessCommandApdu(NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID))
        )
        val lenResp = helper.nfcProcessCommandApdu(NfcUtil.createApduReadBinary(0, 2))
        val ndefLen = lenResp.getUInt16(0).toInt()
        assertTrue("Expected NDEF message >= 256 bytes, got $ndefLen", ndefLen >= 256)

        // ISO/IEC 7816-4 short READ BINARY with Le=0x00 encodes 256 bytes.
        val shortLe256Apdu = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_READ_BINARY,
            p1 = 0x00,
            p2 = 0x02,
            payload = ByteString(),
            le = 0x100
        ).encode()
        assertEquals(5, shortLe256Apdu.size)
        val resp = helper.nfcProcessCommandApdu(shortLe256Apdu)
        assertEquals(256 + 2, resp.size)
        assertEquals(0x9000, resp.getUInt16(256).toInt())
        helper.close()
    }

    @Test
    fun testNfcAndBleCarrierRecordEdgeCases() {
        fun nfcRecord(payload: ByteArray) = AndroidNdefRecord(
            AndroidNdefRecord.TNF_EXTERNAL_TYPE,
            "iso.org:18013:nfc".toByteArray(),
            "nfc".toByteArray(),
            payload
        )

        // 1. Version-only 0x01 payload: valid for HR (reader), rejected for HS (mdoc).
        val versionOnly = nfcRecord(byteArrayOf(0x01))
        val parsedHr = DataTransportNfc.fromNdefRecord(versionOnly, isForHandoverSelect = false)
        assertEquals(LegacyMdocConnectionMethodNfc(0xffff, 0x10000), parsedHr)
        assertNull(DataTransportNfc.fromNdefRecord(versionOnly, isForHandoverSelect = true))

        // 2. Legacy reader payload with lengths: still accepted for HR.
        val legacyWithLengths = nfcRecord("01030110000402010000".fromHex())
        assertEquals(
            LegacyMdocConnectionMethodNfc(0x1000, 0x10000),
            DataTransportNfc.fromNdefRecord(legacyWithLengths, isForHandoverSelect = false)
        )
        assertEquals(
            LegacyMdocConnectionMethodNfc(0x1000, 0x10000),
            DataTransportNfc.fromNdefRecord(legacyWithLengths, isForHandoverSelect = true)
        )

        // 3. Unknown LTV data types are skipped, truncated/malformed payloads return null cleanly.
        val withUnknownTlv = nfcRecord("0102ff420301100003022000".fromHex())
        assertEquals(
            LegacyMdocConnectionMethodNfc(0x1000, 0x2000),
            DataTransportNfc.fromNdefRecord(withUnknownTlv, isForHandoverSelect = true)
        )
        assertNull(DataTransportNfc.fromNdefRecord(nfcRecord(byteArrayOf()), isForHandoverSelect = false))
        assertNull(DataTransportNfc.fromNdefRecord(nfcRecord("010301ff".fromHex()), isForHandoverSelect = false))
        assertNull(DataTransportNfc.fromNdefRecord(nfcRecord("0100".fromHex()), isForHandoverSelect = false))
        assertNull(DataTransportNfc.fromNdefRecord(nfcRecord("010201ff0201ff".fromHex()), isForHandoverSelect = false))

        // 4. DataTransportNfc.toNdefRecord omits lengths for HR and includes them for HS.
        val cm = LegacyMdocConnectionMethodNfc(0xffff, 0x10000)
        assertArrayEquals(
            byteArrayOf(0x01),
            DataTransportNfc.toNdefRecord(cm, emptyList(), isForHandoverSelect = false).first.payload
        )
        assertEquals(
            "010301ffff0402010000",
            DataTransportNfc.toNdefRecord(cm, listOf("mdoc"), isForHandoverSelect = true).first.payload.toHex()
        )

        // 5. DataTransportBle.fromNdefRecord handles 0x00 padding, 7-byte address (len=8), and truncated payloads safely.
        fun bleRecord(payload: ByteArray) = AndroidNdefRecord(
            AndroidNdefRecord.TNF_MIME_MEDIA,
            "application/vnd.bluetooth.le.oob".toByteArray(),
            "0".toByteArray(),
            payload
        )
        val paddedWith7ByteAddr = bleRecord("00021c01081b0102030405060100".fromHex())
        val parsedBle = DataTransportBle.fromNdefRecord(paddedWith7ByteAddr, isForHandoverSelect = false)
        assertNotNull(parsedBle)
        assertTrue(parsedBle!!.supportsPeripheralServerMode)
        assertFalse(parsedBle.supportsCentralClientMode)
        assertNull(parsedBle.peripheralServerModeMacAddress)

        // Truncated AD structure returns null cleanly instead of throwing BufferUnderflowException.
        assertNull(DataTransportBle.fromNdefRecord(bleRecord("021c".fromHex()), isForHandoverSelect = false))
        assertNull(DataTransportBle.fromNdefRecord(bleRecord("1107010203".fromHex()), isForHandoverSelect = false))
    }
}
