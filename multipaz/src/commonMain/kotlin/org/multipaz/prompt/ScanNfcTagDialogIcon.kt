package org.multipaz.prompt

/**
 * Constants used to convey which icon to show in [ScanNfcPromptDialogModel].
 */
enum class ScanNfcTagDialogIcon {
    /** The user should initiate the NFC scanning procedure. */
    READY_TO_SCAN,

    /** Indication of success and the user can move their device out of the field. */
    SUCCESS,

    /** Indication that an error occurred. */
    ERROR,
}
