package org.multipaz.facenet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.OverlayFrame
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

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Scale drawing elements based on the display viewport (~220dp width)
    val dpToPx = width.toFloat() / 220f

    // Map unmirrored sensor coordinates to mirrored selfie camera preview
    fun mapX(x: Double): Float = (width.toDouble() - x).toFloat()
    fun mapY(y: Double): Float = y.toFloat()

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 16f * dpToPx
        textAlign = Paint.Align.CENTER
    }

    // 1. Face bounding box
    strokePaint.color = 0xFF69F0AE.toInt()
    strokePaint.strokeWidth = 2f * dpToPx
    val bbLeft = mapX(face.boundingBox.right)
    val bbRight = mapX(face.boundingBox.left)
    canvas.drawRect(
        bbLeft,
        mapY(face.boundingBox.top),
        bbRight,
        mapY(face.boundingBox.bottom),
        strokePaint
    )

    // 2. Facial wireframe / alignment lines
    val rightEye = face.rightEye
    val leftEye = face.leftEye
    val nose = face.noseTip
    val mouth = face.mouthCenter
    val rightEar = face.rightEarTragus
    val leftEar = face.leftEarTragus

    // Eye-to-eye axis line
    strokePaint.strokeWidth = 2f * dpToPx
    canvas.drawLine(mapX(rightEye.x), mapY(rightEye.y), mapX(leftEye.x), mapY(leftEye.y), strokePaint)

    // Eye midpoint to nose
    val eyeMidX = (rightEye.x + leftEye.x) / 2.0
    val eyeMidY = (rightEye.y + leftEye.y) / 2.0
    canvas.drawLine(mapX(eyeMidX), mapY(eyeMidY), mapX(nose.x), mapY(nose.y), strokePaint)

    // Nose to mouth
    canvas.drawLine(mapX(nose.x), mapY(nose.y), mapX(mouth.x), mapY(mouth.y), strokePaint)

    // Eye to nose triangles (translucent green)
    strokePaint.color = 0x8069F0AE.toInt()
    strokePaint.strokeWidth = 1.5f * dpToPx
    canvas.drawLine(mapX(rightEye.x), mapY(rightEye.y), mapX(nose.x), mapY(nose.y), strokePaint)
    canvas.drawLine(mapX(leftEye.x), mapY(leftEye.y), mapX(nose.x), mapY(nose.y), strokePaint)

    // Mouth to ears (translucent blue)
    strokePaint.color = 0x802979FF.toInt()
    canvas.drawLine(mapX(mouth.x), mapY(mouth.y), mapX(rightEar.x), mapY(rightEar.y), strokePaint)
    canvas.drawLine(mapX(mouth.x), mapY(mouth.y), mapX(leftEar.x), mapY(leftEar.y), strokePaint)

    // 3. Keypoints (circles)
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

        strokePaint.color = 0xFFFF5252.toInt()
        strokePaint.strokeWidth = 3f * dpToPx
        canvas.drawLine(mapX(nose.x), mapY(nose.y), mapX(rayEndX), mapY(rayEndY), strokePaint)

        fillPaint.color = 0xFFFF5252.toInt()
        canvas.drawCircle(mapX(rayEndX), mapY(rayEndY), 4.5f * dpToPx, fillPaint)
    }

    // 5. Match percentage badge
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
        val centerX = mapX(face.boundingBox.centerX)
        val textY = if (face.boundingBox.top >= 24.0 * dpToPx) {
            (face.boundingBox.top - 14.0 * dpToPx).toFloat()
        } else {
            (face.boundingBox.top + 18.0 * dpToPx + textBounds.height()).toFloat()
        }

        // Draw background pill
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

        // Draw text
        textPaint.color = labelColor
        canvas.drawText(text, centerX, textY, textPaint)
    }

    val argb = IntArray(width * height)
    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
    bitmap.recycle()

    return OverlayFrame(
        width = width,
        height = height,
        argb = argb,
        isMirrored = false
    )
}
