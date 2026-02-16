package org.multipaz.nfc

/**
 * The status of a [ExternalNfcReader].
 */
enum class ExternalNfcReaderState {
    /** The reader is currently not connected. */
    NOT_CONNECTED,

    /** The reader is connected but permission is needed to use it. */
    CONNECTED_NO_PERMISSION,

    /** The reader is connected and ready to use. */
    CONNECTED
}
