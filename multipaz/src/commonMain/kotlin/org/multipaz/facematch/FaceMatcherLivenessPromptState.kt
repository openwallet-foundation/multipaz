package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * State of the face matcher liveness prompt dialog observed by the UI layer.
 *
 * @property messageAbove primary text displayed above the camera preview (e.g. instructions).
 * @property messageBelow secondary status or feedback text displayed below the camera preview.
 * @property status overall verification status.
 * @property overlay optional bitmap overlay to draw on top of the camera video stream.
 * @property capturedImage portrait photo bytes captured during liveness verification.
 * This property is set if - and only if - [status] is [Status.SUCCESS]. When set, the image
 * is the uncropped full camera frame encoded in JPEG format at the resolution of the camera stream
 * in upright portrait orientation. For all other statuses, this property is `null`.
 */
data class FaceMatcherLivenessPromptState(
    val messageAbove: String? = null,
    val messageBelow: String? = null,
    val status: Status = Status.IN_PROGRESS,
    val overlay: OverlayFrame? = null,
    val capturedImage: ByteString? = null
) {
    /** Overall status of the face liveness verification session. */
    enum class Status {
        /** Liveness challenge poses or face positioning are actively in progress. */
        IN_PROGRESS,

        /** Liveness verified and portrait photo successfully captured. */
        SUCCESS,

        /** Liveness verification failed or could not be completed. */
        FAILED
    }
}
