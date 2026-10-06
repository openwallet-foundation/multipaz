package org.multipaz.facenet

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneEuroFilterTest {

    @Test
    fun testFirstValueReturnsUnmodified() {
        val filter = OneEuroFilter()
        val result = filter.filter(value = 10.0, timestampSeconds = 1.0)
        assertEquals(10.0, result, 1e-6)
    }

    @Test
    fun testStationaryJitterSuppression() {
        // Simulates a stationary head with ±4.0° high-frequency jitter at 30 FPS (~33ms intervals)
        val filter = OneEuroFilter(minCutoff = 1.0, beta = 0.007, dCutoff = 1.0)
        var t = 0.0
        val dt = 1.0 / 30.0

        val filteredValues = mutableListOf<Double>()
        for (i in 0 until 60) {
            t += dt
            val noise = if (i % 2 == 0) 4.0 else -4.0
            val raw = 0.0 + noise
            val filtered = filter.filter(raw, t)
            if (i > 10) { // after settling
                filteredValues.add(filtered)
            }
        }

        // Check that filtered values stay very close to true zero despite ±4° raw noise
        for (v in filteredValues) {
            assertTrue(abs(v) < 1.0, "Expected filtered stationary jitter < 1.0°, but got $v")
        }
    }

    @Test
    fun testFastMovementResponsiveness() {
        // Simulates rapid head turn from 0° to 30° within ~200ms at 30 FPS
        val filter = OneEuroFilter(minCutoff = 1.0, beta = 0.007, dCutoff = 1.0)
        var t = 0.0
        val dt = 1.0 / 30.0

        // Stabilize at 0
        for (i in 0 until 10) {
            t += dt
            filter.filter(0.0, t)
        }

        // Fast ramp to 30 over 6 frames (200ms)
        var lastFiltered = 0.0
        for (i in 1..6) {
            t += dt
            val raw = i * 5.0 // 5, 10, 15, 20, 25, 30
            lastFiltered = filter.filter(raw, t)
        }

        // At end of ramp (raw = 30), filtered output should track closely due to high derivative/adaptive cutoff
        assertTrue(lastFiltered > 20.0, "Filter should track fast movement responsively; got $lastFiltered")
    }

    @Test
    fun testResetClearsHistory() {
        val filter = OneEuroFilter()
        filter.filter(10.0, 1.0)
        filter.filter(12.0, 1.033)
        filter.reset()

        // After reset, new value at t=10.0 should be returned directly as initial sample
        val next = filter.filter(50.0, 10.0)
        assertEquals(50.0, next, 1e-6)
    }

    @Test
    fun testHeadPoseFilterTriplets() {
        val poseFilter = HeadPoseFilter()
        var now = 1000L

        // Initial frame
        val initial = poseFilter.filter(0f, 0f, 0f, now)
        assertEquals(0f, initial.first, 1e-5f)
        assertEquals(0f, initial.second, 1e-5f)
        assertEquals(0f, initial.third, 1e-5f)

        // 30 frames with jitter around (10°, -5°, 2°)
        for (i in 0 until 30) {
            now += 33L
            val jitter = if (i % 2 == 0) 3f else -3f
            val (yaw, pitch, roll) = poseFilter.filter(10f + jitter, -5f + jitter, 2f + jitter, now)
            if (i > 15) {
                assertTrue(abs(yaw - 10f) < 1.0f, "Smoothed yaw $yaw should be close to 10°")
                assertTrue(abs(pitch - (-5f)) < 1.0f, "Smoothed pitch $pitch should be close to -5°")
                assertTrue(abs(roll - 2f) < 1.0f, "Smoothed roll $roll should be close to 2°")
            }
        }

        poseFilter.reset()
        val afterReset = poseFilter.filter(45f, -30f, 15f, now + 1000L)
        assertEquals(45f, afterReset.first, 1e-5f)
        assertEquals(-30f, afterReset.second, 1e-5f)
        assertEquals(15f, afterReset.third, 1e-5f)
    }

    @Test
    fun testIrregularAndPausedTimeSteps() {
        val filter = OneEuroFilter()
        // Same timestamp (dt = 0)
        filter.filter(10.0, 1.0)
        val sameTime = filter.filter(10.5, 1.0)
        assertTrue(!sameTime.isNaN())

        // Huge time gap (e.g. app paused for 10 seconds)
        val afterPause = filter.filter(25.0, 11.0)
        assertTrue(!afterPause.isNaN())
    }
}
