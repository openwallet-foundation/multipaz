package org.multipaz.jpeg2000

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData

@OptIn(ExperimentalForeignApi::class)
actual fun decodeJpeg2000(encodedData: ByteArray): DecodedJpeg2000Image? {
    if (!isJpeg2000(encodedData)) {
        return null
    }
    val cfData = encodedData.usePinned { pinned ->
        CFDataCreate(
            allocator = kCFAllocatorDefault,
            bytes = pinned.addressOf(0).reinterpret(),
            length = encodedData.size.toLong()
        )
    } ?: return null
    val source = CGImageSourceCreateWithData(data = cfData, options = null)
    CFRelease(cfData)
    if (source == null) return null
    val cgImage = CGImageSourceCreateImageAtIndex(isrc = source, index = 0u, options = null)
    CFRelease(source)
    if (cgImage == null) return null

    try {
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        if (width <= 0 || height <= 0) return null

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val bitmapContext = CGBitmapContextCreate(
            data = null,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (width * 4).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        )
        CGColorSpaceRelease(colorSpace)
        if (bitmapContext == null) return null

        try {
            CGContextDrawImage(
                bitmapContext,
                CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                cgImage
            )
            val rawData = CGBitmapContextGetData(bitmapContext) ?: return null
            val bytePtr = rawData.reinterpret<ByteVar>()
            val numPixels = width * height
            val argbPixels = IntArray(numPixels)
            for (i in 0 until numPixels) {
                val r = bytePtr[i * 4 + 0].toInt() and 0xFF
                val g = bytePtr[i * 4 + 1].toInt() and 0xFF
                val b = bytePtr[i * 4 + 2].toInt() and 0xFF
                val a = bytePtr[i * 4 + 3].toInt() and 0xFF
                argbPixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return DecodedJpeg2000Image(width = width, height = height, argbPixels = argbPixels)
        } finally {
            CGContextRelease(bitmapContext)
        }
    } finally {
        CGImageRelease(cgImage)
    }
}
