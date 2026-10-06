package org.multipaz.facenet

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
