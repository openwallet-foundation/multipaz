package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcCceTransportMdoc
import org.multipaz.mdoc.transport.extractFromDo53
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.NfcTagReader
import org.multipaz.nfc.ResponseApdu
import org.multipaz.presentment.EngagementParams
import org.multipaz.presentment.FaceMatchingMode
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic protocol transcript tests for ISO/IEC 18013-5 Second Edition Clause 9.5
 * NFC Concurrent Channel Engagement (NFC CCE).
 *
 * ### Goals
 * - Verify the end-to-end wire format and state machine behavior of Multipaz's production NFC CCE
 *   primitives ([scanMdocReader], [MdocNfcCceEngagementHelper], [NfcCceTransportMdoc],
 *   [org.multipaz.mdoc.transport.NfcCceTransportMdocReader], and [Iso18013Presentment]).
 * - Capture a human-readable, deterministic transcript of every exchanged NFC APDU (`C-APDU` and
 *   `R-APDU`), secondary transport message (`BLE Reader->Mdoc` and `BLE Mdoc->Reader`), and NFC
 *   field state transition (`NFC: Field lost` and `NFC: Re-tapped`), preceded by `# `-prefixed
 *   CBOR diagnostic notation comments for payloads containing complete CBOR items.
 * - Exercise concurrent message delivery, deduplication via `"seq"` sequence numbers, NFC early
 *   release via `"disconnectNfc": true`, APDU response chaining (`61XX` / `GET RESPONSE`),
 *   backlog pruning across NFC and a secondary transport (BLE Central Client Mode + Direct L2CAP
 *   PSM), and NFC field loss recovery (via BLE fallback or NFC-only multi-tap re-engagement).
 * - Use fixed cryptographic keying material (`eDeviceKey`, `eReaderKey`, `dDeviceKey`) and MSO /
 *   mandatory mDL data elements from [TestVectors] so that every encrypted `SessionData` payload
 *   and `deviceSignature` (`ECDSA` with deterministic RFC 6979 nonces) is 100% reproducible.
 *
 * ### Test Scenarios
 * 1. [testNfcOnlySingleTapSimpleRequest]: Single-tap NFC-only CCE engagement and presentment with
 *    no secondary transport offered.
 * 2. [testReaderOffersBleMdocOnlyNfc]: Single-tap NFC CCE engagement and presentment where the
 *    reader offers BLE Central Client Mode in `CCEHandoverRequest`, but the mdoc only supports NFC
 *    and returns an empty connection method list in `CCEHandoverSelect`, completing the entire
 *    session over NFC.
 * 3. [testBleHandoverRequestOnNfcFirst]: Concurrent NFC + BLE handover where `DeviceRequest`
 *    (`seq = 0`) arrives on NFC first, BLE connects before response generation, `DeviceResponse`
 *    (`seq = 1`, `"disconnectNfc": true`) arrives on NFC first, and session termination (`seq = 2`)
 *    is sent only over BLE.
 * 4. [testBleHandoverRequestOnBleFirst]: Concurrent NFC + BLE handover where `DeviceRequest`
 *    (`seq = 0`) arrives on BLE *before* the second NFC `ENVELOPE` command, causing the mdoc to
 *    queue `DeviceResponse` (`seq = 1`, `"disconnectNfc": true`) for NFC and send it over BLE.
 * 5. [testBleHandoverResponseOnBleBeforeNfcGetResponse]: Concurrent NFC + BLE handover where
 *    `apduResponseMaxSize = 1024` forces APDU response chaining on NFC (`6100`), and the complete
 *    `DeviceResponse` (`seq = 1`, `"disconnectNfc": true`) arrives over BLE before the reader sends
 *    `GET RESPONSE` on NFC.
 * 6. [testBleHandoverLateBleConnectionPrunesBacklog]: Concurrent NFC + BLE handover where BLE
 *    connects *after* the first `DeviceRequest` (`seq = 0`) / `DeviceResponse` (`seq = 1`) exchange
 *    has already completed over NFC, causing the reader to prune `seq = 0` from its BLE backlog.
 * 7. [testBleHandoverFieldLostDuringEnvelope2]: Concurrent NFC + BLE handover where the NFC field
 *    is lost (`NFC: Field lost`) while the mdoc is waiting for user consent during `ENVELOPE` #2,
 *    and the session seamlessly completes over BLE without `"disconnectNfc": true`.
 * 8. [testBleHandoverFieldLostAfterHandover]: Concurrent NFC + BLE handover where the NFC field is
 *    lost (`NFC: Field lost`) immediately after handover (`ENVELOPE` #1), and the entire session
 *    (`seq = 0`, `seq = 1`, `seq = 2`) completes over BLE.
 * 9. [testNfcOnlyMultiTapFieldLostDuringConsent]: NFC-only multi-tap presentment where the NFC
 *    field is lost (`NFC: Field lost`) during user consent on Tap 1 and the session completes on
 *    Tap 2 (`NFC: Re-tapped`), reusing the user consent obtained on Tap 1.
 * 10. [testNfcOnlyApduCommandAndResponseChaining]: Single-tap NFC-only CCE engagement and
 *     presentment with `apduCommandMaxSize = 100` and `apduResponseMaxSize = 900`, exercising
 *     ISO/IEC 7816-4 command chaining (`CLA = 0x10` -> `0x00`) and response chaining (`6100` ->
 *     `6139` -> `9000` via `GET RESPONSE`).
 * 11. [testNfcOnlyMultipleRequestsInSingleSession]: Single-tap NFC-only CCE engagement and
 *     presentment where the reader sends two sequential `DeviceRequest` messages (`seq = 0` for
 *     `family_name` and `seq = 2` for `document_number`) and receives two `DeviceResponse` messages
 *     (`seq = 1` and `seq = 3`) before sending session termination (`seq = 4`, `"status": 20`).
 */
class MdocNfcCceTranscriptTest {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private class TranscriptLogger {
        private val mutex = Mutex()
        private val sb = StringBuilder()
        private var lastEntryWasCommandApdu = false

        private fun appendCborDiagnosticCommentLocked(cborBytes: ByteArray) {
            val diag = Cbor.toDiagnostics(
                cborBytes,
                setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
            )
            for (line in diag.lines()) {
                if (line.isBlank()) {
                    sb.appendLine("#")
                } else {
                    sb.appendLine("# $line")
                }
            }
        }

        suspend fun appendCommandApdu(comment: String, cborBytes: ByteArray?, apduHex: String) {
            mutex.withLock {
                if (sb.isNotEmpty()) {
                    sb.appendLine()
                }
                sb.appendLine("# $comment")
                if (cborBytes != null && cborBytes.isNotEmpty()) {
                    appendCborDiagnosticCommentLocked(cborBytes)
                }
                sb.appendLine("C-APDU: $apduHex")
                lastEntryWasCommandApdu = true
            }
        }

        suspend fun appendResponseApdu(comment: String, cborBytes: ByteArray?, apduHex: String) {
            mutex.withLock {
                if (sb.isNotEmpty() && !lastEntryWasCommandApdu) {
                    sb.appendLine()
                }
                sb.appendLine("# $comment")
                if (cborBytes != null && cborBytes.isNotEmpty()) {
                    appendCborDiagnosticCommentLocked(cborBytes)
                }
                sb.appendLine("R-APDU: $apduHex")
                lastEntryWasCommandApdu = false
            }
        }

        suspend fun appendBleMessage(fromReader: Boolean, messageBytes: ByteArray) {
            mutex.withLock {
                if (sb.isNotEmpty()) {
                    sb.appendLine()
                }
                val item = Cbor.decode(messageBytes)
                val seq = item["seq"].asNumber
                val comment = if (fromReader) {
                    if (item.hasKey("status")) {
                        val status = item["status"].asNumber
                        "The mdoc reader sends SessionData (seq=$seq, status=$status) to terminate the session over BLE."
                    } else {
                        "The mdoc reader sends SessionData (seq=$seq) with encrypted DeviceRequest over BLE."
                    }
                } else {
                    if (item.hasKey("disconnectNfc") && item["disconnectNfc"].asBoolean) {
                        "The mdoc sends SessionData (seq=$seq, disconnectNfc=true) with encrypted DeviceResponse over BLE."
                    } else {
                        "The mdoc sends SessionData (seq=$seq) with encrypted DeviceResponse over BLE."
                    }
                }
                sb.appendLine("# $comment")
                if (messageBytes.isNotEmpty()) {
                    appendCborDiagnosticCommentLocked(messageBytes)
                }
                val prefix = if (fromReader) "BLE Reader->Mdoc" else "BLE Mdoc->Reader"
                sb.appendLine("$prefix: ${messageBytes.toHex()}")
                lastEntryWasCommandApdu = false
            }
        }

        suspend fun appendEvent(eventLine: String) {
            mutex.withLock {
                if (sb.isNotEmpty()) {
                    sb.appendLine()
                }
                val comment = when (eventLine) {
                    "NFC: Field lost" -> "The NFC field is lost."
                    "NFC: Re-tapped" -> "The mdoc is re-tapped on the mdoc reader."
                    else -> error("Unknown event: $eventLine")
                }
                sb.appendLine("# $comment")
                sb.appendLine(eventLine)
                lastEntryWasCommandApdu = false
            }
        }

        override fun toString(): String = sb.toString().trim()
    }

    /**
     * Loopback [NfcIsoTag] that forwards APDUs to [MdocNfcCceEngagementHelper.processApdu] and records
     * a textual transcript of every `C-APDU` and `R-APDU` in hex, annotated with CBOR diagnostic
     * comments whenever an APDU (or completed APDU chain) carries a CBOR payload.
     */
    private class TranscriptRecordingNfcIsoTag(
        private val engagementHelper: MdocNfcCceEngagementHelper,
        val transcript: TranscriptLogger = TranscriptLogger(),
        private val onFieldLost: (() -> Unit)? = null,
        private val onBeforeEnvelopeCommand: (suspend (envelopeIndex: Int, command: CommandApdu) -> Unit)? = null,
        private val onAfterEnvelopeResponse: (suspend (envelopeIndex: Int, response: ResponseApdu) -> Unit)? = null,
        private val onBeforeGetResponseCommand: (suspend (command: CommandApdu) -> Unit)? = null,
    ) : NfcIsoTag() {
        val closedDeferred = CompletableDeferred<Unit>()

        @Volatile
        private var fieldLost: Boolean = false
        private var lastCommandIns: Int = 0
        private var envelopeIndex: Int = 0
        private var wasCommandChained: Boolean = false
        private var incomingDo53Builder = ByteStringBuilder()
        private var outgoingDo53Builder = ByteStringBuilder()

        override val maxTransceiveLength: Int = 65536

        suspend fun simulateFieldLoss() {
            if (!fieldLost) {
                fieldLost = true
                transcript.appendEvent("NFC: Field lost")
                onFieldLost?.invoke()
                engagementHelper.processDeactivated(NfcDeactivationReason.LINK_LOSS)
            }
        }

        override suspend fun close() {
            if (!fieldLost) {
                engagementHelper.processDeactivated(NfcDeactivationReason.DESELECTED)
            }
            closedDeferred.complete(Unit)
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            if (fieldLost) {
                throw NfcTagLostException("NFC field lost")
            }
            lastCommandIns = command.ins

            var commandCborBytes: ByteArray? = null
            val commandComment: String
            when (command.ins) {
                Nfc.INS_SELECT -> {
                    commandComment = "The mdoc reader selects the NFC CCE application."
                }
                Nfc.INS_ENVELOPE -> {
                    incomingDo53Builder.append(command.payload)
                    if (command.cla == Nfc.CLA_CHAIN_NOT_LAST) {
                        wasCommandChained = true
                        commandComment = "The mdoc reader sends a command-chained ENVELOPE chunk (${command.payload.size} bytes, CLA=0x10)."
                    } else {
                        envelopeIndex += 1
                        onBeforeEnvelopeCommand?.invoke(envelopeIndex, command)
                        if (fieldLost) {
                            throw NfcTagLostException("NFC field lost")
                        }
                        commandCborBytes = extractFromDo53(incomingDo53Builder.toByteString()).toByteArray()
                        incomingDo53Builder = ByteStringBuilder()
                        val chained = wasCommandChained
                        wasCommandChained = false
                        val item = Cbor.decode(commandCborBytes)
                        commandComment = if (envelopeIndex == 1) {
                            val readerEngagement = item[0]
                            val hasConnectionMethods = readerEngagement.hasKey(2) && readerEngagement[2].asArray.isNotEmpty()
                            if (hasConnectionMethods) {
                                "The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods."
                            } else {
                                "The mdoc reader sends CCEHandoverRequest with eReaderKey and no connection methods."
                            }
                        } else {
                            val seq = item["seq"].asNumber
                            if (chained) {
                                "The mdoc reader sends the final command-chained ENVELOPE chunk (${command.payload.size} bytes, CLA=0x00) completing SessionData (seq=$seq) with encrypted DeviceRequest."
                            } else if (item.hasKey("status")) {
                                val status = item["status"].asNumber
                                "The mdoc reader sends SessionData (seq=$seq, status=$status) to terminate the session over NFC."
                            } else {
                                "The mdoc reader sends SessionData (seq=$seq) with encrypted DeviceRequest over NFC."
                            }
                        }
                    }
                }
                Nfc.INS_GET_RESPONSE -> {
                    onBeforeGetResponseCommand?.invoke(command)
                    if (fieldLost) {
                        throw NfcTagLostException("NFC field lost")
                    }
                    currentCoroutineContext().ensureActive()
                    commandComment = "The mdoc reader sends GET RESPONSE requesting up to ${command.le} bytes."
                }
                else -> error("Unexpected INS: ${command.ins}")
            }
            transcript.appendCommandApdu(commandComment, commandCborBytes, command.encode().toHex())

            val response = try {
                engagementHelper.processApdu(command)
            } catch (e: Throwable) {
                if (fieldLost) {
                    throw NfcTagLostException("NFC field lost", e)
                }
                throw e
            }
            if (fieldLost) {
                throw NfcTagLostException("NFC field lost")
            }

            var responseCborBytes: ByteArray? = null
            val responseComment: String
            when {
                lastCommandIns == Nfc.INS_SELECT -> {
                    if (response.status == Nfc.RESPONSE_STATUS_SUCCESS && response.payload.size > 0) {
                        responseCborBytes = response.payload.toByteArray()
                    }
                    responseComment = "The mdoc responds with maximum command and response data lengths."
                }
                lastCommandIns == Nfc.INS_ENVELOPE && command.cla == Nfc.CLA_CHAIN_NOT_LAST -> {
                    responseComment = "The mdoc acknowledges the command-chained chunk."
                }
                lastCommandIns == Nfc.INS_ENVELOPE || lastCommandIns == Nfc.INS_GET_RESPONSE -> {
                    outgoingDo53Builder.append(response.payload)
                    if (response.status == Nfc.RESPONSE_STATUS_SUCCESS) {
                        val encapsulated = outgoingDo53Builder.toByteString()
                        outgoingDo53Builder = ByteStringBuilder()
                        if (encapsulated.size > 0) {
                            val extracted = extractFromDo53(encapsulated)
                            if (extracted.size > 0) {
                                responseCborBytes = extracted.toByteArray()
                            }
                        }
                        responseComment = if (responseCborBytes == null) {
                            "The mdoc responds with an empty DO'53' acknowledging session termination."
                        } else if (envelopeIndex == 1) {
                            val item = Cbor.decode(responseCborBytes)
                            val deviceEngagement = item[0]
                            val hasSelectedMethod = deviceEngagement.hasKey(2) && deviceEngagement[2].asArray.isNotEmpty()
                            if (hasSelectedMethod) {
                                "The mdoc responds with CCEHandoverSelect containing eDeviceKey and the selected connection method."
                            } else {
                                "The mdoc responds with CCEHandoverSelect containing eDeviceKey and no connection methods."
                            }
                        } else {
                            val item = Cbor.decode(responseCborBytes)
                            val seq = item["seq"].asNumber
                            val disconnectSuffix = if (item.hasKey("disconnectNfc") && item["disconnectNfc"].asBoolean) {
                                ", disconnectNfc=true"
                            } else {
                                ""
                            }
                            if (lastCommandIns == Nfc.INS_GET_RESPONSE) {
                                "The mdoc returns the final response chunk (${response.payload.size} bytes) completing SessionData (seq=$seq$disconnectSuffix) with encrypted DeviceResponse."
                            } else {
                                "The mdoc responds with SessionData (seq=$seq$disconnectSuffix) containing encrypted DeviceResponse."
                            }
                        }
                    } else {
                        val statusHex = response.status.toString(16).padStart(4, '0')
                        responseComment = "The mdoc returns a response chunk (${response.payload.size} bytes) with status $statusHex (more data available)."
                    }
                }
                else -> error("Unexpected INS: $lastCommandIns")
            }
            transcript.appendResponseApdu(responseComment, responseCborBytes, response.encode().toHex())
            if (command.ins == Nfc.INS_ENVELOPE && command.cla == Nfc.CLA_CHAIN_LAST) {
                onAfterEnvelopeResponse?.invoke(envelopeIndex, response)
            }
            return response
        }
    }

    private class LoopbackBleTransportPair(
        val connectionMethod: MdocConnectionMethodBle,
        val transcript: TranscriptLogger,
        private val onBeforeReaderOpen: (suspend () -> Unit)? = null,
        private val onBeforeReaderSend: (suspend (msgIndex: Int, message: ByteArray) -> Unit)? = null,
        private val onAfterReaderSendQueued: (suspend (msgIndex: Int, message: ByteArray) -> Unit)? = null,
        private val onBeforeMdocSend: (suspend (msgIndex: Int, message: ByteArray) -> Unit)? = null,
        private val onAfterMdocSendLogged: (suspend (msgIndex: Int, message: ByteArray) -> Unit)? = null,
        private val onBeforeMdocSendDeliver: (suspend (msgIndex: Int, message: ByteArray) -> Unit)? = null,
        private val onReaderEnterWaitForMessage: (suspend (waitIndex: Int) -> Unit)? = null,
        private val onMdocEnterWaitForMessage: (suspend (waitIndex: Int) -> Unit)? = null,
    ) : MdocTransportFactory {
        private val readerToMdocChannel = Channel<ByteArray>(Channel.UNLIMITED)
        private val mdocToReaderChannel = Channel<ByteArray>(Channel.UNLIMITED)
        private var readerSendCount = 0
        private var mdocSendCount = 0
        private var readerWaitCount = 0
        private var mdocWaitCount = 0

        val readerTransport: MdocTransport = Endpoint(MdocRole.MDOC_READER)
        val mdocTransport: MdocTransport = Endpoint(MdocRole.MDOC)

        override fun createTransport(
            connectionMethod: MdocConnectionMethod,
            role: MdocRole,
            options: MdocTransportOptions
        ): MdocTransport = when (role) {
            MdocRole.MDOC_READER -> readerTransport
            MdocRole.MDOC -> mdocTransport
        }

        private inner class Endpoint(
            override val role: MdocRole
        ) : MdocTransport() {
            private val _state = MutableStateFlow(State.IDLE)
            override val state: StateFlow<State> = _state.asStateFlow()

            override val connectionMethod: MdocConnectionMethod
                get() = this@LoopbackBleTransportPair.connectionMethod

            override val scanningTime: Duration? = null

            override suspend fun advertise() {
                if (_state.value == State.IDLE) {
                    _state.value = State.ADVERTISING
                }
            }

            override suspend fun open(eSenderKey: EcPublicKey) {
                if (role == MdocRole.MDOC_READER) {
                    onBeforeReaderOpen?.invoke()
                }
                _state.value = State.CONNECTED
            }

            override suspend fun sendMessage(message: ByteArray) {
                if (role == MdocRole.MDOC_READER) {
                    readerSendCount += 1
                    val idx = readerSendCount
                    onBeforeReaderSend?.invoke(idx, message)
                    transcript.appendBleMessage(fromReader = true, messageBytes = message)
                    readerToMdocChannel.trySend(message)
                    onAfterReaderSendQueued?.invoke(idx, message)
                } else {
                    mdocSendCount += 1
                    val idx = mdocSendCount
                    onBeforeMdocSend?.invoke(idx, message)
                    transcript.appendBleMessage(fromReader = false, messageBytes = message)
                    onAfterMdocSendLogged?.invoke(idx, message)
                    onBeforeMdocSendDeliver?.invoke(idx, message)
                    mdocToReaderChannel.trySend(message)
                }
            }

            override suspend fun waitForMessage(): ByteArray {
                if (role == MdocRole.MDOC_READER) {
                    readerWaitCount += 1
                    onReaderEnterWaitForMessage?.invoke(readerWaitCount)
                } else {
                    mdocWaitCount += 1
                    onMdocEnterWaitForMessage?.invoke(mdocWaitCount)
                }
                val ch = if (role == MdocRole.MDOC_READER) mdocToReaderChannel else readerToMdocChannel
                return try {
                    ch.receive()
                } catch (e: Throwable) {
                    throw MdocTransportClosedException("BLE transport closed", e)
                }
            }

            override suspend fun close() {
                _state.value = State.CLOSED
                if (role == MdocRole.MDOC_READER) {
                    mdocToReaderChannel.close()
                } else {
                    readerToMdocChannel.close()
                }
            }
        }
    }

    private class SingleTagNfcTagReader(
        private val tag: NfcIsoTag
    ) : NfcTagReader {
        override val external: Boolean = false
        override val dialogAlwaysShown: Boolean = false

        override suspend fun <T : Any> scan(
            message: String?,
            tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
            options: NfcScanOptions,
            context: CoroutineContext
        ): T {
            return checkNotNull(tagInteractionFunc(tag))
        }
    }

    private class MultiTagNfcTagReader(
        private val tags: List<NfcIsoTag>,
        private val onBeforeNextTap: (suspend (tapIndex: Int) -> Unit)? = null,
    ) : NfcTagReader {
        override val external: Boolean = false
        override val dialogAlwaysShown: Boolean = false

        override suspend fun <T : Any> scan(
            message: String?,
            tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
            options: NfcScanOptions,
            context: CoroutineContext
        ): T {
            for ((index, tag) in tags.withIndex()) {
                if (index > 0) {
                    onBeforeNextTap?.invoke(index + 1)
                }
                val res = tagInteractionFunc(tag)
                if (res != null) {
                    return res
                }
            }
            error("Exhausted ${tags.size} tags without completing scan")
        }
    }

    private class StaticTestPresentmentSource(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        private val onBeforeShowConsentPrompt: (suspend () -> Unit)? = null,
    ) : PresentmentSource(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository,
    ) {
        override suspend fun resolveTrust(requester: Requester): TrustedRequesterIdentity? = null

        override suspend fun showConsentPrompt(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            presentmentData: PresentmentData,
            preselectedDocuments: List<Document>,
            onDocumentsInFocus: (documents: List<Document>) -> Unit
        ): PresentmentSelection? {
            onBeforeShowConsentPrompt?.invoke()
            return promptModelSilentConsent(
                requester = requester,
                trustedRequesterIdentity = trustedRequesterIdentity,
                presentmentData = presentmentData,
                preselectedDocuments = preselectedDocuments,
                onDocumentsInFocus = onDocumentsInFocus,
            )
        }

        override suspend fun selectCredential(
            document: Document,
            requestedClaims: List<RequestedClaim>,
            keyAgreementPossible: List<EcCurve>,
            credential: Credential?
        ): Credential? {
            return document.getCertifiedCredentials().firstOrNull()
        }

        override suspend fun getBadges(document: Document): List<DocumentBadge> = emptyList()

        override suspend fun getFaceMatchingMode(
            credential: Credential,
            requesterIdentities: List<RequesterIdentity>
        ): FaceMatchingMode = FaceMatchingMode.NEVER
    }

    private fun getAnnexDEDeviceKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_Y.fromHex(),
    )

    private fun getAnnexDEReaderKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex(),
    )

    private fun getAnnexDStaticDeviceKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex(),
    )

    private fun getStaticBleConnectionMethod(): MdocConnectionMethodBle = MdocConnectionMethodBle(
        supportsPeripheralServerMode = false,
        supportsCentralClientMode = true,
        peripheralServerModeUuid = null,
        centralClientModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        peripheralServerModePsm = 128,
        peripheralServerModeMacAddress = null,
    )

    /**
     * Provisions an [MdocCredential] backed by the static `dDeviceKey` and the `issuerSigned`
     * structure (`issuerAuth` MSO + mandatory data elements excluding `portrait` and
     * `driving_privileges`) extracted from [TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE].
     */
    private suspend fun createAnnexDPresentmentSource(
        onBeforeShowConsentPrompt: (suspend () -> Unit)? = null,
    ): PresentmentSource {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder()
            .add(secureArea)
            .build()
        val documentTypeRepository = DocumentTypeRepository().apply {
            addDocumentType(DrivingLicense.getDocumentType())
        }
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {}

        val document = documentStore.createDocument(
            displayName = "Annex D mDL",
            typeDisplayName = "Driving License"
        )
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc_key_agreement",
            secureArea = secureArea,
            docType = DrivingLicense.MDL_DOCTYPE,
            createKeySettings = SoftwareCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ECDH_P256)
                .setPrivateKey(getAnnexDStaticDeviceKey())
                .build()
        )

        val annexDResponseItem = Cbor.decode(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex())
        val annexDIssuerSigned = annexDResponseItem["documents"].asArray.first()["issuerSigned"]
        val annexDMdlItems = annexDIssuerSigned["nameSpaces"][DrivingLicense.MDL_NAMESPACE].asArray
        // Keep only the simple mandatory elements (family_name, issue_date, expiry_date, document_number)
        val mandatoryItems = annexDMdlItems.filter { taggedItem ->
            val elementId = taggedItem.asTaggedEncodedCbor["elementIdentifier"].asTstr
            elementId in setOf("family_name", "issue_date", "expiry_date", "document_number")
        }
        val issuerSigned = buildCborMap {
            putCborMap("nameSpaces") {
                put(DrivingLicense.MDL_NAMESPACE, buildCborArray {
                    mandatoryItems.forEach { add(it) }
                })
            }
            put("issuerAuth", annexDIssuerSigned["issuerAuth"])
        }
        credential.certify(ByteString(Cbor.encode(issuerSigned)))

        return StaticTestPresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            onBeforeShowConsentPrompt = onBeforeShowConsentPrompt,
        )
    }

    /**
     * Test 1: Single-tap NFC-only CCE engagement and presentment (no secondary transport).
     *
     * Sequence of events:
     * - Reader sends `SELECT` (`00A4040007A0000002480401`) for the NFC CCE AID.
     * - Mdoc responds (`9000`) with CBOR `{0: 65535, 1: 65536}` advertising `maxCommandDataLength`
     *   and `maxResponseDataLength`.
     * - Reader sends `ENVELOPE` #1 containing `DO'53'` with `CCEHandoverRequest` (`version = "1.1"`,
     *   `eReaderKey`, and no alternative `ReaderConnectionMethods`).
     * - Mdoc responds (`9000`) with `DO'53'` containing `CCEHandoverSelect` (`version = "1.1"`,
     *   `eDeviceKey`, and no selected connection method).
     * - Reader sends `ENVELOPE` #2 containing `DO'53'` with `SessionData` (`seq = 0`, encrypted
     *   `DeviceRequest` for `org.iso.18013.5.1.mDL` requesting `family_name`).
     * - Mdoc decrypts `seq = 0`, generates the mDL `DeviceResponse`, and responds (`9000`) with
     *   `DO'53'` containing `SessionData` (`seq = 1`, encrypted `DeviceResponse`, without
     *   `"disconnectNfc"` since no secondary transport is used).
     * - Reader decrypts and verifies `seq = 1`, then sends `ENVELOPE` #3 containing `DO'53'` with
     *   `SessionData` (`seq = 2`, `"status": 20` session termination).
     * - Mdoc responds (`9000`) with an empty `DO'53'` (`5300`) and closes the session.
     * - Reader closes the NFC tag.
     */
    @Test
    fun testNfcOnlySingleTapSimpleRequest() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val presentmentSource = createAnnexDPresentmentSource()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertNull(connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { null },
        )

        val tag = TranscriptRecordingNfcIsoTag(engagementHelper)
        val tagReader = SingleTagNfcTagReader(tag)

        val verifiedDeviceResponse = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            selectConnectionMethod = { null },
            negotiatedHandoverConnectionMethods = emptyList(),
            onHandover = { scanResult ->
                assertEquals(MdocHandoverType.CCE_HANDOVER, scanResult.type)
                val readerTransport = scanResult.transport
                val deviceEngagementItem = Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
                val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementItem)
                readerTransport.open(deviceEngagement.eDeviceKey)

                val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                val sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
                    add(scanResult.handover)
                }
                val encodedSessionTranscript = Cbor.encode(sessionTranscript)

                val readerEncryption = SessionEncryption(
                    role = MdocRole.MDOC_READER,
                    eSelfKey = eReaderKey,
                    remotePublicKey = deviceEngagement.eDeviceKey,
                    encodedSessionTranscript = encodedSessionTranscript,
                    insertSequenceNumbers = true,
                ).apply {
                    setSendSessionEstablishment(false)
                }

                val deviceRequest = buildDeviceRequest(
                    sessionTranscript = sessionTranscript,
                    version = "1.0",
                ) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                        )
                    )
                }

                val encryptedRequest = readerEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                    statusCode = null,
                )
                readerTransport.sendMessage(encryptedRequest)

                val encryptedResponse = readerTransport.waitForMessage()
                val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
                assertNull(status)
                assertNotNull(encodedDeviceResponse)

                val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                deviceResponse.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
                )

                val terminationMessage = SessionEncryption.encodeStatus(
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                    sequenceNumber = readerEncryption.nextSequenceNumber,
                )
                readerTransport.sendMessage(terminationMessage)
                readerTransport.close()

                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        presentmentDone.await()
                        tag.closedDeferred.await()
                    }
                }
                mdocTransport.close()
                deviceResponse
            }
        )

        assertNotNull(verifiedDeviceResponse)
        assertEquals(1, verifiedDeviceResponse.documents.size)
        val doc = verifiedDeviceResponse.documents.first()
        assertEquals(DrivingLicense.MDL_DOCTYPE, doc.docType)
        assertEquals(
            "Doe",
            doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["family_name"]!!.dataElementValue.asTstr
        )

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ]
            #   }
            # }
            C-APDU: 00c3000000005c535aa100a20063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 5362a100a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'4841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158874841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c6373657100fffa
            # The mdoc responds with SessionData (seq=1) containing encrypted DeviceResponse.
            # {
            #   "data": h'7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce99f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa78dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce35',
            #   "seq": 1
            # }
            R-APDU: 5382073da2646461746159072f7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce99f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa78dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce3563736571019000

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over NFC.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            C-APDU: 00c30000000010530ea266737461747573146373657102fffa
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, tag.transcript.toString())
    }

    /**
     * Test 2: Single-tap NFC CCE engagement and presentment where the reader offers BLE Central
     * Client Mode in `CCEHandoverRequest`, but the mdoc only supports NFC and returns an empty
     * connection method list in `CCEHandoverSelect`.
     *
     * Sequence of events:
     * - Reader sends `SELECT` (`00A4040007A0000002480401`) for the NFC CCE AID.
     * - Mdoc responds (`9000`) with CBOR `{0: 65535, 1: 65536}` advertising `maxCommandDataLength`
     *   and `maxResponseDataLength`.
     * - Reader sends `ENVELOPE` #1 containing `DO'53'` with `CCEHandoverRequest` (`version = "1.1"`,
     *   `eReaderKey`, and BLE Central Client Mode with UUID
     *   `00000001-a123-48ce-896b-4c76973373e6` and L2CAP PSM `128`).
     * - Mdoc declines the offered BLE connection method (`handoverPicker` returns `null`) and
     *   responds (`9000`) with `DO'53'` containing `CCEHandoverSelect` (`version = "1.1"`,
     *   `eDeviceKey`, and an empty connection method list `5: []`).
     * - Reader sees no secondary connection method was selected and continues over NFC.
     * - Reader sends `ENVELOPE` #2 containing `DO'53'` with `SessionData` (`seq = 0`, encrypted
     *   `DeviceRequest` for `org.iso.18013.5.1.mDL` requesting `family_name`).
     * - Mdoc decrypts `seq = 0`, generates the mDL `DeviceResponse`, and responds (`9000`) with
     *   `DO'53'` containing `SessionData` (`seq = 1`, encrypted `DeviceResponse`, without
     *   `"disconnectNfc"` since no secondary transport was selected).
     * - Reader decrypts and verifies `seq = 1`, then sends `ENVELOPE` #3 containing `DO'53'` with
     *   `SessionData` (`seq = 2`, `"status": 20` session termination).
     * - Mdoc responds (`9000`) with an empty `DO'53'` (`5300`) and closes the session.
     * - Reader closes the NFC tag.
     */
    @Test
    fun testReaderOffersBleMdocOnlyNfc() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val bleCm = getStaticBleConnectionMethod()
        val transcriptLogger = TranscriptLogger()
        val blePair = LoopbackBleTransportPair(
            connectionMethod = bleCm,
            transcript = transcriptLogger,
        )
        val presentmentSource = createAnnexDPresentmentSource()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertNull(connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { methods ->
                assertEquals(listOf(bleCm), methods)
                null
            },
        )

        val tag = TranscriptRecordingNfcIsoTag(engagementHelper, transcriptLogger)
        val tagReader = SingleTagNfcTagReader(tag)

        val verifiedDeviceResponse = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(bleUseL2CAP = true),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            transportFactory = blePair,
            selectConnectionMethod = {
                fail("selectConnectionMethod should not be called when mdoc selects no secondary transport")
            },
            negotiatedHandoverConnectionMethods = listOf(bleCm),
            onHandover = { scanResult ->
                assertEquals(MdocHandoverType.CCE_HANDOVER, scanResult.type)
                val readerTransport = scanResult.transport
                val deviceEngagementItem = Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
                val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementItem)
                readerTransport.open(deviceEngagement.eDeviceKey)

                val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                val sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
                    add(Tagged(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)).tagNumber, Bstr(encodedEReaderKey)))
                    add(scanResult.handover)
                }
                val encodedSessionTranscript = Cbor.encode(sessionTranscript)

                val readerEncryption = SessionEncryption(
                    role = MdocRole.MDOC_READER,
                    eSelfKey = eReaderKey,
                    remotePublicKey = deviceEngagement.eDeviceKey,
                    encodedSessionTranscript = encodedSessionTranscript,
                    insertSequenceNumbers = true,
                ).apply {
                    setSendSessionEstablishment(false)
                }

                val deviceRequest = buildDeviceRequest(
                    sessionTranscript = sessionTranscript,
                    version = "1.0",
                ) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                        )
                    )
                }

                val encryptedRequest = readerEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                    statusCode = null,
                )
                readerTransport.sendMessage(encryptedRequest)

                val encryptedResponse = readerTransport.waitForMessage()
                val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
                assertNull(status)
                assertNotNull(encodedDeviceResponse)

                val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                deviceResponse.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
                )

                val terminationMessage = SessionEncryption.encodeStatus(
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                    sequenceNumber = readerEncryption.nextSequenceNumber,
                )
                readerTransport.sendMessage(terminationMessage)
                readerTransport.close()

                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        presentmentDone.await()
                        tag.closedDeferred.await()
                    }
                }
                mdocTransport.close()
                deviceResponse
            }
        )

        assertNotNull(verifiedDeviceResponse)
        assertEquals(1, verifiedDeviceResponse.documents.size)
        val doc = verifiedDeviceResponse.documents.first()
        assertEquals(DrivingLicense.MDL_DOCTYPE, doc.docType)
        assertEquals(
            "Doe",
            doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["family_name"]!!.dataElementValue.asTstr
        )

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f51518800000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 5362a100a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'c712975820fceae16767c6646aec4eb4e610bb55696794f6f4af13a9fda45207b9f873dcff97386c2addf734595d818ae4d38e75dc77bbc3d54263ea22ec51c83bead158d2b256dc2024743a99e08f322acc967b42f140f607150754038650cccdfbdd06e6ca9145524ae2130afeeb0582d8fef248ec9637632afbc87be475c1312f0aa15fa0f1',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a264646174615887c712975820fceae16767c6646aec4eb4e610bb55696794f6f4af13a9fda45207b9f873dcff97386c2addf734595d818ae4d38e75dc77bbc3d54263ea22ec51c83bead158d2b256dc2024743a99e08f322acc967b42f140f607150754038650cccdfbdd06e6ca9145524ae2130afeeb0582d8fef248ec9637632afbc87be475c1312f0aa15fa0f16373657100fffa
            # The mdoc responds with SessionData (seq=1) containing encrypted DeviceResponse.
            # {
            #   "data": h'f677784c6b1797b894e3d0fd65b3c66b7e9d6b049c328e0953bbcea926c95a814a8eef82475d4269a8da94e00f9c649c3facefc55c5d5608fc54c2712211dbddbdebff7bac2c97f2fac62c8bdf2bc2bbb705ec8d6877b9452cccb7a92f2a4fc8e324bc7b92b9d99716fa0bb49ccf7f2d12c04cb866d603b45428e1d3bf1ce2d53061875b5f9a1869d74a28888f1239ea494edf3c7ce29b108b09caef70c5a11f1e395b403696d292c309e6078fc873b95c790c4b3c5f9eb6a15f94a299298da9aef09b2a782b020c40967496b09f0b110850bee3f3c3d1c7bdff6c67ab5bd17b920958bfccf4a4404b6bffd7df5f7daecf438c483ed7e2c743dfc0c7d614fb02e7af8533654695fd57b31162109f62e1808fc00c3bbaa122f27c351d0208ea2c4e9708a81354b3cdc99556d1ebda6dd7a76d5b945eb40d7cf5d85b7a85841677225c07426a4332347fe4869380acce4fe3228e5676252113ce277af2cf93468a123b3925782689a7225b549b866c44e85f9acf18987b475b22412bd20d933bc4b418d3346b7f2795e5d7fe4fdc271746f576eb4893a8f80ebc75dae9dd732fa60824777e7fe9ac7ef31f05496f150cfe6e2196a7493fb0bfcc39613e9f20d4e8d7db91dfab582dd57b3c430df340f7f9abab77c98aff030c0ed3135794b1cca6208c865676a1f2bbed1a3feacca1557e761e2bb6e4c88533b6c8c8e4ff3e4082d734d4536dc9a362127675b2acefee9f13d4032836692f6d111e6cc493946590ba330071af22ea14d9a657d4ab0fb067ac8f62a424d4f0a50557507ba3a56dd658d6869791db223aba7b7fc69eb1d77e0006e92eec14ab2261a31b140c5cad8c34a6d96e9d81e40e0ee34507eba5f5cfc39d47180837f49a5b773ede5d7ced07c279cece98d9efcb74d9dbbe20c132b1bc15c4d480ba93ca1869748b3bc55db15f2aff71a3cf366c7bc7d0a95a6ecdff44c4527f7181bcc56708e15209155d578bc9d09feb459f1487082308133412fd51abc83dbe4c19399c2d1c626c4802a5c944bae4605d5f3882557dcb8a8009d89a88f2937499c02c448dae2c169f636072a09148876a9f3b6849c78090ad98991e9fafb9010c625c7cbf0c9022b5c1a782e6773dba7e816e36808dc32de611f0d442eb97b9896a5bb15d163c3b70eeac5dcf8f5692422ac1906628cb854daf8ae7143fe104426661a8f8c17d5a68c881a36620a719db271989bac566675c00dd0a023781bf02afb5ea030ef278ab557f5076dae1c3fd7007aee101db3f165b42374624c95daddc116ba611e92ad1321ef7723dcfba1c1f2e53cd836e5f56f6815619a7559d6e8c3a03cc6fac7c1cce5f0bbed66840c8800044a0d7402ddcb115339f9cb151053bd6cfa1d697d6eb49772a02a433987dea5e491a848010df0d2fcc952f7e7ce5ade22ac46a82b1eef25b7c31fa5aa4228e85b85c5a66bfd2fed9407666abf054354fd4beb1a4aafda475bbc08d334176369dd678dbd23c095a5c0c44800b8259fed4ed48991f9c03dd069a714c55b10acda5e8d8ce973e42ecd0526dc45a87d75b98ca6391376b7b8cddc47815b2487df747c9d347796ffe1ff760b02faca73b6e3104dcab25cf0117282328565cb05f7ef1db15718e5ae0c1085b6ca35d1b1430a65c3520724f1144ffe306225041e3f8730a334eac72ef7af061f25314fd909cd58ffcff0e274bbd659bf20e44aa6e6ad1542cc0212ed3e5b7df8ef615b7b20df7cc767bd42b28f7c441900154fd835f94480f2e3ec6862689d059680abb96748c59dde988d333d535d73a35859b2431cce6338db5aaed6478d593757ad62baf2580569ad8e5cd99dd95450d17bb9a016885458c96b6ceb510624dc2a2068463c646f3e6306eae62431b30a4aed6ef06952c0cd7ec16f568231e7dfde5bc563dd733aa330d9d75af1f44201240d2d309120c5ca54c5fc781b3c0e2bfe0e68832fc854cd94976a94e9e3d0310a1ef272ffd4748d829b48c41f44b7fb1676f1bc92f50b80bf2f33f9eea2b43d9fc8aa9380ae22f550f9ce9e6a702009f46fac175e185a0d8c6fe98683b63726348c3ab0b8b0b0b9537dc451024094be5548bd878b995daa2ec2bd60d026d5106bd66404977ca4a6e56eb520f03e5ec9f1c7af2b4272fe2bddf7fcb05762f6481bcd749e86cc9d15b5304efa9fd8f1abfbacd210e197128db3a65259452307902fcf4ccb38c8961a2e96124b9af88f203d044d42205225e4f22ea3fc3e55e9d650dc82a45b577854f600367bc541e9353752cdf892d5d1260d88edebdf2471c30dcaca55284ddcad923af0d5524adc44aac17ff971d147546ad1a7a40d413c5f303816e485664b61837577f73a402045050512afd54ba8cecd76c59251822cd535dc90f4d93cd425ef89c169ae9c96adb5a7397a294508db201722a63b5951a1c38e330ea2a0e5a473d70830142677042f10bfeab83b4903d8402e68a203cc3a851594085320687bc9a72a909a67d4fd9f8afb8b8babec69bdf5b21784865401750fc38052e87e7c5cd7f5dba6b6dc90472302a50449f2fed81014671b6e4dda1c5f5eb0ca1cdd096f73b582b',
            #   "seq": 1
            # }
            R-APDU: 5382073da2646461746159072ff677784c6b1797b894e3d0fd65b3c66b7e9d6b049c328e0953bbcea926c95a814a8eef82475d4269a8da94e00f9c649c3facefc55c5d5608fc54c2712211dbddbdebff7bac2c97f2fac62c8bdf2bc2bbb705ec8d6877b9452cccb7a92f2a4fc8e324bc7b92b9d99716fa0bb49ccf7f2d12c04cb866d603b45428e1d3bf1ce2d53061875b5f9a1869d74a28888f1239ea494edf3c7ce29b108b09caef70c5a11f1e395b403696d292c309e6078fc873b95c790c4b3c5f9eb6a15f94a299298da9aef09b2a782b020c40967496b09f0b110850bee3f3c3d1c7bdff6c67ab5bd17b920958bfccf4a4404b6bffd7df5f7daecf438c483ed7e2c743dfc0c7d614fb02e7af8533654695fd57b31162109f62e1808fc00c3bbaa122f27c351d0208ea2c4e9708a81354b3cdc99556d1ebda6dd7a76d5b945eb40d7cf5d85b7a85841677225c07426a4332347fe4869380acce4fe3228e5676252113ce277af2cf93468a123b3925782689a7225b549b866c44e85f9acf18987b475b22412bd20d933bc4b418d3346b7f2795e5d7fe4fdc271746f576eb4893a8f80ebc75dae9dd732fa60824777e7fe9ac7ef31f05496f150cfe6e2196a7493fb0bfcc39613e9f20d4e8d7db91dfab582dd57b3c430df340f7f9abab77c98aff030c0ed3135794b1cca6208c865676a1f2bbed1a3feacca1557e761e2bb6e4c88533b6c8c8e4ff3e4082d734d4536dc9a362127675b2acefee9f13d4032836692f6d111e6cc493946590ba330071af22ea14d9a657d4ab0fb067ac8f62a424d4f0a50557507ba3a56dd658d6869791db223aba7b7fc69eb1d77e0006e92eec14ab2261a31b140c5cad8c34a6d96e9d81e40e0ee34507eba5f5cfc39d47180837f49a5b773ede5d7ced07c279cece98d9efcb74d9dbbe20c132b1bc15c4d480ba93ca1869748b3bc55db15f2aff71a3cf366c7bc7d0a95a6ecdff44c4527f7181bcc56708e15209155d578bc9d09feb459f1487082308133412fd51abc83dbe4c19399c2d1c626c4802a5c944bae4605d5f3882557dcb8a8009d89a88f2937499c02c448dae2c169f636072a09148876a9f3b6849c78090ad98991e9fafb9010c625c7cbf0c9022b5c1a782e6773dba7e816e36808dc32de611f0d442eb97b9896a5bb15d163c3b70eeac5dcf8f5692422ac1906628cb854daf8ae7143fe104426661a8f8c17d5a68c881a36620a719db271989bac566675c00dd0a023781bf02afb5ea030ef278ab557f5076dae1c3fd7007aee101db3f165b42374624c95daddc116ba611e92ad1321ef7723dcfba1c1f2e53cd836e5f56f6815619a7559d6e8c3a03cc6fac7c1cce5f0bbed66840c8800044a0d7402ddcb115339f9cb151053bd6cfa1d697d6eb49772a02a433987dea5e491a848010df0d2fcc952f7e7ce5ade22ac46a82b1eef25b7c31fa5aa4228e85b85c5a66bfd2fed9407666abf054354fd4beb1a4aafda475bbc08d334176369dd678dbd23c095a5c0c44800b8259fed4ed48991f9c03dd069a714c55b10acda5e8d8ce973e42ecd0526dc45a87d75b98ca6391376b7b8cddc47815b2487df747c9d347796ffe1ff760b02faca73b6e3104dcab25cf0117282328565cb05f7ef1db15718e5ae0c1085b6ca35d1b1430a65c3520724f1144ffe306225041e3f8730a334eac72ef7af061f25314fd909cd58ffcff0e274bbd659bf20e44aa6e6ad1542cc0212ed3e5b7df8ef615b7b20df7cc767bd42b28f7c441900154fd835f94480f2e3ec6862689d059680abb96748c59dde988d333d535d73a35859b2431cce6338db5aaed6478d593757ad62baf2580569ad8e5cd99dd95450d17bb9a016885458c96b6ceb510624dc2a2068463c646f3e6306eae62431b30a4aed6ef06952c0cd7ec16f568231e7dfde5bc563dd733aa330d9d75af1f44201240d2d309120c5ca54c5fc781b3c0e2bfe0e68832fc854cd94976a94e9e3d0310a1ef272ffd4748d829b48c41f44b7fb1676f1bc92f50b80bf2f33f9eea2b43d9fc8aa9380ae22f550f9ce9e6a702009f46fac175e185a0d8c6fe98683b63726348c3ab0b8b0b0b9537dc451024094be5548bd878b995daa2ec2bd60d026d5106bd66404977ca4a6e56eb520f03e5ec9f1c7af2b4272fe2bddf7fcb05762f6481bcd749e86cc9d15b5304efa9fd8f1abfbacd210e197128db3a65259452307902fcf4ccb38c8961a2e96124b9af88f203d044d42205225e4f22ea3fc3e55e9d650dc82a45b577854f600367bc541e9353752cdf892d5d1260d88edebdf2471c30dcaca55284ddcad923af0d5524adc44aac17ff971d147546ad1a7a40d413c5f303816e485664b61837577f73a402045050512afd54ba8cecd76c59251822cd535dc90f4d93cd425ef89c169ae9c96adb5a7397a294508db201722a63b5951a1c38e330ea2a0e5a473d70830142677042f10bfeab83b4903d8402e68a203cc3a851594085320687bc9a72a909a67d4fd9f8afb8b8babec69bdf5b21784865401750fc38052e87e7c5cd7f5dba6b6dc90472302a50449f2fed81014671b6e4dda1c5f5eb0ca1cdd096f73b582b63736571019000

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over NFC.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            C-APDU: 00c30000000010530ea266737461747573146373657102fffa
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 3: Concurrent NFC + BLE handover where `DeviceRequest` (`seq = 0`) and `DeviceResponse`
     * (`seq = 1`) both arrive on NFC first.
     *
     * Sequence of events:
     * - Reader sends `SELECT` for the NFC CCE AID; mdoc responds with max command/response lengths
     *   (`65535` / `65536`).
     * - Reader sends `ENVELOPE` #1 (`CCEHandoverRequest`) offering BLE Central Client Mode with
     *   UUID `00000001-a123-48ce-896b-4c76973373e6` and L2CAP PSM `128`.
     * - Mdoc responds (`9000`) with `CCEHandoverSelect` selecting the offered BLE connection method
     *   and begins advertising/listening on BLE while keeping NFC active.
     * - Reader sends `ENVELOPE` #2 (`C-APDU`) with `SessionData` (`seq = 0`, encrypted
     *   `DeviceRequest`) over NFC; mdoc receives and conveys `seq = 0` from NFC first.
     * - Reader connects to mdoc over BLE and flushes its pending backlog (`seq = 0`) over BLE
     *   (`BLE Reader->Mdoc`).
     * - Mdoc receives the duplicate `seq = 0` on BLE and discards it (already received via NFC).
     * - Mdoc generates `DeviceResponse` (`seq = 1`) with `"disconnectNfc": true` (since BLE is now
     *   connected) and returns it in the `ENVELOPE` #2 `R-APDU` on NFC (`9000`), while also sending
     *   it over BLE (`BLE Mdoc->Reader`).
     * - Reader receives `seq = 1` (`"disconnectNfc": true`) on NFC first, conveys it to the caller,
     *   and closes the NFC tag (`tag.close()`).
     * - Reader receives the duplicate `seq = 1` on BLE and discards it (already received via NFC).
     * - Reader sends Session Termination (`seq = 2`, `"status": 20`) only over BLE
     *   (`BLE Reader->Mdoc`).
     */
    @Test
    fun testBleHandoverRequestOnNfcFirst() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val bleCm = getStaticBleConnectionMethod()
        val transcriptLogger = TranscriptLogger()

        val nfcRequestReceivedByMdoc = CompletableDeferred<Unit>()
        val bleRequestProcessedByMdoc = CompletableDeferred<Unit>()
        val nfcResponseLoggedAndConveyed = CompletableDeferred<Unit>()
        val bleResponseProcessedByReader = CompletableDeferred<Unit>()
        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val blePair = LoopbackBleTransportPair(
            connectionMethod = bleCm,
            transcript = transcriptLogger,
            onBeforeReaderOpen = {
                nfcRequestReceivedByMdoc.await()
            },
            onMdocEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 2) {
                    bleRequestProcessedByMdoc.complete(Unit)
                }
            },
            onBeforeMdocSend = { msgIndex, _ ->
                if (msgIndex == 1) {
                    nfcResponseLoggedAndConveyed.await()
                }
            },
            onReaderEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 2) {
                    bleResponseProcessedByReader.complete(Unit)
                }
            },
        )

        val presentmentSource = createAnnexDPresentmentSource(
            onBeforeShowConsentPrompt = {
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        bleRequestProcessedByMdoc.await()
                    }
                }
            }
        )

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(bleCm, connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                mdocTransport.setExpectTransport(true, connectionMethod)
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    val secondaryTransport = blePair.createTransport(
                        connectionMethod = connectionMethod!!,
                        role = MdocRole.MDOC,
                        options = MdocTransportOptions(bleUseL2CAP = true),
                    )
                    secondaryTransport.open(eDeviceKey.publicKey)
                    mdocTransport.setTransport(secondaryTransport)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
                nfcRequestReceivedByMdoc.complete(Unit)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { methods -> methods.firstOrNull() },
        )

        val tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
            onAfterEnvelopeResponse = { envelopeIndex, _ ->
                if (envelopeIndex == 2) {
                    nfcResponseLoggedAndConveyed.complete(Unit)
                }
            }
        )
        val tagReader = SingleTagNfcTagReader(tag)

        val scanResult = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(bleUseL2CAP = true),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            transportFactory = blePair,
            selectConnectionMethod = { methods -> methods.firstOrNull() },
            negotiatedHandoverConnectionMethods = listOf(bleCm),
        )
        assertNotNull(scanResult)
        val readerTransport = scanResult.transport
        val deviceEngagement = DeviceEngagement.fromDataItem(
            Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
        )
        readerTransport.open(deviceEngagement.eDeviceKey)

        val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
            add(scanResult.handover)
        }
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = deviceEngagement.eDeviceKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = true,
        )
        readerEncryption.setSendSessionEstablishment(false)

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.0",
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                )
            )
        }

        val encryptedRequest = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        readerTransport.sendMessage(encryptedRequest)

        val encryptedResponse = readerTransport.waitForMessage()
        val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
        assertNull(status)
        assertNotNull(encodedDeviceResponse)

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
        )

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                bleResponseProcessedByReader.await()
                tag.closedDeferred.await()
            }
        }

        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption.nextSequenceNumber,
        )
        readerTransport.sendMessage(terminationMessage)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                presentmentDone.await()
            }
        }
        readerTransport.close()
        mdocTransport.close()

        assertEquals(1, mdocTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(1, mdocTransport.stats.nfcHybridNumReceivedFirstOnTransport)
        assertEquals(1, readerTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(0, readerTransport.stats.nfcHybridNumReceivedFirstOnTransport)

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f51518800000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and the selected connection method.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 538185a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f5151880058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100fffa

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100

            # The mdoc responds with SessionData (seq=1, disconnectNfc=true) containing encrypted DeviceResponse.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            R-APDU: 5382074ca3646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e4063736571016d646973636f6e6e6563744e6663f59000

            # The mdoc sends SessionData (seq=1, disconnectNfc=true) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            BLE Mdoc->Reader: a3646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e4063736571016d646973636f6e6e6563744e6663f5

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 4: Concurrent NFC + BLE handover where `DeviceRequest` (`seq = 0`) arrives on BLE
     * *before* the NFC `ENVELOPE` #2 `C-APDU`.
     *
     * Sequence of events:
     * - Reader sends `SELECT` for the NFC CCE AID; mdoc responds with max command/response lengths
     *   (`65535` / `65536`).
     * - Reader sends `ENVELOPE` #1 (`CCEHandoverRequest`) offering BLE Central Client Mode + L2CAP
     *   PSM `128`; mdoc responds (`9000`) with `CCEHandoverSelect` selecting BLE.
     * - BLE connection completes immediately and reader sends `SessionData` (`seq = 0`, encrypted
     *   `DeviceRequest`) over BLE (`BLE Reader->Mdoc`) before the NFC reader loop transmits
     *   `ENVELOPE` #2.
     * - Mdoc receives and conveys `seq = 0` from BLE first, generates `DeviceResponse` (`seq = 1`)
     *   with `"disconnectNfc": true`, queues `seq = 1` for the pending NFC `ENVELOPE` response, and
     *   sends `seq = 1` over BLE (`BLE Mdoc->Reader`).
     * - Reader transmits `ENVELOPE` #2 (`C-APDU`) with `seq = 0` over NFC.
     * - Mdoc receives the duplicate `seq = 0` on NFC, discards it (already received via BLE), and
     *   immediately returns the queued `seq = 1` (`"disconnectNfc": true`) in the `ENVELOPE` #2
     *   `R-APDU` (`9000`).
     * - Reader receives `seq = 1` (`"disconnectNfc": true`), closes the NFC tag (`tag.close()`),
     *   and discards the duplicate `seq = 1` on the other channel.
     * - Reader sends Session Termination (`seq = 2`, `"status": 20`) only over BLE
     *   (`BLE Reader->Mdoc`).
     */
    @Test
    fun testBleHandoverRequestOnBleFirst() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val bleCm = getStaticBleConnectionMethod()
        val transcriptLogger = TranscriptLogger()

        val bleResponseLogged = CompletableDeferred<Unit>()
        val nfcEnvelope2Completed = CompletableDeferred<Unit>()
        val bleResponseProcessedByReader = CompletableDeferred<Unit>()
        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val blePair = LoopbackBleTransportPair(
            connectionMethod = bleCm,
            transcript = transcriptLogger,
            onAfterMdocSendLogged = { msgIndex, _ ->
                if (msgIndex == 1) {
                    bleResponseLogged.complete(Unit)
                }
            },
            onBeforeMdocSendDeliver = { msgIndex, _ ->
                if (msgIndex == 1) {
                    nfcEnvelope2Completed.await()
                }
            },
            onReaderEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 2) {
                    bleResponseProcessedByReader.complete(Unit)
                }
            },
        )

        val presentmentSource = createAnnexDPresentmentSource()

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(bleCm, connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                mdocTransport.setExpectTransport(true, connectionMethod)
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    val secondaryTransport = blePair.createTransport(
                        connectionMethod = connectionMethod!!,
                        role = MdocRole.MDOC,
                        options = MdocTransportOptions(bleUseL2CAP = true),
                    )
                    secondaryTransport.open(eDeviceKey.publicKey)
                    mdocTransport.setTransport(secondaryTransport)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { methods -> methods.firstOrNull() },
        )

        val tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
            onBeforeEnvelopeCommand = { envelopeIndex, _ ->
                if (envelopeIndex == 2) {
                    bleResponseLogged.await()
                }
            },
            onAfterEnvelopeResponse = { envelopeIndex, _ ->
                if (envelopeIndex == 2) {
                    nfcEnvelope2Completed.complete(Unit)
                }
            }
        )
        val tagReader = SingleTagNfcTagReader(tag)

        val scanResult = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(bleUseL2CAP = true),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            transportFactory = blePair,
            selectConnectionMethod = { methods -> methods.firstOrNull() },
            negotiatedHandoverConnectionMethods = listOf(bleCm),
        )
        assertNotNull(scanResult)
        val readerTransport = scanResult.transport
        val deviceEngagement = DeviceEngagement.fromDataItem(
            Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
        )
        readerTransport.open(deviceEngagement.eDeviceKey)

        val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
            add(scanResult.handover)
        }
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = deviceEngagement.eDeviceKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = true,
        )
        readerEncryption.setSendSessionEstablishment(false)

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.0",
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                )
            )
        }

        val encryptedRequest = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        readerTransport.sendMessage(encryptedRequest)

        val encryptedResponse = readerTransport.waitForMessage()
        val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
        assertNull(status)
        assertNotNull(encodedDeviceResponse)

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
        )

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                bleResponseProcessedByReader.await()
                tag.closedDeferred.await()
            }
        }

        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption.nextSequenceNumber,
        )
        readerTransport.sendMessage(terminationMessage)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                presentmentDone.await()
            }
        }
        readerTransport.close()
        mdocTransport.close()

        assertEquals(0, mdocTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(2, mdocTransport.stats.nfcHybridNumReceivedFirstOnTransport)

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f51518800000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and the selected connection method.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 538185a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f5151880058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100

            # The mdoc sends SessionData (seq=1, disconnectNfc=true) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            BLE Mdoc->Reader: a3646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e4063736571016d646973636f6e6e6563744e6663f5

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100fffa
            # The mdoc responds with SessionData (seq=1, disconnectNfc=true) containing encrypted DeviceResponse.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            R-APDU: 5382074ca3646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e4063736571016d646973636f6e6e6563744e6663f59000

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 5: Concurrent NFC + BLE handover where `DeviceResponse` (`seq = 1`) is chained across
     * multiple NFC APDUs (`apduResponseMaxSize = 1024`), and the full response arrives over BLE
     * before the reader sends `GET RESPONSE` on NFC.
     *
     * Sequence of events:
     * - Reader sends `SELECT` for the NFC CCE AID; mdoc responds with `{0: 65535, 1: 1024}`
     *   advertising a 1024-byte `maxResponseDataLength`.
     * - Reader sends `ENVELOPE` #1 (`CCEHandoverRequest`, `Le = 1024`) offering BLE Central Client
     *   Mode + L2CAP PSM `128`; mdoc responds (`9000`) with `CCEHandoverSelect` selecting BLE.
     * - Reader sends `ENVELOPE` #2 (`C-APDU`, `Le = 1024`) with `SessionData` (`seq = 0`, encrypted
     *   `DeviceRequest`) over NFC; mdoc receives and conveys `seq = 0` from NFC first.
     * - BLE connection completes and reader sends duplicate `seq = 0` over BLE
     *   (`BLE Reader->Mdoc`), which mdoc deduplicates.
     * - Mdoc generates `DeviceResponse` (`seq = 1`, `1868` bytes, encapsulated in `1872`-byte
     *   `DO'53'`) with `"disconnectNfc": true`.
     * - Mdoc returns the first `1024`-byte chunk of `DO'53'` in the `ENVELOPE` #2 `R-APDU` with
     *   status `6100` (`848` bytes remaining, which exceeds `255` so `SW2 = 0x00`).
     * - Before the reader sends `GET RESPONSE` on NFC, mdoc sends the complete `seq = 1`
     *   (`"disconnectNfc": true`) over BLE (`BLE Mdoc->Reader`).
     * - Reader receives the complete `seq = 1` over BLE first, conveys it to the caller, cancels
     *   its in-flight NFC I/O coroutine (so `GET RESPONSE` is never transmitted), and closes the
     *   NFC tag (`tag.close()`).
     * - Reader sends Session Termination (`seq = 2`, `"status": 20`) only over BLE
     *   (`BLE Reader->Mdoc`).
     */
    @Test
    fun testBleHandoverResponseOnBleBeforeNfcGetResponse() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val bleCm = getStaticBleConnectionMethod()
        val transcriptLogger = TranscriptLogger()

        val nfcRequestReceivedByMdoc = CompletableDeferred<Unit>()
        val bleRequestProcessedByMdoc = CompletableDeferred<Unit>()
        val nfcFirstResponseChunkLogged = CompletableDeferred<Unit>()
        val bleResponseProcessedByReader = CompletableDeferred<Unit>()
        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val blePair = LoopbackBleTransportPair(
            connectionMethod = bleCm,
            transcript = transcriptLogger,
            onBeforeReaderOpen = {
                nfcRequestReceivedByMdoc.await()
            },
            onMdocEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 2) {
                    bleRequestProcessedByMdoc.complete(Unit)
                }
            },
            onBeforeMdocSend = { msgIndex, _ ->
                if (msgIndex == 1) {
                    nfcFirstResponseChunkLogged.await()
                }
            },
            onReaderEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 2) {
                    bleResponseProcessedByReader.complete(Unit)
                }
            },
        )

        val presentmentSource = createAnnexDPresentmentSource(
            onBeforeShowConsentPrompt = {
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        bleRequestProcessedByMdoc.await()
                    }
                }
            }
        )

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            apduResponseMaxSize = 1024L,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(bleCm, connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                mdocTransport.setExpectTransport(true, connectionMethod)
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    val secondaryTransport = blePair.createTransport(
                        connectionMethod = connectionMethod!!,
                        role = MdocRole.MDOC,
                        options = MdocTransportOptions(bleUseL2CAP = true),
                    )
                    secondaryTransport.open(eDeviceKey.publicKey)
                    mdocTransport.setTransport(secondaryTransport)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
                nfcRequestReceivedByMdoc.complete(Unit)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { methods -> methods.firstOrNull() },
        )

        lateinit var tag: TranscriptRecordingNfcIsoTag
        tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
            onAfterEnvelopeResponse = { envelopeIndex, _ ->
                if (envelopeIndex == 2) {
                    nfcFirstResponseChunkLogged.complete(Unit)
                }
            },
            onBeforeGetResponseCommand = { _ ->
                tag.closedDeferred.await()
            }
        )
        val tagReader = SingleTagNfcTagReader(tag)

        val scanResult = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(bleUseL2CAP = true),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            transportFactory = blePair,
            selectConnectionMethod = { methods -> methods.firstOrNull() },
            negotiatedHandoverConnectionMethods = listOf(bleCm),
        )
        assertNotNull(scanResult)
        val readerTransport = scanResult.transport
        val deviceEngagement = DeviceEngagement.fromDataItem(
            Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
        )
        readerTransport.open(deviceEngagement.eDeviceKey)

        val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
            add(scanResult.handover)
        }
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = deviceEngagement.eDeviceKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = true,
        )
        readerEncryption.setSendSessionEstablishment(false)

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.0",
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                )
            )
        }

        val encryptedRequest = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        readerTransport.sendMessage(encryptedRequest)

        val encryptedResponse = readerTransport.waitForMessage()
        val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
        assertNull(status)
        assertNotNull(encodedDeviceResponse)

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
        )

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                bleResponseProcessedByReader.await()
                tag.closedDeferred.await()
            }
        }

        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption.nextSequenceNumber,
        )
        readerTransport.sendMessage(terminationMessage)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                presentmentDone.await()
            }
        }
        readerTransport.close()
        mdocTransport.close()

        assertEquals(1, mdocTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(1, mdocTransport.stats.nfcHybridNumReceivedFirstOnTransport)
        assertEquals(0, readerTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(1, readerTransport.stats.nfcHybridNumReceivedFirstOnTransport)

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 1024
            # }
            R-APDU: a20019ffff011904009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f51518800400
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and the selected connection method.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 538185a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f5151880058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e8063736571000400

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100

            # The mdoc returns a response chunk (1024 bytes) with status 6100 (more data available).
            R-APDU: 5382074ca3646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6100

            # The mdoc sends SessionData (seq=1, disconnectNfc=true) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            BLE Mdoc->Reader: a3646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e4063736571016d646973636f6e6e6563744e6663f5

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 6: Concurrent NFC + BLE handover where BLE connection establishment is slow and only
     * completes *after* `DeviceRequest` (`seq = 0`) and `DeviceResponse` (`seq = 1`) have already
     * been exchanged over NFC.
     *
     * Sequence of events:
     * - Reader sends `SELECT` for the NFC CCE AID; mdoc responds with max command/response lengths
     *   (`65535` / `65536`).
     * - Reader sends `ENVELOPE` #1 (`CCEHandoverRequest`) offering BLE Central Client Mode + L2CAP
     *   PSM `128`; mdoc responds (`9000`) with `CCEHandoverSelect` selecting BLE.
     * - While BLE connection establishment is still in progress, reader sends `ENVELOPE` #2
     *   (`C-APDU`) with `SessionData` (`seq = 0`, encrypted `DeviceRequest`) over NFC.
     * - Mdoc receives `seq = 0` over NFC and generates `DeviceResponse` (`seq = 1`). Because BLE is
     *   not yet connected on the mdoc side, `seq = 1` does *not* include `"disconnectNfc": true`.
     * - Mdoc returns `seq = 1` in the `ENVELOPE` #2 `R-APDU` (`9000`) over NFC and also records
     *   `seq = 1` in its outgoing BLE backlog.
     * - Reader receives `seq = 1` over NFC and prunes `seq = 0` (`0 < 1`) from its outgoing BLE
     *   backlog (per ISO/IEC 18013-5 Second Edition Clause 9.5.5 Note 1). Since `"disconnectNfc"`
     *   was not set, the NFC tag remains open.
     * - BLE connection completes on both sides:
     *   - Reader's outgoing BLE backlog is now empty (`seq = 0` was pruned), so reader does *not*
     *     re-send `seq = 0` over BLE.
     *   - Mdoc flushes its outgoing BLE backlog (`seq = 1`) over BLE (`BLE Mdoc->Reader`), which
     *     the reader deduplicates (already received via NFC).
     * - Reader sends Session Termination (`seq = 2`, `"status": 20`) on both NFC (`ENVELOPE` #3
     *   `C-APDU` -> `R-APDU: 53009000`) and BLE (`BLE Reader->Mdoc`).
     */
    @Test
    fun testBleHandoverLateBleConnectionPrunesBacklog() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val bleCm = getStaticBleConnectionMethod()
        val transcriptLogger = TranscriptLogger()

        val nfcExchange1Completed = CompletableDeferred<Unit>()
        val bleBacklogResponseProcessedByReader = CompletableDeferred<Unit>()
        val nfcTerminationCompleted = CompletableDeferred<Unit>()
        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val blePair = LoopbackBleTransportPair(
            connectionMethod = bleCm,
            transcript = transcriptLogger,
            onBeforeReaderOpen = {
                nfcExchange1Completed.await()
            },
            onReaderEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 2) {
                    bleBacklogResponseProcessedByReader.complete(Unit)
                }
            },
            onBeforeReaderSend = { msgIndex, _ ->
                if (msgIndex == 1) {
                    // This is seq = 2 (termination), because seq = 0 was pruned from backlog!
                    nfcTerminationCompleted.await()
                }
            }
        )

        val presentmentSource = createAnnexDPresentmentSource()

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(bleCm, connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                mdocTransport.setExpectTransport(true, connectionMethod)
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    launch {
                        nfcExchange1Completed.await()
                        val secondaryTransport = blePair.createTransport(
                            connectionMethod = connectionMethod!!,
                            role = MdocRole.MDOC,
                            options = MdocTransportOptions(bleUseL2CAP = true),
                        )
                        secondaryTransport.open(eDeviceKey.publicKey)
                        mdocTransport.setTransport(secondaryTransport)
                    }
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { methods -> methods.firstOrNull() },
        )

        val tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
            onAfterEnvelopeResponse = { envelopeIndex, _ ->
                if (envelopeIndex == 3) {
                    nfcTerminationCompleted.complete(Unit)
                }
            }
        )
        val tagReader = SingleTagNfcTagReader(tag)

        val scanResult = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(bleUseL2CAP = true),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            transportFactory = blePair,
            selectConnectionMethod = { methods -> methods.firstOrNull() },
            negotiatedHandoverConnectionMethods = listOf(bleCm),
        )
        assertNotNull(scanResult)
        val readerTransport = scanResult.transport
        val deviceEngagement = DeviceEngagement.fromDataItem(
            Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
        )
        readerTransport.open(deviceEngagement.eDeviceKey)

        val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
            add(scanResult.handover)
        }
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = deviceEngagement.eDeviceKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = true,
        )
        readerEncryption.setSendSessionEstablishment(false)

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            version = "1.0",
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                )
            )
        }

        val encryptedRequest = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        readerTransport.sendMessage(encryptedRequest)

        val encryptedResponse = readerTransport.waitForMessage()
        val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
        assertNull(status)
        assertNotNull(encodedDeviceResponse)

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
        )

        // Allow BLE connection to complete now that seq = 0 and seq = 1 completed on NFC
        nfcExchange1Completed.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                bleBacklogResponseProcessedByReader.await()
            }
        }

        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption.nextSequenceNumber,
        )
        readerTransport.sendMessage(terminationMessage)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                presentmentDone.await()
                nfcTerminationCompleted.await()
            }
        }
        readerTransport.close()
        mdocTransport.close()

        assertEquals(2, mdocTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(0, mdocTransport.stats.nfcHybridNumReceivedFirstOnTransport)
        assertEquals(1, readerTransport.stats.nfcHybridNumSentViaTransport)

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f51518800000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and the selected connection method.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 538185a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f5151880058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100fffa
            # The mdoc responds with SessionData (seq=1) containing encrypted DeviceResponse.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1
            # }
            R-APDU: 5382073da2646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e4063736571019000

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e406373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over NFC.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            C-APDU: 00c30000000010530ea266737461747573146373657102fffa
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 7: BLE handover where the NFC field is lost while waiting for `ENVELOPE` #2 `R-APDU`
     * (during user consent).
     *
     * Sequence of events:
     * - Reader sends `SELECT` (`00A4040007A0000002480401`) for the NFC CCE AID.
     * - Mdoc responds (`9000`) with `{0: 65535, 1: 65536}`.
     * - Reader sends `ENVELOPE` #1 with `CCEHandoverRequest` offering BLE Central Client Mode with
     *   Direct L2CAP PSM (`128`).
     * - Mdoc selects the BLE connection method and responds (`9000`) with `CCEHandoverSelect`.
     * - Reader sends `ENVELOPE` #2 `C-APDU` containing `DO'53'` with `SessionData` (`seq = 0`,
     *   encrypted `DeviceRequest`).
     * - Mdoc receives `seq = 0` over NFC and enters the user consent prompt.
     * - While the mdoc is waiting in the consent prompt (before `ENVELOPE` #2 `R-APDU` can be
     *   returned), the NFC field breaks (`NFC: Field lost`).
     * - Reader catches `NfcTagLostException`, marks NFC unavailable (`nfcDisconnectedDuringTransaction = true`),
     *   closes the NFC tag, and continues the session on BLE.
     * - BLE connects; reader flushes its pending `SessionData` (`seq = 0`) over BLE
     *   (`BLE Reader->Mdoc`), which the mdoc ignores as a duplicate of `seq = 0`.
     * - User consent completes; mdoc sees NFC is disconnected and generates `SessionData` (`seq = 1`,
     *   encrypted `DeviceResponse` *without* `"disconnectNfc": true`) and sends it over BLE
     *   (`BLE Mdoc->Reader`).
     * - Reader receives and verifies `seq = 1` over BLE, then sends `SessionData` (`seq = 2`,
     *   `"status": 20` session termination) over BLE (`BLE Reader->Mdoc`).
     */
    @Test
    fun testBleHandoverFieldLostDuringEnvelope2() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val bleMethod = getStaticBleConnectionMethod()
        val transcriptLogger = TranscriptLogger()

        val bleOpenGate = CompletableDeferred<Unit>()
        val mdocEnteredSecondBleWait = CompletableDeferred<Unit>()
        val bleTerminationReceivedByMdoc = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper
        lateinit var blePair: LoopbackBleTransportPair
        lateinit var tag: TranscriptRecordingNfcIsoTag

        var isNfcFieldActive = true

        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        val presentmentSource = createAnnexDPresentmentSource(
            onBeforeShowConsentPrompt = {
                // Simulate NFC field loss while ENVELOPE #2 is in-flight waiting for user consent
                tag.simulateFieldLoss()
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        tag.closedDeferred.await()
                    }
                }
                // Now allow BLE to connect and flush the reader's duplicate seq = 0 before consent finishes
                bleOpenGate.complete(Unit)
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        mdocEnteredSecondBleWait.await()
                    }
                }
            }
        )

        blePair = LoopbackBleTransportPair(
            connectionMethod = bleMethod,
            transcript = transcriptLogger,
            onBeforeReaderOpen = {
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        bleOpenGate.await()
                    }
                }
                mdocTransport.setTransport(blePair.mdocTransport)
            },
            onMdocEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 2) {
                    mdocEnteredSecondBleWait.complete(Unit)
                } else if (waitIndex == 3) {
                    bleTerminationReceivedByMdoc.complete(Unit)
                }
            }
        )

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(bleMethod, connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        if (isNfcFieldActive) {
                            engagementHelper.sendMessage(message)
                            true
                        } else {
                            false
                        }
                    }
                )
                mdocTransport.setExpectTransport(true, connectionMethod)
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { methods -> methods.firstOrNull() },
        )

        tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
            onFieldLost = {
                isNfcFieldActive = false
                mdocTransport.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)
            },
        )
        val tagReader = SingleTagNfcTagReader(tag)

        val scanResult = checkNotNull(
            tagReader.scanMdocReader(
                message = null,
                options = MdocTransportOptions(),
                handoverOptions = MdocReaderNfcHandoverOptions(
                    useCce = true,
                    eReaderKey = eReaderKey,
                ),
                transportFactory = blePair,
                selectConnectionMethod = { methods -> methods.firstOrNull() },
                negotiatedHandoverConnectionMethods = listOf(bleMethod),
                context = coroutineContext,
            )
        )
        assertEquals(MdocHandoverType.CCE_HANDOVER, scanResult.type)

        val readerTransport = scanResult.transport
        readerTransport.open(eReaderKey.publicKey)

        val encodedEReaderKeyCose = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKeyCose)))
            add(scanResult.handover)
        }
        val encodedSessionTranscript = Cbor.encode(sessionTranscript)

        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = eDeviceKey.publicKey,
            encodedSessionTranscript = encodedSessionTranscript,
            insertSequenceNumbers = true,
        ).apply {
            setSendSessionEstablishment(false)
        }

        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "family_name" to false
                    )
                )
            )
        }
        val encryptedRequest = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        readerTransport.sendMessage(encryptedRequest)

        val encryptedResponse = readerTransport.waitForMessage()
        val (encodedResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
        assertNull(status)
        assertNotNull(encodedResponse)

        val validAt = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC)
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(
                privateKey = eReaderKey,
                algorithm = Algorithm.ECDH_P256,
            ),
            atTime = validAt,
        )
        assertEquals(1, deviceResponse.documents.size)

        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption.nextSequenceNumber,
        )
        readerTransport.sendMessage(terminationMessage)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                presentmentDone.await()
                bleTerminationReceivedByMdoc.await()
            }
        }
        readerTransport.close()
        mdocTransport.close()

        assertTrue(readerTransport.stats.nfcDisconnectedDuringTransaction)
        assertTrue(mdocTransport.stats.nfcDisconnectedDuringTransaction)
        assertEquals(1, mdocTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(1, mdocTransport.stats.nfcHybridNumReceivedFirstOnTransport)
        assertEquals(0, readerTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(1, readerTransport.stats.nfcHybridNumReceivedFirstOnTransport)

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f51518800000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and the selected connection method.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 538185a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f5151880058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100fffa

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e406373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 8: BLE handover where the NFC field is lost immediately after handover (`ENVELOPE` #1).
     *
     * Sequence of events:
     * - Reader sends `SELECT` (`00A4040007A0000002480401`) for the NFC CCE AID.
     * - Mdoc responds (`9000`) with `{0: 65535, 1: 65536}`.
     * - Reader sends `ENVELOPE` #1 with `CCEHandoverRequest` offering BLE Central Client Mode with
     *   Direct L2CAP PSM (`128`).
     * - Mdoc selects the BLE connection method and responds (`9000`) with `CCEHandoverSelect`.
     * - Immediately after handover completes (before `ENVELOPE` #2 `C-APDU` can be transmitted),
     *   the NFC field breaks (`NFC: Field lost`).
     * - Reader attempts to send `seq = 0` (`DeviceRequest`) on NFC, catches `NfcTagLostException`,
     *   marks NFC unavailable (`nfcDisconnectedDuringTransaction = true`), and closes the NFC tag.
     * - BLE connects; reader flushes `SessionData` (`seq = 0`, encrypted `DeviceRequest`) over BLE
     *   (`BLE Reader->Mdoc`).
     * - Mdoc receives `seq = 0` over BLE, obtains user consent, and generates `SessionData` (`seq = 1`,
     *   encrypted `DeviceResponse` *without* `"disconnectNfc": true` since NFC is already disconnected).
     * - Mdoc sends `seq = 1` over BLE (`BLE Mdoc->Reader`).
     * - Reader receives and verifies `seq = 1` over BLE, then sends `SessionData` (`seq = 2`,
     *   `"status": 20` session termination) over BLE (`BLE Reader->Mdoc`).
     */
    @Test
    fun testBleHandoverFieldLostAfterHandover() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val bleMethod = getStaticBleConnectionMethod()
        val presentmentSource = createAnnexDPresentmentSource()
        val transcriptLogger = TranscriptLogger()

        val bleOpenGate = CompletableDeferred<Unit>()
        val bleTerminationReceivedByMdoc = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper
        lateinit var blePair: LoopbackBleTransportPair
        lateinit var tag: TranscriptRecordingNfcIsoTag

        var isNfcFieldActive = true

        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        blePair = LoopbackBleTransportPair(
            connectionMethod = bleMethod,
            transcript = transcriptLogger,
            onBeforeReaderOpen = {
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        bleOpenGate.await()
                    }
                }
                mdocTransport.setTransport(blePair.mdocTransport)
            },
            onMdocEnterWaitForMessage = { waitIndex ->
                if (waitIndex == 3) {
                    bleTerminationReceivedByMdoc.complete(Unit)
                }
            }
        )

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(bleMethod, connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        if (isNfcFieldActive) {
                            engagementHelper.sendMessage(message)
                            true
                        } else {
                            false
                        }
                    }
                )
                mdocTransport.setExpectTransport(true, connectionMethod)
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { methods -> methods.firstOrNull() },
        )

        tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
            onFieldLost = {
                isNfcFieldActive = false
                mdocTransport.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)
            },
            onBeforeEnvelopeCommand = { envelopeIndex, _ ->
                if (envelopeIndex == 2) {
                    presentmentReady.await()
                    tag.simulateFieldLoss()
                }
            }
        )
        val tagReader = SingleTagNfcTagReader(tag)

        val scanResult = checkNotNull(
            tagReader.scanMdocReader(
                message = null,
                options = MdocTransportOptions(),
                handoverOptions = MdocReaderNfcHandoverOptions(
                    useCce = true,
                    eReaderKey = eReaderKey,
                ),
                transportFactory = blePair,
                selectConnectionMethod = { methods -> methods.firstOrNull() },
                negotiatedHandoverConnectionMethods = listOf(bleMethod),
                context = coroutineContext,
            )
        )
        assertEquals(MdocHandoverType.CCE_HANDOVER, scanResult.type)

        val readerTransport = scanResult.transport
        readerTransport.open(eReaderKey.publicKey)

        val encodedEReaderKeyCose = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKeyCose)))
            add(scanResult.handover)
        }
        val encodedSessionTranscript = Cbor.encode(sessionTranscript)

        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = eDeviceKey.publicKey,
            encodedSessionTranscript = encodedSessionTranscript,
            insertSequenceNumbers = true,
        ).apply {
            setSendSessionEstablishment(false)
        }

        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "family_name" to false
                    )
                )
            )
        }
        val encryptedRequest = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        readerTransport.sendMessage(encryptedRequest)

        // Wait for the reader's NFC ioJob to observe the broken field and close the tag before opening BLE
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                tag.closedDeferred.await()
            }
        }
        bleOpenGate.complete(Unit)

        val encryptedResponse = readerTransport.waitForMessage()
        val (encodedResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
        assertNull(status)
        assertNotNull(encodedResponse)

        val validAt = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC)
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(
                privateKey = eReaderKey,
                algorithm = Algorithm.ECDH_P256,
            ),
            atTime = validAt,
        )
        assertEquals(1, deviceResponse.documents.size)

        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption.nextSequenceNumber,
        )
        readerTransport.sendMessage(terminationMessage)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                presentmentDone.await()
                bleTerminationReceivedByMdoc.await()
            }
        }
        readerTransport.close()
        mdocTransport.close()

        assertTrue(readerTransport.stats.nfcDisconnectedDuringTransaction)
        assertTrue(mdocTransport.stats.nfcDisconnectedDuringTransaction)
        assertEquals(0, mdocTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(2, mdocTransport.stats.nfcHybridNumReceivedFirstOnTransport)
        assertEquals(0, readerTransport.stats.nfcHybridNumReceivedFirstOnNfc)
        assertEquals(1, readerTransport.stats.nfcHybridNumReceivedFirstOnTransport)

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and offered connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f51518800000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and the selected connection method.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     2: [
            #       [
            #         2,
            #         1,
            #         {
            #           0: false,
            #           1: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           2: false,
            #           3: true,
            #           21: 128
            #         }
            #       ]
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 538185a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f401f50b5000000001a12348ce896b4c76973373e602f403f5151880058006a203f504f59000

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e80',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887b999229ac10dd90711546e968c3665ad3ca26cb431eef60401616648723edba117cc35b94f88f18a99ecac49d921ca914aa3788bea045c0760039bf43421a539cb71fba2c98ba0cd251c69e94b53e68fc564d8b7146255ecf817806d8e0330f855b40c9cb67a5cfce042a77e49fa228c75ea92699f1311cebcf2252a26f63d2fdfeea2e6e85e806373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e40',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072f1a5bb42b811812446e47bcbf39a725fd0ec7bf1c1c25c4c3f2a87cd8ddc926b512b9d491941e7ec827d513c13e08fb31cbebcffc83786c1fa843d1ff159cfb3c8a132d2f2bdafbe057b5928951b74a3eacf45761b6ae9da390f2bf62d811976c121be4180bec363e49906dff9a3b7422221fd00e29abc11cfad84dbca4af50bd6fec09ad0605e8134ce619f12b3652d8ce85b0f87266f35fc27f3a1a4c8aa10196d879a9c87439ead30ad2ae6eae58205145e7d5ab66e449f61de6dbd6b120b8135fb995cfce18fcc8755620fd1d927c11fc0fddd35a67f5478db0ce4d9907d87a07fd80eb832c8fa82d5a1981723ff999bd551460f40c078e34c6023a831cf3e534f5db257b9fd28c00a4b553aa63df1e0e11e687eacdb9f5aed904d488b015dee6d04146a38823ca7bd5ac28092e89b94ed1791e2628c770cfbdc27185478d924bfc934d0ad3c94fb4555ecba1e040c52fd3302ba17d412874c6fb59aa2c9bf7df7d415d5ee55b5e5e9653c61167772c88b7d8cef2f35b3179c144ece61e8394500552c96a01031203585fde152ad55ae8c6954973a6ed740fcdd6c0a158c71c98f004c2b5aa35b8254f0c275603b18e5709b843488877fc4830650d4e370d6d4f3afb483a30938b6ce34cf9eb53dad9c26c08ab3b105d1d1de32a3cc5fce5f11efa82dae78509d36b8ccaaa0a7b02790cef053b638b8e268804b719c42b6a129579e610e3843ba02d2af89f20b4c47605db570d8ae0fb3b41eb35ecd48dcdbdde3589ff05307de543b8bfea548f850f94f8671566f7c13f4ee91e527a027ec0587e0cf66505be9594a1507eb5cd93b4b6874d66a76a8397bf0044099d6bb64098a508781f52054938361dcb2230acfbaa3b4339a1bb355303880c45b9cc0c542b7e525d0c9bfac3b18184b51959778afb55162d54d667aec5678fc9f4a827f8da947be82a30bd5269da407520e86af6ab884abfbc15421f050382623344a2e5f75577e37cbfe096ec972a06bc45826a715338a9246e38c5bc7c911ec52abf50708b1b94e8fbb711ba551bf292a85a6995a91d30e26ce2bd9d060f23651a4de417914aeea0ba5421533610f43adbebc5d2bf732e225d36b2c444357c5bae1dc86ed5be0631118497eb34a7745f47232fd7ad5f56be31db18669e72429f5aad13e446bf47f287d60a9d483901e46b98cd58f7a42d5f73641f5c829f81a117dcb31875059204164c10b96452758a53c5db3f237e4504bbcd338005f436ff687c541ac58520fc3ddf3a2f681efeb6bf637f844f38edf9cecd4030485c645e80d8aea4d30ec909591a302c20c52d65e5ea51b4f2407632d16c16836187d71b476cbeb0d221df6c4650530f627c7a34a98a31b4f3b3c223a105b79048ff3bbf8080c3d8c82d161ab35f7f9a0c3896326ee369b3bf6cd1b144d6ce97325f1cc72c2cc6545b3e0a2f55be39041a8155b4bb1e47a419b8dd602cd411f6bdc51fe97e58f6e8fed450a4f4d9f6bd3d202420ba7fd34d9aa60b05f683cdce2a2349b21336431c60280607ca684ed67d3a8c07d18b95ef283fff020a49a2dc54f87478d093335191e79176656d70224e8d86c2624fc209d6b2f1520365bdfc07fe72183fe383bf005e9c78f23a73da334126cc4325b84fbb0fd85f3a61f415fa098363b7cfe8c33c24840b10b62bf8b248a664bae6db7a298225c301f2858bd4367dbd7347e42e9ddf15bded6086916a14c36ef253d52892451a0d9db18f8f988275dcdf5960cee999edb942e9bd13a7c3029c17e43a6a29b17e0e6bcb9dfa34be603c7db97f6b09a432b8af620c16d673fd2df1cc03d5f89554ae791fdae3cc224d521eb381d6c95e7983af7f54912691db03e48b8194c99c1bbd00b0745ad753d90fcfba50a6e6b29eb7d1f4c14ac04da2b0dde7924f0229e6729116fe97fe5345e4e5d15828fc3baa403700c58891dd3d297cd087ab26eca3aa29ad1dcce3b1e22f91d2cfcf83b8cacfabdf4c5b21d7c074f8ccf78149b00f6fdda3c80653fb654da4fc062c8557261b26497529623046d43f445a9c8f25a5f1bec7f7f347a2c5677a34f502d67979a73eff3e5d6967f851e605ac02ab33d4ef1b23c596f46b11f9f4a3d8a3bb14416b480e1d94c903423a9170988e197038ad976c642e6f8d20bd09c05e733015bd3222b98657ab9c7fc06cc4b4a109f4a8efe70aaedd15c11f3f09977424f07313ad61ed7bf506101bd289dee1de72ac7cb40ffc3d89be4f9a47c05cb29d79f37f770cdf297963f70ff345551e318346ebec0f7f399f09bfe0238808cb3b9695b72872dc7461a65f86c90be075f8f2661d88650811682a0d1a77c62ca3ce8ed606cc1eb092e650c9a7730823710e4cc6af1710f328d6b19cc261899c874c1d5ff25030ad6ae64a118076591671756b980ba031b05badf67a33e45e5563f95adba4ebf8ef49d0ed95fc3f55bdd9de34dca05c117dfaf562c84d1688ced6c2e368941106d5bbc48d031c5e3f7029071c3bfb07333dc00a4e8a73790008239eeb8d967fd3c51f9088a10da215a5094a88e24ff1e907ef0745ae22bea32b3e2085ad98fc0597a74bfcf0f29e406373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 9: NFC-only multi-tap presentment where the NFC field is lost during user consent on
     * Tap 1 and the session completes on Tap 2 (`NFC: Re-tapped`).
     *
     * Sequence of events:
     * - **Tap 1:**
     *   - Reader sends `SELECT` (`00A4040007A0000002480401`) for the NFC CCE AID.
     *   - Mdoc responds (`9000`) with `{0: 65535, 1: 65536}`.
     *   - Reader sends `ENVELOPE` #1 with `CCEHandoverRequest` (no alternative connection methods).
     *   - Mdoc responds (`9000`) with `CCEHandoverSelect` (NFC-only).
     *   - Reader sends `ENVELOPE` #2 `C-APDU` containing `DO'53'` with `SessionData` (`seq = 0`,
     *     encrypted `DeviceRequest`).
     *   - Mdoc decrypts `seq = 0`, marks a response as pending, and enters the user consent prompt.
     *   - While the mdoc is waiting in the consent prompt, the user removes the phone from the
     *     reader field (`NFC: Field lost`).
     *   - Reader's `waitForMessage()` throws `MdocTransportException` (caused by
     *     `NfcTagLostException`), and [scanMdocReader] closes Tap 1's transport and continues
     *     polling for a re-tap.
     *   - User grants consent off-field; [Iso18013Presentment] sees `isNfcConnected == false` and
     *     suspends waiting for a re-tap.
     * - **Tap 2 (`NFC: Re-tapped`):**
     *   - User taps the phone again (`NFC: Re-tapped`).
     *   - Reader sends `SELECT` (`00A4040007A0000002480401`) -> Mdoc responds (`9000`) with
     *     `{0: 65535, 1: 65536}`.
     *   - Reader sends `ENVELOPE` #1 with `CCEHandoverRequest` -> Mdoc updates its active
     *     `EngagementParams` and responds (`9000`) with `CCEHandoverSelect`.
     *   - Reader sends `ENVELOPE` #2 `C-APDU` (`seq = 0`, encrypted `DeviceRequest`).
     *   - Mdoc receives the re-tap on NFC, reuses the user consent obtained on Tap 1 without
     *     prompting again, and immediately responds (`9000`) to `ENVELOPE` #2 with `DO'53'`
     *     containing `SessionData` (`seq = 1`, encrypted `DeviceResponse`).
     *   - Reader decrypts and verifies `seq = 1`, then sends `ENVELOPE` #3 `C-APDU` (`seq = 2`,
     *     `"status": 20` session termination).
     *   - Mdoc responds (`9000`) with an empty `DO'53'` (`5300`) and closes the session.
     */
    @Test
    fun testNfcOnlyMultiTapFieldLostDuringConsent() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val transcriptLogger = TranscriptLogger()

        var consentPromptCount = 0
        val readyForRetap = CompletableDeferred<Unit>()
        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()
        val tag2TerminationCompleted = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var currentEngagementHelper: MdocNfcCceEngagementHelper
        lateinit var tag1: TranscriptRecordingNfcIsoTag
        lateinit var tag2: TranscriptRecordingNfcIsoTag

        val presentmentSource = createAnnexDPresentmentSource(
            onBeforeShowConsentPrompt = {
                consentPromptCount += 1
                // User removes phone from NFC field while looking at the consent prompt on Tap 1
                tag1.simulateFieldLoss()
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        tag1.closedDeferred.await()
                    }
                }
            }
        )

        val engagementHelper1 = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertNull(connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        currentEngagementHelper.sendMessage(message)
                        true
                    }
                )
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                        onSendingResponse = {
                            readyForRetap.complete(Unit)
                        },
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper (Tap 1): $error")
            },
            handoverPicker = { null },
        )
        currentEngagementHelper = engagementHelper1

        lateinit var engagementHelper2: MdocNfcCceEngagementHelper
        engagementHelper2 = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertNull(connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                currentEngagementHelper = engagementHelper2
                activeEngagementParamsFlow.value = EngagementParams(
                    eDeviceKey = eDeviceKey.duplicate(),
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper (Tap 2): $error")
            },
            handoverPicker = { null },
        )

        tag1 = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper1,
            transcript = transcriptLogger,
            onFieldLost = {
                mdocTransport.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)
            },
        )
        tag2 = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper2,
            transcript = transcriptLogger,
            onAfterEnvelopeResponse = { envelopeIndex, _ ->
                if (envelopeIndex == 3) {
                    tag2TerminationCompleted.complete(Unit)
                }
            },
        )

        val tagReader = MultiTagNfcTagReader(
            tags = listOf(tag1, tag2),
            onBeforeNextTap = { tapIndex ->
                assertEquals(2, tapIndex)
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        readyForRetap.await()
                    }
                }
                transcriptLogger.appendEvent("NFC: Re-tapped")
            },
        )

        var handoverAttempt = 0
        val validAt = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC)
        val deviceResponse = checkNotNull(
            tagReader.scanMdocReader(
                message = null,
                options = MdocTransportOptions(),
                handoverOptions = MdocReaderNfcHandoverOptions(
                    useCce = true,
                    eReaderKey = eReaderKey,
                ),
                selectConnectionMethod = { methods -> methods.firstOrNull() },
                negotiatedHandoverConnectionMethods = emptyList(),
                context = coroutineContext,
                onHandover = { scanResult ->
                    handoverAttempt += 1
                    assertEquals(MdocHandoverType.CCE_HANDOVER, scanResult.type)

                    val readerTransport = scanResult.transport
                    readerTransport.open(eReaderKey.publicKey)

                    val encodedEReaderKeyCose = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                    val sessionTranscript = buildCborArray {
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
                        add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKeyCose)))
                        add(scanResult.handover)
                    }
                    val encodedSessionTranscript = Cbor.encode(sessionTranscript)

                    val readerEncryption = SessionEncryption(
                        role = MdocRole.MDOC_READER,
                        eSelfKey = eReaderKey,
                        remotePublicKey = eDeviceKey.publicKey,
                        encodedSessionTranscript = encodedSessionTranscript,
                        insertSequenceNumbers = true,
                    ).apply {
                        setSendSessionEstablishment(false)
                    }

                    val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
                        addDocRequest(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            nameSpaces = mapOf(
                                DrivingLicense.MDL_NAMESPACE to mapOf(
                                    "family_name" to false
                                )
                            )
                        )
                    }
                    val encryptedRequest = readerEncryption.encryptMessage(
                        messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                        statusCode = null,
                    )
                    readerTransport.sendMessage(encryptedRequest)

                    // On Tap 1, waitForMessage() throws MdocTransportException caused by NfcTagLostException,
                    // which scanMdocReader catches so it can close Tap 1's transport and scan Tap 2.
                    val encryptedResponse = readerTransport.waitForMessage()
                    val (encodedResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
                    assertNull(status)
                    assertNotNull(encodedResponse)

                    val response = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse))
                    response.verify(
                        sessionTranscript = sessionTranscript,
                        eReaderKey = AsymmetricKey.AnonymousExplicit(
                            privateKey = eReaderKey,
                            algorithm = Algorithm.ECDH_P256,
                        ),
                        atTime = validAt,
                    )

                    val terminationMessage = SessionEncryption.encodeStatus(
                        statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                        sequenceNumber = readerEncryption.nextSequenceNumber,
                    )
                    readerTransport.sendMessage(terminationMessage)

                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            presentmentDone.await()
                            tag2TerminationCompleted.await()
                        }
                    }
                    readerTransport.close()
                    response
                }
            )
        )

        assertEquals(2, handoverAttempt)
        assertEquals(1, consentPromptCount)
        assertEquals(1, deviceResponse.documents.size)
        mdocTransport.close()
        tag2.closedDeferred.await()

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ]
            #   }
            # }
            C-APDU: 00c3000000005c535aa100a20063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 5362a100a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'4841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158874841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c6373657100fffa

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc is re-tapped on the mdoc reader.
            NFC: Re-tapped

            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ]
            #   }
            # }
            C-APDU: 00c3000000005c535aa100a20063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 5362a100a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'4841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158874841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c6373657100fffa
            # The mdoc responds with SessionData (seq=1) containing encrypted DeviceResponse.
            # {
            #   "data": h'7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce99f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa78dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce35',
            #   "seq": 1
            # }
            R-APDU: 5382073da2646461746159072f7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce99f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa78dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce3563736571019000

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over NFC.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            C-APDU: 00c30000000010530ea266737461747573146373657102fffa
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, transcriptLogger.toString())
    }

    /**
     * Test 10: Single-tap NFC-only CCE engagement and presentment with small `maxCommandDataLength`
     * and `maxResponseDataLength` values (`100` and `900` bytes), exercising ISO/IEC 7816-4 command
     * chaining and response chaining (`GET RESPONSE` with both `6100` and `61XX` status words).
     *
     * Sequence of events:
     * - Reader sends `SELECT` (`00A4040007A0000002480401`) for the NFC CCE AID.
     * - Mdoc responds (`9000`) with CBOR `{0: 100, 1: 900}` advertising `maxCommandDataLength = 100`
     *   and `maxResponseDataLength = 900`.
     * - Reader sends `ENVELOPE` #1 (`92` bytes, fits within `100` bytes, `CLA = 0x00`, `Le = 900`)
     *   containing `DO'53'` with `CCEHandoverRequest`.
     * - Mdoc responds (`9000`) with `DO'53'` (`98` bytes, fits within `900` bytes) containing
     *   `CCEHandoverSelect`.
     * - Reader sends `ENVELOPE` #2 (`151`-byte `DO'53'` containing `SessionData` `seq = 0`, encrypted
     *   `DeviceRequest` for `family_name`) split across two command-chained APDUs:
     *   - Chunk 1 (`100` bytes, `CLA = 0x10`, `Le = 0`) -> Mdoc responds `9000`.
     *   - Chunk 2 (`51` bytes, `CLA = 0x00`, `Le = 900`).
     * - Mdoc reassembles and decrypts `seq = 0`, generates the `1857`-byte `DO'53'` (`SessionData`
     *   `seq = 1`, encrypted `DeviceResponse`), and returns it across three response-chained APDUs:
     *   - Chunk 1 (`900` bytes) with status `6100` (`957 > 255` bytes remaining).
     *   - Reader sends extended `GET RESPONSE` (`00C00000000384`, `Le = 900`) -> Mdoc returns
     *     Chunk 2 (`900` bytes) with status `6139` (`57 = 0x39 <= 255` bytes remaining).
     *   - Reader sends short `GET RESPONSE` (`00C0000039`, `Le = 57`) -> Mdoc returns Chunk 3
     *     (`57` bytes) with status `9000`.
     * - Reader reassembles, decrypts, and verifies `seq = 1`, then sends `ENVELOPE` #3 (`16` bytes,
     *   fits within `100` bytes) containing `DO'53'` with `SessionData` (`seq = 2`, `"status": 20`
     *   session termination).
     * - Mdoc responds (`9000`) with an empty `DO'53'` (`5300`) and closes the session.
     * - Reader closes the NFC tag.
     */
    @Test
    fun testNfcOnlyApduCommandAndResponseChaining() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val presentmentSource = createAnnexDPresentmentSource()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertNull(connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { null },
            apduCommandMaxSize = 100L,
            apduResponseMaxSize = 900L,
        )

        val tag = TranscriptRecordingNfcIsoTag(engagementHelper)
        val tagReader = SingleTagNfcTagReader(tag)

        val verifiedDeviceResponse = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            selectConnectionMethod = { null },
            negotiatedHandoverConnectionMethods = emptyList(),
            onHandover = { scanResult ->
                assertEquals(MdocHandoverType.CCE_HANDOVER, scanResult.type)
                val readerTransport = scanResult.transport
                val deviceEngagementItem = Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
                val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementItem)
                readerTransport.open(deviceEngagement.eDeviceKey)

                val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                val sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
                    add(scanResult.handover)
                }
                val encodedSessionTranscript = Cbor.encode(sessionTranscript)

                val readerEncryption = SessionEncryption(
                    role = MdocRole.MDOC_READER,
                    eSelfKey = eReaderKey,
                    remotePublicKey = deviceEngagement.eDeviceKey,
                    encodedSessionTranscript = encodedSessionTranscript,
                    insertSequenceNumbers = true,
                ).apply {
                    setSendSessionEstablishment(false)
                }

                val deviceRequest = buildDeviceRequest(
                    sessionTranscript = sessionTranscript,
                    version = "1.0",
                ) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                        )
                    )
                }

                val encryptedRequest = readerEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                    statusCode = null,
                )
                readerTransport.sendMessage(encryptedRequest)

                val encryptedResponse = readerTransport.waitForMessage()
                val (encodedDeviceResponse, status) = readerEncryption.decryptMessage(encryptedResponse)
                assertNull(status)
                assertNotNull(encodedDeviceResponse)

                val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
                deviceResponse.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
                )

                val terminationMessage = SessionEncryption.encodeStatus(
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                    sequenceNumber = readerEncryption.nextSequenceNumber,
                )
                readerTransport.sendMessage(terminationMessage)
                readerTransport.close()

                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        presentmentDone.await()
                        tag.closedDeferred.await()
                    }
                }
                mdocTransport.close()
                deviceResponse
            }
        )

        assertNotNull(verifiedDeviceResponse)
        assertEquals(1, verifiedDeviceResponse.documents.size)
        val doc = verifiedDeviceResponse.documents.first()
        assertEquals(DrivingLicense.MDL_DOCTYPE, doc.docType)
        assertEquals(
            "Doe",
            doc.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["family_name"]!!.dataElementValue.asTstr
        )

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 100,
            #   1: 900
            # }
            R-APDU: a2001864011903849000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ]
            #   }
            # }
            C-APDU: 00c3000000005c535aa100a20063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0384
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 5362a100a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f59000

            # The mdoc reader sends a command-chained ENVELOPE chunk (100 bytes, CLA=0x10).
            C-APDU: 10c3000064538194a2646461746158874841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98
            # The mdoc acknowledges the command-chained chunk.
            R-APDU: 9000

            # The mdoc reader sends the final command-chained ENVELOPE chunk (51 bytes, CLA=0x00) completing SessionData (seq=0) with encrypted DeviceRequest.
            # {
            #   "data": h'4841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c',
            #   "seq": 0
            # }
            C-APDU: 00c30000000033511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c63736571000384
            # The mdoc returns a response chunk (900 bytes) with status 6100 (more data available).
            R-APDU: 5382073da2646461746159072f7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce996100

            # The mdoc reader sends GET RESPONSE requesting up to 900 bytes.
            C-APDU: 00c00000000384
            # The mdoc returns a response chunk (900 bytes) with status 6139 (more data available).
            R-APDU: f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa76139

            # The mdoc reader sends GET RESPONSE requesting up to 57 bytes.
            C-APDU: 00c0000039
            # The mdoc returns the final response chunk (57 bytes) completing SessionData (seq=1) with encrypted DeviceResponse.
            # {
            #   "data": h'7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce99f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa78dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce35',
            #   "seq": 1
            # }
            R-APDU: 8dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce3563736571019000

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over NFC.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            C-APDU: 00c30000000010530ea2667374617475731463736571020384
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, tag.transcript.toString())
    }

    /**
     * Test 11: Single-tap NFC-only CCE engagement and presentment with multiple sequential
     * `DeviceRequest` / `DeviceResponse` exchanges in a single session before termination.
     *
     * Sequence of events:
     * - Reader sends `SELECT` (`00A4040007A0000002480401`) for the NFC CCE AID.
     * - Mdoc responds (`9000`) with CBOR `{0: 65535, 1: 65536}` advertising `maxCommandDataLength`
     *   and `maxResponseDataLength`.
     * - Reader sends `ENVELOPE` #1 containing `DO'53'` with `CCEHandoverRequest` (`version = "1.1"`,
     *   `eReaderKey`, and no alternative `ReaderConnectionMethods`).
     * - Mdoc responds (`9000`) with `DO'53'` containing `CCEHandoverSelect` (`version = "1.1"`,
     *   `eDeviceKey`, and no selected connection method).
     * - Reader sends `ENVELOPE` #2 containing `DO'53'` with `SessionData` (`seq = 0`, encrypted
     *   `DeviceRequest` #1 for `org.iso.18013.5.1.mDL` requesting `family_name`).
     * - Mdoc decrypts `seq = 0`, generates `DeviceResponse` #1 (`family_name = "Doe"`), and responds
     *   (`9000`) with `DO'53'` containing `SessionData` (`seq = 1`).
     * - Reader decrypts and verifies `seq = 1`, then sends `ENVELOPE` #3 containing `DO'53'` with
     *   `SessionData` (`seq = 2`, encrypted `DeviceRequest` #2 for `org.iso.18013.5.1.mDL` requesting
     *   `document_number`).
     * - Mdoc decrypts `seq = 2`, generates `DeviceResponse` #2 (`document_number = "123456789"`), and
     *   responds (`9000`) with `DO'53'` containing `SessionData` (`seq = 3`).
     * - Reader decrypts and verifies `seq = 3`, then sends `ENVELOPE` #4 containing `DO'53'` with
     *   `SessionData` (`seq = 4`, `"status": 20` session termination).
     * - Mdoc responds (`9000`) with an empty `DO'53'` (`5300`) and closes the session.
     * - Reader closes the NFC tag.
     */
    @Test
    fun testNfcOnlyMultipleRequestsInSingleSession() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val presentmentSource = createAnnexDPresentmentSource()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper

        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethod, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertNull(connectionMethod)
                assertEquals(eReaderKey.publicKey, receivedEReaderKey)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey,
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
                mdocTransport = NfcCceTransportMdoc(
                    sendMessageViaNfc = { message ->
                        engagementHelper.sendMessage(message)
                        true
                    }
                )
                launch {
                    mdocTransport.open(eDeviceKey.publicKey)
                    presentmentReady.complete(Unit)
                    Iso18013Presentment(
                        transport = mdocTransport,
                        engagementParams = activeEngagementParamsFlow,
                        source = presentmentSource,
                        keyAgreementPossible = listOf(EcCurve.P256),
                        insertSequenceNumbers = true,
                        timeout = null,
                        timeoutSubsequentRequests = null,
                    )
                    presentmentDone.complete(Unit)
                }
            },
            onMessageReceived = { message ->
                presentmentReady.await()
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("Unexpected error in MdocNfcCceEngagementHelper: $error")
            },
            handoverPicker = { null },
        )

        val tag = TranscriptRecordingNfcIsoTag(engagementHelper)
        val tagReader = SingleTagNfcTagReader(tag)

        val verifiedDeviceResponses = tagReader.scanMdocReader(
            message = null,
            options = MdocTransportOptions(),
            handoverOptions = MdocReaderNfcHandoverOptions(
                useCce = true,
                eReaderKey = eReaderKey,
            ),
            selectConnectionMethod = { null },
            negotiatedHandoverConnectionMethods = emptyList(),
            onHandover = { scanResult ->
                assertEquals(MdocHandoverType.CCE_HANDOVER, scanResult.type)
                val readerTransport = scanResult.transport
                val deviceEngagementItem = Cbor.decode(scanResult.encodedDeviceEngagement.toByteArray())
                val deviceEngagement = DeviceEngagement.fromDataItem(deviceEngagementItem)
                readerTransport.open(deviceEngagement.eDeviceKey)

                val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
                val sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(scanResult.encodedDeviceEngagement.toByteArray())))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
                    add(scanResult.handover)
                }
                val encodedSessionTranscript = Cbor.encode(sessionTranscript)

                val readerEncryption = SessionEncryption(
                    role = MdocRole.MDOC_READER,
                    eSelfKey = eReaderKey,
                    remotePublicKey = deviceEngagement.eDeviceKey,
                    encodedSessionTranscript = encodedSessionTranscript,
                    insertSequenceNumbers = true,
                ).apply {
                    setSendSessionEstablishment(false)
                }

                // Request #1: family_name
                val deviceRequest1 = buildDeviceRequest(
                    sessionTranscript = sessionTranscript,
                    version = "1.0",
                ) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                        )
                    )
                }
                val encryptedRequest1 = readerEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest1.toDataItem()),
                    statusCode = null,
                )
                readerTransport.sendMessage(encryptedRequest1)

                val encryptedResponse1 = readerTransport.waitForMessage()
                val (encodedDeviceResponse1, status1) = readerEncryption.decryptMessage(encryptedResponse1)
                assertNull(status1)
                assertNotNull(encodedDeviceResponse1)
                val deviceResponse1 = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse1))
                deviceResponse1.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
                )

                // Request #2: document_number
                val deviceRequest2 = buildDeviceRequest(
                    sessionTranscript = sessionTranscript,
                    version = "1.0",
                ) {
                    addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf("document_number" to false)
                        )
                    )
                }
                val encryptedRequest2 = readerEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest2.toDataItem()),
                    statusCode = null,
                )
                readerTransport.sendMessage(encryptedRequest2)

                val encryptedResponse2 = readerTransport.waitForMessage()
                val (encodedDeviceResponse2, status2) = readerEncryption.decryptMessage(encryptedResponse2)
                assertNull(status2)
                assertNotNull(encodedDeviceResponse2)
                val deviceResponse2 = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse2))
                deviceResponse2.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
                )

                val terminationMessage = SessionEncryption.encodeStatus(
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                    sequenceNumber = readerEncryption.nextSequenceNumber,
                )
                readerTransport.sendMessage(terminationMessage)
                readerTransport.close()

                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        presentmentDone.await()
                        tag.closedDeferred.await()
                    }
                }
                mdocTransport.close()
                listOf(deviceResponse1, deviceResponse2)
            }
        )

        assertNotNull(verifiedDeviceResponses)
        assertEquals(2, verifiedDeviceResponses.size)
        val doc1 = verifiedDeviceResponses[0].documents.first()
        assertEquals(DrivingLicense.MDL_DOCTYPE, doc1.docType)
        assertEquals(
            "Doe",
            doc1.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["family_name"]!!.dataElementValue.asTstr
        )
        val doc2 = verifiedDeviceResponses[1].documents.first()
        assertEquals(DrivingLicense.MDL_DOCTYPE, doc2.docType)
        assertEquals(
            "123456789",
            doc2.issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["document_number"]!!.dataElementValue.asTstr
        )

        val expectedTranscript = """
            # The mdoc reader selects the NFC CCE application.
            C-APDU: 00a4040007a0000002480401
            # The mdoc responds with maximum command and response data lengths.
            # {
            #   0: 65535,
            #   1: 65536
            # }
            R-APDU: a20019ffff011a000100009000

            # The mdoc reader sends CCEHandoverRequest with eReaderKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
            #         -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
            #       } >>)
            #     ]
            #   }
            # }
            C-APDU: 00c3000000005c535aa100a20063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0000
            # The mdoc responds with CCEHandoverSelect containing eDeviceKey and no connection methods.
            # {
            #   0: {
            #     0: "1.1",
            #     1: [
            #       1,
            #       24(<< {
            #         1: 2,
            #         -1: 1,
            #         -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
            #         -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
            #       } >>)
            #     ],
            #     5: [],
            #     6: {
            #       3: true,
            #       4: true
            #     }
            #   }
            # }
            R-APDU: 5362a100a40063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'4841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158874841c5c172e812b03ea59964abcb7773d59394f72821c1c7a1da6cbe2fe837c9d7d855286a4b407af75471595471d7cdbb32f0d09766790a5526bd7195bce60b7c5faa1459e5ada15e213af1ad8a74157544f9cdcd510cbd98511c65192eca502bd628796f1876d7fb9f57a2129211d5bc1228753ffe85af534f6c2365a65db34091a8b4b7d05c6373657100fffa
            # The mdoc responds with SessionData (seq=1) containing encrypted DeviceResponse.
            # {
            #   "data": h'7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce99f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa78dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce35',
            #   "seq": 1
            # }
            R-APDU: 5382073da2646461746159072f7e4c29785eb3bf95a1d06f30567bdfcfc4fa8da5d92e65d6415b30743952669801ecc682c2a1e6602cbd931bc51c7cc978ad923b6b4657013eca412bba4b57db9e8c8963ada75488bde9ea1b1765af027a9a1560d64df7e1d3371a645d96f2655ee444e242158a5c208cbe748ab4f16456ef0d86f801c112c58af7c4de1d875fa729feeb2eecbfa3db4a41b45ef81df750d316f520d348c9e5bc2621d2a2b982d9540b28985eb402b3909a542048908e130640f80fab0a6a1405a1364f43beb6ad6ad770cc4e7daaa870b5d231aee0a242b5025e1c087f6a0f4d9de863067a39d2b272ee0d22da870f95a42a7690bfec986fa8852b0b6013a4726d1fdbbdc9fb3b956d6b413e71fba240d9833885e5b09b346d2e88c2ebbe9cf4699adc62ac1e8d96ee8f7d604177c251d520e3185bef4987e4d8a9f104f563a6d626a74ad4e5be7e00f529087ab4d67c61b1203ebc8052544d8e50720d09afe69ce9ae9565632b80b4146088fed00042c85a98d509f53e19fd48eb0b62c376a9f4d26e1a1bb799cf0251c0a386bc0c9fe5d73a16e490a93ea067b71375ab288e035b2ede409a4aac96653acf17dadd9dc084bd657c7f0486f96ab7bab2000fd8df8060f9aa2dc29d98c0d522fbca8ea42de4648cbf9824de8c99e270931aa5220c180190f931b73fca6ce1503bc03b8b084d714571ab724b091c6ed981d8c06b156cbf17320cf512bd533c78789e32d43071b638bf201de01c5b7b5539124d44acca930194e7bd264ee6f5b1275dd8e6ca0da9b81552be3963e9944ad78bc8569125934d28c454038dc0e7b55fbe23469f64f6015b5f13f6542f1eba73663f097542258db11481d582d90d53a11f25fc1fd97630b416bb1b21e6f0734c96b6b55ae668399bbec68a5c5cf4cd76fd518b8e69f4287b3f641b3823f0ce2d6fc174f43a1099d38706dd78e28a68449533f8846aa7df58e0a6f17c9275a2d7a6a286506d8f51db5457ba418f7f0a4934f1a6fec8a595bd4d6259701f7789bf9169941f9fa1161a467e1f8ea4c202c9c5c56cd43613b8da399ae723d9d97b110be905f0de4a4c22d699614aa763d34cac16a9cd45fe88523c30aa1e0fbe57c1770a726751e6926754b20621b59d003f3137fb194a316c48f2badcec35d7876cac51b7e9c94077a4418096010d855b699498315cac65a2424dbdc154c2edc8f520322bfa8c6812e5f137c5ac6789aa6a9649e47751a26577e23cffb5e851ce99f63b563f4608605f7b3395354a81298d03f06b53a6b446d32d91adae8b2b810511fa4a058976faf31237925bc6d1a9d6bdd196389d5d6b8597d800b312e590e89b8d2942fd9f774e1d38e8bce4c2f12dbca1df301882a0dd1e8c1b01eccdb2e55d569b90e087e28b551533b618277c8ddbb5ff7634c93459043b746cfb9ae080fe3c708d6c5a3a47c3fa5ac59a8639f3e3656bd489cb0cdd49332fe3b26caa264d39b8994806d8138868f9e81059ed653346e5b928f3848bb0fefd5d14078aa86eaba37a74dbe03fec5a01753c5866f638e7ff62f36e91cab65e56d9220e537fbc229c455c8647a0db41c3512a12514d142242b088e176e2514324f1c0a4fa5c1831fa88e00806585f00c87c06f8f00664d58cd4f96c349f3621ffbe5d1a62f77ff32bd4b27436f1e778ac6c5575e1925bd52f4a8577cecd51d5f7d48e67c5da2d439e2d3f31a9c6bbc90d29ae3379cfc52328130199fc50e35c0d0d766225898717c4603504bb7f38ce7fda12f32e44a8829d3cb6892a7f05ef112b8d9cb065110dd2cc0f6388de0a9fa3762c0b898e6ef90f22788733d8f780ac7c599b136fec85be87969ad384e49fc3a4b64da1eb776dcb5255f7b3e1f6b56aff29764327e2f4a0573666aa87bd383d674268027fc20cedcbda0f610149794a4ec6f1c0d8cbddeae183b84f4936608f961d35c66e83aa04bda861e199f5066bb392adcb1ef8f8217822574ec6fdf9a2c9aa4a1f073a876435170fd1ff3ef1890607ed8dd87df09af28be346b97f44b42ee8198bbae2b8cfedcb2355de0812bd76512661be20ce0d8de20029f1f3fc317d9367aa8a9d84a85f32a3615a438dc8ee8039468a7d099d77d36e74f6ffcb9fe4c30c9ec14e2198dbcb4b78417787e2262f44f6e624b5e8b9f8b76c312de932952c18cf6d4c0571c79cc728d3eb25843560f9293d890c32056fded6e1577141ad416aab69aacbae9cfbdfcbc8e3283acc32f95d2f51b69a41453391065019a8c3353d64d6b3c530c74327315421f016b75fe81b101d6a16efc3427c7fa68dec48c73697426b0b0547643fbdb791f2a086c7b168cc75da8b47d6fe8820793decd8d45a151e57469d0dfa78049a4a9e3be49b180cfed49c51b68547c60b6966b56e8c814d08b6460fc1d33f13d12eb2504972978c67e5a4fb335af076a67974ab344602198fbbefc0b379e1ee78e00cc7fad5d1432bfbd71d66140f9e97c37c3bd260c19e83a86610a51ab8f908cbbadc91fca9d8709aac0fa78dc3db2867a8a946b102f26190ded6a02065f96ac2aed80daca559ba7bc782e1227f004934f7a62cec6c0b7422ee3f71e724ce3563736571019000

            # The mdoc reader sends SessionData (seq=2) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'f5cca7ba0e53e1f0bd05c3d8f1e1b2377e428b7cb3eb536705b5ee7e9f953a5a98e8db6c12f6d4b06f1e289a4ce2684820ef8b3a7b33f6807a0e2adc68404df0e1906aef5f2e6201120da1d8060911d90c6a2af114137191d94b603b5f8bf07d2d14d30cd556783002e788fc5b41149709e08f15106909cdcd1b7d5028f2e59df004320ce7046a4a1316fd',
            #   "seq": 2
            # }
            C-APDU: 00c3000000009b538198a26464617461588bf5cca7ba0e53e1f0bd05c3d8f1e1b2377e428b7cb3eb536705b5ee7e9f953a5a98e8db6c12f6d4b06f1e289a4ce2684820ef8b3a7b33f6807a0e2adc68404df0e1906aef5f2e6201120da1d8060911d90c6a2af114137191d94b603b5f8bf07d2d14d30cd556783002e788fc5b41149709e08f15106909cdcd1b7d5028f2e59df004320ce7046a4a1316fd6373657102fffa
            # The mdoc responds with SessionData (seq=3) containing encrypted DeviceResponse.
            # {
            #   "data": h'a581ee765fbbee65c919a7e10ceb9a31eeab2c7990792d26dd3f61cd56651c8248b73fe8467bb3a004f8806650dff4b1c797df78e292478f217fbab713a4b7499f624ba93e8b765290ec4a7fc8ee59ac09c07f3e07c6457a0ce4cf18f21092def79202539716e6d1eaaaebb6366641aad323ae49ba8e22b2bc094c81a7e45528f243f0827249879a25598576729c2b405244f172c42f3dfddf489b9fbd2b4599a43693e661b52858f72230dac38c2f2d3be6567cfd2be5939231ffc8e7e7990d604373e89a1dc31e9dc7697f1e0b60f33c34deea50bafce502e8b448e493640a3235a0ad328ecb6842ab19fc046f4c2fb99a9402cad727023f40891e608dabe9db0b95bed03c46a9f903a91956e49ab1161330e18ec5a20f00cb927c7314c9e2591476c1f3f7db827ba37390a99f4ea7c84f7eea4daea3486c2ef81e0073bddac9b4d583daae28db1632e416e9dc2c472cfb66f295e09e74784a9cc89e90f1e29a96c4d2374da1a93bc545134cdc06c3217762643bad41f9be1b6e8a7c76e4514b3e799767cba5fd62ddebd7e5aebaf9e9e8df7858a9f1fc1821f35e797e45e5582c65d12f021f97a9220c7ce93ee8712acd7ecdfa65f6e42cd3833e657244e9d8760b972c8d4acab32bae1fb8abd100c4b375635c478166a24c1013274b4d2ff0ecb1db91433a87b9e29b05cfaef2c1bdb797acb5344006ecd796731ab05945139f0748a93e10e649c6de9d57f55d25428336361f556b9422c5aa255c19a331b3bf522612cd1d1c5baf4d9e14eb93c00c87ae04f1301f9d6af97f0c83d9361bea3302e9f3d5f3ff967ea803d6a4a90f43b603777d70322de6138c8c8f074f1b35b5a06eff011284c79d5cd5e89b8da68175c6f2a38d886f63fcfdcf18818e0b9319b223bc958eb308d02778613d4f430190fa4c950b542f8a6ac04e3974cfe321639197951e2f1bd28ea07761ae117cc8be75649a1feae123ca1684231155eb92801aeedd5062c4ecd99d1059469e3c859ef995629769d0183a768b3313012b71627d3310dafa6a943ed4fc99ae398557ce75b199a6b468847dc6941b5b5fbd72399a95272946d6d4c81edc5971da1b67d4734841a45cb55ec3d782592f905d35166816dc8e607714ff4bf6c989a4ca34519241e12a6e5c8fa8461277b19c5484f283073787b447cb792dee7d547ff00bdea29a98efc19ad5a5962907e72dc67b42d2f77b88452cbf1c5ae1f3be6266bc2d304b5feac1153f3715e31af2489d36eb96f8f121c34d8cc955a79eda22033b6732949b8ae11958a5c127f2ef453b518cd92000e17e4801f7b9658c615f38d6d36593cd7b1cbcbb0f237be7f9cff508f365d1b8bb5ee38b66a5ea885460a7d031ebf94ee703a73aebfce0156613552ee99a43234f89ebc47a47060f606fc895cce4593897b0b7f55185d8de5624fa377bd68ec9a173d2e56bcdd7eedb171ff8ce26925df46c5b5f80b59dc9be661274b8efb1811f8cec955adc546e09e06105f6e12a0c0133ebcb7f5141687f5d48e74f196e04d6dd5dc8443f8e914079121cd915d1231b7c875f6729936ecace0fd7bc330d8031b40f38d59c0ec75c64825ff978944cfe1f803f7d43bc060165e7c79c0051c326cd36305e4bc8e3f1ce91713d7d99ff6ce55827971b7d2e9234533a113e8f5a527008c85193f737f1343ae3ba697b73b1d6fbe274eb4a6d71906904dea8197925b11f70cf109cea0e825730f7c862a2aad55bd3a5e8a419881ef9b82064e8391cc1640976a6642a8d20f0ed4ce61533262a8077bc3da7ef652a86883fba37764694f657d0cc2f259c24f99325d841f520df85fae478f3aa6a64290ffe67f24884bd0915c1838f55c9f95e995d533b1a82bd714389ff756e67913598957ba81bc016956213e968ee4000e82b2a1d713872273ece0cc14b500232a4c3624aeaa514f873d9ef8e3927fdc054b6e46ffdd8228d99e24c7dcbd39e8a1c7fab8af0832c12f1833de3e93782305a5a7e6ea389cefa221446604c18ce5aff271aa8ebbcbc886cd94bd3371d11924d7d9207a5e217bb5ad55ca13db23b4f06de586c8b2f0ee661e07a2e097074c9239a5622306d108b5757370dafa950a16f3f7e30e7576288a3ee9437ef1366d985a5de9c41b830d0da2bc01f51f5749b3d231dfb0e7276376dbeba66608a2d26cc5bac0f4d361804cf39263ef357291354bccc2a8280b546cf89494a9757127dc9e21523d3d8c268b936a64ad8a3c72437d285b711fe46e1f5adc8afed3541d0736f988571114a5021e3bd2e6c8804f69483af2918def6151e1591f4157bd03f2f6eeeadce1bc4c1facac12e3b5045dccb95bfca99656f379debd478e6ef9aed1acf8d38361f022f824b4d7c3250211cbd4e0fa0472e6a2ecf0eb55333862239a165efc32a4bc88562734f305cef580ea935d8ede3ac9250d5182e9afe015c5cd9b2830bccbe02430922619cc68f9feba816e654f9df762f0dd9f79b45e7e9b45239d40c4ec1ce246d5cf0329feb71978694d3ec19429eb4f872eb0ecf55296b601d8a5732cacd8fd1a4204a89eb988adce8217f254f27693c88046ab2ccb425bc8060f7a9bc7151b876',
            #   "seq": 3
            # }
            R-APDU: 53820747a26464617461590739a581ee765fbbee65c919a7e10ceb9a31eeab2c7990792d26dd3f61cd56651c8248b73fe8467bb3a004f8806650dff4b1c797df78e292478f217fbab713a4b7499f624ba93e8b765290ec4a7fc8ee59ac09c07f3e07c6457a0ce4cf18f21092def79202539716e6d1eaaaebb6366641aad323ae49ba8e22b2bc094c81a7e45528f243f0827249879a25598576729c2b405244f172c42f3dfddf489b9fbd2b4599a43693e661b52858f72230dac38c2f2d3be6567cfd2be5939231ffc8e7e7990d604373e89a1dc31e9dc7697f1e0b60f33c34deea50bafce502e8b448e493640a3235a0ad328ecb6842ab19fc046f4c2fb99a9402cad727023f40891e608dabe9db0b95bed03c46a9f903a91956e49ab1161330e18ec5a20f00cb927c7314c9e2591476c1f3f7db827ba37390a99f4ea7c84f7eea4daea3486c2ef81e0073bddac9b4d583daae28db1632e416e9dc2c472cfb66f295e09e74784a9cc89e90f1e29a96c4d2374da1a93bc545134cdc06c3217762643bad41f9be1b6e8a7c76e4514b3e799767cba5fd62ddebd7e5aebaf9e9e8df7858a9f1fc1821f35e797e45e5582c65d12f021f97a9220c7ce93ee8712acd7ecdfa65f6e42cd3833e657244e9d8760b972c8d4acab32bae1fb8abd100c4b375635c478166a24c1013274b4d2ff0ecb1db91433a87b9e29b05cfaef2c1bdb797acb5344006ecd796731ab05945139f0748a93e10e649c6de9d57f55d25428336361f556b9422c5aa255c19a331b3bf522612cd1d1c5baf4d9e14eb93c00c87ae04f1301f9d6af97f0c83d9361bea3302e9f3d5f3ff967ea803d6a4a90f43b603777d70322de6138c8c8f074f1b35b5a06eff011284c79d5cd5e89b8da68175c6f2a38d886f63fcfdcf18818e0b9319b223bc958eb308d02778613d4f430190fa4c950b542f8a6ac04e3974cfe321639197951e2f1bd28ea07761ae117cc8be75649a1feae123ca1684231155eb92801aeedd5062c4ecd99d1059469e3c859ef995629769d0183a768b3313012b71627d3310dafa6a943ed4fc99ae398557ce75b199a6b468847dc6941b5b5fbd72399a95272946d6d4c81edc5971da1b67d4734841a45cb55ec3d782592f905d35166816dc8e607714ff4bf6c989a4ca34519241e12a6e5c8fa8461277b19c5484f283073787b447cb792dee7d547ff00bdea29a98efc19ad5a5962907e72dc67b42d2f77b88452cbf1c5ae1f3be6266bc2d304b5feac1153f3715e31af2489d36eb96f8f121c34d8cc955a79eda22033b6732949b8ae11958a5c127f2ef453b518cd92000e17e4801f7b9658c615f38d6d36593cd7b1cbcbb0f237be7f9cff508f365d1b8bb5ee38b66a5ea885460a7d031ebf94ee703a73aebfce0156613552ee99a43234f89ebc47a47060f606fc895cce4593897b0b7f55185d8de5624fa377bd68ec9a173d2e56bcdd7eedb171ff8ce26925df46c5b5f80b59dc9be661274b8efb1811f8cec955adc546e09e06105f6e12a0c0133ebcb7f5141687f5d48e74f196e04d6dd5dc8443f8e914079121cd915d1231b7c875f6729936ecace0fd7bc330d8031b40f38d59c0ec75c64825ff978944cfe1f803f7d43bc060165e7c79c0051c326cd36305e4bc8e3f1ce91713d7d99ff6ce55827971b7d2e9234533a113e8f5a527008c85193f737f1343ae3ba697b73b1d6fbe274eb4a6d71906904dea8197925b11f70cf109cea0e825730f7c862a2aad55bd3a5e8a419881ef9b82064e8391cc1640976a6642a8d20f0ed4ce61533262a8077bc3da7ef652a86883fba37764694f657d0cc2f259c24f99325d841f520df85fae478f3aa6a64290ffe67f24884bd0915c1838f55c9f95e995d533b1a82bd714389ff756e67913598957ba81bc016956213e968ee4000e82b2a1d713872273ece0cc14b500232a4c3624aeaa514f873d9ef8e3927fdc054b6e46ffdd8228d99e24c7dcbd39e8a1c7fab8af0832c12f1833de3e93782305a5a7e6ea389cefa221446604c18ce5aff271aa8ebbcbc886cd94bd3371d11924d7d9207a5e217bb5ad55ca13db23b4f06de586c8b2f0ee661e07a2e097074c9239a5622306d108b5757370dafa950a16f3f7e30e7576288a3ee9437ef1366d985a5de9c41b830d0da2bc01f51f5749b3d231dfb0e7276376dbeba66608a2d26cc5bac0f4d361804cf39263ef357291354bccc2a8280b546cf89494a9757127dc9e21523d3d8c268b936a64ad8a3c72437d285b711fe46e1f5adc8afed3541d0736f988571114a5021e3bd2e6c8804f69483af2918def6151e1591f4157bd03f2f6eeeadce1bc4c1facac12e3b5045dccb95bfca99656f379debd478e6ef9aed1acf8d38361f022f824b4d7c3250211cbd4e0fa0472e6a2ecf0eb55333862239a165efc32a4bc88562734f305cef580ea935d8ede3ac9250d5182e9afe015c5cd9b2830bccbe02430922619cc68f9feba816e654f9df762f0dd9f79b45e7e9b45239d40c4ec1ce246d5cf0329feb71978694d3ec19429eb4f872eb0ecf55296b601d8a5732cacd8fd1a4204a89eb988adce8217f254f27693c88046ab2ccb425bc8060f7a9bc7151b87663736571039000

            # The mdoc reader sends SessionData (seq=4, status=20) to terminate the session over NFC.
            # {
            #   "status": 20,
            #   "seq": 4
            # }
            C-APDU: 00c30000000010530ea266737461747573146373657104fffa
            # The mdoc responds with an empty DO'53' acknowledging session termination.
            R-APDU: 53009000
        """.trimIndent()
        assertEquals(expectedTranscript, tag.transcript.toString())
    }
}
