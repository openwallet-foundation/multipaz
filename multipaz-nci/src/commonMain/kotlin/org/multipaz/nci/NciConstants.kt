package org.multipaz.nci

/**
 * NFC Forum NFC Controller Interface (NCI) 2.0 and NXP PN7160 protocol constants.
 */
internal object NciConstants {
    // Message Types (MT, bits 7..5 of octet 0)
    const val MT_SHIFT = 5
    const val MT_MASK = 0xE0
    const val MT_DATA = 0x00
    const val MT_CMD = 0x20
    const val MT_RSP = 0x40
    const val MT_NTF = 0x60

    // Packet Boundary Flag (PBF, bit 4 of octet 0)
    const val PBF_MASK = 0x10
    const val PBF_COMPLETE = 0x00
    const val PBF_SEGMENT = 0x10

    // Group Identifier (GID, bits 3..0 of octet 0 for control messages) or ConnID (for data messages)
    const val GID_MASK = 0x0F
    const val CONN_ID_MASK = 0x0F
    const val GID_CORE = 0x00
    const val GID_RF_MGMT = 0x01
    const val GID_NFCEE_MGMT = 0x02
    const val GID_PROPRIETARY = 0x0F

    // Opcode Identifier (OID, bits 5..0 of octet 1 for control messages)
    const val OID_MASK = 0x3F

    // Core OIDs (GID = 0x00)
    const val OID_CORE_RESET = 0x00
    const val OID_CORE_INIT = 0x01
    const val OID_CORE_SET_CONFIG = 0x02
    const val OID_CORE_GET_CONFIG = 0x03
    const val OID_CORE_CONN_CREATE = 0x04
    const val OID_CORE_CONN_CLOSE = 0x05
    const val OID_CORE_CONN_CREDITS = 0x06
    const val OID_CORE_GENERIC_ERROR = 0x07
    const val OID_CORE_INTERFACE_ERROR = 0x08

    // RF Management OIDs (GID = 0x01)
    const val OID_RF_DISCOVER_MAP = 0x00
    const val OID_RF_SET_LISTEN_MODE_ROUTING = 0x01
    const val OID_RF_GET_LISTEN_MODE_ROUTING = 0x02
    const val OID_RF_DISCOVER = 0x03
    const val OID_RF_DISCOVER_SELECT = 0x04
    const val OID_RF_INTF_ACTIVATED = 0x05
    const val OID_RF_DEACTIVATE = 0x06
    const val OID_RF_FIELD_INFO = 0x07
    const val OID_RF_T3T_POLLING = 0x08
    const val OID_RF_NFCEE_ACTION = 0x09
    const val OID_RF_NFCEE_DISCOVERY_REQ = 0x0A
    const val OID_RF_PARAMETER_UPDATE = 0x0B

    // NXP Proprietary OIDs (GID = 0x0F)
    const val OID_NXP_PROPRIETARY_ACT = 0x02
    const val OID_NXP_PRESENCE_CHECK = 0x11

    // Status Codes
    const val STATUS_OK = 0x00
    const val STATUS_REJECTED = 0x01
    const val STATUS_RF_FRAME_CORRUPTED = 0x02
    const val STATUS_FAILED = 0x03
    const val STATUS_NOT_INITIALIZED = 0x04
    const val STATUS_SYNTAX_ERROR = 0x05
    const val STATUS_SEMANTIC_ERROR = 0x06
    const val STATUS_INVALID_PARAM = 0x09
    const val STATUS_MESSAGE_SIZE_EXCEEDED = 0x0A
    const val STATUS_DISCOVERY_ALREADY_STARTED = 0xA0
    const val STATUS_DISCOVERY_TARGET_ACTIVATION_FAILED = 0xA1
    const val STATUS_DISCOVERY_TEAR_DOWN = 0xA2
    const val STATUS_RF_TRANSMISSION_ERROR = 0xB0
    const val STATUS_RF_PROTOCOL_ERROR = 0xB1
    const val STATUS_RF_TIMEOUT_ERROR = 0xB2

