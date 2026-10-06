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
 * - Preparation for photo capture: 2-second hold-still phase before capturing photo.
 * - High-resolution portrait photo capture upon completing the hold-still phase.
 * - Dynamic visual feedback via [OverlayFrame] and segmented challenge ring.
 */
abstract class FaceNetLivenessSessionBase<TFace : DetectedFacePose>(
    val config: FaceNetModelConfig,
    val debug: Boolean = false,
    val matcherName: String = "facenet",
    val matcherDisplayName: String = "MobileFaceNet",
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    randomSeed: Long = clock(),
    val enablePoseSmoothing: Boolean = true
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
    private val headPoseFilter = HeadPoseFilter()

    val sessionTimeoutMs = 25000L
    val prepareForPhotoDurationMs = 2000L

    init {
        updateState(
            message = "Position your face and look at the camera",
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
                        message = "Position your face and look at the camera"
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to initialize pipeline", e)
                    failSession(e.message ?: "Could not start camera pipeline")
                    return
                }
            } else {
                return
            }
        }

        if (now - startTime!! > sessionTimeoutMs) {
            failSession("Liveness check timed out - please try again")
            return
        }

        val detectedFaces = detectFaces(frame)

        if (isCancelled) return

        if (detectedFaces.isEmpty()) {
            missedFaceFrames++
            consecutiveMatchFrames = 0
            consecutivePoseFrames = 0

            val overlay = createOverlay(frame, emptyList())
            if (missedFaceFrames >= 3) {
                headPoseFilter.reset()
                when (phase) {
                    Phase.POSITIONING -> {
                        updateState(
                            message = "No face detected",
                            overlay = overlay
                        )
                    }
                    Phase.LIVENESS_CHALLENGE -> {
                        updateState(
                            message = "Looking for face...",
                            overlay = overlay
                        )
                    }
                    Phase.PREPARE_FOR_PHOTO, Phase.CAPTURING -> {
                        updateState(
                            message = "Looking for face...",
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
            consecutivePoseFrames = 0
            headPoseFilter.reset()
            currentRingSegments = RingSegment.defaultSegments
            val overlay = createOverlay(frame, detectedFaces)
            updateState(
                message = "Ensure only one person is in the frame",
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
                            message = "Hold still...",
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
                        message = prompt,
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
                                message = "Hold still...",
                                status = FaceMatcherLivenessPromptState.Status.IN_PROGRESS,
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
                        message = "Hold still...",
                        status = FaceMatcherLivenessPromptState.Status.IN_PROGRESS,
                        overlay = overlay
                    )
                } else {
                    updateState(
                        message = "Hold still...",
                        status = FaceMatcherLivenessPromptState.Status.IN_PROGRESS,
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
                            failSession("Could not capture portrait photo")
                            return
                        }
                        phase = Phase.COMPLETED
                        headPoseFilter.reset()
                        currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                            RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.2f)
                        }
                        val successOverlay = createOverlay(frame, faces)
                        updateState(
                            message = "Portrait captured",
                            status = FaceMatcherLivenessPromptState.Status.SUCCESS,
                            capturedImage = photoBytes,
                            overlay = successOverlay
                        )
                    } else {
                        currentRingSegments = List(RingSegment.NUM_SEGMENTS) {
                            RingSegment(color = RingSegment.COLOR_GREEN, scale = 1.15f)
                        }
                        val overlay = createOverlay(frame, faces)
                        updateState(
                            message = "Hold still...",
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
                        message = "Look directly at the camera...",
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
            message = "$promptDetail ($stepText)",
            status = FaceMatcherLivenessPromptState.Status.IN_PROGRESS,
            overlay = overlay
        )
    }

    protected fun failSession(
        message: String,
        overlay: OverlayFrame? = null
    ) {
        phase = Phase.FAILED
        headPoseFilter.reset()
        updateState(
            message = message,
            status = FaceMatcherLivenessPromptState.Status.FAILED,
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
