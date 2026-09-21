package org.multipaz.facenet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "AndroidFaceNetSession"

internal class AndroidFaceNetSession(
    referencePortrait: ByteString,
    private val modelBytesProvider: (suspend () -> ByteString)?,
    config: FaceNetModelConfig,
    matcherName: String = "facenet",
    matcherDisplayName: String = "MobileFaceNet",
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceNetSessionBase<AndroidDetectedFace>(
    referencePortrait = referencePortrait,
    config = config,
    matcherName = matcherName,
    matcherDisplayName = matcherDisplayName,
    clock = clock
) {
    private var detector: AndroidFaceDetector? = null
    private var interpreter: AndroidFaceNetInterpreter? = null

    override suspend fun initializePipeline() {
        val modelBytes = modelBytesProvider?.invoke()
        val interp = AndroidFaceNetInterpreter(modelBytes, config)
        val det = AndroidFaceDetector()

        val refBytes = referencePortrait.toByteArray()
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

        interpreter = interp
        detector = det
        referenceEmbedding = embedding
        Logger.d(TAG, "Pipeline initialized successfully with embedding size ${embedding.embedding.size}")
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
