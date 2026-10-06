package org.multipaz.facenet

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facenet.testdata.FaceTestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceNetFaceMatcherTest {

    @Test
    fun testProperties() {
        val matcher = FaceNetFaceMatcher(modelBytes = ByteString())
        assertEquals("facenet", matcher.name)
        assertEquals("MobileFaceNet", matcher.displayName)
    }

    @Test
    fun testCreateSessionSupportedFormats() = runTest {
        val matcher = FaceNetFaceMatcher(modelBytes = ByteString())

        // JPEG format
        val jpegPortrait = FaceTestData.decodeImageByteString(FaceTestData.QUALCOMM_DEMO_1_BASE64)
        val jpegSession = matcher.createSession(jpegPortrait)
        assertNotNull(jpegSession)
        assertEquals(FaceMatcherPromptState.Status.IN_PROGRESS, jpegSession.state.value.status)

        // PNG format
        val pngPortrait = FaceTestData.decodeImageByteString(FaceTestData.QUALCOMM_DEMO_1_PNG_BASE64)
        val pngSession = matcher.createSession(pngPortrait)
        assertNotNull(pngSession)
        assertEquals(FaceMatcherPromptState.Status.IN_PROGRESS, pngSession.state.value.status)

        // JPEG 2000 format
        val jp2Portrait = FaceTestData.decodeImageByteString(FaceTestData.QUALCOMM_DEMO_1_JP2_BASE64)
        val jp2Session = matcher.createSession(jp2Portrait)
        assertNotNull(jp2Session)
        assertEquals(FaceMatcherPromptState.Status.IN_PROGRESS, jp2Session.state.value.status)

        // Unsupported format: empty ByteString
        assertFailsWith<IllegalArgumentException> {
            matcher.createSession(ByteString())
        }

        // Unsupported format: raw invalid bytes
        assertFailsWith<IllegalArgumentException> {
            matcher.createSession(ByteString(byteArrayOf(1, 2, 3, 4)))
        }

        // Unsupported format: GIF header
        assertFailsWith<IllegalArgumentException> {
            matcher.createSession(ByteString("GIF89a".encodeToByteArray()))
        }
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
