package org.multipaz.facematch

/**
 * A camera video frame captured during user interaction.
 *
 * @property width width of the frame in pixels.
 * @property height height of the frame in pixels.
 * @property rotationDegrees rotation in degrees clockwise (0, 90, 180, 270) to orient the frame upright.
 * @property platformHandle optional platform-specific handle (e.g. Android `ImageProxy` or iOS `UIImage`)
 *   for zero-copy hardware acceleration if supported by the matcher.
 */
class CameraFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val platformHandle: Any? = null
) {
    constructor(
        width: Int,
        height: Int,
        rotationDegrees: Int
    ) : this(width, height, rotationDegrees, null)

    init {
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "rotationDegrees must be 0, 90, 180, or 270, got $rotationDegrees"
        }
    }

    /** Width in pixels after applying [rotationDegrees] to orient upright. */
    val uprightWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    /** Height in pixels after applying [rotationDegrees] to orient upright. */
    val uprightHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

