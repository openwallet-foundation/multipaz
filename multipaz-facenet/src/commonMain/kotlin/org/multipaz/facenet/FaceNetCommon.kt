package org.multipaz.facenet

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlinx.io.bytestring.ByteString

/**
 * Common pose interface for detected faces across platforms.
 */
interface DetectedFacePose {
    val yaw: Float
    val pitch: Float
    val roll: Float
}

/**
 * Head pose directions requested during active liveness challenges.
 */
enum class ChallengeDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    CENTER
}

internal fun computeDirectionSegments(
    direction: ChallengeDirection,
    progress: Float,
    activeColor: Int = RingSegment.COLOR_BRIGHT_GREEN,
    baseColor: Int = RingSegment.COLOR_DARK_GRAY
): List<RingSegment> {
    val p = progress.coerceIn(0f, 1f)
    if (direction == ChallengeDirection.CENTER) {
        val color = RingSegment.lerpColor(baseColor, activeColor, p)
        val scale = 1.0f + 0.5f * p
        return List(RingSegment.NUM_SEGMENTS) {
            RingSegment(color = color, scale = scale)
        }
    }

    val targetCenter = when (direction) {
        ChallengeDirection.UP -> 0.0f
        ChallengeDirection.RIGHT -> 4.5f
        ChallengeDirection.DOWN -> 9.0f
        ChallengeDirection.LEFT -> 13.5f
        ChallengeDirection.CENTER -> 0.0f
    }

    val maxRadius = 3.5f
    val minDistance = when (direction) {
        ChallengeDirection.LEFT, ChallengeDirection.RIGHT -> 0.5f
        else -> 0.0f
    }
    val peakFalloff = (0.5f * (1.0f + cos((minDistance / maxRadius) * PI))).toFloat()

    return List(RingSegment.NUM_SEGMENTS) { index ->
        val rawDiff = abs(index.toFloat() - targetCenter)
        val dist = minOf(rawDiff, 18f - rawDiff)

        if (dist < maxRadius) {
            val rawFalloff = (0.5f * (1.0f + cos((dist / maxRadius) * PI))).toFloat()
            val normalizedFalloff = (rawFalloff / peakFalloff).coerceIn(0f, 1f)
            val segmentProgress = (p * normalizedFalloff).coerceIn(0f, 1f)
            val color = RingSegment.lerpColor(baseColor, activeColor, segmentProgress)
            val scale = 1.0f + 0.55f * segmentProgress
            RingSegment(color = color, scale = scale)
        } else {
            RingSegment(color = baseColor, scale = 1.0f)
        }
    }
}

internal fun computeChallengeProgress(direction: ChallengeDirection, yaw: Float, pitch: Float): Float {
    val yawThreshold = 18.0f
    val pitchThreshold = 14.0f
    return when (direction) {
        ChallengeDirection.LEFT -> (yaw / yawThreshold).coerceIn(0f, 1f)
        ChallengeDirection.RIGHT -> (-yaw / yawThreshold).coerceIn(0f, 1f)
        ChallengeDirection.UP -> (pitch / pitchThreshold).coerceIn(0f, 1f)
        ChallengeDirection.DOWN -> (-pitch / pitchThreshold).coerceIn(0f, 1f)
        ChallengeDirection.CENTER -> {
            val deviation = maxOf(abs(yaw), abs(pitch))
            if (deviation <= 8.0f) 1.0f else (1.0f - ((deviation - 8.0f) / 10.0f)).coerceIn(0f, 1f)
        }
    }
}

internal fun getChallengePromptTexts(direction: ChallengeDirection): Pair<String, String> {
    return when (direction) {
        ChallengeDirection.LEFT -> "Look to your left" to "Turn your head left"
        ChallengeDirection.RIGHT -> "Look to your right" to "Turn your head right"
        ChallengeDirection.UP -> "Look up" to "Tilt your head up"
        ChallengeDirection.DOWN -> "Look down" to "Tilt your head down"
        ChallengeDirection.CENTER -> "Look at the camera" to "Look straight ahead"
    }
}

/**
 * Checks whether [bytes] represent a supported reference portrait image format
 * (PNG, JPEG, or JPEG 2000 in JP2 container or raw J2K codestream).
 */
internal fun isSupportedReferencePortraitFormat(bytes: ByteString): Boolean {
    if (bytes.size < 3) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF

    // JPEG: starts with FF D8 FF
    if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
        return true
    }

    // PNG: starts with 89 50 4E 47 0D 0A 1A 0A
    if (bytes.size >= 8 &&
        b0 == 0x89 && b1 == 0x50 && b2 == 0x4E &&
        (bytes[3].toInt() and 0xFF) == 0x47 &&
        (bytes[4].toInt() and 0xFF) == 0x0D &&
        (bytes[5].toInt() and 0xFF) == 0x0A &&
        (bytes[6].toInt() and 0xFF) == 0x1A &&
        (bytes[7].toInt() and 0xFF) == 0x0A
    ) {
        return true
    }

    // JPEG 2000 JP2 format: starts with 00 00 00 0C 6A 50 20 20 0D 0A 87 0A
    if (bytes.size >= 12 &&
        b0 == 0x00 && b1 == 0x00 && b2 == 0x00 &&
        (bytes[3].toInt() and 0xFF) == 0x0C &&
        (bytes[4].toInt() and 0xFF) == 0x6A &&
        (bytes[5].toInt() and 0xFF) == 0x50 &&
        (bytes[6].toInt() and 0xFF) == 0x20 &&
        (bytes[7].toInt() and 0xFF) == 0x20 &&
        (bytes[8].toInt() and 0xFF) == 0x0D &&
        (bytes[9].toInt() and 0xFF) == 0x0A &&
        (bytes[10].toInt() and 0xFF) == 0x87 &&
        (bytes[11].toInt() and 0xFF) == 0x0A
    ) {
        return true
    }

    // JPEG 2000 raw codestream (J2K): starts with FF 4F FF 51
    if (bytes.size >= 4 &&
        b0 == 0xFF && b1 == 0x4F && b2 == 0xFF &&
        (bytes[3].toInt() and 0xFF) == 0x51
    ) {
        return true
    }

    return false
}
