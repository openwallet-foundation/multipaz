package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * Interface for verifying user identity via face matching.
 *
 * Implementations are registered in [FaceMatcherRepository] and instantiate a
 * fresh [FaceMatcherSession] for each verification attempt.
 */
interface FaceMatcher {
    /** Unique machine identifier for this matcher (e.g. "simulated", "facenet"). */
    val name: String

    /** Human-readable display name (e.g. "Simulated", "Google FaceNet"). */
    val displayName: String
        get() = name

    /**
     * Creates a new [FaceMatcherSession] for a verification session against [referencePortrait].
     *
     * @param referencePortrait the reference portrait image bytes to verify against.
     * @return a new [FaceMatcherSession] instance for this verification session.
     */
    fun createSession(referencePortrait: ByteString): FaceMatcherSession
}
