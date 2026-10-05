package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * An uncompressed ARGB bitmap overlay rendered on top of the camera video stream during face matching.
 *
 * Each pixel in [argb] is a 32-bit packed color in ARGB order (0xAARRGGBB):
 * - bits 24..31: alpha (0 = transparent, 255 = fully opaque)
 * - bits 16..23: red
 * - bits 8..15: green
 * - bits 0..7: blue
 *
 * @property width width of the overlay in pixels.
 * @property height height of the overlay in pixels.
 * @property argb 32-bit packed ARGB pixel data array of size `width * height`.
 * @property isMirrored whether the horizontal axis should be mirrored to match a mirrored camera preview.
 */
class OverlayFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val isMirrored: Boolean = false
) {
    init {
        require(width > 0) { "width must be positive, got $width" }
        require(height > 0) { "height must be positive, got $height" }
        require(argb.size == width * height) {
            "argb array size (${argb.size}) must match width * height ($width * $height = ${width * height})"
        }
    }

    constructor(
        width: Int,
        height: Int,
        argb: IntArray
    ) : this(width, height, argb, false)

    /**
     * Returns the ARGB pixel value at ([x], [y]).
     */
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    /**
     * Sets the ARGB pixel value at ([x], [y]).
     */
    operator fun set(x: Int, y: Int, color: Int) {
        argb[y * width + x] = color
    }

    /**
     * Converts the ARGB integer buffer into a byte array in native byte order.
     */
    fun toByteArray(): ByteArray {
        val bytes = ByteArray(argb.size * 4)
        var bi = 0
        for (pixel in argb) {
            bytes[bi++] = (pixel and 0xFF).toByte()
            bytes[bi++] = ((pixel ushr 8) and 0xFF).toByte()
            bytes[bi++] = ((pixel ushr 16) and 0xFF).toByte()
            bytes[bi++] = ((pixel ushr 24) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Converts the ARGB integer buffer into a ByteString.
     */
    fun toByteString(): ByteString = ByteString(toByteArray())
}
