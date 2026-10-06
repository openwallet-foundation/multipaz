package org.multipaz.prompt

import kotlinx.io.bytestring.ByteString
import org.multipaz.document.Document
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherLivenessSession

/**
 * Prompt dialog model for active face liveness verification and portrait photo capture.
 *
 * @param defaultMatcher default [FaceMatcher] to use if not specified in the request.
 */
class FaceMatcherLivenessPromptDialogModel(
    var defaultMatcher: FaceMatcher? = null
) : PromptDialogModel<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, ByteString?>() {

    object DialogType : PromptDialogModel.DialogType<FaceMatcherLivenessPromptDialogModel>
    override val dialogType: DialogType get() = DialogType

    /**
     * Request parameters for face liveness verification and capture.
     *
     * @property reason user-facing description of the verification reason.
     * @property matcher optional [FaceMatcher] to use, overriding [defaultMatcher].
     * @property faceMatcherLivenessSession optional active [FaceMatcherLivenessSession] driving liveness.
     * @property document optional document associated with this request.
     */
    data class FaceMatcherLivenessRequest(
        val reason: Reason = Reason.HumanReadable(
            title = "Check Liveness",
            subtitle = "Follow the prompts to confirm liveness and capture your photo",
            requireConfirmation = false
        ),
        val matcher: FaceMatcher? = null,
        val faceMatcherLivenessSession: FaceMatcherLivenessSession? = null,
        val document: Document? = null
    )
}
