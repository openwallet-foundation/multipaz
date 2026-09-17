package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Implementation of [FaceMatcherSession] for [SimulatedFaceMatcher].
 *
 * Holds isolated session state for one verification run.
 */
class SimulatedFaceMatcherSession(
    referencePortrait: ByteString,
    private val searchDurationMs: Long = 2500L,
    private val matchConveyDurationMs: Long = 1500L,
    private val challengeDurationMs: Long = 3000L,
    private val simulatedConfidence: Float = 0.95f,
    private val enableLiveness: Boolean = true,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceMatcherSession(referencePortrait) {

    private enum class Phase {
        INITIAL_SEARCH,
        MATCH_CONVEYED,
        LIVENESS_CHALLENGE,
        COMPLETED
    }

    private var firstFrameTime: Long? = null
    private var phaseStartTime: Long = 0L
    private var phase = Phase.INITIAL_SEARCH
    private var currentChallengeIndex = 0

    // Generate randomized challenges to defeat deepfakes
    private val challenges: List<RingDirection> = if (enableLiveness) {
        val pool = listOf(RingDirection.LEFT, RingDirection.RIGHT, RingDirection.UP, RingDirection.DOWN)
        val shuffled = pool.shuffled(Random(clock())).take(2)
        shuffled + listOf(RingDirection.CENTER)
    } else {
        emptyList()
    }

    init {
        updateState(
            messageAbove = "Verify Identity",
            messageBelow = "Position your face and look at the camera",
            ringSegments = FaceMatcherPromptState.defaultSegments,
            outcome = FaceMatcherPromptState.Outcome.IN_PROGRESS
        )
    }

    override suspend fun feedFrame(frame: CameraFrame) {
        if (state.value.outcome != FaceMatcherPromptState.Outcome.IN_PROGRESS) {
            return
        }

        val now = clock()
        val start = firstFrameTime ?: run {
            firstFrameTime = now
            phaseStartTime = now
            now
        }

        when (phase) {
            Phase.INITIAL_SEARCH -> {
                val elapsed = now - start
                if (elapsed < searchDurationMs) {
                    val searchProgress = (elapsed.toFloat() / searchDurationMs.toFloat()).coerceIn(0f, 1f)
                    val pulse = (kotlin.math.sin(elapsed / 250.0) * 0.35 + 0.65).toFloat()
                    val pulseColor = PromptColor.lerp(PromptColor.DARK_GRAY, PromptColor.BLUE, pulse)
                    updateState(
                        messageAbove = "Verify Identity",
                        messageBelow = "Hold still...",
                        ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                            RingSegment(color = pulseColor, scale = 1.0f)
                        }
                    )
                } else {
                    phase = Phase.MATCH_CONVEYED
                    phaseStartTime = now
                    updateState(
                        messageAbove = "Face Matched",
                        messageBelow = "Keep steady",
                        ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                            RingSegment(color = PromptColor.GREEN, scale = 1.2f)
                        }
                    )
                }
            }

            Phase.MATCH_CONVEYED -> {
                val elapsed = now - phaseStartTime
                if (elapsed >= matchConveyDurationMs) {
                    if (challenges.isNotEmpty()) {
                        phase = Phase.LIVENESS_CHALLENGE
                        phaseStartTime = now
                        currentChallengeIndex = 0
                        showCurrentChallenge(0f)
                    } else {
                        completeSuccess()
                    }
                }
            }

            Phase.LIVENESS_CHALLENGE -> {
                val elapsed = now - phaseStartTime
                val progress = (elapsed.toFloat() / challengeDurationMs.toFloat()).coerceIn(0f, 1f)
                showCurrentChallenge(progress)

                if (elapsed >= challengeDurationMs) {
                    currentChallengeIndex++
                    if (currentChallengeIndex < challenges.size) {
                        phaseStartTime = now
                        showCurrentChallenge(0f)
                    } else {
                        completeSuccess()
                    }
                }
            }

            Phase.COMPLETED -> {}
        }
    }

    private fun showCurrentChallenge(progress: Float) {
        val direction = challenges[currentChallengeIndex]
        val (promptTitle, promptDetail) = when (direction) {
            RingDirection.LEFT -> "Look to your left" to "Turn your head left"
            RingDirection.RIGHT -> "Look to your right" to "Turn your head right"
            RingDirection.UP -> "Look up" to "Tilt your head up"
            RingDirection.DOWN -> "Look down" to "Tilt your head down"
            RingDirection.CENTER -> "Look at the camera" to "Center your face"
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

    private fun completeSuccess() {
        phase = Phase.COMPLETED
        updateState(
            messageAbove = "Identity Verified",
            messageBelow = "Verification successful",
            ringSegments = List(FaceMatcherPromptState.NUM_RING_SEGMENTS) {
                RingSegment(color = PromptColor.GREEN, scale = 1.2f)
            },
            outcome = FaceMatcherPromptState.Outcome.SUCCESS
        )
    }
}
