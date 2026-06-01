package org.multipaz.prompt

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.ConsentData
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata

/**
 * A function that can be used to obtain consent and selection of credentials by the user.
 *
 * The functions [promptModelRequestConsent] and [promptModelSilentConsent] are expected to
 * cover most needs but this type alias exists so applications can override this with their own
 * implementations.
 *
 * If no consent is given or the user dismissed the dialog, `null` is returned.
 *
 * Note that implementations *MUST* always call [onDocumentsInFocus], even if no user interaction
 * is happening.
 */
typealias ShowConsentPromptFn = suspend (
    requester: Requester,
    trustMetadata: TrustMetadata?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
) -> CredentialSelection?

/**
 * A [ShowConsentPromptFn] which doesn't show any consent prompt
 *
 * @return the result of calling [CredentialQueryResult.select] passing [preselectedDocuments].
 */
suspend fun promptModelSilentConsent(
    requester: Requester,
    trustMetadata: TrustMetadata?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialSelection? {
    val ret = consentData.credentialQueryResult.select(preselectedDocuments)
    onDocumentsInFocus(ret.matches.map { it.credential.document })
    return ret
}

/**
 * A [ShowConsentPromptFn] which calls [PromptModel.requestConsent] on the [PromptModel] in the
 * current coroutine scope.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustMetadata [TrustMetadata] conveying the level of trust in the requester, if any.
 * @param consentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments a list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return `null` if the user dismissed the prompt, otherwise a [CredentialSelection] object
 *   conveying which credentials the user selected, if multiple options are available.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 */
suspend fun promptModelRequestConsent(
    requester: Requester,
    trustMetadata: TrustMetadata?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialSelection? {
    try {
        return PromptModel.get().requestConsent(
            requester = requester,
            trustMetadata = trustMetadata,
            consentData = consentData,
            preselectedDocuments = preselectedDocuments,
            onDocumentsInFocus = onDocumentsInFocus,
        )
    } catch (_: PromptDismissedException) {
        return null
    }
}
