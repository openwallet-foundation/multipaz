package org.multipaz.mdoc.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
import org.multipaz.mdoc.transport.NfcHybridTransportMdocReader
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.NfcTagReader
import org.multipaz.nfc.ResponseApdu
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Unit tests for [NfcTagReader.scanMdocReader].
 *
 * ### Test Architecture
 * [scanMdocReader] orchestrates NFC engagement on the reader side by:
 * 1. Pre-creating and calling [MdocTransport.advertise] on all `negotiatedHandoverConnectionMethods`
 *    ("warmed-up transports") before starting the NFC scan session.
 * 2. Invoking [NfcTagReader.scan], which polls tapped [NfcIsoTag] instances and runs [mdocReaderNfcHandover]
 *    inside `tagInteractionFunc` until a non-null result is returned.
 * 3. Selecting or creating the appropriate [MdocTransport] (reusing a warmed-up transport on Negotiated Handover,
 *    creating a new transport on Static Handover, or wrapping in [NfcHybridTransportMdocReader] on NFCv2 Handover),
 *    and closing all unused warmed-up transports.
 * 4. Running `onHandover` either:
 *    - **Inside** `tagInteractionFunc` for NFCv2 NFC-only engagements ([MdocConnectionMethodNfcV2]), so that if
 *      the tag is lost mid-transaction, the NFC dialog stays open and automatically retries on the next tap.
 *    - **Outside** [NfcTagReader.scan] for all other engagement types, so the NFC dialog is dismissed immediately
 *      after the initial tap.
 *
 * To test all of these flows in `commonTest` without platform NFC or BLE hardware, this suite uses:
 * - [FakeNfcTagReader]: Simulates an [NfcTagReader] session that feeds a queue of [NfcIsoTag] taps into
 *   `tagInteractionFunc` while tracking whether the NFC scanning dialog (`isScanning`) is currently active.
 * - [FakeMdocTransport] & [FakeMdocTransportFactory]: Tracks `createTransport()`, `advertise()`, and `close()`
 *   lifecycle events on reader transports, while returning a real [NfcTransportMdocReader] when
 *   [MdocConnectionMethodNfc] is selected so `setTag()` can be verified.
 * - Loopback [NfcIsoTag] implementations backed by [MdocNfcEngagementHelper] (for NDEF Static and Negotiated
 *   Handover) and [MdocNfcV2EngagementHelper] (for NFCv2 Handover).
 */
class ScanMdocReaderTest {

    /**
     * Fake [MdocTransport] that records whether [advertise] and [close] were called.
     */
    private class FakeMdocTransport(
        override val role: MdocRole,
        override val connectionMethod: MdocConnectionMethod,
    ) : MdocTransport() {
        private val _state = MutableStateFlow(State.IDLE)
        override val state: StateFlow<State> = _state.asStateFlow()
        override val scanningTime: Duration? = null

        var advertised: Boolean = false
            private set
        var closed: Boolean = false
            private set

        override suspend fun advertise() {
            advertised = true
            _state.value = State.ADVERTISING
        }

        override suspend fun open(eSenderKey: EcPublicKey) {
            _state.value = State.CONNECTED
        }

        override suspend fun sendMessage(message: ByteArray) {}

        override suspend fun waitForMessage(): ByteArray = byteArrayOf()

        override suspend fun close() {
            closed = true
            _state.value = State.CLOSED
        }
    }

    /**
     * Fake [MdocTransportFactory] that records every transport it creates.
     *
     * For [MdocConnectionMethodNfc], it creates a real [NfcTransportMdocReader] so that
     * [scanMdocReader]'s `if (transport is NfcTransportMdocReader) transport.setTag(tag)` branch is exercised.
     * For all other connection methods (such as [MdocConnectionMethodBle]), it creates a [FakeMdocTransport].
     */
    private class FakeMdocTransportFactory(
        private val onBeforeScanAssert: (() -> Unit)? = null
    ) : MdocTransportFactory {
        val createdTransports = mutableListOf<MdocTransport>()
        val createdFakeTransports = mutableListOf<FakeMdocTransport>()

        override fun createTransport(
            connectionMethod: MdocConnectionMethod,
            role: MdocRole,
            options: MdocTransportOptions
        ): MdocTransport {
            val transport = if (connectionMethod is MdocConnectionMethodNfc) {
                NfcTransportMdocReader(role, options, connectionMethod)
            } else {
                FakeMdocTransport(role, connectionMethod).also {
                    createdFakeTransports.add(it)
                }
            }
            createdTransports.add(transport)
            return transport
        }

        fun assertWarmedUpBeforeScan() {
            onBeforeScanAssert?.invoke()
        }
    }

