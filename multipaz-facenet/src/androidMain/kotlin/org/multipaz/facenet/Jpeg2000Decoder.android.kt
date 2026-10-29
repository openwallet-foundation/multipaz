package org.multipaz.facenet

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal fun decodePortraitBytesToBitmap(bytes: ByteArray): Bitmap? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap != null) {
        return bitmap
    }
    return decodeJpeg2000ToBitmap(bytes)
}

internal fun decodeJpeg2000ToBitmap(bytes: ByteArray): Bitmap? =
    org.multipaz.jpeg2000.decodeJpeg2000ToBitmap(bytes)
