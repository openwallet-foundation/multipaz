package org.multipaz.facematch

/**
 * A vector graphic element rendered on top of the camera video stream during face matching.
 */
sealed interface FaceMatcherGraphic {
    /**
     * A point (circle) graphic.
     *
     * @property x horizontal center coordinate in frame pixels.
     * @property y vertical center coordinate in frame pixels.
     * @property color color of the point.
     * @property radius radius of the point in density-independent points/dp.
     */
    data class Point(
        val x: Float,
        val y: Float,
        val color: PromptColor = PromptColor.BRIGHT_GREEN,
        val radius: Float = 3.0f
    ) : FaceMatcherGraphic

    /**
     * A line segment connecting two points.
     *
     * @property startX horizontal start coordinate in frame pixels.
     * @property startY vertical start coordinate in frame pixels.
     * @property endX horizontal end coordinate in frame pixels.
     * @property endY vertical end coordinate in frame pixels.
     * @property color stroke color.
     * @property strokeWidth stroke width in density-independent points/dp.
     */
    data class Line(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: PromptColor = PromptColor.BRIGHT_GREEN,
        val strokeWidth: Float = 2.0f
    ) : FaceMatcherGraphic

    /**
     * An axis-aligned rectangle (e.g. face bounding box).
     *
     * @property left left horizontal coordinate in frame pixels.
     * @property top top vertical coordinate in frame pixels.
     * @property right right horizontal coordinate in frame pixels.
     * @property bottom bottom vertical coordinate in frame pixels.
     * @property color stroke color.
     * @property strokeWidth stroke width in density-independent points/dp.
     */
    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: PromptColor = PromptColor.BRIGHT_GREEN,
        val strokeWidth: Float = 2.0f
    ) : FaceMatcherGraphic

    /**
     * A text label rendered at a specific position.
     *
     * @property text text string to display.
     * @property x horizontal center coordinate in frame pixels.
     * @property y vertical center coordinate in frame pixels.
     * @property color text color.
     * @property fontSize font size in sp/points.
     */
    data class Text(
        val text: String,
        val x: Float,
        val y: Float,
        val color: PromptColor = PromptColor.BRIGHT_GREEN,
        val fontSize: Float = 14.0f
    ) : FaceMatcherGraphic
}

/**
 * Collection of vector graphics positioned relative to a camera frame of specified dimensions.
 *
 * @property frameWidth width of the source frame in pixels.
 * @property frameHeight height of the source frame in pixels.
 * @property isMirrored whether the horizontal axis should be mirrored to match a mirrored camera preview.
 * @property items list of graphics elements to render.
 */
data class FaceMatcherGraphics(
    val frameWidth: Int,
    val frameHeight: Int,
    val isMirrored: Boolean = false,
    val items: List<FaceMatcherGraphic> = emptyList()
)
