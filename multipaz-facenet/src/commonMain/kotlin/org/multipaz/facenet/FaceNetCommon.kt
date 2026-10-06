package org.multipaz.facenet

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

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
