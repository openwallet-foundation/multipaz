package org.multipaz.facematch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.bytestring.ByteString

/**
 * Session driving the interactive face matching verification UI against a reference portrait.
 *
 * A fresh session instance is created per verification attempt via [FaceMatcher.createSession].
 * It directly controls the messages above and below the camera preview, the overlay, and verification status.
 *
 * @property referencePortrait reference portrait image bytes in PNG, JPEG, or JPEG 2000 format to verify against.
 */
abstract class FaceMatcherSession(
    val referencePortrait: ByteString
) {
    protected val _state = MutableStateFlow(FaceMatcherPromptState())

    /** Observable reactive state stream consumed by UI dialogs. */
    val state: StateFlow<FaceMatcherPromptState> = _state.asStateFlow()

    /**
     * Feeds a camera frame captured from the front camera into the matcher session.
     *
     * @param frame camera frame to process.
     */
    abstract suspend fun feedFrame(frame: CameraFrame)

    /**
     * Pushes a touch or tap event within the camera preview into the session.
     *
     * Coordinates are normalized to [0.0, 1.0] where (0.0, 0.0) is the top-left and
     * (1.0, 1.0) is the bottom-right of the camera preview area.
     *
     * @param x horizontal position in normalized preview coordinates [0.0, 1.0].
     * @param y vertical position in normalized preview coordinates [0.0, 1.0].
     */
    open fun onTouchEvent(x: Float, y: Float) {}

    /**
     * Cancels the verification session and releases any associated resources.
     */
    open fun cancel() {}

    /**
     * Atomically updates the prompt UI state.
     *
     * @param message instructional or status message displayed to the user, or `null`.
     * @param status overall verification status.
     * @param overlay optional bitmap overlay to draw on top of the camera video stream, or `null`.
     */
    protected fun updateState(
        message: String? = _state.value.message,
        status: FaceMatcherPromptState.Status = _state.value.status,
        overlay: OverlayFrame? = _state.value.overlay
    ) {
        _state.value = FaceMatcherPromptState(
            message = message,
            status = status,
            overlay = overlay
        )
    }
}

