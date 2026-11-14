package org.multipaz.presentment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventLogger
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocEngagementType
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.engagement.ReaderEngagement
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.engagement.buildReaderEngagement
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.prompt.ShowConsentPromptFn
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.toBase64Url
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Iso18013ReaderQrPresentmentTest {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private class RecordingEventLogger : EventLogger {
        val events = mutableListOf<Event>()
        override suspend fun addEvent(event: Event): Event {
            events.add(event)
            return event
        }

        override fun addEventAsync(event: Event) {
            events.add(event)
        }
    }

    private suspend fun createTestPresentmentSource(
        eventLogger: EventLogger? = null,
        showConsentPromptFn: ShowConsentPromptFn = ::promptModelSilentConsent,
    ): SimplePresentmentSource {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()
        return SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            eventLogger = eventLogger,
            showConsentPromptFn = showConsentPromptFn,
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
            domainsKeyBoundSdJwt = listOf("sdjwt"),
        )
    }

    private suspend fun buildTestDeviceRequest(
        encodedSessionTranscript: ByteArray,
        elementName: String = "given_name",
    ): DeviceRequest = buildDeviceRequest(sessionTranscript = Cbor.decode(encodedSessionTranscript)) {
        addDocRequest(
            docType = DrivingLicense.MDL_DOCTYPE,
            nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf(elementName to false)),
        )
    }

    /**
     * In-memory [MdocTransportFactory] supporting single-mode and dual-mode (`DirectL2capCentral` +
     * `GattCentral`) BLE reader advertisement and mdoc connection for deterministic Reader QR tests.
     */
    private class InMemoryReaderQrTransportFactory(
        private val simulateConnectionHang: Boolean = false,
        private val publishedPsm: Int = 192,
    ) : MdocTransportFactory {
        private val lock = Mutex()
        private val advertisedReaderSlots = mutableMapOf<String, ReaderTransport>()
        val createdHolderTransports = mutableListOf<HolderTransport>()

        inner class ReaderTransport(
            private val initialMethods: List<MdocConnectionMethod>,
        ) : MdocTransport() {
            override val role: MdocRole = MdocRole.MDOC_READER
            private val _state = MutableStateFlow(State.IDLE)
            override val state: StateFlow<State> = _state
            override val scanningTime: Duration? = null

            private var resolvedMethods: List<MdocConnectionMethod> = initialMethods
            var connectedMethod: MdocConnectionMethod? = null
            override val connectionMethod: MdocConnectionMethod?
                get() = connectedMethod ?: resolvedMethods.firstOrNull()
            override val connectionMethods: List<MdocConnectionMethod>
                get() = resolvedMethods

            val readerOpenKeyDeferred = CompletableDeferred<EcPublicKey>()
            val connectedDeferred = CompletableDeferred<Unit>()
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            var outgoing: Channel<ByteArray>? = null

            override suspend fun advertise() {
                resolvedMethods = initialMethods.map { method ->
                    if (method is MdocConnectionMethod.Ble.DirectL2capCentral && method.psm == null) {
                        method.copy(psm = publishedPsm)
                    } else {
                        method
                    }
                }
                _state.value = State.ADVERTISING
                lock.withLock {
                    for (method in resolvedMethods) {
                        advertisedReaderSlots[method.toString()] = this
                    }
                }
            }

            override suspend fun open(eSenderKey: EcPublicKey) {
                readerOpenKeyDeferred.complete(eSenderKey)
                try {
                    connectedDeferred.await()
                    _state.value = State.CONNECTED
                } catch (e: MdocTransportClosedException) {
                    throw e
                }
            }

            fun acceptHolderConnection(
                selectedMethod: MdocConnectionMethod,
                holderToReader: Channel<ByteArray>,
                readerToHolder: Channel<ByteArray>,
            ) {
                connectedMethod = selectedMethod
                outgoing = readerToHolder
                connectedDeferred.complete(Unit)
            }

            override suspend fun sendMessage(message: ByteArray) {
                val out = outgoing ?: throw MdocTransportException("Reader transport not connected")
                out.send(message.copyOf())
            }

            override suspend fun waitForMessage(): ByteArray =
                incoming.receiveCatching().getOrNull() ?: throw MdocTransportClosedException("Reader transport closed")

            override suspend fun close() {
                lock.withLock {
                    for (method in resolvedMethods) {
                        if (advertisedReaderSlots[method.toString()] === this) {
                            advertisedReaderSlots.remove(method.toString())
                        }
                    }
                }
                if (_state.value != State.FAILED) {
                    _state.value = State.CLOSED
                }
                incoming.close()
                outgoing?.close()
                val closedException = MdocTransportClosedException("Closed before connection")
                readerOpenKeyDeferred.completeExceptionally(closedException)
                connectedDeferred.completeExceptionally(closedException)
            }
        }

        inner class HolderTransport(
            override val connectionMethod: MdocConnectionMethod,
        ) : MdocTransport() {
            override val role: MdocRole = MdocRole.MDOC
            private val _state = MutableStateFlow(State.IDLE)
            override val state: StateFlow<State> = _state
            override val scanningTime: Duration? = 5.milliseconds

            private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            private var outgoing: Channel<ByteArray>? = null
            private val hangDeferred = CompletableDeferred<Unit>()
            var wasClosed = false

            override suspend fun advertise() {}

            override suspend fun open(eSenderKey: EcPublicKey) {
                _state.value = State.CONNECTING
                if (simulateConnectionHang) {
                    hangDeferred.await()
                    return
                }
                val readerTransport = lock.withLock {
                    advertisedReaderSlots[connectionMethod.toString()]
                } ?: throw MdocTransportException("No advertised reader for $connectionMethod")

                val readerExpectedKey = readerTransport.readerOpenKeyDeferred.await()
                if (readerExpectedKey != eSenderKey) {
                    _state.value = State.FAILED
                    throw MdocTransportException("Mismatched eSenderKey")
                }

                outgoing = readerTransport.incoming
                readerTransport.acceptHolderConnection(
                    selectedMethod = connectionMethod,
                    holderToReader = readerTransport.incoming,
                    readerToHolder = incoming,
                )
                _state.value = State.CONNECTED
            }

            override suspend fun sendMessage(message: ByteArray) {
                val out = outgoing ?: throw MdocTransportException("Holder transport not connected")
                out.send(message.copyOf())
            }

            override suspend fun waitForMessage(): ByteArray =
                incoming.receiveCatching().getOrNull() ?: throw MdocTransportClosedException("Holder transport closed")

            override suspend fun close() {
                wasClosed = true
                if (_state.value != State.FAILED) {
                    _state.value = State.CLOSED
                }
                hangDeferred.cancel()
                incoming.close()
                outgoing?.close()
            }
        }

        override fun createTransport(
            connectionMethod: MdocConnectionMethod,
            role: MdocRole,
            options: MdocTransportOptions,
        ): MdocTransport = when (role) {
            MdocRole.MDOC_READER -> ReaderTransport(listOf(connectionMethod))
            MdocRole.MDOC -> HolderTransport(connectionMethod).also { createdHolderTransports.add(it) }
        }

        override fun createTransports(
            connectionMethods: List<MdocConnectionMethod>,
            role: MdocRole,
            options: MdocTransportOptions,
        ): List<MdocTransport> {
            if (connectionMethods.isEmpty()) return emptyList()
            if (role == MdocRole.MDOC) {
                return connectionMethods.map { createTransport(it, role, options) }
            }
            val result = mutableListOf<MdocTransport>()
            val consumed = mutableSetOf<Int>()
            for (i in connectionMethods.indices) {
                if (i in consumed) continue
                val current = connectionMethods[i]
                val isCentralClient = current is MdocConnectionMethod.Ble.DirectL2capCentral ||
                    current is MdocConnectionMethod.Ble.GattCentral
                if (isCentralClient && (current as MdocConnectionMethod.Ble).uuid != null) {
                    val group = mutableListOf<MdocConnectionMethod>(current)
                    consumed.add(i)
                    for (j in (i + 1) until connectionMethods.size) {
                        val candidate = connectionMethods[j]
                        val candidateIsCentralClient = candidate is MdocConnectionMethod.Ble.DirectL2capCentral ||
                            candidate is MdocConnectionMethod.Ble.GattCentral
                        if (j !in consumed &&
                            candidateIsCentralClient &&
                            (candidate as MdocConnectionMethod.Ble).uuid == current.uuid
                        ) {
                            group.add(candidate)
                            consumed.add(j)
                        }
                    }
                    result.add(ReaderTransport(group))
                } else {
                    consumed.add(i)
                    result.add(ReaderTransport(listOf(current)))
                }
            }
            return result
        }
    }

    @Test
    fun endToEndReaderQrAcrossBleModesAndPreferenceOrder() = runTest {
        data class ModeCase(
            val preferredOrder: List<String>,
            val bleDirectL2capEnabled: Boolean,
            val expectedPrefix: String,
        )

        val cases = listOf(
            ModeCase(
                preferredOrder = emptyList(),
                bleDirectL2capEnabled = true,
                expectedPrefix = "ble:direct_l2cap_central:",
            ),
            ModeCase(
                preferredOrder = emptyList(),
                bleDirectL2capEnabled = false,
                expectedPrefix = "ble:gatt_central:",
            ),
            ModeCase(
                preferredOrder = listOf("ble:gatt_peripheral:", "ble:direct_l2cap_central:"),
                bleDirectL2capEnabled = true,
                expectedPrefix = "ble:gatt_peripheral:",
            ),
        )

        for (case in cases) {
            val eventLogger = RecordingEventLogger()
            val source = createTestPresentmentSource(eventLogger = eventLogger)
            val factory = InMemoryReaderQrTransportFactory()
            val bleUuid = UUID.randomUUID()
            val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

            val readerMethods = listOf(
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = bleUuid),
                MdocConnectionMethod.Ble.GattCentral(uuid = bleUuid),
                MdocConnectionMethod.Ble.GattPeripheral(uuid = bleUuid),
            )
            val advertisedTransports = readerMethods.advertise(
                role = MdocRole.MDOC_READER,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
            assertEquals(2, advertisedTransports.size) // Dual-mode Central + single-mode Peripheral

            val readerEngagement = buildReaderEngagement(
                eReaderKey = eReaderKey.publicKey,
                version = "1.1",
            ) {
                advertisedTransports.forEach { t ->
                    t.connectionMethods.forEach { addConnectionMethod(it) }
                }
            }
            val encodedReaderEngagement = Cbor.encode(
                readerEngagement.toDataItem(MdocEngagementType.READER_QR)
            )
            val uri = "mdoc:" + encodedReaderEngagement.toBase64Url()
            val readerHandover = ReaderEngagement.computeHandover(encodedReaderEngagement)

            var holderTelemetry: PresentmentTelemetry? = null
            var connectingCalled = false
            val readerDone = CompletableDeferred<MdocConnectionMethod>()

            val readerJob = launch {
                val connectedReaderTransport = advertisedTransports.waitForConnection(
                    eSenderKey = eReaderKey.publicKey
                )
                try {
                    val msg1 = connectedReaderTransport.waitForMessage()
                    val encodedDeBytes = SessionEncryption.getDeviceEngagementBytes(msg1)
                    val parsedDe = DeviceEngagement.fromDataItem(
                        dataItem = Cbor.decode(encodedDeBytes),
                        engagementType = MdocEngagementType.READER_QR,
                    )
                    assertEquals("1.1", parsedDe.version)
                    assertTrue(parsedDe.connectionMethods.isEmpty())
                    assertEquals(true, parsedDe.capabilities[Capability.READER_AUTH_ALL_SUPPORT]?.asBoolean)
                    assertEquals(true, parsedDe.capabilities[Capability.EXTENDED_REQUEST_SUPPORT]?.asBoolean)

                    val encodedEReaderKeyCose = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                    val sessionTranscript = buildCborArray {
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeBytes)))
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKeyCose)))
                        add(readerHandover)
                    }
                    val encodedTranscript = Cbor.encode(sessionTranscript)
                    val readerEncryption = SessionEncryption(
                        role = MdocRole.MDOC_READER,
                        eSelfKey = eReaderKey,
                        remotePublicKey = parsedDe.eDeviceKey,
                        encodedSessionTranscript = encodedTranscript,
                    ).apply {
                        setSendSessionEstablishment(false)
                    }

                    val request = buildTestDeviceRequest(encodedTranscript)
                    val encryptedRequest = readerEncryption.encryptMessage(
                        messagePlaintext = Cbor.encode(request.toDataItem()),
                        statusCode = null,
                    )
                    // Verify message 2 omits "eReaderKey" in Reader QR
                    assertFalse(Cbor.decode(encryptedRequest).hasKey("eReaderKey"))
                    connectedReaderTransport.sendMessage(encryptedRequest)

                    val encryptedResponse = connectedReaderTransport.waitForMessage()
                    val (responseBytes, status) = readerEncryption.decryptMessage(encryptedResponse)
                    assertNotNull(responseBytes)
                    // Holder sends status 20 or follows with session termination; send reader termination if still open
                    val response = DeviceResponse.fromDataItem(Cbor.decode(responseBytes))
                    response.verify(sessionTranscript = sessionTranscript)
                    assertEquals(1, response.documents.size)
                    assertEquals(DrivingLicense.MDL_DOCTYPE, response.documents.first().docType)

                    if (status != Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                        connectedReaderTransport.sendMessage(
                            readerEncryption.encryptMessage(
                                messagePlaintext = null,
                                statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                            )
                        )
                    }
                    readerDone.complete(connectedReaderTransport.connectionMethod!!)
                } finally {
                    connectedReaderTransport.close()
                }
            }

            Iso18013ReaderQrPresentment(
                uri = uri,
                source = source,
                transportFactory = factory,
                preferredConnectionMethodOrder = case.preferredOrder,
                bleDirectL2capEnabled = case.bleDirectL2capEnabled,
                connectionTimeout = 5.seconds,
                timeout = 5.seconds,
                onConnecting = { connectingCalled = true },
                onTelemetry = { holderTelemetry = it },
            )
            readerJob.join()

            assertTrue(connectingCalled)
            val chosenMethod = readerDone.await()
            assertTrue(
                chosenMethod.toString().startsWith(case.expectedPrefix),
                "Expected prefix ${case.expectedPrefix}, got $chosenMethod"
            )
            assertNotNull(holderTelemetry)
            assertEquals(EngagementType.READER_QR_CODE, holderTelemetry!!.engagementType)
            assertEquals(PresentmentOutcome.SUCCESS, holderTelemetry!!.outcome)
            assertEquals(1, eventLogger.events.size)
            val event = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
            assertEquals(EngagementType.READER_QR_CODE, event.telemetry?.engagementType)
        }
    }

    @Test
    fun endToEndReaderQrAcrossMultipleCurves() = runTest {
        val candidateCurves = listOf(
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.X25519,
        ).filter { it in Crypto.supportedCurves && it.supportsKeyAgreement }
        assertTrue(candidateCurves.isNotEmpty())

        for (curve in candidateCurves) {
            val source = createTestPresentmentSource()
            val factory = InMemoryReaderQrTransportFactory()
            val bleUuid = UUID.randomUUID()
            val eReaderKey = Crypto.createEcPrivateKey(curve)

            val advertised = listOf(
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = bleUuid)
            ).advertise(
                role = MdocRole.MDOC_READER,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
            val encodedReaderEngagement = Cbor.encode(
                buildReaderEngagement(eReaderKey = eReaderKey.publicKey, version = "1.1") {
                    advertised.forEach { t -> t.connectionMethods.forEach { addConnectionMethod(it) } }
                }.toDataItem(MdocEngagementType.READER_QR)
            )
            val uri = "mdoc:" + encodedReaderEngagement.toBase64Url()
            val handover = ReaderEngagement.computeHandover(encodedReaderEngagement)

            val readerJob = launch {
                val readerTransport = advertised.waitForConnection(eReaderKey.publicKey)
                try {
                    val encodedDeBytes = SessionEncryption.getDeviceEngagementBytes(readerTransport.waitForMessage())
                    val de = DeviceEngagement.fromDataItem(
                        Cbor.decode(encodedDeBytes),
                        MdocEngagementType.READER_QR,
                    )
                    assertEquals(curve, de.eDeviceKey.curve)
                    val sessionTranscript = buildCborArray {
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeBytes)))
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem()))))
                        add(handover)
                    }
                    val readerEncryption = SessionEncryption(
                        role = MdocRole.MDOC_READER,
                        eSelfKey = eReaderKey,
                        remotePublicKey = de.eDeviceKey,
                        encodedSessionTranscript = Cbor.encode(sessionTranscript),
                    ).apply { setSendSessionEstablishment(false) }

                    val req = buildTestDeviceRequest(Cbor.encode(sessionTranscript))
                    readerTransport.sendMessage(
                        readerEncryption.encryptMessage(Cbor.encode(req.toDataItem()), null)
                    )
                    val (respBytes, _) = readerEncryption.decryptMessage(readerTransport.waitForMessage())
                    assertNotNull(respBytes)
                    DeviceResponse.fromDataItem(Cbor.decode(respBytes)).verify(sessionTranscript)
                    readerTransport.sendMessage(
                        readerEncryption.encryptMessage(null, Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                    )
                } finally {
                    readerTransport.close()
                }
            }

            Iso18013ReaderQrPresentment(
                uri = uri,
                source = source,
                transportFactory = factory,
                connectionTimeout = 5.seconds,
                timeout = 5.seconds,
            )
            readerJob.join()
        }
    }

    @Test
    fun multiRequestSessionAndTransportSpecificTermination() = runTest {
        for (useTransportSpecificTermination in listOf(false, true)) {
            val eventLogger = RecordingEventLogger()
            val source = createTestPresentmentSource(eventLogger = eventLogger)
            val factory = InMemoryReaderQrTransportFactory()
            val bleUuid = UUID.randomUUID()
            val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

            val advertised = listOf(
                MdocConnectionMethod.Ble.GattCentral(uuid = bleUuid)
            ).advertise(
                role = MdocRole.MDOC_READER,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
            val encodedReaderEngagement = Cbor.encode(
                buildReaderEngagement(eReaderKey = eReaderKey.publicKey, version = "1.1") {
                    advertised.forEach { t -> t.connectionMethods.forEach { addConnectionMethod(it) } }
                }.toDataItem(MdocEngagementType.READER_QR)
            )
            val uri = "mdoc:" + encodedReaderEngagement.toBase64Url()
            val handover = ReaderEngagement.computeHandover(encodedReaderEngagement)

            val readerJob = launch {
                val readerTransport = advertised.waitForConnection(eReaderKey.publicKey)
                try {
                    val encodedDeBytes = SessionEncryption.getDeviceEngagementBytes(readerTransport.waitForMessage())
                    val de = DeviceEngagement.fromDataItem(
                        Cbor.decode(encodedDeBytes),
                        MdocEngagementType.READER_QR,
                    )
                    val sessionTranscript = buildCborArray {
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeBytes)))
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem()))))
                        add(handover)
                    }
                    val encodedTranscript = Cbor.encode(sessionTranscript)
                    val readerEncryption = SessionEncryption(
                        role = MdocRole.MDOC_READER,
                        eSelfKey = eReaderKey,
                        remotePublicKey = de.eDeviceKey,
                        encodedSessionTranscript = encodedTranscript,
                    ).apply { setSendSessionEstablishment(false) }

                    // Request 1: given_name
                    val req1 = buildTestDeviceRequest(encodedTranscript, "given_name")
                    readerTransport.sendMessage(
                        readerEncryption.encryptMessage(Cbor.encode(req1.toDataItem()), null)
                    )
                    val (resp1Bytes, _) = readerEncryption.decryptMessage(readerTransport.waitForMessage())
                    assertNotNull(resp1Bytes)
                    DeviceResponse.fromDataItem(Cbor.decode(resp1Bytes)).verify(sessionTranscript)

                    // Request 2: family_name
                    val req2 = buildTestDeviceRequest(encodedTranscript, "family_name")
                    readerTransport.sendMessage(
                        readerEncryption.encryptMessage(Cbor.encode(req2.toDataItem()), null)
                    )
                    val (resp2Bytes, _) = readerEncryption.decryptMessage(readerTransport.waitForMessage())
                    assertNotNull(resp2Bytes)
                    DeviceResponse.fromDataItem(Cbor.decode(resp2Bytes)).verify(sessionTranscript)

                    if (useTransportSpecificTermination) {
                        readerTransport.sendMessage(byteArrayOf())
                    } else {
                        readerTransport.sendMessage(
                            readerEncryption.encryptMessage(null, Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                        )
                    }
                } finally {
                    readerTransport.close()
                }
            }

            Iso18013ReaderQrPresentment(
                uri = uri,
                source = source,
                transportFactory = factory,
                connectionTimeout = 5.seconds,
                timeout = 5.seconds,
                timeoutSubsequentRequests = 5.seconds,
            )
            readerJob.join()
            assertEquals(2, eventLogger.events.size)
        }
    }

    @Test
    fun nonCanonicalReaderEngagementAndEReaderKeyBytesPreservedInTranscript() = runTest {
        val source = createTestPresentmentSource()
        val factory = InMemoryReaderQrTransportFactory()
        val bleUuid = UUID.randomUUID()
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

        val advertised = listOf(
            MdocConnectionMethod.Ble.GattPeripheral(uuid = bleUuid)
        ).advertise(
            role = MdocRole.MDOC_READER,
            transportFactory = factory,
            options = MdocTransportOptions(),
        )

        // Build a non-canonical COSE_Key byte sequence with a 2-byte integer encoding (0x18 0x01 for kty=2 -> 0x18 0x02)
        val canonicalCoseKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        // Wrap canonicalCoseKey map entries preceded by an indefinite-length map header (0xBF ... 0xFF)
        // so its bytes differ from Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())!
        val nonCanonicalCoseKey = byteArrayOf(0xBF.toByte()) +
            canonicalCoseKey.copyOfRange(1, canonicalCoseKey.size) +
            byteArrayOf(0xFF.toByte())
        assertFalse(canonicalCoseKey.contentEquals(nonCanonicalCoseKey))

        val encodedMethods = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = advertised.flatMap { it.connectionMethods },
            engagementType = MdocEngagementType.READER_QR,
        )
        val canonicalReMap = Cbor.encode(
            buildCborMap {
                put(0, "1.1")
                putCborArray(1) {
                    add(1)
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(nonCanonicalCoseKey)))
                }
                putCborArray(2) {
                    encodedMethods.forEach { add(it) }
                }
            }
        )
        // Also wrap the outer ReaderEngagement map in indefinite-length CBOR (0xBF ... 0xFF)
        val nonCanonicalReaderEngagement = byteArrayOf(0xBF.toByte()) +
            canonicalReMap.copyOfRange(1, canonicalReMap.size) +
            byteArrayOf(0xFF.toByte())

        val uri = "mdoc:" + nonCanonicalReaderEngagement.toBase64Url()
        val expectedHandover = ReaderEngagement.computeHandover(nonCanonicalReaderEngagement)

        val readerJob = launch {
            val readerTransport = advertised.waitForConnection(eReaderKey.publicKey)
            try {
                val encodedDeBytes = SessionEncryption.getDeviceEngagementBytes(readerTransport.waitForMessage())
                val de = DeviceEngagement.fromDataItem(
                    Cbor.decode(encodedDeBytes),
                    MdocEngagementType.READER_QR,
                )
                // Reader constructs SessionTranscript using the exact non-canonical COSE_Key and ReaderEngagement bytes
                val sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeBytes)))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(nonCanonicalCoseKey)))
                    add(expectedHandover)
                }
                val encodedTranscript = Cbor.encode(sessionTranscript)
                val readerEncryption = SessionEncryption(
                    role = MdocRole.MDOC_READER,
                    eSelfKey = eReaderKey,
                    remotePublicKey = de.eDeviceKey,
                    encodedSessionTranscript = encodedTranscript,
                ).apply { setSendSessionEstablishment(false) }

                val req = buildTestDeviceRequest(encodedTranscript)
                readerTransport.sendMessage(
                    readerEncryption.encryptMessage(Cbor.encode(req.toDataItem()), null)
                )
                val (respBytes, _) = readerEncryption.decryptMessage(readerTransport.waitForMessage())
                assertNotNull(respBytes)
                DeviceResponse.fromDataItem(Cbor.decode(respBytes)).verify(sessionTranscript)
                readerTransport.sendMessage(
                    readerEncryption.encryptMessage(null, Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                )
            } finally {
                readerTransport.close()
            }
        }

        Iso18013ReaderQrPresentment(
            uri = uri,
            source = source,
            transportFactory = factory,
            connectionTimeout = 5.seconds,
            timeout = 5.seconds,
        )
        readerJob.join()
    }

    @Test
    fun validationRejectsInvalidUrisReaderEngagementsAndUnsupportedCurves() = runTest {
        val eventLogger = RecordingEventLogger()
        val source = createTestPresentmentSource(eventLogger = eventLogger)
        val factory = InMemoryReaderQrTransportFactory()
        val bleUuid = UUID.randomUUID()
        val p256Key = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

        // 1. Annex A "mdoc://" URL, wrong scheme, and empty payload are rejected
        for (badUri in listOf("mdoc://example.com/1234", "https://example.com", "mdoc:")) {
            var telemetry: PresentmentTelemetry? = null
            assertFailsWith<IllegalArgumentException> {
                Iso18013ReaderQrPresentment(
                    uri = badUri,
                    source = source,
                    transportFactory = factory,
                    onTelemetry = { telemetry = it },
                )
            }
            assertNotNull(telemetry)
            assertEquals(EngagementType.READER_QR_CODE, telemetry!!.engagementType)
        }
        assertTrue(factory.createdHolderTransports.isEmpty())

        // 2. ReaderEngagement with version "1.0", missing EReaderKey, or empty connectionMethods rejected
        val invalidReaderEngagementItems = listOf(
            // version 1.0
            buildCborMap {
                put(0, "1.0")
                putCborArray(1) {
                    add(1)
                    addTaggedEncodedCbor(Cbor.encode(p256Key.toCoseKey().toDataItem()))
                }
                putCborArray(2) {
                    MdocConnectionMethod.encodeDeviceRetrievalMethods(
                        listOf(MdocConnectionMethod.Ble.GattCentral(uuid = bleUuid)),
                        MdocEngagementType.READER_QR,
                    ).forEach { add(it) }
                }
            },
            // missing EReaderKey (key 1)
            buildCborMap {
                put(0, "1.1")
                putCborArray(2) {
                    MdocConnectionMethod.encodeDeviceRetrievalMethods(
                        listOf(MdocConnectionMethod.Ble.GattCentral(uuid = bleUuid)),
                        MdocEngagementType.READER_QR,
                    ).forEach { add(it) }
                }
            },
            // empty connectionMethods
            buildCborMap {
                put(0, "1.1")
                putCborArray(1) {
                    add(1)
                    addTaggedEncodedCbor(Cbor.encode(p256Key.toCoseKey().toDataItem()))
                }
            },
        )
        for (badItem in invalidReaderEngagementItems) {
            val badUri = "mdoc:" + Cbor.encode(badItem).toBase64Url()
            assertFailsWith<IllegalArgumentException> {
                Iso18013ReaderQrPresentment(
                    uri = badUri,
                    source = source,
                    transportFactory = factory,
                )
            }
        }

        // 3. Encoding forbidden methods (NFC or DirectL2capPeripheral) for READER_QR fails
        assertFailsWith<IllegalArgumentException> {
            buildReaderEngagement(eReaderKey = p256Key, version = "1.1") {
                addConnectionMethod(
                    MdocConnectionMethod.Nfc(
                        commandDataFieldMaxLength = 0xffff,
                        responseDataFieldMaxLength = 0x10000,
                    )
                )
            }.toDataItem(MdocEngagementType.READER_QR)
        }
        assertFailsWith<IllegalArgumentException> {
            buildReaderEngagement(eReaderKey = p256Key, version = "1.1") {
                addConnectionMethod(
                    MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = bleUuid, psm = 128)
                )
            }.toDataItem(MdocEngagementType.READER_QR)
        }

        // 4. Unsupported EReaderKey curve aborts cleanly before opening transport
        val p384Key = Crypto.createEcPrivateKey(EcCurve.P384).use { it.publicKey }
        val p384Uri = "mdoc:" + Cbor.encode(
            buildReaderEngagement(eReaderKey = p384Key, version = "1.1") {
                addConnectionMethod(MdocConnectionMethod.Ble.GattCentral(uuid = bleUuid))
            }.toDataItem(MdocEngagementType.READER_QR)
        ).toBase64Url()
        var unsupportedCurveTelemetry: PresentmentTelemetry? = null
        assertFailsWith<IllegalArgumentException> {
            Iso18013ReaderQrPresentment(
                uri = p384Uri,
                source = source,
                transportFactory = factory,
                supportedCurves = listOf(EcCurve.P256),
                onTelemetry = { unsupportedCurveTelemetry = it },
            )
        }
        assertNotNull(unsupportedCurveTelemetry)
        assertEquals(EngagementType.READER_QR_CODE, unsupportedCurveTelemetry!!.engagementType)
        assertTrue(factory.createdHolderTransports.isEmpty())
    }

    @Test
    fun deviceEngagementAndSessionMessageValidation() = runTest {
        val eDevicePriv = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        try {
            // DeviceEngagement containing key 2 (DeviceRetrievalMethods) is rejected in READER_QR context
            val deWithMethods = buildDeviceEngagement(eDeviceKey = eDevicePriv.publicKey) {
                addConnectionMethod(MdocConnectionMethod.Ble.GattPeripheral(uuid = UUID.randomUUID()))
            }.toDataItem(MdocEngagementType.DEVICE_QR)
            assertFailsWith<IllegalArgumentException> {
                DeviceEngagement.fromDataItem(deWithMethods, MdocEngagementType.READER_QR)
            }

            // Round-trip valid step-1 SessionMessage
            val validDeBytes = Cbor.encode(
                buildDeviceEngagement(eDeviceKey = eDevicePriv.publicKey, version = "1.1") {}
                    .toDataItem(MdocEngagementType.READER_QR)
            )
            val step1Message = SessionEncryption.encodeDeviceEngagementMessage(validDeBytes)
            assertContentEquals(validDeBytes, SessionEncryption.getDeviceEngagementBytes(step1Message))

            // Malformed step-1 messages rejected by getDeviceEngagementBytes
            val untaggedStep1 = Cbor.encode(buildCborMap { put("deviceEngagementBytes", validDeBytes) })
            assertFailsWith<IllegalArgumentException> {
                SessionEncryption.getDeviceEngagementBytes(untaggedStep1)
            }
            val mixedStep1 = Cbor.encode(
                buildCborMap {
                    putTaggedEncodedCbor("deviceEngagementBytes", validDeBytes)
                    put("status", 20L)
                }
            )
            assertFailsWith<IllegalArgumentException> {
                SessionEncryption.getDeviceEngagementBytes(mixedStep1)
            }
            val missingKeyStep1 = Cbor.encode(buildCborMap { put("status", 20L) })
            assertFailsWith<IllegalArgumentException> {
                SessionEncryption.getDeviceEngagementBytes(missingKeyStep1)
            }

            // Encrypted SessionEncryption.decryptMessage rejects any message carrying "deviceEngagementBytes"
            val transcript = Cbor.encode(
                buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(validDeBytes)))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(eReaderPriv.publicKey.toCoseKey().toDataItem()))))
                    add(Bstr(ByteArray(32)))
                }
            )
            SessionEncryption(
                role = MdocRole.MDOC,
                eSelfKey = eDevicePriv.duplicate(),
                remotePublicKey = eReaderPriv.publicKey,
                encodedSessionTranscript = transcript,
            ).use { mdocEncryption ->
                assertFailsWith<IllegalArgumentException> {
                    mdocEncryption.decryptMessage(step1Message)
                }
            }
        } finally {
            eDevicePriv.close()
            eReaderPriv.close()
        }
    }

    @Test
    fun connectionTimeoutThrowsPresentmentTimeoutAndClosesTransport() = runTest {
        val eventLogger = RecordingEventLogger()
        val source = createTestPresentmentSource(eventLogger = eventLogger)
        val hangingFactory = InMemoryReaderQrTransportFactory(simulateConnectionHang = true)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        val uri = "mdoc:" + Cbor.encode(
            buildReaderEngagement(eReaderKey = eReaderKey, version = "1.1") {
                addConnectionMethod(MdocConnectionMethod.Ble.GattCentral(uuid = UUID.randomUUID()))
            }.toDataItem(MdocEngagementType.READER_QR)
        ).toBase64Url()

        var capturedTelemetry: PresentmentTelemetry? = null
        assertFailsWith<PresentmentTimeout> {
            Iso18013ReaderQrPresentment(
                uri = uri,
                source = source,
                transportFactory = hangingFactory,
                connectionTimeout = 50.milliseconds,
                onTelemetry = { capturedTelemetry = it },
            )
        }
        assertEquals(1, hangingFactory.createdHolderTransports.size)
        assertTrue(hangingFactory.createdHolderTransports.single().wasClosed)
        assertNotNull(capturedTelemetry)
        assertEquals(EngagementType.READER_QR_CODE, capturedTelemetry!!.engagementType)
        assertEquals(PresentmentOutcome.CONNECTION_TIMEOUT, capturedTelemetry!!.outcome)
        assertEquals(1, eventLogger.events.size)
    }
}
