package org.multipaz.facenet

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.PixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FaceNetFaceMatcherTest {

    @Test
    fun testProperties() {
        val matcher = FaceNetFaceMatcher()
        assertEquals("facenet", matcher.name)
        assertEquals("Google FaceNet", matcher.displayName)
    }

    @Test
    fun testCreateSession() = runTest {
        val matcher = FaceNetFaceMatcher()
        val session = matcher.createSession(ByteString())
        assertNotNull(session)
        assertEquals(FaceMatcherPromptState.NUM_RING_SEGMENTS, session.state.value.ringSegments.size)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session.state.value.outcome)

        val dummyFrame = CameraFrame(
            width = 640,
            height = 480,
            rotationDegrees = 0,
            pixelFormat = PixelFormat.UNKNOWN,
            data = ByteString()
        )
        session.feedFrame(dummyFrame)
        assertEquals(FaceMatcherPromptState.Outcome.IN_PROGRESS, session.state.value.outcome)
    }
}
