package org.multipaz.facenet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "IosFaceNetSession"

@OptIn(ExperimentalForeignApi::class)
internal class IosFaceNetSession(
    referencePortrait: ByteString,
    private val modelBytesProvider: suspend () -> ByteString,
    config: FaceNetModelConfig,
    debug: Boolean = false,
    matcherName: String = "facenet",
    matcherDisplayName: String = "MobileFaceNet",
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceNetSessionBase<IosDetectedFace>(
    referencePortrait = referencePortrait,
    config = config,
    debug = debug,
    matcherName = matcherName,
    matcherDisplayName = matcherDisplayName,
    clock = clock
) {
    private var detector: IosFaceDetector? = null
    private var interpreter: IosFaceNetInterpreter? = null

    override suspend fun initializePipeline() {
        val modelBytes = modelBytesProvider()
        val interp = IosFaceNetInterpreter(modelBytes, config)
        val det = IosFaceDetector()

        val refBytes = referencePortrait.toByteArray()
        val refFaces = det.detectFaces(refBytes)
        if (refFaces.isEmpty()) {
            throw IllegalArgumentException("No face detected in reference portrait")
        }

        val refFaceCrop = det.extractFaceCrop(refBytes, refFaces[0], interp.imageSquareSize)
            ?: throw IllegalStateException("Failed to extract face crop from reference portrait")

        val embedding = interp.getEmbedding(refFaceCrop)
            ?: throw IllegalStateException("Failed to compute embedding from reference portrait")

        interpreter = interp
        detector = det
        referenceEmbedding = embedding
        Logger.d(TAG, "Pipeline initialized successfully with embedding size ${embedding.embedding.size}")
    }

    override suspend fun detectFaces(frame: CameraFrame): List<IosDetectedFace> {
        val activeDetector = detector ?: return emptyList()
        return activeDetector.detectFaces(frame)
    }

    override suspend fun computeCameraEmbedding(frame: CameraFrame, face: IosDetectedFace): FaceEmbedding? {
        val activeDetector = detector ?: return null
        val activeInterpreter = interpreter ?: return null
        val faceCrop = activeDetector.extractFaceCrop(frame, face, activeInterpreter.imageSquareSize) ?: return null
        return activeInterpreter.getEmbedding(faceCrop)
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
