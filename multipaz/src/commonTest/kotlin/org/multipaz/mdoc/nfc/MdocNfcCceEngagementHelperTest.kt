package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.NfcCceTransportMdoc
import org.multipaz.mdoc.transport.NfcCceTransportMdocReader
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MdocNfcCceEngagementHelperTest"

/**
 * Unit tests for [MdocNfcCceEngagementHelper] (wallet-side NFC CCE engagement and data transfer) and
 * [mdocReaderNfcHandover] / [NfcCceTransportMdocReader] / [NfcCceTransportMdoc] according to
 * ISO/IEC 18013-5 Second Edition Clause 9.5 (AID `A0000002480401`).
 *
 * ### How ISO 18013-5 Second Edition NFC CCE Engagement works
 * Unlike the 2021 NDEF engagement (which uses Type 4 Tag `READ BINARY` / `UPDATE BINARY` commands
 * on AID `D2760000850101` followed by a separate `SELECT APPLICATION` for NFC data transfer on AID
 * `A0000002480400`), NFC CCE uses a single application (`A0000002480401`) for both engagement and
 * data transfer:
 * 1. **Application selection (`SELECT APPLICATION`)**:
 *    - The reader selects `A0000002480401`.
 *    - [MdocNfcCceEngagementHelper] responds with `9000` and a CBOR `CCESelectResponse` map
 *      `{0: apduCommandMaxSize, 1: apduResponseMaxSize}`.
 * 2. **Handover exchange (`INS_ENVELOPE` #1)**:
 *    - The reader sends a CBOR `CCEHandoverRequest` `{0: ReaderEngagement}` encapsulated
 *      in BER-TLV `DO'53'`, containing `EReaderKey` and any offered secondary connection methods.
 *    - [MdocNfcCceEngagementHelper] invokes `handoverPicker` to select at most one secondary
 *      connection method (or `null` for NFC-only), builds `DeviceEngagement` (omitting
 *      `DeviceRetrievalMethods` for NFC-only), invokes `onHandoverComplete`, and returns the CBOR
 *      `CCEHandoverSelect` `{0: DeviceEngagement}` in `DO'53'`.
 *    - If the mdoc omitted the BLE UUID in `DeviceEngagement` (because the UUID was supplied by the
 *      reader in `CCEHandoverRequest`), [mdocReaderNfcHandover] supplements the reader's BLE UUID back
 *      onto the returned [MdocConnectionMethodBle].
 * 3. **Concurrent / NFC-only data transfer (`INS_ENVELOPE` #2+)**:
 *    - Subsequent `ENVELOPE` APDUs on the same selected application carry encrypted session messages
 *      (`DeviceRequest` / `DeviceResponse` / session termination) between
 *      [NfcCceTransportMdocReader] and [NfcCceTransportMdoc] (via
 *      [MdocNfcCceEngagementHelper.onMessageReceived] and [MdocNfcCceEngagementHelper.sendMessage]),
 *      running in parallel with any negotiated secondary BLE transport.
 *
 * ### Test doubles used in this file
 * - [LoopbackIsoTag]: In-memory [NfcIsoTag] that forwards [CommandApdu] calls to
 *   [MdocNfcCceEngagementHelper.processApdu], records a full APDU transcript, tracks `close()` calls
 *   (`closeCount` and `closedDeferred`), and supports simulating mid-transaction tag loss via
 *   `failNextTransceive`.
 * - [LoopbackTransport]: Pair of in-memory [Channel]-connected [MdocTransport] instances used to
 *   simulate the negotiated secondary BLE transport between [NfcCceTransportMdocReader] and
 *   [NfcCceTransportMdoc].
 */
class MdocNfcCceEngagementHelperTest {

    /**
     * In-memory [NfcIsoTag] that routes [transceive] calls to [MdocNfcCceEngagementHelper.processApdu],
     * records the APDU transcript, and tracks `close()` invocations.
     */
    class LoopbackIsoTag(val engagementHelper: MdocNfcCceEngagementHelper): NfcIsoTag() {
        val transcript = StringBuilder()
        var failNextTransceive = false
        var closeCount = 0
        val closedDeferred = CompletableDeferred<Unit>()

        override val maxTransceiveLength: Int
            get() = 65536

