package org.multipaz.facenet

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaceEmbeddingRegressionTest {

    @Test
    fun testGoldenVectorsCosineSimilarity() {
        val sim = FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING.calculateSimilarity(
            FaceTestData.QUALCOMM_DEMO_2_GOLDEN_EMBEDDING
        )
        // Verify exact regression against Qualcomm MobileFaceNet golden output vectors
        assertEquals(
            FaceTestData.QUALCOMM_DEMO_PAIR_DIRECT_COSINE,
            sim,
            0.0001f,
            "Cosine similarity between golden demo vectors must match reference"
        )
        assertTrue(
            sim >= FaceNetModelConfig.MOBILE_FACENET.matchThreshold,
            "Golden pair similarity ($sim) must exceed match threshold (${FaceNetModelConfig.MOBILE_FACENET.matchThreshold})"
        )
    }

    @Test
    fun testGoldenEmbeddingProperties() {
        val emb1 = FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING
        val emb2 = FaceTestData.QUALCOMM_DEMO_2_GOLDEN_EMBEDDING

        assertEquals(128, emb1.embedding.size)
        assertEquals(128, emb2.embedding.size)

        // MobileFaceNet produces L2-normalized 128-dimensional unit vectors
        val norm1 = sqrt(emb1.embedding.sumOf { (it * it).toDouble() }.toFloat())
        val norm2 = sqrt(emb2.embedding.sumOf { (it * it).toDouble() }.toFloat())

        assertEquals(1.0f, norm1, 0.001f, "Embedding 1 should be L2 unit vector")
        assertEquals(1.0f, norm2, 0.001f, "Embedding 2 should be L2 unit vector")
    }

    @Test
    fun testCorpusImagesDecodeValidJpegs() {
        val corpus = listOf(
            "qualcomm_demo_1" to FaceTestData.QUALCOMM_DEMO_1_BASE64,
            "qualcomm_demo_2" to FaceTestData.QUALCOMM_DEMO_2_BASE64,
            "warren_portrait" to FaceTestData.WARREN_PORTRAIT_BASE64,
            "warren_portrait_114th" to FaceTestData.WARREN_PORTRAIT_114TH_BASE64,
            "erika_mustermann" to FaceTestData.ERIKA_MUSTERMANN_BASE64,
            "erika_mustermann_2001" to FaceTestData.ERIKA_MUSTERMANN_2001_BASE64,
            "male_portrait" to FaceTestData.MALE_PORTRAIT_BASE64,
            "female_portrait" to FaceTestData.FEMALE_PORTRAIT_BASE64,
            "bob_with_glasses_1" to FaceTestData.BOB_WITH_GLASSES_1_BASE64,
            "bob_with_glasses_2" to FaceTestData.BOB_WITH_GLASSES_2_BASE64,
            "bob_without_glasses_1" to FaceTestData.BOB_WITHOUT_GLASSES_1_BASE64,
            "bob_without_glasses_2" to FaceTestData.BOB_WITHOUT_GLASSES_2_BASE64,
        )

        for ((name, b64) in corpus) {
            val bytes = FaceTestData.decodeImageBytes(b64)
            assertTrue(bytes.size > 1000, "Image $name should be at least 1KB (was ${bytes.size})")

            // Check JPEG SOI marker (0xFF, 0xD8)
            assertEquals(0xFF.toByte(), bytes[0], "Image $name must start with 0xFF")
            assertEquals(0xD8.toByte(), bytes[1], "Image $name must start with 0xD8")

            // Check ByteString wrapper
            val byteString = FaceTestData.decodeImageByteString(b64)
            assertEquals(bytes.size, byteString.size)
        }
    }

    @Test
    fun testSimilarityBoundaryConditions() {
        val v = FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING
        assertEquals(1.0f, v.calculateSimilarity(v), 0.0001f, "Self similarity must be 1.0")

        val inverted = FaceEmbedding(FloatArray(128) { -v.embedding[it] })
        assertEquals(-1.0f, v.calculateSimilarity(inverted), 0.0001f, "Inverted similarity must be -1.0")

        val orthogonal = FaceEmbedding(FloatArray(128) { if (it < 64) v.embedding[it + 64] else -v.embedding[it - 64] })
        val orthoSim = v.calculateSimilarity(orthogonal)
        assertTrue(abs(orthoSim) < 0.001f, "Orthogonal similarity should be ~0.0 (was $orthoSim)")
    }
}
