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
     */
    data class FaceMatcherRequest(
        val faceMatcherSession: FaceMatcherSession,
        val reason: Reason = FaceMatchingReason
    )
}
