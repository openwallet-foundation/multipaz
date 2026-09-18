package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.SimulatedFaceMatcherSession
import org.multipaz.util.Logger

private const val TAG = "FaceNetFaceMatcher"

internal actual fun createFaceNetSession(
    referencePortrait: ByteString,
    modelBytesProvider: (suspend () -> ByteString)?,
    config: FaceNetModelConfig,
    matcherName: String,
    matcherDisplayName: String
): FaceMatcherSession {
    return try {
        AndroidFaceNetSession(
            referencePortrait = referencePortrait,
            modelBytesProvider = modelBytesProvider,
            config = config,
            matcherName = matcherName,
            matcherDisplayName = matcherDisplayName
        )
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to instantiate AndroidFaceNetSession, falling back to simulated", e)
        SimulatedFaceMatcherSession(referencePortrait = referencePortrait)
    }
}
