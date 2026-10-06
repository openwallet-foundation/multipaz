package org.multipaz.facenet

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherLivenessPromptState
import org.multipaz.facematch.FaceMatcherLivenessSession
import org.multipaz.facematch.OverlayFrame
import org.multipaz.util.Logger

private const val TAG = "FaceNetLivenessSessionBase"

/**
 * Base class containing the shared active liveness verification state machine for FaceNet.
 *
 * Implements:
 * - Active liveness challenges: prompts random directional head turns.
 * - Preparation for photo capture: 2-second hold-still phase emitting [FaceMatcherLivenessPromptState.Status.PREPARE_FOR_PHOTO].
 * - High-resolution portrait photo capture upon completing the hold-still phase.
 * - Dynamic visual feedback via [OverlayFrame] and segmented challenge ring.
 */
abstract class FaceNetLivenessSessionBase<TFace : DetectedFacePose>(
    val config: FaceNetModelConfig,
    val debug: Boolean = false,
    val matcherName: String = "facenet",
    val matcherDisplayName: String = "MobileFaceNet",
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    randomSeed: Long = clock()
) : FaceMatcherLivenessSession() {

    enum class Phase {
        INITIALIZING,
        POSITIONING,
        LIVENESS_CHALLENGE,
        PREPARE_FOR_PHOTO,
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

    private var initializationAttempted = false

    private var currentRingSegments: List<RingSegment> = RingSegment.defaultSegments
    internal val ringSegments: List<RingSegment>
        get() = currentRingSegments

    private val pool = listOf(ChallengeDirection.LEFT, ChallengeDirection.RIGHT, ChallengeDirection.UP, ChallengeDirection.DOWN)
    val challenges: List<ChallengeDirection> = pool.shuffled(Random(randomSeed)).take(3)
    var currentChallengeIndex = 0
        protected set
    private var consecutivePoseFrames = 0
    private var missedFaceFrames = 0
    private var consecutiveMatchFrames = 0

    val sessionTimeoutMs = 25000L
    val prepareForPhotoDurationMs = 2000L

    init {
        updateState(
            messageAbove = "Check Liveness",
            messageBelow = "Position your face and look at the camera",
            status = FaceMatcherLivenessPromptState.Status.IN_PROGRESS
        )
    }

    /**
     * Initializes platform-specific detector and interpreter pipelines.
     */
    protected abstract suspend fun initializePipeline()

    /**
     * Detects faces in [frame].
     */
    protected abstract suspend fun detectFaces(frame: CameraFrame): List<TFace>

    /**
     * Releases platform resources when session is closed or cancelled.
     */
    protected abstract fun onSessionClosed()

    /**
     * Captures a high-resolution upright portrait photo from [frame].
     */
    protected abstract suspend fun captureHighResolutionImage(frame: CameraFrame): ByteString?

    protected open fun createOverlay(
        frame: CameraFrame,
        faces: List<TFace>
    ): OverlayFrame? {
        return renderOverlay(
            frame = frame,
            faces = faces,
            ringSegments = currentRingSegments,
            debug = debug,
            currentSimilarity = null,
            bestSimilarity = 0f,
            matchThreshold = config.matchThreshold
        )
    }

    private val frameMutex = Mutex()

    override suspend fun feedFrame(frame: CameraFrame) {
        if (isCancelled || state.value.status == FaceMatcherLivenessPromptState.Status.SUCCESS ||
            state.value.status == FaceMatcherLivenessPromptState.Status.FAILED) {
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

        if (now - startTime!! > sessionTimeoutMs) {
            failSession(
                messageAbove = "Verification Failed",
                messageBelow = "Liveness check timed out - please try again"
            )
            return
        }

        val faces = detectFaces(frame)

        if (isCancelled) return

        if (faces.isEmpty()) {
            missedFaceFrames++
            consecutiveMatchFrames = 0
            consecutivePoseFrames = 0

            val overlay = createOverlay(frame, emptyList())
            if (missedFaceFrames > 3) {
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
                    Phase.PREPARE_FOR_PHOTO, Phase.CAPTURING -> {
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
            consecutivePoseFrames = 0
            currentRingSegments = RingSegment.defaultSegments
            val overlay = createOverlay(frame, faces)
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

        if (isCancelled) return

        when (phase) {
            Phase.POSITIONING -> {
                val elapsed = now - phaseStartTime
                val pulse = (sin(elapsed / 250.0) * 0.35 + 0.65).toFloat()
                val pulseColor = RingSegment.lerpColor(RingSegment.COLOR_DARK_GRAY, RingSegment.COLOR_BLUE, pulse)

                if (isFacingStraight) {
                    consecutiveMatchFrames++
                    if (consecutiveMatchFrames >= 2) {
                        phase = Phase.LIVENESS_CHALLENGE
                        phaseStartTime = now
                        currentChallengeIndex = 0
                        consecutivePoseFrames = 0
                        consecutiveMatchFrames = 0
                        currentRingSegments = computeDirectionSegments(challenges[0], 0.0f)
                        val overlay = createOverlay(frame, faces)
                        showChallenge(0.0f, overlay)
                    } else {
                        currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                            RingSegment(color = pulseColor, scale = 1.0f)
                        }
                        val overlay = createOverlay(frame, faces)
                        updateState(
                            messageAbove = "Position your face",
                            messageBelow = "Hold still...",
                            overlay = overlay
                        )
                    }
                } else {
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
                    val overlay = createOverlay(frame, faces)
                    updateState(
                        messageAbove = "Position your face",
                        messageBelow = prompt,
                        overlay = overlay
                    )
                }
            }

            Phase.LIVENESS_CHALLENGE -> {
                val direction = challenges[currentChallengeIndex]
                val progress = computeChallengeProgress(direction, yaw, pitch)

                currentRingSegments = computeDirectionSegments(direction, progress)
                val overlay = createOverlay(frame, faces)

                if (progress >= 1.0f) {
                    consecutivePoseFrames++
                    if (consecutivePoseFrames >= 2) {
                        currentChallengeIndex++
                        consecutivePoseFrames = 0
                        if (currentChallengeIndex >= challenges.size) {
                            phase = Phase.PREPARE_FOR_PHOTO
                            phaseStartTime = now
                            consecutiveMatchFrames = 0
                            currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                                RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.15f)
                            }
                            val prepOverlay = createOverlay(frame, faces)
                            updateState(
                                messageAbove = "Hold Still",
                                messageBelow = "Preparing photo...",
                                status = FaceMatcherLivenessPromptState.Status.PREPARE_FOR_PHOTO,
                                overlay = prepOverlay
                            )
                            return
                        } else {
                            val nextDirection = challenges[currentChallengeIndex]
                            currentRingSegments = computeDirectionSegments(nextDirection, 0.0f)
                            val nextOverlay = createOverlay(frame, faces)
                            showChallenge(0.0f, nextOverlay)
                            return
                        }
                    }
                } else {
                    consecutivePoseFrames = 0
                }

                showChallenge(progress, overlay)
            }

            Phase.PREPARE_FOR_PHOTO -> {
                val elapsed = now - phaseStartTime
                currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                    RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.15f)
                }
                val overlay = createOverlay(frame, faces)

                if (elapsed >= prepareForPhotoDurationMs) {
                    phase = Phase.CAPTURING
                    phaseStartTime = now
                    consecutiveMatchFrames = 0
                    updateState(
                        messageAbove = "Hold Still",
                        messageBelow = "Capturing portrait image...",
                        status = FaceMatcherLivenessPromptState.Status.PREPARE_FOR_PHOTO,
                        overlay = overlay
                    )
                } else {
                    updateState(
                        messageAbove = "Hold Still",
                        messageBelow = "Preparing photo...",
                        status = FaceMatcherLivenessPromptState.Status.PREPARE_FOR_PHOTO,
                        overlay = overlay
                    )
                }
            }

            Phase.CAPTURING -> {
                if (isFacingStraight) {
                    consecutiveMatchFrames++
                    if (consecutiveMatchFrames >= 2) {
                        val photoBytes = captureHighResolutionImage(frame)
                        if (photoBytes == null) {
                            failSession("Capture Failed", "Could not capture portrait photo")
                            return
                        }
                        phase = Phase.COMPLETED
                        currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                            RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.2f)
                        }
                        updateState(
                            messageAbove = "Portrait Captured",
                            messageBelow = "Liveness verified",
                            status = FaceMatcherLivenessPromptState.Status.SUCCESS,
                            capturedImage = photoBytes,
                            overlay = null
                        )
                    } else {
                        currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                            RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.15f)
                        }
                        val overlay = createOverlay(frame, faces)
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
                    val overlay = createOverlay(frame, faces)
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

    private fun showChallenge(progress: Float, overlay: OverlayFrame? = null) {
        val direction = challenges[currentChallengeIndex]
        val (promptTitle, promptDetail) = getChallengePromptTexts(direction)
        val stepText = "Step ${currentChallengeIndex + 1} of ${challenges.size}"

        updateState(
            messageAbove = "$promptTitle ($stepText)",
            messageBelow = promptDetail,
            status = FaceMatcherLivenessPromptState.Status.IN_PROGRESS,
            overlay = overlay
        )
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
            status = FaceMatcherLivenessPromptState.Status.FAILED,
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
