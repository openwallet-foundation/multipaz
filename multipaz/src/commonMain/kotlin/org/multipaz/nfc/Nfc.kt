package org.multipaz.nfc

import org.multipaz.nfc.NdefRecord.Tnf
import org.multipaz.util.fromHex
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString

/**
 * Constants and utilities related to NFC.
 */
object Nfc {
    private const val TAG = "Nfc"

    /**
     * The Application ID for the Type 4 Tag NDEF application.
     */
    val NDEF_APPLICATION_ID = ByteString("D2760000850101".fromHex())

    /**
     * The Application ID for ISO mdoc NFC Concurrent Channel Engagement (CCE).
     *
     * Reference: ISO/IEC 18013-5 Second Edition clause 9.5.2
     */
    val ISO_MDOC_NFC_CCE_APPLICATION_ID = ByteString("A0000002480401".fromHex())

    /**
     * The Application ID for ISO mdoc NFC data transfer.
     *
     * Reference: ISO/IEC 18013-5:2021 clause 8.3.3.1.2
     */
    val ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID = ByteString("A0000002480400".fromHex())

    /**
     * The File Identifier for the NDEF Capability Container file.
     */
    const val NDEF_CAPABILITY_CONTAINER_FILE_ID = 0xe103

    /**
     * The [ResponseApdu.status] for indicating a request was successful.
     *
     * Reference: ISO/IEC 7816-4 clause 5.6
     */
    const val RESPONSE_STATUS_SUCCESS = 0x9000

    /**
     * The [ResponseApdu.status] for indicating an instruction is not supported
     *
     * Reference: ISO/IEC 7816-4 clause 5.6
     */
    const val RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID = 0x6d00

    /**
     * The [ResponseApdu.status] for indicating a command was aborted.
     *
     * Reference: ISO/IEC 7816-4 clause 5.6
     */
    const val RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS = 0x6f00

    /**
     * The [ResponseApdu.status] for indicating a file or application was not found.
     *
     * Reference: ISO/IEC 7816-4 clause 5.6
     */
    const val RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND = 0x6a82

    /**
     * The [ResponseApdu.status] for indicating that bytes are still available.
     *
     * Reference: ISO/IEC 7816-4 clause 5.3.4
     */
    const val RESPONSE_STATUS_CHAINING_RESPONSE_BYTES_STILL_AVAILABLE = 0x6100

    /**
     * Command chaining control flag indicating this is the last or only part of a chain.
     *
     * Reference: ISO/IEC 7816-4 clause 5.4.1
     */
    const val CLA_CHAIN_LAST = 0x00

    /**
     * Command chaining control flag indicating this is not the last part of a chain.
     *
     * Reference: ISO/IEC 7816-4 clause 5.4.1
     */
    const val CLA_CHAIN_NOT_LAST = 0x10

    /**
     * SELECT command.
     *
     * Reference: ISO/IEC 7816-4 clause 11.2.2
     */
    const val INS_SELECT = 0xa4

    /**
     * Parameter value 1 for SELECT when selecting an application.
     *
     * Reference: ISO/IEC 7816-4 clause 11.2.2
     */
    const val INS_SELECT_P1_APPLICATION = 0x04

    /**
     * Parameter value 1 for SELECT when selecting an application.
     *
     * Reference: ISO/IEC 7816-4 clause 11.2.2
     */
    const val INS_SELECT_P2_APPLICATION = 0x00

    /**
     * Parameter value 1 for SELECT when selecting a file.
     *
     * Reference: ISO/IEC 7816-4 clause 11.2.2
     */
    const val INS_SELECT_P1_FILE = 0x00

    /**
     * Parameter value 2 for SELECT when selecting a file.
     */
    const val INS_SELECT_P2_FILE = 0x0c

    /**
     * READ_BINARY command.
     *
     * Reference: ISO/IEC 7816-4 clause 11.3.3
     */
    const val INS_READ_BINARY = 0xb0

    /**
     * UPDATE_BINARY command.
     *
     * Reference: ISO/IEC 7816-4 clause 11.3.5
     */
    const val INS_UPDATE_BINARY = 0xd6

    /**
     * ENVELOPE command.
     *
     * Reference: ISO/IEC 7816-4 clause 11.8.2
     */
    const val INS_ENVELOPE = 0xc3

    /**
     * GET RESPONSE command.
     *
     * Reference: ISO/IEC 7816-4 clause 11.8.1
     */
    const val INS_GET_RESPONSE = 0xc0

    /**
     * RTD Text type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_TEXT = "T".encodeToByteString()

    /**
     * RTD URI type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_URI = "U".encodeToByteString()

    /**
     * RTD Smart Poster type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_SMART_POSTER = "Sp".encodeToByteString()

    /**
     * RTD Alternative Carrier type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_ALTERNATIVE_CARRIER = "ac".encodeToByteString()

    /**
     * RTD Handover Carrier type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_HANDOVER_CARRIER = "Hc".encodeToByteString()

    /**
     * RTD Handover Request type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_HANDOVER_REQUEST = "Hr".encodeToByteString()

    /**
     * RTD Handover Select type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_HANDOVER_SELECT = "Hs".encodeToByteString()

    /**
     * RTD Service Select type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_SERVICE_SELECT = "Ts".encodeToByteString()

    /**
     * RTD Service Parameter type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_SERVICE_PARAMETER = "Tp".encodeToByteString()

    /**
     * RTD TNEP Status Record type, for use with [Tnf.WELL_KNOWN].
     */
    val RTD_TNEP_STATUS = "Te".encodeToByteString()

    /**
     * The service name for connection handover.
     *
     * Reference: NFC Forum Connection Handover Technical Specification section 4.1.2.
     */
    const val SERVICE_NAME_CONNECTION_HANDOVER = "urn:nfc:sn:handover"

    /**
     * The MIME type for BLE connection handover.
     *
     * Reference: NFC Forum Connection Handover Technical Specification section 4.1.2.
     */
    const val MIME_TYPE_CONNECTION_HANDOVER_BLE = "application/vnd.bluetooth.le.oob"

    /**
     * The external type for NFC data transfer.
     *
     * Reference: ISO/IEC 18013-5:2021 clause 8.2.2.2.
     */
    const val EXTERNAL_TYPE_ISO_18013_5_NFC = "iso.org:18013:nfc"
}
