package org.multipaz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.bytestring.ByteString
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.multipaz.compose.camera.CameraFrame
import org.multipaz.facematch.OverlayFrame
import org.multipaz.jpeg2000.decodeJpeg2000ToBufferedImage
import org.multipaz.util.Logger
import kotlin.coroutines.CoroutineContext

private const val TAG = "Util.jvm"

actual fun getApplicationInfo(appId: String): ApplicationInfo {
    throw NotImplementedError("ApplicationInfo is not available on JVM Desktop")
}

actual fun decodeImage(encodedData: ByteArray): ImageBitmap {
    try {
        return Image.makeFromEncoded(encodedData).toComposeImageBitmap()
    } catch (_: Throwable) {
    }
    try {
        val j2kImage = decodeJpeg2000ToBufferedImage(encodedData)
        if (j2kImage != null) {
            return j2kImage.toComposeImageBitmap()
        }
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to decode JPEG 2000 image (${encodedData.size} bytes)", e)
    }
    Logger.e(TAG, "Failed to decode image (${encodedData.size} bytes)")
    return ImageBitmap(1, 1)
}

actual fun encodeImageToPng(image: ImageBitmap): ByteString {
    val data = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG, 100)
        ?: throw IllegalStateException("Error encoding image to PNG")
    return ByteString(data.bytes)
}

actual fun cropRotateScaleImage(
    frameData: CameraFrame,
    cx: Double,
    cy: Double,
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): ImageBitmap {
    return frameData.cameraImage.toImageBitmap().cropRotateScaleImage(
        cx = cx,
        cy = cy,
        angleDegrees = angleDegrees,
        outputWidthPx = outputWidthPx,
        outputHeightPx = outputHeightPx,
        targetWidthPx = targetWidthPx
    )
}

actual fun ImageBitmap.cropRotateScaleImage(
    cx: Double,
    cy: Double,
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): ImageBitmap {
    val scale = targetWidthPx.toFloat() / outputWidthPx.toFloat()
    val finalHeightPx = (outputHeightPx * scale).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(targetWidthPx.coerceAtLeast(1), finalHeightPx)
    val canvas: Canvas = surface.canvas
    canvas.translate(targetWidthPx / 2f, finalHeightPx / 2f)
    canvas.scale(scale, scale)
    canvas.rotate(angleDegrees.toFloat())
    canvas.translate(-cx.toFloat(), -cy.toFloat())
    canvas.drawImage(Image.makeFromBitmap(this.asSkiaBitmap()), 0f, 0f)
    return surface.makeImageSnapshot().toComposeImageBitmap()
}

@Composable
actual fun rememberUiBoundCoroutineScope(
    getContext: @DisallowComposableCalls () -> CoroutineContext
): CoroutineScope = rememberCoroutineScope(getContext)

actual fun OverlayFrame.toImageBitmap(): ImageBitmap {
    return (platformHandle as? ImageBitmap) ?: ImageBitmap(width, height)
}

@Composable
internal actual fun ScreenBrightnessEffect(brightness: Float?) {
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun fullScreenDialogProperties(): DialogProperties =
    DialogProperties(
        usePlatformDefaultWidth = false,
        usePlatformInsets = false
    )

@Composable
internal actual fun FullScreenDialogEffect() {
}

@Composable
actual fun KeepScreenOnEffect() {
}
