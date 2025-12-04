package org.multipaz.prompt

import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcScanOptions
import kotlin.coroutines.CoroutineContext

/**
 * Gives user feedback while NFC scanning is performed.
 */
class ScanNfcPromptDialogModel:
    PromptDialogModel<ScanNfcPromptDialogModel.NfcDialogParameters<Any>, Any?>() {
    object DialogType: PromptDialogModel.DialogType<ScanNfcPromptDialogModel>

    override val dialogType: DialogType get() = DialogType

    /**
     * Parameters needed for UI to display and run NFC dialog. See
     * [org.multipaz.nfc.NfcTagReader.scan] for more information.
     *
     * @property initialMessage the message to initially show in the dialog or `null` to not show a dialog at all.
     * @property interactionFunction the function which is called when the tag is in the field.
     * @property options a [NfcScanOptions] with options to influence scanning.
     * @property context the [CoroutineContext] to use for calls which blocks the calling thread.
     */
    class NfcDialogParameters<out T>(
        val initialMessage: String?,
        val interactionFunction: suspend (tag: NfcIsoTag) -> T?,
        val options: NfcScanOptions,
        val context: CoroutineContext
    )
}