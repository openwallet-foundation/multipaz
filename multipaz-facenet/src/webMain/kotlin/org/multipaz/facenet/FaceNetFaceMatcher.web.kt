package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.SimulatedFaceMatcherSession

internal actual val isFaceNetSupported: Boolean = false

internal actual fun createFaceNetSession(
    referencePortrait: ByteString?,
    modelBytes: ByteString,
    config: FaceNetModelConfig,
    debug: Boolean,
    matcherName: String,
    matcherDisplayName: String
): FaceMatcherSession {
    return SimulatedFaceMatcherSession(referencePortrait = referencePortrait)
}

internal actual suspend fun extractFaceEmbedding(
    portrait: ByteString,
    modelBytes: ByteString,
    config: FaceNetModelConfig
): FaceEmbedding {
    throw UnsupportedOperationException("Face matching is not supported on this platform")
}

internal actual suspend fun extractDetectedFaceCrop(
    portrait: ByteString,
    modelBytes: ByteString,
    config: FaceNetModelConfig
): ByteString {
    throw UnsupportedOperationException("Face matching is not supported on this platform")
}

