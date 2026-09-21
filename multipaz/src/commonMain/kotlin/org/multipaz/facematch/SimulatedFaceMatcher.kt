package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString
import kotlin.time.Clock

/**
 * A simulated face matcher that demonstrates dynamic UI driving, 18-block segmented ring
 * illumination, and multi-step anti-spoofing / liveness challenges.
 *
 * @param searchDurationMs time in milliseconds to simulate initial face search/lock.
 * @param matchConveyDurationMs time in milliseconds to display confirmed face match before liveness challenges.
 * @param challengeDurationMs time in milliseconds allocated for each liveness pose challenge.
 * @param simulatedConfidence confidence score reported on success.
 * @param enableLiveness whether to simulate randomized directional liveness challenges.
 * @param clock provider for current timestamp in milliseconds.
 */
class SimulatedFaceMatcher(
    override val name: String = "simulated",
    override val displayName: String = "Simulated",
    private val searchDurationMs: Long = 2500L,
    private val matchConveyDurationMs: Long = 1500L,
    private val challengeDurationMs: Long = 3000L,
    private val simulatedConfidence: Float = 0.95f,
    private val enableLiveness: Boolean = true,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : FaceMatcher {
    constructor() : this("simulated", "Simulated", 2500L, 1500L, 3000L, 0.95f, true)
    constructor(searchDurationMs: Long) : this("simulated", "Simulated", searchDurationMs, 1500L, 3000L, 0.95f, true)
    constructor(searchDurationMs: Long, simulatedConfidence: Float) : this(
        "simulated",
        "Simulated",
        searchDurationMs,
        1500L,
        3000L,
        simulatedConfidence,
        true,
        { Clock.System.now().toEpochMilliseconds() }
    )

    override val supportsLiveness: Boolean
        get() = true

    override fun createSession(referencePortrait: ByteString): FaceMatcherSession {
        return SimulatedFaceMatcherSession(
            referencePortrait = referencePortrait,
            searchDurationMs = searchDurationMs,
            matchConveyDurationMs = matchConveyDurationMs,
            challengeDurationMs = challengeDurationMs,
            simulatedConfidence = simulatedConfidence,
            enableLiveness = enableLiveness,
            clock = clock
        )
    }

    override fun createLivenessSession(): FaceMatcherSession {
        return SimulatedFaceMatcherSession(
            referencePortrait = null,
            searchDurationMs = searchDurationMs,
            matchConveyDurationMs = matchConveyDurationMs,
            challengeDurationMs = challengeDurationMs,
            simulatedConfidence = simulatedConfidence,
            enableLiveness = enableLiveness,
            clock = clock
        )
    }
}
