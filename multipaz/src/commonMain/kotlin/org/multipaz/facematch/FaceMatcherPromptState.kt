package org.multipaz.facematch

/**
 * State of the face matcher prompt dialog observed by the UI layer.
 *
 * @property messageAbove primary text displayed above the camera preview (e.g. instructions).
 * @property messageBelow secondary status or feedback text displayed below the camera preview.
 * @property ringSegments visual configuration for the 18 segments of the ring (indexed 0 to 17
 * clockwise from 12 o'clock).
 * @property outcome overall verification outcome.
 * @property graphicsOverlay optional vector graphics to overlay on top of the camera video stream.
 */
data class FaceMatcherPromptState(
    val messageAbove: String? = null,
    val messageBelow: String? = null,
    val ringSegments: List<RingSegment> = defaultSegments,
    val outcome: Outcome = Outcome.IN_PROGRESS,
    val graphicsOverlay: FaceMatcherGraphics? = null
) {
    /** Overall outcome of the face verification session. */
    enum class Outcome {
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }

    companion object {
        /** Total number of segments around the perimeter (18 segments). */
        const val NUM_RING_SEGMENTS = 18

        /** Default ring segment configuration (all neutral dark gray). */
        val defaultSegments: List<RingSegment> = List(NUM_RING_SEGMENTS) {
            RingSegment(color = PromptColor.DARK_GRAY, scale = 1.0f)
        }
    }
}
