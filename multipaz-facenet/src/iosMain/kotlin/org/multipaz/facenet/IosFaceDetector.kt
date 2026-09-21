package org.multipaz.facenet

import cnames.structs.TfLiteInterpreter
import cnames.structs.TfLiteInterpreterOptions
import cnames.structs.TfLiteModel
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.pin
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import org.multipaz.util.toByteArray
import org.tensorflow.lite.c.TfLiteDelegate
import org.tensorflow.lite.c.TfLiteInterpreterAllocateTensors
import org.tensorflow.lite.c.TfLiteInterpreterCreate
import org.tensorflow.lite.c.TfLiteInterpreterDelete
import org.tensorflow.lite.c.TfLiteInterpreterGetInputTensor
import org.tensorflow.lite.c.TfLiteInterpreterGetOutputTensor
import org.tensorflow.lite.c.TfLiteInterpreterInvoke
import org.tensorflow.lite.c.TfLiteInterpreterOptionsAddDelegate
import org.tensorflow.lite.c.TfLiteInterpreterOptionsCreate
import org.tensorflow.lite.c.TfLiteInterpreterOptionsDelete
import org.tensorflow.lite.c.TfLiteInterpreterOptionsSetNumThreads
import org.tensorflow.lite.c.TfLiteModelCreate
import org.tensorflow.lite.c.TfLiteModelDelete
import org.tensorflow.lite.c.TfLiteTensorCopyFromBuffer
import org.tensorflow.lite.c.TfLiteTensorCopyToBuffer
import org.tensorflow.lite.c.TfLiteTensorDim
import org.tensorflow.lite.c.TfLiteXNNPackDelegateCreate
import org.tensorflow.lite.c.TfLiteXNNPackDelegateDelete
import org.tensorflow.lite.c.kTfLiteOk
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextRotateCTM
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

private const val TAG = "IosFaceDetector"

typealias IosDetectedFace = BlazeFaceDetection

@OptIn(ExperimentalForeignApi::class)
private data class ImageRefHolder(
    val cgImage: CGImageRef,
    val needsRelease: Boolean
)

