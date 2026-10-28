package org.multipaz.facematch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.bytestring.ByteString

/**
 * Session driving the interactive face liveness verification and photo capture UI.
 *
 * A fresh session instance is created per liveness attempt via [FaceMatcher.createLivenessSession].
 * It directly controls the messages above and below the camera preview, the overlay, and verification status.
 *
 * @property poses ordered list of [FacePose]s to capture during the session. Must not be empty.
 */
abstract class FaceMatcherLivenessSession(
    val poses: List<FacePose> = listOf(FacePose.FRONT)
) {
    init {
        require(poses.isNotEmpty()) { "At least one pose must be specified" }
    }

    protected val _state = MutableStateFlow(FaceMatcherLivenessPromptState())

    /** Observable reactive state stream consumed by UI dialogs. */
    val state: StateFlow<FaceMatcherLivenessPromptState> = _state.asStateFlow()

    /**
     * Feeds a camera frame captured from the front camera into the liveness session.
     *
     * This is not called if [FaceMatcher.usesCustomUserInterface] is `true`.
     *
     * @param frame camera frame to process.
     */
    open suspend fun feedFrame(frame: CameraFrame) {}

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
     * Cancels the liveness session and releases any associated resources.
     */
    open fun cancel() {}

    /**
     * Atomically updates the prompt UI state.
     *
     * @param message instructional or status message displayed to the user, or `null`.
     * @param status overall verification status.
     * @param overlay optional bitmap overlay to draw on top of the camera video stream, or `null`.
     * @param capturedImages map of captured portrait photo bytes keyed by [FacePose] when [status] is
     * [FaceMatcherLivenessPromptState.Status.SUCCESS], or empty.
     * @param backgroundColor optional background color for the liveness screen, or `null` for default.
     */
    protected fun updateState(
        message: String? = _state.value.message,
        status: FaceMatcherLivenessPromptState.Status = _state.value.status,
        overlay: OverlayFrame? = _state.value.overlay,
        capturedImages: Map<FacePose, ByteString> = _state.value.capturedImages,
        backgroundColor: Color? = _state.value.backgroundColor
    ) {
        _state.value = FaceMatcherLivenessPromptState(
            message = message,
            status = status,
            overlay = overlay,
            capturedImages = if (status == FaceMatcherLivenessPromptState.Status.SUCCESS) capturedImages else emptyMap(),
            backgroundColor = backgroundColor
        )
    }
}
