package org.multipaz.presentment

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocEngagementType
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.engagement.ReaderEngagement
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "Iso18013ReaderQrPresentment"
private const val READER_QR_SCHEME_PREFIX = "mdoc:"

/**
 * Performs holder-side proximity presentment using ISO/IEC 18013-5 Second Edition **Reader QR**
 * engagement (clauses 9.7 and 12.2.4).
 *
 * This parses the scanned `mdoc:<base64url(ReaderEngagement)>` [uri], validates that the reader's
 * ephemeral key curve is in [supportedCurves], selects a BLE connection method according to
 * [preferredConnectionMethodOrder] and [bleDirectL2capEnabled], opens the BLE transport, transmits
 * the initial unencrypted `{"deviceEngagementBytes": #6.24(bstr .cbor DeviceEngagement)}` message,
 * and completes the encrypted presentment session.
 *
 * @param uri the scanned Reader QR URI (`"mdoc:"` followed by base64url-without-padding `ReaderEngagement` CBOR).
 * @param source the source of truth for documents to present.
 * @param transportFactory factory used to construct the selected BLE [MdocTransport].
 * @param options options passed to [transportFactory].
 * @param preferredConnectionMethodOrder optional list of connection method string prefixes (for example,
 *   `"ble:direct_l2cap_central:"`, `"ble:gatt_central:"`, `"ble:gatt_peripheral:"`) specifying preference
 *   order when the reader advertises multiple BLE modes. When empty or unmatched, defaults to the first
 *   eligible method decoded from `ReaderEngagement` (`DirectL2capCentral`, `GattCentral`, `GattPeripheral`).
 * @param bleDirectL2capEnabled whether `DirectL2capCentral` methods offered by the reader may be selected.
 * @param supportedCurves ECDH curves supported for session encryption (`EDeviceKey` is generated on the
 *   same curve as `EReaderKey`).
 * @param capabilities mdoc capabilities included in `DeviceEngagement`.
 * @param preselectedDocuments documents preselected by the user, if any.
 * @param connectionTimeout timeout for establishing the BLE connection to the reader, or `null` for no timeout.
 * @param timeout timeout for receiving the initial encrypted `DeviceRequest` message after connecting.
 * @param timeoutSubsequentRequests timeout for receiving subsequent messages in multi-request sessions.
 * @param onConnecting callback invoked right before opening the BLE transport connection.
 * @param onWaitingForRequest callback invoked when waiting for a request message from the reader.
 * @param onWaitingForUserInput callback invoked when waiting for user consent, authentication, or face matching.
 * @param onDocumentsInFocus callback invoked with the documents currently in focus for consent.
 * @param onSendingResponse callback invoked right before sending the encrypted `DeviceResponse`.
 * @param onDeviceRequest callback invoked when a verified [DeviceRequest] is received.
 * @param onTelemetry callback invoked with the final [PresentmentTelemetry] before returning or throwing.
 * @throws IllegalArgumentException if [uri] or `ReaderEngagement` is malformed or uses an unsupported curve.
 * @throws PresentmentTimeout if establishing the BLE connection exceeds [connectionTimeout].
 * @throws AuthenticationFailedException if user authentication failed or was canceled.
 * @throws FaceNotMatchedException if face matching failed or was canceled.
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    AuthenticationFailedException::class,
    CancellationException::class,
    Exception::class,
    FaceNotMatchedException::class,
    IllegalArgumentException::class,
    IllegalStateException::class,
    MdocTransportClosedException::class,
    MdocTransportException::class,
    PresentmentTimeout::class,
    Iso18013PresentmentTimeoutException::class,
    PresentmentCanceledException::class,
    PresentmentCannotSatisfyRequestException::class,
)
suspend fun Iso18013ReaderQrPresentment(
    uri: String,
    source: PresentmentSource,
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
    options: MdocTransportOptions = MdocTransportOptions(),
    preferredConnectionMethodOrder: List<String> = emptyList(),
    bleDirectL2capEnabled: Boolean = true,
    supportedCurves: List<EcCurve> = Crypto.supportedCurves.filter { it.supportsKeyAgreement },
    capabilities: Map<Capability, DataItem> = mapOf(
        Capability.READER_AUTH_ALL_SUPPORT to true.toDataItem(),
        Capability.EXTENDED_REQUEST_SUPPORT to true.toDataItem(),
    ),
    preselectedDocuments: List<Document> = emptyList(),
    connectionTimeout: Duration? = 15.seconds,
    timeout: Duration? = 15.seconds,
    timeoutSubsequentRequests: Duration? = 30.seconds,
    onConnecting: () -> Unit = {},
    onWaitingForRequest: () -> Unit = {},
    onWaitingForUserInput: () -> Unit = {},
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    onSendingResponse: () -> Unit = {},
    onDeviceRequest: (deviceRequest: DeviceRequest) -> Unit = {},
    onTelemetry: (telemetry: PresentmentTelemetry) -> Unit = {},
) {
    val startTime = Clock.System.now()
    var eDeviceKey: EcPrivateKey? = null
    var transport: MdocTransport? = null
    var enteredPresentment = false

    try {
        require(uri.startsWith(READER_QR_SCHEME_PREFIX) && !uri.startsWith("mdoc://")) {
            "Invalid Reader QR URI: must start with 'mdoc:' without '//'"
        }
        val encodedPayload = uri.substring(READER_QR_SCHEME_PREFIX.length)
        require(encodedPayload.isNotEmpty()) {
            "Invalid Reader QR URI: empty payload"
        }
        val encodedReaderEngagement = encodedPayload.fromBase64Url()
        val readerEngagement = ReaderEngagement.fromDataItem(
            dataItem = Cbor.decode(encodedReaderEngagement),
            engagementType = MdocEngagementType.READER_QR,
        )
        val eReaderKey = requireNotNull(readerEngagement.eReaderKey) {
            "EReaderKey is required in ReaderEngagement for Reader QR"
        }
        require(eReaderKey.curve in supportedCurves) {
            "Unsupported EReaderKey curve ${eReaderKey.curve} (supported: $supportedCurves)"
        }

        val eligibleMethods = if (bleDirectL2capEnabled) {
            readerEngagement.connectionMethods
        } else {
            readerEngagement.connectionMethods.filterNot {
                it is MdocConnectionMethod.Ble && it.isDirectL2cap
            }
        }
        require(eligibleMethods.isNotEmpty()) {
            "No eligible BLE connection methods in ReaderEngagement " +
                "(offered=${readerEngagement.connectionMethods}, bleDirectL2capEnabled=$bleDirectL2capEnabled)"
        }
        val selectedMethod = preferredConnectionMethodOrder.firstNotNullOfOrNull { prefix ->
            eligibleMethods.firstOrNull { it.toString().startsWith(prefix) }
        } ?: eligibleMethods.first()

        Logger.i(TAG, "Selected Reader QR connection method: $selectedMethod")

        val generatedKey = Crypto.createEcPrivateKey(eReaderKey.curve)
        eDeviceKey = generatedKey
        val deviceEngagement = buildDeviceEngagement(
            eDeviceKey = generatedKey.publicKey,
            version = "1.1",
        ) {
            capabilities.forEach { (capability, value) ->
                addCapability(capability, value)
            }
        }.toDataItem(engagementType = MdocEngagementType.READER_QR)
        val handover = ReaderEngagement.computeHandover(encodedReaderEngagement)

        val createdTransport = transportFactory.createTransport(
            connectionMethod = selectedMethod,
            role = MdocRole.MDOC,
            options = options,
        )
        transport = createdTransport

        onConnecting()
        try {
            if (connectionTimeout == null) {
                createdTransport.open(eSenderKey = eReaderKey)
            } else {
                withTimeout(connectionTimeout) {
                    createdTransport.open(eSenderKey = eReaderKey)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw PresentmentTimeout("Timed out connecting to reader over BLE ($selectedMethod)")
        }

        val engagementParamsFlow = MutableStateFlow(
            EngagementParams(
                eDeviceKey = generatedKey,
                deviceEngagement = deviceEngagement,
                handover = handover,
                eReaderKey = eReaderKey,
                eReaderKeyBytes = readerEngagement.eReaderKeyBytes,
            )
        )
        enteredPresentment = true
        runIso18013Presentment(
            transport = createdTransport,
            engagementParams = engagementParamsFlow,
            source = source,
            keyAgreementPossible = supportedCurves,
            preselectedDocuments = preselectedDocuments,
            insertSequenceNumbers = false,
            timeout = timeout,
            timeoutSubsequentRequests = timeoutSubsequentRequests,
            onWaitingForRequest = onWaitingForRequest,
            onWaitingForUserInput = onWaitingForUserInput,
            onDocumentsInFocus = onDocumentsInFocus,
            onSendingResponse = onSendingResponse,
            onDeviceRequest = onDeviceRequest,
            onTelemetry = onTelemetry,
            startTime = startTime,
        )
    } catch (error: Throwable) {
        if (!enteredPresentment) {
            val currentStats = transport?.stats
            val failureTelemetry = PresentmentTelemetry(
                protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
                engagementType = EngagementType.READER_QR_CODE,
                outcome = classifyPresentmentException(error, waitingForConnection = true),
                warnings = currentStats?.warnings ?: emptyList(),
                durationTotal = Clock.System.now() - startTime,
                transportStats = currentStats,
            )
            source.eventLogger?.addEventAsync(
                EventPresentmentIso18013Proximity(
                    presentmentData = null,
                    request = null,
                    response = null,
                    sessionTranscript = null,
                    telemetry = failureTelemetry,
                )
            )
            onTelemetry(failureTelemetry)
            withContext(NonCancellable) {
                eDeviceKey?.close()
                try {
                    transport?.close()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Error closing transport after Reader QR setup failure", e)
                }
            }
        }
        throw error
    }
}
