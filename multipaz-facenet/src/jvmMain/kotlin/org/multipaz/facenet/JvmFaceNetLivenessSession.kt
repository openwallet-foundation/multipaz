package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "JvmFaceNetLivenessSession"

internal class JvmFaceNetLivenessSession(
    private val modelBytes: ByteString,
    config: FaceNetModelConfig,
    debug: Boolean = false,
    matcherName: String = "facenet",
    matcherDisplayName: String = "MobileFaceNet",
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceNetLivenessSessionBase<BlazeFaceDetection>(
    config = config,
    debug = debug,
    matcherName = matcherName,
    matcherDisplayName = matcherDisplayName,
    clock = clock
) {
    private var detector: JvmFaceDetector? = null

    override suspend fun initializePipeline() {
        detector = JvmFaceDetector()
        Logger.d(TAG, "Liveness pipeline initialized successfully")
    }

    override suspend fun captureHighResolutionImage(frame: CameraFrame): ByteString? {
        val activeDetector = detector ?: return null
        return activeDetector.captureUprightJpeg(frame)
    }

    override suspend fun detectFaces(frame: CameraFrame): List<BlazeFaceDetection> {
        val activeDetector = detector ?: return emptyList()
        return activeDetector.detectFaces(frame)
    }

    override fun onSessionClosed() {
        try {
            detector?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing detector", e)
        }
        detector = null
    }
}
