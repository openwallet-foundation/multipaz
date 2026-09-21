package org.multipaz.facenet

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherGraphic
import org.multipaz.facematch.FaceMatcherGraphics
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
    val debug: Boolean = false,
    val matcherName: String = "facenet",
    val matcherDisplayName: String = "MobileFaceNet",
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    randomSeed: Long = clock()
) : FaceMatcherSession(referencePortrait) {

    override val providesGraphicsOverlay: Boolean
        get() = debug


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
                            ringSegments = FaceMatcherPromptState.defaultSegments,
                            graphicsOverlay = null
                        )
                    }
                    Phase.LIVENESS_CHALLENGE -> {
                        updateState(
                            messageBelow = "Face lost, looking for face...",
                            graphicsOverlay = null
                        )
                    }
                    else -> {}
                }
            } else if (debug) {
                updateState(graphicsOverlay = null)
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
                ringSegments = FaceMatcherPromptState.defaultSegments,
                graphicsOverlay = null
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

        val debugGraphics = if (debug) buildDebugGraphics(frame, faces, currentSimilarity) else null

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
                            val percent = ((currentSimilarity ?: bestSimilarity) * 100).toInt()
                            val matchedMsg = if (debug) "Face Matched ($percent%)" else "Face Matched"
                            updateState(
                                messageAbove = matchedMsg,
                                messageBelow = "Hold still...",
                                ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                                    RingSegment(color = PromptColor.GREEN, scale = 1.15f)
                                },
                                graphicsOverlay = debugGraphics
                            )
                        } else {
                            val percent = ((currentSimilarity ?: bestSimilarity) * 100).toInt()
                            val verifyingMsg = if (debug) "Verifying Identity ($percent%)" else "Verifying Identity"
                            updateState(
                                messageAbove = verifyingMsg,
                                messageBelow = "Hold still...",
                                ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                                    RingSegment(color = pulseColor, scale = 1.0f)
                                },
                                graphicsOverlay = debugGraphics
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
                        val verifyingMsg = if (debug && currentSimilarity != null) {
                            val percent = (currentSimilarity * 100).toInt()
                            "Verifying Identity ($percent%)"
                        } else {
                            "Verifying Identity"
                        }
                        updateState(
                            messageAbove = verifyingMsg,
                            messageBelow = "Hold still and look directly at the camera...",
                            ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                                RingSegment(color = pulseColor, scale = 1.0f)
                            },
                            graphicsOverlay = debugGraphics
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
                        },
                        graphicsOverlay = debugGraphics
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
                    showChallenge(0.0f, debugGraphics)
                } else {
                    val scale = 1.15f - (elapsed.toFloat() / matchConveyDurationMs) * 0.15f
                    updateState(
                        ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                            RingSegment(color = PromptColor.GREEN, scale = scale)
                        },
                        graphicsOverlay = debugGraphics
                    )
                }
            }

            Phase.LIVENESS_CHALLENGE -> {
                val challenge = challenges[currentChallengeIndex]
                val progress = computeChallengeProgress(challenge, yaw, pitch)
                showChallenge(progress, debugGraphics)

                if (progress >= 0.85f) {
                    consecutivePoseFrames++
                    if (consecutivePoseFrames >= 3) {
                        consecutivePoseFrames = 0
                        currentChallengeIndex++
                        if (currentChallengeIndex >= challenges.size) {
                            finalizeVerification()
                        } else {
                            phaseStartTime = now
                            showChallenge(0.0f, debugGraphics)
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

    private fun showChallenge(progress: Float, debugGraphics: FaceMatcherGraphics? = null) {
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
            ringSegments = segments,
            graphicsOverlay = debugGraphics
        )
    }

    /**
     * Builds vector graphics for detected faces to overlay on the camera video stream when in debug mode.
     */
    protected open fun buildDebugGraphics(
        frame: CameraFrame,
        faces: List<TFace>,
        currentSimilarity: Float?
    ): FaceMatcherGraphics? {
        if (!debug || faces.isEmpty()) return null
        val face = faces[0] as? BlazeFaceDetection ?: return null

        val items = mutableListOf<FaceMatcherGraphic>()

        // 1. Face bounding box
        items.add(
            FaceMatcherGraphic.Rect(
                left = face.boundingBox.left.toFloat(),
                top = face.boundingBox.top.toFloat(),
                right = face.boundingBox.right.toFloat(),
                bottom = face.boundingBox.bottom.toFloat(),
                color = PromptColor.BRIGHT_GREEN,
                strokeWidth = 2.0f
            )
        )

        val rightEye = face.rightEye
        val leftEye = face.leftEye
        val nose = face.noseTip
        val mouth = face.mouthCenter
        val rightEar = face.rightEarTragus
        val leftEar = face.leftEarTragus

        // 2. Facial wireframe / alignment lines
        // Eye-to-eye axis line
        items.add(
            FaceMatcherGraphic.Line(
                startX = rightEye.x.toFloat(),
                startY = rightEye.y.toFloat(),
                endX = leftEye.x.toFloat(),
                endY = leftEye.y.toFloat(),
                color = PromptColor.BRIGHT_GREEN,
                strokeWidth = 1.5f
            )
        )

        // Eye midpoint to nose
        val eyeMidX = (rightEye.x + leftEye.x).toFloat() / 2f
        val eyeMidY = (rightEye.y + leftEye.y).toFloat() / 2f
        items.add(
            FaceMatcherGraphic.Line(
                startX = eyeMidX,
                startY = eyeMidY,
                endX = nose.x.toFloat(),
                endY = nose.y.toFloat(),
                color = PromptColor.BRIGHT_GREEN,
                strokeWidth = 1.5f
            )
        )

        // Nose to mouth
        items.add(
            FaceMatcherGraphic.Line(
                startX = nose.x.toFloat(),
                startY = nose.y.toFloat(),
                endX = mouth.x.toFloat(),
                endY = mouth.y.toFloat(),
                color = PromptColor.BRIGHT_GREEN,
                strokeWidth = 1.5f
            )
        )

        // Eye to nose triangles (translucent)
        val meshColor = PromptColor(0x8069F0AEL)
        items.add(
            FaceMatcherGraphic.Line(
                startX = rightEye.x.toFloat(),
                startY = rightEye.y.toFloat(),
                endX = nose.x.toFloat(),
                endY = nose.y.toFloat(),
                color = meshColor,
                strokeWidth = 1.0f
            )
        )
        items.add(
            FaceMatcherGraphic.Line(
                startX = leftEye.x.toFloat(),
                startY = leftEye.y.toFloat(),
                endX = nose.x.toFloat(),
                endY = nose.y.toFloat(),
                color = meshColor,
                strokeWidth = 1.0f
            )
        )

        // Mouth to ears (translucent blue)
        val earColor = PromptColor(0x802979FFL)
        items.add(
            FaceMatcherGraphic.Line(
                startX = mouth.x.toFloat(),
                startY = mouth.y.toFloat(),
                endX = rightEar.x.toFloat(),
                endY = rightEar.y.toFloat(),
                color = earColor,
                strokeWidth = 1.0f
            )
        )
        items.add(
            FaceMatcherGraphic.Line(
                startX = mouth.x.toFloat(),
                startY = mouth.y.toFloat(),
                endX = leftEar.x.toFloat(),
                endY = leftEar.y.toFloat(),
                color = earColor,
                strokeWidth = 1.0f
            )
        )

        // 3. Keypoints (points/circles)
        items.add(FaceMatcherGraphic.Point(rightEye.x.toFloat(), rightEye.y.toFloat(), PromptColor.BRIGHT_GREEN, radius = 4f))
        items.add(FaceMatcherGraphic.Point(leftEye.x.toFloat(), leftEye.y.toFloat(), PromptColor.BRIGHT_GREEN, radius = 4f))
        items.add(FaceMatcherGraphic.Point(nose.x.toFloat(), nose.y.toFloat(), PromptColor.RED, radius = 4f))
        items.add(FaceMatcherGraphic.Point(mouth.x.toFloat(), mouth.y.toFloat(), PromptColor(0xFFFFD600L), radius = 4f))
        items.add(FaceMatcherGraphic.Point(rightEar.x.toFloat(), rightEar.y.toFloat(), PromptColor.BLUE, radius = 3.5f))
        items.add(FaceMatcherGraphic.Point(leftEar.x.toFloat(), leftEar.y.toFloat(), PromptColor.BLUE, radius = 3.5f))

        // 4. 3D Head pose direction vector (projected from nose tip)
        val eyeDist = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y).toFloat()
        if (eyeDist > 1f) {
            val rayLength = eyeDist * 0.9f
            val radYaw = face.yaw * (PI / 180.0)
            val radPitch = face.pitch * (PI / 180.0)
            val rayDx = (sin(radYaw) * rayLength).toFloat()
            val rayDy = (sin(radPitch) * rayLength).toFloat()
            val rayEndX = nose.x.toFloat() + rayDx
            val rayEndY = nose.y.toFloat() + rayDy

            items.add(
                FaceMatcherGraphic.Line(
                    startX = nose.x.toFloat(),
                    startY = nose.y.toFloat(),
                    endX = rayEndX,
                    endY = rayEndY,
                    color = PromptColor(0xFFFF5252L),
                    strokeWidth = 3.0f
                )
            )
            items.add(
                FaceMatcherGraphic.Point(
                    x = rayEndX,
                    y = rayEndY,
                    color = PromptColor(0xFFFF5252L),
                    radius = 4.5f
                )
            )
        }

        // 5. Match percentage label above face bounding box
        val similarityToDisplay = currentSimilarity ?: if (bestSimilarity > 0f) bestSimilarity else null
        if (similarityToDisplay != null) {
            val percentage = (similarityToDisplay * 100).toInt().coerceIn(0, 100)
            val isMatch = similarityToDisplay >= config.matchThreshold
            val labelColor = if (isMatch) {
                PromptColor.BRIGHT_GREEN
            } else if (similarityToDisplay >= 0.4f) {
                PromptColor(0xFFFFD600L) // Amber/Yellow
            } else {
                PromptColor(0xFFFF5252L) // Red
            }
            val centerX = face.boundingBox.centerX.toFloat()
            val textY = if (face.boundingBox.top >= 24.0) {
                (face.boundingBox.top - 14.0).toFloat()
            } else {
                (face.boundingBox.top + 18.0).toFloat()
            }
            items.add(
                FaceMatcherGraphic.Text(
                    text = "$percentage%",
                    x = centerX,
                    y = textY,
                    color = labelColor,
                    fontSize = 16.0f
                )
            )
        }

        val frameWidth = if (face.imageWidth > 0) face.imageWidth else frame.uprightWidth
        val frameHeight = if (face.imageHeight > 0) face.imageHeight else frame.uprightHeight

        return FaceMatcherGraphics(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            isMirrored = false,
            items = items
        )
    }

    private fun finalizeVerification() {
        if (bestSimilarity >= config.matchThreshold) {
            phase = Phase.COMPLETED
            val percent = (bestSimilarity * 100).toInt()
            val verifiedMsg = if (debug) "Identity Verified ($percent%)" else "Identity Verified"
            updateState(
                messageAbove = verifiedMsg,
                messageBelow = "Verification successful",
                ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                    RingSegment(color = PromptColor.GREEN, scale = 1.2f)
                },
                outcome = FaceMatcherPromptState.Outcome.SUCCESS,
                graphicsOverlay = null
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
            outcome = FaceMatcherPromptState.Outcome.FAILED,
            graphicsOverlay = null
        )
    }


    override fun cancel() {
        isCancelled = true
        onSessionClosed()
    }
}