        override suspend fun close() {
            closeCount++
            transcript.appendLine("close")
            closedDeferred.complete(Unit)
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            if (failNextTransceive) {
                throw NfcTagLostException("Tag was lost during transceive")
            }
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

    /**
     * Verifies end-to-end NFC CCE engagement and data transfer in NFC-only mode, including:
     * - Handover negotiation over `A0000002480401` with `CCESelectResponse` and `CCEHandover`.
     * - Request, response, and session termination message exchange between
     *   [NfcCceTransportMdocReader] and [NfcCceTransportMdoc].
     * - Deferred tag closing (500ms grace period) when `close()` is called immediately after sending
     *   the session termination message, and idempotency when `close()` is called multiple times.
     */
    @Test
    fun testNfcOnly() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        lateinit var mdocReaderTransport: NfcCceTransportMdocReader
        lateinit var mdocTransport: NfcCceTransportMdoc

        var handoverComplete = false
        var receivedHandover: DataItem? = null
        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertNull(connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                receivedHandover = handover
                handoverComplete = true
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { connectionMethods ->
                assertTrue(connectionMethods.isEmpty())
                null
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = emptyList(),
            options = MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey)
        )
        assertNotNull(handoverResult)
        assertTrue(handoverResult.connectionMethods.isEmpty())
        assertEquals(MdocHandoverType.CCE_HANDOVER, handoverResult.type)
        assertEquals(eReaderKey, handoverResult.eReaderKey)
        assertEquals(65535, handoverResult.apduCommandMaxSize)
        assertEquals(65536, handoverResult.apduResponseMaxSize)
        assertEquals(receivedHandover, handoverResult.handover)

        assertTrue(handoverComplete)

        mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = null,
            apduCommandMaxSize = handoverResult.apduCommandMaxSize,
            apduResponseMaxSize = handoverResult.apduResponseMaxSize,
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                engagementHelper.sendMessage(message)
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
        // Calling close() twice immediately after sending session termination (as reader flows do via try/finally)
        // must not close the tag prematurely before the 500ms grace period delivers the termination APDU.
        mdocReaderTransport.close()
        mdocReaderTransport.close()
        assertEquals(0, tag.closeCount)
        assertContentEquals(sessionTermination, mdocTransport.waitForMessage())
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                tag.closedDeferred.await()
            }
        }
        assertEquals(1, tag.closeCount)
        mdocTransport.close()

