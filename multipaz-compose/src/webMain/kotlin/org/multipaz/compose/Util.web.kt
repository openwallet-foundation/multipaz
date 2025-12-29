package org.multipaz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.bytestring.ByteString
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.multipaz.compose.camera.CameraFrame
import org.multipaz.compose.camera.CameraImage
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI

actual fun getApplicationInfo(appId: String): ApplicationInfo {
    throw NotImplementedError("This information is not available not implemented on Js or WasmJS")
}

actual fun decodeImage(encodedData: ByteArray): ImageBitmap {
    return Image.makeFromEncoded(encodedData).toComposeImageBitmap()
}

actual fun encodeImageToPng(image: ImageBitmap): ByteString {
    val data = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG, 100)
        ?: throw IllegalStateException("Error encoding image to PNG")
    return ByteString(data.bytes)
}


actual fun cropRotateScaleImage(
    frameData: CameraFrame,
    cx: Double, // From top-left of image data, Y increases downwards.
    cy: Double, // From top-left of image data, Y increases downwards.
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): ImageBitmap {
    TODO("cropRotateScaleImage() not yet implemented")
}

actual fun ImageBitmap.cropRotateScaleImage(
    cx: Double,
    cy: Double,
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): ImageBitmap {
    TODO("ImageBitmap.cropRotateScaleImage() not yet implemented")
}

@Composable
actual fun rememberUiBoundCoroutineScope(
    getContext: @DisallowComposableCalls () -> CoroutineContext
): CoroutineScope = rememberCoroutineScope(getContext)

