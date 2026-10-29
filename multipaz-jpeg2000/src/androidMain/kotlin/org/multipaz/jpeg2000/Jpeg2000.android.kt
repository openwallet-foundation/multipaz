package org.multipaz.jpeg2000

import android.graphics.Bitmap

internal actual object NativeLoader {
    private val loaded by lazy {
        System.loadLibrary("multipaz_openjpeg_jni")
        true
    }

    actual fun ensureLoaded() {
        loaded
    }
}

/**
 * Decodes JPEG 2000 encoded image data (either `.jp2` or `.j2k`) into an Android [Bitmap]
 * with [Bitmap.Config.ARGB_8888], or returns `null` if decoding fails.
 */
fun decodeJpeg2000ToBitmap(encodedData: ByteArray): Bitmap? {
    val decoded = decodeJpeg2000(encodedData) ?: return null
    return Bitmap.createBitmap(
        decoded.argbPixels,
        decoded.width,
        decoded.height,
        Bitmap.Config.ARGB_8888
    )
}
