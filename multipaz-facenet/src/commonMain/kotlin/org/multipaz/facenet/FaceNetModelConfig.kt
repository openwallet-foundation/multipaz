package org.multipaz.facenet

/**
 * Configuration parameters for FaceNet and MobileFaceNet models.
 *
 * @property imageSquareSize the input image dimension in pixels (e.g. 160 for FaceNet, 112 for MobileFaceNet).
 *   If `null`, inferred from the TFLite input tensor shape at runtime.
 * @property embeddingDim the output embedding dimension (e.g. 512 for FaceNet-512, 128 for MobileFaceNet).
 *   If `null`, inferred from the TFLite output tensor shape at runtime.
 * @property normalization the tensor preprocessing normalization method to apply.
 * @property matchThreshold the minimum cosine similarity between reference portrait and camera face embeddings
 *   required to consider the identity verified.
 */
data class FaceNetModelConfig(
    val imageSquareSize: Int? = null,
    val embeddingDim: Int? = null,
    val normalization: NormalizationMethod = NormalizationMethod.SCALE_ZERO_TO_ONE,
    val matchThreshold: Float = 0.70f
) {
    companion object {
        /** Standard MobileFaceNet model configuration (112x112 input, 128-d output, [0, 1] scaling). */
        val MOBILE_FACENET = FaceNetModelConfig(
            imageSquareSize = 112,
            embeddingDim = 128,
            normalization = NormalizationMethod.SCALE_ZERO_TO_ONE,
            matchThreshold = 0.70f
        )

        /** Inferred configuration: image dimension and embedding size are read directly from model tensors. */
        val AUTO = FaceNetModelConfig()
    }
}
