package org.multipaz.mdoc.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.ResponseApdu
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun String.truncateTo(maxLength: Int): String {
    if (this.length < maxLength) {
        return this
    } else {
        return this.substring(0, maxLength) + "..."
    }
}

/**
 * Unit tests for [NfcTransportMdoc], [NfcTransportMdocReader], and the BER-TLV `DO'53'`
 * encapsulation helpers ([encapsulateInDo53] and [extractFromDo53]) used for ISO/IEC 18013-5:2021
 * NFC data transfer (AID `A0000002480400`).
 *
 * ### How ISO 18013-5 NFC Data Transfer is tested here
 * - **End-to-end APDU loopback ([LoopbackIsoTag])**: [NfcTransportMdocReader] is paired with an in-memory
 *   [LoopbackIsoTag] that forwards every [CommandApdu] directly to [NfcTransportMdoc.processApdu] and
 *   records a human-readable APDU transcript. This verifies:
 *   1. Application selection (`SELECT APPLICATION` for `A0000002480400`).
 *   2. Single-APDU `ENVELOPE` (`INS = 0xC3`, `CLA = 0x00`) exchanges when payloads fit within both the
 *      negotiated [MdocConnectionMethodNfc] limits and [NfcIsoTag.maxTransceiveLength].
 *   3. Command APDU chaining (`CLA = 0x10` followed by `CLA = 0x00`) for large reader-to-mdoc requests,
 *      and `GET RESPONSE` (`INS = 0xC0`) chaining (`SW1 = 0x61`) for large mdoc-to-reader responses.
 * - **Tag lifecycle and error handling**: Verifies that [NfcTransportMdocReader] closes its underlying
 *   [NfcIsoTag] idempotently when `open()` or `transceive()` fails, and that [NfcTransportMdoc] returns
 *   the appropriate ISO 7816-4 status words (`6A82`, `6D00`, `6F00`) and transitions to
 *   [MdocTransport.State.FAILED] on invalid APDUs or out-of-order calls.
 * - **Static APDU routing and deferred `ENVELOPE` completion**: Verifies that
 *   [NfcTransportMdoc.processCommandApdu] and [NfcTransportMdoc.onDeactivated] route incoming HCE service
 *   events to the active [NfcTransportMdoc] instance, and that calling [NfcTransportMdoc.close] while an
 *   `ENVELOPE` command is suspended waiting for a response completes the APDU with an empty `DO'53'`
 *   (`53 00`) and status `9000`.
 */
class NfcTransportTests {

    companion object {
        private const val TAG = "NfcTransportTests"
    }

    /**
     * In-memory [NfcIsoTag] that forwards every [CommandApdu] from [NfcTransportMdocReader] directly
     * to [NfcTransportMdoc.processApdu] and records a transcript of command/response APDU sizes and
     * `close()` calls.
     *
     * Setting [failNextTransceive] to `true` causes the next [transceive] call to throw
     * [NfcTagLostException] to simulate tag loss during `open()` or message transfer.
     */
    class LoopbackIsoTag(val transport: NfcTransportMdoc): NfcIsoTag() {
        val transcript = StringBuilder()

        override var maxTransceiveLength = 0xfeff

        var failNextTransceive = false
        var closeCount = 0

        override suspend fun close() {
            closeCount++
            transcript.appendLine("close")
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            if (failNextTransceive) {
                throw NfcTagLostException("Tag was lost during transceive")
            }
            transcript.appendLine("${command.toString().truncateTo(100)} (${command.encode().size} bytes)")
            val response = transport.processApdu(command)
            transcript.appendLine("${response.toString().truncateTo(100)} (${response.encode().size} bytes)")
            return response
        }
    }

