package org.multipaz.facenet

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaceLandmarkAlignmentTest {

    @Test
    fun testLevelFaceAlignmentMapsToStandardEyePositions() {
        val cx = 300.0
        val cy = 400.0
        val eyeDistance = 100.0

        val rightEye = FacePoint2D(cx - eyeDistance / 2.0, cy)
        val leftEye = FacePoint2D(cx + eyeDistance / 2.0, cy)

        val alignment = FaceLandmarkAlignment(leftEye = leftEye, rightEye = rightEye, targetSize = 112)

        val mappedRight = alignment.mapCoordinate(rightEye)
        val mappedLeft = alignment.mapCoordinate(leftEye)

        // Mathematical output:
        // outX = (-50 / 320) * 112 + 56 = 38.5
        // outY = (-13 / 320) * 112 + 56 = 51.45
        assertEquals(38.5, mappedRight.x, 0.001)
        assertEquals(51.45, mappedRight.y, 0.001)
        assertEquals(73.5, mappedLeft.x, 0.001)
        assertEquals(51.45, mappedLeft.y, 0.001)

        // Verify proximity to ArcFace canonical landmark template (< 0.25 px)
        val rightDist = abs(mappedRight.x - FaceLandmarkAlignment.CANONICAL_RIGHT_EYE_112.x) +
            abs(mappedRight.y - FaceLandmarkAlignment.CANONICAL_RIGHT_EYE_112.y)
        val leftDist = abs(mappedLeft.x - FaceLandmarkAlignment.CANONICAL_LEFT_EYE_112.x) +
            abs(mappedLeft.y - FaceLandmarkAlignment.CANONICAL_LEFT_EYE_112.y)
        assertTrue(rightDist < 0.5, "Right eye distance from canonical must be < 0.5 px (was $rightDist)")
        assertTrue(leftDist < 0.5, "Left eye distance from canonical must be < 0.5 px (was $leftDist)")
    }

    @Test
    fun testHeadRollPositive15DegreesIsLevelled() {
        val cx = 250.0
        val cy = 350.0
        val eyeDistance = 80.0
        val angleRad = 15.0 * PI / 180.0

        val rightEye = FacePoint2D(
            cx - (eyeDistance / 2.0) * cos(angleRad),
            cy - (eyeDistance / 2.0) * sin(angleRad)
        )
        val leftEye = FacePoint2D(
            cx + (eyeDistance / 2.0) * cos(angleRad),
            cy + (eyeDistance / 2.0) * sin(angleRad)
        )

        val alignment = FaceLandmarkAlignment(leftEye = leftEye, rightEye = rightEye, targetSize = 112)

        val mappedRight = alignment.mapCoordinate(rightEye)
        val mappedLeft = alignment.mapCoordinate(leftEye)

        // Eyes must be levelled to exactly the same horizontal line (outY = 51.45)
        assertEquals(38.5, mappedRight.x, 0.001)
        assertEquals(51.45, mappedRight.y, 0.001)
        assertEquals(73.5, mappedLeft.x, 0.001)
        assertEquals(51.45, mappedLeft.y, 0.001)
    }

    @Test
    fun testHeadRollNegative15DegreesIsLevelled() {
        val cx = 500.0
        val cy = 600.0
        val eyeDistance = 120.0
        val angleRad = -15.0 * PI / 180.0

        val rightEye = FacePoint2D(
            cx - (eyeDistance / 2.0) * cos(angleRad),
            cy - (eyeDistance / 2.0) * sin(angleRad)
        )
        val leftEye = FacePoint2D(
            cx + (eyeDistance / 2.0) * cos(angleRad),
            cy + (eyeDistance / 2.0) * sin(angleRad)
        )

        val alignment = FaceLandmarkAlignment(leftEye = leftEye, rightEye = rightEye, targetSize = 112)

        val mappedRight = alignment.mapCoordinate(rightEye)
        val mappedLeft = alignment.mapCoordinate(leftEye)

        assertEquals(38.5, mappedRight.x, 0.001)
        assertEquals(51.45, mappedRight.y, 0.001)
        assertEquals(73.5, mappedLeft.x, 0.001)
        assertEquals(51.45, mappedLeft.y, 0.001)
    }

    @Test
    fun testScaleInvarianceAcrossResolutions() {
        for (dist in listOf(25.0, 50.0, 100.0, 200.0, 450.0)) {
            val rightEye = FacePoint2D(200.0 - dist / 2.0, 300.0)
            val leftEye = FacePoint2D(200.0 + dist / 2.0, 300.0)
            val alignment = FaceLandmarkAlignment(leftEye, rightEye, 112)

            val mappedRight = alignment.mapCoordinate(rightEye)
            val mappedLeft = alignment.mapCoordinate(leftEye)

            assertEquals(38.5, mappedRight.x, 0.001)
            assertEquals(51.45, mappedRight.y, 0.001)
            assertEquals(73.5, mappedLeft.x, 0.001)
            assertEquals(51.45, mappedLeft.y, 0.001)
        }
    }

    @Test
    fun testFaceBoundingBoxProperties() {
        val bb = FaceBoundingBox(left = 100.0, top = 150.0, width = 80.0, height = 120.0)
        assertEquals(180.0, bb.right, 0.001)
        assertEquals(270.0, bb.bottom, 0.001)
        assertEquals(140.0, bb.centerX, 0.001)
        assertEquals(210.0, bb.centerY, 0.001)
    }
}
