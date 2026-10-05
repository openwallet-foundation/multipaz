package org.multipaz.facenet

import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.OverlayFrame

internal expect fun renderDebugOverlay(
    frame: CameraFrame,
    faces: List<DetectedFacePose>,
    currentSimilarity: Float?,
    bestSimilarity: Float,
    matchThreshold: Float
): OverlayFrame?
