package org.multipaz.prompt

import kotlinx.io.bytestring.ByteString
import org.multipaz.document.Document
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.SimulatedFaceMatcher
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.ConsentData
import org.multipaz.prompt.PassphrasePromptDialogModel.PassphraseRequest
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.PassphraseConstraints
import kotlin.coroutines.cancellation.CancellationException

fun PromptModel.getPassphraseDialogModel() = getDialogModel(PassphrasePromptDialogModel.DialogType)

fun PromptModel.getConsentPromptDialogModel() = getDialogModel(ConsentPromptDialogModel.DialogType)

fun PromptModel.getFaceMatcherDialogModel() = getDialogModel(FaceMatcherPromptDialogModel.DialogType)

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
 * @param reason a [Reason] describing why passphrase authentication is needed.
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
    reason: Reason,
    passphraseConstraints: PassphraseConstraints,
    passphraseEvaluator: (suspend (enteredPassphrase: String) -> PassphraseEvaluation)?
): String {
    return getDialogModel(PassphrasePromptDialogModel.DialogType).displayPrompt(
        PassphraseRequest(
            reason,
            passphraseConstraints,
            passphraseEvaluator
        )
    )
}

/**
 * Shows a consent prompt to the user for presentment of credentials.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity conveys the level of trust in the requester, if any.
 * @param consentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments a list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return a [CredentialSelection] object conveying which credentials the user selected, if multiple
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
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialSelection {
    return getDialogModel(ConsentPromptDialogModel.DialogType).displayPrompt(
        parameters = ConsentPromptDialogModel.ConsentPromptRequest(
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            consentData = consentData,
            preselectedDocuments = preselectedDocuments,
            onDocumentsInFocus = onDocumentsInFocus,
        )
    )
}

/**
 * Prompts user to verify their identity by matching their face against a reference portrait.
 *
 * @param referencePortrait the reference portrait image bytes.
 * @param reason user-facing description of the verification reason.
 * @param matcher optional [FaceMatcher] to use for this request.
 * @param document optional [Document] context.
 * @return `true` if face match succeeded, `false` if dismissed or failed.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PromptModelNotAvailableException::class,
    PromptUiNotAvailableException::class
)
suspend fun PromptModel.showFaceMatcherPrompt(
    referencePortrait: ByteString,
    reason: Reason = Reason.HumanReadable(
        title = "Verify it's you",
        subtitle = "Look at the camera to verify your identity",
        requireConfirmation = false
    ),
    matcher: FaceMatcher? = null,
    document: Document? = null
): Boolean {
    val dialogModel = getFaceMatcherDialogModel()
    val matcherToUse = matcher ?: dialogModel.defaultMatcher ?: SimulatedFaceMatcher()
    val faceMatcherSession = matcherToUse.createSession(referencePortrait)
    return try {
        dialogModel.displayPrompt(
            FaceMatcherPromptDialogModel.FaceMatcherRequest(
                referencePortrait = referencePortrait,
                reason = reason,
                matcher = matcherToUse,
                faceMatcherSession = faceMatcherSession,
                document = document
            )
        )
    } catch (e: PromptDismissedException) {
        false
    }
}
