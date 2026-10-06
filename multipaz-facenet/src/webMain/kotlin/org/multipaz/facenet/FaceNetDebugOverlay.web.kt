package org.multipaz.facenet

import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.OverlayFrame

internal actual fun renderOverlay(
    frame: CameraFrame,
    faces: List<DetectedFacePose>,
    ringSegments: List<RingSegment>,
    debug: Boolean,
    currentSimilarity: Float?,
    bestSimilarity: Float,
    matchThreshold: Float
): OverlayFrame? = null
