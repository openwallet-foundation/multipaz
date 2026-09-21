package org.multipaz.facenet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.hypot

private const val TAG = "AndroidFaceDetector"

internal class AndroidFaceDetector : Closeable {

    private val lock = Any()
    @Volatile
    private var isClosed = false

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )

    suspend fun detectFaces(inputImage: InputImage): List<Face> {
        synchronized(lock) {
            if (isClosed) return emptyList()
        }
        return suspendCancellableCoroutine { cont ->
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (cont.isActive) cont.resume(faces)
                }
                .addOnFailureListener { exception ->
                    if (cont.isActive) {
                        if (isClosed) {
                            cont.resume(emptyList())
                        } else {
                            cont.resumeWithException(exception)
                        }
                    }
                }
        }
    }

    suspend fun detectFaces(bitmap: Bitmap, rotationDegrees: Int = 0): List<Face> {
        val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
        return detectFaces(inputImage)
    }

    suspend fun detectFaces(frame: CameraFrame): List<Face> {
        val platformHandle = frame.platformHandle
        return when {
            platformHandle is ImageProxy -> {
                val rotationDegrees = platformHandle.imageInfo.rotationDegrees
                val mediaImage: Image? = platformHandle.image
                if (mediaImage != null) {
                    try {
                        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                        detectFaces(inputImage)
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to process mediaImage, falling back to bitmap", e)
                        val rawBitmap = platformHandle.toBitmap()
                        val bitmap = rotateBitmap(rawBitmap, rotationDegrees)
                        if (bitmap != rawBitmap) rawBitmap.recycle()
                        try {
                            detectFaces(bitmap, 0)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } else {
                    val rawBitmap = platformHandle.toBitmap()
                    val bitmap = rotateBitmap(rawBitmap, rotationDegrees)
                    if (bitmap != rawBitmap) rawBitmap.recycle()
                    try {
                        detectFaces(bitmap, 0)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            platformHandle is Bitmap -> {
                val bitmap = rotateBitmap(platformHandle, frame.rotationDegrees)
                val faces = detectFaces(bitmap, 0)
                if (bitmap != platformHandle) bitmap.recycle()
                faces
            }
            frame.data.size > 0 -> {
                val bytes = frame.data.toByteArray()
                val rawBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    val bitmap = rotateBitmap(rawBitmap, frame.rotationDegrees)
                    if (bitmap != rawBitmap) rawBitmap.recycle()
                    try {
                        detectFaces(bitmap, 0)
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Extracts an aligned, square face crop of dimension [targetSize] x [targetSize] from [frame].
     */
    fun extractFaceCrop(frame: CameraFrame, face: Face, targetSize: Int): Bitmap? {
        if (isClosed) return null
        val platformHandle = frame.platformHandle
        val (sourceBitmap, shouldRecycle) = when {
            platformHandle is ImageProxy -> {
                val rotationDegrees = platformHandle.imageInfo.rotationDegrees
                val rawBitmap = platformHandle.toBitmap()
                val bitmap = rotateBitmap(rawBitmap, rotationDegrees)
                if (bitmap != rawBitmap) rawBitmap.recycle()
                Pair(bitmap, true)
            }
            platformHandle is Bitmap -> {
                val bitmap = rotateBitmap(platformHandle, frame.rotationDegrees)
                Pair(bitmap, bitmap != platformHandle)
            }
            frame.data.size > 0 -> {
                val bytes = frame.data.toByteArray()
                val rawBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    val bitmap = rotateBitmap(rawBitmap, frame.rotationDegrees)
                    if (bitmap != rawBitmap) rawBitmap.recycle()
                    Pair(bitmap, true)
                } else {
                    Pair(null, false)
                }
            }
            else -> Pair(null, false)
        }

        if (sourceBitmap == null) return null

        try {
            return extractFaceCrop(sourceBitmap, face, targetSize)
        } finally {
            if (shouldRecycle) {
                sourceBitmap.recycle()
            }
        }
    }

    /**
     * Extracts an aligned, square face crop of dimension [targetSize] x [targetSize].
     *
     * If left and right eye landmarks are present, the crop is rotated to level the eyes,
     * vertically offset to balance eyes and mouth, and scaled to [targetSize].
     * Otherwise, falls back to cropping the expanded face bounding box.
     */
    fun extractFaceCrop(sourceBitmap: Bitmap, face: Face, targetSize: Int): Bitmap {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        if (leftEye != null && rightEye != null) {
            val eyeDistance = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
            if (eyeDistance > 1.0f) {
                val cx = (leftEye.x + rightEye.x) / 2.0f
                val cy = (leftEye.y + rightEye.y) / 2.0f
                val eyeAngleRad = atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x)
                val eyeAngleDeg = Math.toDegrees(eyeAngleRad.toDouble()).toFloat()

                val faceCropFactor = 3.2f
                val faceVerticalOffsetFactor = 0.13f
                val cropWidth = eyeDistance * faceCropFactor
                val cropHeight = eyeDistance * faceCropFactor
                val verticalOffset = eyeDistance * faceVerticalOffsetFactor

                val scale = targetSize.toFloat() / cropWidth
                val matrix = Matrix().apply {
                    postTranslate(-cx, -cy)
                    postRotate(-eyeAngleDeg)
                    postTranslate(cropWidth / 2f, cropHeight / 2f - verticalOffset)
                    postScale(scale, scale)
                }

                val cropped = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                Canvas(cropped).drawBitmap(sourceBitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                return cropped
            }
        }

        // Fallback: bounding box crop
        val bb = face.boundingBox
        val marginX = (bb.width() * 0.1f).toInt()
        val marginY = (bb.height() * 0.1f).toInt()
        val left = (bb.left - marginX).coerceAtLeast(0)
        val top = (bb.top - marginY).coerceAtLeast(0)
        val right = (bb.right + marginX).coerceAtMost(sourceBitmap.width)
        val bottom = (bb.bottom + marginY).coerceAtMost(sourceBitmap.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        val croppedBb = Bitmap.createBitmap(sourceBitmap, left, top, width, height)
        return if (width == targetSize && height == targetSize) {
            croppedBb
        } else {
            val scaled = Bitmap.createScaledBitmap(croppedBb, targetSize, targetSize, true)
            if (scaled != croppedBb) {
                croppedBb.recycle()
            }
            scaled
        }
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            try {
                detector.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing face detector", e)
            }
        }
    }
}
