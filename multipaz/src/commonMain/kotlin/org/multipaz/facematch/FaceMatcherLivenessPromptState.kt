package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * State of the face matcher liveness prompt dialog observed by the UI layer.
 *
 * @property messageAbove primary text displayed above the camera preview (e.g. instructions).
 * @property messageBelow secondary status or feedback text displayed below the camera preview.
 * @property outcome overall verification outcome.
 * @property overlay optional bitmap overlay to draw on top of the camera video stream.
 * @property capturedImage optional high-resolution portrait photo bytes captured during liveness enrollment.
 */
data class FaceMatcherLivenessPromptState(
    val messageAbove: String? = null,
    val messageBelow: String? = null,
    val outcome: Outcome = Outcome.IN_PROGRESS,
    val overlay: OverlayFrame? = null,
    val capturedImage: ByteString? = null
) {
    /** Overall outcome of the face liveness verification session. */
    enum class Outcome {
        IN_PROGRESS,
        PREPARE_FOR_PHOTO,
        SUCCESS,
        FAILED
    }
}
