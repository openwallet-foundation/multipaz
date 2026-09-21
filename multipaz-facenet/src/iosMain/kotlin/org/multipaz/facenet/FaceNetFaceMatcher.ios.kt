package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcherSession

internal actual val isFaceNetSupported: Boolean = true

internal actual fun createFaceNetSession(
    referencePortrait: ByteString,
    modelBytesProvider: suspend () -> ByteString,
    config: FaceNetModelConfig,
    matcherName: String,
    matcherDisplayName: String
): FaceMatcherSession {
    return IosFaceNetSession(
        referencePortrait = referencePortrait,
        modelBytesProvider = modelBytesProvider,
        config = config,
        matcherName = matcherName,
        matcherDisplayName = matcherDisplayName
    )
}

internal actual suspend fun extractFaceEmbedding(
    portrait: ByteString,
    modelBytesProvider: suspend () -> ByteString,
    config: FaceNetModelConfig
): FaceEmbedding {
    val modelBytes = modelBytesProvider()
    IosFaceNetInterpreter(modelBytes, config).use { interpreter ->
        IosFaceDetector().use { detector ->
            val bytes = portrait.toByteArray()
            val faces = detector.detectFaces(bytes)
            if (faces.isEmpty()) {
                throw IllegalArgumentException("No face detected in portrait")
            }
            val crop = detector.extractFaceCrop(bytes, faces[0], interpreter.imageSquareSize)
                ?: throw IllegalStateException("Failed to extract face crop")
            return interpreter.getEmbedding(crop)
                ?: throw IllegalStateException("Failed to compute face embedding")
        }
    }
}
