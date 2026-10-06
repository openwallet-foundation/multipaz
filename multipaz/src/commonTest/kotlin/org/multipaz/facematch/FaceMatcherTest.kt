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
        assertEquals(FaceMatcherPromptState.Status.IN_PROGRESS, defaultState.status)

        val successState = FaceMatcherPromptState(status = FaceMatcherPromptState.Status.SUCCESS)
        assertEquals(FaceMatcherPromptState.Status.SUCCESS, successState.status)

        val defaultLivenessState = FaceMatcherLivenessPromptState()
        assertEquals(FaceMatcherLivenessPromptState.Status.IN_PROGRESS, defaultLivenessState.status)
        assertNull(defaultLivenessState.capturedImage)

        val livenessPrepState = FaceMatcherLivenessPromptState(status = FaceMatcherLivenessPromptState.Status.PREPARE_FOR_PHOTO)
        assertEquals(FaceMatcherLivenessPromptState.Status.PREPARE_FOR_PHOTO, livenessPrepState.status)
        assertNull(livenessPrepState.capturedImage)

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
    fun testSupportedReferencePortraitFormats() {
        // PNG header
        val pngBytes = kotlinx.io.bytestring.ByteString(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
        )
        kotlin.test.assertTrue(isSupportedReferencePortraitFormat(pngBytes))

        // JPEG header
        val jpegBytes = kotlinx.io.bytestring.ByteString(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00)
        )
        kotlin.test.assertTrue(isSupportedReferencePortraitFormat(jpegBytes))

        // JPEG 2000 JP2 container header
        val jp2Bytes = kotlinx.io.bytestring.ByteString(
            byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A, 0x00)
        )
        kotlin.test.assertTrue(isSupportedReferencePortraitFormat(jp2Bytes))

        // JPEG 2000 raw codestream (J2K) header
        val j2kBytes = kotlinx.io.bytestring.ByteString(
            byteArrayOf(0xFF.toByte(), 0x4F.toByte(), 0xFF.toByte(), 0x51.toByte(), 0x00)
        )
        kotlin.test.assertTrue(isSupportedReferencePortraitFormat(j2kBytes))

        // Unsupported formats
        val emptyBytes = kotlinx.io.bytestring.ByteString()
        kotlin.test.assertFalse(isSupportedReferencePortraitFormat(emptyBytes))

        val gifBytes = kotlinx.io.bytestring.ByteString("GIF89a".encodeToByteArray())
        kotlin.test.assertFalse(isSupportedReferencePortraitFormat(gifBytes))

        val webpBytes = kotlinx.io.bytestring.ByteString("RIFF....WEBP".encodeToByteArray())
        kotlin.test.assertFalse(isSupportedReferencePortraitFormat(webpBytes))

        val randomBytes = kotlinx.io.bytestring.ByteString(byteArrayOf(1, 2, 3, 4, 5))
        kotlin.test.assertFalse(isSupportedReferencePortraitFormat(randomBytes))

        // Verify FaceMatcherSession accepts valid formats and rejects invalid formats
        val validSession = object : FaceMatcherSession(pngBytes) {
            override suspend fun feedFrame(frame: CameraFrame) {}
        }
        assertEquals(pngBytes, validSession.referencePortrait)

        assertFailsWith<IllegalArgumentException> {
            object : FaceMatcherSession(gifBytes) {
                override suspend fun feedFrame(frame: CameraFrame) {}
            }
        }
    }
}
