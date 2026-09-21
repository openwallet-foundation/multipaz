package org.multipaz.facenet

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlazeFaceDecoderTest {

    @Test
    fun testAnchorGenerationProperties() {
        val anchors = BlazeFaceDecoder.ANCHORS
        assertEquals(896, anchors.size)

        // Stride 8 layer (16x16 grid, 2 anchors per cell = 512 anchors)
        val stride8Anchors = anchors.take(512)
        assertEquals(512, stride8Anchors.size)

        // Stride 16 layer (8x8 grid, 6 anchors per cell = 384 anchors)
        val stride16Anchors = anchors.drop(512)
        assertEquals(384, stride16Anchors.size)

        // All anchors must have fixed size 1.0 x 1.0 and normalized center coordinates in (0, 1)
        for (a in anchors) {
            assertEquals(1.0f, a.w)
            assertEquals(1.0f, a.h)
            assertTrue(a.xCenter > 0f && a.xCenter < 1f, "xCenter out of range: ${a.xCenter}")
            assertTrue(a.yCenter > 0f && a.yCenter < 1f, "yCenter out of range: ${a.yCenter}")
        }
    }

    @Test
    fun testHeadPoseEstimation() {
        // Canonical neutral face at eye distance = 60
        val rightEye = FacePoint2D(70.0, 100.0)
        val leftEye = FacePoint2D(130.0, 100.0)
        val noseTip = FacePoint2D(100.0, 128.0) // Midpoint x=100, y=100. Eye-to-nose=28, Nose-to-mouth=34.
        val mouthCenter = FacePoint2D(100.0, 162.0)

        val (neutralYaw, neutralPitch, neutralRoll) = BlazeFaceDecoder.computePose(
            rightEye, leftEye, noseTip, mouthCenter
        )

        assertTrue(abs(neutralRoll) < 2.0f, "Neutral roll should be ~0: $neutralRoll")
        assertTrue(abs(neutralYaw) < 3.0f, "Neutral yaw should be ~0: $neutralYaw")
        assertTrue(abs(neutralPitch) < 5.0f, "Neutral pitch should be ~0: $neutralPitch")

        // 1. Turned head to person's left (nose shifts toward left eye / positive X)
        val leftTurnNose = FacePoint2D(115.0, 128.0)
        val (leftYaw, _, _) = BlazeFaceDecoder.computePose(rightEye, leftEye, leftTurnNose, mouthCenter)
        assertTrue(leftYaw > 12.0f, "Left head turn yaw should be > 12: $leftYaw")

        // 2. Turned head to person's right (nose shifts toward right eye / negative X)
        val rightTurnNose = FacePoint2D(85.0, 128.0)
        val (rightYaw, _, _) = BlazeFaceDecoder.computePose(rightEye, leftEye, rightTurnNose, mouthCenter)
        assertTrue(rightYaw < -12.0f, "Right head turn yaw should be < -12: $rightYaw")

        // 3. Tilting head up (nose shifts up toward eyes)
        val upNose = FacePoint2D(100.0, 118.0)
        val (_, upPitch, _) = BlazeFaceDecoder.computePose(rightEye, leftEye, upNose, mouthCenter)
        assertTrue(upPitch > 10.0f, "Head up pitch should be > 10: $upPitch")

        // 4. Tilting head down (nose shifts down toward mouth)
        val downNose = FacePoint2D(100.0, 138.0)
        val (_, downPitch, _) = BlazeFaceDecoder.computePose(rightEye, leftEye, downNose, mouthCenter)
        assertTrue(downPitch < -10.0f, "Head down pitch should be < -10: $downPitch")

        // 5. Head roll (left eye tilted higher, dy < 0)
        val rolledLeftEye = FacePoint2D(128.0, 80.0) // Left eye higher
        val rolledRightEye = FacePoint2D(72.0, 120.0) // Right eye lower
        val (_, _, rolledRoll) = BlazeFaceDecoder.computePose(rolledRightEye, rolledLeftEye, noseTip, mouthCenter)
        assertTrue(rolledRoll > 15.0f, "Counter-clockwise roll should be > 15: $rolledRoll")
    }

    @Test
    fun testDefaultModelBytesAvailable() {
        val bytes = BlazeFaceModelData.defaultModelBytes
        assertEquals(229032, bytes.size)
        // Check TFL3 identifier at offset 4
        val raw = bytes.toByteArray()
        assertEquals('T'.code.toByte(), raw[4])
        assertEquals('F'.code.toByte(), raw[5])
        assertEquals('L'.code.toByte(), raw[6])
        assertEquals('3'.code.toByte(), raw[7])
    }
}
