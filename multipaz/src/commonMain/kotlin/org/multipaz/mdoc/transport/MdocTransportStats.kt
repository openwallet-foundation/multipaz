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

    /** Secondary transport in NFC CCE hybrid mode failed to open while NFC continued. */
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
 * @property bytesSent total application-level payload bytes sent over this transport.
 * @property bytesReceived total application-level payload bytes received over this transport.
 * @property numMessagesSent number of transport messages sent.
 * @property numMessagesReceived number of transport messages received.
 * @property warnings structured warnings recorded during the transport session.
 * @property bleScanningTime time spent scanning/waiting for the BLE peer before connection starts, if applicable.
 * @property bleConnectingTime time spent establishing the BLE connection after scanning completes, if applicable.
 * @property bleUsingL2cap `true` if BLE L2CAP was used for data transfer, or `null` if not a BLE transport.
 * @property bleL2capPsmInEngagement `true` if L2CAP PSM was advertised in engagement rather than via GATT characteristic, or `null` if not a BLE transport.
 * @property bleNegotiatedMtu negotiated BLE GATT ATT MTU, if GATT was used.
 * @property bleMtuInferredFromWrite `true` if [bleNegotiatedMtu] was inferred from an incoming GATT write rather than `onMtuChanged`, or `null` if not a BLE transport.
 * @property bleNumScanRestarts number of times BLE scanning timed out or failed and restarted, or `null` if not acting as a BLE central.
 * @property bleNumGattConnectRetries number of GATT connection retries after initial failure or mid-handshake disconnect, or `null` if not acting as a BLE central.
 * @property bleNumMtuRequestRetries number of GATT MTU request retries after initial failure, or `null` if not acting as a BLE central.
 * @property bleNumServiceDiscoveryRetries number of GATT service discovery retries after initial failure, or `null` if not acting as a BLE central.
 * @property bleNumCharacteristicDiscoveryRetries number of GATT characteristic discovery retries after initial failure, or `null` if not acting as a BLE central.
 * @property bleNumCharacteristicReadRetries number of GATT characteristic read (`Ident` or `L2CAP` PSM) retries after initial failure, or `null` if not acting as a BLE central.
 * @property bleNumDescriptorWriteRetries number of GATT descriptor (CCCD) write retries after initial failure, or `null` if not acting as a BLE central.
 * @property bleNumL2capConnectRetries number of L2CAP socket connection retries after initial failure, or `null` if not acting as a BLE central.
 * @property bleNumChunksSent number of GATT characteristic chunks sent, or `null` if not a BLE transport.
 * @property bleNumChunksReceived number of GATT characteristic chunks received, or `null` if not a BLE transport.
 * @property nfcHybridNumSentViaNfc number of messages sent via the NFC channel in NFC CCE modes, or `null` if not an NFC CCE transport.
 * @property nfcHybridNumSentViaTransport number of messages sent via the secondary transport in NFC CCE hybrid modes, or `null` if not an NFC CCE transport.
 * @property nfcHybridNumReceivedFirstOnNfc number of messages received first on NFC in NFC CCE modes, or `null` if not an NFC CCE transport.
 * @property nfcHybridNumReceivedFirstOnTransport number of messages received first on the secondary transport in NFC CCE hybrid modes, or `null` if not an NFC CCE transport.
 * @property nfcDisconnectedDuringTransaction `true` if the NFC field disconnected during the transaction in NFC CCE modes, or `null` if not an NFC CCE transport.
 */
@CborSerializable
data class MdocTransportStats(
    val transportType: MdocTransportType,
    val role: MdocRole,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val numMessagesSent: Int = 0,
    val numMessagesReceived: Int = 0,
    val warnings: List<TransportWarning> = emptyList(),
    val bleScanningTime: Duration? = null,
    val bleConnectingTime: Duration? = null,
    val bleUsingL2cap: Boolean? = null,
    val bleL2capPsmInEngagement: Boolean? = null,
    val bleNegotiatedMtu: Int? = null,
    val bleMtuInferredFromWrite: Boolean? = null,
    val bleNumScanRestarts: Int? = null,
    val bleNumGattConnectRetries: Int? = null,
    val bleNumMtuRequestRetries: Int? = null,
    val bleNumServiceDiscoveryRetries: Int? = null,
    val bleNumCharacteristicDiscoveryRetries: Int? = null,
    val bleNumCharacteristicReadRetries: Int? = null,
    val bleNumDescriptorWriteRetries: Int? = null,
    val bleNumL2capConnectRetries: Int? = null,
    val bleNumChunksSent: Int? = null,
    val bleNumChunksReceived: Int? = null,
    val nfcHybridNumSentViaNfc: Int? = null,
    val nfcHybridNumSentViaTransport: Int? = null,
    val nfcHybridNumReceivedFirstOnNfc: Int? = null,
    val nfcHybridNumReceivedFirstOnTransport: Int? = null,
    val nfcDisconnectedDuringTransaction: Boolean? = null,
) {
    companion object
}
