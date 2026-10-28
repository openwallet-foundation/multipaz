package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(emptyMap(), defaultLivenessState.capturedImages)

        val dummyImage = ByteString(byteArrayOf(1, 2, 3))
        val dummyMap = mapOf(FacePose.FRONT to dummyImage)
        val livenessSuccessState = FaceMatcherLivenessPromptState(
            status = FaceMatcherLivenessPromptState.Status.SUCCESS,
            capturedImages = dummyMap
        )
        assertEquals(FaceMatcherLivenessPromptState.Status.SUCCESS, livenessSuccessState.status)
        assertEquals(dummyMap, livenessSuccessState.capturedImages)

        // Test FaceMatcherLivenessSession default poses and empty poses rejection
        val testSession = object : FaceMatcherLivenessSession() {
            override suspend fun feedFrame(frame: CameraFrame) {}
            fun testUpdate(status: FaceMatcherLivenessPromptState.Status, images: Map<FacePose, ByteString>) {
                updateState(status = status, capturedImages = images)
            }
        }
        assertEquals(listOf(FacePose.FRONT), testSession.poses)
        assertFailsWith<IllegalArgumentException> {
            object : FaceMatcherLivenessSession(poses = emptyList()) {
                override suspend fun feedFrame(frame: CameraFrame) {}
            }
        }

        // Test FaceMatcherLivenessSession enforces capturedImages empty unless SUCCESS
        testSession.testUpdate(FaceMatcherLivenessPromptState.Status.IN_PROGRESS, dummyMap)
        assertEquals(emptyMap(), testSession.state.value.capturedImages)

        testSession.testUpdate(FaceMatcherLivenessPromptState.Status.SUCCESS, dummyMap)
        assertEquals(dummyMap, testSession.state.value.capturedImages)

        testSession.testUpdate(FaceMatcherLivenessPromptState.Status.FAILED, dummyMap)
        assertEquals(emptyMap(), testSession.state.value.capturedImages)

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
        assertNull(session.state.value.backgroundColor)
    }

    @Test
    fun testColorAndDisplayProperties() {
        assertEquals(0xFFFFFFFF.toInt(), Color.WHITE.toArgb())
        assertEquals(0xFF000000.toInt(), Color.BLACK.toArgb())
        assertEquals(0xFFFF0000.toInt(), Color.RED.toArgb())
        assertEquals(Color.WHITE, Color.fromArgb(0xFFFFFFFF.toInt()))
        assertEquals(Color.BLACK, Color.fromArgb(0xFF000000.toInt()))

        assertFailsWith<IllegalArgumentException> {
            Color(red = 1.5f, green = 0f, blue = 0f)
        }

        val stateWithColor = FaceMatcherPromptState(
            backgroundColor = Color.WHITE
        )
        assertEquals(Color.WHITE, stateWithColor.backgroundColor)
    }

    @Test
    fun testUsesCustomUserInterfaceDefaultAndOverride() {
        val defaultMatcher = object : FaceMatcher {
            override val name: String = "default"
            override fun createSession(referencePortrait: ByteString): FaceMatcherSession =
                object : FaceMatcherSession(referencePortrait) {}
        }
        assertFalse(defaultMatcher.usesCustomUserInterface)

        val customUiMatcher = object : FaceMatcher {
            override val name: String = "custom_ui"
            override val usesCustomUserInterface: Boolean = true
            override fun createSession(referencePortrait: ByteString): FaceMatcherSession =
                object : FaceMatcherSession(referencePortrait) {}
        }
        assertTrue(customUiMatcher.usesCustomUserInterface)
    }
}

