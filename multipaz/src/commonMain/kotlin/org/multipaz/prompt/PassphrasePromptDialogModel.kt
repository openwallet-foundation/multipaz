package org.multipaz.prompt

import org.multipaz.securearea.PassphraseConstraints

/**
 * [PromptDialogModel] to display a passphrase dialog.
 *
 * See [PromptModel.requestPassphrase] that is a thin wrapper over this class functionality for
 * more info.
 */
class PassphrasePromptDialogModel():
        PromptDialogModel<PassphrasePromptDialogModel.PassphraseRequest, String>() {
    override val dialogType: PromptDialogModel.DialogType<PassphrasePromptDialogModel>
        get() = DialogType

    object DialogType : PromptDialogModel.DialogType<PassphrasePromptDialogModel>

    /**
     * Data for the UI to display and run passphrase dialog.
     * @property title the title for the passphrase prompt.
     * @property subtitle the subtitle for the passphrase prompt.
     * @property passphraseConstraints the [PassphraseConstraints] for the passphrase.
     * @property passphraseEvaluator an optional function to evaluate the passphrase and give the user feedback.
     */
    class PassphraseRequest(
        val title: String,
        val subtitle: String,
        val passphraseConstraints: PassphraseConstraints,
        val passphraseEvaluator: (suspend (enteredPassphrase: String) -> PassphraseEvaluation)?
    )
}


