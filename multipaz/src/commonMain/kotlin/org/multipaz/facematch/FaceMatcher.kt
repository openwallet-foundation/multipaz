package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * Interface for verifying user identity via face matching.
 *
 * Implementations are registered in [FaceMatcherRepository] and instantiate a
 * fresh [FaceMatcherSession] for each verification attempt.
 *
 * The `multipaz-facenet` library provides an implementation based on Google FaceNet
 * and the MobileFaceNet model which can be used for development and testing.
 */
interface FaceMatcher {
    /** Unique machine identifier for this matcher (e.g. "simulated", "facenet"). */
    val name: String

    /** Human-readable display name (e.g. "Simulated", "Google FaceNet"). */
    val displayName: String
        get() = name

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
     * @throws IllegalArgumentException if [referencePortrait] is invalid or in an unsupported format.
     */
    fun createSession(referencePortrait: ByteString): FaceMatcherSession

    /**
     * Creates a new [FaceMatcherLivenessSession] for active liveness checking and portrait photo capture.
     *
     * @return a new [FaceMatcherLivenessSession] instance for this liveness session.
     * @throws UnsupportedOperationException if this matcher does not support liveness detection.
     */
    @Throws(UnsupportedOperationException::class)
    fun createLivenessSession(): FaceMatcherLivenessSession {
        throw UnsupportedOperationException("$displayName does not support liveness detection")
    }
}
