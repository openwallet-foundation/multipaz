package org.multipaz.facenet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.applicationContext
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.SimulatedFaceMatcherSession
import org.multipaz.util.Logger

private const val TAG = "FaceNetFaceMatcher"

internal actual val isFaceNetSupported: Boolean
    get() = try {
        applicationContext.packageName.isNotEmpty()
    } catch (e: Throwable) {
        false
    }

internal actual fun createFaceNetSession(
    referencePortrait: ByteString?,
    modelBytes: ByteString,
    config: FaceNetModelConfig,
    debug: Boolean,
    matcherName: String,
    matcherDisplayName: String
): FaceMatcherSession {
    return try {
        AndroidFaceNetSession(
            referencePortrait = referencePortrait,
            modelBytes = modelBytes,
            config = config,
            debug = debug,
            matcherName = matcherName,
            matcherDisplayName = matcherDisplayName
        )
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to instantiate AndroidFaceNetSession, falling back to simulated", e)
        SimulatedFaceMatcherSession(referencePortrait = referencePortrait)
    }
}

internal actual suspend fun extractFaceEmbedding(
    portrait: ByteString,
    modelBytes: ByteString,
    config: FaceNetModelConfig
): FaceEmbedding {
    AndroidFaceNetInterpreter(modelBytes, config).use { interpreter ->
        AndroidFaceDetector().use { detector ->
            val bytes = portrait.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Failed to decode portrait bytes to bitmap")
            val faces = try {
                detector.detectFaces(bitmap)
            } finally {
                bitmap.recycle()
            }
            if (faces.isEmpty()) {
                throw IllegalArgumentException("No face detected in portrait")
            }
            val freshBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val crop = try {
                detector.extractFaceCrop(freshBitmap, faces[0], interpreter.imageSquareSize)
            } finally {
                freshBitmap.recycle()
            }
            return try {
                interpreter.getEmbedding(crop)
                    ?: throw IllegalStateException("Failed to compute face embedding")
            } finally {
                crop.recycle()
            }
        }
    }
}

internal actual suspend fun extractDetectedFaceCrop(
    portrait: ByteString,
    modelBytes: ByteString,
    config: FaceNetModelConfig
): ByteString {
    val targetSize = config.imageSquareSize ?: 112
    AndroidFaceDetector().use { detector ->
        val bytes = portrait.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Failed to decode portrait bytes to bitmap")
        val faces = try {
            detector.detectFaces(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (faces.isEmpty()) {
            throw IllegalArgumentException("No face detected in portrait")
        }
        val freshBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val crop = try {
            detector.extractFaceCrop(freshBitmap, faces[0], targetSize)
        } finally {
            freshBitmap.recycle()
        }
        try {
            val stream = java.io.ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.PNG, 100, stream)
            return ByteString(stream.toByteArray())
        } finally {
            crop.recycle()
        }
    }
}

