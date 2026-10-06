package org.multipaz.facenet

import androidx.camera.core.ImageProxy
import android.graphics.Bitmap
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

private const val TAG = "AndroidFaceNetLivenessSession"

internal class AndroidFaceNetLivenessSession(
    private val modelBytes: ByteString,
    config: FaceNetModelConfig,
    debug: Boolean = false,
    matcherName: String = "facenet",
    matcherDisplayName: String = "MobileFaceNet",
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceNetLivenessSessionBase<AndroidDetectedFace>(
    config = config,
    debug = debug,
    matcherName = matcherName,
    matcherDisplayName = matcherDisplayName,
    clock = clock
) {
    private var detector: AndroidFaceDetector? = null

    override suspend fun initializePipeline() {
        detector = AndroidFaceDetector()
        Logger.d(TAG, "Liveness pipeline initialized successfully")
    }

    override suspend fun detectFaces(frame: CameraFrame): List<AndroidDetectedFace> {
        val activeDetector = detector ?: return emptyList()
        return activeDetector.detectFaces(frame)
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

    override fun onSessionClosed() {
        try {
            detector?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing detector", e)
        }
        detector = null
    }
}
