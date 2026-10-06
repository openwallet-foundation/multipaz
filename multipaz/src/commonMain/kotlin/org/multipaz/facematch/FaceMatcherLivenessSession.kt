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
 */
abstract class FaceMatcherLivenessSession {
    protected val _state = MutableStateFlow(FaceMatcherLivenessPromptState())

    /** Observable reactive state stream consumed by UI dialogs. */
    val state: StateFlow<FaceMatcherLivenessPromptState> = _state.asStateFlow()

    /**
     * Feeds a camera frame captured from the front camera into the liveness session.
     *
     * @param frame camera frame to process.
     */
    abstract suspend fun feedFrame(frame: CameraFrame)

    /**
     * Cancels the liveness session and releases any associated resources.
     */
    open fun cancel() {}

    /**
     * Atomically updates the prompt UI state.
     */
    protected fun updateState(
        message: String? = _state.value.message,
        status: FaceMatcherLivenessPromptState.Status = _state.value.status,
        overlay: OverlayFrame? = _state.value.overlay,
        capturedImage: ByteString? = _state.value.capturedImage
    ) {
        _state.value = FaceMatcherLivenessPromptState(
            message = message,
            status = status,
            overlay = overlay,
            capturedImage = if (status == FaceMatcherLivenessPromptState.Status.SUCCESS) capturedImage else null
        )
    }
}
