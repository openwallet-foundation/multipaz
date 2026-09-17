package org.multipaz.compose.camera

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform-specific image from camera capture.
 */
expect class CameraImage {
    /**
     * Platform-specific handle (e.g. Android ImageProxy, iOS UIImage) or null.
     */
    val platformHandle: Any?

    /**
     * Converts the platform-specific image data into a [androidx.compose.ui.graphics.ImageBitmap].
     */
    fun toImageBitmap(): ImageBitmap
}