/**
 * Cross-platform face detector for iOS based on Google MediaPipe BlazeFace short-range TFLite model.
 *
 * Runs deterministic face detection and 6-landmark extraction via TensorFlowLiteC on iOS,
 * eliminating OS-level Apple Vision API drift across iOS releases and simulator environments.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosFaceDetector(
    modelBytes: ByteString? = null
) : AutoCloseable {

    private val isClosed = kotlinx.atomicfu.atomic(false)

    private var pinnedModelBytes: Pinned<ByteArray>? = null
    private var model: CPointer<TfLiteModel>? = null
    private var options: CPointer<TfLiteInterpreterOptions>? = null
    private var xnnpackDelegate: CPointer<TfLiteDelegate>? = null
    private var interpreter: CPointer<TfLiteInterpreter>? = null

    private val regressorsIndex: Int
    private val classificatorsIndex: Int

    init {
        val bytes = when {
            modelBytes != null && !modelBytes.isEmpty() -> modelBytes.toByteArray()
            else -> loadModelFromBundle("face_detection_short_range")
                ?: BlazeFaceModelData.defaultModelBytes.toByteArray()
        }

        val pinned = bytes.pin()
        pinnedModelBytes = pinned

        val localModel = TfLiteModelCreate(pinned.addressOf(0), bytes.size.toULong())
            ?: throw IllegalStateException("Failed to parse BlazeFace model from bytes.")
        model = localModel

        val localOptions = TfLiteInterpreterOptionsCreate()
            ?: throw IllegalStateException("Failed to create TfLiteInterpreterOptions.")
        options = localOptions
        TfLiteInterpreterOptionsSetNumThreads(localOptions, 4)

        try {
            val delegate = TfLiteXNNPackDelegateCreate(null)
            if (delegate != null) {
                xnnpackDelegate = delegate
                TfLiteInterpreterOptionsAddDelegate(localOptions, delegate)
                Logger.d(TAG, "XNNPACK delegate enabled for iOS BlazeFace")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to initialize XNNPACK delegate, falling back to CPU", e)
        }

        val localInterpreter = TfLiteInterpreterCreate(localModel, localOptions)
            ?: throw IllegalStateException("Failed to create TfLiteInterpreter.")
        interpreter = localInterpreter

        val allocStatus = TfLiteInterpreterAllocateTensors(localInterpreter)
        if (allocStatus != kTfLiteOk) {
            throw IllegalStateException("Failed to allocate tensors in TfLiteInterpreter: status $allocStatus")
        }

        val out0 = TfLiteInterpreterGetOutputTensor(localInterpreter, 0)
        val out0LastDim = if (out0 != null) TfLiteTensorDim(out0, 2) else 16
        if (out0LastDim == 16) {
            regressorsIndex = 0
            classificatorsIndex = 1
        } else {
            regressorsIndex = 1
            classificatorsIndex = 0
        }

        Logger.d(
            TAG,
            "BlazeFace detector initialized on iOS: regressorsIndex=$regressorsIndex, " +
                    "classificatorsIndex=$classificatorsIndex"
        )
    }

    fun detectFaces(cgImage: CGImageRef): List<IosDetectedFace> {
        if (isClosed.value) return emptyList()
        val localInterpreter = interpreter ?: return emptyList()

        val imgW = CGImageGetWidth(cgImage).toDouble()
        val imgH = CGImageGetHeight(cgImage).toDouble()
        if (imgW <= 0.0 || imgH <= 0.0) return emptyList()

        val maxDim = max(imgW, imgH)
        val scale = 128.0 / maxDim
        val dw = imgW * scale
        val dh = imgH * scale
        val dx = (128.0 - dw) / 2.0
        val dy = (128.0 - dh) / 2.0

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val bitmapContext = CGBitmapContextCreate(
            data = null,
            width = 128u,
            height = 128u,
            bitsPerComponent = 8u,
            bytesPerRow = (128 * 4).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        )
        CGColorSpaceRelease(colorSpace)
        if (bitmapContext == null) return emptyList()

        val inputFloats = FloatArray(128 * 128 * 3)
        try {
            // Draw image with letterboxing into 128x128 context
            CGContextDrawImage(
                bitmapContext,
                CGRectMake(dx, 128.0 - dy - dh, dw, dh),
                cgImage
            )

            val rawData = CGBitmapContextGetData(bitmapContext) ?: return emptyList()
            val bytePtr = rawData.reinterpret<ByteVar>()
            var floatIdx = 0
            for (i in 0 until 128 * 128) {
                val r = bytePtr[i * 4 + 0].toInt() and 0xFF
                val g = bytePtr[i * 4 + 1].toInt() and 0xFF
                val b = bytePtr[i * 4 + 2].toInt() and 0xFF
                inputFloats[floatIdx++] = (r - 127.5f) / 127.5f
                inputFloats[floatIdx++] = (g - 127.5f) / 127.5f
                inputFloats[floatIdx++] = (b - 127.5f) / 127.5f
            }
        } finally {
            CGContextRelease(bitmapContext)
        }

        // Run TFLite inference
        val inTensor = TfLiteInterpreterGetInputTensor(localInterpreter, 0) ?: return emptyList()
        val inByteSize = (inputFloats.size * Float.SIZE_BYTES).toULong()
        val copyInOk = inputFloats.usePinned { pinned ->
            TfLiteTensorCopyFromBuffer(inTensor, pinned.addressOf(0), inByteSize) == kTfLiteOk
        }
        if (!copyInOk) return emptyList()

        val invokeStatus = TfLiteInterpreterInvoke(localInterpreter)
        if (invokeStatus != kTfLiteOk) {
            Logger.e(TAG, "TfLiteInterpreterInvoke failed with status $invokeStatus")
            return emptyList()
        }

        val regTensor = TfLiteInterpreterGetOutputTensor(localInterpreter, regressorsIndex) ?: return emptyList()
        val clsTensor = TfLiteInterpreterGetOutputTensor(localInterpreter, classificatorsIndex) ?: return emptyList()

        val rawBoxes = FloatArray(BlazeFaceDecoder.NUM_ANCHORS * BlazeFaceDecoder.NUM_COORDS)
        val rawScores = FloatArray(BlazeFaceDecoder.NUM_ANCHORS)

        val copyRegOk = rawBoxes.usePinned { pinned ->
            TfLiteTensorCopyToBuffer(regTensor, pinned.addressOf(0), (rawBoxes.size * Float.SIZE_BYTES).toULong()) == kTfLiteOk
        }
        val copyClsOk = rawScores.usePinned { pinned ->
            TfLiteTensorCopyToBuffer(clsTensor, pinned.addressOf(0), (rawScores.size * Float.SIZE_BYTES).toULong()) == kTfLiteOk
        }
        if (!copyRegOk || !copyClsOk) return emptyList()

        return BlazeFaceDecoder.decode(
            rawBoxes = rawBoxes,
            rawScores = rawScores,
            imageWidth = imgW,
            imageHeight = imgH
        )
    }

    fun detectFaces(frame: CameraFrame): List<IosDetectedFace> {
        val holder = extractCgImage(frame) ?: return emptyList()
        val (uprightImage, isAllocated) = ensureUpright(holder.cgImage, frame.rotationDegrees)
        return try {
            detectFaces(uprightImage)
        } finally {
            if (isAllocated) {
                CGImageRelease(uprightImage)
            }
            if (holder.needsRelease) {
                CGImageRelease(holder.cgImage)
            }
        }
    }

    fun captureUprightJpeg(frame: CameraFrame): ByteString? {
        val holder = extractCgImage(frame) ?: return null
        val (uprightImage, isAllocated) = ensureUpright(holder.cgImage, frame.rotationDegrees)
        return try {
            val uiImage = UIImage.imageWithCGImage(uprightImage)
            val nsData = UIImageJPEGRepresentation(uiImage, 0.95) ?: return null
            ByteString(nsData.toByteArray())
        } finally {
            if (isAllocated) {
                CGImageRelease(uprightImage)
            }
            if (holder.needsRelease) {
                CGImageRelease(holder.cgImage)
            }
        }
    }

    fun detectFaces(imageBytes: ByteArray): List<IosDetectedFace> {
        val cgImage = decodeToCgImage(imageBytes) ?: return emptyList()
        return try {
            detectFaces(cgImage)
        } finally {
            CGImageRelease(cgImage)
        }
    }

    /**
     * Extracts an aligned square face crop of size [targetSize] x [targetSize] as raw RGB floats.
     * Returns FloatArray of size [targetSize * targetSize * 3].
     */
    fun extractFaceCrop(frame: CameraFrame, face: IosDetectedFace, targetSize: Int): FloatArray? {
        val holder = extractCgImage(frame) ?: return null
        val (uprightImage, isAllocated) = ensureUpright(holder.cgImage, frame.rotationDegrees)
        return try {
            extractFaceCrop(uprightImage, face, targetSize)
        } finally {
            if (isAllocated) {
                CGImageRelease(uprightImage)
            }
            if (holder.needsRelease) {
                CGImageRelease(holder.cgImage)
            }
        }
    }

    fun extractFaceCrop(imageBytes: ByteArray, face: IosDetectedFace, targetSize: Int): FloatArray? {
        val cgImage = decodeToCgImage(imageBytes) ?: return null
        return try {
            extractFaceCrop(cgImage, face, targetSize)
        } finally {
            CGImageRelease(cgImage)
        }
    }

    /**
     * Resizes [imageBytes] directly to [targetSize] x [targetSize] without face alignment or crop.
     */
    fun resizeImageDirect(imageBytes: ByteArray, targetSize: Int): FloatArray? {
        val cgImage = decodeToCgImage(imageBytes) ?: return null
        return try {
            val colorSpace = CGColorSpaceCreateDeviceRGB()
            val bitmapContext = CGBitmapContextCreate(
                data = null,
                width = targetSize.toULong(),
                height = targetSize.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = (targetSize * 4).toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
            )
            CGColorSpaceRelease(colorSpace)
            if (bitmapContext == null) return null
            try {
                CGContextDrawImage(bitmapContext, CGRectMake(0.0, 0.0, targetSize.toDouble(), targetSize.toDouble()), cgImage)
                val rawData = CGBitmapContextGetData(bitmapContext) ?: return null
                val bytePtr = rawData.reinterpret<ByteVar>()
                val numPixels = targetSize * targetSize
                val floatPixels = FloatArray(numPixels * 3)
                var floatIdx = 0
                for (i in 0 until numPixels) {
                    floatPixels[floatIdx++] = (bytePtr[i * 4 + 0].toInt() and 0xFF).toFloat()
                    floatPixels[floatIdx++] = (bytePtr[i * 4 + 1].toInt() and 0xFF).toFloat()
                    floatPixels[floatIdx++] = (bytePtr[i * 4 + 2].toInt() and 0xFF).toFloat()
                }
                floatPixels
            } finally {
                CGContextRelease(bitmapContext)
            }
        } finally {
            CGImageRelease(cgImage)
        }
    }

    /**
     * Extracts an aligned square face crop of size [targetSize] x [targetSize] as a CGImageRef.
     */
    fun extractFaceCropImage(imageBytes: ByteArray, face: IosDetectedFace, targetSize: Int): CGImageRef? {
        val cgImage = decodeToCgImage(imageBytes) ?: return null
        return try {
            extractFaceCropImage(cgImage, face, targetSize)
        } finally {
            CGImageRelease(cgImage)
        }
    }

    fun extractFaceCropImage(sourceImage: CGImageRef, face: IosDetectedFace, targetSize: Int): CGImageRef? {
        if (isClosed.value) return null

        val imgW = CGImageGetWidth(sourceImage).toDouble()
        val imgH = CGImageGetHeight(sourceImage).toDouble()
        if (imgW <= 0.0 || imgH <= 0.0) return null

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val bitmapContext = CGBitmapContextCreate(
            data = null,
            width = targetSize.toULong(),
            height = targetSize.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (targetSize * 4).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        )
        CGColorSpaceRelease(colorSpace)

        if (bitmapContext == null) {
            Logger.e(TAG, "Failed to create CGBitmapContext for face crop")
            return null
        }

        try {
            renderFaceCropToContext(bitmapContext, sourceImage, face, targetSize, imgW, imgH)
            return CGBitmapContextCreateImage(bitmapContext)
        } finally {
            CGContextRelease(bitmapContext)
        }
    }

    private fun renderFaceCropToContext(
        bitmapContext: platform.CoreGraphics.CGContextRef,
        sourceImage: CGImageRef,
        face: IosDetectedFace,
        targetSize: Int,
        imgW: Double,
        imgH: Double
    ) {
        CGContextSaveGState(bitmapContext)
        try {
            val leftEye = face.leftEye
            val rightEye = face.rightEye

            val eyeDistance = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
            if (eyeDistance > 1.0) {
                val cx = (leftEye.x + rightEye.x) / 2.0
                val cy = (leftEye.y + rightEye.y) / 2.0
                val eyeAngleRad = atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x)

                val faceCropFactor = 3.2
                val faceVerticalOffsetFactor = 0.13
                val cropSize = eyeDistance * faceCropFactor
                val verticalOffset = eyeDistance * faceVerticalOffsetFactor
                val scale = targetSize.toDouble() / cropSize

                val srcEyeCenterY = imgH - cy
                val dstEyeCenterY = targetSize / 2.0 + verticalOffset * scale

                val t1 = CGAffineTransformMakeTranslation(-cx, -srcEyeCenterY)
                val t2 = CGAffineTransformMakeRotation(eyeAngleRad)
                val t3 = CGAffineTransformMakeScale(scale, scale)
                val t4 = CGAffineTransformMakeTranslation(targetSize / 2.0, dstEyeCenterY)

                val transform = CGAffineTransformConcat(
                    CGAffineTransformConcat(CGAffineTransformConcat(t1, t2), t3),
                    t4
                )
                CGContextConcatCTM(bitmapContext, transform)
                CGContextDrawImage(bitmapContext, CGRectMake(0.0, 0.0, imgW, imgH), sourceImage)
            } else {
                drawBoundingBoxFallback(bitmapContext, sourceImage, face.boundingBox, imgW, imgH, targetSize)
            }
        } finally {
            CGContextRestoreGState(bitmapContext)
        }
    }

    /**
     * Aligns eyes horizontally, centers the face with standard vertical offset,
     * scales to [targetSize] x [targetSize], and returns a FloatArray of raw RGB values in range [0, 255].
     */
    fun extractFaceCrop(sourceImage: CGImageRef, face: IosDetectedFace, targetSize: Int): FloatArray? {
        if (isClosed.value) return null

        val imgW = CGImageGetWidth(sourceImage).toDouble()
        val imgH = CGImageGetHeight(sourceImage).toDouble()
        if (imgW <= 0.0 || imgH <= 0.0) return null

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val bitmapContext = CGBitmapContextCreate(
            data = null,
            width = targetSize.toULong(),
            height = targetSize.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (targetSize * 4).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        )
        CGColorSpaceRelease(colorSpace)

        if (bitmapContext == null) {
            Logger.e(TAG, "Failed to create CGBitmapContext for face crop")
            return null
        }

        try {
            renderFaceCropToContext(bitmapContext, sourceImage, face, targetSize, imgW, imgH)

            val rawData = CGBitmapContextGetData(bitmapContext) ?: return null
            val bytePtr = rawData.reinterpret<ByteVar>()
            val numPixels = targetSize * targetSize
            val floatPixels = FloatArray(numPixels * 3)
            var floatIdx = 0
            for (i in 0 until numPixels) {
                val r = bytePtr[i * 4 + 0].toInt() and 0xFF
                val g = bytePtr[i * 4 + 1].toInt() and 0xFF
                val b = bytePtr[i * 4 + 2].toInt() and 0xFF
                floatPixels[floatIdx++] = r.toFloat()
                floatPixels[floatIdx++] = g.toFloat()
                floatPixels[floatIdx++] = b.toFloat()
            }
            return floatPixels
        } finally {
            CGContextRelease(bitmapContext)
        }
    }

    private fun drawBoundingBoxFallback(
        ctx: platform.CoreGraphics.CGContextRef,
        sourceImage: CGImageRef,
        bb: FaceBoundingBox,
        imgW: Double,
        imgH: Double,
        targetSize: Int
    ) {
        val marginX = bb.width * 0.1
        val marginY = bb.height * 0.1
        val left = (bb.left - marginX).coerceAtLeast(0.0)
        val top = (bb.top - marginY).coerceAtLeast(0.0)
        val width = (bb.width + marginX * 2.0).coerceAtMost(imgW - left)
        val height = (bb.height + marginY * 2.0).coerceAtMost(imgH - top)
        val scale = targetSize.toDouble() / maxOf(width, height, 1.0)
        val bottomUpY = imgH - (top + height)

        val t1 = CGAffineTransformMakeScale(scale, scale)
        val t2 = CGAffineTransformMakeTranslation(-left, -bottomUpY)
        val transform = CGAffineTransformConcat(t2, t1)
        CGContextConcatCTM(ctx, transform)
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, imgW, imgH), sourceImage)
    }

    private fun ensureUpright(sourceImage: CGImageRef, rotationDegrees: Int): Pair<CGImageRef, Boolean> {
        val imgW = CGImageGetWidth(sourceImage).toDouble()
        val imgH = CGImageGetHeight(sourceImage).toDouble()
        val effectiveRotation = when {
            rotationDegrees != 0 -> ((rotationDegrees % 360) + 360) % 360
            imgW > imgH -> 90 // Default iOS camera sensor landscape orientation in portrait session
            else -> 0
        }
        if (effectiveRotation == 0) {
            return Pair(sourceImage, false)
        }
        val rotated = rotateCgImage(sourceImage, effectiveRotation) ?: return Pair(sourceImage, false)
        return Pair(rotated, true)
    }

    private fun rotateCgImage(sourceImage: CGImageRef, degrees: Int): CGImageRef? {
        val normDegrees = ((degrees % 360) + 360) % 360
        if (normDegrees == 0) return null

        val imgW = CGImageGetWidth(sourceImage).toDouble()
        val imgH = CGImageGetHeight(sourceImage).toDouble()

        val swap = normDegrees == 90 || normDegrees == 270
        val targetW = if (swap) imgH else imgW
        val targetH = if (swap) imgW else imgH

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        val bitmapContext = CGBitmapContextCreate(
            data = null,
            width = targetW.toULong(),
            height = targetH.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (targetW * 4).toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )
        CGColorSpaceRelease(colorSpace)
        if (bitmapContext == null) return null

        when (normDegrees) {
            90 -> {
                CGContextTranslateCTM(bitmapContext, 0.0, targetH)
                CGContextRotateCTM(bitmapContext, -PI / 2.0)
            }
            180 -> {
                CGContextTranslateCTM(bitmapContext, targetW, targetH)
                CGContextRotateCTM(bitmapContext, -PI)
            }
            270 -> {
                CGContextTranslateCTM(bitmapContext, targetW, 0.0)
                CGContextRotateCTM(bitmapContext, PI / 2.0)
            }
        }

        CGContextDrawImage(bitmapContext, CGRectMake(0.0, 0.0, imgW, imgH), sourceImage)
        val rotatedImage = CGBitmapContextCreateImage(bitmapContext)
        CGContextRelease(bitmapContext)
        return rotatedImage
    }

    private fun extractCgImage(frame: CameraFrame): ImageRefHolder? {
        val platformHandle = frame.platformHandle
        return when {
            platformHandle is UIImage -> {
                val cg = platformHandle.CGImage ?: return null
                ImageRefHolder(cg, needsRelease = false)
            }
            frame.data.size > 0 -> {
                val cg = decodeToCgImage(frame.data.toByteArray()) ?: return null
                ImageRefHolder(cg, needsRelease = true)
            }
            else -> null
        }
    }

    private fun decodeToCgImage(bytes: ByteArray): CGImageRef? {
        if (bytes.isEmpty()) return null
        val cfData = bytes.usePinned { pinned ->
            CFDataCreate(
                allocator = kCFAllocatorDefault,
                bytes = pinned.addressOf(0).reinterpret(),
                length = bytes.size.toLong()
            )
        } ?: return null
        val source = CGImageSourceCreateWithData(data = cfData, options = null)
        CFRelease(cfData)
        if (source == null) return null
        val cgImage = CGImageSourceCreateImageAtIndex(isrc = source, index = 0u, options = null)
        CFRelease(source)
        return cgImage
    }

    override fun close() {
        if (isClosed.value) return
        isClosed.value = true
        interpreter?.let {
            TfLiteInterpreterDelete(it)
            interpreter = null
        }
        options?.let {
            TfLiteInterpreterOptionsDelete(it)
            options = null
        }
        xnnpackDelegate?.let {
            TfLiteXNNPackDelegateDelete(it)
            xnnpackDelegate = null
        }
        model?.let {
            TfLiteModelDelete(it)
            model = null
        }
        pinnedModelBytes?.unpin()
        pinnedModelBytes = null
    }

    companion object {
        @OptIn(ExperimentalForeignApi::class)
        private fun loadModelFromBundle(name: String): ByteArray? {
            val path = NSBundle.mainBundle.pathForResource(name, ofType = "tflite") ?: return null
            val data = NSData.dataWithContentsOfFile(path) ?: return null
            val length = data.length.toInt()
            val bytes = ByteArray(length)
            if (length > 0) {
                bytes.usePinned { pinned ->
                    platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
                }
            }
            return bytes
        }
    }
}
