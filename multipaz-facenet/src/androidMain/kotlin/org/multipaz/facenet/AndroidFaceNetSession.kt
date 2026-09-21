package org.multipaz.facenet

import androidx.camera.core.ImageProxy
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherGraphics
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

private const val TAG = "AndroidFaceNetSession"

internal class AndroidFaceNetSession(
    referencePortrait: ByteString? = null,
    private val modelBytes: ByteString,
    config: FaceNetModelConfig,
    debug: Boolean = false,
    matcherName: String = "facenet",
    matcherDisplayName: String = "MobileFaceNet",
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceNetSessionBase<AndroidDetectedFace>(
    referencePortrait = referencePortrait,
    config = config,
    debug = debug,
    matcherName = matcherName,
    matcherDisplayName = matcherDisplayName,
    clock = clock
) {
    private var detector: AndroidFaceDetector? = null
    private var interpreter: AndroidFaceNetInterpreter? = null

    override fun buildDebugGraphics(
        frame: CameraFrame,
        faces: List<AndroidDetectedFace>,
        currentSimilarity: Float?
    ): FaceMatcherGraphics? {
        val base = super.buildDebugGraphics(frame, faces, currentSimilarity) ?: return null
        return base.copy(isMirrored = true)
    }

    override suspend fun initializePipeline() {
        val interp = AndroidFaceNetInterpreter(modelBytes, config)
        val det = AndroidFaceDetector()

        val refPortrait = referencePortrait
        if (refPortrait != null) {
            val refBytes = refPortrait.toByteArray()
            val refBitmap = BitmapFactory.decodeByteArray(refBytes, 0, refBytes.size)
                ?: throw IllegalArgumentException("Failed to decode reference portrait bytes to image")

            val refFaces = try {
                det.detectFaces(refBitmap)
            } finally {
                refBitmap.recycle()
            }

            if (refFaces.isEmpty()) {
                throw IllegalArgumentException("No face detected in reference portrait")
            }

            val refFaceCrop = try {
                val freshBitmap = BitmapFactory.decodeByteArray(refBytes, 0, refBytes.size)
                try {
                    det.extractFaceCrop(freshBitmap, refFaces[0], interp.imageSquareSize)
                } finally {
                    freshBitmap.recycle()
                }
            } catch (e: Exception) {
                throw IllegalStateException("Failed to extract face crop from reference portrait", e)
            }

            val embedding = interp.getEmbedding(refFaceCrop)
            refFaceCrop.recycle()

            if (embedding == null) {
                throw IllegalStateException("Failed to compute embedding from reference portrait")
            }
            referenceEmbedding = embedding
            Logger.d(TAG, "Pipeline initialized successfully with embedding size ${embedding.embedding.size}")
        } else {
            Logger.d(TAG, "Pipeline initialized for liveness only")
        }

        interpreter = interp
        detector = det
    }

    override suspend fun captureHighResolutionImage(frame: CameraFrame): ByteString? {
        val platformHandle = frame.platformHandle
        val (sourceBitmap, shouldRecycle) = when {
            platformHandle is ImageProxy -> {
                val rotationDegrees = platformHandle.imageInfo.rotationDegrees
                val rawBitmap = platformHandle.toBitmap()
                val activeDet = detector
                val bitmap = if (activeDet != null) {
                    activeDet.rotateBitmap(rawBitmap, rotationDegrees)
                } else {
                    rawBitmap
                }
                if (bitmap != rawBitmap) rawBitmap.recycle()
                Pair(bitmap, true)
            }
            platformHandle is Bitmap -> {
                val activeDet = detector
                val bitmap = if (activeDet != null) {
                    activeDet.rotateBitmap(platformHandle, frame.rotationDegrees)
                } else {
                    platformHandle
                }
                Pair(bitmap, bitmap != platformHandle)
            }
            frame.data.size > 0 -> {
                val bytes = frame.data.toByteArray()
                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    val activeDet = detector
                    val bitmap = if (activeDet != null) {
                        activeDet.rotateBitmap(rawBitmap, frame.rotationDegrees)
                    } else {
                        rawBitmap
                    }
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
            val stream = ByteArrayOutputStream()
            sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            return ByteString(stream.toByteArray())
        } finally {
            if (shouldRecycle) {
                sourceBitmap.recycle()
            }
        }
    }

    override suspend fun detectFaces(frame: CameraFrame): List<AndroidDetectedFace> {
        val activeDetector = detector ?: return emptyList()
        return activeDetector.detectFaces(frame)
    }

    override suspend fun computeCameraEmbedding(frame: CameraFrame, face: AndroidDetectedFace): FaceEmbedding? {
        val activeDetector = detector ?: return null
        val activeInterpreter = interpreter ?: return null
        val faceCrop: Bitmap = activeDetector.extractFaceCrop(frame, face, activeInterpreter.imageSquareSize)
            ?: return null
        val cameraEmbedding = try {
            activeInterpreter.getEmbedding(faceCrop)
        } finally {
            faceCrop.recycle()
        }
        return cameraEmbedding
    }

    override fun onSessionClosed() {
        try {
            detector?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing detector", e)
        }
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing interpreter", e)
        }
        detector = null
        interpreter = null
    }
}
