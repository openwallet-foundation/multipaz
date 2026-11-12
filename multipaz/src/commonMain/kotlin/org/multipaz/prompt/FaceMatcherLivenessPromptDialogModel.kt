package org.multipaz.prompt

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcherLivenessSession
import org.multipaz.facematch.FacePose

/**
 * Prompt dialog model for verifying user liveness and capturing portrait photos with a face matcher.
 */
class FaceMatcherLivenessPromptDialogModel :
    PromptDialogModel<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, Map<FacePose, ByteString>>() {

    /** Dialog type identifier for [FaceMatcherLivenessPromptDialogModel]. */
    object DialogType : PromptDialogModel.DialogType<FaceMatcherLivenessPromptDialogModel>
    override val dialogType: DialogType get() = DialogType

    /**
     * Request parameters for face liveness verification and portrait capture.
     *
     * @property faceMatcherLivenessSession active [FaceMatcherLivenessSession] driving liveness detection and capture.
     * @property reason the [Reason] for face liveness verification.
     * @property requireConfirmation set to `true` to require explicit user confirmation after liveness verification succeeds.
     * @property usesCustomUserInterface `true` if the matcher provides its own custom user interface.
     */
    data class FaceMatcherLivenessRequest(
        val faceMatcherLivenessSession: FaceMatcherLivenessSession,
        val reason: Reason = FaceLivenessReason,
        val requireConfirmation: Boolean = false,
        val usesCustomUserInterface: Boolean = false
    )
}
