package org.multipaz.compose.camera

import kotlinx.coroutines.CancellationException
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.compose.foundation.layout.add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.multipaz.util.Logger
import java.util.concurrent.Executors
import kotlin.collections.isNotEmpty
import kotlin.collections.toTypedArray

private const val TAG = "Camera"

@OptIn(TransformExperimental::class)
@Composable
actual fun Camera(
    modifier: Modifier,
    cameraSelection: CameraSelection,
    captureResolution: CameraCaptureResolution,
    showCameraPreview: Boolean,
    onFrameCaptured: suspend (frame: CameraFrame) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var activePreviewView by remember { mutableStateOf<PreviewView?>(null) }
    var currentDisplayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }

    DisposableEffect(Unit) { // Keyed by Unit to run once and clean up with the composable
        val orientationEventListener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                val newSurfaceRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270 // Landscape, rotated left
                    in 135..224 -> Surface.ROTATION_180 // Upside down
                    in 225..314 -> Surface.ROTATION_90  // Landscape, rotated right
                    else -> Surface.ROTATION_0 // Portrait
                }
                if (currentDisplayRotation != newSurfaceRotation) {
                    Logger.d(TAG, "Orientation changed. New display rotation for target: $newSurfaceRotation")
                    currentDisplayRotation = newSurfaceRotation
                }
            }
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
        onDispose {
            orientationEventListener.disable()
        }
    }

    DisposableEffect(cameraSelection, showCameraPreview, activePreviewView, currentDisplayRotation) {
        val cameraProvider = cameraProviderFuture.get()

        if (!cameraProviderFuture.isDone) {
            onDispose { /** Not bound. */ }
        } else {
            cameraProvider.unbindAll()

            val resolutionStrategy = ResolutionStrategy(
                captureResolution.getDimensions(),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
            )
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(resolutionStrategy)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(currentDisplayRotation)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                runBlocking {
                    val transformationProxy = if (showCameraPreview && activePreviewView != null) {
                        withContext(Dispatchers.Main) {
                            if (activePreviewView?.surfaceProvider != null && activePreviewView?.outputTransform != null) {
                                getCorrectionMatrix(imageProxy, activePreviewView!!)
                            } else {
                                Matrix() // Fallback if preview is not ready.
                            }
                        }
                    } else {
                        Matrix()
                    }
                    val frame = CameraFrame(
                        cameraImage = CameraImage(imageProxy),
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rotation = calculateDetectorAngle(currentDisplayRotation, cameraSelection),
                        previewTransformation = transformationProxy
                    )
                    onFrameCaptured(frame)
                }
                imageProxy.close()
            }

            try {
                if (showCameraPreview && activePreviewView != null) {
                    val preview = Preview.Builder()
                        .build()
                        .also {
                            activePreviewView!!.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            it.setSurfaceProvider(activePreviewView!!.surfaceProvider)
                        }
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelection.toAndroidCameraSelector(),
                        preview,
                        imageAnalysis
                    )
                } else {
                    // Image Analysis only if preview is not shown.
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelection.toAndroidCameraSelector(),
                        imageAnalysis
                    )
                }
            } catch (exc: Exception) {
                if (exc is CancellationException) throw exc
                Logger.e(TAG, "Use case binding failed", exc)
            }

            onDispose {
                cameraProvider.unbindAll()
            }
        }
    }

    if (showCameraPreview) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    activePreviewView = this
                }
            },
            onRelease = {
                activePreviewView = null
            }
        )
    } else {
        if (activePreviewView != null) {
            // This ensures the DisposableEffect re-runs if the view was previously shown.
            activePreviewView = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Logger.d(TAG, "Shutting down camera executor.")
            if (!executor.isShutdown) {
                executor.shutdown()
            }
        }
    }
}

/** Required as MLKit face detector accepts only bitmaps with upright face composition. */
private fun calculateDetectorAngle(currentDisplayRotation: Int, cameraSelection: CameraSelection): Int {
    return when (currentDisplayRotation) {
        Surface.ROTATION_0 -> if (cameraSelection.isMirrored()) 0 else 180
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> if (cameraSelection.isMirrored()) 180 else 0
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

private fun CameraCaptureResolution.getDimensions(): Size {
    return when (this) {
        CameraCaptureResolution.LOW -> Size(640, 480)
        CameraCaptureResolution.MEDIUM -> Size(1280, 720)
        CameraCaptureResolution.HIGH -> Size(1920, 1080)
    }
}

private fun CameraSelection.toAndroidCameraSelector() =
    when (this) {
        CameraSelection.DEFAULT_BACK_CAMERA -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraSelection.DEFAULT_FRONT_CAMERA -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

@OptIn(TransformExperimental::class)
private fun getCorrectionMatrix(imageProxy: ImageProxy, previewView: PreviewView): Matrix {

    // This matrix maps (-1, -1) -> (1, 1) space to preview-coordinate system. This includes
    // any scaling, rotation, or cropping that's done in the preview.
    val matrix = previewView.outputTransform!!.matrix
    val composeMatrix = Matrix()
    composeMatrix.setFrom(matrix)

    // By the scale and translate below, we modify the matrix so it maps
    // (0, 0) -> (width, height) of the frame to analyze to the preview
    // coordinate system
    composeMatrix.scale(2f / imageProxy.width, 2f / imageProxy.height, 1f)
    composeMatrix.translate(-0.5f * imageProxy.width, -0.5f * imageProxy.height, 1.0f)

    return composeMatrix
}
