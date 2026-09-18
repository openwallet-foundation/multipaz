package org.multipaz.facenet

import kotlin.math.sqrt

/**
 * Normalized multidimensional face embeddings vector.
 *
 * @param embedding the float array vector produced by model inference.
 */
data class FaceEmbedding(val embedding: FloatArray) {
    /**
     * Calculates the cosine similarity between two embedding vectors.
     *
     * Cosine similarity is computed as:
     * $$S = \frac{\mathbf{A} \cdot \mathbf{B}}{\|\mathbf{A}\| \|\mathbf{B}\|}$$
     *
     * @param other the other face embedding to compare against.
     * @return cosine similarity in the range `[-1.0, 1.0]`. A higher value indicates higher similarity.
     */
    fun calculateSimilarity(other: FaceEmbedding): Float {
        val otherEmbedding = other.embedding
        require(embedding.size == otherEmbedding.size) {
            "Embedding vectors must have the same size (${embedding.size} vs ${otherEmbedding.size})"
        }

        var mag1 = 0.0f
        var mag2 = 0.0f
        var product = 0.0f

        for (i in embedding.indices) {
            val a = embedding[i]
            val b = otherEmbedding[i]
            mag1 += a * a
            mag2 += b * b
            product += a * b
        }

        val denominator = sqrt(mag1) * sqrt(mag2)
        return if (denominator > 0.0f) product / denominator else 0.0f
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbedding) return false
        return embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        return embedding.contentHashCode()
    }
}
