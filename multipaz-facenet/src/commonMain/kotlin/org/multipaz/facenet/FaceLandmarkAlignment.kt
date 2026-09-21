package org.multipaz.facenet

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 2D point representation for facial landmarks.
 */
data class FacePoint2D(val x: Double, val y: Double) {
    constructor(x: Float, y: Float) : this(x.toDouble(), y.toDouble())
}

/**
 * 2D bounding box representation for detected faces.
 */
data class FaceBoundingBox(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double
) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
    val centerX: Double get() = left + width / 2.0
    val centerY: Double get() = top + height / 2.0
}

/**
 * Mathematical helper for standard facial landmark alignment and coordinate mapping.
 *
 * Implements the standard affine transformation used for ArcFace / MobileFaceNet:
 * 1. Centers between the two eyes.
 * 2. Levels the eyes horizontally (canceling head roll).
 * 3. Applies vertical offset to balance eyes and mouth in the cropped square.
 * 4. Scales so that the eye distance occupies the standard proportion of [targetSize].
 *
 * @property leftEye Position of subject's left eye (viewer right) in source image coordinates.
 * @property rightEye Position of subject's right eye (viewer left) in source image coordinates.
 * @property targetSize Dimension of the square output crop (default 112 for MobileFaceNet).
 * @property faceCropFactor Scale ratio of crop box relative to inter-pupillary distance (default 3.2).
 * @property faceVerticalOffsetFactor Vertical shift ratio relative to inter-pupillary distance (default 0.13).
 */
class FaceLandmarkAlignment(
    val leftEye: FacePoint2D,
    val rightEye: FacePoint2D,
    val targetSize: Int = 112,
    val faceCropFactor: Double = DEFAULT_CROP_FACTOR,
    val faceVerticalOffsetFactor: Double = DEFAULT_VERTICAL_OFFSET_FACTOR
) {
    val eyeDistance: Double = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
    val eyeAngleRad: Double = atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x)
    val centerX: Double = (leftEye.x + rightEye.x) / 2.0
    val centerY: Double = (leftEye.y + rightEye.y) / 2.0
    val verticalOffset: Double = eyeDistance * faceVerticalOffsetFactor
    val cropSize: Double = eyeDistance * faceCropFactor
    val scale: Double = if (cropSize > 0.0) targetSize.toDouble() / cropSize else 1.0

    /**
     * Maps a coordinate [point] from source image space to aligned [targetSize] x [targetSize] space.
     */
    fun mapCoordinate(point: FacePoint2D): FacePoint2D {
        val dx = point.x - centerX
        val dy = point.y - centerY

        // Rotate by -eyeAngleRad to level the eyes horizontally
        val cosA = cos(-eyeAngleRad)
        val sinA = sin(-eyeAngleRad)
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA

        // Apply vertical offset in the levelled face coordinate system
        val ryOffset = ry - verticalOffset

        val outX = rx * scale + targetSize / 2.0
        val outY = ryOffset * scale + targetSize / 2.0

        return FacePoint2D(outX, outY)
    }

    companion object {
        const val DEFAULT_CROP_FACTOR = 3.2
        const val DEFAULT_VERTICAL_OFFSET_FACTOR = 0.13

        /** Canonical right eye (viewer left) landmark in standard 112x112 ArcFace / MobileFaceNet space. */
        val CANONICAL_RIGHT_EYE_112 = FacePoint2D(38.2946, 51.6963)

        /** Canonical left eye (viewer right) landmark in standard 112x112 ArcFace / MobileFaceNet space. */
        val CANONICAL_LEFT_EYE_112 = FacePoint2D(73.5318, 51.5014)

        /** Canonical nose tip landmark in standard 112x112 ArcFace / MobileFaceNet space. */
        val CANONICAL_NOSE_112 = FacePoint2D(56.0252, 71.7366)

        /** Canonical right mouth corner (viewer left) in standard 112x112 ArcFace / MobileFaceNet space. */
        val CANONICAL_RIGHT_MOUTH_112 = FacePoint2D(41.5493, 92.3655)

        /** Canonical left mouth corner (viewer right) in standard 112x112 ArcFace / MobileFaceNet space. */
        val CANONICAL_LEFT_MOUTH_112 = FacePoint2D(70.7299, 92.2041)
    }
}
