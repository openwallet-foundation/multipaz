package org.multipaz.jpeg2000

/**
 * Decoded JPEG 2000 image containing dimensions and packed 32-bit `0xAARRGGBB` pixels.
 *
 * @property width Image width in pixels.
 * @property height Image height in pixels.
 * @property argbPixels Row-major pixel array of size `width * height` in `0xAARRGGBB` format.
 */
class DecodedJpeg2000Image(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedJpeg2000Image) return false
        return width == other.width &&
            height == other.height &&
            argbPixels.contentEquals(other.argbPixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + argbPixels.contentHashCode()
        return result
    }
}

/**
 * Decodes JPEG 2000 encoded image data (either a `.jp2` container or a raw `.j2k`/`.j2c` codestream)
 * into a [DecodedJpeg2000Image] with `0xAARRGGBB` pixels, or returns `null` if decoding fails.
 *
 * @param encodedData Raw JPEG 2000 bytes.
 * @return [DecodedJpeg2000Image] on success, or `null` if the input is not valid JPEG 2000 data.
 */
expect fun decodeJpeg2000(encodedData: ByteArray): DecodedJpeg2000Image?

/**
 * Returns `true` if [data] starts with a JPEG 2000 JP2 box signature
 * (`00 00 00 0C 6A 50 20 20 0D 0A 87 0A`) or a raw J2K codestream SOC+SIZ marker (`FF 4F FF 51`).
 */
fun isJpeg2000(data: ByteArray): Boolean {
    if (data.size >= 12 &&
        data[0] == 0x00.toByte() &&
        data[1] == 0x00.toByte() &&
        data[2] == 0x00.toByte() &&
        data[3] == 0x0C.toByte() &&
        data[4] == 0x6A.toByte() &&
        data[5] == 0x50.toByte() &&
        data[6] == 0x20.toByte() &&
        data[7] == 0x20.toByte() &&
        data[8] == 0x0D.toByte() &&
        data[9] == 0x0A.toByte() &&
        data[10] == 0x87.toByte() &&
        data[11] == 0x0A.toByte()
    ) {
        return true
    }
    if (data.size >= 4 &&
        data[0] == 0xFF.toByte() &&
        data[1] == 0x4F.toByte() &&
        data[2] == 0xFF.toByte() &&
        data[3] == 0x51.toByte()
    ) {
        return true
    }
    return false
}
