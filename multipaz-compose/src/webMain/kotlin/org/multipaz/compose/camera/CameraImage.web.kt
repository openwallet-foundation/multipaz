package org.multipaz.compose.camera

import androidx.compose.ui.graphics.ImageBitmap

actual class CameraImage {
    actual val platformHandle: Any?
        get() = null

    actual fun toImageBitmap(): ImageBitmap {
        TODO("Not yet implemented")
    }
}