package org.multipaz.facenet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.OverlayFrame
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

    val bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) {
        null
    }
    if (bitmap == null) {
        return OverlayFrame(width = width, height = height, platformHandle = Any())
    }
    val canvas = Canvas(bitmap)

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

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 1. Black background border around the vertical rounded rectangle
    if (ringSegments.isNotEmpty()) {
        strokePaint.color = android.graphics.Color.BLACK
        strokePaint.strokeWidth = 10f * dpToPx
        val bgPath = Path()
        val fullSamples = outline.sampleSegment(0f, outline.totalLength, numSamples = 72)
        if (fullSamples.isNotEmpty()) {
            bgPath.moveTo(fullSamples[0].first, fullSamples[0].second)
            for (i in 1 until fullSamples.size) {
                bgPath.lineTo(fullSamples[i].first, fullSamples[i].second)
            }
            bgPath.close()
            canvas.drawPath(bgPath, strokePaint)
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
                val segPath = Path()
                segPath.moveTo(segPoints[0].first, segPoints[0].second)
                for (j in 1 until segPoints.size) {
                    segPath.lineTo(segPoints[j].first, segPoints[j].second)
                }
                strokePaint.color = segment.color
                strokePaint.strokeWidth = 5f * dpToPx * segment.scale
                canvas.drawPath(segPath, strokePaint)
            }
        }
    }

    // 3. Debug overlays (if enabled and face detected)
    if (debug && firstFace != null) {
        fun mapX(x: Double): Float = (width.toDouble() - x).toFloat()
        fun mapY(y: Double): Float = y.toFloat()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f * dpToPx
            textAlign = Paint.Align.CENTER
        }

        // Bounding box
        strokePaint.color = 0xFF69F0AE.toInt()
        strokePaint.strokeWidth = 2f * dpToPx
        val bbLeft = mapX(firstFace.boundingBox.right)
        val bbRight = mapX(firstFace.boundingBox.left)
        canvas.drawRect(
            bbLeft,
            mapY(firstFace.boundingBox.top),
            bbRight,
            mapY(firstFace.boundingBox.bottom),
            strokePaint
        )

        // Facial landmarks
        val rightEye = firstFace.rightEye
        val leftEye = firstFace.leftEye
        val nose = firstFace.noseTip
        val mouth = firstFace.mouthCenter
        val rightEar = firstFace.rightEarTragus
        val leftEar = firstFace.leftEarTragus

        // Eye-to-eye axis line
        strokePaint.strokeWidth = 2f * dpToPx
        canvas.drawLine(mapX(rightEye.x), mapY(rightEye.y), mapX(leftEye.x), mapY(leftEye.y), strokePaint)

        // Eye midpoint to nose
        val eyeMidX = (rightEye.x + leftEye.x) / 2.0
        val eyeMidY = (rightEye.y + leftEye.y) / 2.0
        canvas.drawLine(mapX(eyeMidX), mapY(eyeMidY), mapX(nose.x), mapY(nose.y), strokePaint)

        // Nose to mouth
        canvas.drawLine(mapX(nose.x), mapY(nose.y), mapX(mouth.x), mapY(mouth.y), strokePaint)

        // Triangles
        strokePaint.color = 0x8069F0AE.toInt()
        strokePaint.strokeWidth = 1.5f * dpToPx
        canvas.drawLine(mapX(rightEye.x), mapY(rightEye.y), mapX(nose.x), mapY(nose.y), strokePaint)
        canvas.drawLine(mapX(leftEye.x), mapY(leftEye.y), mapX(nose.x), mapY(nose.y), strokePaint)

        strokePaint.color = 0x802979FF.toInt()
        canvas.drawLine(mapX(mouth.x), mapY(mouth.y), mapX(rightEar.x), mapY(rightEar.y), strokePaint)
        canvas.drawLine(mapX(mouth.x), mapY(mouth.y), mapX(leftEar.x), mapY(leftEar.y), strokePaint)

        // Circles
        fillPaint.color = 0xFF69F0AE.toInt()
        canvas.drawCircle(mapX(rightEye.x), mapY(rightEye.y), 4f * dpToPx, fillPaint)
        canvas.drawCircle(mapX(leftEye.x), mapY(leftEye.y), 4f * dpToPx, fillPaint)
        fillPaint.color = 0xFFD50000.toInt()
        canvas.drawCircle(mapX(nose.x), mapY(nose.y), 4f * dpToPx, fillPaint)
        fillPaint.color = 0xFFFFD600.toInt()
        canvas.drawCircle(mapX(mouth.x), mapY(mouth.y), 4f * dpToPx, fillPaint)
        fillPaint.color = 0xFF2979FF.toInt()
        canvas.drawCircle(mapX(rightEar.x), mapY(rightEar.y), 3.5f * dpToPx, fillPaint)
        canvas.drawCircle(mapX(leftEar.x), mapY(leftEar.y), 3.5f * dpToPx, fillPaint)

        // Pose ray
        val eyeDist = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
        if (eyeDist > 1.0) {
            val rayLength = eyeDist * 0.9
            val radYaw = firstFace.yaw * (PI / 180.0)
            val radPitch = firstFace.pitch * (PI / 180.0)
            val rayDx = sin(radYaw) * rayLength
            val rayDy = sin(radPitch) * rayLength
            val rayEndX = nose.x + rayDx
            val rayEndY = nose.y + rayDy

            strokePaint.color = 0xFFFF5252.toInt()
            strokePaint.strokeWidth = 3f * dpToPx
            canvas.drawLine(mapX(nose.x), mapY(nose.y), mapX(rayEndX), mapY(rayEndY), strokePaint)
            fillPaint.color = 0xFFFF5252.toInt()
            canvas.drawCircle(mapX(rayEndX), mapY(rayEndY), 4.5f * dpToPx, fillPaint)
        }

        // Percentage badge
        val similarityToDisplay = currentSimilarity ?: if (bestSimilarity > 0f) bestSimilarity else null
        if (similarityToDisplay != null) {
            val percentage = (similarityToDisplay * 100).toInt().coerceIn(0, 100)
            val isMatch = similarityToDisplay >= matchThreshold
            val labelColor = when {
                isMatch -> 0xFF69F0AE.toInt()
                similarityToDisplay >= 0.4f -> 0xFFFFD600.toInt()
                else -> 0xFFFF5252.toInt()
            }
            val text = "$percentage%"
            val textBounds = Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            val centerX = mapX(firstFace.boundingBox.centerX)
            val textY = if (firstFace.boundingBox.top >= 24.0 * dpToPx) {
                (firstFace.boundingBox.top - 14.0 * dpToPx).toFloat()
            } else {
                (firstFace.boundingBox.top + 18.0 * dpToPx + textBounds.height()).toFloat()
            }

            val padH = 10f * dpToPx
            val padV = 6f * dpToPx
            val bgRect = RectF(
                centerX - textBounds.width() / 2f - padH,
                textY + textBounds.top - padV,
                centerX + textBounds.width() / 2f + padH,
                textY + textBounds.bottom + padV
            )
            fillPaint.color = 0xA0000000.toInt()
            canvas.drawRoundRect(bgRect, 8f * dpToPx, 8f * dpToPx, fillPaint)

            textPaint.color = labelColor
            canvas.drawText(text, centerX, textY, textPaint)
        }
    }

    return OverlayFrame(
        width = width,
        height = height,
        platformHandle = bitmap
    )
}
