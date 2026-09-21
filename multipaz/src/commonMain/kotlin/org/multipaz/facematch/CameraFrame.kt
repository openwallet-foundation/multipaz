package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * Pixel format of image data in a [CameraFrame].
 */
enum class PixelFormat {
    RGBA,
    YUV_420_888,
    NV21,
    JPEG,
    UNKNOWN
}

/**
 * A camera video frame captured during user interaction.
 *
 * @property width width of the frame in pixels.
 * @property height height of the frame in pixels.
 * @property rotationDegrees rotation in degrees clockwise (0, 90, 180, 270) to orient the frame upright.
 * @property pixelFormat the format of the pixel data in [data].
 * @property data raw pixel bytes, plane bytes, or encoded image bytes.
 * @property platformHandle optional platform-specific handle (e.g. Android `ImageProxy` or iOS `CVPixelBuffer`)
 *   for zero-copy hardware acceleration if supported by the matcher.
 */
class CameraFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val pixelFormat: PixelFormat = PixelFormat.UNKNOWN,
    val data: ByteString = ByteString(),
    val platformHandle: Any? = null
) {
    constructor(
        width: Int,
        height: Int,
        rotationDegrees: Int
    ) : this(width, height, rotationDegrees, PixelFormat.UNKNOWN, ByteString(), null)

    constructor(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        pixelFormat: PixelFormat,
        data: ByteString
    ) : this(width, height, rotationDegrees, pixelFormat, data, null)

    /** Width in pixels after applying [rotationDegrees] to orient upright. */
    val uprightWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    /** Height in pixels after applying [rotationDegrees] to orient upright. */
    val uprightHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