        assertEquals(
            """
                CommandApdu(cla=0, ins=164, p1=4, p2=0, payload=ByteString(size=7 hex=a0000002480401), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=11 hex=a20019ffff011a00010000))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=92 hex=535aa100a20063312e31018201d818584ba401022001215820db1b6d2cc5e6baebd12fb76d4fa40957659832e41cade15db9038f37ef5ba321225820b0361e208471bb94a687089c4957fc4d9983abe04cdeeb155bcd690640137727), le=65536)
                ResponseApdu(status=36864, payload=ByteString(size=100 hex=5362a100a40063312e31018201d818584ba401022001215820711b420f708e686917b799e5991346c318a6bd89ac32d04e78cb537cd89dcaa92258201b99557fca9a63617246669eababc7a36068db058e9c98b067a5eba3233b451c058006a203f504f5))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=12 hex=530aa1646461746143010203), le=65530)
                ResponseApdu(status=36864, payload=ByteString(size=13 hex=530ba164646174614401020304))
                CommandApdu(cla=0, ins=195, p1=0, p2=0, payload=ByteString(size=11 hex=5309a16673746174757314), le=65530)
                close
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }

    /**
     * In-memory [MdocTransport] backed by a pair of [Channel]s to simulate a secondary BLE transport
     * alongside NFC CCE.
     */
    class LoopbackTransport(
        override val role: MdocRole,
        override val connectionMethod: MdocConnectionMethod,
        val incomingMessages: Channel<ByteString>,
        val outgoingMessages: Channel<ByteString>
    ): MdocTransport() {

        val mutableState = MutableStateFlow<State>(State.IDLE)

        override val state = mutableState.asStateFlow()

        override val scanningTime: Duration?
            get() = null

        override suspend fun advertise() {}

        private var closed = false

        override suspend fun close() {
            closed = true
        }

        override suspend fun open(eSenderKey: EcPublicKey) {
            mutableState.value = State.CONNECTED
        }

        override suspend fun sendMessage(message: ByteArray) {
            outgoingMessages.send(ByteString(message))
        }

        override suspend fun waitForMessage(): ByteArray {
            return incomingMessages.receive().toByteArray()
        }
    }

    /**
     * Verifies NFC CCE handover where the reader offers BLE Central Client Mode, the mdoc selects
     * the BLE method, and both [NfcCceTransportMdocReader] and [NfcCceTransportMdoc] exchange
     * messages with the secondary BLE [LoopbackTransport] attached.
     */
    @Test
    fun testWithBle() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        lateinit var mdocReaderTransport: NfcCceTransportMdocReader
        lateinit var mdocTransport: NfcCceTransportMdoc

        val methods = listOf(MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = UUID(1UL, 2UL),
            peripheralServerModePsm = 192,
            peripheralServerModeMacAddress = null
        ))

        var handoverComplete = false
        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(methods.first(), connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                handoverComplete = true
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { connectionMethods ->
                assertEquals(1, connectionMethods.size)
                assertEquals(methods.first(), connectionMethods[0])
                // Pick the BLE method
                connectionMethods[0]
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = methods,
            options = MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.CCE_HANDOVER, handoverResult.type)
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

        mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = bleMdocReaderTransport
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcCceTransportMdoc(
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
    }

    /**
     * Verifies that when the mdoc selects BLE Central Client Mode during NFC CCE handover and omits the
     * `centralClientModeUuid` from its `DeviceEngagement` (per ISO 18013-5 Second Edition, since the
     * reader already provided the UUID in `CCEHandoverRequest`), [mdocReaderNfcHandover] supplements the
     * reader's UUID onto the returned [MdocConnectionMethodBle] while preserving the raw
     * `encodedDeviceEngagement` bytes for `SessionTranscript`.
     */
    @Test
    fun testWithBleOmitUuidCentralClientMode() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        val bleUuid = UUID(10UL, 20UL)
        val readerMethods = listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = bleUuid,
                peripheralServerModePsm = 192,
                peripheralServerModeMacAddress = null
            )
        )

        // The mdoc selects BLE Central Client Mode, but omits the UUID from DeviceEngagement
        val mdocBleMethodWithoutUuid = MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = null,
            peripheralServerModePsm = 192,
            peripheralServerModeMacAddress = null
        )

        lateinit var mdocReaderTransport: NfcCceTransportMdocReader
        lateinit var mdocTransport: NfcCceTransportMdoc

        var handoverComplete = false
        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                handoverComplete = true
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { connectionMethods ->
                assertEquals(1, connectionMethods.size)
                assertEquals(readerMethods.first(), connectionMethods[0])
                // Pick the BLE method without UUID
                mdocBleMethodWithoutUuid
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = readerMethods,
            options = MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.CCE_HANDOVER, handoverResult.type)
        assertEquals(1, handoverResult.connectionMethods.size)

        // Verify that the reader supplemented the UUID from readerMethods
        val supplementedMethod = handoverResult.connectionMethods[0] as MdocConnectionMethodBle
        assertNull(supplementedMethod.peripheralServerModeUuid)
        assertEquals(bleUuid, supplementedMethod.centralClientModeUuid)
        assertEquals(192, supplementedMethod.peripheralServerModePsm)

        // Verify that raw DeviceEngagement received from the mdoc had no UUID
        val deFromMdoc = DeviceEngagement.fromDataItem(
            Cbor.decode(handoverResult.encodedDeviceEngagement.toByteArray())
        )
        assertEquals(1, deFromMdoc.connectionMethods.size)
        val deBleMethod = deFromMdoc.connectionMethods[0] as MdocConnectionMethodBle
        assertNull(deBleMethod.centralClientModeUuid)
        assertNull(deBleMethod.peripheralServerModeUuid)

        assertTrue(handoverComplete)

        val mdocToReader = Channel<ByteString>(Channel.UNLIMITED)
        val readerToMdoc = Channel<ByteString>(Channel.UNLIMITED)

        val bleMdocReaderTransport = LoopbackTransport(
            role = MdocRole.MDOC_READER,
            connectionMethod = supplementedMethod,
            incomingMessages = mdocToReader,
            outgoingMessages = readerToMdoc
        )

        val bleMdocTransport = LoopbackTransport(
            role = MdocRole.MDOC,
            connectionMethod = supplementedMethod,
            incomingMessages = readerToMdoc,
            outgoingMessages = mdocToReader
        )

        mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = bleMdocReaderTransport
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                engagementHelper.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)
        mdocTransport.setTransport(bleMdocTransport)

        val request = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(10, 20, 30)))
        })
        mdocReaderTransport.sendMessage(request)
        assertContentEquals(request, mdocTransport.waitForMessage())

        val response = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(10, 20, 30, 40)))
        })
        mdocTransport.sendMessage(response)
        assertContentEquals(response, mdocReaderTransport.waitForMessage())
    }

    /**
     * Verifies that when the mdoc selects BLE Peripheral Server Mode during NFC CCE handover and omits
     * the `peripheralServerModeUuid` from its `DeviceEngagement`, [mdocReaderNfcHandover] supplements
     * the reader's UUID onto the returned [MdocConnectionMethodBle].
     */
    @Test
    fun testWithBleOmitUuidPeripheralServerMode() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        val bleUuid = UUID(30UL, 40UL)
        val readerMethods = listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = false,
                peripheralServerModeUuid = bleUuid,
                centralClientModeUuid = null,
                peripheralServerModePsm = null,
                peripheralServerModeMacAddress = null
            )
        )

        // The mdoc selects BLE Peripheral Server Mode, but omits the UUID from DeviceEngagement
        val mdocBleMethodWithoutUuid = MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = null,
            centralClientModeUuid = null,
            peripheralServerModePsm = null,
            peripheralServerModeMacAddress = null
        )

        lateinit var mdocReaderTransport: NfcCceTransportMdocReader
        lateinit var mdocTransport: NfcCceTransportMdoc

        var handoverComplete = false
        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                handoverComplete = true
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { connectionMethods ->
                assertEquals(1, connectionMethods.size)
                assertEquals(readerMethods.first(), connectionMethods[0])
                mdocBleMethodWithoutUuid
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = readerMethods,
            options = MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.CCE_HANDOVER, handoverResult.type)
        assertEquals(1, handoverResult.connectionMethods.size)

        // Verify that the reader supplemented the UUID from readerMethods
        val supplementedMethod = handoverResult.connectionMethods[0] as MdocConnectionMethodBle
        assertEquals(bleUuid, supplementedMethod.peripheralServerModeUuid)
        assertNull(supplementedMethod.centralClientModeUuid)

        // Verify that raw DeviceEngagement received from the mdoc had no UUID
        val deFromMdoc = DeviceEngagement.fromDataItem(
            Cbor.decode(handoverResult.encodedDeviceEngagement.toByteArray())
        )
        val deBleMethod = deFromMdoc.connectionMethods[0] as MdocConnectionMethodBle
        assertNull(deBleMethod.peripheralServerModeUuid)
        assertNull(deBleMethod.centralClientModeUuid)

        assertTrue(handoverComplete)

        val mdocToReader = Channel<ByteString>(Channel.UNLIMITED)
        val readerToMdoc = Channel<ByteString>(Channel.UNLIMITED)

        val bleMdocReaderTransport = LoopbackTransport(
            role = MdocRole.MDOC_READER,
            connectionMethod = supplementedMethod,
            incomingMessages = mdocToReader,
            outgoingMessages = readerToMdoc
        )

        val bleMdocTransport = LoopbackTransport(
            role = MdocRole.MDOC,
            connectionMethod = supplementedMethod,
            incomingMessages = readerToMdoc,
            outgoingMessages = mdocToReader
        )

        mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = bleMdocReaderTransport
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                engagementHelper.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)
        mdocTransport.setTransport(bleMdocTransport)

        val request = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(5, 6, 7)))
        })
        mdocReaderTransport.sendMessage(request)
        assertContentEquals(request, mdocTransport.waitForMessage())

        val response = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(5, 6, 7, 8)))
        })
        mdocTransport.sendMessage(response)
        assertContentEquals(response, mdocReaderTransport.waitForMessage())
    }

    /**
     * Verifies that when the mdoc returns a combined [MdocConnectionMethodBle] supporting both
     * Central Client Mode and Peripheral Server Mode with omitted UUIDs, [mdocReaderNfcHandover]
     * supplements the reader's UUID for both modes and disambiguates the result into two separate
     * [MdocConnectionMethodBle] entries for `MDOC_READER`.
     */
    @Test
    fun testWithBleOmitUuidBothModes() = runTest {
        val eDeviceKey = getEDeviceKey()

        val bleUuid = UUID(50UL, 60UL)
        val readerMethods = listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = bleUuid,
                centralClientModeUuid = bleUuid,
                peripheralServerModePsm = 192,
                peripheralServerModeMacAddress = null
            )
        )

        // The mdoc returns BLE with both modes supported and no UUID in DeviceEngagement
        val mdocBleMethodWithoutUuid = MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = null,
            peripheralServerModePsm = 192,
            peripheralServerModeMacAddress = null
        )

        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _, _ -> },
            onMessageReceived = {},
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { _ ->
                mdocBleMethodWithoutUuid
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = readerMethods,
            options = MdocReaderNfcHandoverOptions(useCce = true)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.CCE_HANDOVER, handoverResult.type)

        // Disambiguation for MDOC_READER splits the combined BLE into central client and peripheral server modes
        assertEquals(2, handoverResult.connectionMethods.size)

        val centralClientMethod = handoverResult.connectionMethods[0] as MdocConnectionMethodBle
        assertTrue(centralClientMethod.supportsCentralClientMode)
        assertFalse(centralClientMethod.supportsPeripheralServerMode)
        assertEquals(bleUuid, centralClientMethod.centralClientModeUuid)
        assertNull(centralClientMethod.peripheralServerModeUuid)
        assertNull(centralClientMethod.peripheralServerModePsm) // PSM is on peripheral server mode for reader

        val peripheralServerMethod = handoverResult.connectionMethods[1] as MdocConnectionMethodBle
        assertFalse(peripheralServerMethod.supportsCentralClientMode)
        assertTrue(peripheralServerMethod.supportsPeripheralServerMode)
        assertNull(peripheralServerMethod.centralClientModeUuid)
        assertEquals(bleUuid, peripheralServerMethod.peripheralServerModeUuid)
        assertEquals(192, peripheralServerMethod.peripheralServerModePsm)

        // Verify that raw DeviceEngagement had no UUIDs
        val deFromMdoc = DeviceEngagement.fromDataItem(
            Cbor.decode(handoverResult.encodedDeviceEngagement.toByteArray())
        )
        assertEquals(1, deFromMdoc.connectionMethods.size)
        val deBleMethod = deFromMdoc.connectionMethods[0] as MdocConnectionMethodBle
        assertNull(deBleMethod.centralClientModeUuid)
        assertNull(deBleMethod.peripheralServerModeUuid)
    }

    /**
     * Verifies that if the mdoc explicitly includes its own BLE UUID in `DeviceEngagement` during
     * NFC CCE handover, [mdocReaderNfcHandover] preserves the mdoc's UUID rather than overwriting it
     * with the reader's UUID.
     */
    @Test
    fun testWithBleMdocProvidesOwnUuid() = runTest {
        val eDeviceKey = getEDeviceKey()

        val readerUuid = UUID(100UL, 101UL)
        val mdocUuid = UUID(200UL, 201UL)
        val readerMethods = listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = false,
                peripheralServerModeUuid = readerUuid,
                centralClientModeUuid = null,
            )
        )

        // The mdoc provides its own UUID in DeviceEngagement
        val mdocBleMethodWithUuid = MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = mdocUuid,
            centralClientModeUuid = null,
        )

        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _, _ -> },
            onMessageReceived = {},
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { _ ->
                mdocBleMethodWithUuid
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = readerMethods,
            options = MdocReaderNfcHandoverOptions(useCce = true)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.CCE_HANDOVER, handoverResult.type)
        assertEquals(1, handoverResult.connectionMethods.size)

        // Verify that mdoc's UUID is kept and not overwritten by readerUuid
        val cm = handoverResult.connectionMethods[0] as MdocConnectionMethodBle
        assertEquals(mdocUuid, cm.peripheralServerModeUuid)
        assertNull(cm.centralClientModeUuid)
    }

    /**
     * Verifies that during an NFC CCE + BLE session, if the holder removes their phone from the
     * NFC field (`tag.failNextTransceive = true`), [NfcCceTransportMdocReader] immediately closes
     * the [NfcIsoTag] (so platform NFC scanning UI such as the iOS system sheet is dismissed right
     * away), seamlessly completes the transaction over the secondary BLE transport, and does not close
     * the tag a second time when [NfcCceTransportMdocReader.close] is called at the end.
     */
    @Test
    fun testTagClosedOnNfcDisconnectWithBleFallback() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        lateinit var mdocReaderTransport: NfcCceTransportMdocReader
        lateinit var mdocTransport: NfcCceTransportMdoc

        val methods = listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = UUID(1UL, 2UL),
                peripheralServerModePsm = 192,
                peripheralServerModeMacAddress = null
            )
        )

        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _, _ -> },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { connectionMethods ->
                connectionMethods.first()
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = methods,
            options = MdocReaderNfcHandoverOptions(useCce = true)
        )
        assertNotNull(handoverResult)
        assertEquals(0, tag.closeCount)

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

        mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = bleMdocReaderTransport
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                engagementHelper.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)
        mdocTransport.setTransport(bleMdocTransport)

        // Simulate the holder leaving the NFC field before DeviceRequest is sent.
        tag.failNextTransceive = true

        val request = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(1, 2, 3)))
        })
        mdocReaderTransport.sendMessage(request)

        // The NFC tag must be closed immediately upon NFC transceive failure, before the BLE transaction completes.
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                tag.closedDeferred.await()
            }
        }
        assertEquals(1, tag.closeCount)
        assertTrue(mdocReaderTransport.stats.nfcDisconnectedDuringTransaction)

        // Meanwhile, the transaction continues and succeeds over BLE.
        assertContentEquals(request, mdocTransport.waitForMessage())
        val response = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(1, 2, 3, 4)))
        })
        mdocTransport.sendMessage(response)
        assertContentEquals(response, mdocReaderTransport.waitForMessage())

        // Closing the CCE transport afterwards must be idempotent and not close the tag a second time.
        mdocReaderTransport.close()
        assertEquals(1, tag.closeCount)
        assertEquals(MdocTransport.State.CLOSED, mdocReaderTransport.state.value)
        mdocTransport.close()
    }

    /**
     * Verifies that during an NFC CCE NFC-only session (`negotiatedTransport = null`), if the NFC tag
     * disconnects mid-transaction, [NfcCceTransportMdocReader] immediately closes the [NfcIsoTag],
     * transitions to [MdocTransport.State.FAILED], throws [MdocTransportException] from
     * `waitForMessage()`, and does not close the tag a second time when `close()` is called afterward.
     */
    @Test
    fun testTagClosedOnNfcDisconnectWithoutBle() = runTest {
        val eDeviceKey = getEDeviceKey()
        val eReaderKey = getEReaderKey()

        lateinit var mdocReaderTransport: NfcCceTransportMdocReader
        lateinit var mdocTransport: NfcCceTransportMdoc

        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _, _ -> },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            handoverPicker = { _ ->
                null
            },
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = emptyList(),
            options = MdocReaderNfcHandoverOptions(useCce = true)
        )
        assertNotNull(handoverResult)
        assertEquals(0, tag.closeCount)

        mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = null
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                engagementHelper.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        tag.failNextTransceive = true

        val request = Cbor.encode(buildCborMap {
            put("data", Bstr(byteArrayOf(1, 2, 3)))
        })
        mdocReaderTransport.sendMessage(request)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                tag.closedDeferred.await()
            }
        }
        assertEquals(1, tag.closeCount)

        assertFailsWith<MdocTransportException> {
            mdocReaderTransport.waitForMessage()
        }
        assertEquals(MdocTransport.State.FAILED, mdocReaderTransport.state.value)

        // Calling close() after failure must be idempotent and not close the tag a second time.
        mdocReaderTransport.close()
        assertEquals(1, tag.closeCount)
        mdocTransport.close()
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
