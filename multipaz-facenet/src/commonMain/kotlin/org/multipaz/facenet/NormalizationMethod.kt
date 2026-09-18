package org.multipaz.facenet

/**
 * Preprocessing normalization method for face image tensors.
 */
enum class NormalizationMethod {
    /**
     * Standard score normalization: `(x - mean) / max(stdDev, 1 / sqrt(N))`
     * Standard for Google FaceNet models.
     */
    STANDARDIZE,

    /**
     * Fixed scaling to `[-1.0, 1.0]`: `(x - 127.5) / 128.0`
     * Standard for MobileFaceNet models.
     */
    SCALE_MINUS_ONE_TO_ONE
}
