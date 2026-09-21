package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherSession

/**
 * A face matcher implementation based on Google FaceNet and MobileFaceNet models.
 *
 * @property modelBytesProvider a suspending lambda that supplies the `.tflite` model data.
 * @property config configuration parameters specifying input dimensions, normalization, and match threshold.
 * @property name unique machine identifier for this matcher.
 * @property displayName human-readable display name.
 */
class FaceNetFaceMatcher(
    val modelBytesProvider: suspend () -> ByteString,
    val config: FaceNetModelConfig = FaceNetModelConfig.AUTO,
    override val name: String = "facenet",
    override val displayName: String = "MobileFaceNet"
) : FaceMatcher {

    constructor(
        modelBytes: ByteString
    ) : this(
        modelBytes = modelBytes,
        config = FaceNetModelConfig.AUTO,
        name = "facenet",
        displayName = "MobileFaceNet"
    )

    constructor(
        modelBytes: ByteString,
        config: FaceNetModelConfig
    ) : this(
        modelBytes = modelBytes,
        config = config,
        name = "facenet",
        displayName = "MobileFaceNet"
    )

    constructor(
        modelBytes: ByteString,
        config: FaceNetModelConfig = FaceNetModelConfig.AUTO,
        name: String = "facenet",
        displayName: String = "MobileFaceNet"
    ) : this(
        modelBytesProvider = { modelBytes },
        config = config,
        name = name,
        displayName = displayName
    )

    /**
     * Whether native on-device face matching is supported on the current platform runtime.
     *
     * Returns true on Android and iOS; false on desktop JVM and Web where matching is simulated.
     */
    val isSupported: Boolean
        get() = isFaceNetSupported

    override fun createSession(referencePortrait: ByteString): FaceMatcherSession {
        return createFaceNetSession(
            referencePortrait = referencePortrait,
            modelBytesProvider = modelBytesProvider,
            config = config,
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
            modelBytesProvider = modelBytesProvider,
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
            modelBytesProvider = modelBytesProvider,
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
    referencePortrait: ByteString,
    modelBytesProvider: suspend () -> ByteString,
    config: FaceNetModelConfig,
    matcherName: String,
    matcherDisplayName: String
): FaceMatcherSession

internal expect suspend fun extractFaceEmbedding(
    portrait: ByteString,
    modelBytesProvider: suspend () -> ByteString,
    config: FaceNetModelConfig
): FaceEmbedding

internal expect suspend fun extractDetectedFaceCrop(
    portrait: ByteString,
    modelBytesProvider: suspend () -> ByteString,
    config: FaceNetModelConfig
): ByteString
