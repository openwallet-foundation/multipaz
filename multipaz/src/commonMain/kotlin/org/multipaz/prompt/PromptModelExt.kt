package org.multipaz.prompt

import org.multipaz.prompt.PassphrasePromptDialogModel.PassphraseRequest
import org.multipaz.securearea.PassphraseConstraints

/**
 * Prompts user for authentication through a passphrase.
 *
 * If [passphraseEvaluator] is not `null`, it is called every time the user inputs a passphrase with
 * the user input as a parameter. It should return [PassphraseEvaluation.OK] to
 * indicate the passphrase is correct otherwise [PassphraseEvaluation.TryAgain] with optional number
 * of remaining attempts, or [PassphraseEvaluation.TooManyAttempts].
 *
 * To dismiss the prompt programmatically, cancel the job the coroutine was launched in.
 *
 * To obtain [title] and [subtitle] back-end code generally should create a [Reason] object and
 * use [PromptModel.toHumanReadable] to convert it to human-readable form. This gives
 * application code a chance to customize user-facing messages.
 *
 * @param title the title for the passphrase prompt.
 * @param subtitle the subtitle for the passphrase prompt.
 * @param passphraseConstraints the [PassphraseConstraints] for the passphrase.
 * @param passphraseEvaluator an optional function to evaluate the passphrase and give the user feedback.
 * @return the passphrase entered by the user.
 * @throws IllegalStateException if [PromptModel] does not have [PassphrasePromptDialogModel] registered
 * @throws PromptDismissedException if user dismissed passphrase prompt dialog.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 */
suspend fun PromptModel.requestPassphrase(
    title: String,
    subtitle: String,
    passphraseConstraints: PassphraseConstraints,
    passphraseEvaluator: (suspend (enteredPassphrase: String) -> PassphraseEvaluation)?
): String {
    return getDialogModel(PassphrasePromptDialogModel.DialogType).displayPrompt(
        PassphraseRequest(
            title,
            subtitle,
            passphraseConstraints,
            passphraseEvaluator
        )
    )
}
