package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherSession

/**
 * A face matcher implementation based on Google FaceNet and MobileFaceNet models.
 *
 * @property modelBytes the `.tflite` model data.
 * @property config configuration parameters specifying input dimensions, normalization, and match threshold.
 * @property name unique machine identifier for this matcher.
 * @property displayName human-readable display name.
 */
class FaceNetFaceMatcher(
    val modelBytes: ByteString,
    val config: FaceNetModelConfig = FaceNetModelConfig.AUTO,
    val debug: Boolean = false,
    override val name: String = if (debug) "facenet_debug" else "facenet",
    override val displayName: String = if (debug) "MobileFaceNet (debug)" else "MobileFaceNet"
) : FaceMatcher {

    override val providesGraphicsOverlay: Boolean
        get() = debug

    constructor(
        modelBytes: ByteString
    ) : this(
        modelBytes = modelBytes,
        config = FaceNetModelConfig.AUTO,
        debug = false,
        name = "facenet",
        displayName = "MobileFaceNet"
    )

    constructor(
        modelBytes: ByteString,
        debug: Boolean
    ) : this(
        modelBytes = modelBytes,
        config = FaceNetModelConfig.AUTO,
        debug = debug,
        name = if (debug) "facenet_debug" else "facenet",
        displayName = if (debug) "MobileFaceNet (debug)" else "MobileFaceNet"
    )

    constructor(
        modelBytes: ByteString,
        config: FaceNetModelConfig
    ) : this(
        modelBytes = modelBytes,
        config = config,
        debug = false,
        name = "facenet",
        displayName = "MobileFaceNet"
    )

    /**
     * Whether native on-device face matching is supported on the current platform runtime.
     *
     * Returns true on Android and iOS; false on desktop JVM and Web where matching is simulated.
     */
    val isSupported: Boolean
        get() = isFaceNetSupported

    override val supportsLiveness: Boolean
        get() = isSupported

    override fun createSession(referencePortrait: ByteString): FaceMatcherSession {
        return createFaceNetSession(
            referencePortrait = referencePortrait,
            modelBytes = modelBytes,
            config = config,
            debug = debug,
            matcherName = name,
            matcherDisplayName = displayName
        )
    }

    override fun createLivenessSession(): FaceMatcherSession {
        return createFaceNetSession(
            referencePortrait = null,
            modelBytes = modelBytes,
            config = config,
            debug = debug,
            matcherName = name,
            matcherDisplayName = displayName
        )
    }

    /**
     * Computes the biometric [FaceEmbedding] for the detected face in [portrait].
     *
     * Detects the face via BlazeFace, extracts a landmark-aligned crop, and runs embedding inference
     * using the configured FaceNet model.
     *
     * @param portrait encoded image bytes (e.g. JPEG or PNG).
     * @return the computed [FaceEmbedding].
     * @throws IllegalArgumentException if no face is detected in [portrait] or image decoding fails.
     * @throws IllegalStateException if crop extraction or embedding computation fails.
     * @throws UnsupportedOperationException if face matching is not supported on this platform.
     */
    suspend fun getFaceEmbedding(portrait: ByteString): FaceEmbedding {
        return extractFaceEmbedding(
            portrait = portrait,
            modelBytes = modelBytes,
            config = config
        )
    }

    /**
     * Matches two portrait images and returns their cosine similarity.
     *
     * Computes the biometric [FaceEmbedding] for each portrait and calculates their cosine similarity.
     *
     * @param portraitA first portrait image bytes.
     * @param portraitB second portrait image bytes.
     * @return cosine similarity value typically in range [-1.0, 1.0].
     * @throws IllegalArgumentException if no face is detected in either portrait.
     * @throws UnsupportedOperationException if face matching is not supported on this platform.
     */
    suspend fun matchPortraits(portraitA: ByteString, portraitB: ByteString): Float {
        val embA = getFaceEmbedding(portraitA)
        val embB = getFaceEmbedding(portraitB)
        return embA.calculateSimilarity(embB)
    }

    /**
     * Extracts the detected, aligned face crop image that is matched against.
     *
     * Detects the face via BlazeFace, aligns the eyes horizontally, crops, and scales
     * to the model's square input resolution (typically 112x112), returning the encoded PNG bytes.
     *
     * @param portrait encoded image bytes (e.g. JPEG or PNG).
     * @return the encoded PNG bytes of the aligned face crop.
     * @throws IllegalArgumentException if no face is detected in [portrait] or decoding fails.
     * @throws IllegalStateException if crop extraction fails.
     * @throws UnsupportedOperationException if face matching is not supported on this platform.
     */
    suspend fun extractFaceCrop(portrait: ByteString): ByteString {
        return extractDetectedFaceCrop(
            portrait = portrait,
            modelBytes = modelBytes,
            config = config
        )
    }

    /**
     * Extracts the detected, aligned face crop image that is matched against.
     *
     * Alias for [extractFaceCrop].
     */
    suspend fun getDetectedFace(portrait: ByteString): ByteString = extractFaceCrop(portrait)
}

internal expect val isFaceNetSupported: Boolean

internal expect fun createFaceNetSession(
    referencePortrait: ByteString?,
    modelBytes: ByteString,
    config: FaceNetModelConfig,
    debug: Boolean,
    matcherName: String,
    matcherDisplayName: String
): FaceMatcherSession

internal expect suspend fun extractFaceEmbedding(
    portrait: ByteString,
    modelBytes: ByteString,
    config: FaceNetModelConfig
): FaceEmbedding

internal expect suspend fun extractDetectedFaceCrop(
    portrait: ByteString,
    modelBytes: ByteString,
    config: FaceNetModelConfig
): ByteString