    /**
     * Fake [NfcTagReader] that steps through [tags] in order, invoking `tagInteractionFunc` for each tag
     * until `tagInteractionFunc` returns a non-null result.
     *
     * If [throwPromptDismissed] is `true` (or if all queued [tags] return `null` from `tagInteractionFunc`),
     * [scan] throws [PromptDismissedException] to simulate the user dismissing the NFC scan dialog.
     */
    private class FakeNfcTagReader(
        private val tags: List<NfcIsoTag> = emptyList(),
        private val throwPromptDismissed: Boolean = false,
        private val onScanStarted: (() -> Unit)? = null,
    ) : NfcTagReader {
        var isScanning: Boolean = false
            private set
        var tagsPresentedCount: Int = 0
            private set
        var lastScanMessage: String? = null
            private set

        override val external: Boolean = false
        override val dialogAlwaysShown: Boolean = true

        override suspend fun <T : Any> scan(
            message: String?,
            tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
            options: NfcScanOptions,
            context: CoroutineContext
        ): T {
            lastScanMessage = message
            onScanStarted?.invoke()
            if (throwPromptDismissed) {
                throw PromptDismissedException()
            }
            isScanning = true
            try {
                for (tag in tags) {
                    tagsPresentedCount += 1
                    val result = tagInteractionFunc(tag)
                    if (result != null) {
                        return result
                    }
                }
                throw PromptDismissedException()
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Loopback [NfcIsoTag] backed by [MdocNfcEngagementHelper] (for NDEF Static or Negotiated Handover).
     */
    private class NdefLoopbackIsoTag(
        private val engagementHelper: MdocNfcEngagementHelper
    ) : NfcIsoTag() {
        var closed: Boolean = false
            private set

        override val maxTransceiveLength: Int = 65536

        override suspend fun close() {
            closed = true
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            return engagementHelper.processApdu(command)
        }
    }

    /**
     * Loopback [NfcIsoTag] backed by [MdocNfcV2EngagementHelper] (for NFCv2 Handover).
     *
     * @param failAfterHandoverWithTagLost if `true`, any `ENVELOPE` command after handover completion
     *   throws [NfcTagLostException] to simulate the user removing the phone prematurely during data transfer.
     */
    private class NfcV2LoopbackIsoTag(
        private val engagementHelper: MdocNfcV2EngagementHelper,
        private val failAfterHandoverWithTagLost: Boolean = false,
    ) : NfcIsoTag() {
        var closed: Boolean = false
            private set
        private var envelopeCommandsSeen: Int = 0

        override val maxTransceiveLength: Int = 65536

        override suspend fun close() {
            closed = true
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            if (command.ins == Nfc.INS_ENVELOPE) {
                envelopeCommandsSeen += 1
                if (failAfterHandoverWithTagLost && envelopeCommandsSeen > 1) {
                    throw NfcTagLostException("Tag lost during NFCv2 data transfer")
                }
            }
            return engagementHelper.processApdu(command)
        }
    }

    /**
     * Loopback [NfcIsoTag] that rejects all `SELECT APPLICATION` commands with `FILE_NOT_FOUND` (0x6A82),
     * simulating a non-mdoc NFC tag (such as a payment card or transit card) tapped by mistake.
     */
    private class NonMdocIsoTag : NfcIsoTag() {
        var closed: Boolean = false
            private set

        override val maxTransceiveLength: Int = 256

        override suspend fun close() {
            closed = true
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            return ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
        }
    }

    private fun createBleCentralClientMethod(uuid: UUID = UUID(1UL, 2UL)): MdocConnectionMethodBle =
        MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = uuid,
        )

    private fun createBlePeripheralServerMethod(uuid: UUID = UUID(1UL, 2UL)): MdocConnectionMethodBle =
        MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = uuid,
            centralClientModeUuid = null,
        )

    private fun createNfcMethod(): MdocConnectionMethodNfc =
        MdocConnectionMethodNfc(
            commandDataFieldMaxLength = 0xffff,
            responseDataFieldMaxLength = 0x10000
        )

    @Test
    fun negotiatedHandoverReusesWarmedUpTransportAndClosesUnusedAndTag() = runTest {
        val bleUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")
        val bleCc = createBleCentralClientMethod(bleUuid)
        val blePs = createBlePeripheralServerMethod(bleUuid)
        val negotiatedMethods = listOf(bleCc, blePs)

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _ -> },
            onError = { error -> fail("Unexpected engagement error: $error") },
            negotiatedHandoverPicker = { methods ->
                // Select the first method (bleCc)
                methods.first()
            }
        )
        val tag = NdefLoopbackIsoTag(engagementHelper)

