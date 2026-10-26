package org.multipaz.mdoc.transport

import kotlin.time.Duration
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.mdoc.role.MdocRole

/**
 * Type of transport used for an ISO/IEC 18013-5 or 18013-7 mdoc transaction.
 */
enum class MdocTransportType {
    /** BLE mdoc central client mode according to ISO/IEC 18013-5. */
    MDOC_CENTRAL_CLIENT_MODE,

    /** BLE mdoc peripheral server mode according to ISO/IEC 18013-5. */
    MDOC_PERIPHERAL_SERVER_MODE,

    /** ISO/IEC 18013-5:2021 NFC data transfer mode. */
    NFC_DATA_TRANSFER_MODE,

    /** ISO/IEC 18013-5 Second Edition Concurrent Channel Engagement (CCE) using NFC only (no secondary transport). */
    NFC_CCE_NFC_ONLY,

    /** ISO/IEC 18013-5 Second Edition Concurrent Channel Engagement (CCE) hybrid transport mode with BLE mdoc central client mode. */
    NFC_CCE_MDOC_CENTRAL_CLIENT_MODE,

    /** ISO/IEC 18013-5 Second Edition Concurrent Channel Engagement (CCE) hybrid transport mode with BLE mdoc peripheral server mode. */
    NFC_CCE_MDOC_PERIPHERAL_SERVER_MODE,

    /** Custom or test transport mode. */
    OTHER,
}

/**
 * Structured warning code recorded during transport or presentment execution.
 */
enum class TransportWarningCode {
    /** BLE MTU negotiation did not complete or fell back to the default 23-byte ATT MTU. */
    BLE_MTU_NOT_NEGOTIATED,

    /** Android `onMtuChanged` callback was missed; MTU was inferred from incoming `Client2Server` chunk size. */
    BLE_MTU_INFERRED_FROM_WRITE,

    /** Non-final BLE GATT chunk size did not match `maxCharacteristicSize`. */
    BLE_UNEXPECTED_CHUNK_SIZE,

    /** Unexpected BLE GATT callback (e.g., `onNotificationSent` when not waiting, or write to unknown characteristic). */
    BLE_UNEXPECTED_CALLBACK,

    /** NFC field disconnected and the session waited for the user to re-tap. */
    NFC_DISCONNECTED_WAITING_FOR_RETAP,

    /** Secondary transport in NFCv2 hybrid mode failed to open while NFC continued. */
    NFC_SECONDARY_TRANSPORT_FAILED,

    /** Failed to send session termination message during transport cleanup. */
    SESSION_TERMINATION_SEND_FAILED,
}

/**
 * A structured warning recorded during a transport or presentment session.
 *
 * @property code the warning code.
 */
@CborSerializable
data class TransportWarning(
    val code: TransportWarningCode,
) {
    companion object
}

/**
 * Diagnostic and performance statistics for an [MdocTransport] instance.
 *
 * @property transportType the type of transport.
 * @property role the mdoc role ([MdocRole.MDOC] or [MdocRole.MDOC_READER]).
 * @property scanningTime time spent scanning/waiting for the peer before connection starts, if applicable.
 * @property connectingTime time spent establishing the transport connection after scanning completes, if applicable.
 * @property bytesSent total application-level payload bytes sent over this transport.
 * @property bytesReceived total application-level payload bytes received over this transport.
 * @property numMessagesSent number of transport messages sent.
 * @property numMessagesReceived number of transport messages received.
 * @property warnings structured warnings recorded during the transport session.
 * @property bleUsingL2cap `true` if BLE L2CAP was used for data transfer.
 * @property bleL2capPsmInEngagement `true` if L2CAP PSM was advertised in engagement rather than via GATT characteristic.
 * @property bleNegotiatedMtu negotiated BLE GATT ATT MTU, if GATT was used.
 * @property bleMtuInferredFromWrite `true` if [bleNegotiatedMtu] was inferred from an incoming GATT write rather than `onMtuChanged`.
 * @property bleNumScanRestarts number of times BLE scanning timed out and restarted.
 * @property bleNumGattConnectRetries number of GATT connection retries after initial failure.
 * @property bleNumServiceDiscoveryRetries number of GATT service discovery retries after initial failure.
 * @property bleNumL2capConnectRetries number of L2CAP socket connection retries after initial failure.
 * @property bleNumChunksSent number of GATT characteristic chunks sent.
 * @property bleNumChunksReceived number of GATT characteristic chunks received.
 * @property nfcHybridNumSentViaNfc number of messages sent via the NFC channel in NFCv2 modes.
 * @property nfcHybridNumSentViaTransport number of messages sent via the secondary transport in NFCv2 hybrid modes.
 * @property nfcHybridNumReceivedFirstOnNfc number of messages received first on NFC in NFCv2 modes.
 * @property nfcHybridNumReceivedFirstOnTransport number of messages received first on the secondary transport in NFCv2 hybrid modes.
 * @property nfcDisconnectedDuringTransaction `true` if the NFC field disconnected during the transaction.
 */
@CborSerializable
data class MdocTransportStats(
    val transportType: MdocTransportType,
    val role: MdocRole,
    val scanningTime: Duration? = null,
    val connectingTime: Duration? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val numMessagesSent: Int = 0,
    val numMessagesReceived: Int = 0,
    val warnings: List<TransportWarning> = emptyList(),
    val bleUsingL2cap: Boolean = false,
    val bleL2capPsmInEngagement: Boolean = false,
    val bleNegotiatedMtu: Int? = null,
    val bleMtuInferredFromWrite: Boolean = false,
    val bleNumScanRestarts: Int = 0,
    val bleNumGattConnectRetries: Int = 0,
    val bleNumServiceDiscoveryRetries: Int = 0,
    val bleNumL2capConnectRetries: Int = 0,
    val bleNumChunksSent: Int = 0,
    val bleNumChunksReceived: Int = 0,
    val nfcHybridNumSentViaNfc: Int = 0,
    val nfcHybridNumSentViaTransport: Int = 0,
    val nfcHybridNumReceivedFirstOnNfc: Int = 0,
    val nfcHybridNumReceivedFirstOnTransport: Int = 0,
    val nfcDisconnectedDuringTransaction: Boolean = false,
) {
    companion object
}
