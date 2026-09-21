package org.multipaz.facenet

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcherPromptState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceNetFaceMatcherTest {

    @Test
    fun testProperties() {
        val matcher = FaceNetFaceMatcher()
        assertEquals("facenet", matcher.name)
        assertEquals("MobileFaceNet", matcher.displayName)
    }

    @Test
    fun testCreateSession() = runTest {
        val matcher = FaceNetFaceMatcher()
        val session = matcher.createSession(ByteString())
        assertNotNull(session)
        assertEquals(FaceMatcherPromptState.NUM_RING_SEGMENTS, session.state.value.ringSegments.size)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session.state.value.outcome)
    }

    @Test
    fun testFaceEmbeddingCosineSimilarity() {
        val v1 = FaceEmbedding(floatArrayOf(1.0f, 0.0f, 0.0f))
        val v2 = FaceEmbedding(floatArrayOf(1.0f, 0.0f, 0.0f))
        val v3 = FaceEmbedding(floatArrayOf(0.0f, 1.0f, 0.0f))
        val v4 = FaceEmbedding(floatArrayOf(-1.0f, 0.0f, 0.0f))

        // Identical vectors -> similarity = 1.0
        val simIdentical = v1.calculateSimilarity(v2)
        assertTrue(simIdentical > 0.999f && simIdentical <= 1.001f)

        // Orthogonal vectors -> similarity = 0.0
        val simOrthogonal = v1.calculateSimilarity(v3)
        assertTrue(kotlin.math.abs(simOrthogonal) < 0.001f)

        // Opposite vectors -> similarity = -1.0
        val simOpposite = v1.calculateSimilarity(v4)
        assertTrue(simOpposite < -0.999f && simOpposite >= -1.001f)
    }

    @Test
    fun testFaceNetModelConfigs() {
        assertEquals(160, FaceNetModelConfig.FACENET_512.imageSquareSize)
        assertEquals(512, FaceNetModelConfig.FACENET_512.embeddingDim)
        assertEquals(NormalizationMethod.STANDARDIZE, FaceNetModelConfig.FACENET_512.normalization)
        assertEquals(0.70f, FaceNetModelConfig.FACENET_512.matchThreshold)

        assertEquals(112, FaceNetModelConfig.MOBILE_FACENET.imageSquareSize)
        assertEquals(128, FaceNetModelConfig.MOBILE_FACENET.embeddingDim)
        assertEquals(NormalizationMethod.SCALE_ZERO_TO_ONE, FaceNetModelConfig.MOBILE_FACENET.normalization)
        assertEquals(0.70f, FaceNetModelConfig.MOBILE_FACENET.matchThreshold)

        val auto = FaceNetModelConfig.AUTO
        assertEquals(null, auto.imageSquareSize)
        assertEquals(null, auto.embeddingDim)
    }
}
