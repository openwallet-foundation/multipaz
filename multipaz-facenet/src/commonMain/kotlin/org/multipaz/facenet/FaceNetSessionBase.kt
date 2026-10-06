package org.multipaz.facenet

import kotlin.concurrent.Volatile
import kotlin.math.abs
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
    referencePortrait: ByteString,
    val config: FaceNetModelConfig,
    val debug: Boolean = false,
    val matcherName: String = "facenet",
    val matcherDisplayName: String = "MobileFaceNet",
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    randomSeed: Long = clock(),
    val enablePoseSmoothing: Boolean = true
) : FaceMatcherSession(referencePortrait) {

    init {
        require(isSupportedReferencePortraitFormat(referencePortrait)) {
            "Unsupported reference portrait format. Supported formats are PNG, JPEG, and JPEG 2000."
        }
    }

    enum class Phase {
        INITIALIZING,
        POSITIONING,
        MATCH_CONVEYED,
        LIVENESS_CHALLENGE,
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
    val challenges: List<ChallengeDirection> = pool.shuffled(Random(randomSeed)).take(2) + listOf(ChallengeDirection.CENTER)
    var currentChallengeIndex = 0
        protected set
    private var consecutivePoseFrames = 0
    private var missedFaceFrames = 0
    private var consecutiveMatchFrames = 0
    private var straightFaceStartTime: Long? = null
    private val headPoseFilter = HeadPoseFilter()

    val matchTimeoutMs = 8000L
    val sessionTimeoutMs = 25000L
    val matchConveyDurationMs = 1200L

    init {
        updateState(
            messageAbove = "Verify Identity",
            messageBelow = "Position your face and look at the camera",
            status = FaceMatcherPromptState.Status.IN_PROGRESS
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
        if (isCancelled || state.value.status != FaceMatcherPromptState.Status.IN_PROGRESS) {
            return
        }

        if (!frameMutex.tryLock()) {
            return
        }

        try {
            processFrame(frame)
        } finally {
            frameMutex.unlock()
        }
    }

    private suspend fun processFrame(frame: CameraFrame) {
        val now = clock()
        if (startTime == null) {
            startTime = now
            phaseStartTime = now
        }

        if (phase == Phase.INITIALIZING) {
            if (!initializationAttempted) {
                initializationAttempted = true
                try {
                    initializePipeline()
                    if (referenceEmbedding == null) {
                        failSession("Setup Failed", "Could not extract face from reference photo")
                        return
                    }
                    phase = Phase.POSITIONING
                    phaseStartTime = now
                    updateState(
                        messageAbove = "Position your face",
                        messageBelow = "Look directly at the camera"
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to initialize pipeline", e)
                    failSession("Initialization Failed", e.message ?: "Could not start camera pipeline")
                    return
                }
            } else {
                return
            }
        }

        val refEmb = referenceEmbedding
        if (refEmb == null) {
            failSession("Setup Failed", "Missing reference face embedding")
            return
        }

        if (now - startTime!! > sessionTimeoutMs) {
            val percentage = (bestSimilarity * 100).toInt().coerceAtLeast(0)
            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                RingSegment(color = RingSegment.COLOR_RED, scale = 1.0f)
            }
            val overlay = createOverlay(frame, emptyList(), null)
            failSession(
                messageAbove = "Verification Failed",
                messageBelow = "Face verification timed out ($percentage% match, required ${(config.matchThreshold * 100).toInt()}%)",
                overlay = overlay
            )
            return
        }

        val detectedFaces = detectFaces(frame)

        if (isCancelled) return

        if (detectedFaces.isEmpty()) {
            missedFaceFrames++
            consecutiveMatchFrames = 0
            straightFaceStartTime = null
            consecutivePoseFrames = 0

            val overlay = createOverlay(frame, emptyList(), null)
            if (missedFaceFrames >= 3) {
                headPoseFilter.reset()
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
                    else -> {}
                }
            } else {
                updateState(overlay = overlay)
            }
            return
        }

        if (detectedFaces.size > 1) {
            missedFaceFrames = 0
            consecutiveMatchFrames = 0
            straightFaceStartTime = null
            consecutivePoseFrames = 0
            headPoseFilter.reset()
            currentRingSegments = RingSegment.defaultSegments
            val overlay = createOverlay(frame, detectedFaces, null)
            updateState(
                messageAbove = "Multiple faces detected",
                messageBelow = "Ensure only one person is in the frame",
                overlay = overlay
            )
            return
        }

        missedFaceFrames = 0
        val rawFace = detectedFaces[0]
        val (yaw, pitch, roll) = if (enablePoseSmoothing) {
            headPoseFilter.filter(
                yaw = rawFace.yaw,
                pitch = rawFace.pitch,
                roll = rawFace.roll,
                timestampMillis = now
            )
        } else {
            Triple(rawFace.yaw, rawFace.pitch, rawFace.roll)
        }

        @Suppress("UNCHECKED_CAST")
        val face = if (enablePoseSmoothing && rawFace is BlazeFaceDetection) {
            rawFace.copy(yaw = yaw, pitch = pitch, roll = roll) as TFace
        } else {
            rawFace
        }
        val faces = listOf(face)

        val isFacingStraight = abs(yaw) < 12.0f && abs(pitch) < 12.0f && abs(roll) < 15.0f

        var currentSimilarity: Float? = null

        if (isCancelled) return

        if (isFacingStraight) {
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
                }
            }

            Phase.LIVENESS_CHALLENGE -> {
                val direction = challenges[currentChallengeIndex]
                val progress = computeChallengeProgress(direction, yaw, pitch)

                currentRingSegments = computeDirectionSegments(direction, progress)
                val overlay = createOverlay(frame, faces, currentSimilarity)

                if (progress >= 1.0f) {
                    consecutivePoseFrames++
                    if (consecutivePoseFrames >= 2) {
                        currentChallengeIndex++
                        consecutivePoseFrames = 0
                        if (currentChallengeIndex >= challenges.size) {
                            finalizeVerification(frame, faces, currentSimilarity)
                            return
                        } else {
                            val nextDirection = challenges[currentChallengeIndex]
                            currentRingSegments = computeDirectionSegments(nextDirection, 0.0f)
                            val nextOverlay = createOverlay(frame, faces, currentSimilarity)
                            showChallenge(0.0f, nextOverlay)
                            return
                        }
                    }
                } else {
                    consecutivePoseFrames = 0
                }

                showChallenge(progress, overlay)
            }

            Phase.COMPLETED, Phase.FAILED, Phase.INITIALIZING -> {}
        }
    }

    private fun showChallenge(progress: Float, overlay: OverlayFrame? = null) {
        val direction = challenges[currentChallengeIndex]
        val (promptTitle, promptDetail) = getChallengePromptTexts(direction)
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
            headPoseFilter.reset()
            val percent = (bestSimilarity * 100).toInt()
            val verifiedMsg = if (debug) "Identity Verified ($percent%)" else "Identity Verified"
            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.2f)
            }
            updateState(
                messageAbove = verifiedMsg,
                messageBelow = "Verification successful",
                status = FaceMatcherPromptState.Status.SUCCESS,
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
        headPoseFilter.reset()
        updateState(
            messageAbove = messageAbove,
            messageBelow = messageBelow,
            status = FaceMatcherPromptState.Status.FAILED,
            overlay = overlay
        )
    }

    override fun cancel() {
        isCancelled = true
        headPoseFilter.reset()
        if (frameMutex.tryLock()) {
            try {
                onSessionClosed()
            } finally {
                frameMutex.unlock()
            }
        }
    }
}