    // CORE_RESET Reset Types
    const val RESET_TYPE_KEEP_CONFIG = 0x00
    const val RESET_TYPE_RESET_CONFIG = 0x01

    // NCI Versions
    const val NCI_VERSION_1_0 = 0x10
    const val NCI_VERSION_1_1 = 0x11
    const val NCI_VERSION_2_0 = 0x20

    // Static RF Connection ID
    const val STATIC_RF_CONN_ID = 0x00
    const val DH_NFCEE_ID = 0x00
    const val CREDITS_NO_FLOW_CONTROL = 0xFF

    // RF Technologies & Modes
    const val NFC_A_PASSIVE_POLL_MODE = 0x00
    const val NFC_B_PASSIVE_POLL_MODE = 0x01
    const val NFC_F_PASSIVE_POLL_MODE = 0x02
    const val NFC_V_PASSIVE_POLL_MODE = 0x06
    const val NFC_A_PASSIVE_LISTEN_MODE = 0x80
    const val NFC_B_PASSIVE_LISTEN_MODE = 0x81
    const val NFC_F_PASSIVE_LISTEN_MODE = 0x82

    // RF Technologies
    const val RF_TECH_A = 0x00
    const val RF_TECH_B = 0x01
    const val RF_TECH_F = 0x02
    const val RF_TECH_V = 0x03

    // RF Protocols
    const val PROTOCOL_UNDETERMINED = 0x00
    const val PROTOCOL_T1T = 0x01
    const val PROTOCOL_T2T = 0x02
    const val PROTOCOL_T3T = 0x03
    const val PROTOCOL_ISO_DEP = 0x04
    const val PROTOCOL_NFC_DEP = 0x05
    const val PROTOCOL_T5T = 0x06

    // RF Interfaces
    const val RF_INTERFACE_NFCEE_DIRECT = 0x00
    const val RF_INTERFACE_FRAME = 0x01
    const val RF_INTERFACE_ISO_DEP = 0x02
    const val RF_INTERFACE_NFC_DEP = 0x03

    // RF_DISCOVER_MAP Mode Flags
    const val DISCOVER_MAP_MODE_POLL = 0x01
    const val DISCOVER_MAP_MODE_LISTEN = 0x02

    // Listen Mode Routing Entry Types
    const val ROUTE_ENTRY_TYPE_TECH = 0x00
    const val ROUTE_ENTRY_TYPE_PROTOCOL = 0x01
    const val ROUTE_ENTRY_TYPE_AID = 0x02

    // Listen Mode Routing Power States (bit 0 = Switched On, bits 3..5 = Switched On Sub-states)
    const val POWER_STATE_SWITCHED_ON = 0x01

    // Deactivation Types (RF_DEACTIVATE_CMD / NTF)
    const val DEACTIVATE_TYPE_IDLE = 0x00
    const val DEACTIVATE_TYPE_SLEEP = 0x01
    const val DEACTIVATE_TYPE_SLEEP_AF = 0x02
    const val DEACTIVATE_TYPE_DISCOVERY = 0x03

    // Deactivation Reasons (RF_DEACTIVATE_NTF)
    const val DEACTIVATE_REASON_DH_REQUEST = 0x00
    const val DEACTIVATE_REASON_ENDPOINT_REQUEST = 0x01
    const val DEACTIVATE_REASON_RF_LINK_LOSS = 0x02
    const val DEACTIVATE_REASON_NFC_B_BAD_AFI = 0x03
    const val DEACTIVATE_REASON_DH_REQUEST_FAILED = 0x04

