package org.multipaz.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.camera.CameraFrame
import org.multipaz.context.AndroidUiContext
import org.multipaz.context.applicationContext
import org.multipaz.facematch.OverlayFrame
import org.multipaz.jpeg2000.decodeJpeg2000ToBitmap
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import kotlin.coroutines.CoroutineContext

private const val TAG = "Util"

actual fun getApplicationInfo(appId: String): ApplicationInfo {
    try {
        val ai = applicationContext.packageManager.getApplicationInfo(appId, 0)
        val icon = applicationContext.packageManager.getApplicationIcon(ai)
        return ApplicationInfo(
            name = applicationContext.packageManager.getApplicationLabel(ai).toString(),
            icon = icon.toBitmap().asImageBitmap()
        )
    } catch (e: NameNotFoundException) {
        throw IllegalArgumentException("Application not found", e)
    }
}

actual fun decodeImage(encodedData: ByteArray): ImageBitmap {
    val bitmap = BitmapFactory.decodeByteArray(encodedData, 0, encodedData.size)
    if (bitmap != null) {
        return bitmap.asImageBitmap()
    }
    try {
        val j2kBitmap = decodeJpeg2000ToBitmap(encodedData)
        if (j2kBitmap != null) {
            return j2kBitmap.asImageBitmap()
        }
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to decode JPEG 2000 image (${encodedData.size} bytes)", e)
    }
    Logger.e(TAG, "Failed to decode image (${encodedData.size} bytes)")
    return ImageBitmap(1, 1)
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

actual fun OverlayFrame.toImageBitmap(): ImageBitmap {
    val bitmap = platformHandle as? Bitmap
    if (bitmap != null) {
        return bitmap.asImageBitmap()
    }
    return ImageBitmap(width, height)
}

private tailrec fun Context.findActivityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findActivityWindow()
    else -> null
}

@Composable
internal actual fun ScreenBrightnessEffect(brightness: Float?) {
    val context = LocalContext.current
    val view = LocalView.current
    val activityWindow = remember(context) { context.findActivityWindow() }
    val dialogWindow = remember(view) { (view.parent as? DialogWindowProvider)?.window }

    val initialActivityBrightness = remember(activityWindow) {
        activityWindow?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    val initialDialogBrightness = remember(dialogWindow) {
        dialogWindow?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    LaunchedEffect(brightness, activityWindow, dialogWindow) {
        val target = brightness?.coerceIn(0.0f, 1.0f)
        activityWindow?.let { win ->
            win.attributes = win.attributes.apply {
                screenBrightness = target ?: initialActivityBrightness
            }
        }
        dialogWindow?.let { win ->
            win.attributes = win.attributes.apply {
                screenBrightness = target ?: initialDialogBrightness
            }
        }
    }

    DisposableEffect(activityWindow, dialogWindow) {
        onDispose {
            activityWindow?.let { win ->
                win.attributes = win.attributes.apply {
                    screenBrightness = initialActivityBrightness
                }
            }
            dialogWindow?.let { win ->
                win.attributes = win.attributes.apply {
                    screenBrightness = initialDialogBrightness
                }
            }
        }
    }
}

internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false
)

@Composable
internal actual fun FullScreenDialogEffect() {
    val context = LocalContext.current
    val view = LocalView.current
    val activityWindow = remember(context) { context.findActivityWindow() }
    val dialogWindow = remember(view) { (view.parent as? DialogWindowProvider)?.window }

    SideEffect {
        dialogWindow?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            win.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            win.setWindowAnimations(0)
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            @Suppress("DEPRECATION")
            win.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            win.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
                @Suppress("DEPRECATION")
                win.isStatusBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                win.attributes = win.attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            val controller = WindowCompat.getInsetsController(win, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        activityWindow?.let { win ->
            val controller = WindowCompat.getInsetsController(win, win.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(activityWindow) {
        onDispose {
            activityWindow?.let { win ->
                val controller = WindowCompat.getInsetsController(win, win.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
actual fun KeepScreenOnEffect() {
    val context = LocalContext.current
    val view = LocalView.current
    val activityWindow = remember(context) { context.findActivityWindow() }

    DisposableEffect(activityWindow, view) {
        val hadWindowFlag = activityWindow?.let {
            (it.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        } ?: false
        val hadViewKeepScreenOn = view.keepScreenOn

        activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true

        onDispose {
            if (!hadWindowFlag) {
                activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            view.keepScreenOn = hadViewKeepScreenOn
        }
    }
}




