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
    init {
        require(isSupportedReferencePortraitFormat(referencePortrait)) {
            "Unsupported reference portrait format. Supported formats are PNG, JPEG, and JPEG 2000."
        }
    }

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

internal fun isSupportedReferencePortraitFormat(bytes: ByteString): Boolean {
    if (bytes.size < 3) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF

    // JPEG: starts with FF D8 FF
    if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
        return true
    }

    // PNG: starts with 89 50 4E 47 0D 0A 1A 0A
    if (bytes.size >= 8 &&
        b0 == 0x89 && b1 == 0x50 && b2 == 0x4E &&
        (bytes[3].toInt() and 0xFF) == 0x47 &&
        (bytes[4].toInt() and 0xFF) == 0x0D &&
        (bytes[5].toInt() and 0xFF) == 0x0A &&
        (bytes[6].toInt() and 0xFF) == 0x1A &&
        (bytes[7].toInt() and 0xFF) == 0x0A
    ) {
        return true
    }

    // JPEG 2000 JP2 format: starts with 00 00 00 0C 6A 50 20 20 0D 0A 87 0A
    if (bytes.size >= 12 &&
        b0 == 0x00 && b1 == 0x00 && b2 == 0x00 &&
        (bytes[3].toInt() and 0xFF) == 0x0C &&
        (bytes[4].toInt() and 0xFF) == 0x6A &&
        (bytes[5].toInt() and 0xFF) == 0x50 &&
        (bytes[6].toInt() and 0xFF) == 0x20 &&
        (bytes[7].toInt() and 0xFF) == 0x20 &&
        (bytes[8].toInt() and 0xFF) == 0x0D &&
        (bytes[9].toInt() and 0xFF) == 0x0A &&
        (bytes[10].toInt() and 0xFF) == 0x87 &&
        (bytes[11].toInt() and 0xFF) == 0x0A
    ) {
        return true
    }

    // JPEG 2000 raw codestream (J2K): starts with FF 4F FF 51
    if (bytes.size >= 4 &&
        b0 == 0xFF && b1 == 0x4F && b2 == 0xFF &&
        (bytes[3].toInt() and 0xFF) == 0x51
    ) {
        return true
    }

    return false
}

