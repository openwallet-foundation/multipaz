package org.multipaz.facenet

/**
 * Visual feedback segment in the biometric verification prompt overlay.
 *
 * @property color 32-bit ARGB color integer.
 * @property scale stroke width scaling factor relative to the base stroke.
 */
data class RingSegment(
    val color: Int,
    val scale: Float = 1.0f
) {
    companion object {
        const val NUM_SEGMENTS = 18

        const val COLOR_DARK_GRAY = 0xFF424242.toInt()
        const val COLOR_BLUE = 0xFF2979FF.toInt()
        const val COLOR_GREEN = 0xFF2E7D32.toInt()
        const val COLOR_BRIGHT_GREEN = 0xFF69F0AE.toInt()
        const val COLOR_RED = 0xFFFF5252.toInt()

        /**
         * Linearly interpolates between two 32-bit ARGB colors [c1] and [c2] by [t] in [0, 1].
         */
        fun lerpColor(c1: Int, c2: Int, t: Float): Int {
            val a1 = (c1 ushr 24) and 0xFF
            val r1 = (c1 ushr 16) and 0xFF
            val g1 = (c1 ushr 8) and 0xFF
            val b1 = c1 and 0xFF
            val a2 = (c2 ushr 24) and 0xFF
            val r2 = (c2 ushr 16) and 0xFF
            val g2 = (c2 ushr 8) and 0xFF
            val b2 = c2 and 0xFF
            val a = (a1 + (a2 - a1) * t).toInt().coerceIn(0, 255)
            val r = (r1 + (r2 - r1) * t).toInt().coerceIn(0, 255)
            val g = (g1 + (g2 - g1) * t).toInt().coerceIn(0, 255)
            val b = (b1 + (b2 - b1) * t).toInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val defaultSegments: List<RingSegment> = List(NUM_SEGMENTS) {
            RingSegment(color = COLOR_DARK_GRAY, scale = 1.0f)
        }
    }
}
