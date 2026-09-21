package org.multipaz.facenet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.context.applicationContext
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

private const val TAG = "AndroidFaceDetector"

typealias AndroidDetectedFace = BlazeFaceDetection

/**
 * Cross-platform face detector for Android based on Google MediaPipe BlazeFace short-range TFLite model.
 *
 * Runs deterministic face detection and 6-landmark extraction via LiteRT on Android,
 * eliminating external Google Play Services / MLKit dependencies and ensuring byte-level
 * alignment parity with the iOS pipeline.
 */
internal class AndroidFaceDetector(
    modelBytes: ByteString? = null
) : AutoCloseable {

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val lock = Any()
    @Volatile
    private var isClosed = false

    private val regressorsIndex: Int
    private val classificatorsIndex: Int

    init {
        val bytes = when {
            modelBytes != null && !modelBytes.isEmpty() -> modelBytes.toByteArray()
            else -> {
                val ctx = try { applicationContext } catch (e: Throwable) { null }
                tryLoadFromAssets(ctx, "face_detection_short_range.tflite")
                    ?: BlazeFaceModelData.defaultModelBytes.toByteArray()
            }
        }

        val modelByteBuffer = ByteBuffer.allocateDirect(bytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(bytes)
            rewind()
        }

        val interpreterOptions = Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = true
        }

        interpreter = Interpreter(modelByteBuffer, interpreterOptions)

        val out0 = interpreter.getOutputTensor(0)
        val out0Shape = out0.shape()
        val out0LastDim = if (out0Shape.isNotEmpty()) out0Shape[out0Shape.size - 1] else 16
        if (out0LastDim == 16) {
            regressorsIndex = 0
            classificatorsIndex = 1
        } else {
            regressorsIndex = 1
            classificatorsIndex = 0
        }

        Logger.d(
            TAG,
            "BlazeFace detector initialized on Android: regressorsIndex=$regressorsIndex, " +
                    "classificatorsIndex=$classificatorsIndex"
        )
    }

    fun detectFaces(bitmap: Bitmap, rotationDegrees: Int = 0): List<AndroidDetectedFace> {
        synchronized(lock) {
            if (isClosed) return emptyList()

            val upright = rotateBitmap(bitmap, rotationDegrees)
            val shouldRecycleUpright = upright != bitmap

            try {
                val imgW = upright.width
                val imgH = upright.height
                if (imgW <= 0 || imgH <= 0) return emptyList()

                val maxDim = max(imgW, imgH)
                val scale = 128.0f / maxDim
                val dw = (imgW * scale).toInt().coerceAtLeast(1)
                val dh = (imgH * scale).toInt().coerceAtLeast(1)
                val dx = (128 - dw) / 2
                val dy = (128 - dh) / 2

                val scaled = if (upright.width == dw && upright.height == dh) {
                    upright
                } else {
                    Bitmap.createScaledBitmap(upright, dw, dh, true)
                }

                val inputBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(inputBitmap)
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(scaled, dx.toFloat(), dy.toFloat(), null)
                if (scaled != upright) {
                    scaled.recycle()
                }

                val pixels = IntArray(128 * 128)
                inputBitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                inputBitmap.recycle()

                val inputBuffer = ByteBuffer.allocateDirect(128 * 128 * 3 * Float.SIZE_BYTES).apply {
                    order(ByteOrder.nativeOrder())
                }
                for (p in pixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    inputBuffer.putFloat((r - 127.5f) / 127.5f)
                    inputBuffer.putFloat((g - 127.5f) / 127.5f)
                    inputBuffer.putFloat((b - 127.5f) / 127.5f)
                }
                inputBuffer.rewind()

                val rawBoxesArray = Array(1) { Array(BlazeFaceDecoder.NUM_ANCHORS) { FloatArray(BlazeFaceDecoder.NUM_COORDS) } }
                val rawScoresArray = Array(1) { Array(BlazeFaceDecoder.NUM_ANCHORS) { FloatArray(1) } }

                val outputsMap = mutableMapOf<Int, Any>()
                outputsMap[regressorsIndex] = rawBoxesArray
                outputsMap[classificatorsIndex] = rawScoresArray

                interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputsMap)

                val rawBoxes = FloatArray(BlazeFaceDecoder.NUM_ANCHORS * BlazeFaceDecoder.NUM_COORDS)
                var boxIdx = 0
                for (i in 0 until BlazeFaceDecoder.NUM_ANCHORS) {
                    for (c in 0 until BlazeFaceDecoder.NUM_COORDS) {
                        rawBoxes[boxIdx++] = rawBoxesArray[0][i][c]
                    }
                }

                val rawScores = FloatArray(BlazeFaceDecoder.NUM_ANCHORS)
                for (i in 0 until BlazeFaceDecoder.NUM_ANCHORS) {
                    rawScores[i] = rawScoresArray[0][i][0]
                }

                return BlazeFaceDecoder.decode(
                    rawBoxes = rawBoxes,
                    rawScores = rawScores,
                    imageWidth = imgW.toDouble(),
                    imageHeight = imgH.toDouble()
                )
            } finally {
                if (shouldRecycleUpright) {
                    upright.recycle()
                }
            }
        }
    }

    fun detectFaces(frame: CameraFrame): List<AndroidDetectedFace> {
        val platformHandle = frame.platformHandle
        return when {
            platformHandle is ImageProxy -> {
                val rotationDegrees = platformHandle.imageInfo.rotationDegrees
                val rawBitmap = platformHandle.toBitmap()
                try {
                    detectFaces(rawBitmap, rotationDegrees)
                } finally {
                    rawBitmap.recycle()
                }
            }
            platformHandle is Bitmap -> {
                detectFaces(platformHandle, frame.rotationDegrees)
            }
            frame.data.size > 0 -> {
                val bytes = frame.data.toByteArray()
                val rawBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    try {
                        detectFaces(rawBitmap, frame.rotationDegrees)
                    } finally {
                        rawBitmap.recycle()
                    }
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    fun extractFaceCrop(frame: CameraFrame, face: AndroidDetectedFace, targetSize: Int): Bitmap? {
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

    fun extractFaceCrop(sourceBitmap: Bitmap, face: AndroidDetectedFace, targetSize: Int): Bitmap {
        val leftEye = face.leftEye
        val rightEye = face.rightEye

        val eyeDistance = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
        if (eyeDistance > 1.0) {
            val cx = (leftEye.x + rightEye.x) / 2.0
            val cy = (leftEye.y + rightEye.y) / 2.0
            val eyeAngleRad = atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x)
            val eyeAngleDeg = Math.toDegrees(eyeAngleRad).toFloat()

            val faceCropFactor = 3.2
            val faceVerticalOffsetFactor = 0.13
            val cropWidth = eyeDistance * faceCropFactor
            val cropHeight = eyeDistance * faceCropFactor
            val verticalOffset = eyeDistance * faceVerticalOffsetFactor

            val scale = targetSize.toFloat() / cropWidth.toFloat()
            val matrix = Matrix().apply {
                postTranslate(-cx.toFloat(), -cy.toFloat())
                postRotate(-eyeAngleDeg)
                postTranslate(cropWidth.toFloat() / 2f, cropHeight.toFloat() / 2f - verticalOffset.toFloat())
                postScale(scale, scale)
            }

            val cropped = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            Canvas(cropped).drawBitmap(sourceBitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            return cropped
        }

        // Fallback: bounding box crop
        val bb = face.boundingBox
        val marginX = (bb.width * 0.1).toInt()
        val marginY = (bb.height * 0.1).toInt()
        val left = (bb.left.toInt() - marginX).coerceAtLeast(0)
        val top = (bb.top.toInt() - marginY).coerceAtLeast(0)
        val right = (bb.right.toInt() + marginX).coerceAtMost(sourceBitmap.width)
        val bottom = (bb.bottom.toInt() + marginY).coerceAtMost(sourceBitmap.height)
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

    internal fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val normDegrees = ((degrees % 360) + 360) % 360
        if (normDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            try {
                interpreter.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing face detector interpreter", e)
            }
            try {
                gpuDelegate?.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing GPU delegate", e)
            }
            gpuDelegate = null
        }
    }

    companion object {
        private fun tryLoadFromAssets(context: Context?, assetName: String): ByteArray? {
            if (context == null) return null
            return try {
                context.assets.open(assetName).use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
    }
}
