package org.multipaz.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.camera.CameraFrame
import org.multipaz.context.AndroidUiContext
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import kotlin.coroutines.CoroutineContext

private const val TAG = "Util"

actual fun getApplicationInfo(appId: String): ApplicationInfo {
    val ai = applicationContext.packageManager.getApplicationInfo(appId, 0)
    val icon = applicationContext.packageManager.getApplicationIcon(ai)
    return ApplicationInfo(
        name = applicationContext.packageManager.getApplicationLabel(ai).toString(),
        icon = icon.toBitmap().asImageBitmap()
    )
}

actual fun decodeImage(encodedData: ByteArray): ImageBitmap {
    val bitmap = BitmapFactory.decodeByteArray(encodedData, 0, encodedData.size)
    if (bitmap == null) {
        Logger.e(TAG, "Failed to decode image (${encodedData.size} bytes)")
        return ImageBitmap(1, 1)
    }
    return bitmap.asImageBitmap()
}

actual fun encodeImageToPng(image: ImageBitmap): ByteString {
    val bitmap = image.asAndroidBitmap()
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
    return ByteString(baos.toByteArray())
}

private val paint = Paint().apply {
    isAntiAlias = false
    isFilterBitmap = false // Improves scaling quality
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
    return frameData.cameraImage.imageProxy.toBitmap().cropRotateScaleImage(
        cx = cx,
        cy = cy,
        angleDegrees = angleDegrees,
        outputWidthPx = outputWidthPx,
        outputHeightPx = outputHeightPx,
        targetWidthPx = targetWidthPx
    ).asImageBitmap()
}

private fun Bitmap.cropRotateScaleImage(
    cx: Double,
    cy: Double,
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): Bitmap {
    val finalScale = targetWidthPx.toFloat() / outputWidthPx.toFloat()
    val finalOutputHeight = (outputHeightPx * finalScale).toInt()
    val matrix = Matrix() // Use Android's Matrix

    matrix.postTranslate(-cx.toFloat(), -cy.toFloat())
    matrix.postRotate(angleDegrees.toFloat())
    matrix.postTranslate((outputWidthPx / 2).toFloat(), (outputHeightPx / 2).toFloat())
    matrix.postScale(finalScale, finalScale)

    // Create the output bitmap with the final scaled dimensions.
    val resultBitmap = createBitmap(targetWidthPx, finalOutputHeight, config ?: Bitmap.Config.ARGB_8888)
    Canvas(resultBitmap).drawBitmap(this, matrix, paint)
    return resultBitmap
}

actual fun ImageBitmap.cropRotateScaleImage(
    cx: Double,
    cy: Double,
    angleDegrees: Double,
    outputWidthPx: Int,
    outputHeightPx: Int,
    targetWidthPx: Int
): ImageBitmap {
    return asAndroidBitmap().cropRotateScaleImage(
        cx = cx,
        cy = cy,
        angleDegrees = angleDegrees,
        outputWidthPx = outputWidthPx,
        outputHeightPx = outputHeightPx,
        targetWidthPx = targetWidthPx
    ).asImageBitmap()
}

@Composable
actual fun rememberUiBoundCoroutineScope(
    getContext: @DisallowComposableCalls () -> CoroutineContext
): CoroutineScope {
    val context = LocalContext.current
    return rememberCoroutineScope { getContext() + AndroidUiContext(context) }
}