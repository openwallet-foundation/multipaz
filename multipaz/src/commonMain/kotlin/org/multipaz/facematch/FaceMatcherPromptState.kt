package org.multipaz.facematch

/**
 * State of the face matcher prompt dialog observed by the UI layer during verification.
 *
 * @property messageAbove primary text displayed above the camera preview (e.g. instructions).
 * @property messageBelow secondary status or feedback text displayed below the camera preview.
 * @property status overall verification status.
 * @property overlay optional bitmap overlay to draw on top of the camera video stream.
 */
data class FaceMatcherPromptState(
    val messageAbove: String? = null,
    val messageBelow: String? = null,
    val status: Status = Status.IN_PROGRESS,
    val overlay: OverlayFrame? = null
) {
    /** Overall status of the face verification session. */
    enum class Status {
        /** Verification is actively in progress. */
        IN_PROGRESS,

        /** Face matched successfully against the reference portrait. */
        SUCCESS,

        /** Face matching failed or could not be completed. */
        FAILED
    }
}
