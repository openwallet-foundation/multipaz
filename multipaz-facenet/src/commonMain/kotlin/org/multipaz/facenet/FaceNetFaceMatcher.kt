package org.multipaz.facenet

import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.SimulatedFaceMatcher

/**
 * A face matcher implementation based on Google's FaceNet.
 *
 * Initially delegates to [SimulatedFaceMatcher] while the build system and harness
 * are established, before plugging in the full on-device inference pipeline.
 */
class FaceNetFaceMatcher(
    override val name: String = "facenet",
    override val displayName: String = "Google FaceNet",
    private val delegate: FaceMatcher = SimulatedFaceMatcher(name = name, displayName = displayName)
) : FaceMatcher {
    constructor() : this("facenet", "Google FaceNet")

    override fun createSession(referencePortrait: ByteString): FaceMatcherSession {
        return delegate.createSession(referencePortrait)
    }
}
