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
     * Whether this matcher optionally supplies graphics to overlay on top of the matching video stream.
     */
    val providesGraphicsOverlay: Boolean
        get() = false

    /**
     * Whether this matcher supports active liveness detection and portrait photo capture.
     */
    val supportsLiveness: Boolean
        get() = false

    /**
     * Creates a new [FaceMatcherSession] for a verification session against [referencePortrait].
     *
     * @param referencePortrait the reference portrait image bytes to verify against.
     * @return a new [FaceMatcherSession] instance for this verification session.
     */
    fun createSession(referencePortrait: ByteString): FaceMatcherSession

    /**
     * Creates a new [FaceMatcherSession] for active liveness checking and portrait photo capture.
     *
     * @return a new [FaceMatcherSession] instance for this liveness session.
     * @throws UnsupportedOperationException if this matcher does not support liveness detection.
     */
    fun createLivenessSession(): FaceMatcherSession {
        throw UnsupportedOperationException("$displayName does not support liveness detection")
    }
}
