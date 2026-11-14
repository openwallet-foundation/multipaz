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
import org.multipaz.mdoc.connectionmethod.BlePeripheralAddress
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
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

        val readerTransport: MdocTransport = Endpoint(MdocRole.MDOC_READER, connectionMethod)
        val mdocTransport: MdocTransport = Endpoint(MdocRole.MDOC, connectionMethod)

        override fun createTransport(
            connectionMethod: MdocConnectionMethod,
            role: MdocRole,
            options: MdocTransportOptions
        ): MdocTransport = when (role) {
            MdocRole.MDOC_READER -> Endpoint(MdocRole.MDOC_READER, connectionMethod)
            MdocRole.MDOC -> Endpoint(MdocRole.MDOC, connectionMethod)
        }

        private inner class Endpoint(
            override val role: MdocRole,
            override val connectionMethod: MdocConnectionMethod,
        ) : MdocTransport() {
            private val _state = MutableStateFlow(State.IDLE)
            override val state: StateFlow<State> = _state.asStateFlow()
            private var wasOpened = false

            override val scanningTime: Duration? = null

            override suspend fun advertise() {
                if (_state.value == State.IDLE) {
                    _state.value = State.ADVERTISING
                }
            }

            override suspend fun open(eSenderKey: EcPublicKey) {
                wasOpened = true
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
                if (wasOpened) {
                    if (role == MdocRole.MDOC_READER) {
                        mdocToReaderChannel.close()
                    } else {
                        readerToMdocChannel.close()
                    }
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
        gattPeripheral = false,
        gattCentral = true,
        directL2capPeripheral = false,
        directL2capCentral = true,
        peripheralModeUuid = null,
        centralModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        directL2capPsm = 128,
        peripheralAddress = null,
    )

    private fun getSelectedDirectL2capCentralMethod(): MdocConnectionMethodBle = MdocConnectionMethodBle(
        gattPeripheral = false,
        gattCentral = false,
        directL2capPeripheral = false,
        directL2capCentral = true,
        peripheralModeUuid = null,
        centralModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        directL2capPsm = 128,
        peripheralAddress = null,
    )

    private fun getSelectedGattCentralMethod(): MdocConnectionMethodBle = MdocConnectionMethodBle(
        gattPeripheral = false,
        gattCentral = true,
        directL2capPeripheral = false,
        directL2capCentral = false,
        peripheralModeUuid = null,
        centralModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        directL2capPsm = null,
        peripheralAddress = null,
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

                val terminationMessage = readerEncryption.encryptMessage(
                    messagePlaintext = null,
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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
                assertEquals(
                    listOf(getSelectedDirectL2capCentralMethod(), getSelectedGattCentralMethod()),
                    methods
                )
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

                val terminationMessage = readerEncryption.encryptMessage(
                    messagePlaintext = null,
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800000
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
            #   "data": h'7064003c959268c485a3205732918b61725cfcc1e5f8ea567bf436627f5f62dcf0cd5232c3886b85a69ff12197d65a6cb4ccdc6c0e24e22d4bb085e927541309b6c73f2e77d429cb2d8265d3f04d35c359c818e0c6ff4a4c50914cbdde495f52cfab81ebe727fd1561377a3157c1ad02b8833778c00651fa9f6daae46337bf6de3db5f76714d5b',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158877064003c959268c485a3205732918b61725cfcc1e5f8ea567bf436627f5f62dcf0cd5232c3886b85a69ff12197d65a6cb4ccdc6c0e24e22d4bb085e927541309b6c73f2e77d429cb2d8265d3f04d35c359c818e0c6ff4a4c50914cbdde495f52cfab81ebe727fd1561377a3157c1ad02b8833778c00651fa9f6daae46337bf6de3db5f76714d5b6373657100fffa
            # The mdoc responds with SessionData (seq=1) containing encrypted DeviceResponse.
            # {
            #   "data": h'8e588d37f3d303d4e153a26d1d25b81e1206066b69f008122fbce3b0c91a97e937906edf1a7f1d60040748fd34a191763907d55fad3e49757063d71909bb58c9570bff79d02cdd5badd40efc2ece0e360fdd4456751e570502d73dbf974c9fbf46b28d617690b6277c2abaa52290805e31aa6ab149dd63ce24e1da48ade109c15811cf4fe0d20ca91c350192b8bba4bb27d816d984749b7db578fee98f4d59a691fa531ced02cd4c6f2930fdb2b2da6fb28ac46068e591de2da0ec5f84a1f64b9ced6bc4d96e12fda3593152d0a813ae6d980a75b4862e3208a35a2195def161303c77b970dcd3fad75c316d1f24094c29c8fd3c7a29768e9f7a12786e7cd229c91732d3259423be1d8e4a73727353d5ca532ae84db59522550392a974840a53b663bfe52343c3608508a5aee500a8d6b3d55439f2a3873d406ec076920e740a83a5c31bc90ba9f406811e95c438adea83c5a74f58e4f5d72b911a3a843daf8ceabd5c1724322bd60c2960cb5e3dbc30fb951a1eb6788a29982bed05054b41876c04c4410403724fa9c8be7818708419e2405f4818f71b5bcdee640b51c2abf25cb5790258b1c874030bf2596810509ecf569e8181ca282f7e61ef56a9f5fa0e4fd0ff75e20a4ff2f2cc39dc16f50908679b666d9f18c2d19c5f789f02fb0d2999a3e936c04ce0e8fbc70ff82500900b6e0325b5cee95d089744fe0510185002bb6d9d31fc2b31af707c8562d047204ac5151b848b13a6b8d514a24e1127f6208836b06d21e73c69a41d8c1482a7e922db8896692e937474e59e77b326a9a0d270b516b45538b5488797f04470ba3b7ca78e155df98871bd6369f1e0d402de16ae2e26ff816ab8a1aa39166abcee7563aaf40943d7e2b1b1b4693d86044ba2299710eb5de1dadefb4dc4b4f74d47b04c969faca912d682ccd417fa57fc72d5d5c3a3b638c91325a3391e9da969134507e2678cd6611e6ee261893ea93d27a661ede6e35fc16fc2cdd4dcceefe0fc6f43ca5980e164f579b81ac8817ec20707532236b69b9cf5060e089f934fe13be5573d68535cb824055f0514277214fb4165a468b6d9f93f9b873f5c745c66b8cb4f3cd651a0049860c7d4a85eb1ffcc89b63c793e468eeaa068c5b47977d5b1d915a9bfaaf04d0c8112920810ce9e66e42c35c1676efeb54c68df7a37d9fdfa541556fc2916ec0ea86fb965e7f5d1d069581c98223f8a8de658b65454a7d1dcfba3420997797373fd89b7f7cde5a48826863e047b25fab7a524d787bd129370255ffc9bb4738701167c50a07851e29d85ca9138716d72c4f089f02e1038c05386396f3b3ad92b22dd5c943773f0082d93c8226827489135531108b3d89fdd466b380e5352a73c2d7f4566277d362f18eef8a416bb6a32bab0870ab2af40754c56789420fa03cbcc38b5c3a7ea7562f36e49684680c2a459a2fe6dd842b0c21f3e2292527600aef2d3c8aa023096a13ea51b6b47d6a8bfd1b620c6cf1fca979ec26180a2d9d51ef7460c05a8d8def69776bf4d78b71d55a78eba56bf70faa0549cd2a30262d7d81ae0704c4c3b3b4acbac9875b4418969c646dff4499b77698474d350dace8e56163ff9208b6373296e2b47364251bb3b676aef3c8947bf984e16d02494fe7910f1af9d10e0a523bc1aba3d004b142f00bde2682dc27f4a5c30214abb830d03c64ec960dce453300ca09fd95b7e51e20e46922ecf8efaf24ad95b08d915ac29e6cd44371786139f59dc6de6abeb235d21438934ca7e0b8ade4912b5f3526d4f98cce824bd5c77b82a697ddb4e3e5d7230573625b61df53f71c6ee97761e304caf0d8b59f62e139794c8732bb75f4c88497746cb68ed8951d73b4045590c76c37e8c162c662add487c046066f0e4d162bf83dcfe73a4a35ea821cbcb8122cff2c04c02047f3b0493cba9a76b50c737c4fd60079635cab82e86169d38f7168146061534fec424bb8a82bebd08957725b6b343584867ab489642fcb491c5c828df527dfaca9f75f4e3d1f665336d6f48949c123573188ecd1998341a871372edfb65173321ee361d5c5605c0722b585e21377582ac69770b1a56f8fcd8ebfe82aec068767937f6ce81995e2bd2c02bae8d302ef43f97a84b06bcc739696ea515124a01ab69d1762a5d44481a5b51935206f575cf0672e98002c136e66e1b2c86155c4ae5b34e1ef8f9acba82b5dfc24873fad4a416534507569ccf91e5c0ce78900352e7addbd98abc10d9460421f50c6df4d62fe25ae74f58432ee976294bf8a214e3e314bb08754166f176086bd263d091636a3b6b1a3640412bf5764f5c9128d6555b9f0060f9a25910562204b95c4db43668f3634f96a5e8995671ef5134481e9cf57e3405cde57713d601e1ac68ae907ecf7ec8024457052908b6a21575b716a7bfcf5b13b0bf31ca68b07ddd0a390a811953549a66288a7cb499aac1982ab3f75ddb8ec3eadd4b083b18eae79d228b1294d86033aa4f9d2d2b84f6eb9e4320aa9ebd11744f32845d33f140a3449182c659f08675b7a7b78d99cb66c376bb3e28f553240e8828abe970cb3c8a970c8d0a6ca126d37facb77a50',
            #   "seq": 1
            # }
            R-APDU: 5382073da2646461746159072f8e588d37f3d303d4e153a26d1d25b81e1206066b69f008122fbce3b0c91a97e937906edf1a7f1d60040748fd34a191763907d55fad3e49757063d71909bb58c9570bff79d02cdd5badd40efc2ece0e360fdd4456751e570502d73dbf974c9fbf46b28d617690b6277c2abaa52290805e31aa6ab149dd63ce24e1da48ade109c15811cf4fe0d20ca91c350192b8bba4bb27d816d984749b7db578fee98f4d59a691fa531ced02cd4c6f2930fdb2b2da6fb28ac46068e591de2da0ec5f84a1f64b9ced6bc4d96e12fda3593152d0a813ae6d980a75b4862e3208a35a2195def161303c77b970dcd3fad75c316d1f24094c29c8fd3c7a29768e9f7a12786e7cd229c91732d3259423be1d8e4a73727353d5ca532ae84db59522550392a974840a53b663bfe52343c3608508a5aee500a8d6b3d55439f2a3873d406ec076920e740a83a5c31bc90ba9f406811e95c438adea83c5a74f58e4f5d72b911a3a843daf8ceabd5c1724322bd60c2960cb5e3dbc30fb951a1eb6788a29982bed05054b41876c04c4410403724fa9c8be7818708419e2405f4818f71b5bcdee640b51c2abf25cb5790258b1c874030bf2596810509ecf569e8181ca282f7e61ef56a9f5fa0e4fd0ff75e20a4ff2f2cc39dc16f50908679b666d9f18c2d19c5f789f02fb0d2999a3e936c04ce0e8fbc70ff82500900b6e0325b5cee95d089744fe0510185002bb6d9d31fc2b31af707c8562d047204ac5151b848b13a6b8d514a24e1127f6208836b06d21e73c69a41d8c1482a7e922db8896692e937474e59e77b326a9a0d270b516b45538b5488797f04470ba3b7ca78e155df98871bd6369f1e0d402de16ae2e26ff816ab8a1aa39166abcee7563aaf40943d7e2b1b1b4693d86044ba2299710eb5de1dadefb4dc4b4f74d47b04c969faca912d682ccd417fa57fc72d5d5c3a3b638c91325a3391e9da969134507e2678cd6611e6ee261893ea93d27a661ede6e35fc16fc2cdd4dcceefe0fc6f43ca5980e164f579b81ac8817ec20707532236b69b9cf5060e089f934fe13be5573d68535cb824055f0514277214fb4165a468b6d9f93f9b873f5c745c66b8cb4f3cd651a0049860c7d4a85eb1ffcc89b63c793e468eeaa068c5b47977d5b1d915a9bfaaf04d0c8112920810ce9e66e42c35c1676efeb54c68df7a37d9fdfa541556fc2916ec0ea86fb965e7f5d1d069581c98223f8a8de658b65454a7d1dcfba3420997797373fd89b7f7cde5a48826863e047b25fab7a524d787bd129370255ffc9bb4738701167c50a07851e29d85ca9138716d72c4f089f02e1038c05386396f3b3ad92b22dd5c943773f0082d93c8226827489135531108b3d89fdd466b380e5352a73c2d7f4566277d362f18eef8a416bb6a32bab0870ab2af40754c56789420fa03cbcc38b5c3a7ea7562f36e49684680c2a459a2fe6dd842b0c21f3e2292527600aef2d3c8aa023096a13ea51b6b47d6a8bfd1b620c6cf1fca979ec26180a2d9d51ef7460c05a8d8def69776bf4d78b71d55a78eba56bf70faa0549cd2a30262d7d81ae0704c4c3b3b4acbac9875b4418969c646dff4499b77698474d350dace8e56163ff9208b6373296e2b47364251bb3b676aef3c8947bf984e16d02494fe7910f1af9d10e0a523bc1aba3d004b142f00bde2682dc27f4a5c30214abb830d03c64ec960dce453300ca09fd95b7e51e20e46922ecf8efaf24ad95b08d915ac29e6cd44371786139f59dc6de6abeb235d21438934ca7e0b8ade4912b5f3526d4f98cce824bd5c77b82a697ddb4e3e5d7230573625b61df53f71c6ee97761e304caf0d8b59f62e139794c8732bb75f4c88497746cb68ed8951d73b4045590c76c37e8c162c662add487c046066f0e4d162bf83dcfe73a4a35ea821cbcb8122cff2c04c02047f3b0493cba9a76b50c737c4fd60079635cab82e86169d38f7168146061534fec424bb8a82bebd08957725b6b343584867ab489642fcb491c5c828df527dfaca9f75f4e3d1f665336d6f48949c123573188ecd1998341a871372edfb65173321ee361d5c5605c0722b585e21377582ac69770b1a56f8fcd8ebfe82aec068767937f6ce81995e2bd2c02bae8d302ef43f97a84b06bcc739696ea515124a01ab69d1762a5d44481a5b51935206f575cf0672e98002c136e66e1b2c86155c4ae5b34e1ef8f9acba82b5dfc24873fad4a416534507569ccf91e5c0ce78900352e7addbd98abc10d9460421f50c6df4d62fe25ae74f58432ee976294bf8a214e3e314bb08754166f176086bd263d091636a3b6b1a3640412bf5764f5c9128d6555b9f0060f9a25910562204b95c4db43668f3634f96a5e8995671ef5134481e9cf57e3405cde57713d601e1ac68ae907ecf7ec8024457052908b6a21575b716a7bfcf5b13b0bf31ca68b07ddd0a390a811953549a66288a7cb499aac1982ab3f75ddb8ec3eadd4b083b18eae79d228b1294d86033aa4f9d2d2b84f6eb9e4320aa9ebd11744f32845d33f140a3449182c659f08675b7a7b78d99cb66c376bb3e28f553240e8828abe970cb3c8a970c8d0a6ca126d37facb77a5063736571019000

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
        val bleResponseProcessedByReader = CompletableDeferred<Unit>()
        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper
        lateinit var tag: TranscriptRecordingNfcIsoTag

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
                    tag.closedDeferred.await()
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
                assertEquals(getSelectedDirectL2capCentralMethod(), connectionMethod)
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

        tag = TranscriptRecordingNfcIsoTag(
            engagementHelper = engagementHelper,
            transcript = transcriptLogger,
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

        val terminationMessage = readerEncryption.encryptMessage(
            messagePlaintext = null,
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800000
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
            #           1: false,
            #           2: false,
            #           3: true
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
            R-APDU: 5370a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a400f401f402f403f5058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100fffa

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100

            # The mdoc responds with SessionData (seq=1, disconnectNfc=true) containing encrypted DeviceResponse.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            R-APDU: 5382074ca3646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e063736571016d646973636f6e6e6563744e6663f59000

            # The mdoc sends SessionData (seq=1, disconnectNfc=true) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            BLE Mdoc->Reader: a3646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e063736571016d646973636f6e6e6563744e6663f5

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
                assertEquals(getSelectedDirectL2capCentralMethod(), connectionMethod)
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

        val terminationMessage = readerEncryption.encryptMessage(
            messagePlaintext = null,
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800000
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
            #           1: false,
            #           2: false,
            #           3: true
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
            R-APDU: 5370a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a400f401f402f403f5058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100

            # The mdoc sends SessionData (seq=1, disconnectNfc=true) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            BLE Mdoc->Reader: a3646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e063736571016d646973636f6e6e6563744e6663f5

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100fffa
            # The mdoc responds with SessionData (seq=1, disconnectNfc=true) containing encrypted DeviceResponse.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            R-APDU: 5382074ca3646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e063736571016d646973636f6e6e6563744e6663f59000

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
                assertEquals(getSelectedDirectL2capCentralMethod(), connectionMethod)
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

        val terminationMessage = readerEncryption.encryptMessage(
            messagePlaintext = null,
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800400
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
            #           1: false,
            #           2: false,
            #           3: true
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
            R-APDU: 5370a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a400f401f402f403f5058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac63736571000400

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100

            # The mdoc returns a response chunk (1024 bytes) with status 6100 (more data available).
            R-APDU: 5382074ca3646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df6100

            # The mdoc sends SessionData (seq=1, disconnectNfc=true) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1,
            #   "disconnectNfc": true
            # }
            BLE Mdoc->Reader: a3646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e063736571016d646973636f6e6e6563744e6663f5

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
                assertEquals(getSelectedDirectL2capCentralMethod(), connectionMethod)
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

        val terminationMessage = readerEncryption.encryptMessage(
            messagePlaintext = null,
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800000
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
            #           1: false,
            #           2: false,
            #           3: true
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
            R-APDU: 5370a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a400f401f402f403f5058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100fffa
            # The mdoc responds with SessionData (seq=1) containing encrypted DeviceResponse.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1
            # }
            R-APDU: 5382073da2646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e063736571019000

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e06373657101

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
                assertEquals(getSelectedDirectL2capCentralMethod(), connectionMethod)
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

        val terminationMessage = readerEncryption.encryptMessage(
            messagePlaintext = null,
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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

        assertEquals(true, readerTransport.stats.nfcDisconnectedDuringTransaction)
        assertEquals(true, mdocTransport.stats.nfcDisconnectedDuringTransaction)
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800000
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
            #           1: false,
            #           2: false,
            #           3: true
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
            R-APDU: 5370a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a400f401f402f403f5058006a203f504f59000

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over NFC.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            C-APDU: 00c30000000097538194a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100fffa

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e06373657101

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
                assertEquals(getSelectedDirectL2capCentralMethod(), connectionMethod)
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

        val terminationMessage = readerEncryption.encryptMessage(
            messagePlaintext = null,
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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

        assertEquals(true, readerTransport.stats.nfcDisconnectedDuringTransaction)
        assertEquals(true, mdocTransport.stats.nfcDisconnectedDuringTransaction)
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800000
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
            #           1: false,
            #           2: false,
            #           3: true
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
            R-APDU: 5370a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a400f401f402f403f5058006a203f504f59000

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'8f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a2646461746158878f1d1ddff861dcdf4370205a348f29ddafc8a224b3461d64f0c624dabf309988763ec24c8dcc6c6ea7987539e68a48824c8b479c4bedffcd3d2e6821b36f87e0ec94f371da0636544fabc4be849e91d13051f9c22f2ed12ec3c723af8fe39635edb6c5423c11dd5220521154132d3d6b8ff8dafb1fe77560d9521da22e32d689c3493ca2ec38ac6373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'b479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e0',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072fb479ca5bdad8236d26b73020d3b16885f25d5dd0d6389c36cd22ba144d4edd5935d61757688e99f9a3a6b8c7a8f5230d9276f660b39a561ebe41c9fc2616bf4911207f1ecce368a61ddf62b44c71257cd3b14a2657d0b57299de9e950b6d3109965c9073d99434d18e97a209f2aea4882c606d6139f313f34a6dc606ee97ad2e4b9bfc61dec2c375472541375ca50c51f0c305d02a14e35e0c5e5c6124a736e298f4597d96bdb2cda8fe0383167fad3e857789b0867a5ab84d3cd0a2ac12ee937b2550416ae647b1b28db9f1ea553f544184c30821ad1c7deb6ef46fb9ec7ccb8d6fa0313b3b952950d8d48f041b2d08e460c2708cdf0075befbaa6bf51026f8568db9ffa0753c0a86ff4df62314b91db5f6e76162fda3cb79a676e8d9b15e8d96a1ed40c8f2fc7c86ef8debf6e93714e9879a811aa57042fecd7e4e9acb6afdea5611bcaed2962ca39a841c26c47b7c6c852694895b27f4457c8c6de41dd6505390b8532885a1884e6bb9c420b99476baa26752e193d16cc4791565639c3c7d3badd980da8cee89e15e9054bca4574e867e449979ed727ca024f36efe637d52fade5efcd0985ff9232e33db62f13982496ef481f0817c2e9d0edfd17280ecfb4c35998a55a41363ff937b7cb018b5663119f39f27935a00f6f1004564edc5c20aae608353828a81645999c43bdd67076dc1e19f0112f56a679c0c44a05b058c6a42ec097f9267dec70d1130ec3be8eb981c9ee691ae695e4c9808ba092e371e39c071913212e7a1c23e2ad01b13bbb09398ca32df18e2b74c13910907f80d2d79c6d846416e29cbd414fb18551d3440cf261dfdb5bb8e82896fc29eb7761d0ff119dde4eb6b87894b70ecfa7610035602a081fe7a66846f1915092aa4275a4ef8f41785dd396d8d9161443d05d2f3c835bc9d6d128b0948504b5a01f5dd0ab7334816ae243338ce5a32947193a2f932011ac6c386465e2c391ac75fb72048122dcf482d02f898cc06bb06f5feb8ab9d183e23fe32db64bc0a87ce0322314eae7431a123c0f3df56ef6b486a508533a2ff4810c77db39f6ce95092ed79d94619fbb4c35cf16580a15d5faa5c19a572d9940a70846e45d4242ebc91b4c9bc98d2c5cfd46c4a80b9fff4f5c2d60d849c3efe18aa6fa73f9039acdf820ec835ccabcd68eb61a7a8039a5cf4a8e05472b9b6b87213800a73b2d0f72ff07016ec542804563a932af704636d6b54d61ed709d4ad0770d754129abdf858334e313f236b7e73e1f6791a478364aa8fdfd0c5be2290c1e04e22c2480b469d7477e35896e1e74bca5c10fee09615c965a846e37528e6e0616d5fb5a521b24472f37b104c779c6a79c921895c1228ccb7e68833e5cb824355b8de3cbd2438d3261f538a3156340d3ad014a7422111f7c0a34e5539d4a747df22220db412f19348552ad6fced1a33ef6bcce11957eaf457f981114c5ebb714eb9178595ec81391b06a74755e7ff519f84944aeccfc6448c66a9e8b95129c5cd3a1d753deb3aa9f38cca0648e8e7a42af83359f98f36a5f253d4aa76b89ee7f662103b4d31991ac4b24b391815954c4eafbb1a44517317d856d45c47fd8661814417a70c23229203874f90e4cd12dcd24221b7c112eb5c8bc4a782e9a44b050fd5b94c33dae1027d679ffa5c9a1e77d52ff2b09433cfe79e88b986817ca77de115c4cd4ac2df2ec96c2694087aafb8d8acc7fdb4953f7f654ec4c1bec9d5c5e1d2a3d5090a64114151118effb7a9705a103e8b8952a5d4d1eee2d2a055d0ac73ec352c60e505865b90cf842d792966414015d2eae4888aff4c11bd8801b6feb05ff54f87dcb90037b95801d320ab89d28ceddc1b1e673ea6995a1e4b01960faad55660f19d8a56584fb54cfc038bafe980cc8232661dc6690888b0b220e726ea2380882ef185ba6d2c313283eed6127e37864e4eb5d2744c0f9a18e0e485b729f8a776f2a9dc3dad87fdb83e383437176466e3880639cdff4d0e3dd984b3312d7d79ed01f1b21e06a7ef12e5a166f29076cab1ffbb017281688041cdae19b50b8663dc6a4b55eeafaf593693eb793f823249845c710e5a5f01daf070269e4a926b1a2c8cb64600b5e8bb61630db33dfafd2ac01935f429025d27607c3811149570d574127a005649134934c06d2e8ef0b917bc86f059ae547e0fb46fe452580b2dc2f36e12f7724be8da6efd88709cf09f7c8968dda161bf6ba9286668d7ed1f25a9899abc9d51e3be05a364dd850cf91e031ad580c365f1824675120caccab12a2bfe8d272c323fb3fd4abaf724dd8964a9d652c4b7ac4fe45c606487d979369eb64e79d0f4b6650e62299769e9a1636d8bd2cf759a06093aae25f65eb77427b398e1a3d4e04f50623b55ca8bb5a271176985194375802077d1445be770780be945dbaa794ccb4045382f2d87652c848fe30ee5c8b0e699e43bd6bcc24ff16b2042ee151b7cf6aa758f6c52a8059bc8057694d7b25221d83de535f935c95a79d6e4868cec0dfa451891d4081b39e08f07391cdbc0e096858d47859f192099dbfcd5b826ff008f207ed489427d3718ec207e1e26167f1237bca3e3e06373657101

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

                    val terminationMessage = readerEncryption.encryptMessage(
                        messagePlaintext = null,
                        statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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

                val terminationMessage = readerEncryption.encryptMessage(
                    messagePlaintext = null,
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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

                val terminationMessage = readerEncryption.encryptMessage(
                    messagePlaintext = null,
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
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

    private suspend fun runGoldenCceBleScenario(
        readerOfferedMethods: List<MdocConnectionMethod>,
        mdocHandoverPicker: suspend (List<MdocConnectionMethod>) -> MdocConnectionMethod?,
        expectedSelectedMethod: MdocConnectionMethodBle,
    ): String = withContext(Dispatchers.Default) {
        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val presentmentSource = createAnnexDPresentmentSource()
        val transcriptLogger = TranscriptLogger()

        val bleOpenGate = CompletableDeferred<Unit>()
        val bleTerminationReceivedByMdoc = CompletableDeferred<Unit>()
        val presentmentReady = CompletableDeferred<Unit>()
        val presentmentDone = CompletableDeferred<Unit>()

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        lateinit var engagementHelper: MdocNfcCceEngagementHelper
        lateinit var blePair: LoopbackBleTransportPair
        lateinit var tag: TranscriptRecordingNfcIsoTag

        var isNfcFieldActive = true

        blePair = LoopbackBleTransportPair(
            connectionMethod = expectedSelectedMethod,
            transcript = transcriptLogger,
            onBeforeReaderOpen = {
                withTimeout(5.seconds) {
                    bleOpenGate.await()
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
                assertEquals(expectedSelectedMethod, connectionMethod)
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
            handoverPicker = mdocHandoverPicker,
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
                negotiatedHandoverConnectionMethods = readerOfferedMethods,
                context = Dispatchers.Default,
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
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = eDeviceKey.publicKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = true,
        ).apply {
            setSendSessionEstablishment(false)
        }

        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
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

        withTimeout(5.seconds) {
            tag.closedDeferred.await()
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

        val terminationMessage = readerEncryption.encryptMessage(
            messagePlaintext = null,
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
        )
        readerTransport.sendMessage(terminationMessage)

        withTimeout(5.seconds) {
            presentmentDone.await()
            bleTerminationReceivedByMdoc.await()
        }
        readerTransport.close()
        mdocTransport.close()

        transcriptLogger.toString()
    }

    /**
     * Golden `T-CCE-2`: Reader offers `GattCentral` only (`{0: false, 1: true, 11: h'00000001...'}`);
     * mdoc selects `GattCentral` and emits `{0: false, 1: true}` (omitting key `11` per Clause 11.1.2).
     */
    @Test
    fun testGoldenCceGattCentral() = runTest {
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
        )
        val transcript = runGoldenCceBleScenario(
            readerOfferedMethods = listOf(readerOffer),
            mdocHandoverPicker = { methods -> methods.single() },
            expectedSelectedMethod = getSelectedGattCentralMethod(),
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
            #           11: h'00000001a12348ce896b4c76973373e6'
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c300000000785376a100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a300f401f50b5000000001a12348ce896b4c76973373e60000
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
            #           1: true
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
            R-APDU: 536ca100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a200f401f5058006a203f504f59000

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'ffbac15ffacaabc3b0292fb2e57d8df1ba085ca288954515364a581f5f585abdeda50d848b824565c39c970abdf9920f9bd5b4ef33add7c50b44d7f4dadeaf17cca155d9337c0ec6051c012763bcbee2ba5879cde7631f95de1eeaf67d176a5c25b490d8a8d479a629718a33d271133d9a42df7a21041363567866e87ba9549a0fb7e6fa43f859',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887ffbac15ffacaabc3b0292fb2e57d8df1ba085ca288954515364a581f5f585abdeda50d848b824565c39c970abdf9920f9bd5b4ef33add7c50b44d7f4dadeaf17cca155d9337c0ec6051c012763bcbee2ba5879cde7631f95de1eeaf67d176a5c25b490d8a8d479a629718a33d271133d9a42df7a21041363567866e87ba9549a0fb7e6fa43f8596373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'b58b87681fad701effa219b74b69ee40fe7cee68622fded6532b1b228665fadc1ffb02afe4151600de3ec55eb5b5faa0d27605d877695e13ade3500cb3b6e4f18c2d6f6c9498ab71e11575e8819e4aee35561c10ca90e5a39940a9c0129749251f8c126aca95cd9bd5c4e7cd78bfb6e9d16ec4d6396e2a3515aa71c7754c7d4d8f297a6b64366789979a9e9d3ca1f644f0efc10c7cf669991729605d4c725bb6226c70327d7d59390d65bc9976c349c5400930fb0b9ded8dae763f8b08772b08b7230ba001cf0b46a34f4e0e142b10fb002f7c0a1223738df1ee2287b48846b9436d56cf593672ba16a6fb68be8ca27c8bcea5b717c232900455d10f8e5b1866e6ea2cb94faa1452b1a114683eeae2a541606bf97dc7ed6b34f0f7643414b4d4fc00154ff8d2b3ae563769057b5931bc38921fdf1d7de09fa04fef819dc6d81f9d76618b5415864ec52ead9571238aab70b974a18746a83f1a298ea7c2dd8f7bb27aea95ad82fb01c7a908050fb28ae98d5276d4093e70c9aa10ae46bcb5a7336c09402d33a9c228ba51e010a15997e0496f28679a77d4aa1d3f6b448016ae819945e5656e98be06bdd3547dbc825db59670f6ea2f169f2fbb2d0e38e32cfdfd8a72790fb04dcc83de5159814ac85518d90048231465b0d7ed1730f27465aa56d2cf00fe86b4f495d75b0545085ca421295a333ae5e295e3af184a9dfaeabbf2b1f2b34d1d98dbf4bb17a04a0b815fdb71d0fd057ab3c21d8441e5b94147ca5f7c76d0075ddfd298fd5b62091749e48c16d7f1659c977af35031d6304e942b8e76026013bd47766e3905a201cf2d94a70397670bc2cdc485fea9735d04d7d00752537930d69c4931f3e0d3eafb85c12b2d962b008596a04984a64ccbd0d934cc070d004ce549758e89eead48bb373a1e4fb47e63b08248a47f5dc13d399daa177ee5e581f9c37683b8eb9806221fd5219e18559c5785560137c8d1cc29ef77b5b80830e4ef6493fb22dff75f6ef088d1215a73a3f1d26749915af3accc7eb39e753d083359f14287fe4cc60d25aacf11211ba709c99ba20ac3e2c9ff658f82d0fa200f694f3a99c2d8daf2f7ff872226b16df1f7c1a997f4472b14edc47016bb44e4e5141b15964c8d97608e705511b84c53f5d96b0e5971f6d71a8db5be3832a22970b738b0289fe94516b0d4246689456156517933afa18e9e4ee8631464ece2effe01938aa43cc78715861bf1d7ad4472635c5d47ab30551875c915492d28916722a7e129400301f7cd7e2b9ac2845b61045a75d3c8913899979fac947b5c6b12c28804e578be236846f15a20cc6d758c359b353dadaa08d4735dad130e61687cdc6d3dcf88c19872056f7a5219f57e02b4459cd7e2d1961c20ebbc5167403aa2ae3527389b32ac183972a49896da7d67a35cb645ef14493d17cfc67a8a6758afe7ea16b7f90c38bc62ad160fcca50e9c6f7af476ed1dc9dd3be2270906a4f33d036abd6d70d212228cae89a1336029448fd731c45c31a3c13d8a7b4ad61f28b37f456b3bab0f18708d8daba145d433c37ebc590a66a73b02118726ebf13329ed44caf83b755f1b1bef61f1c86fb7cb39a818987ea6844bd40a59acc348b86b320198d2c0936f4622375cbb2bf32b2060e733571ed85158b577b4b9bdb98ce695b61219ac30f7ac1b5dbc6d10a3ac51540ba7d0696ff343a5a48a7c2db7f73f0b33d7202ff5ce890b5566e573204f57493246a3d6ff6abfc89754652a907826f21f5d20002c38e37bd6a7a848a49d934e622a0b4f980852d18f5abeb9505de4bb931ebe45c71a7934228b75783ef9cfb875c98f38468a25d89ced65d83a57b7d278a7d566f6834a7243190575cc3efcb4aa5f1bb12593dfb339626098d43c8f5500914182d2e8b8621faeadda6b418d41bbbc480b75fc06774ee7c4e20ec592c009cdb16c900b8e88fcd1d04e0567f124aa7596c76115b9fdea14ac7177e08ff848f37685638205a5ca1ae74eb0b532e8881123e7aa793bd88e88f2103f558de37d731041150a3c61310a21cb23861cebcd92c52dec9d6898e8b6ead553da8dc40a15732e2bfec345b1b60bb845380377cf31c8362429b8732b3573ba49dfeaabc02b52ca2671d95bfd5496b1ca96695b9329edbffee4ae3653d6fec68c88ddb59906470dd9cbb8cb5661353a651032ddc251d63d65935aa5baef5a7cef14ef228b7e1ed2ad838983080565dff4427126ec92b0a926d8202bbda84caabfd717ae599332c8a9d5784e8bff2b4337706e889df2045e1463b9066ef8347aa34a4cf2e3861e6f4fbc59dc029ada58dbf98c6a413e1687282f1a35174a3095bfaa22f052f1559daf955934517476fe2528b081103b8486cd3535dd55cc2a2ccc70a0180de0827b3c517410d375b9decee2d4cfd4884aa436bf11e72b49a6fd724a0f73c9dd902825317a669095b400786e6be98bc30a1a8d7fdf9ae497b50a051fdd6776ea73675ac74f380f876a64843376a40bfc9d9ebe33a2af87070cdedf91d68fa2c10127821b085aa418b5f01ddd7aa6adad3b7c2ba4f60774f04662b2a70c93993ba62a9950cb66dc6c9fa9c0',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072fb58b87681fad701effa219b74b69ee40fe7cee68622fded6532b1b228665fadc1ffb02afe4151600de3ec55eb5b5faa0d27605d877695e13ade3500cb3b6e4f18c2d6f6c9498ab71e11575e8819e4aee35561c10ca90e5a39940a9c0129749251f8c126aca95cd9bd5c4e7cd78bfb6e9d16ec4d6396e2a3515aa71c7754c7d4d8f297a6b64366789979a9e9d3ca1f644f0efc10c7cf669991729605d4c725bb6226c70327d7d59390d65bc9976c349c5400930fb0b9ded8dae763f8b08772b08b7230ba001cf0b46a34f4e0e142b10fb002f7c0a1223738df1ee2287b48846b9436d56cf593672ba16a6fb68be8ca27c8bcea5b717c232900455d10f8e5b1866e6ea2cb94faa1452b1a114683eeae2a541606bf97dc7ed6b34f0f7643414b4d4fc00154ff8d2b3ae563769057b5931bc38921fdf1d7de09fa04fef819dc6d81f9d76618b5415864ec52ead9571238aab70b974a18746a83f1a298ea7c2dd8f7bb27aea95ad82fb01c7a908050fb28ae98d5276d4093e70c9aa10ae46bcb5a7336c09402d33a9c228ba51e010a15997e0496f28679a77d4aa1d3f6b448016ae819945e5656e98be06bdd3547dbc825db59670f6ea2f169f2fbb2d0e38e32cfdfd8a72790fb04dcc83de5159814ac85518d90048231465b0d7ed1730f27465aa56d2cf00fe86b4f495d75b0545085ca421295a333ae5e295e3af184a9dfaeabbf2b1f2b34d1d98dbf4bb17a04a0b815fdb71d0fd057ab3c21d8441e5b94147ca5f7c76d0075ddfd298fd5b62091749e48c16d7f1659c977af35031d6304e942b8e76026013bd47766e3905a201cf2d94a70397670bc2cdc485fea9735d04d7d00752537930d69c4931f3e0d3eafb85c12b2d962b008596a04984a64ccbd0d934cc070d004ce549758e89eead48bb373a1e4fb47e63b08248a47f5dc13d399daa177ee5e581f9c37683b8eb9806221fd5219e18559c5785560137c8d1cc29ef77b5b80830e4ef6493fb22dff75f6ef088d1215a73a3f1d26749915af3accc7eb39e753d083359f14287fe4cc60d25aacf11211ba709c99ba20ac3e2c9ff658f82d0fa200f694f3a99c2d8daf2f7ff872226b16df1f7c1a997f4472b14edc47016bb44e4e5141b15964c8d97608e705511b84c53f5d96b0e5971f6d71a8db5be3832a22970b738b0289fe94516b0d4246689456156517933afa18e9e4ee8631464ece2effe01938aa43cc78715861bf1d7ad4472635c5d47ab30551875c915492d28916722a7e129400301f7cd7e2b9ac2845b61045a75d3c8913899979fac947b5c6b12c28804e578be236846f15a20cc6d758c359b353dadaa08d4735dad130e61687cdc6d3dcf88c19872056f7a5219f57e02b4459cd7e2d1961c20ebbc5167403aa2ae3527389b32ac183972a49896da7d67a35cb645ef14493d17cfc67a8a6758afe7ea16b7f90c38bc62ad160fcca50e9c6f7af476ed1dc9dd3be2270906a4f33d036abd6d70d212228cae89a1336029448fd731c45c31a3c13d8a7b4ad61f28b37f456b3bab0f18708d8daba145d433c37ebc590a66a73b02118726ebf13329ed44caf83b755f1b1bef61f1c86fb7cb39a818987ea6844bd40a59acc348b86b320198d2c0936f4622375cbb2bf32b2060e733571ed85158b577b4b9bdb98ce695b61219ac30f7ac1b5dbc6d10a3ac51540ba7d0696ff343a5a48a7c2db7f73f0b33d7202ff5ce890b5566e573204f57493246a3d6ff6abfc89754652a907826f21f5d20002c38e37bd6a7a848a49d934e622a0b4f980852d18f5abeb9505de4bb931ebe45c71a7934228b75783ef9cfb875c98f38468a25d89ced65d83a57b7d278a7d566f6834a7243190575cc3efcb4aa5f1bb12593dfb339626098d43c8f5500914182d2e8b8621faeadda6b418d41bbbc480b75fc06774ee7c4e20ec592c009cdb16c900b8e88fcd1d04e0567f124aa7596c76115b9fdea14ac7177e08ff848f37685638205a5ca1ae74eb0b532e8881123e7aa793bd88e88f2103f558de37d731041150a3c61310a21cb23861cebcd92c52dec9d6898e8b6ead553da8dc40a15732e2bfec345b1b60bb845380377cf31c8362429b8732b3573ba49dfeaabc02b52ca2671d95bfd5496b1ca96695b9329edbffee4ae3653d6fec68c88ddb59906470dd9cbb8cb5661353a651032ddc251d63d65935aa5baef5a7cef14ef228b7e1ed2ad838983080565dff4427126ec92b0a926d8202bbda84caabfd717ae599332c8a9d5784e8bff2b4337706e889df2045e1463b9066ef8347aa34a4cf2e3861e6f4fbc59dc029ada58dbf98c6a413e1687282f1a35174a3095bfaa22f052f1559daf955934517476fe2528b081103b8486cd3535dd55cc2a2ccc70a0180de0827b3c517410d375b9decee2d4cfd4884aa436bf11e72b49a6fd724a0f73c9dd902825317a669095b400786e6be98bc30a1a8d7fdf9ae497b50a051fdd6776ea73675ac74f380f876a64843376a40bfc9d9ebe33a2af87070cdedf91d68fa2c10127821b085aa418b5f01ddd7aa6adad3b7c2ba4f60774f04662b2a70c93993ba62a9950cb66dc6c9fa9c06373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CCE-4`: Reader offers `GattCentral` + `DirectL2capCentral`; mdoc selects `GattCentral`
     * and emits `{0: false, 1: true}` (keys `2`, `3`, `11`, `21` absent per Clause 11.1.2).
     */
    @Test
    fun testGoldenCceSelectGattCentralFromDualCentralOffer() = runTest {
        val transcript = runGoldenCceBleScenario(
            readerOfferedMethods = listOf(getStaticBleConnectionMethod()),
            mdocHandoverPicker = { methods ->
                assertEquals(2, methods.size)
                methods.filterIsInstance<MdocConnectionMethodBle>().first { it.gattCentral }
            },
            expectedSelectedMethod = getSelectedGattCentralMethod(),
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
            #           2: false,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000007f537da100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e61518800000
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
            #           1: true
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
            R-APDU: 536ca100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a200f401f5058006a203f504f59000

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'75eb6dd5c4b69c598cbfbcb53f9e5f9865c023195717f9a885d7f9e49a4a63aea855b904b02a3ab7b71ddb5e02ca18bc59328c8a020a30cb7f502dcb17766261a2e97ec4212b6ef110ad4e389048b900f7e2211cf96c3f627f20c63fa3d8a28a53b542e2c842381542f55c7287b868e4a92cd7ad3e4d64db015ece5f81fd983ac9d848ac75e9d6',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a26464617461588775eb6dd5c4b69c598cbfbcb53f9e5f9865c023195717f9a885d7f9e49a4a63aea855b904b02a3ab7b71ddb5e02ca18bc59328c8a020a30cb7f502dcb17766261a2e97ec4212b6ef110ad4e389048b900f7e2211cf96c3f627f20c63fa3d8a28a53b542e2c842381542f55c7287b868e4a92cd7ad3e4d64db015ece5f81fd983ac9d848ac75e9d66373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'd7f2a1c5a507f26210b8b6a98590131b3d4855e9c9f1465a650fef620abf9d1e940124770c7057875e19cbad63051bed0a5f32bc253adf7f0c03929ddeba00374b10ba7d7711031531c5fb18480ca287c3cbceb87741736c5ec3293144599c0c400793ae992b8a1527e5b5502e3984e8f1dcf58add371f4fd4f1ec58d05b335e420725430da24e5d4090f077d5b1632fa517e982f5b1ce51121a56f170955e86343dd6befb7c648facbbd51a5ddf39dc99b604607c80b89172c1c3412198b00eff8b047ef827cf33c33cc7237d7df93e0f0ee4b01a8966243aeb3c030b73b0579f8db7c9036af35c39c7e83a0ed3f03dbea2b528fa5e55b00bcd823bd0f4c65aafa5d792614e6d2594714b1b8491c41159ded04a9afc46b65bde8e65d880fa6b143d0333570d56e164cf2fc5dd51849735ee8470cab3716bfa9a04c0823537b3531369b3bbf5828e260b53d434b81974ff3eab704ff2df43961d77e68a34aa78086799fe4088923cb91070e7b4487d0c65a6defc22635c4e53a9a9d6f173a4ff944e8632f8b1331c5f66c2e0f6b73cfc8bd41ef95c60f9836aadc60bffb66eb5630ce6857702b7fa9b44061f1667b655a7710385c0310f66abb007e7f924d3e6dce7303e51e4271c0b3a7e765c9ef692d5b3d4235e401e2900c6b520586c2f78e91eeae0002a1aa095aea421ce9a8b6be9a38a5c8d68afe71d6be630bf2899d34f7f9f4c5ffcb9266b68f0ff2cb371fad5cbb69eefd3b4cfcc035abe46ca41a95330c56a05cef082ee5b61acb6733599fa75f42fb5654ad71096ca0c4d702b0de684405702a99e60053e2655ab064d0cd774fa9da04cfc236d49ed58d87be35e9162780b7c6e0453fd9602272cb51afba7178a24be1b7bd23d4f79e0d50ca28171a07aa9687453d7e550a17d059ec684ff67f88c1785ccd3a728318f6aa4a03c1cfbc54736befb37360469c930d06dbc62385ecfa9fe658fd19ed4943106031edf53a2be1f7b09ce0c423cbea065418b61fcdd8c9d0d643721b707e4e2dba28cedb19c90503b1f1297fdb36c06bee0f13361e37457a73d18240768d11864c44f0ee039a92020dd693216ec1cc373fc46c7beec110aa3481fff37ad3e06a68c7693e4204f7339c330004f8bb8ffa50e26a1344c217fdd3a320b62a2c89a6fcdd1d10f042108b61437886575c5e05da6bad5ff1deb85c6640fe638d1dcc593fd8d4a4b7d6d8b25f65b2831c8057328db6959907cf571e1f6fe3bdb70b0e990853c531697f7e5ae30103d1fd84581e616ce704d29433503c2151a0539d3154b082a816c357fe40ef265d6e14a516e390732c49deaa1b0f12ce0e6afa9d1213d4f02688ca1797dbee059880622e61a760f49ca87ad5ec084eabb0379f73340959a553ef8aab977995516d9a6885461e7f63a0f14235ba7f38cf4614713a3b2052422303a502d744457d39a38bba1a597b5467ee36179b4c13f738cb0c641b3dc4a2dcaf4365cbefb9117317465df16a493f31dc80ee55b830149b80eceb2442adfe5f80e6f7e9d3d767e259589be216081b87ad2af8408572849dacd0c340df18341949ad9337871378c343be3ef88997b5364606dccff2b82245d2dae1a74b2bf78650437f600ea6a09d1d448d8cf261604c3ca4cff9dc1e6df731293a2044eb6fb1bc4737e49e9c7f91c9edf4792d9c7ed0c62dcb0bffd153cd7e37c9d217546a12ecd29bb7ff8f7812476f71918dc8d617fa37ee9b383f3e257acd364ff531d8cbcbe62c8a339aaa7ce39dc0c7c6ddc96b0ac13409a493520b3e5944bcd4176c11268cc4035474f9e2814874473b7c5e21c3ab3359263b7d73ac5abc64c0e251ca5fa2ac1d25f58ed0cc569eeecdbf223626558caa0cefb4e14616af43633e6622fda11123eb572afadcaa97c866b32c2338c0137caccb75f7297498126c4b255e5eaadc33d4ef18794a37d5cecf0829db3bb0ece615a4f7bc72e80d69c2e406a58f9a86075865fab3a22fc1b9600d45bd9a23b7b65545b2af4515e02d9aa624f184792f634a7883b04f2a9a9994a9bd75777ab57f9313abfc4b6030149d3c9ad089cdf639067d568cfc4137b30cb03c184528195a3ed6310cb4150c73b171b01058fea291a09598fc2b5e8d571b128208f67aaf57f48054942cf0482232d8700a75eb4ad1e5c34feabd496b546e2db2867f4905ac181453b161ebc52b3847714958048e286d0e24641c0ebbd2580db5c0d1d6f9bf0c031df6dec020b546deb40e4df6f48b01f4357a8c0e4a866764d73638475b685a2eb39742c05c41528a66192242c8015058b6b7fc203bcfde831d94674b1fda7be3ce472c2d63b12f3c98edd9103d463c90ada57d914c9d0d56e8ea374e836edd7a62f1242322046fef6a1b10f277c7e51123b1d8b797138526e183c835de2e2d3c38a7ac0be3fb3f16577ce02824abe97fe355a33d283063ede45f3fe6953ba9c00d99a23083c7b9cb8fc25917f855bb67ebb31e0b0394f25297511cb4d63c51ead503cd3f6cf1d8090dd5352e74269fd6a41c70063b1e133910a9a35ebbbef1d947158f5df5f6d9903e24555ee755adce9a3b3ce600275ae03',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072fd7f2a1c5a507f26210b8b6a98590131b3d4855e9c9f1465a650fef620abf9d1e940124770c7057875e19cbad63051bed0a5f32bc253adf7f0c03929ddeba00374b10ba7d7711031531c5fb18480ca287c3cbceb87741736c5ec3293144599c0c400793ae992b8a1527e5b5502e3984e8f1dcf58add371f4fd4f1ec58d05b335e420725430da24e5d4090f077d5b1632fa517e982f5b1ce51121a56f170955e86343dd6befb7c648facbbd51a5ddf39dc99b604607c80b89172c1c3412198b00eff8b047ef827cf33c33cc7237d7df93e0f0ee4b01a8966243aeb3c030b73b0579f8db7c9036af35c39c7e83a0ed3f03dbea2b528fa5e55b00bcd823bd0f4c65aafa5d792614e6d2594714b1b8491c41159ded04a9afc46b65bde8e65d880fa6b143d0333570d56e164cf2fc5dd51849735ee8470cab3716bfa9a04c0823537b3531369b3bbf5828e260b53d434b81974ff3eab704ff2df43961d77e68a34aa78086799fe4088923cb91070e7b4487d0c65a6defc22635c4e53a9a9d6f173a4ff944e8632f8b1331c5f66c2e0f6b73cfc8bd41ef95c60f9836aadc60bffb66eb5630ce6857702b7fa9b44061f1667b655a7710385c0310f66abb007e7f924d3e6dce7303e51e4271c0b3a7e765c9ef692d5b3d4235e401e2900c6b520586c2f78e91eeae0002a1aa095aea421ce9a8b6be9a38a5c8d68afe71d6be630bf2899d34f7f9f4c5ffcb9266b68f0ff2cb371fad5cbb69eefd3b4cfcc035abe46ca41a95330c56a05cef082ee5b61acb6733599fa75f42fb5654ad71096ca0c4d702b0de684405702a99e60053e2655ab064d0cd774fa9da04cfc236d49ed58d87be35e9162780b7c6e0453fd9602272cb51afba7178a24be1b7bd23d4f79e0d50ca28171a07aa9687453d7e550a17d059ec684ff67f88c1785ccd3a728318f6aa4a03c1cfbc54736befb37360469c930d06dbc62385ecfa9fe658fd19ed4943106031edf53a2be1f7b09ce0c423cbea065418b61fcdd8c9d0d643721b707e4e2dba28cedb19c90503b1f1297fdb36c06bee0f13361e37457a73d18240768d11864c44f0ee039a92020dd693216ec1cc373fc46c7beec110aa3481fff37ad3e06a68c7693e4204f7339c330004f8bb8ffa50e26a1344c217fdd3a320b62a2c89a6fcdd1d10f042108b61437886575c5e05da6bad5ff1deb85c6640fe638d1dcc593fd8d4a4b7d6d8b25f65b2831c8057328db6959907cf571e1f6fe3bdb70b0e990853c531697f7e5ae30103d1fd84581e616ce704d29433503c2151a0539d3154b082a816c357fe40ef265d6e14a516e390732c49deaa1b0f12ce0e6afa9d1213d4f02688ca1797dbee059880622e61a760f49ca87ad5ec084eabb0379f73340959a553ef8aab977995516d9a6885461e7f63a0f14235ba7f38cf4614713a3b2052422303a502d744457d39a38bba1a597b5467ee36179b4c13f738cb0c641b3dc4a2dcaf4365cbefb9117317465df16a493f31dc80ee55b830149b80eceb2442adfe5f80e6f7e9d3d767e259589be216081b87ad2af8408572849dacd0c340df18341949ad9337871378c343be3ef88997b5364606dccff2b82245d2dae1a74b2bf78650437f600ea6a09d1d448d8cf261604c3ca4cff9dc1e6df731293a2044eb6fb1bc4737e49e9c7f91c9edf4792d9c7ed0c62dcb0bffd153cd7e37c9d217546a12ecd29bb7ff8f7812476f71918dc8d617fa37ee9b383f3e257acd364ff531d8cbcbe62c8a339aaa7ce39dc0c7c6ddc96b0ac13409a493520b3e5944bcd4176c11268cc4035474f9e2814874473b7c5e21c3ab3359263b7d73ac5abc64c0e251ca5fa2ac1d25f58ed0cc569eeecdbf223626558caa0cefb4e14616af43633e6622fda11123eb572afadcaa97c866b32c2338c0137caccb75f7297498126c4b255e5eaadc33d4ef18794a37d5cecf0829db3bb0ece615a4f7bc72e80d69c2e406a58f9a86075865fab3a22fc1b9600d45bd9a23b7b65545b2af4515e02d9aa624f184792f634a7883b04f2a9a9994a9bd75777ab57f9313abfc4b6030149d3c9ad089cdf639067d568cfc4137b30cb03c184528195a3ed6310cb4150c73b171b01058fea291a09598fc2b5e8d571b128208f67aaf57f48054942cf0482232d8700a75eb4ad1e5c34feabd496b546e2db2867f4905ac181453b161ebc52b3847714958048e286d0e24641c0ebbd2580db5c0d1d6f9bf0c031df6dec020b546deb40e4df6f48b01f4357a8c0e4a866764d73638475b685a2eb39742c05c41528a66192242c8015058b6b7fc203bcfde831d94674b1fda7be3ce472c2d63b12f3c98edd9103d463c90ada57d914c9d0d56e8ea374e836edd7a62f1242322046fef6a1b10f277c7e51123b1d8b797138526e183c835de2e2d3c38a7ac0be3fb3f16577ce02824abe97fe355a33d283063ede45f3fe6953ba9c00d99a23083c7b9cb8fc25917f855bb67ebb31e0b0394f25297511cb4d63c51ead503cd3f6cf1d8090dd5352e74269fd6a41c70063b1e133910a9a35ebbbef1d947158f5df5f6d9903e24555ee755adce9a3b3ce600275ae036373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CCE-5`: Reader offers `GattPeripheral` (`{0: true, 1: false}`, no key `10`);
     * mdoc selects `GattPeripheral` and generates `10: PeripheralModeUuid` (`{0: true, 1: false, 10: ...}`).
     */
    @Test
    fun testGoldenCceGattPeripheral() = runTest {
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            peripheralModeUuid = null,
            centralModeUuid = null,
        )
        val expectedSelected = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = null,
        )
        val transcript = runGoldenCceBleScenario(
            readerOfferedMethods = listOf(readerOffer),
            mdocHandoverPicker = { methods ->
                val chosen = methods.single() as MdocConnectionMethodBle
                assertNull(chosen.peripheralModeUuid)
                chosen.copy(peripheralModeUuid = peripheralUuid)
            },
            expectedSelectedMethod = expectedSelected,
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
            #           0: true,
            #           1: false
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c300000000665364a100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a200f501f40000
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
            #           0: true,
            #           1: false,
            #           10: h'00000002a12348ce896b4c76973373e6'
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
            R-APDU: 537ea100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a300f501f40a5000000002a12348ce896b4c76973373e6058006a203f504f59000

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'9595fc65ed75c1f2d1f3ae669228e2057311ef4edba0c301fbac41d70130d9d0896991c7bfbdfe4e596c30258e287a1b059f332736ac6135bfc62a5fb881c0b63c77f419d16add484d7468626eff5996272d8488ce21c2e431a3211164d0fcdd38d1544c7aecb8f3b0235be25611fd7bac2f6a48ecf4dacb44ee7262a6eb128c0d7b143b6f082d',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a2646461746158879595fc65ed75c1f2d1f3ae669228e2057311ef4edba0c301fbac41d70130d9d0896991c7bfbdfe4e596c30258e287a1b059f332736ac6135bfc62a5fb881c0b63c77f419d16add484d7468626eff5996272d8488ce21c2e431a3211164d0fcdd38d1544c7aecb8f3b0235be25611fd7bac2f6a48ecf4dacb44ee7262a6eb128c0d7b143b6f082d6373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'c8ca6fa07ae6a8f5e4b319146ea29ac8d370e77dd114cdbdb9dacf1d243159e17d3a6d3fdc005d2271b0130521cb500c0a9c29a075a69418ede6b6b3b710ed0930211be3bdce33fdf479fe0f285a5ba1a698511ee6173fb8a67640a22577259ebf09f7afea740a6360a2de8d56fa4c28799dda54520dd4b26403e88b560d57a42c23124793deb8e6123ced870c8ee35089a21285f800f8c4b3e87bf41fdca93cec8002a99932f6f9da44a22ebca3e146dedc49ef702ed72d436816a69cec287a78426dfd1443dc19d690b11026f8bec1e53a44000125974c8fc05882c163dfee8c4bd1ed4248b055929acffc40e105173a2708c883c61ac9635bf69aa794d76a2f31d45bb89c2a37488831f011ad8ccd528ef4ce945b92e3968d1fa13f60526f87c2052df6b57f9d37aa375b32588436d934c4df37a8382273c56e561874bfb073438c9d311651c02929cf741a0bc8e55451909d1976b90add763a36d18504f64c443554a5bbdbf7bad5c6a2d56a7126e0c87dd3b4a5df9b96fdca4bbc14806b152603a7224357fd0d83d28f9e962f41ddf5c87fedb909ff5eb8b65509f408f3fa3c1c38ee24928f7a51420c054a06f6ff93eefbf2032103bc6085971717ddfdd9d84233b6c04418c8f9a2dd5fca08a46f954ceed1bffbd5c3ad91625ee3e6616f1d5dfa314e9a646aecba509e9121272d0a42d64cceede447944f87fc659910a3ef5925d2ec71e6f53c021b0efdc11c7350c90138d8c66a3cdf884e2c4b6449a4a15790910b89ae41f47be3643b2c9d9f9abdab40b60625d9ec4e6bf02949a260933e82cccc5b4d78d04e28934b59d4099bd869cde042b72a50a5860016cf05edf65646decbe6997e885d4062cfda2bdc31bf4a48274a1a8425fd82463b8f250e8a240d8341f05bee7d1dc4404f469983098ce659b4424c6f961081a769ac0051afff7a559a34d014ac3c3d2fafcdf6efc47b19affed46398bd10e4f40ea1cb45e06f84e051d94432b5677e60c47b937e094d0dde854922c38af596274f159afdb4fc2d8ceeb51f4b8bd015490a757c9dbdf0d9176ccb5d4bd3fe3bcee0a963ad631bac7301689bb22903797f349289b2f65a9434bc3bc572a4c0a2f4be3cdd6ded0c24b10e8d1be821502d385c51b62756455a098e3db9dc67be01e9a438abebf9ee4d5bddd588688573281748a31736a6de5806eae4cfbe75d5216e37188cc61983381ea589d0da33f8df9732a3a14c7055484bca1fa239a2dca00d85b04de1121c3f3a2558d2021185c1f33fe5ad46c51c83c6fb994e739e1102b59d983ebf8f86bc242dcd14e0ee98b6a6d7b4850dd77c88f5e09d4b0d195f4a08f9efbda1d556676e29166d8306ec6e17d1df883cf5f6aeeafcfa3532dc3066007cbd0d7e4bcfe13272170af6df01556669b6dd2a5cba405b564ca16ece1db6ca8f28b69cf725d6754d1a785a08f903bd18bc45ff735337c71b64e2d547884b71a9f4704243bc47531c71425f404e2f10432c4815be0c1103ee5cbfdce80451bb5656eed4b7931f58ac085024018504547074765255f8c2acf988787f979ca19883707d728ce8910bf61a76bf701821955c495e438eccbafb296af02a2efe71103a18f8e7877a2e73456ee826aa0969fb235a91705b46ebd61176e58d516a637458a5d01d7add0be0c4ea21068d6519d6a1b350a17b54ff869287096b813e61cd7ef79896a9e32f0d8835a27b9e93669a71851dc294fd1fbdad518281e4c1eb31f7e0cffaf7e319acc4a7e2ac2769308bf454ece23411c1b7d7932a25170c5f174f78489d4d3f34a243109e84d92f4962a102ea467048441306f298c3b9221a3518eed370716a63bd01044b3fcd420828446c4872e30f0d07e89b65358456f5e49303d7b80ad193f66aa01158748465c7e4dd0bc86d95a4143fdba4a8f296dbb6d97c811a55ea5e6e6232f4de770bcfa9a33e2f38391cae18555267769237293868ce6d792e0d217f3fbeaa5c6782d060b43170eddc8948ceb462e60528ddb56a5e70c0d3939507be45a8de3970f81cc900dd1c94f640f7f38e10ef848d84c98bd90091a21c3a17398022133a39fada72831a98a4aae23cc3c4a76c53f0b64146b64704d6fe4bd620682a9d5ef15e742addce9874c0369edf68faf6289c18d06a63bee58c1c4f383778414b520642431961ce0c5a5eb92630911d2c5f435ee1ada107c16a8f1d59b9e39fe0a9406d848236d3c30fcd8ba9f1c66685a223e52334f6752cdd7a15f87d7e4a8cc4f5ef0e59b58e99f423cf59bce84e16ca088c8ac60ef378bb63355a52a111777da532b60adf46deb96f66181dd1073e07b88703cd704963858e1c0ac76420ca688807439b940d7fc31caa6ad2d31feeb0c344ac71fe69ae052ecba9181b694be43d1e1b733074b03191db95a7fdb158d516c36ae6034ab14f28968d4f743d09a8e8cc1d279c5f9c40547785d09d0db1cd67a079a722ab6a3c9f3875051467432a80addc99e7364f438401d86d5265db0b87a651618172141885b4538e1a191ab08ecb19d8df651c1b405f77cfbb0571e4f5d907df4d9ff9ed5a94c0218bfea8dfd9f870366182',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072fc8ca6fa07ae6a8f5e4b319146ea29ac8d370e77dd114cdbdb9dacf1d243159e17d3a6d3fdc005d2271b0130521cb500c0a9c29a075a69418ede6b6b3b710ed0930211be3bdce33fdf479fe0f285a5ba1a698511ee6173fb8a67640a22577259ebf09f7afea740a6360a2de8d56fa4c28799dda54520dd4b26403e88b560d57a42c23124793deb8e6123ced870c8ee35089a21285f800f8c4b3e87bf41fdca93cec8002a99932f6f9da44a22ebca3e146dedc49ef702ed72d436816a69cec287a78426dfd1443dc19d690b11026f8bec1e53a44000125974c8fc05882c163dfee8c4bd1ed4248b055929acffc40e105173a2708c883c61ac9635bf69aa794d76a2f31d45bb89c2a37488831f011ad8ccd528ef4ce945b92e3968d1fa13f60526f87c2052df6b57f9d37aa375b32588436d934c4df37a8382273c56e561874bfb073438c9d311651c02929cf741a0bc8e55451909d1976b90add763a36d18504f64c443554a5bbdbf7bad5c6a2d56a7126e0c87dd3b4a5df9b96fdca4bbc14806b152603a7224357fd0d83d28f9e962f41ddf5c87fedb909ff5eb8b65509f408f3fa3c1c38ee24928f7a51420c054a06f6ff93eefbf2032103bc6085971717ddfdd9d84233b6c04418c8f9a2dd5fca08a46f954ceed1bffbd5c3ad91625ee3e6616f1d5dfa314e9a646aecba509e9121272d0a42d64cceede447944f87fc659910a3ef5925d2ec71e6f53c021b0efdc11c7350c90138d8c66a3cdf884e2c4b6449a4a15790910b89ae41f47be3643b2c9d9f9abdab40b60625d9ec4e6bf02949a260933e82cccc5b4d78d04e28934b59d4099bd869cde042b72a50a5860016cf05edf65646decbe6997e885d4062cfda2bdc31bf4a48274a1a8425fd82463b8f250e8a240d8341f05bee7d1dc4404f469983098ce659b4424c6f961081a769ac0051afff7a559a34d014ac3c3d2fafcdf6efc47b19affed46398bd10e4f40ea1cb45e06f84e051d94432b5677e60c47b937e094d0dde854922c38af596274f159afdb4fc2d8ceeb51f4b8bd015490a757c9dbdf0d9176ccb5d4bd3fe3bcee0a963ad631bac7301689bb22903797f349289b2f65a9434bc3bc572a4c0a2f4be3cdd6ded0c24b10e8d1be821502d385c51b62756455a098e3db9dc67be01e9a438abebf9ee4d5bddd588688573281748a31736a6de5806eae4cfbe75d5216e37188cc61983381ea589d0da33f8df9732a3a14c7055484bca1fa239a2dca00d85b04de1121c3f3a2558d2021185c1f33fe5ad46c51c83c6fb994e739e1102b59d983ebf8f86bc242dcd14e0ee98b6a6d7b4850dd77c88f5e09d4b0d195f4a08f9efbda1d556676e29166d8306ec6e17d1df883cf5f6aeeafcfa3532dc3066007cbd0d7e4bcfe13272170af6df01556669b6dd2a5cba405b564ca16ece1db6ca8f28b69cf725d6754d1a785a08f903bd18bc45ff735337c71b64e2d547884b71a9f4704243bc47531c71425f404e2f10432c4815be0c1103ee5cbfdce80451bb5656eed4b7931f58ac085024018504547074765255f8c2acf988787f979ca19883707d728ce8910bf61a76bf701821955c495e438eccbafb296af02a2efe71103a18f8e7877a2e73456ee826aa0969fb235a91705b46ebd61176e58d516a637458a5d01d7add0be0c4ea21068d6519d6a1b350a17b54ff869287096b813e61cd7ef79896a9e32f0d8835a27b9e93669a71851dc294fd1fbdad518281e4c1eb31f7e0cffaf7e319acc4a7e2ac2769308bf454ece23411c1b7d7932a25170c5f174f78489d4d3f34a243109e84d92f4962a102ea467048441306f298c3b9221a3518eed370716a63bd01044b3fcd420828446c4872e30f0d07e89b65358456f5e49303d7b80ad193f66aa01158748465c7e4dd0bc86d95a4143fdba4a8f296dbb6d97c811a55ea5e6e6232f4de770bcfa9a33e2f38391cae18555267769237293868ce6d792e0d217f3fbeaa5c6782d060b43170eddc8948ceb462e60528ddb56a5e70c0d3939507be45a8de3970f81cc900dd1c94f640f7f38e10ef848d84c98bd90091a21c3a17398022133a39fada72831a98a4aae23cc3c4a76c53f0b64146b64704d6fe4bd620682a9d5ef15e742addce9874c0369edf68faf6289c18d06a63bee58c1c4f383778414b520642431961ce0c5a5eb92630911d2c5f435ee1ada107c16a8f1d59b9e39fe0a9406d848236d3c30fcd8ba9f1c66685a223e52334f6752cdd7a15f87d7e4a8cc4f5ef0e59b58e99f423cf59bce84e16ca088c8ac60ef378bb63355a52a111777da532b60adf46deb96f66181dd1073e07b88703cd704963858e1c0ac76420ca688807439b940d7fc31caa6ad2d31feeb0c344ac71fe69ae052ecba9181b694be43d1e1b733074b03191db95a7fdb158d516c36ae6034ab14f28968d4f743d09a8e8cc1d279c5f9c40547785d09d0db1cd67a079a722ab6a3c9f3875051467432a80addc99e7364f438401d86d5265db0b87a651618172141885b4538e1a191ab08ecb19d8df651c1b405f77cfbb0571e4f5d907df4d9ff9ed5a94c0218bfea8dfd9f8703661826373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CCE-6`: Reader offers `GattPeripheral` + `DirectL2capPeripheral` (`{0: true, 1: false, 2: true, 3: false}`);
     * mdoc pre-advertises L2CAP server on PSM `129` with `peripheralModeUuid` and emits
     * `{0: false, 1: false, 2: true, 3: false, 10: h'00000002...', 21: 129}`.
     */
    @Test
    fun testGoldenCceDirectL2capPeripheral() = runTest {
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = null,
            centralModeUuid = null,
            directL2capPsm = null,
        )
        val expectedSelected = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = null,
            directL2capPsm = 129,
        )
        val transcript = runGoldenCceBleScenario(
            readerOfferedMethods = listOf(readerOffer),
            mdocHandoverPicker = { methods ->
                assertEquals(2, methods.size)
                val chosen = methods.first() as MdocConnectionMethodBle
                assertTrue(chosen.directL2capPeripheral)
                assertNull(chosen.peripheralModeUuid)
                assertNull(chosen.directL2capPsm)
                chosen.copy(
                    peripheralModeUuid = peripheralUuid,
                    directL2capPsm = 129,
                )
            },
            expectedSelectedMethod = expectedSelected,
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
            #           0: true,
            #           1: false,
            #           2: true,
            #           3: false
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c3000000006a5368a100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a400f501f402f503f40000
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
            #           1: false,
            #           2: true,
            #           3: false,
            #           10: h'00000002a12348ce896b4c76973373e6',
            #           21: 129
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
            R-APDU: 538185a100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f401f402f503f40a5000000002a12348ce896b4c76973373e6151881058006a203f504f59000

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'd66769e9aab19c381697e48b8033368db7311d42b9c92be6b0dc404ce3cbe164f3d433260adc3476e9d4710828b91805ab1665ff234ee2ef495d56477f87586beace0849a2929638aded3626fd457b2a27144d31c747e1a90b67977c21e4d7a6bab1ae1363f76eba8f84209ab016b499b0cdd4d78eccfe0ebbee31ad04c8892d9cbcdbc572d284',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887d66769e9aab19c381697e48b8033368db7311d42b9c92be6b0dc404ce3cbe164f3d433260adc3476e9d4710828b91805ab1665ff234ee2ef495d56477f87586beace0849a2929638aded3626fd457b2a27144d31c747e1a90b67977c21e4d7a6bab1ae1363f76eba8f84209ab016b499b0cdd4d78eccfe0ebbee31ad04c8892d9cbcdbc572d2846373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'50586aeafb839c30164f5551b271e30858ef15678648a6a059d6cbde3e7bb6f8d3f7c743bad1e8e448438e81a927b4992434ee629c3651ae69dc5b94f1d7117eb102089b54c6facb50bc841a941d872d3eded20f5b67967404e2abfd8b1f8724e9c8a1690d5fc16a141bbc38d97c52dac37cbdd630657ee33c416f7668a6c3ec63429e430f9779e1b604aa0f5460bfff2af66be0bb845eb05d181ad68d015424df720552dacf9bba260d6c94bdc57ea06404ef09f3fce8dd00d3a0f3ae587f190e884059f7d56ff23d19f2bcdec22a69e6c6d937d2299139163e9a4d5c80d44cfff7ae8cdb66714d03530f6b20dfc84ecd68af3e04326d2147bbdb7222eb6162c67e7527593794112420440ab6f1fe342339c1ed26c083c0971d92b897e5e117e53e88887e5063717e28f274b83bc17b2a88035d61597a20ca332559358c91c312d94720fa331d00afdfec33d788882da8bb01c1894fa99bb84f6ff014ea6848ce51df1286b4d897ab19f674ded2743a46d4066aee420e9f9150a603074462a0ad57b8e1544051f81e08a9490575c3c067ab3fff9c81dd128c0c27335023267a84909408d2c96a1ed9812c1881b501c81c8df935836935486cacb6c2eb3b6b8db2bdaea31c85949eb6a9f91b38c86a530fe6757599a055ebb0ed413c1a5e0827d2feacbabff781279aff32fe8a01df2fd56d381062aa207732249571a29ed7de8d5733f6d9f8dd5e34bb98a2b92ebdea85e4df9ed3cd742ee49e98db6c5e84ae2d831051ad95be34094c9a609a182b5e35fcdef8d0a1722d8677755200ed9f983342dd49f4de7ae9dada0099eba7b87932f87401a276ab9d3e90c13a06f995e735b034038d5cccefb5a2ebf76a4100910100127c5f0e29da0d4bd9aa3a50c6534e24e0fb6d0cde159a688cfea174efef8aa6f73b69319e6560bdae656a1e50b53658550c5f6ad3e3244e3acb27fb9fb4ef140cc1f4df882b7e42335209a141de15780b8d30140adc5e01424782c7538b6a2a7b6e57898c6e775270616f5ddf2baef7608b04fa68b85eb55a33880f340d5811577eb9ed3010373a7c2874d850c4043b22d80c72b20598210e8b41db998e14cf01ed5b1eef412497fd09fca82812b2c9d6ae2e026770553d278f947eec746152616fad69728aed889f182fbb9d1ea62c0e796851ac8432313d85f6690c8211e7dfa767251112c2424d844a9a28a1e3345645d8640b989fa12441a0ae84af68c62ba4aeb6950d4ae34cfdcde2f40ba7a797e3e10713fb9d90b30cee00a9b9ec6811b40304a591ad54a9461707fbb049af447d65c1ea3ccf84feddae860bfb1b690ea3cc682b066c894c0dca3e48e7a48b6c850ec792264467d376e6cf725b662e81dd6432a116c7e8f679a07954af9a76ac791209bf2f5c7e4d407b83909ed32c34f99ea74fcb80486970e7bc29ed914d27743f5bfa65a59160826d2d8286e8b8dd5be320edccc03dcf3afddc47d92eae7b22b9b912540329694b00ffda3bbf74b81edd97079d63b6f01e9a3e4a92f7318f3d737409578c1f487b025ce786cf21c280fa25148c0962964a19836f055ccccbdece1fe0d5ee98a989841ab4d50f93854b525e28572bdd7f032860285729115addeb1b4a6f0971c31fefa7f8a64ca29f0e04336931d802c4535ebda6ec4a74362e480a0827fd3901da732cedf4a749b50cff4bc8af717976273b83ad94f4670066d7c542b34d11844b4bca59fc0bacd49527d9db3c151c8e9c58dc8806f676805fa498755d8b64798b97666fba7024f9cecd497cbda1c2bf33e6c72e0a75617affd9ba3bb8315b8327080c2463ec42fbbcf5ddec99dc7c238d03482b0702192a48fa64961a09ba7f313259a3aff8dc9908103054a69600ac03adf7d2d2bfa6b7945e7fce47c7ce27ba716c84495c9b4c094883ed607ce5538ba272e5bb84929e963f0009743d724d8b45f6a04035cae921ee74e7fac9e1c34e28283e51f4a70d9c8ce9de99a46e2ae4685b5dd2bb298bd0acfb5cf2c0be3ac5b8bc6d4079f6ccd7a52c2e302725116983b91cb3ff605557b904a3e58e4af6ed4bc63fa1fe4422195d238b82ce7968c5d3ee2623f1af2420ead1f80aaf6fedfc6307c78a4466dbfc583c8b6daa0aabfb1651a5f05714c564add6d0a87543c9dd14ef76b4a21cd7874255534b9a985930cc7b7a8f2625178be83f65506b32cf941c36a9068c3c45b9e18af54060f94dbed4e755f73d639e903e10fea72555945e2e3189d5dbc2ad767c3dc8a32f15468c9fefb9a391f724b86678fa3b8990a5df1cfbc4d06b6738b84f343b3418395530cb716517dc9e508d777a38183bda3d2c36451f9efb6165d7fce1a1765a061d4f70b5fadef62aff64582b2a801449eb89714c01739d59bf9a670619394bd6ceb9b0764d4885e54663f8decdd9688b4d6f017266d94d4906c54f5ebdfa7befc156c40ec23bb7c96ef0c9349ef7a61d78489959b69a5b8dd5ddfca2e3050f2a39a071cda2620040d6afac73d7864bed3a53ce8fa413dbbce4c9394f1a1deb65eb509b2eb3eab61b066be42f85fbd561c8964e951639b8aaa593c2e4e691761b8e4b6f0030',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072f50586aeafb839c30164f5551b271e30858ef15678648a6a059d6cbde3e7bb6f8d3f7c743bad1e8e448438e81a927b4992434ee629c3651ae69dc5b94f1d7117eb102089b54c6facb50bc841a941d872d3eded20f5b67967404e2abfd8b1f8724e9c8a1690d5fc16a141bbc38d97c52dac37cbdd630657ee33c416f7668a6c3ec63429e430f9779e1b604aa0f5460bfff2af66be0bb845eb05d181ad68d015424df720552dacf9bba260d6c94bdc57ea06404ef09f3fce8dd00d3a0f3ae587f190e884059f7d56ff23d19f2bcdec22a69e6c6d937d2299139163e9a4d5c80d44cfff7ae8cdb66714d03530f6b20dfc84ecd68af3e04326d2147bbdb7222eb6162c67e7527593794112420440ab6f1fe342339c1ed26c083c0971d92b897e5e117e53e88887e5063717e28f274b83bc17b2a88035d61597a20ca332559358c91c312d94720fa331d00afdfec33d788882da8bb01c1894fa99bb84f6ff014ea6848ce51df1286b4d897ab19f674ded2743a46d4066aee420e9f9150a603074462a0ad57b8e1544051f81e08a9490575c3c067ab3fff9c81dd128c0c27335023267a84909408d2c96a1ed9812c1881b501c81c8df935836935486cacb6c2eb3b6b8db2bdaea31c85949eb6a9f91b38c86a530fe6757599a055ebb0ed413c1a5e0827d2feacbabff781279aff32fe8a01df2fd56d381062aa207732249571a29ed7de8d5733f6d9f8dd5e34bb98a2b92ebdea85e4df9ed3cd742ee49e98db6c5e84ae2d831051ad95be34094c9a609a182b5e35fcdef8d0a1722d8677755200ed9f983342dd49f4de7ae9dada0099eba7b87932f87401a276ab9d3e90c13a06f995e735b034038d5cccefb5a2ebf76a4100910100127c5f0e29da0d4bd9aa3a50c6534e24e0fb6d0cde159a688cfea174efef8aa6f73b69319e6560bdae656a1e50b53658550c5f6ad3e3244e3acb27fb9fb4ef140cc1f4df882b7e42335209a141de15780b8d30140adc5e01424782c7538b6a2a7b6e57898c6e775270616f5ddf2baef7608b04fa68b85eb55a33880f340d5811577eb9ed3010373a7c2874d850c4043b22d80c72b20598210e8b41db998e14cf01ed5b1eef412497fd09fca82812b2c9d6ae2e026770553d278f947eec746152616fad69728aed889f182fbb9d1ea62c0e796851ac8432313d85f6690c8211e7dfa767251112c2424d844a9a28a1e3345645d8640b989fa12441a0ae84af68c62ba4aeb6950d4ae34cfdcde2f40ba7a797e3e10713fb9d90b30cee00a9b9ec6811b40304a591ad54a9461707fbb049af447d65c1ea3ccf84feddae860bfb1b690ea3cc682b066c894c0dca3e48e7a48b6c850ec792264467d376e6cf725b662e81dd6432a116c7e8f679a07954af9a76ac791209bf2f5c7e4d407b83909ed32c34f99ea74fcb80486970e7bc29ed914d27743f5bfa65a59160826d2d8286e8b8dd5be320edccc03dcf3afddc47d92eae7b22b9b912540329694b00ffda3bbf74b81edd97079d63b6f01e9a3e4a92f7318f3d737409578c1f487b025ce786cf21c280fa25148c0962964a19836f055ccccbdece1fe0d5ee98a989841ab4d50f93854b525e28572bdd7f032860285729115addeb1b4a6f0971c31fefa7f8a64ca29f0e04336931d802c4535ebda6ec4a74362e480a0827fd3901da732cedf4a749b50cff4bc8af717976273b83ad94f4670066d7c542b34d11844b4bca59fc0bacd49527d9db3c151c8e9c58dc8806f676805fa498755d8b64798b97666fba7024f9cecd497cbda1c2bf33e6c72e0a75617affd9ba3bb8315b8327080c2463ec42fbbcf5ddec99dc7c238d03482b0702192a48fa64961a09ba7f313259a3aff8dc9908103054a69600ac03adf7d2d2bfa6b7945e7fce47c7ce27ba716c84495c9b4c094883ed607ce5538ba272e5bb84929e963f0009743d724d8b45f6a04035cae921ee74e7fac9e1c34e28283e51f4a70d9c8ce9de99a46e2ae4685b5dd2bb298bd0acfb5cf2c0be3ac5b8bc6d4079f6ccd7a52c2e302725116983b91cb3ff605557b904a3e58e4af6ed4bc63fa1fe4422195d238b82ce7968c5d3ee2623f1af2420ead1f80aaf6fedfc6307c78a4466dbfc583c8b6daa0aabfb1651a5f05714c564add6d0a87543c9dd14ef76b4a21cd7874255534b9a985930cc7b7a8f2625178be83f65506b32cf941c36a9068c3c45b9e18af54060f94dbed4e755f73d639e903e10fea72555945e2e3189d5dbc2ad767c3dc8a32f15468c9fefb9a391f724b86678fa3b8990a5df1cfbc4d06b6738b84f343b3418395530cb716517dc9e508d777a38183bda3d2c36451f9efb6165d7fce1a1765a061d4f70b5fadef62aff64582b2a801449eb89714c01739d59bf9a670619394bd6ceb9b0764d4885e54663f8decdd9688b4d6f017266d94d4906c54f5ebdfa7befc156c40ec23bb7c96ef0c9349ef7a61d78489959b69a5b8dd5ddfca2e3050f2a39a071cda2620040d6afac73d7864bed3a53ce8fa413dbbce4c9394f1a1deb65eb509b2eb3eab61b066be42f85fbd561c8964e951639b8aaa593c2e4e691761b8e4b6f00306373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }

    /**
     * Golden `T-CCE-7`: Reader offers all 4 BLE modes + optional 7-byte reader peripheral address
     * (`{0: true, 1: true, 2: true, 3: true, 11: h'00000001...', 20: h'28078080bf2801', 21: 128}`) plus
     * `MdocConnectionMethodNfc` (which `mdocReaderNfcCceHandover` must strip per Clauses 9.5.1 & 11.2.1);
     * mdoc selects `DirectL2capPeripheral` with mdoc's UUID, 7-byte peripheral address, and PSM `129`.
     */
    @Test
    fun testGoldenCceAllFourModesOffer() = runTest {
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val readerAddress = BlePeripheralAddress.fromEncoded("28078080bf2801".fromHex())
        val mdocAddress = BlePeripheralAddress.fromEncoded("28128b37282801".fromHex())
        val readerOffer = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            directL2capPeripheral = true,
            directL2capCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
            peripheralAddress = readerAddress,
        )
        val expectedSelected = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = null,
            directL2capPsm = 129,
            peripheralAddress = mdocAddress,
        )
        val transcript = runGoldenCceBleScenario(
            readerOfferedMethods = listOf(
                readerOffer,
                MdocConnectionMethodNfc(65535L, 65536L),
            ),
            mdocHandoverPicker = { methods ->
                assertEquals(4, methods.size)
                val bleMethods = methods.map { it as MdocConnectionMethodBle }
                assertTrue(bleMethods[0].directL2capCentral)
                assertTrue(bleMethods[1].directL2capPeripheral)
                assertTrue(bleMethods[2].gattCentral)
                assertTrue(bleMethods[3].gattPeripheral)
                bleMethods[1].copy(
                    peripheralModeUuid = peripheralUuid,
                    directL2capPsm = 129,
                    peripheralAddress = mdocAddress,
                )
            },
            expectedSelectedMethod = expectedSelected,
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
            #           0: true,
            #           1: true,
            #           2: true,
            #           3: true,
            #           11: h'00000001a12348ce896b4c76973373e6',
            #           20: h'28078080bf2801',
            #           21: 128
            #         }
            #       ]
            #     ]
            #   }
            # }
            C-APDU: 00c30000000089538186a100a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a700f501f502f503f50b5000000001a12348ce896b4c76973373e6144728078080bf28011518800000
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
            #           1: false,
            #           2: true,
            #           3: false,
            #           10: h'00000002a12348ce896b4c76973373e6',
            #           20: h'28128b37282801',
            #           21: 129
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
            R-APDU: 53818ea100a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a700f401f402f503f40a5000000002a12348ce896b4c76973373e6144728128b37282801151881058006a203f504f59000

            # The NFC field is lost.
            NFC: Field lost

            # The mdoc reader sends SessionData (seq=0) with encrypted DeviceRequest over BLE.
            # {
            #   "data": h'f0f378ea182b3dab4324e46ac4bd4329cdf45744f807571273e2b8495a7e5bed6c405bcfe7ec37146a4d8ad68e29404fc05df85c24474408b40d5f9dd7010e2eae9a5dc8174084a0fcfd9f89d7bda49e159df645b8e0b4ee0842366c2c3cfb2e686063f5c461dcff2aadaa286878a0d5dfe317659f636b6d473ab2e65cfb9d7088f1c03afe3982',
            #   "seq": 0
            # }
            BLE Reader->Mdoc: a264646174615887f0f378ea182b3dab4324e46ac4bd4329cdf45744f807571273e2b8495a7e5bed6c405bcfe7ec37146a4d8ad68e29404fc05df85c24474408b40d5f9dd7010e2eae9a5dc8174084a0fcfd9f89d7bda49e159df645b8e0b4ee0842366c2c3cfb2e686063f5c461dcff2aadaa286878a0d5dfe317659f636b6d473ab2e65cfb9d7088f1c03afe39826373657100

            # The mdoc sends SessionData (seq=1) with encrypted DeviceResponse over BLE.
            # {
            #   "data": h'92c3d013edee44d94e6d40c7de094b4795466f57992b2c70d7b92dae50bd677f5e73f6ded2983120a4cf596c46bce6db1401527a51727c0fe8b2a3e8c6e81205e0c78001ba9cd42ad90478ab4247f69b407eebf6efd939014a5afe6f989417ca412c228a2516fc04b19cf86c51957434f32cb56a4443a83c5e0f0338a11b1ee36c9c308154a0d3542c90f12472fcb5d04abafcdff6c1e564dc59dd23b7275772141033735f4d71fcb43f575c2816eafb7648af7e264888beeaa599e0499a61e458c2dee83b357703cb159cc0dfa9fe844b6135c57b98c91bdb47109c838ea37ddafb702fcd1d62a40e2f3f82d5038b29f2cc637b41a881ea5893baaf1437456ffe119c25f0e539710df62aad6739da4dd045edc90c613658e813f5af020263a6466297f29636e610cdd908dacfa62a9fc5dc0be4967fe05d9c0bd46b9fc8bf4f915910145c82d8019395acbe40979e1fa699c44e1952b81cec957027fe31631a6c2173136ce7a1baa41a2f864935899f02622344919e9b2a23d14c7c79c6fa04e853f6c042e9d06a434827c3710c742e7ae5130d8077411a466ea48555746c1fc7d3a1e31eea6bf3190b50b666e635106d472db7e03bb836dd8e25d4b9077ce853f863717c771336cdd044fd5289cbc9c22ff1c33c5f6d2f0e94b79c324003ccb9402b0e9609893b446865e06a3cacff74e28800a79e11182e0af5f693ff2f92ceaebc38e8c9cc9a3b064dabd5bd9571dc601e1fc515d6ec5bc36e619fc8dd76473a0221ec9ba3cf2f2ae76a16e1e622ace52ba6729483f07ecb211e253c27aab738a36269907957b7446445dee6ac9fa938e9c108973bb6428a937541695a16bd09a098967c753f047563b1fb2ae0aadfabdc8327ed1a9f977ef46742e84d2fd3e81cae0e66554d5c930a387cc3cdbc58aedf2d12f11ea0fb22d38ba6d80d56bd5d4a0720dd84d563b862ee04c81150bf62ae529c199f7b4368a4f385a7dbb61e7bf76944782757919000a471f7fdd40299dfc88aa0fe281ec341af14e26811ae76690ac7829a79df59b87a413d9281f6c4a518ab33a562ade5683bbf9c4f4c9a84cc1a92a02754f5a3e99892ee9dbf2b24fc7325eaa04ee2ae06b010548776d7884fb3dd4b7eaa4a5ad2bf51aba10c50cfad2edb7942d8159e5a0a570dee9b9fe4ebd66b9a9cc103ef5c4b98c46743f0d06b0ff684b1b6b7afe090895b60dc45a06f980ee93de100ffbb9cbd1e3d272c3963df7a9f70f73ba4fc0201924803bba307ee79bc464636314cd69d24ec32ff646aed4c5efebaae50384a919b270f846f4fff29f8d94ec97fddcb5ce506f0e5591758a8f076564cbef7b503c82e9ea2a440d9d17f0cd7dc8aacfa9e4d090c5dacafaa7e92a0111a2f44126fde5f6be3872a9e26967c4b37acb0169fc67ebd32bd36e525effcc4f69cb01a166c6cef63dd1e45417032a9d3c5a161e8cdd37f595e45259c0a15e842c2bc01269feb657daca3417da26500c929ed57051157fd7724c35a869fba165f85af590e7e02afa4b66c037c38bc4033f30e0918910380c600f3bf3a3bee0abb0c2f09411ae215c3f4f07f080214e72936c17f7471f6b5748a29f22d33011d0d63f62c0c45bb346d563a2088191e9c1bc565a5969e5540d6474641c513395f45b50db1b2fbdf68b1d3c74f3e99d93af1cbfd5729eedb14e5a2ddead58f0d320cdf86c5bc57748b227fdfda75596ae84079c6a867a3882643385c5b1886d56b475a02d3b10f609c62a985131d9e3715317e2cc4efb43c87dc8209f7240983c660db8c9ec2f6c1ba3adf36f07566ee723af7c83e7a1639148c6d7c68b169fcb17f4b2b2765914b97b17a0cee048d19f366954920f9fca037644a6935bc0fa1a53c2f8463d2f2c0369013a002edf22adf63d4589f7ac06094e59b6ca5f0548ce700c8aefe5fbee48849f19454134a72b7ab25f6e4da7cdd5701f582ea154621437e498144760262107ec9237bb6bbc39fa11510293a597a1ae9af90dcaa62d178ec395ea7fac235d93e55e4d07ea5a88d56c3973f029bc99c8fcb34dbfc1df4a3dea5d15fcc30d2c9dfa369e7c658de200a3705ae73a10bfa4a8ee40b3f6960fcd0fe92ef14cae3cf403dff3723a2c4caa00f5285e1df1e0f5aa6fc7f8e2080664aa33f47ee5215b0df283d434437edf0ef6b45cc513cef41bd274a49596c433f384888b3deb58c456e564e755e3a8ea19582c8c787495f0067ee00b788d214268b4867283254157300029d817a8a2fb9d0ff8499952f7bd2ce1319857ba33da9fe95d454f45a00b4a16cfa9721880d1bf106bfb0201d74ac15ac2e42bef11a2cbd494a55267431958709cdb4ad338c815c7a4c8b2b95d7f892bcd453c90b24f4932533e163a536b77f95c44c9331a5dac89472978556589a9e83461cdfc352ee429d1edf7eecbd244623a2eac0361f417ae702a4b414d08f31e18ad47964eeefd8173df3f4c2e460e1f6ac059eac9be8e53025615e2c36e8310f7aa32ba074099e9506ea05ce666ceceaf88dc442918deab9e375c8b9b85037f50ca478b05cf93b947f680e49ad26016240b3dd1cf0419e89db2c5d5b3c',
            #   "seq": 1
            # }
            BLE Mdoc->Reader: a2646461746159072f92c3d013edee44d94e6d40c7de094b4795466f57992b2c70d7b92dae50bd677f5e73f6ded2983120a4cf596c46bce6db1401527a51727c0fe8b2a3e8c6e81205e0c78001ba9cd42ad90478ab4247f69b407eebf6efd939014a5afe6f989417ca412c228a2516fc04b19cf86c51957434f32cb56a4443a83c5e0f0338a11b1ee36c9c308154a0d3542c90f12472fcb5d04abafcdff6c1e564dc59dd23b7275772141033735f4d71fcb43f575c2816eafb7648af7e264888beeaa599e0499a61e458c2dee83b357703cb159cc0dfa9fe844b6135c57b98c91bdb47109c838ea37ddafb702fcd1d62a40e2f3f82d5038b29f2cc637b41a881ea5893baaf1437456ffe119c25f0e539710df62aad6739da4dd045edc90c613658e813f5af020263a6466297f29636e610cdd908dacfa62a9fc5dc0be4967fe05d9c0bd46b9fc8bf4f915910145c82d8019395acbe40979e1fa699c44e1952b81cec957027fe31631a6c2173136ce7a1baa41a2f864935899f02622344919e9b2a23d14c7c79c6fa04e853f6c042e9d06a434827c3710c742e7ae5130d8077411a466ea48555746c1fc7d3a1e31eea6bf3190b50b666e635106d472db7e03bb836dd8e25d4b9077ce853f863717c771336cdd044fd5289cbc9c22ff1c33c5f6d2f0e94b79c324003ccb9402b0e9609893b446865e06a3cacff74e28800a79e11182e0af5f693ff2f92ceaebc38e8c9cc9a3b064dabd5bd9571dc601e1fc515d6ec5bc36e619fc8dd76473a0221ec9ba3cf2f2ae76a16e1e622ace52ba6729483f07ecb211e253c27aab738a36269907957b7446445dee6ac9fa938e9c108973bb6428a937541695a16bd09a098967c753f047563b1fb2ae0aadfabdc8327ed1a9f977ef46742e84d2fd3e81cae0e66554d5c930a387cc3cdbc58aedf2d12f11ea0fb22d38ba6d80d56bd5d4a0720dd84d563b862ee04c81150bf62ae529c199f7b4368a4f385a7dbb61e7bf76944782757919000a471f7fdd40299dfc88aa0fe281ec341af14e26811ae76690ac7829a79df59b87a413d9281f6c4a518ab33a562ade5683bbf9c4f4c9a84cc1a92a02754f5a3e99892ee9dbf2b24fc7325eaa04ee2ae06b010548776d7884fb3dd4b7eaa4a5ad2bf51aba10c50cfad2edb7942d8159e5a0a570dee9b9fe4ebd66b9a9cc103ef5c4b98c46743f0d06b0ff684b1b6b7afe090895b60dc45a06f980ee93de100ffbb9cbd1e3d272c3963df7a9f70f73ba4fc0201924803bba307ee79bc464636314cd69d24ec32ff646aed4c5efebaae50384a919b270f846f4fff29f8d94ec97fddcb5ce506f0e5591758a8f076564cbef7b503c82e9ea2a440d9d17f0cd7dc8aacfa9e4d090c5dacafaa7e92a0111a2f44126fde5f6be3872a9e26967c4b37acb0169fc67ebd32bd36e525effcc4f69cb01a166c6cef63dd1e45417032a9d3c5a161e8cdd37f595e45259c0a15e842c2bc01269feb657daca3417da26500c929ed57051157fd7724c35a869fba165f85af590e7e02afa4b66c037c38bc4033f30e0918910380c600f3bf3a3bee0abb0c2f09411ae215c3f4f07f080214e72936c17f7471f6b5748a29f22d33011d0d63f62c0c45bb346d563a2088191e9c1bc565a5969e5540d6474641c513395f45b50db1b2fbdf68b1d3c74f3e99d93af1cbfd5729eedb14e5a2ddead58f0d320cdf86c5bc57748b227fdfda75596ae84079c6a867a3882643385c5b1886d56b475a02d3b10f609c62a985131d9e3715317e2cc4efb43c87dc8209f7240983c660db8c9ec2f6c1ba3adf36f07566ee723af7c83e7a1639148c6d7c68b169fcb17f4b2b2765914b97b17a0cee048d19f366954920f9fca037644a6935bc0fa1a53c2f8463d2f2c0369013a002edf22adf63d4589f7ac06094e59b6ca5f0548ce700c8aefe5fbee48849f19454134a72b7ab25f6e4da7cdd5701f582ea154621437e498144760262107ec9237bb6bbc39fa11510293a597a1ae9af90dcaa62d178ec395ea7fac235d93e55e4d07ea5a88d56c3973f029bc99c8fcb34dbfc1df4a3dea5d15fcc30d2c9dfa369e7c658de200a3705ae73a10bfa4a8ee40b3f6960fcd0fe92ef14cae3cf403dff3723a2c4caa00f5285e1df1e0f5aa6fc7f8e2080664aa33f47ee5215b0df283d434437edf0ef6b45cc513cef41bd274a49596c433f384888b3deb58c456e564e755e3a8ea19582c8c787495f0067ee00b788d214268b4867283254157300029d817a8a2fb9d0ff8499952f7bd2ce1319857ba33da9fe95d454f45a00b4a16cfa9721880d1bf106bfb0201d74ac15ac2e42bef11a2cbd494a55267431958709cdb4ad338c815c7a4c8b2b95d7f892bcd453c90b24f4932533e163a536b77f95c44c9331a5dac89472978556589a9e83461cdfc352ee429d1edf7eecbd244623a2eac0361f417ae702a4b414d08f31e18ad47964eeefd8173df3f4c2e460e1f6ac059eac9be8e53025615e2c36e8310f7aa32ba074099e9506ea05ce666ceceaf88dc442918deab9e375c8b9b85037f50ca478b05cf93b947f680e49ad26016240b3dd1cf0419e89db2c5d5b3c6373657101

            # The mdoc reader sends SessionData (seq=2, status=20) to terminate the session over BLE.
            # {
            #   "status": 20,
            #   "seq": 2
            # }
            BLE Reader->Mdoc: a266737461747573146373657102
        """.trimIndent()
        assertEquals(expectedTranscript, transcript)
    }
}
