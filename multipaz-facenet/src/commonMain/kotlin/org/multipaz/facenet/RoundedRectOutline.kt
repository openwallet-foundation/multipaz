package org.multipaz.facenet

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometric model for continuous parameterization and segment sampling along a rounded rectangle perimeter.
 */
internal class RoundedRectOutline(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    cornerRadius: Float
) {
    val r: Float = cornerRadius.coerceAtMost(minOf(width, height) / 2f)
    val right: Float = left + width
    val bottom: Float = top + height
    val wStraight: Float = width - 2f * r
    val hStraight: Float = height - 2f * r
    val arcLength: Float = (PI.toFloat() / 2f) * r

    val l1: Float = wStraight / 2f
    val l2: Float = l1 + arcLength
    val l3: Float = l2 + hStraight
    val l4: Float = l3 + arcLength
    val l5: Float = l4 + wStraight
    val l6: Float = l5 + arcLength
    val l7: Float = l6 + hStraight
    val l8: Float = l7 + arcLength
    val totalLength: Float = l8 + wStraight / 2f

    fun pointAt(distance: Float): Pair<Float, Float> {
        var d = distance % totalLength
        if (d < 0f) d += totalLength

        return when {
            d < l1 -> {
                val centerX = left + width / 2f
                (centerX + d) to top
            }
            d < l2 -> {
                val arcDist = d - l1
                val angle = -PI / 2.0 + (arcDist / arcLength) * (PI / 2.0)
                val cx = right - r
                val cy = top + r
                (cx + r * cos(angle)).toFloat() to (cy + r * sin(angle)).toFloat()
            }
            d < l3 -> {
                val edgeDist = d - l2
                right to (top + r + edgeDist)
            }
            d < l4 -> {
                val arcDist = d - l3
                val angle = 0.0 + (arcDist / arcLength) * (PI / 2.0)
                val cx = right - r
                val cy = bottom - r
                (cx + r * cos(angle)).toFloat() to (cy + r * sin(angle)).toFloat()
            }
            d < l5 -> {
                val edgeDist = d - l4
                (right - r - edgeDist) to bottom
            }
            d < l6 -> {
                val arcDist = d - l5
                val angle = PI / 2.0 + (arcDist / arcLength) * (PI / 2.0)
                val cx = left + r
                val cy = bottom - r
                (cx + r * cos(angle)).toFloat() to (cy + r * sin(angle)).toFloat()
            }
            d < l7 -> {
                val edgeDist = d - l6
                left to (bottom - r - edgeDist)
            }
            d < l8 -> {
                val arcDist = d - l7
                val angle = PI + (arcDist / arcLength) * (PI / 2.0)
                val cx = left + r
                val cy = top + r
                (cx + r * cos(angle)).toFloat() to (cy + r * sin(angle)).toFloat()
            }
            else -> {
                val edgeDist = d - l8
                (left + r + edgeDist) to top
            }
        }
    }

    fun sampleSegment(startDist: Float, endDist: Float, numSamples: Int = 8): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toFloat() / (numSamples - 1).toFloat()
            val d = startDist + t * (endDist - startDist)
            points.add(pointAt(d))
        }
        return points
    }
}
