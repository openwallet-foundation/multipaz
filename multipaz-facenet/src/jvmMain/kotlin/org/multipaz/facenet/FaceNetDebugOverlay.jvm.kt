package org.multipaz.facenet

import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.OverlayFrame
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

internal actual fun renderDebugOverlay(
    frame: CameraFrame,
    faces: List<DetectedFacePose>,
    currentSimilarity: Float?,
    bestSimilarity: Float,
    matchThreshold: Float
): OverlayFrame? {
    if (faces.isEmpty()) return null
    val face = faces[0] as? BlazeFaceDetection ?: return null

    val width = if (face.imageWidth > 0) face.imageWidth else frame.uprightWidth
    val height = if (face.imageHeight > 0) face.imageHeight else frame.uprightHeight
    if (width <= 0 || height <= 0) return null

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val dpToPx = width.toFloat() / 220f
        fun mapX(x: Double): Int = (width.toDouble() - x).toInt()
        fun mapY(y: Double): Int = y.toInt()

        // 1. Face bounding box
        g.color = Color(0x69, 0xF0, 0xAE)
        g.stroke = BasicStroke(2f * dpToPx)
        val bb = face.boundingBox
        val bbLeft = mapX(bb.right)
        val bbRight = mapX(bb.left)
        g.drawRect(bbLeft, mapY(bb.top), bbRight - bbLeft, (bb.bottom - bb.top).toInt())

        // 2. Facial wireframe / alignment lines
        val rightEye = face.rightEye
        val leftEye = face.leftEye
        val nose = face.noseTip
        val mouth = face.mouthCenter
        val rightEar = face.rightEarTragus
        val leftEar = face.leftEarTragus

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

        // 3. Keypoints (circles)
        fun fillCircle(cx: Int, cy: Int, r: Double, color: Color) {
            g.color = color
            g.fillOval((cx - r).toInt(), (cy - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
        }
        fillCircle(mapX(rightEye.x), mapY(rightEye.y), 4.0 * dpToPx, Color(0x69, 0xF0, 0xAE))
        fillCircle(mapX(leftEye.x), mapY(leftEye.y), 4.0 * dpToPx, Color(0x69, 0xF0, 0xAE))
        fillCircle(mapX(nose.x), mapY(nose.y), 4.0 * dpToPx, Color(0xD5, 0x00, 0x00))
        fillCircle(mapX(mouth.x), mapY(mouth.y), 4.0 * dpToPx, Color(0xFF, 0xD6, 0x00))
        fillCircle(mapX(rightEar.x), mapY(rightEar.y), 3.5 * dpToPx, Color(0x29, 0x79, 0xFF))
        fillCircle(mapX(leftEar.x), mapY(leftEar.y), 3.5 * dpToPx, Color(0x29, 0x79, 0xFF))

        // 4. 3D Head pose direction vector
        val eyeDist = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
        if (eyeDist > 1.0) {
            val rayLength = eyeDist * 0.9
            val radYaw = face.yaw * (PI / 180.0)
            val radPitch = face.pitch * (PI / 180.0)
            val rayDx = sin(radYaw) * rayLength
            val rayDy = sin(radPitch) * rayLength
            val rayEndX = nose.x + rayDx
            val rayEndY = nose.y + rayDy

            g.color = Color(0xFF, 0x52, 0x52)
            g.stroke = BasicStroke(3f * dpToPx)
            g.drawLine(mapX(nose.x), mapY(nose.y), mapX(rayEndX), mapY(rayEndY))
            fillCircle(mapX(rayEndX), mapY(rayEndY), 4.5 * dpToPx, Color(0xFF, 0x52, 0x52))
        }

        // 5. Match percentage badge
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
            val centerX = mapX(face.boundingBox.centerX)
            val textY = if (face.boundingBox.top >= 24.0 * dpToPx) {
                (face.boundingBox.top - 14.0 * dpToPx).toInt()
            } else {
                (face.boundingBox.top + 18.0 * dpToPx + textH).toInt()
            }

            val padH = (10f * dpToPx).toInt()
            val padV = (6f * dpToPx).toInt()
            val cornerRadius = (8f * dpToPx).toInt()
            g.color = Color(0, 0, 0, 160)
            g.fillRoundRect(centerX - textW / 2 - padH, textY - fm.ascent - padV, textW + padH * 2, textH + padV * 2, cornerRadius, cornerRadius)

            g.color = labelColor
            g.drawString(text, centerX - textW / 2, textY)
        }
    } finally {
        g.dispose()
    }

    val argb = (image.raster.dataBuffer as DataBufferInt).data.clone()
    return OverlayFrame(
        width = width,
        height = height,
        argb = argb,
        isMirrored = false
    )
}
