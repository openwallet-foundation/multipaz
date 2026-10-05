package org.multipaz.facenet

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.OverlayFrame
import org.multipaz.util.Logger

private const val TAG = "FaceNetSessionBase"

/**
 * Common pose interface for detected faces across platforms.
 */
interface DetectedFacePose {
    val yaw: Float
    val pitch: Float
    val roll: Float
}

/**
 * Base class containing the shared biometric verification state machine for FaceNet.
 *
 * Implements:
 * - Two-phase verification: Positioning / Match Confirmation followed by Active Liveness Challenge.
 * - Latching prevention: requires 2 consecutive matching frames to prevent accidental triggers.
 * - Head pose guidance: detects head pitch, yaw, and roll with contextual user guidance.
 * - Active liveness challenges: prompts random directional head turns + center gaze.
 * - Timeout handling: 8-second match timeout with formatted percentage feedback, 25-second total session timeout.
 * - Dynamic visual feedback via [OverlayFrame]: pulsing ring during positioning, progressive highlight during challenges.
 */
abstract class FaceNetSessionBase<TFace : DetectedFacePose>(
    referencePortrait: ByteString? = null,
    val config: FaceNetModelConfig,
    val debug: Boolean = false,
    val matcherName: String = "facenet",
    val matcherDisplayName: String = "MobileFaceNet",
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    randomSeed: Long = clock()
) : FaceMatcherSession(referencePortrait) {

    enum class ChallengeDirection {
        LEFT,
        RIGHT,
        UP,
        DOWN,
        CENTER
    }

    val isLivenessOnly: Boolean
        get() = (referencePortrait == null)

    enum class Phase {
        INITIALIZING,
        POSITIONING,
        MATCH_CONVEYED,
        LIVENESS_CHALLENGE,
        CAPTURING,
        COMPLETED,
        FAILED
    }

    var phase = Phase.INITIALIZING
        protected set

    private var startTime: Long? = null
    private var phaseStartTime: Long = 0L

    @Volatile
    var isCancelled: Boolean = false
        protected set

    protected var referenceEmbedding: FaceEmbedding? = null
    var bestSimilarity: Float = 0.0f
        protected set
    private var initializationAttempted = false

    private var currentRingSegments: List<RingSegment> = RingSegment.defaultSegments
    internal val ringSegments: List<RingSegment>
        get() = currentRingSegments

    private val pool = listOf(ChallengeDirection.LEFT, ChallengeDirection.RIGHT, ChallengeDirection.UP, ChallengeDirection.DOWN)
    val challenges: List<ChallengeDirection> = if (isLivenessOnly) {
        pool.shuffled(Random(randomSeed)).take(3)
    } else {
        pool.shuffled(Random(randomSeed)).take(2) + listOf(ChallengeDirection.CENTER)
    }
    var currentChallengeIndex = 0
        protected set
    private var consecutivePoseFrames = 0
    private var missedFaceFrames = 0
    private var consecutiveMatchFrames = 0
    private var straightFaceStartTime: Long? = null

    val matchTimeoutMs = 8000L
    val sessionTimeoutMs = 25000L
    val matchConveyDurationMs = 1200L

    init {
        updateState(
            messageAbove = if (isLivenessOnly) "Check Liveness" else "Verify Identity",
            messageBelow = "Position your face and look at the camera",
            outcome = FaceMatcherPromptState.Outcome.IN_PROGRESS
        )
    }

    /**
     * Initializes platform-specific detector and interpreter pipelines,
     * and sets [referenceEmbedding].
     */
    protected abstract suspend fun initializePipeline()

    /**
     * Detects faces in [frame].
     */
    protected abstract suspend fun detectFaces(frame: CameraFrame): List<TFace>

    /**
     * Extracts face crop for [face] from [frame] and computes its embedding.
     */
    protected abstract suspend fun computeCameraEmbedding(frame: CameraFrame, face: TFace): FaceEmbedding?

    /**
     * Releases platform resources when session is closed or cancelled.
     */
    protected abstract fun onSessionClosed()

    /**
     * Captures a high-resolution upright portrait photo from [frame] when in enrollment mode.
     */
    protected abstract suspend fun captureHighResolutionImage(frame: CameraFrame): ByteString?

    protected open fun createOverlay(
        frame: CameraFrame,
        faces: List<TFace>,
        currentSimilarity: Float?
    ): OverlayFrame? {
        return renderOverlay(
            frame = frame,
            faces = faces,
            ringSegments = currentRingSegments,
            debug = debug,
            currentSimilarity = currentSimilarity,
            bestSimilarity = bestSimilarity,
            matchThreshold = config.matchThreshold
        )
    }

    private val frameMutex = Mutex()

    override suspend fun feedFrame(frame: CameraFrame) {
        if (isCancelled || state.value.outcome != FaceMatcherPromptState.Outcome.IN_PROGRESS) {
            return
        }
        if (!frameMutex.tryLock()) {
            // Drop frame: earlier frame is still being processed
            return
        }
        try {
            if (isCancelled || state.value.outcome != FaceMatcherPromptState.Outcome.IN_PROGRESS) {
                return
            }
            processFrame(frame)
        } finally {
            try {
                if (isCancelled || state.value.outcome != FaceMatcherPromptState.Outcome.IN_PROGRESS) {
                    onSessionClosed()
                }
            } finally {
                frameMutex.unlock()
            }
        }
    }

    private suspend fun processFrame(frame: CameraFrame) {
        if (isCancelled || state.value.outcome != FaceMatcherPromptState.Outcome.IN_PROGRESS) {
            return
        }

        val now = clock()
        val sessionStart = startTime ?: run {
            startTime = now
            phaseStartTime = now
            now
        }

        if (now - sessionStart > sessionTimeoutMs) {
            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                RingSegment(color = RingSegment.COLOR_RED, scale = 1.0f)
            }
            val overlay = createOverlay(frame, emptyList(), null)
            failSession("Verification Failed", "Verification timed out", overlay)
            return
        }

        if (!initializationAttempted) {
            initializationAttempted = true
            try {
                initializePipeline()
            } catch (e: Exception) {
                if (isCancelled) return
                Logger.e(TAG, "Pipeline initialization failed", e)
                currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                    RingSegment(color = RingSegment.COLOR_RED, scale = 1.0f)
                }
                failSession("Verification Error", e.message ?: "Failed to initialize face matching model")
                return
            }
            if (isCancelled) {
                onSessionClosed()
                return
            }
            phase = Phase.POSITIONING
            phaseStartTime = now
        }

        if (isCancelled) return
        val refEmb = referenceEmbedding
        if (!isLivenessOnly && refEmb == null) return

        val faces = detectFaces(frame)
        if (isCancelled) return

        if (faces.isEmpty()) {
            missedFaceFrames++
            consecutiveMatchFrames = 0
            straightFaceStartTime = null
            consecutivePoseFrames = 0
            currentRingSegments = RingSegment.defaultSegments
            val overlay = createOverlay(frame, faces, null)
            if (missedFaceFrames >= 3) {
                when (phase) {
                    Phase.POSITIONING -> {
                        updateState(
                            messageAbove = "Position your face",
                            messageBelow = "No face detected",
                            overlay = overlay
                        )
                    }
                    Phase.LIVENESS_CHALLENGE -> {
                        updateState(
                            messageBelow = "Face lost, looking for face...",
                            overlay = overlay
                        )
                    }
                    Phase.CAPTURING -> {
                        updateState(
                            messageBelow = "Face lost, hold still...",
                            overlay = overlay
                        )
                    }
                    else -> {}
                }
            } else {
                updateState(overlay = overlay)
            }
            return
        }

        if (faces.size > 1) {
            missedFaceFrames = 0
            consecutiveMatchFrames = 0
            straightFaceStartTime = null
            consecutivePoseFrames = 0
            currentRingSegments = RingSegment.defaultSegments
            val overlay = createOverlay(frame, faces, null)
            updateState(
                messageAbove = "Multiple faces detected",
                messageBelow = "Ensure only one person is in the frame",
                overlay = overlay
            )
            return
        }

        missedFaceFrames = 0
        val face = faces[0]
        val yaw = face.yaw
        val pitch = face.pitch
        val roll = face.roll

        val isFacingStraight = abs(yaw) < 12.0f && abs(pitch) < 12.0f && abs(roll) < 15.0f

        var currentSimilarity: Float? = null

        if (isCancelled) return

        // Whenever the face is facing straight and we have a reference embedding, compute similarity
        if (isFacingStraight && refEmb != null) {
            try {
                val cameraEmbedding = computeCameraEmbedding(frame, face)
                if (cameraEmbedding != null) {
                    val similarity = refEmb.calculateSimilarity(cameraEmbedding)
                    currentSimilarity = similarity
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity
                        Logger.d(TAG, "New best face similarity: $bestSimilarity (threshold=${config.matchThreshold})")
                    }
                }
            } catch (e: Exception) {
                if (!isCancelled) {
                    Logger.w(TAG, "Failed to compute embedding from camera frame", e)
                }
            }
        }

        if (isCancelled) return

        when (phase) {
            Phase.POSITIONING -> {
                val elapsed = now - phaseStartTime
                val pulse = (sin(elapsed / 250.0) * 0.35 + 0.65).toFloat()
                val pulseColor = RingSegment.lerpColor(RingSegment.COLOR_DARK_GRAY, RingSegment.COLOR_BLUE, pulse)

                if (isFacingStraight) {
                    if (isLivenessOnly) {
                        consecutiveMatchFrames++
                        if (consecutiveMatchFrames >= 2) {
                            phase = Phase.LIVENESS_CHALLENGE
                            phaseStartTime = now
                            currentChallengeIndex = 0
                            consecutivePoseFrames = 0
                            consecutiveMatchFrames = 0
                            currentRingSegments = computeDirectionSegments(challenges[0], 0.0f)
                            val overlay = createOverlay(frame, faces, currentSimilarity)
                            showChallenge(0.0f, overlay)
                        } else {
                            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                                RingSegment(color = pulseColor, scale = 1.0f)
                            }
                            val overlay = createOverlay(frame, faces, currentSimilarity)
                            updateState(
                                messageAbove = "Position your face",
                                messageBelow = "Hold still...",
                                overlay = overlay
                            )
                        }
                    } else {
                        val straightStart = straightFaceStartTime ?: run {
                            straightFaceStartTime = now
                            now
                        }

                        val isMatched = currentSimilarity != null && currentSimilarity >= config.matchThreshold

                        if (isMatched) {
                            consecutiveMatchFrames++
                            if (consecutiveMatchFrames >= 2) {
                                phase = Phase.MATCH_CONVEYED
                                phaseStartTime = now
                                straightFaceStartTime = null
                                val percent = ((currentSimilarity ?: bestSimilarity) * 100).toInt()
                                val matchedMsg = if (debug) "Face Matched ($percent%)" else "Face Matched"
                                currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                                    RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.15f)
                                }
                                val overlay = createOverlay(frame, faces, currentSimilarity)
                                updateState(
                                    messageAbove = matchedMsg,
                                    messageBelow = "Hold still...",
                                    overlay = overlay
                                )
                            } else {
                                val percent = ((currentSimilarity ?: bestSimilarity) * 100).toInt()
                                val verifyingMsg = if (debug) "Verifying Identity ($percent%)" else "Verifying Identity"
                                currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                                    RingSegment(color = pulseColor, scale = 1.0f)
                                }
                                val overlay = createOverlay(frame, faces, currentSimilarity)
                                updateState(
                                    messageAbove = verifyingMsg,
                                    messageBelow = "Hold still...",
                                    overlay = overlay
                                )
                            }
                        } else {
                            consecutiveMatchFrames = 0
                            if (now - straightStart > matchTimeoutMs) {
                                val percentage = (bestSimilarity * 100).toInt().coerceAtLeast(0)
                                currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                                    RingSegment(color = RingSegment.COLOR_RED, scale = 1.0f)
                                }
                                val overlay = createOverlay(frame, faces, currentSimilarity)
                                if (bestSimilarity >= config.matchThreshold) {
                                    failSession(
                                        messageAbove = "Verification Failed",
                                        messageBelow = "Unable to confirm match - please hold still and look directly at the camera",
                                        overlay = overlay
                                    )
                                } else {
                                    failSession(
                                        messageAbove = "Verification Failed",
                                        messageBelow = "Face does not match reference portrait ($percentage% match, required ${(config.matchThreshold * 100).toInt()}%)",
                                        overlay = overlay
                                    )
                                }
                                return
                            }
                            val verifyingMsg = if (debug && currentSimilarity != null) {
                                val percent = (currentSimilarity * 100).toInt()
                                "Verifying Identity ($percent%)"
                            } else {
                                "Verifying Identity"
                            }
                            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                                RingSegment(color = pulseColor, scale = 1.0f)
                            }
                            val overlay = createOverlay(frame, faces, currentSimilarity)
                            updateState(
                                messageAbove = verifyingMsg,
                                messageBelow = "Hold still and look directly at the camera...",
                                overlay = overlay
                            )
                        }
                    }
                } else {
                    straightFaceStartTime = null
                    consecutiveMatchFrames = 0
                    val prompt = when {
                        yaw > 12f -> "Turn your head slightly to the right"
                        yaw < -12f -> "Turn your head slightly to the left"
                        pitch > 12f -> "Tilt your head slightly down"
                        pitch < -12f -> "Tilt your head slightly up"
                        roll > 15f || roll < -15f -> "Keep your head level"
                        else -> "Look directly at the camera"
                    }
                    currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                        RingSegment(color = pulseColor, scale = 1.0f)
                    }
                    val overlay = createOverlay(frame, faces, currentSimilarity)
                    updateState(
                        messageAbove = "Position your face",
                        messageBelow = prompt,
                        overlay = overlay
                    )
                }
            }

            Phase.MATCH_CONVEYED -> {
                val elapsed = now - phaseStartTime
                if (elapsed >= matchConveyDurationMs) {
                    phase = Phase.LIVENESS_CHALLENGE
                    phaseStartTime = now
                    currentChallengeIndex = 0
                    consecutivePoseFrames = 0
                    currentRingSegments = computeDirectionSegments(challenges[0], 0.0f)
                    val overlay = createOverlay(frame, faces, currentSimilarity)
                    showChallenge(0.0f, overlay)
                } else {
                    val scale = 1.15f - (elapsed.toFloat() / matchConveyDurationMs) * 0.15f
                    currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                        RingSegment(color = RingSegment.COLOR_GREEN, scale = scale)
                    }
                    val overlay = createOverlay(frame, faces, currentSimilarity)
                    updateState(overlay = overlay)
                }
            }

            Phase.LIVENESS_CHALLENGE -> {
                val challenge = challenges[currentChallengeIndex]
                val progress = computeChallengeProgress(challenge, yaw, pitch)
                currentRingSegments = computeDirectionSegments(challenge, progress)
                val overlay = createOverlay(frame, faces, currentSimilarity)
                showChallenge(progress, overlay)

                if (progress >= 0.85f) {
                    consecutivePoseFrames++
                    if (consecutivePoseFrames >= 3) {
                        consecutivePoseFrames = 0
                        currentChallengeIndex++
                        if (currentChallengeIndex >= challenges.size) {
                            if (isLivenessOnly) {
                                phase = Phase.CAPTURING
                                phaseStartTime = now
                                consecutiveMatchFrames = 0
                                currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                                    RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.15f)
                                }
                                val capOverlay = createOverlay(frame, faces, currentSimilarity)
                                updateState(
                                    messageAbove = "Hold Still",
                                    messageBelow = "Hold still to capture photo...",
                                    overlay = capOverlay
                                )
                            } else {
                                finalizeVerification(frame, faces, currentSimilarity)
                            }
                        } else {
                            phaseStartTime = now
                            currentRingSegments = computeDirectionSegments(challenges[currentChallengeIndex], 0.0f)
                            val nextOverlay = createOverlay(frame, faces, currentSimilarity)
                            showChallenge(0.0f, nextOverlay)
                        }
                    }
                } else {
                    consecutivePoseFrames = 0
                }
            }

            Phase.CAPTURING -> {
                if (isFacingStraight) {
                    consecutiveMatchFrames++
                    if (consecutiveMatchFrames >= 2) {
                        val photoBytes = captureHighResolutionImage(frame)
                        phase = Phase.COMPLETED
                        currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                            RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.2f)
                        }
                        updateState(
                            messageAbove = "Portrait Captured",
                            messageBelow = "Liveness verified",
                            outcome = FaceMatcherPromptState.Outcome.SUCCESS,
                            capturedImage = photoBytes,
                            overlay = null
                        )
                    } else {
                        currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                            RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.15f)
                        }
                        val overlay = createOverlay(frame, faces, currentSimilarity)
                        updateState(
                            messageAbove = "Hold Still",
                            messageBelow = "Capturing portrait image...",
                            overlay = overlay
                        )
                    }
                } else {
                    consecutiveMatchFrames = 0
                    currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                        RingSegment(color = RingSegment.COLOR_DARK_GRAY, scale = 1.0f)
                    }
                    val overlay = createOverlay(frame, faces, currentSimilarity)
                    updateState(
                        messageAbove = "Hold Still",
                        messageBelow = "Look directly at the camera...",
                        overlay = overlay
                    )
                }
            }

            Phase.COMPLETED, Phase.FAILED, Phase.INITIALIZING -> {}
        }
    }

    protected fun computeDirectionSegments(
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

    private fun computeChallengeProgress(direction: ChallengeDirection, yaw: Float, pitch: Float): Float {
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

    private fun showChallenge(progress: Float, overlay: OverlayFrame? = null) {
        val direction = challenges[currentChallengeIndex]
        val (promptTitle, promptDetail) = when (direction) {
            ChallengeDirection.LEFT -> "Look to your left" to "Turn your head left"
            ChallengeDirection.RIGHT -> "Look to your right" to "Turn your head right"
            ChallengeDirection.UP -> "Look up" to "Tilt your head up"
            ChallengeDirection.DOWN -> "Look down" to "Tilt your head down"
            ChallengeDirection.CENTER -> "Look at the camera" to "Look straight ahead"
        }

        val stepText = "Step ${currentChallengeIndex + 1} of ${challenges.size}"

        updateState(
            messageAbove = "$promptTitle ($stepText)",
            messageBelow = promptDetail,
            overlay = overlay
        )
    }

    private fun finalizeVerification(
        frame: CameraFrame,
        faces: List<TFace>,
        currentSimilarity: Float?
    ) {
        if (bestSimilarity >= config.matchThreshold) {
            phase = Phase.COMPLETED
            val percent = (bestSimilarity * 100).toInt()
            val verifiedMsg = if (debug) "Identity Verified ($percent%)" else "Identity Verified"
            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.2f)
            }
            updateState(
                messageAbove = verifiedMsg,
                messageBelow = "Verification successful",
                outcome = FaceMatcherPromptState.Outcome.SUCCESS,
                overlay = null
            )
        } else {
            val percentage = (bestSimilarity * 100).toInt().coerceAtLeast(0)
            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                RingSegment(color = RingSegment.COLOR_RED, scale = 1.0f)
            }
            val overlay = createOverlay(frame, faces, currentSimilarity)
            failSession(
                messageAbove = "Verification Failed",
                messageBelow = "Face does not match reference portrait ($percentage% match, required ${(config.matchThreshold * 100).toInt()}%)",
                overlay = overlay
            )
        }
    }

    protected fun failSession(
        messageAbove: String,
        messageBelow: String,
        overlay: OverlayFrame? = null
    ) {
        phase = Phase.FAILED
        updateState(
            messageAbove = messageAbove,
            messageBelow = messageBelow,
            outcome = FaceMatcherPromptState.Outcome.FAILED,
            overlay = overlay
        )
    }

    override fun cancel() {
        isCancelled = true
        if (frameMutex.tryLock()) {
            try {
                onSessionClosed()
            } finally {
                frameMutex.unlock()
            }
        }
    }
}
