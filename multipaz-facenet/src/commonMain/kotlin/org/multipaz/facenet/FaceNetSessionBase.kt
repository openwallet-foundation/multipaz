package org.multipaz.facenet

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.PromptColor
import org.multipaz.facematch.RingDirection
import org.multipaz.facematch.RingSegment
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
 * - Visual feedback: pulsing rings during positioning and progressive green fill during challenges.
 */
abstract class FaceNetSessionBase<TFace : DetectedFacePose>(
    referencePortrait: ByteString,
    val config: FaceNetModelConfig,
    val matcherName: String = "facenet",
    val matcherDisplayName: String = "MobileFaceNet",
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    randomSeed: Long = clock()
) : FaceMatcherSession(referencePortrait) {

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

    private val pool = listOf(RingDirection.LEFT, RingDirection.RIGHT, RingDirection.UP, RingDirection.DOWN)
    val challenges: List<RingDirection> = pool.shuffled(Random(randomSeed)).take(2) + listOf(RingDirection.CENTER)
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
            messageAbove = "Verify Identity",
            messageBelow = "Position your face and look at the camera",
            ringSegments = FaceMatcherPromptState.defaultSegments,
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

    override suspend fun feedFrame(frame: CameraFrame) {
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
            failSession("Verification Failed", "Verification timed out")
            return
        }

        if (!initializationAttempted) {
            initializationAttempted = true
            try {
                initializePipeline()
            } catch (e: Exception) {
                if (isCancelled) return
                Logger.e(TAG, "Pipeline initialization failed", e)
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
        val refEmb = referenceEmbedding ?: return

        val faces = detectFaces(frame)
        if (isCancelled) return

        if (faces.isEmpty()) {
            missedFaceFrames++
            consecutiveMatchFrames = 0
            straightFaceStartTime = null
            consecutivePoseFrames = 0
            if (missedFaceFrames >= 3) {
                when (phase) {
                    Phase.POSITIONING -> {
                        updateState(
                            messageAbove = "Position your face",
                            messageBelow = "No face detected",
                            ringSegments = FaceMatcherPromptState.defaultSegments
                        )
                    }
                    Phase.LIVENESS_CHALLENGE -> {
                        updateState(
                            messageBelow = "Face lost, looking for face..."
                        )
                    }
                    else -> {}
                }
            }
            return
        }

        if (faces.size > 1) {
            missedFaceFrames = 0
            consecutiveMatchFrames = 0
            straightFaceStartTime = null
            consecutivePoseFrames = 0
            updateState(
                messageAbove = "Multiple faces detected",
                messageBelow = "Ensure only one person is in the frame",
                ringSegments = FaceMatcherPromptState.defaultSegments
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

        // Whenever the face is facing straight, compute and evaluate embedding
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
                val pulseColor = PromptColor.lerp(PromptColor.DARK_GRAY, PromptColor.BLUE, pulse)

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
                            updateState(
                                messageAbove = "Face Matched",
                                messageBelow = "Hold still...",
                                ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                                    RingSegment(color = PromptColor.GREEN, scale = 1.15f)
                                }
                            )
                        }
                    } else {
                        consecutiveMatchFrames = 0
                        if (now - straightStart > matchTimeoutMs) {
                            val percentage = (bestSimilarity * 100).toInt().coerceAtLeast(0)
                            if (bestSimilarity >= config.matchThreshold) {
                                failSession(
                                    messageAbove = "Verification Failed",
                                    messageBelow = "Unable to confirm match - please hold still and look directly at the camera"
                                )
                            } else {
                                failSession(
                                    messageAbove = "Verification Failed",
                                    messageBelow = "Face does not match reference portrait ($percentage% match, required ${(config.matchThreshold * 100).toInt()}%)"
                                )
                            }
                            return
                        }
                        updateState(
                            messageAbove = "Verifying Identity",
                            messageBelow = "Hold still and look directly at the camera...",
                            ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                                RingSegment(color = pulseColor, scale = 1.0f)
                            }
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
                    updateState(
                        messageAbove = "Position your face",
                        messageBelow = prompt,
                        ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                            RingSegment(color = pulseColor, scale = 1.0f)
                        }
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
                    showChallenge(0.0f)
                } else {
                    val scale = 1.15f - (elapsed.toFloat() / matchConveyDurationMs) * 0.15f
                    updateState(
                        ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                            RingSegment(color = PromptColor.GREEN, scale = scale)
                        }
                    )
                }
            }

            Phase.LIVENESS_CHALLENGE -> {
                val challenge = challenges[currentChallengeIndex]
                val progress = computeChallengeProgress(challenge, yaw, pitch)
                showChallenge(progress)

                if (progress >= 0.85f) {
                    consecutivePoseFrames++
                    if (consecutivePoseFrames >= 3) {
                        consecutivePoseFrames = 0
                        currentChallengeIndex++
                        if (currentChallengeIndex >= challenges.size) {
                            finalizeVerification()
                        } else {
                            phaseStartTime = now
                            showChallenge(0.0f)
                        }
                    }
                } else {
                    consecutivePoseFrames = 0
                }
            }

            Phase.COMPLETED, Phase.FAILED, Phase.INITIALIZING -> {}
        }
    }

    private fun computeChallengeProgress(direction: RingDirection, yaw: Float, pitch: Float): Float {
        val yawThreshold = 18.0f
        val pitchThreshold = 14.0f
        return when (direction) {
            RingDirection.LEFT -> (yaw / yawThreshold).coerceIn(0f, 1f)
            RingDirection.RIGHT -> (-yaw / yawThreshold).coerceIn(0f, 1f)
            RingDirection.UP -> (pitch / pitchThreshold).coerceIn(0f, 1f)
            RingDirection.DOWN -> (-pitch / pitchThreshold).coerceIn(0f, 1f)
            RingDirection.CENTER -> {
                val deviation = maxOf(abs(yaw), abs(pitch))
                if (deviation <= 8.0f) 1.0f else (1.0f - ((deviation - 8.0f) / 10.0f)).coerceIn(0f, 1f)
            }
        }
    }

    private fun showChallenge(progress: Float) {
        val direction = challenges[currentChallengeIndex]
        val (promptTitle, promptDetail) = when (direction) {
            RingDirection.LEFT -> "Look to your left" to "Turn your head left"
            RingDirection.RIGHT -> "Look to your right" to "Turn your head right"
            RingDirection.UP -> "Look up" to "Tilt your head up"
            RingDirection.DOWN -> "Look down" to "Tilt your head down"
            RingDirection.CENTER -> "Look at the camera" to "Look straight ahead"
        }

        val stepText = "Step ${currentChallengeIndex + 1} of ${challenges.size}"
        val segments = computeDirectionSegments(
            direction = direction,
            progress = progress,
            activeColor = PromptColor.BRIGHT_GREEN,
            baseColor = PromptColor.DARK_GRAY
        )

        updateState(
            messageAbove = "$promptTitle ($stepText)",
            messageBelow = promptDetail,
            ringSegments = segments
        )
    }

    private fun finalizeVerification() {
        if (bestSimilarity >= config.matchThreshold) {
            phase = Phase.COMPLETED
            updateState(
                messageAbove = "Identity Verified",
                messageBelow = "Verification successful",
                ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                    RingSegment(color = PromptColor.GREEN, scale = 1.2f)
                },
                outcome = FaceMatcherPromptState.Outcome.SUCCESS
            )
        } else {
            val percentage = (bestSimilarity * 100).toInt().coerceAtLeast(0)
            failSession(
                messageAbove = "Verification Failed",
                messageBelow = "Face does not match reference portrait ($percentage% match, required ${(config.matchThreshold * 100).toInt()}%)"
            )
        }
    }

    protected fun failSession(messageAbove: String, messageBelow: String) {
        phase = Phase.FAILED
        updateState(
            messageAbove = messageAbove,
            messageBelow = messageBelow,
            ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                RingSegment(color = PromptColor.RED, scale = 1.0f)
            },
            outcome = FaceMatcherPromptState.Outcome.FAILED
        )
    }

    override fun cancel() {
        isCancelled = true
        onSessionClosed()
    }
}