        lateinit var factory: FakeMdocTransportFactory
        val reader = FakeNfcTagReader(
            tags = listOf(tag),
            onScanStarted = {
                // Verify both negotiated handover transports were created and advertised BEFORE scan() started
                assertEquals(2, factory.createdFakeTransports.size)
                assertTrue(factory.createdFakeTransports.all { it.advertised })
                assertTrue(factory.createdFakeTransports.none { it.closed })
            }
        )
        factory = FakeMdocTransportFactory()

        val result = reader.scanMdocReader(
            message = "Hold near mdoc",
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(),
            transportFactory = factory,
            selectConnectionMethod = { fail("selectConnectionMethod should not be called for single method") },
            negotiatedHandoverConnectionMethods = negotiatedMethods,
            context = coroutineContext
        )

        assertNotNull(result)
        assertEquals(MdocHandoverType.NEGOTIATED_HANDOVER, result.type)
        // Verify no extra transport was created after handover
        assertEquals(2, factory.createdFakeTransports.size)
        val warmedUpCc = factory.createdFakeTransports[0]
        val warmedUpPs = factory.createdFakeTransports[1]

        // The chosen transport (bleCc) is reused and remains open; the unused one (blePs) is closed
        assertSame(warmedUpCc, result.transport)
        assertFalse(warmedUpCc.closed)
        assertTrue(warmedUpPs.closed)
        // Because a BLE transport was chosen (not NfcTransportMdocReader), the NFC tag is closed
        assertTrue(tag.closed)
    }

    @Test
    fun staticHandoverSelectsNfcTransportSetsTagAndClosesWarmedUpTransports() = runTest {
        val bleUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")
        val bleCc = createBleCentralClientMethod(bleUuid)
        val blePs = createBlePeripheralServerMethod(bleUuid)
        val nfcMethod = createNfcMethod()
        val staticMethods = listOf(bleCc, blePs, nfcMethod)

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _ -> },
            onError = { error -> fail("Unexpected engagement error: $error") },
            staticHandoverMethods = staticMethods
        )
        val tag = NdefLoopbackIsoTag(engagementHelper)
        val factory = FakeMdocTransportFactory()
        val reader = FakeNfcTagReader(tags = listOf(tag))

        var presentedMethods: List<MdocConnectionMethod>? = null
        val result = reader.scanMdocReader(
            message = "Hold near mdoc",
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(),
            transportFactory = factory,
            selectConnectionMethod = { methods ->
                presentedMethods = methods
                // Choose NFC data transfer
                methods.filterIsInstance<MdocConnectionMethodNfc>().first()
            },
            // Offer bleCc for negotiated handover so 1 warmed-up transport is created prior to scanning
            negotiatedHandoverConnectionMethods = listOf(bleCc),
            context = coroutineContext
        )

        assertNotNull(result)
        assertEquals(MdocHandoverType.STATIC_HANDOVER, result.type)
        assertEquals(staticMethods, presentedMethods)

        // The warmed-up BLE transport went unused and must be closed
        assertEquals(1, factory.createdFakeTransports.size)
        assertTrue(factory.createdFakeTransports[0].closed)

        // The returned transport is the newly created NfcTransportMdocReader, and tag was passed to setTag()
        // rather than closed.
        val nfcTransport = assertIs<NfcTransportMdocReader>(result.transport)
        assertEquals(nfcMethod, nfcTransport.connectionMethod)
        assertFalse(tag.closed)
    }

    @Test
    fun staticHandoverContinuesPollingOnNonMdocTagOrNullSelection() = runTest {
        val bleUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")
        val bleCc = createBleCentralClientMethod(bleUuid)
        val blePs = createBlePeripheralServerMethod(bleUuid)
        val staticMethods = listOf(bleCc, blePs)
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)

        val nonMdocTag = NonMdocIsoTag()
        val staticTag1 = NdefLoopbackIsoTag(
            MdocNfcEngagementHelper(
                eDeviceKey = eDeviceKey.publicKey,
                onHandoverComplete = { _, _, _ -> },
                onError = { error -> fail("Unexpected engagement error: $error") },
                staticHandoverMethods = staticMethods
            )
        )
        val staticTag2 = NdefLoopbackIsoTag(
            MdocNfcEngagementHelper(
                eDeviceKey = eDeviceKey.publicKey,
                onHandoverComplete = { _, _, _ -> },
                onError = { error -> fail("Unexpected engagement error: $error") },
                staticHandoverMethods = staticMethods
            )
        )

        val factory = FakeMdocTransportFactory()
        val reader = FakeNfcTagReader(tags = listOf(nonMdocTag, staticTag1, staticTag2))

        var selectCalls = 0
        val result = reader.scanMdocReader(
            message = "Scan mdoc",
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(),
            transportFactory = factory,
            selectConnectionMethod = { methods ->
                selectCalls += 1
                if (selectCalls == 1) {
                    // Reject the first static tag by returning null; scanner should keep polling
                    null
                } else {
                    methods.last()
                }
            },
            negotiatedHandoverConnectionMethods = listOf(bleCc),
            context = coroutineContext
        )

        assertNotNull(result)
        assertEquals(3, reader.tagsPresentedCount)
        assertEquals(2, selectCalls)
        assertEquals(blePs, result.transport.connectionMethod)
        assertTrue(staticTag2.closed)
        // Warmed-up bleCc transport was closed once staticTag2 succeeded with blePs
        assertTrue(factory.createdFakeTransports.first().closed)
    }

    @Test
    fun nfcV2HandoverWithBleWrapsWarmedUpTransportAndRunsOnHandoverOutsideScan() = runTest {
        val bleUuid = UUID(10UL, 20UL)
        val bleCc = createBleCentralClientMethod(bleUuid)
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)

        val engagementHelper = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _ -> },
            onMessageReceived = {},
            onError = { error -> fail("Unexpected engagement error: $error") },
            handoverPicker = { methods ->
                // Pick the BLE method offered by the reader
                methods.filterIsInstance<MdocConnectionMethodBle>().first()
            }
        )
        val tag = NfcV2LoopbackIsoTag(engagementHelper)
        val factory = FakeMdocTransportFactory()
        val reader = FakeNfcTagReader(tags = listOf(tag))

        var wasScanningDuringOnHandover: Boolean? = null
        val customResult = reader.scanMdocReader(
            message = "Hold near mdoc",
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(useNfcV2 = true),
            transportFactory = factory,
            selectConnectionMethod = { fail("Single method should not invoke selectConnectionMethod") },
            negotiatedHandoverConnectionMethods = listOf(bleCc),
            context = coroutineContext,
            onHandover = { scanResult ->
                wasScanningDuringOnHandover = reader.isScanning
                assertEquals(MdocHandoverType.V2_HANDOVER, scanResult.type)
                val hybridTransport = assertIs<NfcHybridTransportMdocReader>(scanResult.transport)
                assertEquals(bleCc, hybridTransport.connectionMethod)
                "ble-hybrid-complete"
            }
        )

        assertEquals("ble-hybrid-complete", customResult)
        // For NFCv2 with BLE, the NFC scanning dialog is dismissed immediately after the first tap,
        // so onHandover runs AFTER scan() has finished (isScanning == false).
        assertEquals(false, wasScanningDuringOnHandover)
        // The warmed-up BLE transport was reused inside NfcHybridTransportMdocReader, not closed
        assertEquals(1, factory.createdFakeTransports.size)
        assertFalse(factory.createdFakeTransports.first().closed)
    }

    @Test
    fun nfcV2NfcOnlyRetriesOnTagLostAndSucceedsOnSecondTap() = runTest {
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

        // Tap 1: Wallet engagement helper whose tag loses connection right after handover when the reader
        // sends the DeviceRequest ENVELOPE APDU
        val helperTap1 = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _ -> },
            onMessageReceived = {},
            onError = { error -> fail("Unexpected error on Tap 1: $error") },
            handoverPicker = { methods -> methods.first() }
        )
        val tagTap1 = NfcV2LoopbackIsoTag(
            engagementHelper = helperTap1,
            failAfterHandoverWithTagLost = true
        )

        // Tap 2: Wallet engagement helper wired to a real NfcHybridTransportMdoc for end-to-end APDU exchange
        lateinit var mdocTransportTap2: NfcHybridTransportMdoc
        lateinit var helperTap2: MdocNfcV2EngagementHelper
        helperTap2 = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _ -> },
            onMessageReceived = { message ->
                mdocTransportTap2.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Unexpected error on Tap 2: $error") },
            handoverPicker = { methods -> methods.first() }
        )
        mdocTransportTap2 = NfcHybridTransportMdoc(
            sendMessageViaNfc = { message ->
                helperTap2.sendMessage(message)
                true
            }
        )
        mdocTransportTap2.open(eDeviceKey.publicKey)
        val tagTap2 = NfcV2LoopbackIsoTag(helperTap2)

        val reader = FakeNfcTagReader(tags = listOf(tagTap1, tagTap2))
        var onHandoverAttempts = 0
        val scanningStatesDuringHandover = mutableListOf<Boolean>()
        val seenTransports = mutableListOf<MdocTransport>()

        val outcome = reader.scanMdocReader(
            message = "Keep holding phone near reader",
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(useNfcV2 = true),
            transportFactory = FakeMdocTransportFactory(),
            selectConnectionMethod = { methods -> methods.first() },
            negotiatedHandoverConnectionMethods = emptyList(),
            context = coroutineContext,
            onHandover = { scanResult ->
                onHandoverAttempts += 1
                scanningStatesDuringHandover.add(reader.isScanning)
                seenTransports.add(scanResult.transport)
                assertIs<MdocConnectionMethodNfcV2>(scanResult.transport.connectionMethod)

                val request = Cbor.encode(
                    buildCborMap {
                        put("request", Bstr(byteArrayOf(0x01, 0x02)))
                    }
                )
                val response = Cbor.encode(
                    buildCborMap {
                        put("response", Bstr(byteArrayOf(0x03, 0x04)))
                    }
                )

                scanResult.transport.open(eReaderKey.publicKey)
                if (onHandoverAttempts == 2) {
                    // Queue the mdoc's response before sending the ENVELOPE command on Tap 2
                    mdocTransportTap2.sendMessage(response)
                }
                scanResult.transport.sendMessage(request)
                // On Tap 1, waitForMessage() throws MdocTransportException caused by NfcTagLostException.
                // scanMdocReader() catches this via isTagLostOrTransportClosed(), closes Tap 1's transport,
                // and keeps scanning for Tap 2.
                val receivedResponse = scanResult.transport.waitForMessage()
                assertContentEquals(request, mdocTransportTap2.waitForMessage())
                assertContentEquals(response, receivedResponse)
                scanResult.transport.close()
                "nfcv2-retry-succeeded"
            }
        )

        assertEquals("nfcv2-retry-succeeded", outcome)
        assertEquals(2, onHandoverAttempts)
        assertEquals(2, reader.tagsPresentedCount)
        // For NFCv2 NFC-only, onHandover executes INSIDE the active scan loop on both taps
        assertEquals(listOf(true, true), scanningStatesDuringHandover)
        // Tap 1's transport failed with NfcTagLostException and its tag was closed when scanMdocReader cleaned up
        assertEquals(MdocTransport.State.FAILED, seenTransports[0].state.value)
        assertTrue(tagTap1.closed)
        // Tap 2's transport completed and was closed cleanly
        assertEquals(MdocTransport.State.CLOSED, seenTransports[1].state.value)
    }

    @Test
    fun nfcV2NfcOnlyRethrowsNonTransportExceptionAndClosesTransport() = runTest {
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val helper = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, _, _ -> },
            onMessageReceived = {},
            onError = { error -> fail("Unexpected engagement error: $error") },
            handoverPicker = { methods -> methods.first() }
        )
        val tag = NfcV2LoopbackIsoTag(helper)
        val reader = FakeNfcTagReader(tags = listOf(tag))

        val exception = assertFailsWith<IllegalArgumentException> {
            reader.scanMdocReader<String>(
                message = "Keep holding phone near reader",
                options = MdocTransportOptions(),
                handoverOptions = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                transportFactory = FakeMdocTransportFactory(),
                selectConnectionMethod = { methods -> methods.first() },
                negotiatedHandoverConnectionMethods = emptyList(),
                context = coroutineContext,
                onHandover = {
                    throw IllegalArgumentException("Invalid application parameter")
                }
            )
        }
        assertEquals("Invalid application parameter", exception.message)
        assertTrue(tag.closed)
    }

    @Test
    fun promptDismissedClosesWarmedUpTransportsAndReturnsNull() = runTest {
        val bleUuid = UUID(1UL, 2UL)
        val bleCc = createBleCentralClientMethod(bleUuid)
        val blePs = createBlePeripheralServerMethod(bleUuid)

        val factory = FakeMdocTransportFactory()
        val reader = FakeNfcTagReader(throwPromptDismissed = true)

        val result = reader.scanMdocReader(
            message = "Hold near mdoc",
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(),
            transportFactory = factory,
            selectConnectionMethod = { null },
            negotiatedHandoverConnectionMethods = listOf(bleCc, blePs),
            context = coroutineContext
        )

        assertNull(result)
        // Both warmed-up transports must be advertised before scan() and closed in the finally block
        assertEquals(2, factory.createdFakeTransports.size)
        assertTrue(factory.createdFakeTransports.all { it.advertised })
        assertTrue(factory.createdFakeTransports.all { it.closed })
    }
}
