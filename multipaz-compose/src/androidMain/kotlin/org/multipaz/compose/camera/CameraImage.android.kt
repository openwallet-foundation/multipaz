package org.multipaz.compose.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android-specific implementation of [org.multipaz.compose.camera.CameraImage].
 *
 * @param imageProxy the [ImageProxy] representing the image.
 */
actual data class CameraImage(val imageProxy: ImageProxy) {
    actual val platformHandle: Any?
        get() = imageProxy

    actual fun toImageBitmap(): ImageBitmap {
        val bitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees == 0) {
            return bitmap.asImageBitmap()
        }
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        return rotatedBitmap.asImageBitmap()
    }
}