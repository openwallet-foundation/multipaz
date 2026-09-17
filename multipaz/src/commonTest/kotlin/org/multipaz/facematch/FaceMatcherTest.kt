package org.multipaz.facematch

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceMatcherTest {

    @Test
    fun testPromptColor() {
        val color = PromptColor.fromRgba(255, 0, 128, 255)
        assertEquals(1.0f, color.alpha)
        assertEquals(1.0f, color.red)
        assertEquals(0.0f, color.green)
        assertTrue(color.blue > 0.49f && color.blue < 0.51f)

        val lerped = PromptColor.lerp(PromptColor.BLACK, PromptColor.WHITE, 0.5f)
        assertTrue(lerped.red > 0.49f && lerped.red < 0.51f)
        assertTrue(lerped.green > 0.49f && lerped.green < 0.51f)
        assertTrue(lerped.blue > 0.49f && lerped.blue < 0.51f)
    }

    @Test
    fun testRingSegments() {
        assertEquals(18, FaceMatcherPromptState.NUM_RING_SEGMENTS)
        val defaultState = FaceMatcherPromptState()
        assertEquals(18, defaultState.ringSegments.size)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, defaultState.outcome)
    }

    @Test
    fun testSimulatedFaceMatcherSession() = runTest {
        var currentTime = 1000L
        val matcher = SimulatedFaceMatcher(
            searchDurationMs = 1000L,
            matchConveyDurationMs = 500L,
            challengeDurationMs = 1200L,
            simulatedConfidence = 0.98f,
            enableLiveness = true,
            clock = { currentTime }
        )

        val dummyFrame = CameraFrame(
            width = 640,
            height = 480,
            rotationDegrees = 0,
            pixelFormat = PixelFormat.UNKNOWN,
            data = ByteString()
        )

        // Session 1
        val session1 = matcher.createSession(ByteString())
        session1.feedFrame(dummyFrame)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session1.state.value.outcome)

        // Advance time beyond search duration
        currentTime += 1200L
        session1.feedFrame(dummyFrame)
        // Should have transitioned to match conveyed
        assertNotNull(session1.state.value.messageAbove)

        // Advance through match conveyed and challenges
        currentTime += 600L
        session1.feedFrame(dummyFrame)

        currentTime += 1300L
        session1.feedFrame(dummyFrame)

        currentTime += 1300L
        session1.feedFrame(dummyFrame)

        currentTime += 1300L
        session1.feedFrame(dummyFrame)

        // Should complete successfully
        assertEquals(FaceMatcherPromptState.Outcome.SUCCESS, session1.state.value.outcome)

        // Session 2 should start fresh and NOT be completed immediately
        val session2 = matcher.createSession(ByteString())
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session2.state.value.outcome)
        session2.feedFrame(dummyFrame)
        // Session 2 should still be in progress!
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session2.state.value.outcome)
    }

    @Test
    fun testDirectionSegmentsFade() {
        class TestSession : FaceMatcherSession(ByteString()) {
            override suspend fun feedFrame(frame: CameraFrame) {}
            fun testSegments(dir: RingDirection, progress: Float) =
                computeDirectionSegments(dir, progress)
        }
        val session = TestSession()
        val segments = session.testSegments(RingDirection.LEFT, 1.0f)
        assertEquals(18, segments.size)

        // Center of LEFT (indices 13 and 14) should have highest scale and bright green
        assertTrue(segments[13].scale > segments[12].scale)
        assertTrue(segments[12].scale > segments[11].scale)
        assertTrue(segments[11].scale > segments[10].scale)
        assertEquals(1.0f, segments[10].scale)

        // Edge segment 11 has a gentle partial fade (neither pure dark gray nor pure bright green)
        assertTrue(segments[11].color != PromptColor.DARK_GRAY)
        assertTrue(segments[11].color != PromptColor.BRIGHT_GREEN)
        assertEquals(PromptColor.DARK_GRAY, segments[10].color)
    }
}
