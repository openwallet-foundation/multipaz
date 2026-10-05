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
    referencePortrait: ByteString? = null,
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
        CAPTURING,
        COMPLETED
    }

    enum class ChallengeDirection {
        LEFT,
        RIGHT,
        UP,
        DOWN,
        CENTER
    }

    private var firstFrameTime: Long? = null
    private var phaseStartTime: Long = 0L
    private var phase = Phase.INITIAL_SEARCH
    private var currentChallengeIndex = 0

    // Generate randomized challenges to defeat deepfakes
    private val challenges: List<ChallengeDirection> = if (enableLiveness) {
        val pool = listOf(ChallengeDirection.LEFT, ChallengeDirection.RIGHT, ChallengeDirection.UP, ChallengeDirection.DOWN)
        if (referencePortrait == null) {
            pool.shuffled(Random(clock())).take(3)
        } else {
            val shuffled = pool.shuffled(Random(clock())).take(2)
            shuffled + listOf(ChallengeDirection.CENTER)
        }
    } else {
        emptyList()
    }

    init {
        updateState(
            messageAbove = if (referencePortrait == null) "Check Liveness" else "Verify Identity",
            messageBelow = "Position your face and look at the camera",
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
                    updateState(
                        messageAbove = if (referencePortrait == null) "Check Liveness" else "Verify Identity",
                        messageBelow = "Hold still..."
                    )
                } else if (referencePortrait == null) {
                    if (challenges.isNotEmpty()) {
                        phase = Phase.LIVENESS_CHALLENGE
                        phaseStartTime = now
                        currentChallengeIndex = 0
                        showCurrentChallenge()
                    } else {
                        phase = Phase.CAPTURING
                        phaseStartTime = now
                        updateState(
                            messageAbove = "Hold Still",
                            messageBelow = "Capturing portrait image..."
                        )
                    }
                } else {
                    phase = Phase.MATCH_CONVEYED
                    phaseStartTime = now
                    updateState(
                        messageAbove = "Face Matched",
                        messageBelow = "Keep steady"
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
                        showCurrentChallenge()
                    } else {
                        completeSuccess()
                    }
                }
            }

            Phase.LIVENESS_CHALLENGE -> {
                val elapsed = now - phaseStartTime
                showCurrentChallenge()

                if (elapsed >= challengeDurationMs) {
                    currentChallengeIndex++
                    if (currentChallengeIndex < challenges.size) {
                        phaseStartTime = now
                        showCurrentChallenge()
                    } else if (referencePortrait == null) {
                        phase = Phase.CAPTURING
                        phaseStartTime = now
                        updateState(
                            messageAbove = "Hold Still",
                            messageBelow = "Capturing portrait image..."
                        )
                    } else {
                        completeSuccess()
                    }
                }
            }

            Phase.CAPTURING -> {
                val elapsed = now - phaseStartTime
                if (elapsed >= 1000L) {
                    val dummyImage = ByteString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                    completeSuccess(capturedImage = dummyImage)
                }
            }

            Phase.COMPLETED -> {}
        }
    }

    private fun showCurrentChallenge() {
        val direction = challenges[currentChallengeIndex]
        val (promptTitle, promptDetail) = when (direction) {
            ChallengeDirection.LEFT -> "Look to your left" to "Turn your head left"
            ChallengeDirection.RIGHT -> "Look to your right" to "Turn your head right"
            ChallengeDirection.UP -> "Look up" to "Tilt your head up"
            ChallengeDirection.DOWN -> "Look down" to "Tilt your head down"
            ChallengeDirection.CENTER -> "Look at the camera" to "Center your face"
        }

        val stepText = "Step ${currentChallengeIndex + 1} of ${challenges.size}"
        updateState(
            messageAbove = "$promptTitle ($stepText)",
            messageBelow = promptDetail
        )
    }

    private fun completeSuccess(capturedImage: ByteString? = null) {
        phase = Phase.COMPLETED
        updateState(
            messageAbove = if (referencePortrait == null) "Portrait Captured" else "Identity Verified",
            messageBelow = if (referencePortrait == null) "Liveness verified" else "Verification successful",
            outcome = FaceMatcherPromptState.Outcome.SUCCESS,
            capturedImage = capturedImage
        )
    }
}