    // Configuration Parameter Tags (CORE_SET_CONFIG_CMD)
    const val PARAM_TOTAL_DURATION = 0x00
    const val PARAM_PA_BAIL_OUT = 0x08
    const val PARAM_PB_BAIL_OUT = 0x10
    const val PARAM_PI_BIT_RATE = 0x21
    const val PARAM_LA_BIT_FRAME_SDD = 0x30
    const val PARAM_LA_PLATFORM_CONFIG = 0x31
    const val PARAM_LA_SEL_INFO = 0x32
    const val PARAM_LA_NFCID1 = 0x33
    const val PARAM_LF_PROTOCOL_TYPE = 0x50
    const val PARAM_LI_A_RATS_TB1 = 0x58
    const val PARAM_LI_A_HIST_BY = 0x59
    const val PARAM_LI_A_BIT_RATE = 0x5B
    const val PARAM_LI_A_RATS_TC1 = 0x5C
    const val PARAM_RF_FIELD_INFO = 0x80

    // Bit rate values for PARAM_PI_BIT_RATE / PARAM_LI_A_BIT_RATE
    const val BIT_RATE_106_KBPS = 0x00
    const val BIT_RATE_212_KBPS = 0x01
    const val BIT_RATE_424_KBPS = 0x02
    const val BIT_RATE_848_KBPS = 0x03

    /**
     * Returns `true` if [rfTechAndMode] represents a Listen Mode activation (`0x80..0xFF`).
     */
    fun isListenMode(rfTechAndMode: Int): Boolean = (rfTechAndMode and 0x80) != 0

    /**
     * Returns `true` if [rfTechAndMode] represents a Poll Mode activation (`0x00..0x7F`).
     */
    fun isPollMode(rfTechAndMode: Int): Boolean = (rfTechAndMode and 0x80) == 0

    /**
     * Builds an NCI Control Command packet (`MT_CMD`).
     */
    fun buildCommand(gid: Int, oid: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 255) { "NCI control packet payload exceeds 255 bytes: ${payload.size}" }
        val packet = ByteArray(3 + payload.size)
        packet[0] = (MT_CMD or PBF_COMPLETE or (gid and GID_MASK)).toByte()
        packet[1] = (oid and OID_MASK).toByte()
        packet[2] = payload.size.toByte()
        payload.copyInto(packet, destinationOffset = 3)
        return packet
    }

    /**
     * Builds an NCI Control Response packet (`MT_RSP`).
     */
    fun buildResponse(gid: Int, oid: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 255) { "NCI control packet payload exceeds 255 bytes: ${payload.size}" }
        val packet = ByteArray(3 + payload.size)
        packet[0] = (MT_RSP or PBF_COMPLETE or (gid and GID_MASK)).toByte()
        packet[1] = (oid and OID_MASK).toByte()
        packet[2] = payload.size.toByte()
        payload.copyInto(packet, destinationOffset = 3)
        return packet
    }

    /**
     * Builds an NCI Control Notification packet (`MT_NTF`).
     */
    fun buildNotification(gid: Int, oid: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 255) { "NCI control packet payload exceeds 255 bytes: ${payload.size}" }
        val packet = ByteArray(3 + payload.size)
        packet[0] = (MT_NTF or PBF_COMPLETE or (gid and GID_MASK)).toByte()
        packet[1] = (oid and OID_MASK).toByte()
        packet[2] = payload.size.toByte()
        payload.copyInto(packet, destinationOffset = 3)
        return packet
    }

    /**
     * Builds an NCI Data packet (`MT_DATA`) for [connId].
     */
    fun buildDataPacket(
        connId: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
        isComplete: Boolean = true
    ): ByteArray {
        require(length in 0..255) { "NCI data packet payload must be 0..255 bytes, got $length" }
        val pbf = if (isComplete) PBF_COMPLETE else PBF_SEGMENT
        val packet = ByteArray(3 + length)
        packet[0] = (MT_DATA or pbf or (connId and CONN_ID_MASK)).toByte()
        packet[1] = 0x00
        packet[2] = length.toByte()
        payload.copyInto(packet, destinationOffset = 3, startIndex = offset, endIndex = offset + length)
        return packet
    }
}
