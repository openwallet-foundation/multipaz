package org.multipaz.jpeg2000

import org.multipaz.util.Logger

private const val TAG = "OpenJpegJni"

internal expect object NativeLoader {
    fun ensureLoaded()
}

internal object OpenJpegJni {
    @JvmStatic
    external fun decodeNative(encodedData: ByteArray, outDimensions: IntArray): IntArray?
}

actual fun decodeJpeg2000(encodedData: ByteArray): DecodedJpeg2000Image? {
    if (!isJpeg2000(encodedData)) {
        return null
    }
    return try {
        NativeLoader.ensureLoaded()
        val dimensions = IntArray(2)
        val pixels = OpenJpegJni.decodeNative(encodedData, dimensions) ?: return null
        val width = dimensions[0]
        val height = dimensions[1]
        if (width <= 0 || height <= 0 || pixels.size != width * height) {
            return null
        }
        DecodedJpeg2000Image(width = width, height = height, argbPixels = pixels)
    } catch (e: Throwable) {
        Logger.w(TAG, "Error decoding JPEG 2000 image via OpenJPEG", e)
        null
    }
}