    /**
     * Verifies two back-to-back request/response exchanges between [NfcTransportMdocReader] and
     * [NfcTransportMdoc] with `maxTransceiveLength = 0xfeff`:
     * 1. A small exchange (1 KiB request / 2 KiB response) that fits in a single `ENVELOPE` APDU
     *    without command chaining or `GET RESPONSE`.
     * 2. A large exchange (100 KiB request / 200 KiB response) that requires `ENVELOPE` command
     *    chaining (`CLA = 0x10`) in the reader-to-mdoc direction and multiple `GET RESPONSE`
     *    (`INS = 0xC0`, `SW1 = 0x61`) rounds in the mdoc-to-reader direction.
     */
    @Test
    fun testHappyPath() = runTest {
        val mdoc = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val mdocReader = NfcTransportMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val tag = LoopbackIsoTag(mdoc)
        mdocReader.setTag(tag)

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        mdoc.open(eSenderKey = eReaderKey.publicKey)
        mdocReader.open(eSenderKey = eDeviceKey.publicKey)

        // Do two ping-pongs, one where messages won't need chunking, one where they do.

        val request0 = ByteArray(1*1024)
        mdocReader.sendMessage(request0)
        assertContentEquals(request0, mdoc.waitForMessage())
        val response0 = ByteArray(2*1024)
        mdoc.sendMessage(response0)
        assertContentEquals(response0, mdocReader.waitForMessage())

        val request1 = ByteArray(100*1024)
        mdocReader.sendMessage(request1)
        assertContentEquals(request1, mdoc.waitForMessage())
        val response1 = ByteArray(200*1024)
        mdoc.sendMessage(response1)
        assertContentEquals(response1, mdocReader.waitForMessage())

        mdoc.close()
        mdocReader.close()

        // Check via the transcript that only the second interaction was chunked.
        assertEquals(
            """
CommandApdu(cla=0, ins=164, p1=4, p2=0, payload=ByteString(size=7 hex=a0000002480400), le=0) (12 bytes)
ResponseApdu(status=36864, payload=ByteString(size=0)) (2 bytes)
CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=1028 hex=538204000000000000000000000... (1037 bytes)
ResponseApdu(status=36864, payload=ByteString(size=2052 hex=5382080000000000000000000000000000000000... (2054 bytes)
CommandApdu(cla=16, ins=195, p1=0, p2=0, payload=ByteString(size=65272 hex=5383019000000000000000000... (65279 bytes)
ResponseApdu(status=36864, payload=ByteString(size=0)) (2 bytes)
CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=37133 hex=00000000000000000000000000... (37142 bytes)
ResponseApdu(status=24832, payload=ByteString(size=65272 hex=538303200000000000000000000000000000000... (65274 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=65272) (7 bytes)
ResponseApdu(status=24832, payload=ByteString(size=65272 hex=000000000000000000000000000000000000000... (65274 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=65272) (7 bytes)
ResponseApdu(status=24832, payload=ByteString(size=65272 hex=000000000000000000000000000000000000000... (65274 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=65272) (7 bytes)
ResponseApdu(status=36864, payload=ByteString(size=8989 hex=0000000000000000000000000000000000000000... (8991 bytes)
close
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }

    /**
     * Verifies that when the reader's [NfcIsoTag.maxTransceiveLength] (4096 bytes) is smaller than the
     * maximum APDU sizes advertised in [MdocConnectionMethodNfc] (`0xffff` / `0x10000`),
     * [NfcTransportMdocReader] clamps both command chunks and `Le` values so that every APDU fits within
     * `maxTransceiveLength`.
     */
    @Test
    fun testLowTransceiveLength() = runTest {
        val mdoc = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val mdocReader = NfcTransportMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val tag = LoopbackIsoTag(mdoc)
        tag.maxTransceiveLength = 4096
        mdocReader.setTag(tag)

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        mdoc.open(eSenderKey = eReaderKey.publicKey)
        mdocReader.open(eSenderKey = eDeviceKey.publicKey)

        // Check that maxTransceiveLength of 4 KiB is respected

        val request0 = ByteArray(10*1024)
        mdocReader.sendMessage(request0)
        assertContentEquals(request0, mdoc.waitForMessage())
        val response0 = ByteArray(20*1024)
        mdoc.sendMessage(response0)
        assertContentEquals(response0, mdocReader.waitForMessage())

        mdoc.close()
        mdocReader.close()

        // Check via the transcript messages are chunked to fit in 4 KiB APDUs
        assertEquals(
            """
CommandApdu(cla=0, ins=164, p1=4, p2=0, payload=ByteString(size=7 hex=a0000002480400), le=0) (12 bytes)
ResponseApdu(status=36864, payload=ByteString(size=0)) (2 bytes)
CommandApdu(cla=16, ins=195, p1=0, p2=0, payload=ByteString(size=4089 hex=53822800000000000000000000... (4096 bytes)
ResponseApdu(status=36864, payload=ByteString(size=0)) (2 bytes)
CommandApdu(cla=16, ins=195, p1=0, p2=0, payload=ByteString(size=4089 hex=00000000000000000000000000... (4096 bytes)
ResponseApdu(status=36864, payload=ByteString(size=0)) (2 bytes)
CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=2066 hex=000000000000000000000000000... (2075 bytes)
ResponseApdu(status=24832, payload=ByteString(size=4089 hex=5382500000000000000000000000000000000000... (4091 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=4089) (7 bytes)
ResponseApdu(status=24832, payload=ByteString(size=4089 hex=0000000000000000000000000000000000000000... (4091 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=4089) (7 bytes)
ResponseApdu(status=24832, payload=ByteString(size=4089 hex=0000000000000000000000000000000000000000... (4091 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=4089) (7 bytes)
ResponseApdu(status=24832, payload=ByteString(size=4089 hex=0000000000000000000000000000000000000000... (4091 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=4089) (7 bytes)
ResponseApdu(status=24871, payload=ByteString(size=4089 hex=0000000000000000000000000000000000000000... (4091 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=39) (5 bytes)
ResponseApdu(status=36864, payload=ByteString(size=39 hex=000000000000000000000000000000000000000000... (41 bytes)
close
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }

    /**
     * Verifies that when [NfcIsoTag.transceive] throws [NfcTagLostException] during a data transfer,
     * [NfcTransportMdocReader] transitions to [MdocTransport.State.FAILED], closes the underlying
     * [NfcIsoTag] immediately, and does not close the tag a second time when [NfcTransportMdocReader.close]
     * is called afterward.
     */
    @Test
    fun testTagClosedOnTransceiveFailure() = runTest {
        val mdoc = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val mdocReader = NfcTransportMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val tag = LoopbackIsoTag(mdoc)
        mdocReader.setTag(tag)

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        mdoc.open(eSenderKey = eReaderKey.publicKey)
        mdocReader.open(eSenderKey = eDeviceKey.publicKey)

        tag.failNextTransceive = true
        mdocReader.sendMessage(ByteArray(64))

        assertFailsWith<MdocTransportException> {
            mdocReader.waitForMessage()
        }
        assertEquals(MdocTransport.State.FAILED, mdocReader.state.value)
        assertEquals(1, tag.closeCount)

        // Calling close() after failure must be idempotent and not close the tag a second time.
        mdocReader.close()
        assertEquals(1, tag.closeCount)
        mdoc.close()
    }

    /**
     * Verifies that when [NfcIsoTag.transceive] fails during the initial `SELECT APPLICATION` command
     * in [NfcTransportMdocReader.open], the transport transitions to [MdocTransport.State.FAILED] and
     * closes the underlying [NfcIsoTag] exactly once.
     */
    @Test
    fun testTagClosedOnOpenFailure() = runTest {
        val mdoc = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val mdocReader = NfcTransportMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val tag = LoopbackIsoTag(mdoc)
        tag.failNextTransceive = true
        mdocReader.setTag(tag)

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        assertFailsWith<MdocTransportException> {
            mdocReader.open(eSenderKey = eDeviceKey.publicKey)
        }
        assertEquals(MdocTransport.State.FAILED, mdocReader.state.value)
        assertEquals(1, tag.closeCount)

        mdocReader.close()
        assertEquals(1, tag.closeCount)
    }

    /**
     * Verifies round-trip BER-TLV `DO'53'` encapsulation ([encapsulateInDo53]) and extraction
     * ([extractFromDo53]) across all ASN.1 length-encoding boundaries:
     * - 1-byte length (`0x00`..`0x7f`)
     * - 2-byte length (`0x81 xx` for `0x80`..`0xff`)
     * - 3-byte length (`0x82 xx xx` for `0x100`..`0xffff`)
     * - 4-byte length (`0x83 xx xx xx` for `0x10000` and above)
     * Also verifies that [extractFromDo53] rejects truncated inputs, wrong tags, indefinite lengths
     * (`0x80`), length headers exceeding 3 octets (`> 0x83`), and mismatched payload lengths.
     */
    @Test
    fun testDo53EncapsulationAndExtraction() {
        val sizes = listOf(0, 1, 0x7f, 0x80, 0xff, 0x100, 0xffff, 0x10000, 0x20000)
        for (size in sizes) {
            val payload = ByteString(ByteArray(size) { (it and 0xff).toByte() })
            val encapsulated = encapsulateInDo53(payload)
            val extracted = extractFromDo53(encapsulated)
            assertEquals(payload, extracted, "Mismatch for payload size $size")
        }

        // Malformed DO53 inputs
        // Too short (< 2 bytes)
        assertFailsWith<IllegalStateException> {
            extractFromDo53(ByteString(byteArrayOf(0x53)))
        }
        // Wrong tag (not 0x53)
        assertFailsWith<IllegalStateException> {
            extractFromDo53(ByteString(byteArrayOf(0x54, 0x00)))
        }
        // Indefinite length 0x80
        assertFailsWith<IllegalStateException> {
            extractFromDo53(ByteString(byteArrayOf(0x53, 0x80.toByte())))
        }
        // Length byte > 0x83
        assertFailsWith<IllegalStateException> {
            extractFromDo53(ByteString(byteArrayOf(0x53, 0x84.toByte(), 0x01, 0x00, 0x00, 0x00)))
        }
        // Mismatched length (claims 5 bytes, only 2 bytes present)
        assertFailsWith<IllegalStateException> {
            extractFromDo53(ByteString(byteArrayOf(0x53, 0x05, 0x01, 0x02)))
        }
    }

    /**
     * Verifies error handling in [NfcTransportMdoc.processApdu] and [NfcTransportMdoc.sendMessage]:
     * 1. Selecting an unknown AID returns `6A82` (`RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND`),
     *    transitions the transport to [MdocTransport.State.FAILED], and causes subsequent APDUs to
     *    return `6F00` (`RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS`).
     * 2. Sending an unsupported APDU instruction returns `6D00`
     *    (`RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID`) and fails the transport.
     * 3. Calling [NfcTransportMdoc.sendMessage] before any `ENVELOPE` command has been received from
     *    the reader (`leReceived == 0`) throws [IllegalStateException] and fails the transport.
     */
    @Test
    fun testNfcTransportMdocApduErrorsAndEdgeCases() = runTest {
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

        // 1. Wrong AID in SELECT APPLICATION returns 6A82 and fails transport
        val mdoc1 = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        mdoc1.open(eSenderKey = eReaderKey.publicKey)
        val wrongSelect = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = ByteString(byteArrayOf(0x01, 0x02, 0x03)),
            le = 0
        )
        val resp1 = mdoc1.processApdu(wrongSelect)
        assertEquals(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND, resp1.status)
        assertEquals(MdocTransport.State.FAILED, mdoc1.state.value)
        // Subsequent APDUs when inError is true return 6F00
        val resp1Subsequent = mdoc1.processApdu(wrongSelect)
        assertEquals(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS, resp1Subsequent.status)
        mdoc1.close()

        // 2. Unsupported instruction returns 6D00 and fails transport
        val mdoc2 = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        mdoc2.open(eSenderKey = eReaderKey.publicKey)
        val unsupportedApdu = CommandApdu(
            cla = 0x00,
            ins = 0x99,
            p1 = 0x00,
            p2 = 0x00,
            payload = ByteString(),
            le = 0
        )
        val resp2 = mdoc2.processApdu(unsupportedApdu)
        assertEquals(Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID, resp2.status)
        assertEquals(MdocTransport.State.FAILED, mdoc2.state.value)
        mdoc2.close()

        // 3. sendMessage before receiving any ENVELOPE (leReceived == 0) fails transport
        val mdoc3 = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        mdoc3.open(eSenderKey = eReaderKey.publicKey)
        val validSelect = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID,
            le = 0
        )
        assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, mdoc3.processApdu(validSelect).status)
        assertEquals(MdocTransport.State.CONNECTED, mdoc3.state.value)
        assertFailsWith<IllegalStateException> {
            mdoc3.sendMessage(byteArrayOf(1, 2, 3))
        }
        assertEquals(MdocTransport.State.FAILED, mdoc3.state.value)
        mdoc3.close()
    }

    /**
     * Verifies static APDU routing on [NfcTransportMdoc] ([NfcTransportMdoc.processCommandApdu] and
     * [NfcTransportMdoc.onDeactivated]) used by [org.multipaz.mdoc.nfc.MdocNfcDataTransferService],
     * as well as the deferred `ENVELOPE` response completion when [NfcTransportMdoc.close] is called:
     * 1. When no [NfcTransportMdoc] instance is open, `NfcTransportMdoc.processCommandApdu()` returns `6A82`.
     * 2. Once an instance is opened, `NfcTransportMdoc.processCommandApdu()` routes `SELECT` and
     *    `ENVELOPE` commands to it.
     * 3. While the `ENVELOPE` call is suspended waiting for the mdoc to reply, calling `mdoc.close()`
     *    completes the pending `ENVELOPE` with an empty `DO'53'` (`53 00`) and status `9000`.
     * 4. Calling `NfcTransportMdoc.onDeactivated()` transitions an active instance to
     *    [MdocTransport.State.FAILED].
     */
    @Test
    fun testNfcTransportMdocCloseCompletesDeferredEnvelopeAndStaticRouting() = runTest {
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

        // When no instances are open, processCommandApdu returns 6A82
        val validSelect = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID,
            le = 0
        )
        assertEquals(
            Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND,
            NfcTransportMdoc.processCommandApdu(validSelect).status
        )

        val mdoc = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        mdoc.open(eSenderKey = eReaderKey.publicKey)
        assertEquals(
            Nfc.RESPONSE_STATUS_SUCCESS,
            NfcTransportMdoc.processCommandApdu(validSelect).status
        )
        assertEquals(MdocTransport.State.CONNECTED, mdoc.state.value)

        // Send an ENVELOPE command that suspends waiting for mdoc.sendMessage() or mdoc.close()
        val requestPayload = ByteString(byteArrayOf(10, 20, 30))
        val envelopeCmd = CommandApdu(
            cla = Nfc.CLA_CHAIN_LAST,
            ins = Nfc.INS_ENVELOPE,
            p1 = 0x00,
            p2 = 0x00,
            payload = encapsulateInDo53(requestPayload),
            le = 0x1000
        )
        val envelopeDeferred = async { NfcTransportMdoc.processCommandApdu(envelopeCmd) }
        runCurrent()
        assertFalse(envelopeDeferred.isCompleted)
        assertContentEquals(requestPayload.toByteArray(), mdoc.waitForMessage())

        // Calling close() completes the deferred ENVELOPE with empty DO53 (53 00) and status 9000
        mdoc.close()
        runCurrent()
        assertTrue(envelopeDeferred.isCompleted)
        val envelopeResponse = envelopeDeferred.await()
        assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, envelopeResponse.status)
        assertEquals(encapsulateInDo53(ByteString()), envelopeResponse.payload)
        assertEquals(MdocTransport.State.CLOSED, mdoc.state.value)

        // Test static onDeactivated() fails an active instance
        val mdoc2 = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        mdoc2.open(eSenderKey = eReaderKey.publicKey)
        NfcTransportMdoc.onDeactivated()
        assertEquals(MdocTransport.State.FAILED, mdoc2.state.value)
        mdoc2.close()
    }
}