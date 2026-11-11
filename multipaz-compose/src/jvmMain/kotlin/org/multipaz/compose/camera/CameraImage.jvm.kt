package org.multipaz.compose.camera

import androidx.compose.ui.graphics.ImageBitmap

actual class CameraImage(
    private val bitmap: ImageBitmap = ImageBitmap(1, 1)
) {
    actual val platformHandle: Any?
        get() = bitmap

    actual fun toImageBitmap(): ImageBitmap = bitmap
}
