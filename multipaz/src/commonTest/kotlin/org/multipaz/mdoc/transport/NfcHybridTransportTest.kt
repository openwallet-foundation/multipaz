package org.multipaz.mdoc.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [NfcHybridTransportMdoc] and [NfcHybridTransportMdocReader], which implement the
 * ISO/IEC 18013-5 Second Edition NFCv2 hybrid transport for the wallet (mdoc) and reader roles.
 *
 * ### How the NFCv2 hybrid transport works
 * During an NFCv2 engagement ([org.multipaz.mdoc.nfc.MdocNfcV2EngagementHelper] /
 * [org.multipaz.mdoc.nfc.mdocReaderNfcHandover]), the reader and mdoc can either:
 * 1. **Negotiate a secondary transport** (such as BLE) alongside the active NFCv2 channel:
 *    - Both sides immediately begin exchanging session messages over NFCv2 (`INS_ENVELOPE` / `INS_GET_RESPONSE`
 *      APDUs encapsulated in BER-TLV `DO'53'`) while the secondary BLE transport connects asynchronously in the
 *      background (`negotiatedTransport.open()` on the reader, [NfcHybridTransportMdoc.setTransport] on the mdoc).
 *    - Every outgoing message is sent over NFC (if still connected) **and** queued/sent on the secondary
 *      transport. Once the secondary transport finishes connecting, any backlog of messages sent prior to
 *      connection is flushed in order.
 *    - Incoming messages from both channels are funneled through `conveyMessage(message, fromNfc)` which uses
 *      per-channel sequence counters (`incomingMessagesNumViaNfc` and `incomingMessagesNumViaTransport` vs
 *      `incomingMessagesNumConveyed`) so the caller receives each logical message exactly once from whichever
 *      channel delivers it first, while duplicates arriving later on the slower channel are dropped.
 *    - If the holder pulls their phone out of the NFC field before the transaction finishes, the NFC channel
 *      fails gracefully (`nfcDisconnectedDuringTransaction = true`) and the session continues uninterrupted over
 *      the secondary BLE transport.
 * 2. **Run in NFC-only mode** ([MdocConnectionMethodNfcV2]):
 *    - No secondary transport is used (`isNfcOnly == true` on the mdoc, `negotiatedTransport == null` on the
 *      reader).
 *    - If the holder removes their phone while user consent or biometric authentication is in progress
 *      ([NfcHybridTransportMdoc.markResponsePending]), [NfcHybridTransportMdoc.sendMessage] suspends until the
 *      phone is re-tapped (`isNfcConnected` transitions back to `true` when the reader resends the request on the
 *      second tap), ignoring the duplicate re-tap request and delivering the pending response.
 *
 * ### Test doubles used in this file
 * - [FakeSecondaryTransport]: Controllable in-memory [MdocTransport] stand-in for the negotiated BLE transport.
 *   Supports pausing `open()` via `openGate` so tests can send messages over NFC while BLE is still connecting
 *   and then verify that the pending outgoing backlog is flushed when `openGate` completes.
 * - [FakeNfcV2Tag]: In-memory [NfcIsoTag] for [NfcHybridTransportMdocReader] that returns queued `DO'53'`
 *   responses or throws [NfcTagLostException] when `failTransceive` is set to simulate the phone leaving the
 *   NFC field mid-transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NfcHybridTransportTest {
    private lateinit var senderKey: EcPublicKey

    @BeforeTest
    fun setup() = runTest {
        senderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
    }

    /**
     * Verifies message deduplication and [NfcHybridTransportStats] tracking on [NfcHybridTransportMdoc]
     * when both NFC and a secondary transport are active:
     * - Message 1 arrives on NFC first and is conveyed; its later arrival on the secondary transport is dropped.
     * - Message 2 arrives on the secondary transport first and is conveyed; its later arrival on NFC is dropped.
     * - Outgoing messages are transmitted over both NFC and the secondary transport.
     */
    @Test
    fun mdocDeduplicatesMessagesAndTracksStats() = runTest {
        withContext(Dispatchers.Default) {
            val nfcSentMessages = mutableListOf<ByteString>()
            val mdoc = NfcHybridTransportMdoc(
                sendMessageViaNfc = { msg ->
                    nfcSentMessages.add(msg)
                    true
                }
            )
            assertEquals(MdocRole.MDOC, mdoc.role)
            assertTrue(mdoc.isNfcOnly)
            mdoc.setExpectTransport(true)
            assertFalse(mdoc.isNfcOnly)

            mdoc.open(senderKey)
            assertEquals(MdocTransport.State.CONNECTED, mdoc.state.value)

            val secondary = FakeSecondaryTransport(role = MdocRole.MDOC)
            secondary.open(senderKey)
            mdoc.setTransport(secondary)

            // Message 1: arrives via NFC first, then via secondary transport
            val msg1 = ByteString(byteArrayOf(1, 2, 3))
            mdoc.onMessageReceivedViaNfc(msg1)
            assertContentEquals(msg1.toByteArray(), mdoc.waitForMessage())

            secondary.incomingChannel.send(msg1.toByteArray())
            // Now send Message 2 via secondary transport first, then via NFC
            val msg2 = ByteString(byteArrayOf(4, 5, 6))
            secondary.incomingChannel.send(msg2.toByteArray())
            val receivedMsg2 = withTimeout(5.seconds) { mdoc.waitForMessage() }
            assertContentEquals(msg2.toByteArray(), receivedMsg2)

            // Duplicate of msg2 over NFC should be ignored
            mdoc.onMessageReceivedViaNfc(msg2)

            // Send a message from mdoc: should go to both NFC and secondary transport
            val outgoing = byteArrayOf(7, 8, 9)
            mdoc.sendMessage(outgoing)
            assertEquals(1, nfcSentMessages.size)
            assertContentEquals(outgoing, nfcSentMessages[0].toByteArray())
            assertEquals(1, secondary.sentMessages.size)
            assertContentEquals(outgoing, secondary.sentMessages[0])

            val stats = mdoc.stats
            assertEquals(1, stats.numSentViaNfc)
            assertEquals(1, stats.numSentViaTransport)
            assertEquals(2, stats.numReceived)
            assertFalse(stats.nfcDisconnectedDuringTransaction)

            mdoc.close()
            assertEquals(MdocTransport.State.CLOSED, mdoc.state.value)
            assertTrue(secondary.closed)
        }
    }

    /**
     * Verifies that messages sent by [NfcHybridTransportMdoc] before [NfcHybridTransportMdoc.setTransport]
     * is called are buffered and flushed in order as soon as the secondary transport is attached, even if
     * the NFC link dropped (`sendMessageViaNfc` returned `false`) before the second message was sent.
     * Also verifies that calling [NfcHybridTransportMdoc.setTransport] after `close()` immediately closes
     * the late transport rather than leaking it.
     */
    @Test
    fun mdocFlushesBacklogWhenSecondaryTransportConnectsLate() = runTest {
        val nfcSentMessages = mutableListOf<ByteString>()
        var nfcUp = true
        val mdoc = NfcHybridTransportMdoc(
            sendMessageViaNfc = { msg ->
                if (nfcUp) {
                    nfcSentMessages.add(msg)
                    true
                } else {
                    false
                }
            }
        )
        mdoc.setExpectTransport(true)
        mdoc.open(senderKey)

        // Send two messages before secondary transport is attached; second one while NFC is down
        val msg1 = byteArrayOf(10, 11)
        val msg2 = byteArrayOf(12, 13)
        mdoc.sendMessage(msg1)
        nfcUp = false
        mdoc.sendMessage(msg2)

        assertEquals(1, nfcSentMessages.size)
        assertTrue(mdoc.stats.nfcDisconnectedDuringTransaction)

        // Now attach secondary transport: backlog of [msg1, msg2] must be flushed in order
        val secondary = FakeSecondaryTransport(role = MdocRole.MDOC)
        secondary.open(senderKey)
        mdoc.setTransport(secondary)

        assertEquals(2, secondary.sentMessages.size)
        assertContentEquals(msg1, secondary.sentMessages[0])
        assertContentEquals(msg2, secondary.sentMessages[1])
        assertEquals(2, mdoc.stats.numSentViaTransport)

        mdoc.close()

        // Calling setTransport after mdoc is already CLOSED immediately closes the new transport
        val lateSecondary = FakeSecondaryTransport(role = MdocRole.MDOC)
        mdoc.setTransport(lateSecondary)
        assertTrue(lateSecondary.closed)
    }

    /**
     * Verifies the NFC-only multi-tap flow on [NfcHybridTransportMdoc]:
     * 1. Tap 1 delivers the initial request over NFC (`onMessageReceivedViaNfc`).
     * 2. The mdoc calls [NfcHybridTransportMdoc.markResponsePending] (e.g. while showing a consent/biometric
     *    prompt) and the user removes the phone from the RF field (`onNfcDeactivated(LINK_LOSS)`).
     * 3. When the mdoc calls `sendMessage(response)` while NFC is disconnected, `sendMessage()` suspends
     *    waiting for `isNfcConnected` to become `true`.
     * 4. When the user re-taps the reader, the reader resends the request (`onMessageReceivedViaNfc`), which
     *    marks `isNfcConnected = true`, drops the duplicate request because `responsePending` was set, and
     *    wakes `sendMessage()` to transmit the response over the new tap.
     */
    @Test
    fun mdocNfcOnlyMultiTapIgnoresReTapMessageWhileResponsePendingAndWaitsForReTap() = runTest {
        var nfcConnected = false
        val nfcSent = mutableListOf<ByteString>()
        val mdoc = NfcHybridTransportMdoc(
            sendMessageViaNfc = { msg ->
                if (nfcConnected) {
                    nfcSent.add(msg)
                    true
                } else {
                    false
                }
            }
        )
        assertTrue(mdoc.isNfcOnly)
        mdoc.open(senderKey)

        // Initial tap: reader sends request
        val req1 = ByteString(byteArrayOf(1, 1, 1))
        mdoc.onMessageReceivedViaNfc(req1)
        assertContentEquals(req1.toByteArray(), mdoc.waitForMessage())

        // Mark response pending (holder is doing biometric/consent prompt) and NFC deactivates
        mdoc.markResponsePending()
        mdoc.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)
        assertFalse(mdoc.isNfcConnected.value)

        // Start sending response while NFC is disconnected; it should suspend waiting for re-tap
        val responseBytes = byteArrayOf(2, 2, 2)
        val sendDeferred = async { mdoc.sendMessage(responseBytes) }
        runCurrent()
        assertFalse(sendDeferred.isCompleted)

        // Reader re-taps and resends request: onMessageReceivedViaNfc sets isNfcConnected = true
        // and ignores the duplicate request because responsePending was true when marked
        nfcConnected = true
        mdoc.onMessageReceivedViaNfc(req1)
        runCurrent()

        withTimeout(5.seconds) {
            sendDeferred.await()
        }
        assertEquals(1, nfcSent.size)
        assertContentEquals(responseBytes, nfcSent[0].toByteArray())

        mdoc.close()
    }

    /**
     * Verifies that in NFC-only mode, if `sendMessage()` suspends waiting for a re-tap and `sendMessageViaNfc`
     * still returns `false` after the re-tap occurs, [NfcHybridTransportMdoc] transitions to
     * [MdocTransport.State.FAILED] and throws [MdocTransportException].
     */
    @Test
    fun mdocNfcOnlyFailsWhenSendAfterReTapStillFails() = runTest {
        val mdoc = NfcHybridTransportMdoc(
            sendMessageViaNfc = { false }
        )
        mdoc.open(senderKey)

        supervisorScope {
            val sendDeferred = async { mdoc.sendMessage(byteArrayOf(1, 2, 3)) }
            runCurrent()
            assertFalse(mdoc.isNfcConnected.value)
            // Simulate re-tap via onMessageReceivedViaNfc, but sendMessageViaNfc still returns false
            mdoc.onMessageReceivedViaNfc(ByteString(byteArrayOf(9, 9)))
            runCurrent()
            assertFailsWith<MdocTransportException> {
                sendDeferred.await()
            }
        }
        assertEquals(MdocTransport.State.FAILED, mdoc.state.value)
        mdoc.close()
    }

    /**
     * Verifies [NfcHybridTransportMdocReader] when paired with a secondary transport:
     * 1. While the secondary transport is still opening (`openGate` incomplete), `request1` is sent and
     *    `response1` is received over NFC.
     * 2. When `secondary.open()` completes (`openGate.complete(Unit)`), `request1` is flushed from
     *    `negotiatedTransportPendingOutgoingMessages` onto the secondary transport, and a duplicate
     *    `response1` arriving over the secondary transport is suppressed.
     * 3. When the NFC tag is subsequently lost (`tag.failTransceive = true`), `request2` / `response2`
     *    seamlessly fail over to the secondary transport without failing the hybrid reader transport.
     */
    @Test
    fun readerHybridTransportDeduplicationBacklogAndFailover() = runTest {
        withContext(Dispatchers.Default) {
            val tag = FakeNfcV2Tag()
            val secondary = FakeSecondaryTransport(role = MdocRole.MDOC_READER).apply {
                openGate = CompletableDeferred()
            }
            val reader = NfcHybridTransportMdocReader(
                nfcTag = tag,
                negotiatedTransport = secondary
            )
            assertEquals(MdocRole.MDOC_READER, reader.role)
            assertEquals(secondary.connectionMethod, reader.connectionMethod)

            reader.open(senderKey)
            assertEquals(MdocTransport.State.CONNECTED, reader.state.value)

            // Queue a response on NFC tag for the first request
            val request1 = byteArrayOf(10, 20, 30)
            val response1 = byteArrayOf(40, 50, 60)
            tag.responses.send(ByteString(response1))

            // Send request1 while secondary transport is still opening (not yet CONNECTED)
            reader.sendMessage(request1)

            // Receive response1 over NFC
            val got1 = withTimeout(5.seconds) { reader.waitForMessage() }
            assertContentEquals(response1, got1)

            // Now allow secondary transport to finish open(): it should flush request1 from pending backlog
            secondary.openGate!!.complete(Unit)
            withTimeout(5.seconds) {
                secondary.firstMessageSent.await()
            }
            assertEquals(1, secondary.sentMessages.size)
            assertContentEquals(request1, secondary.sentMessages[0])

            // If secondary transport also delivers response1, it is deduplicated
            secondary.incomingChannel.send(response1)

            // Now simulate NFC tag lost on next message; reader should seamlessly continue on secondary transport
            tag.failTransceive = true
            val request2 = byteArrayOf(70, 80)
            val response2 = byteArrayOf(90, 100)
            reader.sendMessage(request2)
            secondary.incomingChannel.send(response2)

            val got2 = withTimeout(5.seconds) { reader.waitForMessage() }
            assertContentEquals(response2, got2)

            val stats = reader.stats
            assertEquals(2, stats.numSent)
            assertEquals(2, stats.numReceived)

            reader.close()
            assertEquals(MdocTransport.State.CLOSED, reader.state.value)
            assertTrue(secondary.closed)
        }
    }

    /**
     * Verifies that when [NfcHybridTransportMdocReader] is operating in NFC-only mode
     * (`negotiatedTransport == null`) and the NFC tag is lost during `nfcV2Transact`, there is no secondary
     * transport to fail over to, so `waitForMessage()` throws [MdocTransportException] and the transport
     * transitions to [MdocTransport.State.FAILED].
     */
    @Test
    fun readerNfcOnlyFailsWhenNfcFails() = runTest {
        withContext(Dispatchers.Default) {
            val tag = FakeNfcV2Tag().apply {
                failTransceive = true
            }
            val reader = NfcHybridTransportMdocReader(
                nfcTag = tag,
                negotiatedTransport = null
            )
            assertTrue(reader.connectionMethod is MdocConnectionMethodNfcV2)

            reader.open(senderKey)
            reader.sendMessage(byteArrayOf(1, 2, 3))

            assertFailsWith<MdocTransportException> {
                withTimeout(5.seconds) {
                    reader.waitForMessage()
                }
            }
            assertEquals(MdocTransport.State.FAILED, reader.state.value)
        }
    }

    /**
     * Controllable in-memory [MdocTransport] stand-in for a secondary (e.g. BLE) transport.
     *
     * If [openGate] is non-null, [open] suspends in [State.CONNECTING] until [openGate] is completed,
     * allowing tests to exercise the window where NFC is already exchanging messages while BLE is still
     * establishing a connection.
     */
    private class FakeSecondaryTransport(
        override val role: MdocRole
    ) : MdocTransport() {
        private val _state = MutableStateFlow(State.IDLE)
        override val state: StateFlow<State> = _state.asStateFlow()

        override val connectionMethod: MdocConnectionMethod = MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = UUID.fromString("12345678-1234-5678-1234-567812345678"),
            centralClientModeUuid = null
        )

        override val scanningTime: Duration? = null

        var openGate: CompletableDeferred<Unit>? = null
        val firstMessageSent = CompletableDeferred<Unit>()
        val sentMessages = mutableListOf<ByteArray>()
        val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
        var closed = false

        override suspend fun advertise() {}

        override suspend fun open(eSenderKey: EcPublicKey) {
            _state.value = State.CONNECTING
            openGate?.await()
            _state.value = State.CONNECTED
        }

        override suspend fun sendMessage(message: ByteArray) {
            sentMessages.add(message)
            firstMessageSent.complete(Unit)
        }

        override suspend fun waitForMessage(): ByteArray {
            return incomingChannel.receive()
        }

        override suspend fun close() {
            closed = true
            incomingChannel.close()
            _state.value = State.CLOSED
        }
    }

    /**
     * In-memory [NfcIsoTag] that encapsulates queued [responses] in BER-TLV `DO'53'` or throws
     * [NfcTagLostException] when [failTransceive] is `true`.
     */
    private class FakeNfcV2Tag : NfcIsoTag() {
        override val maxTransceiveLength: Int = 65536
        var failTransceive = false
        var closed = false
        val responses = Channel<ByteString>(Channel.UNLIMITED)

        override suspend fun close() {
            closed = true
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            if (failTransceive) {
                throw NfcTagLostException("Tag lost")
            }
            val respPayload = responses.receive()
            return ResponseApdu(
                status = Nfc.RESPONSE_STATUS_SUCCESS,
                payload = encapsulateInDo53(respPayload)
            )
        }
    }
}
