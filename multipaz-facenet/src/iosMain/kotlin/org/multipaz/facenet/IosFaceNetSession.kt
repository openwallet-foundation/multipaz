package org.multipaz.facenet

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.PromptColor
import org.multipaz.facematch.RingDirection
import org.multipaz.facematch.RingSegment
import org.multipaz.util.Logger
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock

private const val TAG = "IosFaceNetSession"

@OptIn(ExperimentalForeignApi::class)
internal class IosFaceNetSession(
    referencePortrait: ByteString,
    private val modelBytesProvider: (suspend () -> ByteString)?,
    private val config: FaceNetModelConfig,
    val matcherName: String = "facenet",
    val matcherDisplayName: String = "Google FaceNet",
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceMatcherSession(referencePortrait) {

    private enum class Phase {
        INITIALIZING,
        POSITIONING,
        MATCH_CONVEYED,
        LIVENESS_CHALLENGE,
        COMPLETED,
        FAILED
    }

    private var phase = Phase.INITIALIZING
    private var startTime: Long? = null
    private var phaseStartTime: Long = 0L

    private val isCancelled = atomic(false)

    private var detector: IosFaceDetector? = null
    private var interpreter: IosFaceNetInterpreter? = null
    private var referenceEmbedding: FaceEmbedding? = null
    private var bestSimilarity: Float = 0.0f
    private var initializationAttempted = false

    private val pool = listOf(RingDirection.LEFT, RingDirection.RIGHT, RingDirection.UP, RingDirection.DOWN)
    private val challenges: List<RingDirection> = pool.shuffled(Random(clock())).take(2) + listOf(RingDirection.CENTER)
    private var currentChallengeIndex = 0
    private var consecutivePoseFrames = 0
    private var missedFaceFrames = 0
    private var consecutiveMatchFrames = 0
    private var straightFaceStartTime: Long? = null

    private val matchTimeoutMs = 8000L
    private val sessionTimeoutMs = 25000L
    private val matchConveyDurationMs = 1200L

    init {
        updateState(
            messageAbove = "Verify Identity",
            messageBelow = "Position your face and look at the camera",
            ringSegments = FaceMatcherPromptState.defaultSegments,
            outcome = FaceMatcherPromptState.Outcome.IN_PROGRESS
        )
    }

    override suspend fun feedFrame(frame: CameraFrame) {
        if (isCancelled.value || state.value.outcome != FaceMatcherPromptState.Outcome.IN_PROGRESS) {
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
                if (isCancelled.value) return
                Logger.e(TAG, "Pipeline initialization failed", e)
                failSession("Verification Error", e.message ?: "Failed to initialize face matching model")
                return
            }
            if (isCancelled.value) {
                try { detector?.close() } catch (_: Exception) {}
                try { interpreter?.close() } catch (_: Exception) {}
                detector = null
                interpreter = null
                return
            }
            phase = Phase.POSITIONING
            phaseStartTime = now
        }

        if (isCancelled.value) return
        val activeDetector = detector ?: return
        val activeInterpreter = interpreter ?: return
        val refEmb = referenceEmbedding ?: return

        val faces = activeDetector.detectFaces(frame)
        if (isCancelled.value) return

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
                val faceCrop = activeDetector.extractFaceCrop(frame, face, activeInterpreter.imageSquareSize)
                if (faceCrop != null) {
                    if (isCancelled.value) return
                    val cameraEmbedding = activeInterpreter.getEmbedding(faceCrop)
                    if (cameraEmbedding != null) {
                        val similarity = refEmb.calculateSimilarity(cameraEmbedding)
                        currentSimilarity = similarity
                        if (similarity > bestSimilarity) {
                            bestSimilarity = similarity
                            Logger.d(TAG, "New best face similarity: $bestSimilarity (threshold=${config.matchThreshold})")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isCancelled.value) {
                    Logger.w(TAG, "Failed to compute embedding from camera frame", e)
                }
            }
        }

        if (isCancelled.value) return

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

                    val isMatched = (currentSimilarity != null && currentSimilarity >= config.matchThreshold)
                            || bestSimilarity >= config.matchThreshold

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
                            failSession(
                                messageAbove = "Verification Failed",
                                messageBelow = "Face does not match reference portrait ($percentage% match, required ${(config.matchThreshold * 100).toInt()}%)"
                            )
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
                        abs(roll) > 15f -> "Keep your head level"
                        else -> "Look straight at the camera"
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
                if (now - phaseStartTime >= matchConveyDurationMs) {
                    phase = Phase.LIVENESS_CHALLENGE
                    phaseStartTime = now
                    consecutivePoseFrames = 0
                    showChallenge(0.0f)
                } else {
                    updateState(
                        messageAbove = "Face Matched",
                        messageBelow = "Hold still...",
                        ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                            RingSegment(color = PromptColor.GREEN, scale = 1.15f)
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

    private suspend fun initializePipeline() {
        val modelBytes = modelBytesProvider?.invoke()
        val interp = IosFaceNetInterpreter(modelBytes, config)
        val det = IosFaceDetector()

        val refBytes = referencePortrait.toByteArray()
        val refFaces = try {
            det.detectFaces(refBytes)
        } catch (e: Exception) {
            throw e
        }

        if (refFaces.isEmpty()) {
            throw IllegalArgumentException("No face detected in reference portrait")
        }

        val refFaceCrop = det.extractFaceCrop(refBytes, refFaces[0], interp.imageSquareSize)
            ?: throw IllegalStateException("Failed to extract face crop from reference portrait")

        val embedding = interp.getEmbedding(refFaceCrop)
            ?: throw IllegalStateException("Failed to compute embedding from reference portrait")

        interpreter = interp
        detector = det
        referenceEmbedding = embedding
        Logger.d(TAG, "Pipeline initialized successfully with embedding size ${embedding.embedding.size}")
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

    private fun failSession(messageAbove: String, messageBelow: String) {
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
        isCancelled.value = true
        try {
            detector?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing detector", e)
        }
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing interpreter", e)
        }
        detector = null
        interpreter = null
    }
}
