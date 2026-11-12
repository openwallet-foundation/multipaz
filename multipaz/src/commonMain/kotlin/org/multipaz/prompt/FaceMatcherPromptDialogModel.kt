package org.multipaz.prompt

import org.multipaz.facematch.FaceMatcherSession

/**
 * Prompt dialog model for verifying user identity with a face matcher against a reference portrait.
 */
class FaceMatcherPromptDialogModel : PromptDialogModel<FaceMatcherPromptDialogModel.FaceMatcherRequest, Boolean>() {

    /** Dialog type identifier for [FaceMatcherPromptDialogModel]. */
    object DialogType : PromptDialogModel.DialogType<FaceMatcherPromptDialogModel>
    override val dialogType: DialogType get() = DialogType

    /**
     * Request parameters for face matching.
     *
     * @property faceMatcherSession active [FaceMatcherSession] driving verification.
     * @property reason the [Reason] for face matching.
     * @property requireConfirmation set to `true` to require explicit user confirmation after matching succeeds.
     * @property usesCustomUserInterface `true` if the matcher provides its own custom user interface.
     */
    data class FaceMatcherRequest(
        val faceMatcherSession: FaceMatcherSession,
        val reason: Reason = FaceMatchingReason,
        val requireConfirmation: Boolean = false,
        val usesCustomUserInterface: Boolean = false
    )
}
