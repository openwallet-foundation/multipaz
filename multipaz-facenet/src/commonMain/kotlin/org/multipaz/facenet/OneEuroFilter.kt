package org.multipaz.facenet

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1€ (One-Euro) Filter for real-time signal smoothing with adaptive cutoff frequency.
 *
 * Originally published by Géry Casiez, Nicolas Roussel, and Daniel Vogel (CHI 2012):
 * "1 € Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems".
 *
 * ### Why this is used in FaceNet / BlazeFace:
 * MediaPipe BlazeFace short-range detects 6 coarse 2D landmarks (eyes, nose, mouth, ears)
 * on a 128x128 feature grid. When 3D head pose angles (yaw, pitch, roll) are computed from
 * geometric distance ratios between these 2D points, single-pixel landmark variations between
 * video frames cause high-frequency angular jitter (often fluctuating ±3° to ±7° while the head
 * is stationary).
 *
 * In contrast to static exponential moving averages (which introduce perceptible lag during fast
 * turns when smoothed sufficiently to eliminate static jitter), the One-Euro filter adapts its
 * cutoff frequency dynamically:
 * - At low velocity (holding still / looking straight), the cutoff frequency approaches [minCutoff],
 *   heavily filtering out landmark jitter.
 * - At high velocity (turning head during liveness challenges), the cutoff frequency increases
 *   proportional to [beta] * derivative, eliminating latency and tracking head movements in real time.
 *
 * @property minCutoff Minimum cutoff frequency in Hertz (Hz). Lower values reduce jitter when stationary.
 * @property beta Sensitivity coefficient for velocity-dependent cutoff adjustment. Higher values reduce lag during fast movements.
 * @property dCutoff Cutoff frequency in Hz for the internal derivative (speed) low-pass filter.
 */
class OneEuroFilter(
    val minCutoff: Double = 1.0,
    val beta: Double = 0.007,
    val dCutoff: Double = 1.0
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0
    private var lastTimestampSeconds: Double? = null

    /**
     * Resets the filter state (e.g. when a face is lost or a new tracking session begins).
     */
    fun reset() {
        xPrev = null
        dxPrev = 0.0
        lastTimestampSeconds = null
    }

    /**
     * Filters input value [value] measured at [timestampSeconds].
     *
     * @param value The raw input measurement.
     * @param timestampSeconds The timestamp of the measurement in seconds.
     * @return The smoothed value.
     */
    fun filter(value: Double, timestampSeconds: Double): Double {
        val prevX = xPrev
        val prevTimestamp = lastTimestampSeconds

        if (prevX == null || prevTimestamp == null) {
            xPrev = value
            dxPrev = 0.0
            lastTimestampSeconds = timestampSeconds
            return value
        }

        // Clamp delta time to reasonable interval [1ms, 1s] to protect against paused or skipped frames
        val dt = (timestampSeconds - prevTimestamp).coerceIn(0.001, 1.0)
        lastTimestampSeconds = timestampSeconds

        // Estimate derivative (rate of change)
        val dx = (value - prevX) / dt
        val alphaDx = computeAlpha(dCutoff, dt)
        val edx = alphaDx * dx + (1.0 - alphaDx) * dxPrev
        dxPrev = edx

        // Adaptive cutoff frequency
        val cutoff = minCutoff + beta * abs(edx)
        val alphaX = computeAlpha(cutoff, dt)
        val filtered = alphaX * value + (1.0 - alphaX) * prevX

        xPrev = filtered
        return filtered
    }

    private fun computeAlpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}

/**
 * Filter that smoothes 3D head pose angles ([yaw], [pitch], [roll]) over time using [OneEuroFilter].
 *
 * This filter eliminates angular jitter from MediaPipe BlazeFace 2D keypoints across camera frames
 * while preserving low-latency tracking responsiveness during active head-movement challenges.
 */
class HeadPoseFilter(
    minCutoff: Double = 1.0,
    beta: Double = 0.007,
    dCutoff: Double = 1.0
) {
    private val yawFilter = OneEuroFilter(minCutoff = minCutoff, beta = beta, dCutoff = dCutoff)
    private val pitchFilter = OneEuroFilter(minCutoff = minCutoff, beta = beta, dCutoff = dCutoff)
    private val rollFilter = OneEuroFilter(minCutoff = minCutoff, beta = beta, dCutoff = dCutoff)

    /**
     * Resets all internal angle filters. Should be called when a face tracking track is interrupted
     * or no face was detected for multiple frames.
     */
    fun reset() {
        yawFilter.reset()
        pitchFilter.reset()
        rollFilter.reset()
    }

    /**
     * Filters the raw [yaw], [pitch], and [roll] angles measured at [timestampMillis].
     *
     * @param yaw Raw yaw angle in degrees.
     * @param pitch Raw pitch angle in degrees.
     * @param roll Raw roll angle in degrees.
     * @param timestampMillis Timestamp of the measurement in epoch milliseconds.
     * @return [Triple] of smoothed (yaw, pitch, roll) in degrees.
     */
    fun filter(
        yaw: Float,
        pitch: Float,
        roll: Float,
        timestampMillis: Long
    ): Triple<Float, Float, Float> {
        val tSec = timestampMillis / 1000.0
        val smoothedYaw = yawFilter.filter(yaw.toDouble(), tSec).toFloat()
        val smoothedPitch = pitchFilter.filter(pitch.toDouble(), tSec).toFloat()
        val smoothedRoll = rollFilter.filter(roll.toDouble(), tSec).toFloat()
        return Triple(smoothedYaw, smoothedPitch, smoothedRoll)
    }
}
