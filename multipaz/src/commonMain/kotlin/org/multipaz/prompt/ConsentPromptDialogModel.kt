package org.multipaz.prompt

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.ConsentData
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

class ConsentPromptDialogModel():
    PromptDialogModel<ConsentPromptDialogModel.ConsentPromptRequest, CredentialSelection>() {
    override val dialogType: PromptDialogModel.DialogType<ConsentPromptDialogModel>
        get() = DialogType

    object DialogType : PromptDialogModel.DialogType<ConsentPromptDialogModel>

    /**
     * Class that holds parameters that are needed to present a consent prompt dialog to the user.
     *
     * @param requester the relying party which is requesting the data.
     * @param trustedRequesterIdentity conveys the level of trust in the requester, if any.
     * @param consentData the combinations of credentials and claims that the user can select.
     * @param preselectedDocuments a list of documents the user may have preselected earlier (for
     *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
     *   if the user didn't preselect.
     * @param onDocumentsInFocus called with the documents currently selected for the user, including when
     *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
     */
    class ConsentPromptRequest(
        val requester: Requester,
        val trustedRequesterIdentity: TrustedRequesterIdentity?,
        val consentData: ConsentData,
        val preselectedDocuments: List<Document>,
        val onDocumentsInFocus: (documents: List<Document>) -> Unit
    )
}
