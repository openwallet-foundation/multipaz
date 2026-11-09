package org.multipaz.prompt

import org.multipaz.consent.ClassifyPurposesFn
import org.multipaz.consent.ClassifyRiskFn
import org.multipaz.consent.ClassifySimpleConsentFn
import org.multipaz.consent.defaultClassifyPurposes
import org.multipaz.consent.defaultClassifyRisk
import org.multipaz.consent.defaultClassifySimpleConsent
import org.multipaz.document.Document
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.RequesterHistory
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

class ConsentPromptDialogModel():
    PromptDialogModel<ConsentPromptDialogModel.ConsentPromptRequest, PresentmentSelection>() {
    override val dialogType: PromptDialogModel.DialogType<ConsentPromptDialogModel>
        get() = DialogType

    object DialogType : PromptDialogModel.DialogType<ConsentPromptDialogModel>

    /**
     * Class that holds parameters that are needed to present a consent prompt dialog to the user.
     *
     * @param requester the relying party which is requesting the data.
     * @param trustedRequesterIdentity conveys the level of trust in the requester, if any.
     * @param presentmentData the combinations of credentials and claims that the user can select.
     * @param preselectedDocuments a list of documents the user may have preselected earlier (for
     *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
     *   if the user didn't preselect.
     * @param onDocumentsInFocus called with the documents currently selected for the user, including when
     *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
     * @param transactionDataRendererRepository the [TransactionDataRendererRepository] the prompt uses to
     *   find renderers for transaction data, or `null` to use only the built-in rendering.
     * @param requesterHistory an optional [RequesterHistory] used to query whether the user has shared
     *   credentials with [requester] before.
     * @param classifyPurposesFn a function used to classify the [org.multipaz.consent.PresentmentPurpose]s
     *   for the active selection.
     * @param classifyRiskFn a function used to evaluate the [org.multipaz.consent.ConsentRiskAssessment]
     *   for the active selection.
     * @param classifySimpleConsentFn a function used to evaluate whether the request qualifies for a
     *   combined simple consent and biometric authentication prompt.
     * @param platformConsentOptions optional [PlatformConsentOptions] with platform-specific consent options.
     */
    class ConsentPromptRequest(
        val requester: Requester,
        val trustedRequesterIdentity: TrustedRequesterIdentity?,
        val presentmentData: PresentmentData,
        val preselectedDocuments: List<Document>,
        val onDocumentsInFocus: (documents: List<Document>) -> Unit,
        val transactionDataRendererRepository: TransactionDataRendererRepository?,
        val requesterHistory: RequesterHistory? = null,
        val classifyPurposesFn: ClassifyPurposesFn = ::defaultClassifyPurposes,
        val classifyRiskFn: ClassifyRiskFn = ::defaultClassifyRisk,
        val classifySimpleConsentFn: ClassifySimpleConsentFn = ::defaultClassifySimpleConsent,
        val platformConsentOptions: PlatformConsentOptions? = null,
    )
}
