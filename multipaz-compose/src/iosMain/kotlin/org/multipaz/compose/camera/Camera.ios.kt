package org.multipaz.compose.camera

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.multipaz.util.Logger
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.position
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIImage
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

private const val TAG = "Camera"

@Composable
actual fun Camera(
    modifier: Modifier,
    cameraSelection: CameraSelection,
    captureResolution: CameraCaptureResolution,
    showCameraPreview: Boolean,
    onFrameCaptured: suspend (frame: CameraFrame) -> Unit
) {
    IosCamera(
        modifier = modifier,
        cameraSelection = cameraSelection,
        captureResolution = captureResolution,
        showCameraPreview = showCameraPreview,
        onFrameCaptured = onFrameCaptured,
        onQrCodeScanned = null
    )
}

@Composable
internal fun IosCamera(
    modifier: Modifier,
    cameraSelection: CameraSelection,
    captureResolution: CameraCaptureResolution,
    showCameraPreview: Boolean,
    onFrameCaptured: (suspend (frame: CameraFrame) -> Unit)?,
    onQrCodeScanned: ((qrCode: String?) -> Unit)?
) {
    BoxWithConstraints(modifier) {
        val cameraManager = remember(cameraSelection, captureResolution) {
            CameraManager(
                cameraSelection = cameraSelection,
                captureResolution = captureResolution,
                onCameraFrameCaptured = onFrameCaptured,
                onQrCodeScanned = onQrCodeScanned,
                isShowingPreview = showCameraPreview
            )
        }

        var isCameraReadyForPreview by remember(cameraManager) { mutableStateOf(false) }

        LaunchedEffect(cameraManager) {

            cameraManager.startCamera()

            if (cameraManager.isPreviewLayerInitialized) {
                isCameraReadyForPreview = true
            }
            if (cameraManager.captureSession.isRunning()) {
                cameraManager.setCurrentOrientation(UIDevice.currentDevice.orientation)
            }
        }

        DisposableEffect(cameraManager) {
            val orientationListener = OrientationListener { orientation ->
                cameraManager.setCurrentOrientation(orientation)
            }
            orientationListener.register()

            onDispose {
                orientationListener.unregister()
                cameraManager.stopCamera()
            }
        }

        LaunchedEffect(cameraManager, showCameraPreview, cameraManager.isPreviewLayerInitialized) {
            if (showCameraPreview && !isCameraReadyForPreview) {
                if (cameraManager.isPreviewLayerInitialized) {
                    isCameraReadyForPreview = true
                } else {
                    yield() // Allow time for DisposableEffect.
                    // Re-check after yield, DisposableEffect might have completed.
                    if (cameraManager.isPreviewLayerInitialized) {
                        isCameraReadyForPreview = true
                    }
                }
            } else if (!showCameraPreview) {
                isCameraReadyForPreview = false
            }
        }

        if (showCameraPreview) {
            if (isCameraReadyForPreview) {
                UIKitView(
                    factory = {
                        val camView = CameraPreviewView(cameraManager.previewLayer)
                        camView
                    },
                    modifier = modifier.fillMaxSize(),
                    update = { view ->
                        view.setNeedsLayout()
                        view.layoutIfNeeded() // Force layout pass during update.
                    },
                    properties = UIKitInteropProperties(
                        isInteractive = false,
                        isNativeAccessibilityEnabled = false
                    )
                )
            } else {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Initializing Camera Preview...")
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class CameraPreviewView(
    private val previewLayer: AVCaptureVideoPreviewLayer
) : UIView(frame = cValue { CGRectZero }) {

    init {
        // Add the previewLayer as a sublayer of this UIView's primary layer.
        this.layer.addSublayer(previewLayer)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setValue(true, kCATransactionDisableActions) // Disable animations
        previewLayer.frame = this.bounds // Key line
        CATransaction.commit()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class CameraManager(
    val cameraSelection: CameraSelection,
    val captureResolution: CameraCaptureResolution,
    val onCameraFrameCaptured: (suspend (cameraFrame: CameraFrame) -> Unit)?,
    val onQrCodeScanned: ((qrCode: String?) -> Unit)?,
    val isShowingPreview: Boolean
) : AVCaptureVideoDataOutputSampleBufferDelegateProtocol,
    AVCaptureMetadataOutputObjectsDelegateProtocol,
    NSObject() {

    lateinit var captureSession: AVCaptureSession
        private set
    lateinit var previewLayer: AVCaptureVideoPreviewLayer // Initialized in startCamera.
        private set
    val isPreviewLayerInitialized: Boolean
        get() = if (this::previewLayer.isInitialized) true else false // Explicit true/false for clarity
    private var isFrontCamera: Boolean = false
    private var lastKnownPreviewBounds: CValue<CGRect>? = null
    private var videoOrientation: AVCaptureVideoOrientation = AVCaptureVideoOrientationPortrait
    private val cameraScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isProcessingFrame = atomic(false)

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    suspend fun startCamera() {
        if (::captureSession.isInitialized && captureSession.isRunning()) {
            return
        }
        if (::captureSession.isInitialized && !captureSession.isRunning()) {
            // Session was stopped but exists, thus just restart.
            withContext(Dispatchers.IO) {
                captureSession.startRunning()
            }
            return
        }

        captureSession = AVCaptureSession() // Init capture session first.
        captureSession.sessionPreset = when (captureResolution) {
            CameraCaptureResolution.LOW -> AVCaptureSessionPreset640x480
            CameraCaptureResolution.MEDIUM -> AVCaptureSessionPreset1280x720
            CameraCaptureResolution.HIGH -> AVCaptureSessionPreset1920x1080
        }

        // Initialize Preview layer.
        previewLayer = AVCaptureVideoPreviewLayer(session = captureSession).apply {
            this.videoGravity = AVLayerVideoGravityResizeAspectFill
            this.frame = CGRectMake(0.0, 0.0, 0.0, 0.0) // Will be set by layoutSubviews
        }

        // Select camera lens.
        val requestedDevicePosition = when (cameraSelection) {
            CameraSelection.DEFAULT_BACK_CAMERA -> AVCaptureDevicePositionBack
            CameraSelection.DEFAULT_FRONT_CAMERA -> AVCaptureDevicePositionFront
        }

        val device: AVCaptureDevice? = if (requestedDevicePosition == AVCaptureDevicePositionBack) {
            // Prioritize multi-camera systems for the back camera to improve auto-focusing on closeup targets.
            val multiCameraDeviceTypes = listOf(
                AVCaptureDeviceTypeBuiltInTripleCamera,
                AVCaptureDeviceTypeBuiltInDualWideCamera,
                AVCaptureDeviceTypeBuiltInDualCamera
            )
            val discoverySession = AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
                deviceTypes = multiCameraDeviceTypes,
                mediaType = AVMediaTypeVideo,
                position = AVCaptureDevicePositionBack
            )
            discoverySession.devices.firstOrNull() as? AVCaptureDevice
                ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) // Fallback to default.
        } else {
            // Front camera (no multi-camera options).
            val devices = AVCaptureDevice.devicesWithMediaType(AVMediaTypeVideo)
                .map { it as AVCaptureDevice }
            devices.firstOrNull { it.position == requestedDevicePosition }
                ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        }

        if (device == null) {
            Logger.e(TAG, "Device has no camera")
            // Clean up if partial initialization occurred
            if (::captureSession.isInitialized && captureSession.isRunning()) captureSession.stopRunning()
            return
        }

        isFrontCamera = device.position == AVCaptureDevicePositionFront

        memScoped {
            val error: ObjCObjectVar<NSError?> = alloc()
            val input = AVCaptureDeviceInput(device, error.ptr)
            if (error.value == null) {
                if (captureSession.canAddInput(input)) {
                    captureSession.addInput(input)
                } else {
                    Logger.e(TAG, "Error adding input device.")
                    if (::captureSession.isInitialized && captureSession.isRunning()) captureSession.stopRunning()
                    return@memScoped
                }
            } else {
                Logger.e(TAG, "Error constructing input: ${error.value}")
                if (::captureSession.isInitialized && captureSession.isRunning()) captureSession.stopRunning()
                return@memScoped
            }
        }

        val videoDataOutput = AVCaptureVideoDataOutput().apply {
            videoSettings = mapOf(kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA)
            alwaysDiscardsLateVideoFrames = true
            val queue = dispatch_queue_create("org.multipaz.compose.camera_queue", null)
            setSampleBufferDelegate(this@CameraManager, queue)
        }

        if (captureSession.canAddOutput(videoDataOutput)) {
            captureSession.addOutput(videoDataOutput)
        } else {
            Logger.e(TAG, "Error adding video data output")
            if (::captureSession.isInitialized && captureSession.isRunning()) captureSession.stopRunning()
            return
        }


        if (onQrCodeScanned != null) {
            val metadataOutput = AVCaptureMetadataOutput()
            if (captureSession.canAddOutput(metadataOutput)) {
                captureSession.addOutput(metadataOutput)
            } else {
                Logger.e(TAG, "Error adding metadata output")
                if (::captureSession.isInitialized && captureSession.isRunning()) captureSession.stopRunning()
                return
            }

            metadataOutput.metadataObjectTypes = listOf(
                AVMetadataObjectTypeQRCode
            )
            metadataOutput.setMetadataObjectsDelegate(
                objectsDelegate = this,
                queue = dispatch_get_main_queue()
            )
        }

        withContext(Dispatchers.IO) {
            captureSession.startRunning()
        }
    }

    fun stopCamera() {
        if (::captureSession.isInitialized && captureSession.isRunning()) {
            captureSession.stopRunning()
            cameraScope.cancel()
        }
    }

    fun setCurrentOrientation(newOrientation: UIDeviceOrientation) {
        val avOrientation = when (newOrientation) {
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeRight
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeLeft
            UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
            else -> AVCaptureVideoOrientationPortrait
        }
        videoOrientation = avOrientation

        if (::previewLayer.isInitialized) {
            previewLayer.connection?.videoOrientation = avOrientation
        }
    }

    @OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection
    ) {
        if (didOutputSampleBuffer == null) return
        if (onCameraFrameCaptured == null) return

        if (!isProcessingFrame.compareAndSet(expect = false, update = true)) { // Drop frame if still processing one.
            return
        }

        val uiImage = didOutputSampleBuffer.toUIImage() ?: run {
            isProcessingFrame.value = false
            return
        }

        val cameraFrame: CameraFrame

        try {
            val imageWidth = uiImage.size.useContents { width }.toFloat()
            val imageHeight = uiImage.size.useContents { height }.toFloat()
            var previewTransformation = Matrix() // Identity.
            val density = UIScreen.mainScreen.scale.toFloat()

            if (isShowingPreview) {
                // Retrieve the current preview size.
                var currentPreviewFrame: CValue<CGRect>? = null
                dispatch_sync(dispatch_get_main_queue()) { // Must be dispatched to main thread to get the frame.
                    val frameFromLayer = previewLayer.presentationLayer()?.frame ?: previewLayer.frame
                    if (frameFromLayer.useContents { size.width > 0.0 && size.height > 0.0 }) {
                        currentPreviewFrame = frameFromLayer
                    } else {
                        Logger.w(TAG, "The previewLayer.frame is zero. Using lastKnownPreviewBounds if available.")
                    }
                }

                if (currentPreviewFrame == null && lastKnownPreviewBounds == null) {
                    isProcessingFrame.value = false
                    return
                }

                if (currentPreviewFrame == null) {
                    if (lastKnownPreviewBounds != null && lastKnownPreviewBounds!!.useContents { size.width > 0.0 && size.height > 0.0 }) {
                        currentPreviewFrame = lastKnownPreviewBounds
                    } else {
                        Logger.e(
                            TAG,
                            "captureOutput: No valid bounds (neither main thread nor fallback). Skipping frame."
                        )
                        return // Skip "bad" frame processing.
                    }
                }
	
                if (currentPreviewFrame!!.useContents { size.width > 0.0 && size.height > 0.0 }) {
                    lastKnownPreviewBounds = currentPreviewFrame
                } else {
                    Logger.e(TAG, "Preview bounds can't be retrieved. Skipping frame.")
                    return
                }

                val boundsToUse = currentPreviewFrame

                previewTransformation = calculatePreviewTransformationMatrix(
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    previewBoundsDp = boundsToUse,
                    density = density,
                    videoOrientation = videoOrientation,
                    isFrontCamera = this.isFrontCamera
                )
            } else {
                previewTransformation = calculateBitmapTransformationMatrix(
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    videoOrientation = videoOrientation,
                    isFrontCamera = this.isFrontCamera
                )
            }

            cameraFrame = CameraFrame(
                cameraImage = CameraImage(uiImage),
                width = imageWidth.toInt(),
                height = imageHeight.toInt(),
                rotation = videoOrientation.toRotationAngle(),
                previewTransformation = previewTransformation,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error preparing frame data", e)
            isProcessingFrame.value = false // Release lock on error during prep.
            return
        }

        cameraScope.launch {
            try {
                onCameraFrameCaptured?.let { it(cameraFrame) }
            } finally {
                isProcessingFrame.value = false // Release lock after processing is done or if it throws.
            }
        }
        // Not recommended but IS needed to avoid lockups, see https://youtrack.jetbrains.com/issue/KT-74239
        GC.collect()
    }

    var lastCodeScanned: String? = null
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection
    ) {
        if (onQrCodeScanned == null) {
            return
        }
        if (didOutputMetadataObjects.isEmpty()) {
            if (lastCodeScanned != null) {
                onQrCodeScanned(null)
                lastCodeScanned = null
            }
        } else {
            val metadataObj = didOutputMetadataObjects[0] as AVMetadataMachineReadableCodeObject
            if (metadataObj.type == AVMetadataObjectTypeQRCode) {
                if (lastCodeScanned != metadataObj.stringValue) {
                    onQrCodeScanned(metadataObj.stringValue)
                    lastCodeScanned = metadataObj.stringValue
                }
            }
        }
    }
}

private fun AVCaptureVideoOrientation.toRotationAngle(): Int =
    when (this) {
        AVCaptureVideoOrientationLandscapeLeft -> 270
        AVCaptureVideoOrientationLandscapeRight -> 90
        AVCaptureVideoOrientationPortrait -> 0
        AVCaptureVideoOrientationPortraitUpsideDown -> 180
        else -> 0
    }

@OptIn(ExperimentalForeignApi::class)
private class OrientationListener(
    val orientationChanged: (UIDeviceOrientation) -> Unit
) : NSObject() {

    val notificationName = UIDeviceOrientationDidChangeNotification

    @OptIn(BetaInteropApi::class)
    @Suppress("UNUSED_PARAMETER")
    @ObjCAction
    fun orientationDidChange(arg: NSNotification) {
        orientationChanged(UIDevice.currentDevice.orientation)
    }

    fun register() {
        NSNotificationCenter.defaultCenter.addObserver(
            observer = this,
            selector = NSSelectorFromString(
                OrientationListener::orientationDidChange.name + ":"
            ),
            name = notificationName,
            `object` = null
        )
    }

    fun unregister() {
        NSNotificationCenter.defaultCenter.removeObserver(
            observer = this,
            name = notificationName,
            `object` = null
        )
    }
}

/**
 * Convert camera pixel buffer to UIImage.
 *
 * The CVPixelBuffer Actual Pixel Format: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange (YUV) (875704438).
 * The color bytes order is BGRA_8888.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CMSampleBufferRef.toUIImage(): UIImage? {
    val imageBuffer = CMSampleBufferGetImageBuffer(this)
        ?: throw IllegalStateException("Could not get CVImageBufferRef from CMSampleBufferRef.")
    val ciImage = CIImage.imageWithCVPixelBuffer(imageBuffer)
    val temporaryContext: CIContext = CIContext.contextWithOptions(null)
    val bufferWidth = CVPixelBufferGetWidth(imageBuffer).toDouble()
    val bufferHeight = CVPixelBufferGetHeight(imageBuffer).toDouble()
    val imageRect = CGRectMake(0.0, 0.0, bufferWidth, bufferHeight)
    var videoImage: CGImageRef? = null
    val finalUiImage: UIImage = try {
        videoImage = temporaryContext.createCGImage(ciImage, fromRect = imageRect)
        if (videoImage == null) {
            Logger.e(TAG, "Error: Could not create CGImageRef from CIImage.")
            return null
        }
        UIImage(cGImage = videoImage)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Logger.e(TAG, "Error during image creation", e)
        return null
    } finally {
        try {
            videoImage?.let {
                CGImageRelease(it)
            }
        }
        catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }
    return finalUiImage
}

/**
 * Infer transformation Matrix for conversion of the captured image coordinates to the displayed preview coordinates.
 * The matrix is also used to infer the image mirrored state of the captured bitmap.
 *
 * @param imageWidth Pixel width of the raw camera image.
 * @param imageHeight Pixel height of the raw camera image.
 * @param previewBoundsDp Preview bounds IN DP.
 * @param density Screen density to convert DP to PX.
 * @param videoOrientation Video orientation of the camera.
 * @param isFrontCamera Whether the camera is a front camera.
 *
 * @return Matrix representing the transformation
 */
@OptIn(ExperimentalForeignApi::class)
private fun calculatePreviewTransformationMatrix(
    imageWidth: Float,
    imageHeight: Float,
    previewBoundsDp: CValue<CGRect>?,
    density: Float,
    videoOrientation: AVCaptureVideoOrientation,
    isFrontCamera: Boolean,
): Matrix {
    val resultMatrix = Matrix() // Initialize to identity.

    if (previewBoundsDp == null || imageWidth <= 0.0 || imageHeight <= 0.0 || density <= 0f) {
        Logger.w(TAG, "Invalid image dimensions or density. Returning identity.")
        return resultMatrix
    }

    var previewWidthPx: Double
    var previewHeightPx: Double

    previewBoundsDp.useContents {
        previewWidthPx = this.size.width * density // Convert DP to Pixels.
        previewHeightPx = this.size.height * density // Convert DP to Pixels.
    }

    val previewCenterXPx = (previewWidthPx / 2.0).toFloat()
    val previewCenterYPx = (previewHeightPx / 2.0).toFloat()
    resultMatrix.translate(previewCenterXPx, previewCenterYPx)

    var uiRotationDegrees = 0f
    var swapDimensions = false

    when (videoOrientation) {
        AVCaptureVideoOrientationPortraitUpsideDown -> {
            uiRotationDegrees = -90f
            swapDimensions = true
        }

        AVCaptureVideoOrientationPortrait -> {
            uiRotationDegrees = 90f
            swapDimensions = true
        }

        AVCaptureVideoOrientationLandscapeLeft -> {
            uiRotationDegrees = 180f
            swapDimensions = false
        }

        AVCaptureVideoOrientationLandscapeRight -> {
            uiRotationDegrees = 0f
            swapDimensions = false
        }
    }

    val imageWidthForScale = if (swapDimensions) imageHeight else imageWidth
    val imageHeightForScale = if (swapDimensions) imageWidth else imageHeight
    var scale = 1f
    if (imageWidthForScale > 0.0 && imageHeightForScale > 0.0 && previewWidthPx > 0.0 && previewHeightPx > 0.0) {
        val previewAspectRatio = previewWidthPx / previewHeightPx
        val imageAspectRatioAfterTotalRotation = imageWidthForScale / imageHeightForScale
        if (previewAspectRatio > imageAspectRatioAfterTotalRotation) {
            scale = (previewWidthPx / imageWidthForScale).toFloat()
        } else {
            scale = (previewHeightPx / imageHeightForScale).toFloat()
        }
    } else {
        Logger.w(TAG, "Cannot calculate scale due to zero dimension. Defaulting scale to 1.0.")
    }

    resultMatrix.scale(x = scale, y = scale)

    if (uiRotationDegrees != 0f) {
        resultMatrix.rotateZ(uiRotationDegrees)
    }

    if (isFrontCamera) {
        resultMatrix.scale(x = 1f, y = -1f) // Mirror along local Y-axis (which corresponds to the screen X axis)
    }

    resultMatrix.translate(-imageWidth / 2, -imageHeight / 2)

    return resultMatrix
}

/**
 * Simplified transformation matrix for the no-preview use cases helping to handle front/back camera configurations
 * at different device rotation angles when passing the iOS camera bitmap to MLKit for face detection.
 */
private fun calculateBitmapTransformationMatrix(
    imageWidth: Float,
    imageHeight: Float,
    videoOrientation: AVCaptureVideoOrientation,
    isFrontCamera: Boolean,
): Matrix {
    val resultMatrix = Matrix() // Initialize to identity.

    if (imageWidth <= 0.0 || imageHeight <= 0.0) {
        Logger.w(TAG, "Invalid image dimensions. Returning identity.")
        return resultMatrix
    }

    val imageCenterX = imageWidth / 2
    val imageCenterY = imageHeight / 2

    resultMatrix.translate(imageCenterX, imageCenterY)

    val uiRotationDegrees = when (videoOrientation) {
        AVCaptureVideoOrientationPortraitUpsideDown -> -90f
        AVCaptureVideoOrientationLandscapeLeft, AVCaptureVideoOrientationLandscapeRight -> 180f
        else -> 90f // AVCaptureVideoOrientationPortrait and unknown.
    }
    resultMatrix.rotateZ(uiRotationDegrees)

    if (isFrontCamera) {
        resultMatrix.scale(x = 1f, y = -1f) // Mirror along local Y-axis (which corresponds to the screen X axis)
    }

    resultMatrix.translate(-imageCenterX, -imageCenterY)

    return resultMatrix
}
