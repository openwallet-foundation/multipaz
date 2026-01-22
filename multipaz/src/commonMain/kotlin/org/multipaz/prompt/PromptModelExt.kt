package org.multipaz.prompt

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.prompt.PassphrasePromptDialogModel.PassphraseRequest
import org.multipaz.request.Requester
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.coroutines.cancellation.CancellationException

fun PromptModel.getPassphraseDialogModel() = getDialogModel(PassphrasePromptDialogModel.DialogType)

fun PromptModel.getConsentPromptDialogModel() = getDialogModel(ConsentPromptDialogModel.DialogType)

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
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PromptDismissedException::class,
    PromptModelNotAvailableException::class,
    PromptUiNotAvailableException::class
)
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

/**
 * Shows a consent prompt to the user for presentment of credentials.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustMetadata [TrustMetadata] conveying the level of trust in the requester, if any.
 * @param credentialPresentmentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments a list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return a [CredentialPresentmentSelection] object conveying which credentials the user selected, if multiple
 *   options are available.
 * @throws PromptDismissedException if the user dismissed the prompt.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PromptDismissedException::class,
    PromptModelNotAvailableException::class,
    PromptUiNotAvailableException::class
)
suspend fun PromptModel.requestConsent(
    requester: Requester,
    trustMetadata: TrustMetadata?,
    credentialPresentmentData: CredentialPresentmentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialPresentmentSelection {
    return getDialogModel(ConsentPromptDialogModel.DialogType).displayPrompt(
        parameters = ConsentPromptDialogModel.ConsentPromptRequest(
            requester = requester,
            trustMetadata = trustMetadata,
            credentialPresentmentData = credentialPresentmentData,
            preselectedDocuments = preselectedDocuments,
            onDocumentsInFocus = onDocumentsInFocus,
        )
    )
}
