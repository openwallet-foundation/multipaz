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
     * Cancels the verification session and releases any associated resources.
     */
    open fun cancel() {}

    /**
     * Atomically updates the prompt UI state.
     */
    protected fun updateState(
        messageAbove: String? = _state.value.messageAbove,
        messageBelow: String? = _state.value.messageBelow,
        status: FaceMatcherPromptState.Status = _state.value.status,
        overlay: OverlayFrame? = _state.value.overlay
    ) {
        _state.value = FaceMatcherPromptState(
            messageAbove = messageAbove,
            messageBelow = messageBelow,
            status = status,
            overlay = overlay
        )
    }
}

