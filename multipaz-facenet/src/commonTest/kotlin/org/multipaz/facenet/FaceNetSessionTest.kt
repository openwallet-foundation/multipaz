package org.multipaz.facenet

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.facenet.testdata.FaceTestData
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherLivenessPromptState
import org.multipaz.facematch.FaceMatcherPromptState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceNetSessionTest {

    private class MockDetectedFace(
        override val yaw: Float = 0f,
        override val pitch: Float = 0f,
        override val roll: Float = 0f
    ) : DetectedFacePose

    private class TestFaceNetSession(
        referencePortrait: ByteString = ByteString(),
        config: FaceNetModelConfig = FaceNetModelConfig.MOBILE_FACENET,
        debug: Boolean = false,
        clock: () -> Long,
        randomSeed: Long = 42L
    ) : FaceNetSessionBase<DetectedFacePose>(
        referencePortrait = referencePortrait,
        config = config,
        debug = debug,
        matcherName = "facenet",
        matcherDisplayName = "MobileFaceNet",
        clock = clock,
        randomSeed = randomSeed
    ) {
        var mockFaces: List<DetectedFacePose> = emptyList()
        var mockEmbedding: FaceEmbedding? = null
        var pipelineInitError: Exception? = null
        var isClosed = false

        override suspend fun initializePipeline() {
            pipelineInitError?.let { throw it }
            referenceEmbedding = FaceEmbedding(FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING)
        }

        override suspend fun detectFaces(frame: CameraFrame): List<DetectedFacePose> {
            return mockFaces
        }

        override suspend fun computeCameraEmbedding(frame: CameraFrame, face: DetectedFacePose): FaceEmbedding? {
            return mockEmbedding
        }

        override fun onSessionClosed() {
            isClosed = true
        }
    }

    private class TestFaceNetLivenessSession(
        config: FaceNetModelConfig = FaceNetModelConfig.MOBILE_FACENET,
        debug: Boolean = false,
        clock: () -> Long,
        randomSeed: Long = 42L
    ) : FaceNetLivenessSessionBase<DetectedFacePose>(
        config = config,
        debug = debug,
        matcherName = "facenet",
        matcherDisplayName = "MobileFaceNet",
        clock = clock,
        randomSeed = randomSeed
    ) {
        var mockFaces: List<DetectedFacePose> = emptyList()
        var pipelineInitError: Exception? = null
        var isClosed = false
        var capturedBytes: ByteString? = ByteString(byteArrayOf(1, 2, 3))

        override suspend fun initializePipeline() {
            pipelineInitError?.let { throw it }
        }

        override suspend fun captureHighResolutionImage(frame: CameraFrame): ByteString? {
            return capturedBytes
        }

        override suspend fun detectFaces(frame: CameraFrame): List<DetectedFacePose> {
            return mockFaces
        }

        override fun onSessionClosed() {
            isClosed = true
        }
    }

    private val dummyFrame = CameraFrame(
        width = 640,
        height = 480,
        rotationDegrees = 0
    )

    @Test
    fun testInitialState() {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })

        assertEquals("Verify Identity", session.state.value.messageAbove)
        assertEquals("Position your face and look at the camera", session.state.value.messageBelow)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session.state.value.outcome)
        assertEquals(RingSegment.NUM_SEGMENTS, session.ringSegments.size)
        assertTrue(session.ringSegments.all { it.color == RingSegment.COLOR_DARK_GRAY })
    }

    @Test
    fun testSessionTimeoutFailsSession() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })

        // First frame establishes startTime = 1000L
        session.mockFaces = listOf(MockDetectedFace())
        session.feedFrame(dummyFrame)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session.state.value.outcome)

        // Advance past sessionTimeoutMs (25000L)
        currentTime = 1000L + 26000L
        session.feedFrame(dummyFrame)

        assertEquals(FaceMatcherPromptState.Outcome.FAILED, session.state.value.outcome)
        assertEquals("Verification Failed", session.state.value.messageAbove)
        assertEquals("Face verification timed out (0% match, required 70%)", session.state.value.messageBelow)
        assertTrue(session.ringSegments.all { it.color == RingSegment.COLOR_RED })
    }

    @Test
    fun testPipelineInitializationFailure() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })
        session.pipelineInitError = IllegalStateException("Corrupt TFLite model data")

        session.feedFrame(dummyFrame)

        assertEquals(FaceMatcherPromptState.Outcome.FAILED, session.state.value.outcome)
        assertEquals("Initialization Failed", session.state.value.messageAbove)
        assertEquals("Corrupt TFLite model data", session.state.value.messageBelow)
    }

    @Test
    fun testNoFaceDetectedAfterThreeFrames() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })
        session.mockFaces = emptyList()

        // Frames 1 and 2: no face, missed count increments
        session.feedFrame(dummyFrame)
        assertEquals("Position your face", session.state.value.messageAbove)

        session.feedFrame(dummyFrame)
        assertEquals("Position your face", session.state.value.messageAbove)

        // Frame 3: missedFaceFrames >= 3 triggers "No face detected"
        session.feedFrame(dummyFrame)
        assertEquals("Position your face", session.state.value.messageAbove)
        assertEquals("No face detected", session.state.value.messageBelow)
    }

    @Test
    fun testMultipleFacesDetected() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })
        session.mockFaces = listOf(MockDetectedFace(), MockDetectedFace())

        session.feedFrame(dummyFrame)

        assertEquals("Multiple faces detected", session.state.value.messageAbove)
        assertEquals("Ensure only one person is in the frame", session.state.value.messageBelow)
    }

    @Test
    fun testPoseGuidancePrompts() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })

        // Initialize positioning phase with 1 straight frame
        session.mockFaces = listOf(MockDetectedFace(yaw = 0f, pitch = 0f, roll = 0f))
        session.feedFrame(dummyFrame)

        // Turn right prompt (yaw > 12)
        session.mockFaces = listOf(MockDetectedFace(yaw = 20f))
        session.feedFrame(dummyFrame)
        assertEquals("Turn your head slightly to the right", session.state.value.messageBelow)

        // Turn left prompt (yaw < -12)
        session.mockFaces = listOf(MockDetectedFace(yaw = -20f))
        session.feedFrame(dummyFrame)
        assertEquals("Turn your head slightly to the left", session.state.value.messageBelow)

        // Tilt down prompt (pitch > 12)
        session.mockFaces = listOf(MockDetectedFace(pitch = 20f))
        session.feedFrame(dummyFrame)
        assertEquals("Tilt your head slightly down", session.state.value.messageBelow)

        // Tilt up prompt (pitch < -12)
        session.mockFaces = listOf(MockDetectedFace(pitch = -20f))
        session.feedFrame(dummyFrame)
        assertEquals("Tilt your head slightly up", session.state.value.messageBelow)

        // Roll prompt (roll > 15 or < -15)
        session.mockFaces = listOf(MockDetectedFace(roll = 25f))
        session.feedFrame(dummyFrame)
        assertEquals("Keep your head level", session.state.value.messageBelow)

        session.mockFaces = listOf(MockDetectedFace(roll = -25f))
        session.feedFrame(dummyFrame)
        assertEquals("Keep your head level", session.state.value.messageBelow)
    }

    @Test
    fun testMatchTimeoutWithFormattedPercentageError() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })

        // Similarity = 0.55f (below 0.70 threshold)
        // Cosine with reference: create vector with known dot product
        val v = FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING
        // Create an embedding that yields ~0.55 similarity
        val lowMatchEmb = FaceEmbedding(FloatArray(128) {
            v[it] * 0.55f + (if (it < 64) v[it + 64] else -v[it - 64]) * 0.835164f
        })
        session.mockFaces = listOf(MockDetectedFace())
        session.mockEmbedding = lowMatchEmb

        // First straight face frame at t = 1000
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.POSITIONING, session.phase)
        assertEquals("Verifying Identity", session.state.value.messageAbove)

        // Advance 4 seconds (still within matchTimeoutMs)
        currentTime = 5000L
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.POSITIONING, session.phase)

        // Advance past matchTimeoutMs (8000ms from t = 1000 => t >= 9001)
        currentTime = 10000L
        session.feedFrame(dummyFrame)

        assertEquals(FaceNetSessionBase.Phase.FAILED, session.phase)
        assertEquals(FaceMatcherPromptState.Outcome.FAILED, session.state.value.outcome)
        assertEquals("Verification Failed", session.state.value.messageAbove)
        assertTrue(
            session.state.value.messageBelow?.startsWith("Face does not match reference portrait (55% match, required 70%)") == true,
            "Expected formatted mismatch message with percentage but got: ${session.state.value.messageBelow}"
        )
    }

    @Test
    fun testLatchingPreventionRequiresTwoConsecutiveMatchingFrames() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })

        val matchingEmb = FaceEmbedding(FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING)
        val nonMatchingEmb = FaceTestData.QUALCOMM_DEMO_2_GOLDEN_EMBEDDING.let { golden ->
            // Inverted vector -> negative similarity
            FaceEmbedding(FloatArray(128) { idx -> -golden[idx] })
        }

        session.mockFaces = listOf(MockDetectedFace())

        // Frame 1: matching embedding -> consecutiveMatchFrames = 1 (should NOT advance yet)
        session.mockEmbedding = matchingEmb
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.POSITIONING, session.phase)

        // Frame 2: non-matching embedding -> resets consecutiveMatchFrames to 0
        session.mockEmbedding = nonMatchingEmb
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.POSITIONING, session.phase)

        // Frame 3: matching embedding -> consecutiveMatchFrames = 1
        session.mockEmbedding = matchingEmb
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.POSITIONING, session.phase)

        // Frame 4: second consecutive matching embedding -> consecutiveMatchFrames = 2 -> advances!
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.MATCH_CONVEYED, session.phase)
        assertEquals("Face Matched", session.state.value.messageAbove)
        assertEquals("Hold still...", session.state.value.messageBelow)
    }

    @Test
    fun testFullVerificationLifecycleToCompletion() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime }, randomSeed = 100L)
        val matchingEmb = FaceEmbedding(FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING)

        session.mockFaces = listOf(MockDetectedFace())
        session.mockEmbedding = matchingEmb

        // 2 consecutive matching frames to pass POSITIONING
        session.feedFrame(dummyFrame)
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.MATCH_CONVEYED, session.phase)

        // Advance past matchConveyDurationMs (1200ms) to enter LIVENESS_CHALLENGE
        currentTime += 1300L
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetSessionBase.Phase.LIVENESS_CHALLENGE, session.phase)
        assertEquals(0, session.currentChallengeIndex)

        // Complete each challenge in sequence
        for (step in session.challenges.indices) {
            val direction = session.challenges[step]
            val pose = when (direction) {
                ChallengeDirection.LEFT -> MockDetectedFace(yaw = 20f)
                ChallengeDirection.RIGHT -> MockDetectedFace(yaw = -20f)
                ChallengeDirection.UP -> MockDetectedFace(pitch = 16f)
                ChallengeDirection.DOWN -> MockDetectedFace(pitch = -16f)
                ChallengeDirection.CENTER -> MockDetectedFace(yaw = 0f, pitch = 0f)
            }
            session.mockFaces = listOf(pose)

            // Requires 3 consecutive frames with progress >= 0.85f
            session.feedFrame(dummyFrame)
            session.feedFrame(dummyFrame)
            session.feedFrame(dummyFrame)
        }

        // All challenges completed -> SUCCESS
        assertEquals(FaceNetSessionBase.Phase.COMPLETED, session.phase)
        assertEquals(FaceMatcherPromptState.Outcome.SUCCESS, session.state.value.outcome)
        assertEquals("Identity Verified", session.state.value.messageAbove)
        assertEquals("Verification successful", session.state.value.messageBelow)
        assertTrue(session.ringSegments.all { it.color == RingSegment.COLOR_GREEN })
    }

    @Test
    fun testCancellationReleasesResources() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })

        assertFalse(session.isCancelled)
        assertFalse(session.isClosed)

        session.cancel()

        assertTrue(session.isCancelled)
        assertTrue(session.isClosed)

        // Further frames are ignored
        session.mockFaces = listOf(MockDetectedFace())
        session.feedFrame(dummyFrame)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session.state.value.outcome)
    }

    @Test
    fun testDebugOverlay() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(
            debug = true,
            clock = { currentTime }
        )

        val detection = BlazeFaceDetection(
            score = 0.95f,
            boundingBox = FaceBoundingBox(50.0, 60.0, 100.0, 120.0),
            rightEye = FacePoint2D(70f, 90f),
            leftEye = FacePoint2D(130f, 90f),
            noseTip = FacePoint2D(100f, 120f),
            mouthCenter = FacePoint2D(100f, 150f),
            rightEarTragus = FacePoint2D(40f, 100f),
            leftEarTragus = FacePoint2D(160f, 100f),
            yaw = 5f,
            pitch = -3f,
            roll = 0f
        )
        session.mockFaces = listOf(detection)
        session.mockEmbedding = FaceEmbedding(FaceTestData.QUALCOMM_DEMO_1_GOLDEN_EMBEDDING)
        session.feedFrame(dummyFrame)

        val overlay = session.state.value.overlay
        assertNotNull(overlay)
        assertEquals(dummyFrame.uprightWidth, overlay.width)
        assertEquals(dummyFrame.uprightHeight, overlay.height)
        assertNotNull(overlay.platformHandle)

        // When no face is detected, overlay continues to show ring
        session.mockFaces = emptyList()
        session.feedFrame(dummyFrame)
        assertNotNull(session.state.value.overlay)
    }

    @Test
    fun testLivenessSessionWithImageCapture() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetLivenessSession(
            clock = { currentTime },
            randomSeed = 42L
        )

        assertEquals(3, session.challenges.size)
        assertEquals("Check Liveness", session.state.value.messageAbove)
        assertEquals("Position your face and look at the camera", session.state.value.messageBelow)

        // 2 consecutive straight frames to pass POSITIONING directly to LIVENESS_CHALLENGE
        session.mockFaces = listOf(MockDetectedFace(yaw = 0f, pitch = 0f, roll = 0f))
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetLivenessSessionBase.Phase.POSITIONING, session.phase)
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetLivenessSessionBase.Phase.LIVENESS_CHALLENGE, session.phase)

        // Perform each of the 3 challenges
        for (step in session.challenges.indices) {
            val direction = session.challenges[step]
            val pose = when (direction) {
                ChallengeDirection.LEFT -> MockDetectedFace(yaw = 20f)
                ChallengeDirection.RIGHT -> MockDetectedFace(yaw = -20f)
                ChallengeDirection.UP -> MockDetectedFace(pitch = 16f)
                ChallengeDirection.DOWN -> MockDetectedFace(pitch = -16f)
                ChallengeDirection.CENTER -> MockDetectedFace(yaw = 0f, pitch = 0f)
            }
            session.mockFaces = listOf(pose)
            session.feedFrame(dummyFrame)
            session.feedFrame(dummyFrame)
            session.feedFrame(dummyFrame)
        }

        // After completing 3 challenges, transitions to PREPARE_FOR_PHOTO phase (duration 2000ms)
        assertEquals(FaceNetLivenessSessionBase.Phase.PREPARE_FOR_PHOTO, session.phase)
        assertEquals(FaceMatcherLivenessPromptState.Outcome.PREPARE_FOR_PHOTO, session.state.value.outcome)
        assertEquals("Hold Still", session.state.value.messageAbove)
        assertEquals("Preparing photo...", session.state.value.messageBelow)
        assertNull(session.state.value.capturedImage)

        // Before 2000ms elapsed, stays in PREPARE_FOR_PHOTO
        currentTime += 1000L
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetLivenessSessionBase.Phase.PREPARE_FOR_PHOTO, session.phase)
        assertEquals(FaceMatcherLivenessPromptState.Outcome.PREPARE_FOR_PHOTO, session.state.value.outcome)

        // After 2000ms elapsed, transitions to CAPTURING
        currentTime += 1100L
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetLivenessSessionBase.Phase.CAPTURING, session.phase)
        assertEquals("Hold Still", session.state.value.messageAbove)
        assertEquals("Capturing portrait image...", session.state.value.messageBelow)
        assertNull(session.state.value.capturedImage)

        // Frame 1 in CAPTURING with steady straight face: steadyHoldFrames = 1
        session.mockFaces = listOf(MockDetectedFace(yaw = 0f, pitch = 0f, roll = 0f))
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetLivenessSessionBase.Phase.CAPTURING, session.phase)

        // Frame 2 in CAPTURING with steady straight face: steadyHoldFrames = 2 -> captures photo and completes!
        session.feedFrame(dummyFrame)
        assertEquals(FaceNetLivenessSessionBase.Phase.COMPLETED, session.phase)
        assertEquals(FaceMatcherLivenessPromptState.Outcome.SUCCESS, session.state.value.outcome)
        assertEquals("Portrait Captured", session.state.value.messageAbove)
        assertEquals("Liveness verified", session.state.value.messageBelow)
        assertNotNull(session.state.value.capturedImage)
        assertEquals(ByteString(byteArrayOf(1, 2, 3)), session.state.value.capturedImage)
        assertTrue(session.ringSegments.all { it.color == RingSegment.COLOR_GREEN })
    }

    @Test
    fun testOverlayRenderedDuringSession() = runTest {
        var currentTime = 1000L
        val session = TestFaceNetSession(clock = { currentTime })
        session.mockFaces = listOf(MockDetectedFace())
        session.feedFrame(dummyFrame)
        assertNotNull(session.state.value.overlay)
    }
}

