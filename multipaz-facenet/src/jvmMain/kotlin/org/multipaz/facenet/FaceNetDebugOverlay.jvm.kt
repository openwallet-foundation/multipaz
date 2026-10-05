package org.multipaz.facenet

import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.OverlayFrame
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

internal actual fun renderOverlay(
    frame: CameraFrame,
    faces: List<DetectedFacePose>,
    ringSegments: List<RingSegment>,
    debug: Boolean,
    currentSimilarity: Float?,
    bestSimilarity: Float,
    matchThreshold: Float
): OverlayFrame? {
    val firstFace = faces.firstOrNull() as? BlazeFaceDetection
    val width = if (firstFace != null && firstFace.imageWidth > 0) firstFace.imageWidth else frame.uprightWidth
    val height = if (firstFace != null && firstFace.imageHeight > 0) firstFace.imageHeight else frame.uprightHeight
    if (width <= 0 || height <= 0) return null

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Compute viewport mapping: camera frame is cropped into a 220dp x 284dp box
        val viewportW = 220f
        val viewportH = 284f
        val scale = minOf(width.toFloat() / viewportW, height.toFloat() / viewportH)
        val visibleW = viewportW * scale
        val visibleH = viewportH * scale
        val offsetX = (width.toFloat() - visibleW) / 2f
        val offsetY = (height.toFloat() - visibleH) / 2f

        val dpToPx = scale
        val pad = 6f * dpToPx
        val cornerRadius = 30f * dpToPx

        val outline = RoundedRectOutline(
            left = offsetX + pad,
            top = offsetY + pad,
            width = visibleW - 2f * pad,
            height = visibleH - 2f * pad,
            cornerRadius = cornerRadius
        )

        // 1. Black background border around the vertical rounded rectangle
        if (ringSegments.isNotEmpty()) {
            g.color = Color.BLACK
            g.stroke = BasicStroke(10f * dpToPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val bgPath = Path2D.Float()
            val fullSamples = outline.sampleSegment(0f, outline.totalLength, numSamples = 72)
            if (fullSamples.isNotEmpty()) {
                bgPath.moveTo(fullSamples[0].first, fullSamples[0].second)
                for (i in 1 until fullSamples.size) {
                    bgPath.lineTo(fullSamples[i].first, fullSamples[i].second)
                }
                bgPath.closePath()
                g.draw(bgPath)
            }
        }

        // 2. Draw 18 segments on top of the black border
        val numSegments = ringSegments.size
        if (numSegments > 0) {
            val slotLength = outline.totalLength / numSegments.toFloat()
            val segLength = slotLength * 0.72f

            for (i in 0 until numSegments) {
                val segment = ringSegments[i]
                val centerDist = (i.toFloat() / numSegments.toFloat()) * outline.totalLength
                val rawStart = centerDist - segLength / 2f
                val rawEnd = centerDist + segLength / 2f

                val segPoints = outline.sampleSegment(rawStart, rawEnd, numSamples = 8)
                if (segPoints.isNotEmpty()) {
                    val segPath = Path2D.Float()
                    segPath.moveTo(segPoints[0].first, segPoints[0].second)
                    for (j in 1 until segPoints.size) {
                        segPath.lineTo(segPoints[j].first, segPoints[j].second)
                    }
                    g.color = Color(segment.color, true)
                    g.stroke = BasicStroke(5f * dpToPx * segment.scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(segPath)
                }
            }
        }

        // 3. Debug overlays (if enabled and face detected)
        if (debug && firstFace != null) {
            fun mapX(x: Double): Int = (width.toDouble() - x).toInt()
            fun mapY(y: Double): Int = y.toInt()

            // Bounding box
            g.color = Color(0x69, 0xF0, 0xAE)
            g.stroke = BasicStroke(2f * dpToPx)
            val bb = firstFace.boundingBox
            val bbLeft = mapX(bb.right)
            val bbRight = mapX(bb.left)
            g.drawRect(bbLeft, mapY(bb.top), bbRight - bbLeft, (bb.bottom - bb.top).toInt())

            // Facial wireframe / alignment lines
            val rightEye = firstFace.rightEye
            val leftEye = firstFace.leftEye
            val nose = firstFace.noseTip
            val mouth = firstFace.mouthCenter
            val rightEar = firstFace.rightEarTragus
            val leftEar = firstFace.leftEarTragus

            // Eye-to-eye
            g.stroke = BasicStroke(2f * dpToPx)
            g.drawLine(mapX(rightEye.x), mapY(rightEye.y), mapX(leftEye.x), mapY(leftEye.y))

            // Eye midpoint to nose
            val eyeMidX = (rightEye.x + leftEye.x) / 2.0
            val eyeMidY = (rightEye.y + leftEye.y) / 2.0
            g.drawLine(mapX(eyeMidX), mapY(eyeMidY), mapX(nose.x), mapY(nose.y))

            // Nose to mouth
            g.drawLine(mapX(nose.x), mapY(nose.y), mapX(mouth.x), mapY(mouth.y))

            // Eye to nose triangles (translucent green)
            g.color = Color(0x69, 0xF0, 0xAE, 0x80)
            g.stroke = BasicStroke(1.5f * dpToPx)
            g.drawLine(mapX(rightEye.x), mapY(rightEye.y), mapX(nose.x), mapY(nose.y))
            g.drawLine(mapX(leftEye.x), mapY(leftEye.y), mapX(nose.x), mapY(nose.y))

            // Mouth to ears (translucent blue)
            g.color = Color(0x29, 0x79, 0xFF, 0x80)
            g.drawLine(mapX(mouth.x), mapY(mouth.y), mapX(rightEar.x), mapY(rightEar.y))
            g.drawLine(mapX(mouth.x), mapY(mouth.y), mapX(leftEar.x), mapY(leftEar.y))

            // Keypoints (circles)
            fun fillCircle(cx: Int, cy: Int, r: Double, color: Color) {
                g.color = color
                g.fillOval((cx - r).toInt(), (cy - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
            }
            fillCircle(mapX(rightEye.x), mapY(rightEye.y), 4.0 * dpToPx.toDouble(), Color(0x69, 0xF0, 0xAE))
            fillCircle(mapX(leftEye.x), mapY(leftEye.y), 4.0 * dpToPx.toDouble(), Color(0x69, 0xF0, 0xAE))
            fillCircle(mapX(nose.x), mapY(nose.y), 4.0 * dpToPx.toDouble(), Color(0xD5, 0x00, 0x00))
            fillCircle(mapX(mouth.x), mapY(mouth.y), 4.0 * dpToPx.toDouble(), Color(0xFF, 0xD6, 0x00))
            fillCircle(mapX(rightEar.x), mapY(rightEar.y), 3.5 * dpToPx.toDouble(), Color(0x29, 0x79, 0xFF))
            fillCircle(mapX(leftEar.x), mapY(leftEar.y), 3.5 * dpToPx.toDouble(), Color(0x29, 0x79, 0xFF))

            // 3D Head pose direction vector
            val eyeDist = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
            if (eyeDist > 1.0) {
                val rayLength = eyeDist * 0.9
                val radYaw = firstFace.yaw * (PI / 180.0)
                val radPitch = firstFace.pitch * (PI / 180.0)
                val rayDx = sin(radYaw) * rayLength
                val rayDy = sin(radPitch) * rayLength
                val rayEndX = nose.x + rayDx
                val rayEndY = nose.y + rayDy

                g.color = Color(0xFF, 0x52, 0x52)
                g.stroke = BasicStroke(3f * dpToPx)
                g.drawLine(mapX(nose.x), mapY(nose.y), mapX(rayEndX), mapY(rayEndY))
                fillCircle(mapX(rayEndX), mapY(rayEndY), 4.5 * dpToPx.toDouble(), Color(0xFF, 0x52, 0x52))
            }

            // Match percentage badge
            val similarityToDisplay = currentSimilarity ?: if (bestSimilarity > 0f) bestSimilarity else null
            if (similarityToDisplay != null) {
                val percentage = (similarityToDisplay * 100).toInt().coerceIn(0, 100)
                val isMatch = similarityToDisplay >= matchThreshold
                val labelColor = when {
                    isMatch -> Color(0x69, 0xF0, 0xAE)
                    similarityToDisplay >= 0.4f -> Color(0xFF, 0xD6, 0x00)
                    else -> Color(0xFF, 0x52, 0x52)
                }
                val text = "$percentage%"
                g.font = Font("SansSerif", Font.BOLD, (16f * dpToPx).toInt())
                val fm = g.fontMetrics
                val textW = fm.stringWidth(text)
                val textH = fm.height
                val centerX = mapX(firstFace.boundingBox.centerX)
                val textY = if (firstFace.boundingBox.top >= 24.0 * dpToPx) {
                    (firstFace.boundingBox.top - 14.0 * dpToPx).toInt()
                } else {
                    (firstFace.boundingBox.top + 18.0 * dpToPx + textH).toInt()
                }

                val padH = (10f * dpToPx).toInt()
                val padV = (6f * dpToPx).toInt()
                val bgCornerRadius = (8f * dpToPx).toInt()
                g.color = Color(0, 0, 0, 160)
                g.fillRoundRect(centerX - textW / 2 - padH, textY - fm.ascent - padV, textW + padH * 2, textH + padV * 2, bgCornerRadius, bgCornerRadius)

                g.color = labelColor
                g.drawString(text, centerX - textW / 2, textY)
            }
        }
    } finally {
        g.dispose()
    }

    return OverlayFrame(
        width = width,
        height = height,
        platformHandle = image
    )
}
