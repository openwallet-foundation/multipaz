package org.multipaz.compose.qrcode

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import org.multipaz.SwiftBridge
import org.multipaz.compose.camera.toSkiaImage
import platform.UIKit.UIImage

@OptIn(ExperimentalForeignApi::class)
actual fun generateQrCode(
    url: String,
): ImageBitmap {
    return (SwiftBridge.generateQrCode(url) as UIImage?)?.toSkiaImage()?.toComposeImageBitmap()
        ?: throw IllegalStateException()
}
