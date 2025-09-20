package org.multipaz.mdoc.transport

import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ResponseApdu
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.multipaz.mdoc.role.MdocRole
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private fun String.truncateTo(maxLength: Int): String {
    if (this.length < maxLength) {
        return this
    } else {
        return this.substring(0, maxLength) + "..."
    }
}

class NfcTransportTests {

    companion object {
        private const val TAG = "NfcTransportTests"
    }

    class LoopbackIsoTag(val transport: NfcTransportMdoc): NfcIsoTag() {
        val transcript = StringBuilder()

        override var maxTransceiveLength = 0xfeff

        override suspend fun close() {
            transcript.appendLine("close")
        }

        override suspend fun updateDialogMessage(message: String) {}

        @OptIn(ExperimentalCoroutinesApi::class)
        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            transcript.appendLine("${command.toString().truncateTo(100)} (${command.encode().size} bytes)")
            val response = suspendCancellableCoroutine<ResponseApdu> { continuation ->
                runBlocking {
                    transport.processApdu(
                        command = command,
                        sendResponse = { response ->
                            continuation.resume(response, null)
                        }
                    )
                }
            }
            transcript.appendLine("${response.toString().truncateTo(100)} (${response.encode().size} bytes)")
            return response
        }
    }

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
ResponseApdu(status=24869, payload=ByteString(size=65272 hex=000000000000000000000000000000000000000... (65274 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=37) (5 bytes)
ResponseApdu(status=36864, payload=ByteString(size=8989 hex=0000000000000000000000000000000000000000... (8991 bytes)
close
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }

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
ResponseApdu(status=24871, payload=ByteString(size=4089 hex=0000000000000000000000000000000000000000... (4091 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=39) (5 bytes)
ResponseApdu(status=24878, payload=ByteString(size=4089 hex=0000000000000000000000000000000000000000... (4091 bytes)
CommandApdu(cla=0, ins=192, p1=0, p2=0, payload=ByteString(size=0), le=46) (5 bytes)
ResponseApdu(status=36864, payload=ByteString(size=39 hex=000000000000000000000000000000000000000000... (41 bytes)
close
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }
}