package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.SimulatedFaceMatcherSession

internal actual fun createFaceNetSession(
    referencePortrait: ByteString,
    modelBytesProvider: (suspend () -> ByteString)?,
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
