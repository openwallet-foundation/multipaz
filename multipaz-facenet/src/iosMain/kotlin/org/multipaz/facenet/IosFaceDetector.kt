package org.multipaz.facenet

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
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
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.UIKit.UIImage
import platform.Foundation.NSProcessInfo
import platform.Vision.VNDetectFaceLandmarksRequest
import platform.Vision.VNDetectFaceRectanglesRequest
import platform.Vision.VNFaceLandmarkRegion2D
import platform.Vision.VNFaceObservation
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRequest
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

private const val TAG = "IosFaceDetector"

internal data class Point2D(val x: Double, val y: Double)
internal data class Rect2D(val left: Double, val top: Double, val width: Double, val height: Double)

@OptIn(ExperimentalForeignApi::class)
private data class ImageRefHolder(
    val cgImage: CGImageRef,
    val needsRelease: Boolean
)

internal class IosDetectedFace(
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val leftEye: Point2D?,
    val rightEye: Point2D?,
    val boundingBox: Rect2D
)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosFaceDetector : AutoCloseable {

    private val isClosed = kotlinx.atomicfu.atomic(false)
    private val isRunningOnSimulator =
        NSProcessInfo.processInfo.environment["SIMULATOR_ROOT"] != null
    private val preferCpu = kotlinx.atomicfu.atomic(isRunningOnSimulator)

    fun detectFaces(cgImage: CGImageRef): List<IosDetectedFace> {
        if (isClosed.value) return emptyList()

        val observations = executeDetection(cgImage)
        if (observations.isEmpty()) return emptyList()

        val imgW = CGImageGetWidth(cgImage).toDouble()
        val imgH = CGImageGetHeight(cgImage).toDouble()

        return observations.map { obs ->
            // Apple Vision yaw: positive = turned to person's right. Negate to match MLKit convention:
            // positive = turned to person's left (facing camera right).
            val yaw = -((obs.yaw?.floatValue ?: 0.0f) * 180.0f / PI).toFloat()
            // Apple Vision pitch: positive = tilted down (chin to chest). Negate to match MLKit convention:
            // positive = tilted up (looking up).
            val pitch = -((obs.pitch?.floatValue ?: 0.0f) * 180.0f / PI).toFloat()
            val roll = ((obs.roll?.floatValue ?: 0.0f) * 180.0f / PI).toFloat()

            val bb = obs.boundingBox
            val bbOriginX = bb.useContents { origin.x }
            val bbOriginY = bb.useContents { origin.y }
            val bbWidth = bb.useContents { size.width }
            val bbHeight = bb.useContents { size.height }

            val bbLeft = bbOriginX * imgW
            val bbTop = (1.0 - (bbOriginY + bbHeight)) * imgH
            val bbPixelW = bbWidth * imgW
            val bbPixelH = bbHeight * imgH
            val boundingBox = Rect2D(bbLeft, bbTop, bbPixelW, bbPixelH)

            val landmarks = obs.landmarks
            val leftEye = computeEyeCenter(landmarks?.leftEye, bbOriginX, bbOriginY, bbWidth, bbHeight, imgW, imgH)
            val rightEye = computeEyeCenter(landmarks?.rightEye, bbOriginX, bbOriginY, bbWidth, bbHeight, imgW, imgH)

            IosDetectedFace(
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                leftEye = leftEye,
                rightEye = rightEye,
                boundingBox = boundingBox
            )
        }
    }

    private fun executeDetection(cgImage: CGImageRef): List<VNFaceObservation> {
        val handler = VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any>())

        // 1. Try landmarks request first for detailed facial landmarks
        val landmarksRequest = VNDetectFaceLandmarksRequest()
        if (preferCpu.value) {
            landmarksRequest.usesCPUOnly = true
        }
        var ok = performRequest(handler, landmarksRequest)
        if (!ok && !preferCpu.value) {
            preferCpu.value = true
            landmarksRequest.usesCPUOnly = true
            ok = performRequest(handler, landmarksRequest)
        }
        if (ok) {
            val obs = landmarksRequest.results?.filterIsInstance<VNFaceObservation>()
            if (!obs.isNullOrEmpty()) {
                return obs
            }
        }

        // 2. Fall back to face rectangles request (e.g. low-resolution images or subtle faces)
        val rectRequest = VNDetectFaceRectanglesRequest()
        if (preferCpu.value) {
            rectRequest.usesCPUOnly = true
        }
        ok = performRequest(handler, rectRequest)
        if (!ok && !preferCpu.value) {
            preferCpu.value = true
            rectRequest.usesCPUOnly = true
            ok = performRequest(handler, rectRequest)
        }
        if (ok) {
            val obs = rectRequest.results?.filterIsInstance<VNFaceObservation>()
            if (!obs.isNullOrEmpty()) {
                return obs
            }
        }

        return emptyList()
    }

    private fun performRequest(handler: VNImageRequestHandler, request: VNRequest): Boolean {
        return try {
            memScoped {
                val error: kotlinx.cinterop.ObjCObjectVar<platform.Foundation.NSError?> = alloc()
                val success = handler.performRequests(listOf(request), error.ptr)
                if (!success) {
                    val nsError = error.value
                    Logger.w(TAG, "Vision request ${request::class.simpleName} failed: ${nsError?.localizedDescription} (code ${nsError?.code})")
                }
                success
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "Exception performing Vision request ${request::class.simpleName}", e)
            false
        }
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
            CGContextSaveGState(bitmapContext)

            val leftEye = face.leftEye
            val rightEye = face.rightEye

            if (leftEye != null && rightEye != null) {
                val eyeDistance = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
                if (eyeDistance > 1.0) {
                    val cx = (leftEye.x + rightEye.x) / 2.0
                    val cy = (leftEye.y + rightEye.y) / 2.0
                    val eyeAngleRad = atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x)

                    val faceCropFactor = 4.0
                    val faceVerticalOffsetFactor = 0.25
                    val cropSize = eyeDistance * faceCropFactor
                    val verticalOffset = eyeDistance * faceVerticalOffsetFactor
                    val scale = targetSize.toDouble() / cropSize

                    // Setup transform to map face center with eye leveling to output center
                    // CoreGraphics Y points UP, whereas image pixel Y points DOWN.
                    // Map source (cx, cy) to bottom-up source coords: (cx, imgH - cy)
                    val srcCenterY = imgH - (cy + verticalOffset)

                    val t1 = CGAffineTransformMakeTranslation(targetSize / 2.0, targetSize / 2.0)
                    val t2 = CGAffineTransformMakeScale(scale, scale)
                    val t3 = CGAffineTransformMakeRotation(eyeAngleRad)
                    val t4 = CGAffineTransformMakeTranslation(-cx, -srcCenterY)

                    val transform = CGAffineTransformConcat(
                        CGAffineTransformConcat(CGAffineTransformConcat(t4, t3), t2),
                        t1
                    )
                    CGContextConcatCTM(bitmapContext, transform)
                    CGContextDrawImage(bitmapContext, CGRectMake(0.0, 0.0, imgW, imgH), sourceImage)
                } else {
                    drawBoundingBoxFallback(bitmapContext, sourceImage, face.boundingBox, imgW, imgH, targetSize)
                }
            } else {
                drawBoundingBoxFallback(bitmapContext, sourceImage, face.boundingBox, imgW, imgH, targetSize)
            }

            CGContextRestoreGState(bitmapContext)

            // Read RGBA pixel buffer
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
        bb: Rect2D,
        imgW: Double,
        imgH: Double,
        targetSize: Int
    ) {
        val marginX = bb.width * 0.2
        val marginY = bb.height * 0.2
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

    private fun computeEyeCenter(
        region: VNFaceLandmarkRegion2D?,
        bbOriginX: Double,
        bbOriginY: Double,
        bbWidth: Double,
        bbHeight: Double,
        imgW: Double,
        imgH: Double
    ): Point2D? {
        if (region == null || region.pointCount == 0uL) return null
        val points = region.normalizedPoints ?: return null
        var sumX = 0.0
        var sumY = 0.0
        val count = region.pointCount.toInt()
        for (i in 0 until count) {
            val pt = points[i]
            sumX += pt.x
            sumY += pt.y
        }
        val avgNormX = sumX / count
        val avgNormY = sumY / count
        val fullNormX = bbOriginX + avgNormX * bbWidth
        val fullNormY = bbOriginY + avgNormY * bbHeight
        val pixelX = fullNormX * imgW
        val pixelY = (1.0 - fullNormY) * imgH
        return Point2D(pixelX, pixelY)
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
        isClosed.value = true
    }
}
