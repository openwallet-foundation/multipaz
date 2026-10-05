package org.multipaz.facematch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FaceMatcherTest {

    @Test
    fun testOverlayFrame() {
        val defaultState = FaceMatcherPromptState()
        assertNull(defaultState.overlay)

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
