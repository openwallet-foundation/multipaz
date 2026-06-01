package org.multipaz.prompt

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.ConsentData
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata

class ConsentPromptDialogModel():
    PromptDialogModel<ConsentPromptDialogModel.ConsentPromptRequest, CredentialSelection>() {
    override val dialogType: PromptDialogModel.DialogType<ConsentPromptDialogModel>
        get() = DialogType

    object DialogType : PromptDialogModel.DialogType<ConsentPromptDialogModel>

    class ConsentPromptRequest(
        val requester: Requester,
        val trustMetadata: TrustMetadata?,
        val consentData: ConsentData,
        val preselectedDocuments: List<Document>,
        val onDocumentsInFocus: (documents: List<Document>) -> Unit
    )
}
