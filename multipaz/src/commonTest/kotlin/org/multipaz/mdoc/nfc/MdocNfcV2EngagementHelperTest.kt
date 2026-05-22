package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
import org.multipaz.mdoc.transport.NfcHybridTransportMdocReader
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration

private const val TAG = "MdocNfcV2EngagementHelperTest"

/**
 * This test is mainly for verifying the correct functionality of MdocNfcV2EngagementHelper (the wallet side)
 * but it also exercises and verifies mdocReaderNfcHandover() and other things on the reader side.
 */
class MdocNfcV2EngagementHelperTest {

    // Provides access to MdocNfcV2EngagementHelper via NfcIsoTag abstraction
    class LoopbackIsoTag(val engagementHelper: MdocNfcV2EngagementHelper): NfcIsoTag() {
        val transcript = StringBuilder()

        override val maxTransceiveLength: Int
            get() = 65536

        override suspend fun close() {
            transcript.appendLine("close")
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            transcript.appendLine("$command")
            val response = engagementHelper.processApdu(command)
            transcript.appendLine("$response")
            return response
        }
    }

    private suspend fun getEDeviceKey(): EcPrivateKey {
        return EcPrivateKeyDoubleCoordinate(
            curve = EcCurve.P256,
            x = "711b420f708e686917b799e5991346c318a6bd89ac32d04e78cb537cd89dcaa9".fromHex(),
            y = "1b99557fca9a63617246669eababc7a36068db058e9c98b067a5eba3233b451c".fromHex(),
            d = "18c78253d73ceb03088a1b3a9a14317aef2af89ea0d6a435eaac3fc19d5fe02d".fromHex()
        )
    }

    private suspend fun getEReaderKey(): EcPrivateKey {
        return EcPrivateKeyDoubleCoordinate(
            curve = EcCurve.P256,
            x = "db1b6d2cc5e6baebd12fb76d4fa40957659832e41cade15db9038f37ef5ba321".fromHex(),
            y = "b0361e208471bb94a687089c4957fc4d9983abe04cdeeb155bcd690640137727".fromHex(),
            d = "4e1238f4f265bf02e63af384690940ddf67a9d19577119a48e78fcc4a3fed971".fromHex()
        )
    }

    @Test
    fun testNfcOnly() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        lateinit var mdocReaderTransport: NfcHybridTransportMdocReader
        lateinit var mdocTransport: NfcHybridTransportMdoc

