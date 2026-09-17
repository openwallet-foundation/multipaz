package org.multipaz.facematch

/**
 * Visual configuration for one of the 18 segments in the camera ring.
 *
 * @property color color of this segment.
 * @property scale thickness multiplier (1.0 = standard, >1.0 = expanded/bulging outward).
 */
data class RingSegment(
    val color: PromptColor = PromptColor.DARK_GRAY,
    val scale: Float = 1.0f
)
