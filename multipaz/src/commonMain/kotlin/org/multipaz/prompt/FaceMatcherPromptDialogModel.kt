package org.multipaz.prompt

import kotlinx.io.bytestring.ByteString
import org.multipaz.document.Document
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherSession

/**
 * Prompt dialog model for verifying user identity with a face matcher.
 *
 * @param defaultMatcher default [FaceMatcher] to use if not specified in the request.
 */
class FaceMatcherPromptDialogModel(
    var defaultMatcher: FaceMatcher? = null
) : PromptDialogModel<FaceMatcherPromptDialogModel.FaceMatcherRequest, Boolean>() {

    object DialogType : PromptDialogModel.DialogType<FaceMatcherPromptDialogModel>
    override val dialogType: DialogType get() = DialogType

    /**
     * Request parameters for face matching.
     *
     * @property referencePortrait the reference portrait image bytes.
     * @property reason user-facing description of the verification reason.
     * @property matcher optional [FaceMatcher] to use, overriding [defaultMatcher].
     * @property document optional document being verified.
     */
    data class FaceMatcherRequest(
        val referencePortrait: ByteString? = null,
        val reason: Reason = Reason.HumanReadable(
            title = "Verify it's you",
            subtitle = "Look at the camera to verify your identity",
            requireConfirmation = false
        ),
        val matcher: FaceMatcher? = null,
        val faceMatcherSession: FaceMatcherSession? = null,
        val document: Document? = null
    )
}
