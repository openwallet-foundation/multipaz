package org.multipaz.prompt

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Gives user feedback while NFC scanning is performed.
 */
class ScanNfcPromptDialogModel:
    PromptDialogModel<ScanNfcPromptDialogModel.NfcDialogParameters, Unit>() {
    object DialogType: PromptDialogModel.DialogType<ScanNfcPromptDialogModel>

    override val dialogType: DialogType get() = DialogType

    /**
     * Parameters needed for UI to display the NFC dialog. See
     * [org.multipaz.nfc.NfcTagReader.scan] for more information.
     *
     * @property message the message to show in the dialog.
     * @property icon the icon to show in the dialog.
     */
    class NfcDialogParameters(
        val message: StateFlow<String>,
        val icon: StateFlow<ScanNfcTagDialogIcon>,
    )

    internal suspend fun dismissAfterLinger(parameters: NfcDialogParameters) {
        val state = dialogState.first()
        if (state is DialogShownState && state.parameters === parameters) {
            runCatching {
                state.resultChannel.send(Unit)
            }
        }
    }
}
