package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherSession

/**
 * A face matcher implementation based on Google FaceNet and MobileFaceNet models.
 *
 * @property modelBytesProvider an optional suspending lambda that supplies the `.tflite` model data.
 *   If not provided, the platform implementation will attempt to resolve a default model (e.g. from assets)
 *   or fall back to simulated verification.
 * @property config configuration parameters specifying input dimensions, normalization, and match threshold.
 * @property name unique machine identifier for this matcher.
 * @property displayName human-readable display name.
 */
class FaceNetFaceMatcher(
    val modelBytesProvider: (suspend () -> ByteString)? = null,
    val config: FaceNetModelConfig = FaceNetModelConfig.AUTO,
    override val name: String = "facenet",
    override val displayName: String = "Google FaceNet"
) : FaceMatcher {

    constructor() : this(modelBytesProvider = null, config = FaceNetModelConfig.AUTO)

    override fun createSession(referencePortrait: ByteString): FaceMatcherSession {
        return createFaceNetSession(
            referencePortrait = referencePortrait,
            modelBytesProvider = modelBytesProvider,
            config = config,
            matcherName = name,
            matcherDisplayName = displayName
        )
    }
}

internal expect fun createFaceNetSession(
    referencePortrait: ByteString,
    modelBytesProvider: (suspend () -> ByteString)?,
    config: FaceNetModelConfig,
    matcherName: String,
    matcherDisplayName: String
): FaceMatcherSession
