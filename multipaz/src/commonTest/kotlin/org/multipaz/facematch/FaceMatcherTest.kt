package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FaceMatcherTest {

    @Test
    fun testOverlayFrame() {
        val defaultState = FaceMatcherPromptState()
        assertNull(defaultState.overlay)
        assertEquals(FaceMatcherPromptState.Status.IN_PROGRESS, defaultState.status)

        val successState = FaceMatcherPromptState(status = FaceMatcherPromptState.Status.SUCCESS)
        assertEquals(FaceMatcherPromptState.Status.SUCCESS, successState.status)

        val defaultLivenessState = FaceMatcherLivenessPromptState()
        assertEquals(FaceMatcherLivenessPromptState.Status.IN_PROGRESS, defaultLivenessState.status)
        assertNull(defaultLivenessState.capturedImage)

        val dummyImage = kotlinx.io.bytestring.ByteString(byteArrayOf(1, 2, 3))
        val livenessSuccessState = FaceMatcherLivenessPromptState(
            status = FaceMatcherLivenessPromptState.Status.SUCCESS,
            capturedImage = dummyImage
        )
        assertEquals(FaceMatcherLivenessPromptState.Status.SUCCESS, livenessSuccessState.status)
        assertEquals(dummyImage, livenessSuccessState.capturedImage)

        // Test FaceMatcherLivenessSession enforces capturedImage null unless SUCCESS
        val testSession = object : FaceMatcherLivenessSession() {
            override suspend fun feedFrame(frame: CameraFrame) {}
            fun testUpdate(status: FaceMatcherLivenessPromptState.Status, image: kotlinx.io.bytestring.ByteString?) {
                updateState(status = status, capturedImage = image)
            }
        }
        testSession.testUpdate(FaceMatcherLivenessPromptState.Status.IN_PROGRESS, dummyImage)
        assertNull(testSession.state.value.capturedImage)

        testSession.testUpdate(FaceMatcherLivenessPromptState.Status.SUCCESS, dummyImage)
        assertEquals(dummyImage, testSession.state.value.capturedImage)

        testSession.testUpdate(FaceMatcherLivenessPromptState.Status.FAILED, dummyImage)
        assertNull(testSession.state.value.capturedImage)

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

    @Test
    fun testFaceMatcherSessionReferencePortrait() {
        val sampleBytes = ByteString(byteArrayOf(1, 2, 3))
        val session = object : FaceMatcherSession(sampleBytes) {
            override suspend fun feedFrame(frame: CameraFrame) {}
        }
        assertEquals(sampleBytes, session.referencePortrait)
    }
}
