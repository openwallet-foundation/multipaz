package org.multipaz.facematch

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FaceMatcherTest {

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
            rotationDegrees = 0
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
    fun testOverlayFrame() {
        val defaultState = FaceMatcherPromptState()
        kotlin.test.assertNull(defaultState.overlay)

        val overlay = OverlayFrame(
            width = 100,
            height = 100,
            platformHandle = "mockHandle"
        )
        assertEquals(100, overlay.width)
        assertEquals(100, overlay.height)
        assertEquals("mockHandle", overlay.platformHandle)

        val stateWithOverlay = FaceMatcherPromptState(overlay = overlay)
        assertEquals(overlay, stateWithOverlay.overlay)

        assertFailsWith<IllegalArgumentException> {
            OverlayFrame(width = 0, height = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            OverlayFrame(width = 100, height = 0)
        }
    }

    @Test
    fun testCameraFrameRotationDegrees() {
        for (rot in listOf(0, 90, 180, 270)) {
            val frame = CameraFrame(width = 640, height = 480, rotationDegrees = rot)
            assertEquals(rot, frame.rotationDegrees)
            if (rot == 90 || rot == 270) {
                assertEquals(480, frame.uprightWidth)
                assertEquals(640, frame.uprightHeight)
            } else {
                assertEquals(640, frame.uprightWidth)
                assertEquals(480, frame.uprightHeight)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            CameraFrame(width = 640, height = 480, rotationDegrees = 45)
        }
        assertFailsWith<IllegalArgumentException> {
            CameraFrame(width = 640, height = 480, rotationDegrees = -90)
        }
        assertFailsWith<IllegalArgumentException> {
            CameraFrame(width = 640, height = 480, rotationDegrees = 360)
        }
    }
}
