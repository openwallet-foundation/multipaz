package org.multipaz.facenet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.multipaz.facematch.CameraFrame
import platform.UIKit.UIImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FaceNetDebugOverlayIosTest {

    @Test
    fun testRenderOverlayReturnsNonNullUIImage() {
        val frame = CameraFrame(
            width = 1080,
            height = 1920,
            rotationDegrees = 0,
            platformHandle = null
        )
        val overlay = renderOverlay(
            frame = frame,
            faces = emptyList(),
            ringSegments = RingSegment.defaultSegments,
            debug = false,
            currentSimilarity = null,
            bestSimilarity = 0f,
            matchThreshold = 0.6f
        )
        assertNotNull(overlay, "renderOverlay should not return null")
        assertEquals(1080, overlay.width)
        assertEquals(1920, overlay.height)
        val uiImage = overlay.platformHandle as? UIImage
        assertNotNull(uiImage, "overlay.platformHandle should be a UIImage")
        println("Successfully generated UIImage: width=${overlay.width}, height=${overlay.height}, cgImage=${uiImage.CGImage}")

        val cgImage = uiImage.CGImage!!
        val imgWidth = platform.CoreGraphics.CGImageGetWidth(cgImage).toInt()
        val imgHeight = platform.CoreGraphics.CGImageGetHeight(cgImage).toInt()
        assertEquals(1080, imgWidth)
        assertEquals(1920, imgHeight)

        // Read pixels
        val colorSpace = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
        val rawBytes = ByteArray(imgWidth * imgHeight * 4)
        rawBytes.usePinned { pinned ->
            val ctx = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0),
                imgWidth.toULong(),
                imgHeight.toULong(),
                8u,
                (imgWidth * 4).toULong(),
                colorSpace,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
            )
            assertNotNull(ctx)
            platform.CoreGraphics.CGContextDrawImage(
                ctx,
                platform.CoreGraphics.CGRectMake(0.0, 0.0, imgWidth.toDouble(), imgHeight.toDouble()),
                cgImage
            )
            platform.CoreGraphics.CGContextRelease(ctx)
        }
        platform.CoreGraphics.CGColorSpaceRelease(colorSpace)

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var nonZeroCount = 0

        for (y in 0 until imgHeight) {
            for (x in 0 until imgWidth) {
                val idx = (y * imgWidth + x) * 4
                val alpha = rawBytes[idx + 3].toInt() and 0xFF
                if (alpha > 0) {
                    nonZeroCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        println("Drawn pixels count: $nonZeroCount, bounding box: X=[$minX, $maxX], Y=[$minY, $maxY]")
        assertTrue(nonZeroCount > 0, "Pixels should be drawn")
        assertTrue(minX >= 0 && maxX < 1080, "X should be within [0, 1080)")
        assertTrue(minY >= 250 && maxY <= 1670, "Y should be within [250, 1670]")
    }

    @Test
    fun testRenderOverlaySimulatorDimensions() {
        val frame = CameraFrame(
            width = 640,
            height = 480,
            rotationDegrees = 0,
            platformHandle = null
        )
        val overlay = renderOverlay(
            frame = frame,
            faces = emptyList(),
            ringSegments = RingSegment.defaultSegments,
            debug = false,
            currentSimilarity = null,
            bestSimilarity = 0f,
            matchThreshold = 0.6f
        )
        assertNotNull(overlay)
        assertEquals(640, overlay.width)
        assertEquals(480, overlay.height)
        val uiImage = overlay.platformHandle as? UIImage
        assertNotNull(uiImage)

        val cgImage = uiImage.CGImage!!
        val imgWidth = platform.CoreGraphics.CGImageGetWidth(cgImage).toInt()
        val imgHeight = platform.CoreGraphics.CGImageGetHeight(cgImage).toInt()

        val colorSpace = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
        val rawBytes = ByteArray(imgWidth * imgHeight * 4)
        rawBytes.usePinned { pinned ->
            val ctx = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0),
                imgWidth.toULong(),
                imgHeight.toULong(),
                8u,
                (imgWidth * 4).toULong(),
                colorSpace,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
            )
            assertNotNull(ctx)
            platform.CoreGraphics.CGContextDrawImage(
                ctx,
                platform.CoreGraphics.CGRectMake(0.0, 0.0, imgWidth.toDouble(), imgHeight.toDouble()),
                cgImage
            )
            platform.CoreGraphics.CGContextRelease(ctx)
        }
        platform.CoreGraphics.CGColorSpaceRelease(colorSpace)

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var nonZeroCount = 0

        for (y in 0 until imgHeight) {
            for (x in 0 until imgWidth) {
                val idx = (y * imgWidth + x) * 4
                val alpha = rawBytes[idx + 3].toInt() and 0xFF
                if (alpha > 0) {
                    nonZeroCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        println("Simulator frame drawn pixels: $nonZeroCount, bounding box: X=[$minX, $maxX], Y=[$minY, $maxY]")
        assertTrue(nonZeroCount > 0)
        assertTrue(minX >= 130 && maxX <= 510, "X=[$minX, $maxX] should be within visible simulator window [130, 510]")
        assertTrue(minY >= 0 && maxY < 480, "Y=[$minY, $maxY] should be within [0, 480)")
    }

    @Test
    fun testDebugOverlayWithFace() {
        val face = BlazeFaceDetection(
            score = 0.95f,
            boundingBox = FaceBoundingBox(left = 200.0, top = 100.0, width = 240.0, height = 300.0),
            rightEye = FacePoint2D(250.0, 180.0),
            leftEye = FacePoint2D(390.0, 180.0),
            noseTip = FacePoint2D(320.0, 240.0),
            mouthCenter = FacePoint2D(320.0, 320.0),
            rightEarTragus = FacePoint2D(210.0, 220.0),
            leftEarTragus = FacePoint2D(430.0, 220.0),
            yaw = 0f,
            pitch = 0f,
            roll = 0f,
            imageWidth = 640,
            imageHeight = 480
        )
        val frame = CameraFrame(
            width = 640,
            height = 480,
            rotationDegrees = 0,
            platformHandle = null
        )
        val overlay = renderOverlay(
            frame = frame,
            faces = listOf(face),
            ringSegments = emptyList(),
            debug = true,
            currentSimilarity = 0.85f,
            bestSimilarity = 0.85f,
            matchThreshold = 0.6f
        )
        assertNotNull(overlay)
        val uiImage = overlay.platformHandle as? UIImage
        assertNotNull(uiImage)

        val cgImage = uiImage.CGImage!!
        val imgWidth = platform.CoreGraphics.CGImageGetWidth(cgImage).toInt()
        val imgHeight = platform.CoreGraphics.CGImageGetHeight(cgImage).toInt()

        val colorSpace = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
        val rawBytes = ByteArray(imgWidth * imgHeight * 4)
        rawBytes.usePinned { pinned ->
            val ctx = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0),
                imgWidth.toULong(),
                imgHeight.toULong(),
                8u,
                (imgWidth * 4).toULong(),
                colorSpace,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
            )
            assertNotNull(ctx)
            platform.CoreGraphics.CGContextDrawImage(
                ctx,
                platform.CoreGraphics.CGRectMake(0.0, 0.0, imgWidth.toDouble(), imgHeight.toDouble()),
                cgImage
            )
            platform.CoreGraphics.CGContextRelease(ctx)
        }
        platform.CoreGraphics.CGColorSpaceRelease(colorSpace)

        // Find pixels with black semi-transparent color (alpha around 0.65, r=0, g=0, b=0)
        var blackMinX = Int.MAX_VALUE
        var blackMaxX = Int.MIN_VALUE
        var blackMinY = Int.MAX_VALUE
        var blackMaxY = Int.MIN_VALUE
        var blackCount = 0

        for (y in 0 until imgHeight) {
            for (x in 0 until imgWidth) {
                val idx = (y * imgWidth + x) * 4
                val r = rawBytes[idx].toInt() and 0xFF
                val g = rawBytes[idx + 1].toInt() and 0xFF
                val b = rawBytes[idx + 2].toInt() and 0xFF
                val a = rawBytes[idx + 3].toInt() and 0xFF
                // If semi-transparent black (a ~ 165 / 255 = 0.65, r, g, b close to 0)
                if (a in 140..190 && r < 20 && g < 20 && b < 20) {
                    blackCount++
                    if (x < blackMinX) blackMinX = x
                    if (x > blackMaxX) blackMaxX = x
                    if (y < blackMinY) blackMinY = y
                    if (y > blackMaxY) blackMaxY = y
                }
            }
        }
        println("Badge semi-transparent black pixels count: $blackCount, box: X=[$blackMinX, $blackMaxX], Y=[$blackMinY, $blackMaxY]")
        assertTrue(blackCount in 2000..7000, "Badge should occupy ~4000-5000 pixels, got $blackCount")
        assertTrue(blackMinX >= 260 && blackMaxX <= 380, "Badge X=[$blackMinX, $blackMaxX] should be centered around face center (320)")
        assertTrue(blackMinY >= 25 && blackMaxY <= 95, "Badge Y=[$blackMinY, $blackMaxY] should be directly above face top (100)")
    }
}
