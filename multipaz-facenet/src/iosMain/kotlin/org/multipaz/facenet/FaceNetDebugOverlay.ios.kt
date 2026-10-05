package org.multipaz.facenet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.OverlayFrame
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextAddLineToPoint
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextFillEllipseInRect
import platform.CoreGraphics.CGContextMoveToPoint
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGContextStrokeRect
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.Foundation.NSString
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

@OptIn(ExperimentalForeignApi::class)
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

    val colorSpace = CGColorSpaceCreateDeviceRGB()
    val bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
    val context = CGBitmapContextCreate(
        null,
        width.toULong(),
        height.toULong(),
        8u,
        (width * 4).toULong(),
        colorSpace,
        bitmapInfo
    )
    CGColorSpaceRelease(colorSpace)
    if (context == null) return null

    try {
        UIGraphicsPushContext(context)
        try {
            // Setup coordinates: inverted Y for UIKit drawing on top of CGBitmapContext
            CGContextTranslateCTM(context, 0.0, height.toDouble())
            CGContextScaleCTM(context, 1.0, -1.0)

            val dpToPx = width.toDouble() / 220.0
            fun mapX(x: Double): Double = width.toDouble() - x
            fun mapY(y: Double): Double = y

            // 1. Face bounding box
            CGContextSetRGBStrokeColor(context, 0x69 / 255.0, 0xF0 / 255.0, 0xAE / 255.0, 1.0)
            CGContextSetLineWidth(context, 2.0 * dpToPx)
            val bb = face.boundingBox
            val bbLeft = mapX(bb.right.toDouble())
            val bbRight = mapX(bb.left.toDouble())
            val rect = CGRectMake(bbLeft, mapY(bb.top.toDouble()), bbRight - bbLeft, (bb.bottom - bb.top).toDouble())
            CGContextStrokeRect(context, rect)

            // 2. Facial wireframe / alignment lines
            val rightEye = face.rightEye
            val leftEye = face.leftEye
            val nose = face.noseTip
            val mouth = face.mouthCenter
            val rightEar = face.rightEarTragus
            val leftEar = face.leftEarTragus

            fun drawLine(x1: Double, y1: Double, x2: Double, y2: Double) {
                CGContextBeginPath(context)
                CGContextMoveToPoint(context, x1, y1)
                CGContextAddLineToPoint(context, x2, y2)
                CGContextStrokePath(context)
            }

            // Eye-to-eye
            CGContextSetLineWidth(context, 2.0 * dpToPx)
            drawLine(mapX(rightEye.x.toDouble()), mapY(rightEye.y.toDouble()), mapX(leftEye.x.toDouble()), mapY(leftEye.y.toDouble()))

            // Eye midpoint to nose
            val eyeMidX = (rightEye.x + leftEye.x) / 2.0
            val eyeMidY = (rightEye.y + leftEye.y) / 2.0
            drawLine(mapX(eyeMidX), mapY(eyeMidY), mapX(nose.x.toDouble()), mapY(nose.y.toDouble()))

            // Nose to mouth
            drawLine(mapX(nose.x.toDouble()), mapY(nose.y.toDouble()), mapX(mouth.x.toDouble()), mapY(mouth.y.toDouble()))

            // Eye to nose triangles (translucent green)
            CGContextSetRGBStrokeColor(context, 0x69 / 255.0, 0xF0 / 255.0, 0xAE / 255.0, 0.5)
            CGContextSetLineWidth(context, 1.5 * dpToPx)
            drawLine(mapX(rightEye.x.toDouble()), mapY(rightEye.y.toDouble()), mapX(nose.x.toDouble()), mapY(nose.y.toDouble()))
            drawLine(mapX(leftEye.x.toDouble()), mapY(leftEye.y.toDouble()), mapX(nose.x.toDouble()), mapY(nose.y.toDouble()))

            // Mouth to ears (translucent blue)
            CGContextSetRGBStrokeColor(context, 0x29 / 255.0, 0x79 / 255.0, 1.0, 0.5)
            drawLine(mapX(mouth.x.toDouble()), mapY(mouth.y.toDouble()), mapX(rightEar.x.toDouble()), mapY(rightEar.y.toDouble()))
            drawLine(mapX(mouth.x.toDouble()), mapY(mouth.y.toDouble()), mapX(leftEar.x.toDouble()), mapY(leftEar.y.toDouble()))

            // 3. Keypoints (circles)
            fun fillCircle(cx: Double, cy: Double, r: Double) {
                CGContextFillEllipseInRect(context, CGRectMake(cx - r, cy - r, r * 2.0, r * 2.0))
            }
            CGContextSetRGBFillColor(context, 0x69 / 255.0, 0xF0 / 255.0, 0xAE / 255.0, 1.0)
            fillCircle(mapX(rightEye.x.toDouble()), mapY(rightEye.y.toDouble()), 4.0 * dpToPx)
            fillCircle(mapX(leftEye.x.toDouble()), mapY(leftEye.y.toDouble()), 4.0 * dpToPx)
            CGContextSetRGBFillColor(context, 0xD5 / 255.0, 0.0, 0.0, 1.0)
            fillCircle(mapX(nose.x.toDouble()), mapY(nose.y.toDouble()), 4.0 * dpToPx)
            CGContextSetRGBFillColor(context, 1.0, 0xD6 / 255.0, 0.0, 1.0)
            fillCircle(mapX(mouth.x.toDouble()), mapY(mouth.y.toDouble()), 4.0 * dpToPx)
            CGContextSetRGBFillColor(context, 0x29 / 255.0, 0x79 / 255.0, 1.0, 1.0)
            fillCircle(mapX(rightEar.x.toDouble()), mapY(rightEar.y.toDouble()), 3.5 * dpToPx)
            fillCircle(mapX(leftEar.x.toDouble()), mapY(leftEar.y.toDouble()), 3.5 * dpToPx)

            // 4. 3D Head pose direction vector
            val eyeDist = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y).toDouble()
            if (eyeDist > 1.0) {
                val rayLength = eyeDist * 0.9
                val radYaw = face.yaw * (PI / 180.0)
                val radPitch = face.pitch * (PI / 180.0)
                val rayDx = sin(radYaw) * rayLength
                val rayDy = sin(radPitch) * rayLength
                val rayEndX = nose.x + rayDx
                val rayEndY = nose.y + rayDy

                CGContextSetRGBStrokeColor(context, 1.0, 0x52 / 255.0, 0x52 / 255.0, 1.0)
                CGContextSetLineWidth(context, 3.0 * dpToPx)
                drawLine(mapX(nose.x.toDouble()), mapY(nose.y.toDouble()), mapX(rayEndX), mapY(rayEndY))
                CGContextSetRGBFillColor(context, 1.0, 0x52 / 255.0, 0x52 / 255.0, 1.0)
                fillCircle(mapX(rayEndX), mapY(rayEndY), 4.5 * dpToPx)
            }

            // 5. Match percentage badge
            val similarityToDisplay = currentSimilarity ?: if (bestSimilarity > 0f) bestSimilarity else null
            if (similarityToDisplay != null) {
                val percentage = (similarityToDisplay * 100).toInt().coerceIn(0, 100)
                val isMatch = similarityToDisplay >= matchThreshold
                val textColor = when {
                    isMatch -> UIColor.colorWithRed(0x69 / 255.0, 0xF0 / 255.0, 0xAE / 255.0, 1.0)
                    similarityToDisplay >= 0.4f -> UIColor.colorWithRed(1.0, 0xD6 / 255.0, 0.0, 1.0)
                    else -> UIColor.colorWithRed(1.0, 0x52 / 255.0, 0x52 / 255.0, 1.0)
                }
                val text = "$percentage%"
                val fontSize = 16.0 * dpToPx
                val font = UIFont.boldSystemFontOfSize(fontSize)
                val attrs = mapOf<Any?, Any?>(
                    NSFontAttributeName to font,
                    NSForegroundColorAttributeName to textColor
                )
                val nsText = text as NSString
                val textSize = nsText.sizeWithAttributes(attrs)
                val textWidth = textSize.useContents { width }
                val textHeight = textSize.useContents { height }

                val centerX = mapX(face.boundingBox.centerX.toDouble())
                val textY = if (face.boundingBox.top >= 24.0 * dpToPx) {
                    face.boundingBox.top.toDouble() - 14.0 * dpToPx - textHeight
                } else {
                    face.boundingBox.top.toDouble() + 18.0 * dpToPx
                }
                val textX = centerX - textWidth / 2.0

                val padH = 10.0 * dpToPx
                val padV = 6.0 * dpToPx
                val bgRect = CGRectMake(
                    textX - padH,
                    textY - padV,
                    textWidth + padH * 2.0,
                    textHeight + padV * 2.0
                )
                val path = UIBezierPath.bezierPathWithRoundedRect(bgRect, cornerRadius = 8.0 * dpToPx)
                UIColor.blackColor.colorWithAlphaComponent(0.65).setFill()
                path.fill()

                nsText.drawAtPoint(CGPointMake(textX, textY), withAttributes = attrs)
            }
        } finally {
            UIGraphicsPopContext()
        }

        val rawData = CGBitmapContextGetData(context)
        val totalPixels = width * height
        val argb = IntArray(totalPixels)
        if (rawData != null) {
            val intPtr = rawData.reinterpret<kotlinx.cinterop.IntVar>()
            for (i in 0 until totalPixels) {
                argb[i] = intPtr[i]
            }
        }

        return OverlayFrame(
            width = width,
            height = height,
            argb = argb,
            isMirrored = false
        )
    } finally {
        CGContextRelease(context)
    }
}
