package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHelperTest {
    private lateinit var senderKey: EcPublicKey

    @BeforeTest
    fun setup() = runTest {
        senderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
    }

    @Test
    fun waitsUntilSelectedTransportIsConnected() = runTest {
        val transport = TestTransport()
        val deferred = async { listOf(transport).waitForConnection(senderKey) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        transport.state.value = MdocTransport.State.CONNECTING
        runCurrent()
        assertFalse(deferred.isCompleted)

        transport.finishOpen.complete(Unit)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertSame(transport, deferred.await())
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)
        assertEquals(0, transport.closeCalls)
    }

    @Test
    fun duplicateTransportCanConnect() = runTest {
        val transport = TestTransport()
        val deferred = async { listOf(transport, transport).waitForConnection(senderKey) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        transport.state.value = MdocTransport.State.CONNECTING
        runCurrent()
        assertFalse(deferred.isCompleted)

        transport.finishOpen.complete(Unit)
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertSame(transport, deferred.await())
        assertEquals(1, transport.openCalls)
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)
        assertEquals(0, transport.closeCalls)
    }

    @Test
    fun duplicateLosingTransportDoesNotPreventCompletion() = runTest {
        val winner = TestTransport()
        val loser = TestTransport()
        val deferred = async { listOf(loser, winner, loser).waitForConnection(senderKey) }
        runCurrent()

        winner.finishOpen.complete(Unit)
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertSame(winner, deferred.await())
        assertEquals(1, winner.openCalls)
        assertEquals(1, loser.openCalls)
        assertEquals(0, winner.closeCalls)
        assertTrue(loser.openCancelled)
        assertTrue(loser.closeCalls > 0)
        assertEquals(MdocTransport.State.CLOSED, loser.state.value)
    }

    @Test
    fun duplicateFailedTransportClosesAndThrows() = runTest {
        val transport = TestTransport()
        val result = CompletableDeferred<MdocTransport>()
        val job = launch {
            try {
                result.complete(listOf(transport, transport).waitForConnection(senderKey))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                result.completeExceptionally(e)
            }
        }
        runCurrent()

        transport.failOpen()
        runCurrent()

        assertTrue(job.isCompleted)
        assertFailsWith<IllegalStateException> { result.await() }
        assertEquals(1, transport.openCalls)
        assertTrue(transport.closeCalls > 0)
    }

    @Test
    fun closesLosingTransportsWhenWinnerConnects() = runTest {
        val winner = TestTransport()
        val loser = TestTransport()
        val deferred = async { listOf(winner, loser).waitForConnection(senderKey) }
        runCurrent()

        winner.finishOpen.complete(Unit)
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertSame(winner, deferred.await())
        assertEquals(MdocTransport.State.CONNECTED, winner.state.value)
        assertEquals(0, winner.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, loser.state.value)
        assertTrue(loser.openCancelled)
        assertTrue(loser.closeCalls > 0)
    }

    @Test
    fun transportFailingDuringConnectingDoesNotPreventOtherTransportFromConnecting() = runTest {
        val failing = TestTransport()
        val winner = TestTransport()
        val deferred = async { listOf(failing, winner).waitForConnection(senderKey) }
        runCurrent()

        failing.state.value = MdocTransport.State.CONNECTING
        runCurrent()
        assertFalse(deferred.isCompleted)
        assertEquals(0, winner.closeCalls)

        failing.failOpen()
        runCurrent()
        assertFalse(deferred.isCompleted)

        winner.finishOpen.complete(Unit)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertSame(winner, deferred.await())
        assertEquals(MdocTransport.State.CONNECTED, winner.state.value)
        assertEquals(0, winner.closeCalls)
        assertTrue(failing.closeCalls > 0)
    }

    @Test
    fun cancellationClosesAllTransports() = runTest {
        val first = TestTransport()
        val second = TestTransport()
        val deferred = async { listOf(first, second).waitForConnection(senderKey) }
        runCurrent()

        deferred.cancel()
        runCurrent()

        assertTrue(deferred.isCancelled)
        for (transport in listOf(first, second)) {
            assertTrue(transport.openCancelled)
            assertEquals(1, transport.closeCalls)
            assertEquals(MdocTransport.State.CLOSED, transport.state.value)
        }
    }

    @Test
    fun cancellationClosesDuplicateTransports() = runTest {
        val first = TestTransport()
        val second = TestTransport()
        val deferred = async { listOf(first, second, first, second).waitForConnection(senderKey) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        deferred.cancel()
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertTrue(deferred.isCancelled)
        assertFailsWith<CancellationException> { deferred.await() }
        for (transport in listOf(first, second)) {
            assertEquals(1, transport.openCalls)
            assertTrue(transport.openCancelled)
            assertTrue(transport.closeCalls > 0)
            assertEquals(MdocTransport.State.CLOSED, transport.state.value)
        }
    }

    @Test
    fun allFailedTransportsCloseAndThrow() = runTest {
        val first = TestTransport()
        val second = TestTransport()
        val result = CompletableDeferred<MdocTransport>()
        val job = launch {
            try {
                result.complete(listOf(first, second).waitForConnection(senderKey))
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        runCurrent()

        first.failOpen()
        second.failOpen()
        runCurrent()
        job.join()

        assertFailsWith<IllegalStateException> { result.await() }
        assertTrue(first.closeCalls > 0)
        assertTrue(second.closeCalls > 0)
    }

    @Test
    fun nfcOpenReturningIdleWaitsForSelectApdu() = runTest {
        val nfc = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        )
        val other = TestTransport()
        try {
            val deferred = async { listOf(nfc, other).waitForConnection(senderKey) }
            runCurrent()
            assertFalse(deferred.isCompleted)
            assertEquals(MdocTransport.State.IDLE, nfc.state.value)

            nfc.processApdu(
                CommandApdu(
                    0,
                    Nfc.INS_SELECT,
                    Nfc.INS_SELECT_P1_APPLICATION,
                    0,
                    Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID,
                    0
                ),
                sendResponse = {}
            )
            runCurrent()

            assertTrue(deferred.isCompleted)
            assertSame(nfc, deferred.await())
            assertEquals(MdocTransport.State.CONNECTED, nfc.state.value)
            assertTrue(other.openCancelled)
            assertTrue(other.closeCalls > 0)
        } finally {
            nfc.close()
        }
    }

    @Test
    fun invalidInitialStateThrowsWithoutOpening() = runTest {
        val first = TestTransport()
        val invalid = TestTransport().apply { state.value = MdocTransport.State.CONNECTED }
        assertFailsWith<IllegalStateException> {
            listOf(first, invalid).waitForConnection(senderKey)
        }
        assertFalse(first.openStarted)
        assertFalse(invalid.openStarted)
    }

    @Test
    fun advertiseFailureClosesPreviouslyCreatedTransports() = runTest {
        val first = TestTransport()
        val second = TestTransport().apply {
            advertiseError = IllegalStateException("BLE advertising failed")
        }
        val transports = ArrayDeque(listOf(first, second))
        val factory = object : MdocTransportFactory {
            override fun createTransport(
                connectionMethod: MdocConnectionMethod,
                role: MdocRole,
                options: MdocTransportOptions
            ): MdocTransport = transports.removeFirst()
        }
        val methods = listOf(
            MdocConnectionMethodNfc(0xffff, 0x10000),
            MdocConnectionMethodNfc(0xffff, 0x10000),
        )

        assertFailsWith<IllegalStateException> {
            methods.advertise(
                role = MdocRole.MDOC,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
        }

        assertEquals(1, first.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, first.state.value)
        assertEquals(1, second.closeCalls)
    }

    @Test
    fun advertiseCancellationClosesCreatedTransports() = runTest {
        val first = TestTransport()
        val secondWait = CompletableDeferred<Unit>()
        val second = TestTransport().apply {
            finishAdvertise = secondWait
        }
        val transports = ArrayDeque(listOf(first, second))
        val factory = object : MdocTransportFactory {
            override fun createTransport(
                connectionMethod: MdocConnectionMethod,
                role: MdocRole,
                options: MdocTransportOptions
            ): MdocTransport = transports.removeFirst()
        }
        val methods = listOf(
            MdocConnectionMethodNfc(0xffff, 0x10000),
            MdocConnectionMethodNfc(0xffff, 0x10000),
        )

        val deferred = async {
            methods.advertise(
                role = MdocRole.MDOC,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
        }
        runCurrent()
        assertEquals(MdocTransport.State.ADVERTISING, first.state.value)
        assertFalse(deferred.isCompleted)

        deferred.cancel()
        runCurrent()

        assertTrue(deferred.isCancelled)
        assertEquals(1, first.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, first.state.value)
        assertEquals(1, second.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, second.state.value)
    }

    private class TestTransport : MdocTransport() {
        override val state = MutableStateFlow(State.IDLE)
        override val role = MdocRole.MDOC
        override val connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        override val scanningTime: Duration? = null
        val finishOpen = CompletableDeferred<Unit>()
        var finishAdvertise: CompletableDeferred<Unit>? = null
        var advertiseError: Exception? = null
        var openStarted = false
        var openCalls = 0
        var openCancelled = false
        var closeCalls = 0

        fun failOpen() {
            state.value = State.FAILED
            finishOpen.completeExceptionally(IllegalStateException("Synthetic connection failure"))
        }

        override suspend fun advertise() {
            advertiseError?.let {
                state.value = State.FAILED
                throw it
            }
            finishAdvertise?.await()
            state.value = State.ADVERTISING
        }

        override suspend fun open(eSenderKey: EcPublicKey) {
            openStarted = true
            openCalls += 1
            try {
                finishOpen.await()
                state.value = State.CONNECTED
            } catch (error: CancellationException) {
                openCancelled = true
                throw error
            }
        }

        override suspend fun close() {
            yield()
            closeCalls += 1
            if (state.value != State.FAILED) {
                state.value = State.CLOSED
            }
        }

        override suspend fun sendMessage(message: ByteArray) {
            throw UnsupportedOperationException()
        }

        override suspend fun waitForMessage(): ByteArray {
            throw UnsupportedOperationException()
        }
    }
}
