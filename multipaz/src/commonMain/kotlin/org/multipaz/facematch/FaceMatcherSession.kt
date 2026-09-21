package org.multipaz.facematch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.bytestring.ByteString

/**
 * Session driving the interactive face matching and liveness verification UI.
 *
 * A fresh session instance is created per verification attempt via [FaceMatcher.createSession].
 * It directly controls the messages above and below the camera preview, as well as the
 * colors and scales of the 18 ring segments surrounding the preview.
 *
 * @property referencePortrait reference portrait image bytes to verify against.
 */
abstract class FaceMatcherSession(
    val referencePortrait: ByteString? = null
) {
    /**
     * Whether this session optionally supplies graphics to overlay on top of the matching video stream.
     */
    open val providesGraphicsOverlay: Boolean
        get() = false

    protected val _state = MutableStateFlow(FaceMatcherPromptState())

    /** Observable reactive state stream consumed by Compose and SwiftUI dialogs. */
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
        ringSegments: List<RingSegment> = _state.value.ringSegments,
        outcome: FaceMatcherPromptState.Outcome = _state.value.outcome,
        graphicsOverlay: FaceMatcherGraphics? = _state.value.graphicsOverlay,
        capturedImage: ByteString? = _state.value.capturedImage
    ) {
        _state.value = FaceMatcherPromptState(
            messageAbove = messageAbove,
            messageBelow = messageBelow,
            ringSegments = ringSegments,
            outcome = outcome,
            graphicsOverlay = graphicsOverlay,
            capturedImage = capturedImage
        )
    }

    /**
     * Sets all 18 ring segments to a uniform color and scale.
     */
    protected fun setAllRingSegments(color: PromptColor, scale: Float = 1.0f) {
        updateState(
            ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                RingSegment(color = color, scale = scale)
            }
        )
    }

    /**
     * Computes a list of 18 segments with directional highlight indicating where the user
     * should look and their progress towards that goal, with a smooth falloff fade at the
     * edges of the colored region.
     *
     * @param direction target direction.
     * @param progress progress from 0.0f (started looking) to 1.0f (target pose achieved).
     * @param activeColor color of segments in the active direction.
     * @param baseColor base color of inactive segments.
     * @return updated list of 18 [RingSegment]s.
     */
    protected fun computeDirectionSegments(
        direction: RingDirection,
        progress: Float,
        activeColor: PromptColor = PromptColor.BRIGHT_GREEN,
        baseColor: PromptColor = PromptColor.DARK_GRAY
    ): List<RingSegment> {
        val p = progress.coerceIn(0f, 1f)
        if (direction == RingDirection.CENTER) {
            val color = PromptColor.lerp(baseColor, activeColor, p)
            val scale = 1.0f + 0.5f * p
            return List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                RingSegment(color = color, scale = scale)
            }
        }

        // Center index around the 18 segments for each direction
        // Index 0 is 12 o'clock (-90°), 4.5 is 3 o'clock (0°), 9 is 6 o'clock (90°), 13.5 is 9 o'clock (180°)
        val targetCenter = when (direction) {
            RingDirection.UP -> 0.0f
            RingDirection.RIGHT -> 4.5f
            RingDirection.DOWN -> 9.0f
            RingDirection.LEFT -> 13.5f
            RingDirection.CENTER -> 0.0f
        }

        // Maximum angular distance (in segments) where the highlight fades to 0
        val maxRadius = 3.5f
        val minDistance = when (direction) {
            RingDirection.LEFT, RingDirection.RIGHT -> 0.5f
            else -> 0.0f
        }
        val peakFalloff = (0.5f * (1.0f + kotlin.math.cos((minDistance / maxRadius) * kotlin.math.PI))).toFloat()

        return List(FaceMatcherPromptState.NUM_RING_SEGMENTS) { index ->
            val rawDiff = kotlin.math.abs(index.toFloat() - targetCenter)
            val dist = minOf(rawDiff, 18f - rawDiff)

            if (dist < maxRadius) {
                val rawFalloff = (0.5f * (1.0f + kotlin.math.cos((dist / maxRadius) * kotlin.math.PI))).toFloat()
                val normalizedFalloff = (rawFalloff / peakFalloff).coerceIn(0f, 1f)
                val segmentProgress = (p * normalizedFalloff).coerceIn(0f, 1f)
                val color = PromptColor.lerp(baseColor, activeColor, segmentProgress)
                val scale = 1.0f + 0.55f * segmentProgress
                RingSegment(color = color, scale = scale)
            } else {
                RingSegment(color = baseColor, scale = 1.0f)
            }
        }
    }
}
