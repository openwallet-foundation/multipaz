package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * A bitmap overlay rendered on top of the camera video stream during face matching.
 *
 * @property width width of the overlay in pixels.
 * @property height height of the overlay in pixels.
 * @property platformHandle optional platform-specific handle (e.g. Android `Bitmap`, iOS `UIImage`,
 *   or JVM `BufferedImage`) for zero-copy rendering.
 * @property data optional raw image bytes (e.g. for testing or headless pipelines).
 */
class OverlayFrame(
    val width: Int,
    val height: Int,
    val platformHandle: Any? = null,
    val data: ByteString? = null
) {
    init {
        require(width > 0) { "width must be positive, got $width" }
        require(height > 0) { "height must be positive, got $height" }
    }

    constructor(
        width: Int,
        height: Int,
        platformHandle: Any?
    ) : this(width, height, platformHandle, null)
}