        var handoverComplete = false
        val engagementHelper = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                handoverComplete = true
            },
            onMessageReceived = {
                message -> mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            negotiatedHandoverPicker = { connectionMethods ->
                assertEquals(1, connectionMethods.size)
                assertTrue(connectionMethods.first() is MdocConnectionMethodNfcV2)
                connectionMethods.first()
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = emptyList(),
            options = MdocReaderNfcHandoverOptions(useNfcV2 = true)
        )
        assertNotNull(handoverResult)
        assertEquals(1, handoverResult.connectionMethods.size)
        assertEquals(MdocHandoverType.V2_HANDOVER, handoverResult.type)
        assertTrue(handoverResult.connectionMethods.first() is MdocConnectionMethodNfcV2)

        assertTrue(handoverComplete)

        mdocReaderTransport = NfcHybridTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = null
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcHybridTransportMdoc(
            sendMessageViaNfc = {
                message -> engagementHelper.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        val request = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(1, 2, 3)))
        })
        mdocReaderTransport.sendMessage(request)
        assertContentEquals(request, mdocTransport.waitForMessage())

        val response = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(1, 2, 3, 4)))
        })
        mdocTransport.sendMessage(response)
        assertContentEquals(response, mdocReaderTransport.waitForMessage())

        val sessionTermination = SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)

        mdocReaderTransport.sendMessage(sessionTermination)
        assertContentEquals(sessionTermination, mdocTransport.waitForMessage())

        assertEquals(
            """
                CommandApdu(cla=0, ins=164, p1=4, p2=0, payload=ByteString(size=7 hex=a0000002480401), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=7 hex=a1001a00010000))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=17 hex=530fa100a10281830501a1001a00010000), le=65536)
                ResponseApdu(status=36864, payload=ByteString(size=112 hex=536ea100a50063312e31018201d818584ba401022001215820711b420f708e686917b799e5991346c318a6bd89ac32d04e78cb537cd89dcaa92258201b99557fca9a63617246669eababc7a36068db058e9c98b067a5eba3233b451c0281830501a1001a00010000058006a203f504f5))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=12 hex=530aa1646461746143010203), le=65530)
                ResponseApdu(status=36864, payload=ByteString(size=13 hex=530ba164646174614401020304))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=11 hex=5309a16673746174757314), le=65530)
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }

    class LoopbackTransport(
        override val role: MdocRole,
        override val connectionMethod: MdocConnectionMethod,
        val incomingMessages: Channel<ByteString>,
        val outgoingMessages: Channel<ByteString>
    ): MdocTransport() {

        val mutableState = MutableStateFlow<State>(State.IDLE)

        override val state = mutableState.asStateFlow()

        override val scanningTime: Duration?
            get() = TODO("Not yet implemented")

        override suspend fun advertise() {}

        private var closed = false

        override suspend fun close() {
            closed = true
        }

        override suspend fun open(eSenderKey: EcPublicKey) {
        }

        override suspend fun sendMessage(message: ByteArray) {
            outgoingMessages.send(ByteString(message))
        }

        override suspend fun waitForMessage(): ByteArray {
            return incomingMessages.receive().toByteArray()
        }
    }

    @Test
    fun testWithBle() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        lateinit var mdocReaderTransport: NfcHybridTransportMdocReader
        lateinit var mdocTransport: NfcHybridTransportMdoc

        val methods = listOf(MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = UUID(1UL, 2UL),
            peripheralServerModePsm = 192,
            peripheralServerModeMacAddress = null
        ))

        var handoverComplete = false
        val engagementHelper = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                handoverComplete = true
            },
            onMessageReceived = {
                    message -> mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            negotiatedHandoverPicker = { connectionMethods ->
                assertEquals(2, connectionMethods.size)
                assertTrue(connectionMethods.first() is MdocConnectionMethodNfcV2)
                assertEquals(methods.first(), connectionMethods[1])
                // Pick the BLE method
                connectionMethods[1]
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = methods,
            options = MdocReaderNfcHandoverOptions(useNfcV2 = true)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.V2_HANDOVER, handoverResult.type)
        assertEquals(1, handoverResult.connectionMethods.size)
        assertEquals(methods.first(), handoverResult.connectionMethods[0])

        assertTrue(handoverComplete)

        val mdocToReader = Channel<ByteString>(Channel.UNLIMITED)
        val readerToMdoc = Channel<ByteString>(Channel.UNLIMITED)

        val bleMdocReaderTransport = LoopbackTransport(
            role = MdocRole.MDOC_READER,
            connectionMethod = methods[0],
            incomingMessages = mdocToReader,
            outgoingMessages = readerToMdoc
        )

        val bleMdocTransport = LoopbackTransport(
            role = MdocRole.MDOC,
            connectionMethod = methods[0],
            incomingMessages = readerToMdoc,
            outgoingMessages = mdocToReader
        )

        mdocReaderTransport = NfcHybridTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = bleMdocReaderTransport
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcHybridTransportMdoc(
            sendMessageViaNfc = { message ->
                engagementHelper.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)
        mdocTransport.setTransport(bleMdocTransport)

        val request = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(1, 2, 3)))
        })
        mdocReaderTransport.sendMessage(request)
        assertContentEquals(request, mdocTransport.waitForMessage())

        val response = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(1, 2, 3, 4)))
        })
        mdocTransport.sendMessage(response)
        assertContentEquals(response, mdocReaderTransport.waitForMessage())

        val sessionTermination = SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)

        mdocReaderTransport.sendMessage(sessionTermination)
        assertContentEquals(sessionTermination, mdocTransport.waitForMessage())

        mdocTransport.sendMessage(sessionTermination)
        assertContentEquals(sessionTermination, mdocReaderTransport.waitForMessage())

        /* TODO
        assertEquals(
            """
                CommandApdu(cla=0, ins=164, p1=4, p2=0, payload=ByteString(size=7 hex=a0000002480401), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=5 hex=a10019fde8))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=19 hex=5311a100a10281830501a20019fde80119fde8), le=65536)
                ResponseApdu(status=36864, payload=ByteString(size=106 hex=5368a100a30063312e30018201d818584ba401022001215820711b420f708e686917b799e5991346c318a6bd89ac32d04e78cb537cd89dcaa92258201b99557fca9a63617246669eababc7a36068db058e9c98b067a5eba3233b451c0281830501a20019fde80119fde8))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=12 hex=530aa1646461746143010203), le=65530)
                ResponseApdu(status=36864, payload=ByteString(size=13 hex=530ba164646174614401020304))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=11 hex=5309a16673746174757314), le=65530)
                ResponseApdu(status=36864, payload=ByteString(size=11 hex=5309a16673746174757314))
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
         */
    }
}

fun CoroutineScope.pipeChannels(
    source: ReceiveChannel<ByteString>,
    destination: SendChannel<ByteString>
) = launch {
    try {
        // consumeEach automatically cancels the source channel if an exception
        // is thrown inside the block (e.g., if the destination closes early).
        source.consumeEach { chunk ->
            destination.send(chunk)
        }

        // If the source finishes normally, close the destination normally.
        destination.close()
    } catch (e: Throwable) {
        // If anything fails, close the destination with the exception.
        destination.close(e)
    }
}