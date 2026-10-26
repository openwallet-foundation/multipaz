package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
import org.multipaz.mdoc.transport.NfcHybridTransportMdocReader
import org.multipaz.mdoc.transport.NfcTransportMdoc
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcApduRouter
import org.multipaz.nfc.NfcApduService
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.ResponseApdu
import org.multipaz.presentment.Iso18013PresentmentNfcDisconnectedException
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentOutcome
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.prompt.PassphrasePromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.Reason
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.truncateToWholeSeconds
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [MdocNdefService] and [MdocNfcV2Service] verifying NFC engagement,
 * detached BLE/hybrid transaction lifecycle, error handling, and multi-tap session
 * superseding (GitHub issue #2056).
 *
 * ### How the test harness works
 * - **[ServiceLoopbackIsoTag]:** Implements [NfcIsoTag] on the reader side and forwards each
 *   [CommandApdu] directly to [NfcApduService.processCommandApdu]. Both [MdocNdefService] and
 *   [MdocNfcV2Service] return the initial `SELECT APPLICATION` response synchronously from
 *   `processCommandApdu()` and deliver all subsequent responses asynchronously via the
 *   `sendResponse` callback (from an internal coroutine running on [Dispatchers.Default]).
 *   [ServiceLoopbackIsoTag.transceive] handles both paths transparently so
 *   [mdocReaderNfcHandover] can drive a full static or negotiated NFC handover against the
 *   service under test.
 * - **[FakeBleTransport]:** Injected via [MdocTransportFactory] in service settings to intercept
 *   transport creation, record ordered lifecycle events (`advertise`, `open`, `waitForMessage`,
 *   `sendMessage`, `close`), and expose [CompletableDeferred] latches (`openStarted`,
 *   `finishOpen`, `waitForMessageStarted`, `closedDeferred`) so tests can deterministically
 *   pause a session in `open()` or `waitForMessage()` and observe cancellation and cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MdocNfcServiceTest {

    private class TestPromptModel private constructor(builder: Builder) : PromptModel(builder) {
        override val promptModelScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + this)

        fun cancel() {
            promptModelScope.cancel()
        }

        class Builder : PromptModel.Builder(
            toHumanReadable = { _, _ ->
                Reason.HumanReadable(
                    title = "Authenticate",
                    subtitle = "Authenticate to present mDL",
                    requireConfirmation = false,
                )
            }
        ) {
            override fun build(): TestPromptModel = TestPromptModel(this)
        }
    }

    private fun createTestPromptModel(): TestPromptModel =
        TestPromptModel.Builder().apply { addCommonDialogs() }.build()

    private val allCreatedBleTransports = mutableListOf<FakeBleTransport>()
    private val allCreatedHybridTransports = mutableListOf<NfcHybridTransportMdoc>()
    private val allCreatedReaderTransports = mutableListOf<NfcHybridTransportMdocReader>()
    private val allCreatedReaderSessions = mutableListOf<ReaderSession>()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        allCreatedBleTransports.clear()
        allCreatedHybridTransports.clear()
        allCreatedReaderTransports.clear()
        allCreatedReaderSessions.clear()
        MdocNfcV2Service.onHybridTransportCreated = { transport ->
            allCreatedHybridTransports.add(transport)
        }
    }

    @AfterTest
    fun tearDown() = runTest {
        withContext(Dispatchers.Default) {
            try {
                MdocNfcPresentmentService.transactionJob?.let { job ->
                    withTimeout(5.seconds) {
                        job.join()
                    }
                    assertTrue(
                        job.isCompleted,
                        "Expected MdocNfcPresentmentService.transactionJob to be completed at end of test"
                    )
                }
                allCreatedBleTransports.toList().forEach { transport ->
                    assertTrue(
                        transport.closeCalls > 0,
                        "Expected FakeBleTransport '${transport.name}' to have been closed"
                    )
                    assertTrue(
                        transport.state.value == MdocTransport.State.CLOSED ||
                            transport.state.value == MdocTransport.State.FAILED,
                        "Expected FakeBleTransport '${transport.name}' to be CLOSED or FAILED, got ${transport.state.value}"
                    )
                }
                allCreatedHybridTransports.toList()
                    .forEachIndexed { index, transport ->
                        assertEquals(
                            MdocTransport.State.CLOSED,
                            transport.state.value,
                            "Expected NfcHybridTransportMdoc #$index to be CLOSED at end of test"
                        )
                    }
                allCreatedReaderTransports.toList()
                    .forEachIndexed { index, transport ->
                        assertTrue(
                            transport.state.value == MdocTransport.State.CLOSED ||
                                transport.state.value == MdocTransport.State.FAILED,
                            "Expected NfcHybridTransportMdocReader #$index to be CLOSED or FAILED at end of test, got ${transport.state.value}"
                        )
                    }
                assertNull(
                    MdocNfcV2Service.hybridTransport,
                    "Expected MdocNfcV2Service.hybridTransport to be null at end of test"
                )
                assertNull(
                    MdocNfcV2Service.activeEngagementParamsFlow,
                    "Expected MdocNfcV2Service.activeEngagementParamsFlow to be null at end of test"
                )
                assertNull(
                    MdocNfcV2Service.activeDeviceRequest,
                    "Expected MdocNfcV2Service.activeDeviceRequest to be null at end of test"
                )
                assertNull(
                    MdocNfcV2Service.activeEngagement,
                    "Expected MdocNfcV2Service.activeEngagement to be null at end of test"
                )
            } finally {
                allCreatedReaderTransports.toList().forEach { transport ->
                    transport.close()
                }
                allCreatedHybridTransports.toList().forEach { transport ->
                    transport.close()
                }
                allCreatedReaderSessions.toList().forEach { session ->
                    session.sessionEncryption.close()
                    session.eReaderKey.close()
                }
                MdocNfcV2Service.onHybridTransportCreated = null
                MdocNfcV2Service.hybridTransport = null
                MdocNfcV2Service.activeEngagementParamsFlow = null
                MdocNfcV2Service.activeDeviceRequest = null
                MdocNfcV2Service.activeEngagement = null
                MdocNfcPresentmentService.transactionJob?.cancel()
                MdocNfcPresentmentService.transactionJob = null
                Dispatchers.resetMain()
            }
        }
    }

    private suspend fun createPresentmentSource(): PresentmentSource {
        val storage = EphemeralStorage()
        val secureAreaRepository = SecureAreaRepository.Builder().build()
        val documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        return SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = DocumentTypeRepository(),
            showConsentPromptFn = ::promptModelSilentConsent,
        )
    }

    /**
     * Loopback [NfcIsoTag] that connects [mdocReaderNfcHandover] directly to an
     * [NfcApduService] instance without requiring Android NFC hardware.
     *
     * When [transceive] is called by the reader:
     * 1. It invokes [NfcApduService.processCommandApdu].
     * 2. If the service returns a synchronous response (used for the fast `SELECT APPLICATION`
     *    reply), it decodes and returns that immediately.
     * 3. Otherwise, it awaits the asynchronous response emitted by the service's
     *    `sendResponse` callback via [responseChannel].
     */
    private class ServiceLoopbackIsoTag(
        override val maxTransceiveLength: Int = 65536
    ) : NfcIsoTag() {
        val responseChannel = Channel<ResponseApdu>(Channel.UNLIMITED)
        var service: NfcApduService? = null
        var combinedService: NfcApduRouter? = null

        @Volatile
        var chainedEnvelopeCount: Int = 0

        @Volatile
        var getResponseCount: Int = 0

        override suspend fun close() {}

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            if (command.ins == Nfc.INS_ENVELOPE && command.cla == Nfc.CLA_CHAIN_NOT_LAST) {
                chainedEnvelopeCount += 1
            }
            if (command.ins == Nfc.INS_GET_RESPONSE) {
                getResponseCount += 1
            }
            val syncResponse = combinedService?.processCommandApdu(command)
                ?: service?.let {
                    it.receiveCommandApdu(command)
                    null
                }
            return syncResponse ?: withTimeout(5.seconds) {
                responseChannel.receive()
            }
        }

        fun deactivate(reason: NfcDeactivationReason = NfcDeactivationReason.LINK_LOSS) {
            combinedService?.deactivate(reason)
            service?.deactivate(reason)
            responseChannel.close(NfcTagLostException("NFC tag left the field"))
        }
    }

    /**
     * Controllable [MdocTransport] stand-in for BLE transports created by [MdocNdefService]
     * and [MdocNfcV2Service].
     *
     * By default, [open] suspends on [finishOpen] (simulating a BLE transport advertising/scanning
     * while waiting for the reader to connect over BLE). Tests can either:
     * - Leave [finishOpen] incomplete and trigger a second NFC tap or UI cancellation to verify
     *   that an in-flight `open()` is cancelled (`openCancelled == true`) and `close()` is called, or
     * - Complete [finishOpen] so the service enters `Iso18013Presentment` and suspends in
     *   [waitForMessage] (`waitForMessageStarted`).
     *
     * Every lifecycle call appends `"$name.<method>"` to the shared [events] list so tests can
     * assert strict ordering across multiple transports (for example, verifying that Tap 1's
     * transport is closed before Tap 2's transport starts advertising or opening).
     */
    private inner class FakeBleTransport(
        override val connectionMethod: MdocConnectionMethod,
        val name: String,
        val events: MutableList<String>,
        private val advertiseError: Exception? = null,
    ) : MdocTransport() {
        init {
            allCreatedBleTransports.add(this)
        }

        override val state = MutableStateFlow(State.IDLE)
        override val role = MdocRole.MDOC
        override val scanningTime: Duration? = null

        val advertiseStarted = CompletableDeferred<Unit>()
        val openStarted = CompletableDeferred<Unit>()
        val finishOpen = CompletableDeferred<Unit>()
        val waitForMessageStarted = CompletableDeferred<Unit>()
        val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)
        val sentMessages = mutableListOf<ByteArray>()
        val closedDeferred = CompletableDeferred<Unit>()
        var openCancelled = false
        var waitForMessageCancelled = false
        var closeCalls = 0

        override suspend fun advertise() {
            events.add("$name.advertise")
            advertiseStarted.complete(Unit)
            advertiseError?.let {
                state.value = State.FAILED
                throw it
            }
            state.value = State.ADVERTISING
        }

        override suspend fun open(eSenderKey: EcPublicKey) {
            events.add("$name.open")
            openStarted.complete(Unit)
            try {
                finishOpen.await()
                state.value = State.CONNECTED
            } catch (e: CancellationException) {
                openCancelled = true
                throw e
            }
        }

        override suspend fun close() {
            events.add("$name.close")
            closeCalls += 1
            if (state.value != State.FAILED) {
                state.value = State.CLOSED
            }
            closedDeferred.complete(Unit)
        }

        override suspend fun sendMessage(message: ByteArray) {
            events.add("$name.sendMessage")
            sentMessages.add(message)
        }

        override suspend fun waitForMessage(): ByteArray {
            events.add("$name.waitForMessage")
            waitForMessageStarted.complete(Unit)
            return try {
                incomingMessages.receive()
            } catch (e: CancellationException) {
                waitForMessageCancelled = true
                throw e
            }
        }
    }

    private fun createNdefService(
        tag: ServiceLoopbackIsoTag,
        settingsProvider: suspend () -> MdocNdefService.Settings,
    ): MdocNdefService {
        return object : MdocNdefService() {
            override suspend fun getSettings(): Settings = settingsProvider()
        }.apply {
            sendResponseCallback = { responseApdu -> tag.responseChannel.trySend(responseApdu) }
        }
    }

    private fun createNfcV2Service(
        tag: ServiceLoopbackIsoTag,
        settingsProvider: suspend () -> MdocNfcV2Service.Settings,
    ): MdocNfcV2Service {
        return object : MdocNfcV2Service() {
            override suspend fun getSettings(): Settings = settingsProvider()
        }.apply {
            sendResponseCallback = { responseApdu -> tag.responseChannel.trySend(responseApdu) }
        }
    }

    /**
     * Verifies Bug 1 of issue #2056: when [MdocNdefService] advertises multiple connection
     * methods in its detached transaction coroutine (here BLE Central Client mode + BLE
     * Peripheral Server mode) and `advertise()` throws on one of them (e.g., Bluetooth is
     * turned off):
     * 1. The exception is caught inside `startTransaction()` rather than escaping as an
     *    uncaught exception in the detached `CoroutineScope(Dispatchers.Default)` and crashing the app.
     * 2. Any transport that had already started advertising (`firstTransport`) is closed by
     *    `List<MdocConnectionMethod>.advertise()`.
     * 3. The shared [PresentmentModel] transitions to [PresentmentModel.State.Completed] with
     *    the underlying exception.
     */
    @Test
    fun ndefServiceAdvertiseFailureDoesNotCrashAndSetsCompletedError() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()

            lateinit var firstTransport: FakeBleTransport
            lateinit var secondTransport: FakeBleTransport
            var transportIndex = 0

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    transportIndex += 1
                    return if (transportIndex == 1) {
                        FakeBleTransport(connectionMethod, "t1", events).also { firstTransport = it }
                    } else {
                        FakeBleTransport(
                            connectionMethod = connectionMethod,
                            name = "t2",
                            events = events,
                            advertiseError = IllegalStateException("Bluetooth is turned off"),
                        ).also { secondTransport = it }
                    }
                }
            }

            val tag = ServiceLoopbackIsoTag()
            val service = createNdefService(tag) {
                MdocNdefService.Settings(
                    source = source,
                    promptModel = promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    useNegotiatedHandover = false,
                    negotiatedHandoverPreferredOrder = emptyList(),
                    staticHandoverBleCentralClientModeEnabled = true,
                    staticHandoverBlePeripheralServerModeEnabled = true,
                    staticHandoverNfcDataTransferEnabled = false,
                    transportOptions = MdocTransportOptions(),
                    transportFactory = transportFactory,
                )
            }
            tag.service = service
            service.start()

            try {
                val handoverResult = mdocReaderNfcHandover(
                    tag = tag,
                    negotiatedHandoverConnectionMethods = emptyList(),
                )
                assertNotNull(handoverResult)

                val completedState = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed

                assertNotNull(completedState.error)
                assertEquals("Bluetooth is turned off", completedState.error?.message)
                assertEquals(1, firstTransport.closeCalls)
                assertEquals(MdocTransport.State.CLOSED, firstTransport.state.value)
                assertEquals(1, secondTransport.closeCalls)
            } finally {
                service.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies Bug 2 of issue #2056 across recreated `HostApduService` instances:
     * 1. Tap 1 completes NFC static handover on `service1` and starts a detached BLE session
     *    (`transport1` suspended in `open()`).
     * 2. The phone leaves the NFC field (`service1.deactivate(NfcDeactivationReason.LINK_LOSS)`) and Android destroys
     *    `service1` (`service1.stop()`), while `transport1` continues waiting for a BLE
     *    connection in the background.
     * 3. The user taps the reader a second time, causing Android to instantiate `service2`
     *    (`service2.start()`).
     * 4. Because `transactionJob` is tracked in [NfcApduService]'s companion object, `service2`
     *    cancels and joins Tap 1's `transactionJob` before starting Tap 2:
     *    - `transport1.open()` is cancelled and `transport1.close()` completes *before*
     *      `transport2.advertise()` is invoked.
     *    - Because Tap 1 was cancelled with `TransactionSupersededException`, Tap 1 does not
     *      call `presentmentModel.setCompleted(...)`, leaving the shared [PresentmentModel]
     *      in [PresentmentModel.State.Connecting] for Tap 2.
     */
    @Test
    fun ndefServiceSecondTapAcrossRecreatedServiceInstancesClosesFirstBleSession() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    val index = createdTransports.size + 1
                    return FakeBleTransport(connectionMethod, "tap$index", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            fun buildSettings() = MdocNdefService.Settings(
                source = source,
                promptModel = promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                useNegotiatedHandover = false,
                negotiatedHandoverPreferredOrder = emptyList(),
                staticHandoverBleCentralClientModeEnabled = false,
                staticHandoverBlePeripheralServerModeEnabled = true,
                staticHandoverNfcDataTransferEnabled = false,
                transportOptions = MdocTransportOptions(),
                transportFactory = transportFactory,
            )

            // Tap 1 on service1
            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNdefService(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            val handover1 = mdocReaderNfcHandover(tag1, emptyList())
            assertNotNull(handover1)
            val transport1 = withTimeout(5.seconds) {
                while (createdTransports.size < 1) {
                    delay(10.milliseconds)
                }
                createdTransports[0]
            }
            withTimeout(5.seconds) { transport1.openStarted.await() }

            // Simulate NFC deactivation and Android destroying service1
            service1.deactivate(NfcDeactivationReason.LINK_LOSS)
            service1.stop()

            // Tap 2 on newly created service2
            val tag2 = ServiceLoopbackIsoTag()
            val service2 = createNdefService(tag2) { buildSettings() }
            tag2.service = service2
            service2.start()

            try {
                val handover2 = mdocReaderNfcHandover(tag2, emptyList())
                assertNotNull(handover2)
                val transport2 = withTimeout(5.seconds) {
                    while (createdTransports.size < 2) {
                        delay(10.milliseconds)
                    }
                    createdTransports[1]
                }
                withTimeout(5.seconds) { transport2.openStarted.await() }

                // Verify transport1 was cancelled and closed before transport2 advertised
                assertTrue(transport1.openCancelled)
                assertTrue(transport1.closeCalls > 0)
                assertEquals(MdocTransport.State.CLOSED, transport1.state.value)
                val closeIdx = events.indexOf("tap1.close")
                val advertise2Idx = events.indexOf("tap2.advertise")
                assertTrue(closeIdx in 0..<advertise2Idx, "Expected tap1.close before tap2.advertise in $events")

                // Shared PresentmentModel must still be active for tap2 (not Completed with cancellation)
                assertEquals(PresentmentModel.State.Connecting, presentmentModel.state.value)

                // Clean up tap2 via UI cancellation
                presentmentModel.setCanceledByUser()
                withTimeout(5.seconds) { transport2.closedDeferred.await() }
            } finally {
                service2.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies two additional multi-tap scenarios on the *same* [MdocNdefService] instance
     * (when Android reuses the existing `HostApduService` rather than recreating it):
     * 1. **Reused instance after deactivation:** Tap 1 starts `transport1`, `onDeactivated(0)`
     *    fires without `onDestroy()`, and Tap 2 arrives on the same `service` instance.
     *    Verifies `transport1` is cancelled and closed and `transport2` starts cleanly.
     * 2. **Mid-hold re-selection (no `onDeactivated`):** While the phone remains in the RF
     *    field after Tap 2, the reader sends a new `SELECT APPLICATION` APDU to start Tap 3
     *    without an intervening `onDeactivated()`. Verifies [MdocNdefService] resets its
     *    in-progress engagement state, supersedes `transport2`, and starts `transport3`.
     * 3. **UI cancellation after NFC deactivation:** Verifies that calling
     *    [PresentmentModel.setCanceledByUser] after `onDeactivated()` cancels `transport3` via
     *    the `uiCancelJob` running inside `startTransaction()`.
     */
    @Test
    fun ndefServiceSecondTapOnSameReusedInstanceAndMidHoldClosesPreviousSession() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    val index = createdTransports.size + 1
                    return FakeBleTransport(connectionMethod, "tap$index", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            val tag = ServiceLoopbackIsoTag()
            val service = createNdefService(tag) {
                MdocNdefService.Settings(
                    source = source,
                    promptModel = promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    useNegotiatedHandover = false,
                    negotiatedHandoverPreferredOrder = emptyList(),
                    staticHandoverBleCentralClientModeEnabled = false,
                    staticHandoverBlePeripheralServerModeEnabled = true,
                    staticHandoverNfcDataTransferEnabled = false,
                    transportOptions = MdocTransportOptions(),
                    transportFactory = transportFactory,
                )
            }
            tag.service = service
            service.start()

            try {
                // Tap 1
                assertNotNull(mdocReaderNfcHandover(tag, emptyList()))
                val transport1 = withTimeout(5.seconds) {
                    while (createdTransports.size < 1) {
                        delay(10.milliseconds)
                    }
                    createdTransports[0]
                }
                withTimeout(5.seconds) { transport1.openStarted.await() }

                // Deactivate without destroying service (same instance reused by Android for Tap 2)
                service.deactivate(NfcDeactivationReason.LINK_LOSS)

                // Tap 2 on the same service instance
                assertNotNull(mdocReaderNfcHandover(tag, emptyList()))
                val transport2 = withTimeout(5.seconds) {
                    while (createdTransports.size < 2) {
                        delay(10.milliseconds)
                    }
                    createdTransports[1]
                }
                withTimeout(5.seconds) { transport2.openStarted.await() }

                assertTrue(transport1.openCancelled)
                assertTrue(transport1.closeCalls > 0)
                assertEquals(MdocTransport.State.CLOSED, transport1.state.value)
                assertEquals(PresentmentModel.State.Connecting, presentmentModel.state.value)

                // Tap 3 mid-hold (SELECT APPLICATION without onDeactivated in between)
                assertNotNull(mdocReaderNfcHandover(tag, emptyList()))
                val transport3 = withTimeout(5.seconds) {
                    while (createdTransports.size < 3) {
                        delay(10.milliseconds)
                    }
                    createdTransports[2]
                }
                withTimeout(5.seconds) { transport3.openStarted.await() }

                assertTrue(transport2.openCancelled)
                assertTrue(transport2.closeCalls > 0)
                assertEquals(MdocTransport.State.CLOSED, transport2.state.value)
                assertEquals(PresentmentModel.State.Connecting, presentmentModel.state.value)

                // Verify UI cancellation still works after NFC deactivation
                service.deactivate(NfcDeactivationReason.LINK_LOSS)
                presentmentModel.setCanceledByUser()
                withTimeout(5.seconds) { transport3.closedDeferred.await() }
                val completed = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertTrue(completed.error is PresentmentCanceledException)
            } finally {
                service.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies [MdocNfcV2Service] negotiated BLE handover session superseding across both
     * recreated service instances and mid-hold re-selection:
     * 1. Tap 1 negotiates BLE over NFCv2 on `service1`, launching `waitForTransportJob` in the
     *    background (`transport1` suspended in `open()` before being handed off to
     *    `NfcHybridTransportMdoc`).
     * 2. `service1` is deactivated and destroyed, and Tap 2 occurs on a newly created `service2`.
     * 3. Verifies that `waitForTransportJob` from Tap 1 is cancelled and joined, and its
     *    unattached `transport1` is explicitly closed in `waitForTransportJob`'s `finally` block
     *    *before* `transport2.open()` starts.
     * 4. Tap 3 occurs mid-hold on `service2` (new `SELECT APPLICATION` without `onDeactivated`),
     *    verifying `transport2` is similarly cancelled and closed before `transport3` opens.
     */
    @Test
    fun nfcV2ServiceBleHandoverSupersedesPreviousBleSessionAcrossTapsAndMidHold() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    val index = createdTransports.size + 1
                    return FakeBleTransport(connectionMethod, "v2tap$index", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            val bleMethod = MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = UUID.randomUUID(),
            )

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = source,
                promptModel = promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = false,
                handoverPreferredOrder = listOf("ble:"),
                transportOptions = MdocTransportOptions(),
                transportFactory = transportFactory,
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            // Tap 1 (Negotiated BLE handover over NFCv2)
            val handover1 = mdocReaderNfcHandover(
                tag = tag1,
                negotiatedHandoverConnectionMethods = listOf(bleMethod),
                options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
            )
            assertNotNull(handover1)
            val transport1 = withTimeout(5.seconds) {
                while (createdTransports.size < 1) {
                    delay(10.milliseconds)
                }
                createdTransports[0]
            }
            withTimeout(5.seconds) { transport1.openStarted.await() }

            // Deactivate and recreate service for Tap 2
            service1.deactivate(NfcDeactivationReason.LINK_LOSS)
            service1.stop()

            val tag2 = ServiceLoopbackIsoTag()
            val service2 = createNfcV2Service(tag2) { buildSettings() }
            tag2.service = service2
            service2.start()

            try {
                val handover2 = mdocReaderNfcHandover(
                    tag = tag2,
                    negotiatedHandoverConnectionMethods = listOf(bleMethod),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover2)
                val transport2 = withTimeout(5.seconds) {
                    while (createdTransports.size < 2) {
                        delay(10.milliseconds)
                    }
                    createdTransports[1]
                }
                withTimeout(5.seconds) { transport2.openStarted.await() }

                // Verify transport1 (from waitForTransportJob) was cancelled and closed before transport2 opened
                assertTrue(transport1.openCancelled)
                assertEquals(1, transport1.closeCalls)
                assertEquals(MdocTransport.State.CLOSED, transport1.state.value)
                val closeIdx = events.indexOf("v2tap1.close")
                val open2Idx = events.indexOf("v2tap2.open")
                assertTrue(closeIdx in 0..<open2Idx, "Expected v2tap1.close before v2tap2.open in $events")

                // Mid-hold Tap 3 on service2 (no onDeactivated between Tap 2 and Tap 3)
                val handover3 = mdocReaderNfcHandover(
                    tag = tag2,
                    negotiatedHandoverConnectionMethods = listOf(bleMethod),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover3)
                val transport3 = withTimeout(5.seconds) {
                    while (createdTransports.size < 3) {
                        delay(10.milliseconds)
                    }
                    createdTransports[2]
                }
                withTimeout(5.seconds) { transport3.openStarted.await() }

                assertTrue(transport2.openCancelled)
                assertEquals(1, transport2.closeCalls)
                assertEquals(MdocTransport.State.CLOSED, transport2.state.value)

                // Clean up Tap 3 via UI cancel
                presentmentModel.setCanceledByUser()
                withTimeout(5.seconds) { transport3.closedDeferred.await() }
            } finally {
                service2.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies that [MdocNdefService] configured for NFC Negotiated Handover
     * (`useNegotiatedHandover = true`) also properly cancels and closes the previous BLE
     * session when a second tap occurs before the first BLE transport finishes connecting.
     */
    @Test
    fun ndefServiceNegotiatedHandoverSupersedesPreviousBleSession() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    val index = createdTransports.size + 1
                    return FakeBleTransport(connectionMethod, "neg$index", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            val bleMethod = MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = UUID.randomUUID(),
            )

            fun buildSettings() = MdocNdefService.Settings(
                source = source,
                promptModel = promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                useNegotiatedHandover = true,
                negotiatedHandoverPreferredOrder = listOf("ble:"),
                staticHandoverBleCentralClientModeEnabled = false,
                staticHandoverBlePeripheralServerModeEnabled = false,
                staticHandoverNfcDataTransferEnabled = false,
                transportOptions = MdocTransportOptions(),
                transportFactory = transportFactory,
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNdefService(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            try {
                assertNotNull(mdocReaderNfcHandover(tag1, listOf(bleMethod)))
                val transport1 = withTimeout(5.seconds) {
                    while (createdTransports.size < 1) {
                        delay(10.milliseconds)
                    }
                    createdTransports[0]
                }
                withTimeout(5.seconds) { transport1.openStarted.await() }

                // Re-tap on the same service instance after deactivation
                service1.deactivate(NfcDeactivationReason.LINK_LOSS)
                assertNotNull(mdocReaderNfcHandover(tag1, listOf(bleMethod)))
                val transport2 = withTimeout(5.seconds) {
                    while (createdTransports.size < 2) {
                        delay(10.milliseconds)
                    }
                    createdTransports[1]
                }
                withTimeout(5.seconds) { transport2.openStarted.await() }

                assertTrue(transport1.openCancelled)
                assertTrue(transport1.closeCalls > 0)
                assertEquals(MdocTransport.State.CLOSED, transport1.state.value)
                val closeIdx = events.indexOf("neg1.close")
                val advertise2Idx = events.indexOf("neg2.advertise")
                assertTrue(closeIdx in 0..<advertise2Idx, "Expected neg1.close before neg2.advertise in $events")

                presentmentModel.setCanceledByUser()
                withTimeout(5.seconds) { transport2.closedDeferred.await() }
            } finally {
                service1.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies session superseding when Tap 1's BLE transport has *already* connected
     * (`transport1.finishOpen.complete(Unit)`) and entered `Iso18013Presentment`, where it is
     * suspended in `transport1.waitForMessage()` ([PresentmentModel.State.WaitingForReader]):
     * 1. When Tap 2 arrives, Tap 1's `Iso18013Presentment` coroutine is cancelled while inside
     *    `waitForMessage()`.
     * 2. Because `Iso18013Presentment`'s `finally` block checks `currentCoroutineContext().isActive`
     *    before sending a session-termination status message and runs `transport.close()` inside
     *    `withContext(NonCancellable)`, `transport1` is closed without attempting to send a
     *    termination message on the cancelled coroutine (`transport1.sentMessages.isEmpty()`).
     * 3. `transport1.close()` completes before `transport2.advertise()` starts, and the shared
     *    [PresentmentModel] is not marked `Completed` by the superseded session.
     */
    @Test
    fun ndefServiceActivePresentmentWaitingForReaderIsTerminatedBySecondTap() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    val index = createdTransports.size + 1
                    return FakeBleTransport(connectionMethod, "active$index", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            val tag = ServiceLoopbackIsoTag()
            val service = createNdefService(tag) {
                MdocNdefService.Settings(
                    source = source,
                    promptModel = promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    useNegotiatedHandover = false,
                    negotiatedHandoverPreferredOrder = emptyList(),
                    staticHandoverBleCentralClientModeEnabled = false,
                    staticHandoverBlePeripheralServerModeEnabled = true,
                    staticHandoverNfcDataTransferEnabled = false,
                    transportOptions = MdocTransportOptions(),
                    transportFactory = transportFactory,
                )
            }
            tag.service = service
            service.start()

            try {
                // Tap 1 connects and enters Iso18013Presentment (WaitingForReader)
                assertNotNull(mdocReaderNfcHandover(tag, emptyList()))
                val transport1 = withTimeout(5.seconds) {
                    while (createdTransports.size < 1) {
                        delay(10.milliseconds)
                    }
                    createdTransports[0]
                }
                withTimeout(5.seconds) { transport1.openStarted.await() }
                transport1.finishOpen.complete(Unit)
                withTimeout(5.seconds) { transport1.waitForMessageStarted.await() }
                withTimeout(5.seconds) {
                    presentmentModel.state.first { it == PresentmentModel.State.WaitingForReader }
                }

                // Reader deactivates NFC and taps a second time
                service.deactivate(NfcDeactivationReason.LINK_LOSS)
                assertNotNull(mdocReaderNfcHandover(tag, emptyList()))
                val transport2 = withTimeout(5.seconds) {
                    while (createdTransports.size < 2) {
                        delay(10.milliseconds)
                    }
                    createdTransports[1]
                }
                withTimeout(5.seconds) { transport2.openStarted.await() }

                // Verify transport1's waitForMessage() was cancelled, no termination message was sent,
                // and transport1 was closed before transport2 advertised
                assertTrue(transport1.waitForMessageCancelled)
                assertTrue(transport1.sentMessages.isEmpty())
                assertTrue(transport1.closeCalls > 0)
                assertEquals(MdocTransport.State.CLOSED, transport1.state.value)
                val closeIdx = events.indexOf("active1.close")
                val advertise2Idx = events.indexOf("active2.advertise")
                assertTrue(closeIdx in 0..<advertise2Idx, "Expected active1.close before active2.advertise in $events")

                // PresentmentModel must not have been set to Completed by the superseded Tap 1
                assertFalse(presentmentModel.state.value is PresentmentModel.State.Completed)

                presentmentModel.setCanceledByUser()
                withTimeout(5.seconds) { transport2.closedDeferred.await() }
            } finally {
                service.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies that in [MdocNfcV2Service], when Tap 1 is an NFC-only session
     * ([MdocConnectionMethodNfcV2]) and Tap 2 negotiates a BLE handover
     * ([MdocConnectionMethodBle]) on a recreated service instance:
     * 1. Tap 2 is not mistaken for an NFC-only multi-tap re-engagement (which only applies when
     *    both the active session and the new tap are NFC-only).
     * 2. Instead, Tap 1's NFC-only transaction is superseded and cancelled, [PresentmentModel.isNfcOnly]
     *    updates from `true` to `false`, and Tap 2's BLE transport opens cleanly.
     */
    @Test
    fun nfcV2ServiceBleHandoverSupersedesActiveNfcOnlySession() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    val index = createdTransports.size + 1
                    return FakeBleTransport(connectionMethod, "bleAfterNfc$index", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = source,
                promptModel = promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = false,
                handoverPreferredOrder = listOf("ble:", "nfcv2"),
                transportOptions = MdocTransportOptions(),
                transportFactory = transportFactory,
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            // Tap 1: Reader only offers NFCv2 data transfer -> starts NFC-only session
            val nfcV2Method = MdocConnectionMethodNfcV2()
            val handover1 = mdocReaderNfcHandover(
                tag = tag1,
                negotiatedHandoverConnectionMethods = listOf(nfcV2Method),
                options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
            )
            assertNotNull(handover1)
            withTimeout(5.seconds) {
                presentmentModel.state.first { it == PresentmentModel.State.WaitingForReader }
            }
            assertTrue(presentmentModel.isNfcOnly.value)

            // Deactivate and destroy service1
            service1.deactivate(NfcDeactivationReason.LINK_LOSS)
            service1.stop()

            // Tap 2 on recreated service2: Reader offers BLE handover -> supersedes NFC-only session
            val tag2 = ServiceLoopbackIsoTag()
            val service2 = createNfcV2Service(tag2) { buildSettings() }
            tag2.service = service2
            service2.start()

            val bleMethod = MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = UUID.randomUUID(),
            )

            try {
                val handover2 = mdocReaderNfcHandover(
                    tag = tag2,
                    negotiatedHandoverConnectionMethods = listOf(bleMethod),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover2)
                val bleTransport = withTimeout(5.seconds) {
                    while (createdTransports.size < 1) {
                        delay(10.milliseconds)
                    }
                    createdTransports[0]
                }
                withTimeout(5.seconds) { bleTransport.openStarted.await() }

                // Verify Tap 1's NFC-only hybrid transport was closed before Tap 2's BLE transport opened,
                // and presentmentModel switched from NFC-only to BLE without being completed by superseded Tap 1
                val tap1HybridTransport = allCreatedHybridTransports.first()
                assertEquals(MdocTransport.State.CLOSED, tap1HybridTransport.state.value)
                assertFalse(presentmentModel.isNfcOnly.value)
                assertFalse(presentmentModel.state.value is PresentmentModel.State.Completed)

                presentmentModel.setCanceledByUser()
                withTimeout(5.seconds) { bleTransport.closedDeferred.await() }
            } finally {
                service2.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Test fixture for NFCv2 double-tap presentment tests.
     *
     * Provisions an ISO mDL ([DrivingLicense.MDL_DOCTYPE]) containing `given_name` and `family_name`
     * in a [SoftwareSecureArea] with PIN user authentication enabled, and exposes latches to observe
     * and gate both:
     * 1. The user consent prompt ([consentPromptShown] / [approveConsent]), and
     * 2. The user authentication prompt ([biometricPromptShown] / [approveBiometric]) driven
     *    through [PassphrasePromptDialogModel] on [TestPromptModel].
     */
    private class DoubleTapFixture(
        val source: PresentmentSource,
        val promptModel: TestPromptModel,
        val biometricCollectorJob: Job,
        @Volatile var consentPromptShown: CompletableDeferred<Unit>,
        @Volatile var approveConsent: CompletableDeferred<Unit>,
        @Volatile var biometricPromptShown: CompletableDeferred<Unit>,
        @Volatile var approveBiometric: CompletableDeferred<Unit>,
    ) {
        @Volatile
        var consentPromptCalls: Int = 0

        @Volatile
        var biometricPromptCalls: Int = 0

        fun resetPrompts() {
            consentPromptShown = CompletableDeferred()
            approveConsent = CompletableDeferred()
            biometricPromptShown = CompletableDeferred()
            approveBiometric = CompletableDeferred()
        }

        fun close() {
            biometricCollectorJob.cancel()
            promptModel.cancel()
        }
    }

    private suspend fun createDoubleTapFixture(scope: CoroutineScope): DoubleTapFixture {
        val storage = EphemeralStorage()
        val softwareSecureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder()
            .add(softwareSecureArea)
            .build()
        val documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        val documentTypeRepository = DocumentTypeRepository().apply {
            addDocumentType(DrivingLicense.getDocumentType())
        }

        val now = Clock.System.now().truncateToWholeSeconds()
        val signedAt = now - 1.days
        val validFrom = now - 1.days
        val validUntil = now + 365.days

        val iacaPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaPrivateKey),
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST IACA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = validFrom,
            validUntil = validUntil,
            issuerAltNameUrl = "https://github.com/openwallet-foundation/multipaz",
            crlUrl = "https://github.com/openwallet-foundation/multipaz/crl",
        )
        val iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaPrivateKey)

        val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = iacaKey,
            dsKey = dsPrivateKey.publicKey,
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST DS"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = validFrom,
            validUntil = validUntil,
        )
        val dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert)), dsPrivateKey)

        val document = documentStore.createDocument(displayName = "Test mDL")
        val mdocCredential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = softwareSecureArea,
            docType = DrivingLicense.MDL_DOCTYPE,
            createKeySettings = SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(
                    required = true,
                    passphrase = "1111",
                    constraints = PassphraseConstraints.PIN_FOUR_DIGITS,
                )
                .build()
        )
        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(DrivingLicense.MDL_NAMESPACE) {
                addDataElement("given_name", Tstr("Erika"))
                addDataElement("family_name", Tstr("Mustermann"))
            }
        }
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = DrivingLicense.MDL_DOCTYPE,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = mdocCredential.getAttestation().ecPublicKey,
        )
        val taggedEncodedMso = Cbor.encode(
            Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem())))
        )
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
        )
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to dsKey.certChain.toDataItem()
        )
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                dsKey,
                taggedEncodedMso,
                true,
                protectedHeaders,
                unprotectedHeaders,
            ).toDataItem()
        )
        val issuerProvidedAuthData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", RawCbor(encodedIssuerAuth))
            }
        )
        mdocCredential.certify(ByteString(issuerProvidedAuthData))
        iacaPrivateKey.close()
        dsPrivateKey.close()

        val promptModel = createTestPromptModel()

        lateinit var fixture: DoubleTapFixture
        val biometricCollectorJob = scope.launch(Dispatchers.Unconfined) {
            val passphraseDialogModel = promptModel.getDialogModel(PassphrasePromptDialogModel.DialogType)
            passphraseDialogModel.dialogState.collect { dialogState ->
                if (dialogState is PromptDialogModel.DialogShownState) {
                    fixture.biometricPromptCalls += 1
                    fixture.biometricPromptShown.complete(Unit)
                    fixture.approveBiometric.await()
                    dialogState.resultChannel.send("1111")
                }
            }
        }

        val source = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            showConsentPromptFn = { requester, trustedRequesterIdentity, consentData, preselectedDocuments, onDocumentsInFocus ->
                fixture.consentPromptCalls += 1
                fixture.consentPromptShown.complete(Unit)
                fixture.approveConsent.await()
                promptModelSilentConsent(
                    requester = requester,
                    trustedRequesterIdentity = trustedRequesterIdentity,
                    consentData = consentData,
                    preselectedDocuments = preselectedDocuments,
                    onDocumentsInFocus = onDocumentsInFocus,
                )
            },
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
        )

        fixture = DoubleTapFixture(
            source = source,
            promptModel = promptModel,
            biometricCollectorJob = biometricCollectorJob,
            consentPromptShown = CompletableDeferred(),
            approveConsent = CompletableDeferred(),
            biometricPromptShown = CompletableDeferred(),
            approveBiometric = CompletableDeferred(),
        )
        return fixture
    }

    private class ReaderSession(
        val eReaderKey: EcPrivateKey,
        val sessionTranscript: DataItem,
        val sessionEncryption: SessionEncryption,
        val deviceRequest: DeviceRequest,
        val encryptedDeviceRequest: ByteArray,
    )

    private fun createReaderTransport(tag: NfcIsoTag): NfcHybridTransportMdocReader {
        return NfcHybridTransportMdocReader(tag, null).also {
            allCreatedReaderTransports.add(it)
        }
    }

    private suspend fun prepareReaderSession(
        handoverResult: MdocReaderNfcHandoverResult,
        dataElements: List<String>,
    ): ReaderSession {
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagementItem = Cbor.decode(handoverResult.encodedDeviceEngagement.toByteArray())
        val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementItem)
        val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceEngagementItem))))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
            add(handoverResult.handover)
        }
        val sessionEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = deviceEngagement.eDeviceKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = true,
        )
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to dataElements.associateWith { false }
                )
            )
        }
        val encryptedDeviceRequest = sessionEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        return ReaderSession(
            eReaderKey = eReaderKey,
            sessionTranscript = sessionTranscript,
            sessionEncryption = sessionEncryption,
            deviceRequest = deviceRequest,
            encryptedDeviceRequest = encryptedDeviceRequest,
        ).also {
            allCreatedReaderSessions.add(it)
        }
    }

    /**
     * Verifies the end-to-end NFCv2 double-tap flow in [MdocNfcV2Service] and
     * [org.multipaz.presentment.Iso18013Presentment] when the second tap sends a structurally
     * equivalent [DeviceRequest] (`DeviceRequest.isStructurallyEquivalent() == true`):
     * 1. **Tap 1:** Reader performs NFCv2 handover and sends a [DeviceRequest] for `given_name`.
     *    The service enters the user consent prompt ([PresentmentModel.State.WaitingForUserInput]).
     * 2. **Phone pulled away:** The NFC link is deactivated (`tag1.deactivate()`) and `service1`
     *    is destroyed (`onDestroy()`) while the consent prompt is still showing.
     *    [PresentmentModel.isNfcConnected] becomes `false`.
     * 3. **Offline user approval & auth:** The user approves the consent prompt and
     *    completes user authentication (`SoftwareSecureArea.unlockKey` via
     *    [PassphrasePromptDialogModel]). The session transitions to [PresentmentModel.State.Sending]
     *    and waits for the phone to re-enter the NFC field.
     * 4. **Tap 2 (same request):** On a newly created `service2` instance, the reader performs a
     *    fresh NFCv2 handover (with new ephemeral keys and `SessionTranscript`) and sends a new
     *    [DeviceRequest] for `given_name`.
     * 5. **Response delivery:** [MdocNfcV2Service] verifies `isStructurallyEquivalent() == true`,
     *    re-uses the cached consent and unlocked key without re-prompting (`consentPromptCalls == 1`,
     *    `biometricPromptCalls == 1`), signs and encrypts the [DeviceResponse] using Tap 2's
     *    `SessionTranscript`, delivers it over NFC, and completes with `error == null`.
     */
    @Test
    fun nfcV2ServiceDoubleTapSameRequestDeliversResponse() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = fixture.source,
                promptModel = fixture.promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = true,
                handoverPreferredOrder = emptyList(),
                transportOptions = MdocTransportOptions(),
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            try {
                // 1. Tap 1: NFCv2 handover + send initial DeviceRequest for given_name
                val handover1 = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover1)
                val readerSession1 = prepareReaderSession(handover1, listOf("given_name"))
                val readerTransport1 = createReaderTransport(tag1)
                readerTransport1.open(readerSession1.eReaderKey.publicKey)
                readerTransport1.sendMessage(readerSession1.encryptedDeviceRequest)

                // Wait for mdoc to decrypt DeviceRequest and display consent prompt
                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                assertEquals(PresentmentModel.State.WaitingForUserInput, presentmentModel.state.value)
                assertTrue(presentmentModel.isNfcConnected.value)

                // 2. Pull phone away from NFC reader while user is looking at consent prompt
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                readerTransport1.close()
                withTimeout(5.seconds) {
                    presentmentModel.isNfcConnected.first { !it }
                }
                assertEquals(PresentmentModel.State.WaitingForUserInput, presentmentModel.state.value)

                // 3. User approves consent prompt and completes user authentication while out of NFC field
                fixture.approveConsent.complete(Unit)
                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)

                // Presentment enters Sending state and waits at transport.isNfcConnected.first { it }
                withTimeout(5.seconds) {
                    presentmentModel.state.first { it == PresentmentModel.State.Sending }
                }
                assertFalse(presentmentModel.isNfcConnected.value)
                assertEquals(1, fixture.consentPromptCalls)
                assertEquals(1, fixture.biometricPromptCalls)

                // 4. Tap 2 on recreated service2: fresh handover + structurally equivalent DeviceRequest
                val tag2 = ServiceLoopbackIsoTag()
                val service2 = createNfcV2Service(tag2) { buildSettings() }
                tag2.service = service2
                service2.start()

                try {
                    val handover2 = mdocReaderNfcHandover(
                        tag = tag2,
                        negotiatedHandoverConnectionMethods = emptyList(),
                        options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                    )
                    assertNotNull(handover2)
                    val readerSession2 = prepareReaderSession(handover2, listOf("given_name"))
                    assertTrue(readerSession2.deviceRequest.isStructurallyEquivalent(readerSession1.deviceRequest))

                    val readerTransport2 = createReaderTransport(tag2)
                    readerTransport2.open(readerSession2.eReaderKey.publicKey)
                    readerTransport2.sendMessage(readerSession2.encryptedDeviceRequest)

                    // 5. Reader receives DeviceResponse encrypted & signed under Tap 2's SessionTranscript
                    val encryptedResponse = withTimeout(5.seconds) {
                        readerTransport2.waitForMessage()
                    }
                    val (encodedDeviceResponse, status) = readerSession2.sessionEncryption.decryptMessage(encryptedResponse)
                    assertNull(status)
                    assertNotNull(encodedDeviceResponse)

                    val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                    deviceResponse.verify(sessionTranscript = readerSession2.sessionTranscript)
                    assertEquals(1, deviceResponse.documents.size)
                    val returnedDoc = deviceResponse.documents.single()
                    assertEquals(DrivingLicense.MDL_DOCTYPE, returnedDoc.docType)
                    val returnedGivenName = returnedDoc.issuerNamespaces
                        .data[DrivingLicense.MDL_NAMESPACE]
                        ?.get("given_name")
                        ?.dataElementValue
                        ?.asTstr
                    assertEquals("Erika", returnedGivenName)

                    // Consent and user auth must not have been requested a second time
                    assertEquals(1, fixture.consentPromptCalls)
                    assertEquals(1, fixture.biometricPromptCalls)

                    // Reader sends session termination to complete transaction cleanly
                    readerTransport2.sendMessage(
                        SessionEncryption.encodeStatus(
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            sequenceNumber = readerSession2.sessionEncryption.nextSequenceNumber,
                        )
                    )
                    val completedState = withTimeout(5.seconds) {
                        presentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertNull(completedState.error)
                    readerTransport2.close()
                } finally {
                    service2.stop()
                }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Verifies that in the NFCv2 double-tap flow, if the reader sends a structurally *different*
     * [DeviceRequest] on the second tap (`DeviceRequest.isStructurallyEquivalent() == false`):
     * 1. **Tap 1:** Reader requests `given_name`. The phone is pulled away from the reader, and
     *    the user approves consent and authentication for `given_name`.
     * 2. **Tap 2 (different request):** On the second tap, the reader instead requests
     *    `family_name` (`isStructurallyEquivalent() == false`).
     * 3. **Session termination:** [MdocNfcV2Service] detects the mismatch, cancels the active
     *    transaction without waking up response generation, sends a session-termination status
     *    (`Constants.SESSION_DATA_STATUS_SESSION_TERMINATION`) over NFC with no credential data,
     *    and transitions [PresentmentModel.state] to [PresentmentModel.State.Completed] with a
     *    [PresentmentCanceledException].
     */
    @Test
    fun nfcV2ServiceDoubleTapDifferentRequestKillsSession() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = fixture.source,
                promptModel = fixture.promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = true,
                handoverPreferredOrder = emptyList(),
                transportOptions = MdocTransportOptions(),
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            try {
                // 1. Tap 1: NFCv2 handover + send initial DeviceRequest for given_name
                val handover1 = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover1)
                val readerSession1 = prepareReaderSession(handover1, listOf("given_name"))
                val readerTransport1 = createReaderTransport(tag1)
                readerTransport1.open(readerSession1.eReaderKey.publicKey)
                readerTransport1.sendMessage(readerSession1.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                assertEquals(PresentmentModel.State.WaitingForUserInput, presentmentModel.state.value)

                // 2. Pull phone away from NFC reader
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                readerTransport1.close()
                withTimeout(5.seconds) {
                    presentmentModel.isNfcConnected.first { !it }
                }

                // 3. User approves consent and auth for given_name while out of NFC field
                fixture.approveConsent.complete(Unit)
                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)
                withTimeout(5.seconds) {
                    presentmentModel.state.first { it == PresentmentModel.State.Sending }
                }

                // 4. Tap 2 on recreated service2: reader sends a DIFFERENT DeviceRequest (family_name)
                val tag2 = ServiceLoopbackIsoTag()
                val service2 = createNfcV2Service(tag2) { buildSettings() }
                tag2.service = service2
                service2.start()

                try {
                    val handover2 = mdocReaderNfcHandover(
                        tag = tag2,
                        negotiatedHandoverConnectionMethods = emptyList(),
                        options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                    )
                    assertNotNull(handover2)
                    val readerSession2 = prepareReaderSession(handover2, listOf("family_name"))
                    assertFalse(readerSession2.deviceRequest.isStructurallyEquivalent(readerSession1.deviceRequest))

                    val readerTransport2 = createReaderTransport(tag2)
                    readerTransport2.open(readerSession2.eReaderKey.publicKey)
                    readerTransport2.sendMessage(readerSession2.encryptedDeviceRequest)

                    // 5. Verify no credential data is returned; reader receives session termination status
                    val responseMessage = withTimeout(5.seconds) {
                        readerTransport2.waitForMessage()
                    }
                    val (encodedDeviceResponse, status) = readerSession2.sessionEncryption.decryptMessage(responseMessage)
                    assertNull(encodedDeviceResponse)
                    assertEquals(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION, status)

                    // PresentmentModel transitions to Completed with an error
                    val completedState = withTimeout(5.seconds) {
                        presentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertIs<PresentmentCanceledException>(completedState.error)

                    // Wait for the killed transactionJob to finish cleanup and verify hybrid transport is closed
                    MdocNfcPresentmentService.transactionJob?.let { job ->
                        withTimeout(5.seconds) { job.join() }
                    }
                    val killedHybridTransport = allCreatedHybridTransports.single()
                    assertEquals(MdocTransport.State.CLOSED, killedHybridTransport.state.value)
                    assertNull(MdocNfcV2Service.hybridTransport)

                    assertEquals(1, fixture.consentPromptCalls)
                    assertEquals(1, fixture.biometricPromptCalls)
                    readerTransport2.close()
                } finally {
                    service2.stop()
                }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Verifies that when the user starts the first half of an NFCv2 double-tap flow, pulls the
     * phone away, and **cancels** the session (`PresentmentModel.setCanceledByUser()`), a
     * subsequent tap against another reader starts a completely fresh transaction and delivers
     * the response immediately in a single tap once the user approves consent and auth:
     * 1. **Tap 1 (abandoned):** Reader 1 performs NFCv2 handover and requests `given_name`. The
     *    phone is pulled away (`tag1.deactivate()`) while the consent prompt is showing.
     * 2. **User cancels:** The user cancels the first presentment (`presentmentModel.setCanceledByUser()`).
     *    [MdocNfcV2Service] cancels `transactionJob`, clears `activeDeviceRequest` and
     *    `activeEngagementParamsFlow`, and marks [PresentmentModel.state] as `Completed` with a
     *    [PresentmentCanceledException].
     * 3. **Tap 2 (fresh single-tap transaction):** The user taps against a second reader that
     *    requests `family_name` and keeps the phone on the reader. [MdocNfcV2Service] starts a
     *    new transaction, prompts for consent and authentication for `family_name`,
     *    and immediately delivers the [DeviceResponse] over NFC during that same tap.
     */
    @Test
    fun nfcV2ServiceCancelAfterFirstTapThenSingleTapNewRequestSucceeds() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = fixture.source,
                promptModel = fixture.promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = true,
                handoverPreferredOrder = emptyList(),
                transportOptions = MdocTransportOptions(),
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            try {
                // 1. Tap 1: NFCv2 handover + send initial DeviceRequest for given_name
                val handover1 = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover1)
                val readerSession1 = prepareReaderSession(handover1, listOf("given_name"))
                val readerTransport1 = createReaderTransport(tag1)
                readerTransport1.open(readerSession1.eReaderKey.publicKey)
                readerTransport1.sendMessage(readerSession1.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                assertEquals(PresentmentModel.State.WaitingForUserInput, presentmentModel.state.value)

                // 2. Pull phone away and user cancels the first transaction
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                readerTransport1.close()
                withTimeout(5.seconds) {
                    presentmentModel.isNfcConnected.first { !it }
                }

                presentmentModel.setCanceledByUser()
                val cancelledState = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertIs<PresentmentCanceledException>(cancelledState.error)
                assertEquals(1, fixture.consentPromptCalls)
                assertEquals(0, fixture.biometricPromptCalls)

                // Prepare latches for the second (independent) transaction
                fixture.resetPrompts()

                // 3. Tap 2 on recreated service2 against another reader requesting family_name (held in field)
                val tag2 = ServiceLoopbackIsoTag()
                val service2 = createNfcV2Service(tag2) { buildSettings() }
                tag2.service = service2
                service2.start()

                try {
                    val handover2 = mdocReaderNfcHandover(
                        tag = tag2,
                        negotiatedHandoverConnectionMethods = emptyList(),
                        options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                    )
                    assertNotNull(handover2)
                    val readerSession2 = prepareReaderSession(handover2, listOf("family_name"))
                    assertFalse(readerSession2.deviceRequest.isStructurallyEquivalent(readerSession1.deviceRequest))

                    val readerTransport2 = createReaderTransport(tag2)
                    readerTransport2.open(readerSession2.eReaderKey.publicKey)
                    readerTransport2.sendMessage(readerSession2.encryptedDeviceRequest)

                    // User approves consent and auth while phone remains on the second reader
                    withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                    assertTrue(presentmentModel.isNfcConnected.value)
                    fixture.approveConsent.complete(Unit)

                    withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                    fixture.approveBiometric.complete(Unit)

                    // DeviceResponse is delivered immediately on Tap 2 without requiring another tap
                    val encryptedResponse = withTimeout(5.seconds) {
                        readerTransport2.waitForMessage()
                    }
                    val (encodedDeviceResponse, status) = readerSession2.sessionEncryption.decryptMessage(encryptedResponse)
                    assertNull(status)
                    assertNotNull(encodedDeviceResponse)

                    val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                    deviceResponse.verify(sessionTranscript = readerSession2.sessionTranscript)
                    assertEquals(1, deviceResponse.documents.size)
                    val returnedDoc = deviceResponse.documents.single()
                    assertEquals(DrivingLicense.MDL_DOCTYPE, returnedDoc.docType)
                    val returnedFamilyName = returnedDoc.issuerNamespaces
                        .data[DrivingLicense.MDL_NAMESPACE]
                        ?.get("family_name")
                        ?.dataElementValue
                        ?.asTstr
                    assertEquals("Mustermann", returnedFamilyName)
                    assertNull(
                        returnedDoc.issuerNamespaces
                            .data[DrivingLicense.MDL_NAMESPACE]
                            ?.get("given_name")
                    )

                    assertEquals(2, fixture.consentPromptCalls)
                    assertEquals(1, fixture.biometricPromptCalls)

                    readerTransport2.sendMessage(
                        SessionEncryption.encodeStatus(
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            sequenceNumber = readerSession2.sessionEncryption.nextSequenceNumber,
                        )
                    )
                    val completedState = withTimeout(5.seconds) {
                        presentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertNull(completedState.error)
                    readerTransport2.close()
                } finally {
                    service2.stop()
                }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Verifies [NfcApduRouter] AID routing and cross-protocol session superseding between
     * [MdocNdefService] (NFCv1 `D2760000850101`) and [MdocNfcV2Service] (NFCv2 `A0000002480401`):
     * 1. Selecting an unknown AID returns `6a82` (`RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND`).
     * 2. **Tap 1 (NFCv1 NDEF):** Reader selects `NDEF_APPLICATION_ID` and performs static handover,
     *    starting a BLE session (`cross1`) in [MdocNdefService].
     * 3. **Tap 2 (NFCv2):** After `onDeactivated()`, reader selects `ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID`
     *    and performs NFCv2 BLE handover, starting `cross2` in [MdocNfcV2Service]. Verifies that
     *    `cross1` from [MdocNdefService] was cancelled and closed before `cross2` opened.
     * 4. **Tap 3 (NFCv1 NDEF):** After `onDeactivated()`, reader taps again with NFCv1 NDEF,
     *    starting `cross3` in [MdocNdefService]. Verifies that `cross2` from [MdocNfcV2Service]
     *    was cancelled and closed before `cross3` advertised.
     */
    @Test
    fun combinedNfcServiceCrossProtocolTapsSupersedeEachOther() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    val index = createdTransports.size + 1
                    return FakeBleTransport(connectionMethod, "cross$index", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            val tag = ServiceLoopbackIsoTag()
            val combinedService = object : NfcApduRouter() {
                override fun sendResponseApdu(responseApdu: ResponseApdu) {
                    tag.responseChannel.trySend(responseApdu)
                }

                override fun buildServices(): Map<ByteString, NfcApduService> {
                    val ndefService = object : MdocNdefService() {
                        override suspend fun getSettings() = MdocNdefService.Settings(
                            source = source,
                            promptModel = promptModel,
                            presentmentModel = presentmentModel,
                            launchUi = null,
                            sessionEncryptionCurve = EcCurve.P256,
                            useNegotiatedHandover = false,
                            negotiatedHandoverPreferredOrder = emptyList(),
                            staticHandoverBleCentralClientModeEnabled = false,
                            staticHandoverBlePeripheralServerModeEnabled = true,
                            staticHandoverNfcDataTransferEnabled = false,
                            transportOptions = MdocTransportOptions(),
                            transportFactory = transportFactory,
                        )
                    }
                    val nfcV2Service = object : MdocNfcV2Service() {
                        override suspend fun getSettings() = MdocNfcV2Service.Settings(
                            source = source,
                            promptModel = promptModel,
                            presentmentModel = presentmentModel,
                            launchUi = null,
                            sessionEncryptionCurve = EcCurve.P256,
                            onlyAllowNfc = false,
                            handoverPreferredOrder = listOf("ble:"),
                            transportOptions = MdocTransportOptions(),
                            transportFactory = transportFactory,
                        )
                    }
                    return mapOf(
                        Nfc.NDEF_APPLICATION_ID to ndefService,
                        Nfc.MDOC_NFC_ENGAGEMENT_V2_AID to nfcV2Service,
                    )
                }
            }
            tag.combinedService = combinedService
            combinedService.start()

            try {
                // 0. Sending a non-SELECT APDU before any AID is selected returns 6a82
                val noAidSelectedResponse = tag.transceive(
                    CommandApdu(
                        cla = 0x00,
                        ins = Nfc.INS_READ_BINARY,
                        p1 = 0x00,
                        p2 = 0x00,
                        payload = ByteString(),
                        le = 15,
                    )
                )
                assertEquals(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND, noAidSelectedResponse.status)

                // 1. Selecting an unknown AID returns 6a82
                val unknownAidResponse = tag.transceive(
                    CommandApdu(
                        cla = 0x00,
                        ins = Nfc.INS_SELECT,
                        p1 = Nfc.INS_SELECT_P1_APPLICATION,
                        p2 = 0x04,
                        payload = ByteString(0x01, 0x02, 0x03, 0x04),
                        le = 0,
                    )
                )
                assertEquals(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND, unknownAidResponse.status)
                combinedService.deactivate(NfcDeactivationReason.LINK_LOSS)

                // 2. Tap 1 (NFCv1 NDEF static handover) -> opens cross1 in MdocNdefService
                assertNotNull(mdocReaderNfcHandover(tag, emptyList()))
                val transport1 = withTimeout(5.seconds) {
                    while (createdTransports.size < 1) {
                        delay(10.milliseconds)
                    }
                    createdTransports[0]
                }
                withTimeout(5.seconds) { transport1.openStarted.await() }
                combinedService.deactivate(NfcDeactivationReason.LINK_LOSS)

                // 3. Tap 2 (NFCv2 BLE handover) -> supersedes cross1 and opens cross2 in MdocNfcV2Service
                val bleMethod = MdocConnectionMethodBle(
                    supportsPeripheralServerMode = false,
                    supportsCentralClientMode = true,
                    peripheralServerModeUuid = null,
                    centralClientModeUuid = UUID.randomUUID(),
                )
                assertNotNull(
                    mdocReaderNfcHandover(
                        tag = tag,
                        negotiatedHandoverConnectionMethods = listOf(bleMethod),
                        options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                    )
                )
                val transport2 = withTimeout(5.seconds) {
                    while (createdTransports.size < 2) {
                        delay(10.milliseconds)
                    }
                    createdTransports[1]
                }
                withTimeout(5.seconds) { transport2.openStarted.await() }
                assertTrue(transport1.openCancelled)
                assertTrue(transport1.closeCalls > 0)
                assertEquals(MdocTransport.State.CLOSED, transport1.state.value)
                val close1Idx = events.indexOf("cross1.close")
                val open2Idx = events.indexOf("cross2.open")
                assertTrue(close1Idx in 0..<open2Idx, "Expected cross1.close before cross2.open in $events")
                combinedService.deactivate(NfcDeactivationReason.LINK_LOSS)

                // 4. Tap 3 (NFCv1 NDEF static handover) -> supersedes cross2 and opens cross3 in MdocNdefService
                assertNotNull(mdocReaderNfcHandover(tag, emptyList()))
                val transport3 = withTimeout(5.seconds) {
                    while (createdTransports.size < 3) {
                        delay(10.milliseconds)
                    }
                    createdTransports[2]
                }
                withTimeout(5.seconds) { transport3.openStarted.await() }
                assertTrue(transport2.openCancelled)
                assertTrue(transport2.closeCalls > 0)
                assertEquals(MdocTransport.State.CLOSED, transport2.state.value)
                val close2Idx = events.indexOf("cross2.close")
                val advertise3Idx = events.indexOf("cross3.advertise")
                assertTrue(close2Idx in 0..<advertise3Idx, "Expected cross2.close before cross3.advertise in $events")

                assertFalse(presentmentModel.state.value is PresentmentModel.State.Completed)
                presentmentModel.setCanceledByUser()
                withTimeout(5.seconds) { transport3.closedDeferred.await() }
            } finally {
                combinedService.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies the NFCv2 double-tap flow when Tap 1 is so brief that NFCv2 handover completes,
     * but the phone is pulled away **before** the reader sends the initial [DeviceRequest]:
     * 1. **Tap 1 (handover only):** Reader performs [mdocReaderNfcHandover] on `tag1`, starting
     *    `transactionJob` which enters [PresentmentModel.State.WaitingForReader]. The phone is
     *    pulled away (`tag1.deactivate()`, `service1.stop()`) before any `ENVELOPE` with a
     *    [DeviceRequest] is sent, so [org.multipaz.presentment.Iso18013Presentment] suspends at
     *    `transport.isNfcConnected.first { it }`.
     * 2. **Tap 2 (handover + request + response):** On `service2`, the reader performs a fresh
     *    NFCv2 handover and sends the [DeviceRequest] for `given_name`. [MdocNfcV2Service] updates
     *    `activeEngagementParamsFlow` with Tap 2's parameters, wakes up the waiting presentment
     *    session, prompts the user for consent and auth, and delivers the verified
     *    [DeviceResponse] over `tag2`.
     */
    @Test
    fun nfcV2ServiceDoubleTapWhenFirstTapDisconnectedBeforeDeviceRequest() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = fixture.source,
                promptModel = fixture.promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = true,
                handoverPreferredOrder = emptyList(),
                transportOptions = MdocTransportOptions(),
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            try {
                // 1. Tap 1: handover completes, then phone is pulled away before DeviceRequest arrives
                val handover1 = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover1)
                withTimeout(5.seconds) {
                    presentmentModel.state.first { it == PresentmentModel.State.WaitingForReader }
                }

                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                withTimeout(5.seconds) {
                    presentmentModel.isNfcConnected.first { !it }
                }

                // 2. Tap 2 on recreated service2: fresh handover + DeviceRequest + user approval
                val tag2 = ServiceLoopbackIsoTag()
                val service2 = createNfcV2Service(tag2) { buildSettings() }
                tag2.service = service2
                service2.start()

                try {
                    val handover2 = mdocReaderNfcHandover(
                        tag = tag2,
                        negotiatedHandoverConnectionMethods = emptyList(),
                        options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                    )
                    assertNotNull(handover2)
                    val readerSession2 = prepareReaderSession(handover2, listOf("given_name"))
                    val readerTransport2 = createReaderTransport(tag2)
                    readerTransport2.open(readerSession2.eReaderKey.publicKey)
                    readerTransport2.sendMessage(readerSession2.encryptedDeviceRequest)

                    withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                    fixture.approveConsent.complete(Unit)

                    withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                    fixture.approveBiometric.complete(Unit)

                    val encryptedResponse = withTimeout(5.seconds) {
                        readerTransport2.waitForMessage()
                    }
                    val (encodedDeviceResponse, status) = readerSession2.sessionEncryption.decryptMessage(encryptedResponse)
                    assertNull(status)
                    assertNotNull(encodedDeviceResponse)

                    val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                    deviceResponse.verify(sessionTranscript = readerSession2.sessionTranscript)
                    assertEquals(1, deviceResponse.documents.size)

                    readerTransport2.sendMessage(
                        SessionEncryption.encodeStatus(
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            sequenceNumber = readerSession2.sessionEncryption.nextSequenceNumber,
                        )
                    )
                    val completedState = withTimeout(5.seconds) {
                        presentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertNull(completedState.error)
                    readerTransport2.close()
                } finally {
                    service2.stop()
                }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Verifies [MdocNfcV2Service]'s hybrid NFCv2 + BLE transport flow
     * ([org.multipaz.mdoc.transport.NfcHybridTransportMdoc]) when the [DeviceRequest] arrives over
     * NFC while BLE is still connecting, and the [DeviceResponse] is delivered over BLE after the
     * phone leaves the NFC field:
     * 1. Reader negotiates [MdocConnectionMethodBle] during NFCv2 handover.
     * 2. While the secondary BLE transport (`hybridBle`) is still in `open()`, the reader sends the
     *    encrypted [DeviceRequest] (`given_name`) over NFC (`tag1`).
     * 3. The mdoc receives the request via NFC and enters the consent prompt. The user pulls the
     *    phone away from the NFC reader (`tag1.deactivate()`, `service1.stop()`) and approves
     *    the consent and auth prompts.
     * 4. Because NFC is now disconnected, [org.multipaz.mdoc.transport.NfcHybridTransportMdoc]
     *    queues the encrypted [DeviceResponse] in its transport backlog.
     * 5. Once `hybridBle.finishOpen` completes, the reader's hybrid transport also flushes its
     *    initial [DeviceRequest] over BLE (which [org.multipaz.mdoc.transport.NfcHybridTransportMdoc]
     *    deduplicates as already received via NFC), the mdoc's queued [DeviceResponse] is
     *    transmitted over BLE (`hybridBle.sentMessages`), verified by the reader, and completed
     *    when the reader sends session termination over BLE.
     */
    @Test
    fun nfcV2ServiceHybridHandoverRequestOverNfcResponseOverBle() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()
            val events = mutableListOf<String>()
            val createdTransports = mutableListOf<FakeBleTransport>()

            val transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions
                ): MdocTransport {
                    return FakeBleTransport(connectionMethod, "hybridBle", events).also {
                        createdTransports.add(it)
                    }
                }
            }

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) {
                MdocNfcV2Service.Settings(
                    source = fixture.source,
                    promptModel = fixture.promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    onlyAllowNfc = false,
                    handoverPreferredOrder = listOf("ble:"),
                    transportOptions = MdocTransportOptions(),
                    transportFactory = transportFactory,
                )
            }
            tag1.service = service1
            service1.start()

            try {
                val bleMethod = MdocConnectionMethodBle(
                    supportsPeripheralServerMode = false,
                    supportsCentralClientMode = true,
                    peripheralServerModeUuid = null,
                    centralClientModeUuid = UUID.randomUUID(),
                )
                val handover = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = listOf(bleMethod),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover)
                val bleTransport = withTimeout(5.seconds) {
                    while (createdTransports.size < 1) {
                        delay(10.milliseconds)
                    }
                    createdTransports[0]
                }
                withTimeout(5.seconds) { bleTransport.openStarted.await() }
                assertFalse(presentmentModel.isNfcOnly.value)

                // Reader sends DeviceRequest over NFC while BLE is still connecting
                val readerSession = prepareReaderSession(handover, listOf("given_name"))
                val readerTransport = createReaderTransport(tag1)
                readerTransport.open(readerSession.eReaderKey.publicKey)
                readerTransport.sendMessage(readerSession.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                assertEquals(PresentmentModel.State.WaitingForUserInput, presentmentModel.state.value)

                // Phone is pulled away from NFC reader before consent/auth approval
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                readerTransport.close()

                fixture.approveConsent.complete(Unit)
                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)

                // Wait until presentment has queued the response into NfcHybridTransportMdoc's backlog
                // and looped back to WaitingForReader (waiting for session termination)
                withTimeout(5.seconds) {
                    presentmentModel.state.first { it == PresentmentModel.State.WaitingForReader }
                }

                // Now BLE finishes connecting: reader hybrid transport flushes its DeviceRequest backlog
                // (deduplicated by NfcHybridTransportMdoc) and NfcHybridTransportMdoc flushes DeviceResponse over BLE
                bleTransport.incomingMessages.trySend(readerSession.encryptedDeviceRequest)
                bleTransport.finishOpen.complete(Unit)
                val encryptedResponse = withTimeout(5.seconds) {
                    while (bleTransport.sentMessages.isEmpty()) {
                        delay(10.milliseconds)
                    }
                    bleTransport.sentMessages.first()
                }
                val (encodedDeviceResponse, status) = readerSession.sessionEncryption.decryptMessage(encryptedResponse)
                assertNull(status)
                assertNotNull(encodedDeviceResponse)

                val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                deviceResponse.verify(sessionTranscript = readerSession.sessionTranscript)
                assertEquals(1, deviceResponse.documents.size)

                // Reader sends session termination over BLE
                bleTransport.incomingMessages.trySend(
                    SessionEncryption.encodeStatus(
                        statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                        sequenceNumber = readerSession.sessionEncryption.nextSequenceNumber,
                    )
                )
                val completedState = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertNull(completedState.error)
                withTimeout(5.seconds) { bleTransport.closedDeferred.await() }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Verifies that in an NFCv2 NFC-only session, if the phone is removed from the NFC field
     * *after* the [DeviceResponse] has been sent (`numRequestsServed > 0`) but *before* the
     * reader sends session termination (`status = 20`), [org.multipaz.presentment.Iso18013Presentment]
     * aborts with [Iso18013PresentmentNfcDisconnectedException] and [MdocNfcV2Service] sets
     * [PresentmentModel.state] to [PresentmentModel.State.Completed] with that exception.
     */
    @Test
    fun nfcV2ServicePhoneRemovedBeforeSessionTerminationFailsWithNfcDisconnectedException() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) {
                MdocNfcV2Service.Settings(
                    source = fixture.source,
                    promptModel = fixture.promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    onlyAllowNfc = true,
                    handoverPreferredOrder = emptyList(),
                    transportOptions = MdocTransportOptions(),
                )
            }
            tag1.service = service1
            service1.start()

            try {
                val handover = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover)
                val readerSession = prepareReaderSession(handover, listOf("given_name"))
                val readerTransport = createReaderTransport(tag1)
                readerTransport.open(readerSession.eReaderKey.publicKey)
                readerTransport.sendMessage(readerSession.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                fixture.approveConsent.complete(Unit)

                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)

                val encryptedResponse = withTimeout(5.seconds) {
                    readerTransport.waitForMessage()
                }
                assertNotNull(encryptedResponse)

                // Phone is pulled away before the reader sends session termination
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                readerTransport.close()

                val completedState = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertIs<Iso18013PresentmentNfcDisconnectedException>(completedState.error)
            } finally {
                service1.stop()
                fixture.close()
            }
        }
    }

    /**
     * Verifies the NFCv2 double-tap flow when the user places the phone back on the reader
     * (**Tap 2**) *while* the consent or auth prompt from **Tap 1** is still on screen:
     * 1. **Tap 1:** Reader performs NFCv2 handover and sends a [DeviceRequest] for `given_name`.
     *    [org.multipaz.presentment.Iso18013Presentment] immediately marks the NFC-only hybrid
     *    transport as having a pending response (`transport.markResponsePending()`) and shows
     *    the consent prompt ([PresentmentModel.State.WaitingForUserInput]).
     * 2. **Phone briefly lifted:** `tag1` is deactivated (`isNfcConnected == false`) while the
     *    user is still looking at the consent prompt.
     * 3. **Early Tap 2 (while prompt is still open):** Before the user taps "Approve", the phone
     *    is placed back on the reader (`service2` on `tag2`). The reader performs a fresh NFCv2
     *    handover and sends a structurally equivalent [DeviceRequest] for `given_name`.
     *    Because `markResponsePending()` was set when the first request was received,
     *    [org.multipaz.mdoc.transport.NfcHybridTransportMdoc] sets `isNfcConnected = true` and
     *    discards the duplicate [DeviceRequest] payload instead of queueing it as a second
     *    request in the session.
     * 4. **User approves prompt:** The user approves consent and auth. The service
     *    immediately signs and encrypts [DeviceResponse] using Tap 2's `SessionTranscript`,
     *    delivers it over `tag2`, and cleanly processes the reader's session termination message.
     */
    @Test
    fun nfcV2ServiceDoubleTapEarlyRetapDuringConsentPromptSucceeds() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = fixture.source,
                promptModel = fixture.promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = true,
                handoverPreferredOrder = emptyList(),
                transportOptions = MdocTransportOptions(),
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            try {
                // 1. Tap 1: handover + DeviceRequest -> enters WaitingForUserInput
                val handover1 = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover1)
                val readerSession1 = prepareReaderSession(handover1, listOf("given_name"))
                val readerTransport1 = createReaderTransport(tag1)
                readerTransport1.open(readerSession1.eReaderKey.publicKey)
                readerTransport1.sendMessage(readerSession1.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                assertEquals(PresentmentModel.State.WaitingForUserInput, presentmentModel.state.value)

                // 2. Phone is lifted while consent prompt is still showing
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                readerTransport1.close()
                withTimeout(5.seconds) {
                    presentmentModel.isNfcConnected.first { !it }
                }

                // 3. Early Tap 2 occurs BEFORE user approves the consent prompt
                val tag2 = ServiceLoopbackIsoTag()
                val service2 = createNfcV2Service(tag2) { buildSettings() }
                tag2.service = service2
                service2.start()

                try {
                    val handover2 = mdocReaderNfcHandover(
                        tag = tag2,
                        negotiatedHandoverConnectionMethods = emptyList(),
                        options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                    )
                    assertNotNull(handover2)
                    val readerSession2 = prepareReaderSession(handover2, listOf("given_name"))
                    val readerTransport2 = createReaderTransport(tag2)
                    readerTransport2.open(readerSession2.eReaderKey.publicKey)
                    readerTransport2.sendMessage(readerSession2.encryptedDeviceRequest)

                    // Wait until Tap 2's message arrives and marks NFC connected again
                    withTimeout(5.seconds) {
                        presentmentModel.isNfcConnected.first { it }
                    }
                    assertEquals(PresentmentModel.State.WaitingForUserInput, presentmentModel.state.value)

                    // 4. Now user approves consent and auth prompts while phone is back on reader
                    fixture.approveConsent.complete(Unit)
                    withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                    fixture.approveBiometric.complete(Unit)

                    val encryptedResponse = withTimeout(5.seconds) {
                        readerTransport2.waitForMessage()
                    }
                    val (encodedDeviceResponse, status) = readerSession2.sessionEncryption.decryptMessage(encryptedResponse)
                    assertNull(status)
                    assertNotNull(encodedDeviceResponse)

                    val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                    deviceResponse.verify(sessionTranscript = readerSession2.sessionTranscript)
                    assertEquals(1, deviceResponse.documents.size)

                    assertEquals(1, fixture.consentPromptCalls)
                    assertEquals(1, fixture.biometricPromptCalls)

                    readerTransport2.sendMessage(
                        SessionEncryption.encodeStatus(
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            sequenceNumber = readerSession2.sessionEncryption.nextSequenceNumber,
                        )
                    )
                    val completedState = withTimeout(5.seconds) {
                        presentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertNull(completedState.error)
                    readerTransport2.close()
                } finally {
                    service2.stop()
                }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Verifies that in a single NFCv2 session (`insertSequenceNumbers = true`), the reader can
     * send multiple sequential [DeviceRequest] messages before terminating the session:
     * 1. Reader sends [DeviceRequest] #1 for `given_name` (sequence number 1) and receives
     *    verified [DeviceResponse] #1 (`given_name == "Erika"`, sequence number 1).
     * 2. Without disconnecting, the reader sends [DeviceRequest] #2 for `family_name` encrypted
     *    under the same [SessionEncryption] (sequence number 2).
     * 3. [org.multipaz.presentment.Iso18013Presentment] prompts the user for the new request,
     *    and returns verified [DeviceResponse] #2 (`family_name == "Mustermann"`, sequence number 2).
     * 4. Reader sends `SESSION_DATA_STATUS_SESSION_TERMINATION` (sequence number 3) and the
     *    session completes cleanly (`error == null`).
     */
    @Test
    fun nfcV2ServiceSingleTapMultipleRequestsWithSequenceNumbersSucceeds() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) {
                MdocNfcV2Service.Settings(
                    source = fixture.source,
                    promptModel = fixture.promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    onlyAllowNfc = true,
                    handoverPreferredOrder = emptyList(),
                    transportOptions = MdocTransportOptions(),
                )
            }
            tag1.service = service1
            service1.start()

            try {
                val handover = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover)

                // Request #1: given_name (sequence number 1)
                val readerSession = prepareReaderSession(handover, listOf("given_name"))
                val readerTransport = createReaderTransport(tag1)
                readerTransport.open(readerSession.eReaderKey.publicKey)
                readerTransport.sendMessage(readerSession.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                fixture.approveConsent.complete(Unit)
                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)

                val encryptedResponse1 = withTimeout(5.seconds) {
                    readerTransport.waitForMessage()
                }
                val (encodedResponse1, status1) = readerSession.sessionEncryption.decryptMessage(encryptedResponse1)
                assertNull(status1)
                assertNotNull(encodedResponse1)
                val deviceResponse1 = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse1))
                deviceResponse1.verify(sessionTranscript = readerSession.sessionTranscript)
                val givenName = deviceResponse1.documents.single().issuerNamespaces
                    .data[DrivingLicense.MDL_NAMESPACE]
                    ?.get("given_name")
                    ?.dataElementValue
                    ?.asTstr
                assertEquals("Erika", givenName)

                // Prepare latches for Request #2 in the same session
                fixture.resetPrompts()

                // Request #2: family_name encrypted with same SessionEncryption (sequence number 2)
                val deviceRequest2 = buildDeviceRequest(sessionTranscript = readerSession.sessionTranscript) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                        )
                    )
                }
                val encryptedDeviceRequest2 = readerSession.sessionEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest2.toDataItem()),
                    statusCode = null,
                )
                readerTransport.sendMessage(encryptedDeviceRequest2)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                fixture.approveConsent.complete(Unit)
                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)

                val encryptedResponse2 = withTimeout(5.seconds) {
                    readerTransport.waitForMessage()
                }
                val (encodedResponse2, status2) = readerSession.sessionEncryption.decryptMessage(encryptedResponse2)
                assertNull(status2)
                assertNotNull(encodedResponse2)
                val deviceResponse2 = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse2))
                deviceResponse2.verify(sessionTranscript = readerSession.sessionTranscript)
                val familyName = deviceResponse2.documents.single().issuerNamespaces
                    .data[DrivingLicense.MDL_NAMESPACE]
                    ?.get("family_name")
                    ?.dataElementValue
                    ?.asTstr
                assertEquals("Mustermann", familyName)

                assertEquals(2, fixture.consentPromptCalls)
                assertEquals(2, fixture.biometricPromptCalls)

                // Terminate session (sequence number 3)
                readerTransport.sendMessage(
                    SessionEncryption.encodeStatus(
                        statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                        sequenceNumber = readerSession.sessionEncryption.nextSequenceNumber,
                    )
                )
                val completedState = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertNull(completedState.error)
                readerTransport.close()
            } finally {
                service1.stop()
                fixture.close()
            }
        }
    }

    /**
     * Verifies end-to-end NFCv2 command and response APDU chaining in [MdocNfcV2Service] and
     * [org.multipaz.mdoc.nfc.MdocNfcV2EngagementHelper] when the underlying [NfcIsoTag] has a
     * small `maxTransceiveLength` (128 bytes):
     * 1. The reader splits the encrypted [DeviceRequest] across multiple `ENVELOPE` APDUs with
     *    `CLA_CHAIN_NOT_LAST` (`0x10`) followed by `CLA_CHAIN_LAST` (`0x00`).
     * 2. The mdoc chunks the encrypted [DeviceResponse] across multiple `61 00` / `61 XX`
     *    responses fetched by the reader using `INS_GET_RESPONSE` (`0xC0`) APDUs.
     * 3. Asserts that both `chainedEnvelopeCount > 0` and `getResponseCount > 0`, and that the
     *    reassembled [DeviceResponse] decrypts and verifies against the `SessionTranscript`.
     */
    @Test
    fun nfcV2ServiceSmallMaxTransceiveLengthChainsEnvelopeAndGetResponseApdus() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            val tag1 = ServiceLoopbackIsoTag(maxTransceiveLength = 128)
            val service1 = createNfcV2Service(tag1) {
                MdocNfcV2Service.Settings(
                    source = fixture.source,
                    promptModel = fixture.promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    onlyAllowNfc = true,
                    handoverPreferredOrder = emptyList(),
                    transportOptions = MdocTransportOptions(),
                )
            }
            tag1.service = service1
            service1.start()

            try {
                val handover = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover)
                val readerSession = prepareReaderSession(handover, listOf("given_name", "family_name"))
                val readerTransport = createReaderTransport(tag1)
                readerTransport.open(readerSession.eReaderKey.publicKey)
                readerTransport.sendMessage(readerSession.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                fixture.approveConsent.complete(Unit)

                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)

                val encryptedResponse = withTimeout(5.seconds) {
                    readerTransport.waitForMessage()
                }
                val (encodedDeviceResponse, status) = readerSession.sessionEncryption.decryptMessage(encryptedResponse)
                assertNull(status)
                assertNotNull(encodedDeviceResponse)

                val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                deviceResponse.verify(sessionTranscript = readerSession.sessionTranscript)
                assertEquals(1, deviceResponse.documents.size)

                // Both incoming ENVELOPE command chaining and outgoing GET RESPONSE chaining must have been used
                assertTrue(tag1.chainedEnvelopeCount > 0, "Expected chained ENVELOPE commands with maxTransceiveLength=128")
                assertTrue(tag1.getResponseCount > 0, "Expected GET RESPONSE commands with maxTransceiveLength=128")

                readerTransport.sendMessage(
                    SessionEncryption.encodeStatus(
                        statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                        sequenceNumber = readerSession.sessionEncryption.nextSequenceNumber,
                    )
                )
                val completedState = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertNull(completedState.error)
                readerTransport.close()
            } finally {
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                fixture.close()
            }
        }
    }

    /**
     * Verifies that if a stray or non-mdoc NFC reader polls [MdocNfcV2Service] with an invalid
     * APDU while a presentment transaction is already active (such as between Tap 1 and Tap 2 of
     * an NFCv2 double-tap flow):
     * 1. [org.multipaz.mdoc.nfc.MdocNfcV2EngagementHelper] returns `6d00`
     *    (`RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID`) and invokes `onError`.
     * 2. Because `transactionJob?.isActive == true`, [MdocNfcV2Service]'s `onError` callback
     *    does **not** set [PresentmentModel.state] to [PresentmentModel.State.Completed] with an
     *    error, and the active double-tap transaction remains alive.
     * 3. When the real mdoc reader subsequently performs Tap 2, the double-tap presentment
     *    succeeds and delivers the verified [DeviceResponse].
     */
    @Test
    fun nfcV2ServiceStrayReaderErrorDuringActiveSessionDoesNotAbortTransaction() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()

            fun buildSettings() = MdocNfcV2Service.Settings(
                source = fixture.source,
                promptModel = fixture.promptModel,
                presentmentModel = presentmentModel,
                launchUi = null,
                sessionEncryptionCurve = EcCurve.P256,
                onlyAllowNfc = true,
                handoverPreferredOrder = emptyList(),
                transportOptions = MdocTransportOptions(),
            )

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) { buildSettings() }
            tag1.service = service1
            service1.start()

            try {
                // 1. Tap 1: real reader performs handover and sends DeviceRequest; user approves while away from field
                val handover1 = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover1)
                val readerSession1 = prepareReaderSession(handover1, listOf("given_name"))
                val readerTransport1 = createReaderTransport(tag1)
                readerTransport1.open(readerSession1.eReaderKey.publicKey)
                readerTransport1.sendMessage(readerSession1.encryptedDeviceRequest)

                withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                readerTransport1.close()

                fixture.approveConsent.complete(Unit)
                withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                fixture.approveBiometric.complete(Unit)
                withTimeout(5.seconds) {
                    presentmentModel.state.first { it == PresentmentModel.State.Sending }
                }

                // 2. Stray non-mdoc reader polls the device with SELECT APPLICATION followed by unsupported READ BINARY
                val strayTag = ServiceLoopbackIsoTag()
                val strayService = createNfcV2Service(strayTag) { buildSettings() }
                strayTag.service = strayService
                strayService.start()
                try {
                    val selectResp = strayTag.transceive(
                        CommandApdu(
                            cla = 0x00,
                            ins = Nfc.INS_SELECT,
                            p1 = Nfc.INS_SELECT_P1_APPLICATION,
                            p2 = 0x00,
                            payload = Nfc.MDOC_NFC_ENGAGEMENT_V2_AID,
                            le = 0,
                        )
                    )
                    assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, selectResp.status)

                    val invalidResp = strayTag.transceive(
                        CommandApdu(
                            cla = 0x00,
                            ins = Nfc.INS_READ_BINARY,
                            p1 = 0x00,
                            p2 = 0x00,
                            payload = ByteString(),
                            le = 15,
                        )
                    )
                    assertEquals(Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID, invalidResp.status)
                } finally {
                    strayTag.deactivate(NfcDeactivationReason.LINK_LOSS)
                    strayService.stop()
                }

                // Active presentment session must still be in Sending state (not Completed with error)
                assertEquals(PresentmentModel.State.Sending, presentmentModel.state.value)

                // 3. Real reader performs Tap 2 and receives the verified DeviceResponse
                val tag2 = ServiceLoopbackIsoTag()
                val service2 = createNfcV2Service(tag2) { buildSettings() }
                tag2.service = service2
                service2.start()

                try {
                    val handover2 = mdocReaderNfcHandover(
                        tag = tag2,
                        negotiatedHandoverConnectionMethods = emptyList(),
                        options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                    )
                    assertNotNull(handover2)
                    val readerSession2 = prepareReaderSession(handover2, listOf("given_name"))
                    val readerTransport2 = createReaderTransport(tag2)
                    readerTransport2.open(readerSession2.eReaderKey.publicKey)
                    readerTransport2.sendMessage(readerSession2.encryptedDeviceRequest)

                    val encryptedResponse = withTimeout(5.seconds) {
                        readerTransport2.waitForMessage()
                    }
                    val (encodedDeviceResponse, status) = readerSession2.sessionEncryption.decryptMessage(encryptedResponse)
                    assertNull(status)
                    assertNotNull(encodedDeviceResponse)

                    val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                    deviceResponse.verify(sessionTranscript = readerSession2.sessionTranscript)
                    assertEquals(1, deviceResponse.documents.size)

                    readerTransport2.sendMessage(
                        SessionEncryption.encodeStatus(
                            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            sequenceNumber = readerSession2.sessionEncryption.nextSequenceNumber,
                        )
                    )
                    val completedState = withTimeout(5.seconds) {
                        presentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertNull(completedState.error)
                    readerTransport2.close()
                } finally {
                    service2.stop()
                }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Verifies that when the reader sends a [DeviceRequest] that cannot be satisfied by the
     * wallet's [PresentmentSource] (e.g., an empty [org.multipaz.document.DocumentStore]) while
     * the phone remains in the NFC field:
     * 1. [org.multipaz.presentment.Iso18013Presentment] throws
     *    [PresentmentCannotSatisfyRequestException].
     * 2. Because `sendSessionTermination == true` and `transport.isNfcConnected.value == true`,
     *    [org.multipaz.presentment.Iso18013Presentment]'s `finally` block sends a
     *    session-termination status message (`Constants.SESSION_DATA_STATUS_SESSION_TERMINATION`)
     *    over NFC so the reader's `waitForMessage()` unblocks immediately with `status = 20` and
     *    no credential data.
     * 3. [MdocNfcV2Service] transitions [PresentmentModel.state] to
     *    [PresentmentModel.State.Completed] with [PresentmentCannotSatisfyRequestException].
     */
    @Test
    fun nfcV2ServiceUnsatisfiableRequestSendsSessionTerminationToReader() = runTest {
        withContext(Dispatchers.Default) {
            val emptySource = createPresentmentSource()
            val presentmentModel = PresentmentModel()
            val promptModel = createTestPromptModel()

            val tag1 = ServiceLoopbackIsoTag()
            val service1 = createNfcV2Service(tag1) {
                MdocNfcV2Service.Settings(
                    source = emptySource,
                    promptModel = promptModel,
                    presentmentModel = presentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    onlyAllowNfc = true,
                    handoverPreferredOrder = emptyList(),
                    transportOptions = MdocTransportOptions(),
                )
            }
            tag1.service = service1
            service1.start()

            try {
                val handover = mdocReaderNfcHandover(
                    tag = tag1,
                    negotiatedHandoverConnectionMethods = emptyList(),
                    options = MdocReaderNfcHandoverOptions(useNfcV2 = true),
                )
                assertNotNull(handover)
                val readerSession = prepareReaderSession(handover, listOf("given_name"))
                val readerTransport = createReaderTransport(tag1)
                readerTransport.open(readerSession.eReaderKey.publicKey)
                readerTransport.sendMessage(readerSession.encryptedDeviceRequest)

                val responseMessage = withTimeout(5.seconds) {
                    readerTransport.waitForMessage()
                }
                val (encodedDeviceResponse, status) = readerSession.sessionEncryption.decryptMessage(responseMessage)
                assertNull(encodedDeviceResponse)
                assertEquals(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION, status)

                val completedState = withTimeout(5.seconds) {
                    presentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertIs<PresentmentCannotSatisfyRequestException>(completedState.error)
                readerTransport.close()
            } finally {
                tag1.deactivate(NfcDeactivationReason.LINK_LOSS)
                service1.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies a single-tap ISO/IEC 18013-5:2021 NDEF engagement ([MdocNdefService]) followed
     * immediately by NFC data transfer ([MdocNfcDataTransferService]) routed through
     * [NfcApduRouter] without dropping the RF field between the two AIDs:
     * 1. Reader selects `D2760000850101` ([Nfc.NDEF_APPLICATION_ID]) and performs static handover
     *    offering [MdocConnectionMethodNfc].
     * 2. Reader immediately selects `A0000002480400` ([Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID])
     *    in the same tap.
     * 3. [NfcApduRouter] deactivates [MdocNdefService] with [NfcDeactivationReason.DESELECTED]
     *    and routes the `SELECT APPLICATION` and subsequent `ENVELOPE` / `GET RESPONSE` APDUs
     *    to [MdocNfcDataTransferService].
     * 4. [org.multipaz.mdoc.transport.NfcTransportMdoc.processCommandApdu] waits for `startTransaction()`
     *    to open the [org.multipaz.mdoc.transport.NfcTransportMdoc] instance if needed, and
     *    delivers the verified [DeviceResponse] over NFC.
     */
    @Test
    fun nfcApduRouterNdefToNfcDataTransferMidTapDeselectsNdefAndCompletesPresentment() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            val presentmentModel = PresentmentModel()
            val ndefDeactivationReasons = mutableListOf<NfcDeactivationReason>()

            val tag = ServiceLoopbackIsoTag()
            val router = object : NfcApduRouter() {
                override fun sendResponseApdu(responseApdu: ResponseApdu) {
                    tag.responseChannel.trySend(responseApdu)
                }

                override fun buildServices(): Map<ByteString, NfcApduService> {
                    val ndefService = object : MdocNdefService() {
                        override suspend fun getSettings() = MdocNdefService.Settings(
                            source = fixture.source,
                            promptModel = fixture.promptModel,
                            presentmentModel = presentmentModel,
                            launchUi = null,
                            sessionEncryptionCurve = EcCurve.P256,
                            useNegotiatedHandover = false,
                            negotiatedHandoverPreferredOrder = emptyList(),
                            staticHandoverBleCentralClientModeEnabled = false,
                            staticHandoverBlePeripheralServerModeEnabled = false,
                            staticHandoverNfcDataTransferEnabled = true,
                            transportOptions = MdocTransportOptions(),
                            transportFactory = object : MdocTransportFactory {
                                override fun createTransport(
                                    connectionMethod: MdocConnectionMethod,
                                    role: MdocRole,
                                    options: MdocTransportOptions
                                ): MdocTransport = NfcTransportMdoc(role, options, connectionMethod)
                            },
                        )

                        override suspend fun processDeactivated(reason: NfcDeactivationReason) {
                            ndefDeactivationReasons.add(reason)
                            super.processDeactivated(reason)
                        }
                    }
                    return mapOf(
                        Nfc.NDEF_APPLICATION_ID to ndefService,
                        Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID to MdocNfcDataTransferService(),
                    )
                }
            }
            tag.combinedService = router
            router.start()

            try {
                val handover = mdocReaderNfcHandover(
                    tag = tag,
                    negotiatedHandoverConnectionMethods = emptyList(),
                )
                assertNotNull(handover)
                val nfcMethod = handover.connectionMethods.filterIsInstance<MdocConnectionMethodNfc>().single()

                val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
                val deviceEngagementItem = Cbor.decode(handover.encodedDeviceEngagement.toByteArray())
                val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementItem)
                val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                val sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceEngagementItem))))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
                    add(handover.handover)
                }
                val sessionEncryption = SessionEncryption(
                    role = MdocRole.MDOC_READER,
                    eSelfKey = eReaderKey,
                    remotePublicKey = deviceEngagement.eDeviceKey,
                    encodedSessionTranscript = Cbor.encode(sessionTranscript),
                )
                val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)
                        )
                    )
                }
                val encryptedDeviceRequest = sessionEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                    statusCode = null,
                )

                val readerTransport = NfcTransportMdocReader(
                    role = MdocRole.MDOC_READER,
                    options = MdocTransportOptions(),
                    connectionMethod = nfcMethod,
                )
                readerTransport.setTag(tag)
                readerTransport.open(deviceEngagement.eDeviceKey)
                try {
                    // Selecting A0000002480400 in readerTransport.open() must have deselected MdocNdefService
                    withTimeout(5.seconds) {
                        while (ndefDeactivationReasons.isEmpty()) {
                            delay(10.milliseconds)
                        }
                    }
                    assertEquals(listOf(NfcDeactivationReason.DESELECTED), ndefDeactivationReasons)

                    readerTransport.sendMessage(encryptedDeviceRequest)

                    withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                    fixture.approveConsent.complete(Unit)
                    withTimeout(5.seconds) { fixture.biometricPromptShown.await() }
                    fixture.approveBiometric.complete(Unit)

                    val encryptedResponse = withTimeout(5.seconds) {
                        readerTransport.waitForMessage()
                    }
                    val (encodedDeviceResponse, status) = sessionEncryption.decryptMessage(encryptedResponse)
                    assertNull(status)
                    assertNotNull(encodedDeviceResponse)

                    val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                    deviceResponse.verify(sessionTranscript = sessionTranscript)
                    assertEquals(1, deviceResponse.documents.size)

                    readerTransport.sendMessage(
                        SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                    )
                    val completedState = withTimeout(5.seconds) {
                        presentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertNull(completedState.error)
                } finally {
                    readerTransport.close()
                    sessionEncryption.close()
                    eReaderKey.close()
                }
            } finally {
                tag.deactivate(NfcDeactivationReason.LINK_LOSS)
                router.stop()
                fixture.close()
            }
        }
    }

    /**
     * Verifies that when NFC engagement fails in [MdocNdefService] and [MdocNfcV2Service],
     * [PresentmentModel.State.Completed] is populated with [MdocNfcEngagementException] and
     * telemetry recording [PresentmentOutcome.NFC_ENGAGEMENT_FAILED].
     */
    @Test
    fun nfcServicesEngagementFailureReportsNfcEngagementFailedOutcome() = runTest {
        withContext(Dispatchers.Default) {
            val source = createPresentmentSource()
            val promptModel = createTestPromptModel()

            // 1. MdocNdefService: link loss before handover completes (after selecting Capability Container E103)
            val ndefPresentmentModel = PresentmentModel()
            val ndefTag = ServiceLoopbackIsoTag()
            val ndefService = createNdefService(ndefTag) {
                MdocNdefService.Settings(
                    source = source,
                    promptModel = promptModel,
                    presentmentModel = ndefPresentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    useNegotiatedHandover = false,
                    negotiatedHandoverPreferredOrder = emptyList(),
                    staticHandoverBleCentralClientModeEnabled = true,
                    staticHandoverBlePeripheralServerModeEnabled = false,
                    staticHandoverNfcDataTransferEnabled = false,
                    transportOptions = MdocTransportOptions(),
                )
            }
            ndefTag.service = ndefService
            ndefService.start()
            try {
                ndefTag.selectApplication(Nfc.NDEF_APPLICATION_ID)
                ndefTag.selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID)
                ndefTag.deactivate(NfcDeactivationReason.LINK_LOSS)

                val completedNdef = withTimeout(5.seconds) {
                    ndefPresentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertIs<MdocNfcEngagementException>(completedNdef.error)
                assertNotNull(completedNdef.telemetry)
                assertEquals(PresentmentOutcome.NFC_ENGAGEMENT_FAILED, completedNdef.telemetry.outcome)
            } finally {
                ndefService.stop()
            }

            // 2. MdocNfcV2Service: invalid APDU instruction during engagement
            val nfcV2PresentmentModel = PresentmentModel()
            val nfcV2Tag = ServiceLoopbackIsoTag()
            val nfcV2Service = createNfcV2Service(nfcV2Tag) {
                MdocNfcV2Service.Settings(
                    source = source,
                    promptModel = promptModel,
                    presentmentModel = nfcV2PresentmentModel,
                    launchUi = null,
                    sessionEncryptionCurve = EcCurve.P256,
                    onlyAllowNfc = true,
                    handoverPreferredOrder = emptyList(),
                    transportOptions = MdocTransportOptions(),
                )
            }
            nfcV2Tag.service = nfcV2Service
            nfcV2Service.start()
            try {
                nfcV2Tag.selectApplication(Nfc.MDOC_NFC_ENGAGEMENT_V2_AID)
                val badResponse = nfcV2Tag.transceive(
                    CommandApdu(
                        cla = 0x00,
                        ins = Nfc.INS_READ_BINARY,
                        p1 = 0x00,
                        p2 = 0x00,
                        payload = ByteString(),
                        le = 16,
                    )
                )
                assertEquals(
                    Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID,
                    badResponse.status
                )

                val completedNfcV2 = withTimeout(5.seconds) {
                    nfcV2PresentmentModel.state.first { it is PresentmentModel.State.Completed }
                } as PresentmentModel.State.Completed
                assertIs<MdocNfcEngagementException>(completedNfcV2.error)
                assertNotNull(completedNfcV2.telemetry)
                assertEquals(PresentmentOutcome.NFC_ENGAGEMENT_FAILED, completedNfcV2.telemetry.outcome)
            } finally {
                nfcV2Service.stop()
                promptModel.cancel()
            }
        }
    }

    /**
     * Verifies that in [MdocNdefService]:
     * 1. A failure during `waitForConnection()` before [Iso18013Presentment] starts still populates
     *    non-null telemetry with [PresentmentOutcome.TRANSPORT_ERROR].
     * 2. If the transport transitions to [MdocTransport.State.FAILED] while [Iso18013Presentment]
     *    is suspended waiting on user consent (outside `waitForMessage()`), `monitorJob`'s
     *    cancellation is classified as [PresentmentOutcome.TRANSPORT_ERROR] rather than
     *    [PresentmentOutcome.CANCELED_BY_USER].
     */
    @Test
    fun ndefServicePrePresentmentAndMidConsentTransportFailureOutcomes() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = createDoubleTapFixture(this)
            try {
                // 1. Pre-Iso18013Presentment failure: transport fails during open() inside waitForConnection()
                val prePresentmentModel = PresentmentModel()
                val tag1 = ServiceLoopbackIsoTag()
                val ndefService1 = createNdefService(tag1) {
                    MdocNdefService.Settings(
                        source = fixture.source,
                        promptModel = fixture.promptModel,
                        presentmentModel = prePresentmentModel,
                        launchUi = null,
                        sessionEncryptionCurve = EcCurve.P256,
                        useNegotiatedHandover = false,
                        negotiatedHandoverPreferredOrder = emptyList(),
                        staticHandoverBleCentralClientModeEnabled = true,
                        staticHandoverBlePeripheralServerModeEnabled = false,
                        staticHandoverNfcDataTransferEnabled = false,
                        transportOptions = MdocTransportOptions(),
                        transportFactory = object : MdocTransportFactory {
                            override fun createTransport(
                                connectionMethod: MdocConnectionMethod,
                                role: MdocRole,
                                options: MdocTransportOptions
                            ): MdocTransport = object : MdocTransport() {
                                private val _state = MutableStateFlow(State.IDLE)
                                override val state: StateFlow<State> = _state
                                override val role: MdocRole = role
                                override val connectionMethod: MdocConnectionMethod = connectionMethod
                                override val scanningTime: Duration? = null
                                override suspend fun advertise() {
                                    _state.value = State.ADVERTISING
                                }
                                override suspend fun open(eSenderKey: EcPublicKey) {
                                    _state.value = State.FAILED
                                    throw MdocTransportException("BLE open failed")
                                }
                                override suspend fun sendMessage(message: ByteArray) {}
                                override suspend fun waitForMessage(): ByteArray = byteArrayOf()
                                override suspend fun close() {
                                    _state.value = State.CLOSED
                                }
                            }
                        },
                    )
                }
                tag1.service = ndefService1
                ndefService1.start()
                try {
                    val handover1 = mdocReaderNfcHandover(
                        tag = tag1,
                        negotiatedHandoverConnectionMethods = emptyList(),
                    )
                    assertNotNull(handover1)
                    val completed1 = withTimeout(5.seconds) {
                        prePresentmentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertNotNull(completed1.telemetry)
                    assertEquals(PresentmentOutcome.TRANSPORT_ERROR, completed1.telemetry.outcome)
                } finally {
                    ndefService1.stop()
                }

                // 2. Mid-consent transport failure: transport transitions to State.FAILED while waiting on consent
                val midConsentModel = PresentmentModel()
                val transportStateFlow = MutableStateFlow(MdocTransport.State.IDLE)
                val incomingRequest = CompletableDeferred<ByteArray>()
                val tag2 = ServiceLoopbackIsoTag()
                val ndefService2 = createNdefService(tag2) {
                    MdocNdefService.Settings(
                        source = fixture.source,
                        promptModel = fixture.promptModel,
                        presentmentModel = midConsentModel,
                        launchUi = null,
                        sessionEncryptionCurve = EcCurve.P256,
                        useNegotiatedHandover = false,
                        negotiatedHandoverPreferredOrder = emptyList(),
                        staticHandoverBleCentralClientModeEnabled = true,
                        staticHandoverBlePeripheralServerModeEnabled = false,
                        staticHandoverNfcDataTransferEnabled = false,
                        transportOptions = MdocTransportOptions(),
                        transportFactory = object : MdocTransportFactory {
                            override fun createTransport(
                                connectionMethod: MdocConnectionMethod,
                                role: MdocRole,
                                options: MdocTransportOptions
                            ): MdocTransport = object : MdocTransport() {
                                override val state: StateFlow<State> = transportStateFlow
                                override val role: MdocRole = role
                                override val connectionMethod: MdocConnectionMethod = connectionMethod
                                override val scanningTime: Duration? = null
                                override suspend fun advertise() {
                                    transportStateFlow.value = State.ADVERTISING
                                }
                                override suspend fun open(eSenderKey: EcPublicKey) {
                                    transportStateFlow.value = State.CONNECTED
                                }
                                override suspend fun sendMessage(message: ByteArray) {}
                                override suspend fun waitForMessage(): ByteArray = incomingRequest.await()
                                override suspend fun close() {
                                    if (transportStateFlow.value != State.FAILED) {
                                        transportStateFlow.value = State.CLOSED
                                    }
                                }
                            }
                        },
                    )
                }
                tag2.service = ndefService2
                ndefService2.start()
                try {
                    val handover2 = mdocReaderNfcHandover(
                        tag = tag2,
                        negotiatedHandoverConnectionMethods = emptyList(),
                    )
                    assertNotNull(handover2)
                    val readerSession2 = prepareReaderSession(handover2, listOf("given_name"))
                    incomingRequest.complete(readerSession2.encryptedDeviceRequest)

                    withTimeout(5.seconds) { fixture.consentPromptShown.await() }
                    assertEquals(PresentmentModel.State.WaitingForUserInput, midConsentModel.state.value)

                    // Simulate transport failing while suspended in showConsentPromptFn
                    transportStateFlow.value = MdocTransport.State.FAILED

                    val completed2 = withTimeout(5.seconds) {
                        midConsentModel.state.first { it is PresentmentModel.State.Completed }
                    } as PresentmentModel.State.Completed
                    assertIs<MdocTransportException>(completed2.error)
                    assertNotNull(completed2.telemetry)
                    assertEquals(PresentmentOutcome.TRANSPORT_ERROR, completed2.telemetry.outcome)
                    readerSession2.sessionEncryption.close()
                    readerSession2.eReaderKey.close()
                } finally {
                    ndefService2.stop()
                }
            } finally {
                fixture.close()
            }
        }
    }
}
