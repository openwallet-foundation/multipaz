package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString

/**
 * Interface for verifying user identity via face matching.
 *
 * The normal way to use [createSession] is through the prompt machinery via
 * [org.multipaz.prompt.showFaceMatcherPrompt].
 *
 * The normal way to use [createLivenessSession] is through the prompt machinery via
 * [org.multipaz.prompt.showFaceMatcherLivenessPrompt].
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
     * Whether this matcher provides its own custom user interface (including operating the camera
     * and drawing graphics itself).
     *
     * If `true`, [FaceMatcherSession] and [FaceMatcherLivenessSession] will not receive any camera
     * frames ([FaceMatcherSession.feedFrame]) or touch events ([FaceMatcherSession.onTouchEvent]),
     * and the only [FaceMatcherSession.updateState] / [FaceMatcherLivenessSession.updateState] call
     * expected is when the session ends and the implementation updates the state to
     * [FaceMatcherPromptState.Status.SUCCESS] or [FaceMatcherPromptState.Status.FAILED] (or
     * [FaceMatcherLivenessPromptState.Status.SUCCESS] or [FaceMatcherLivenessPromptState.Status.FAILED]).
     */
    val usesCustomUserInterface: Boolean
        get() = false

    /**
     * Creates a new [FaceMatcherSession] driving an interactive verification session to verify that the
     * user in front of the camera matches the provided [referencePortrait].
     *
     * The implementation is required to verify both:
     * 1. **Liveness**: Ensuring that the subject in front of the camera is a live, physically present human
     *    being rather than a presentation attack (e.g. a printed photograph, digital replay, or 3D mask).
     * 2. **Matching**: Ensuring that the facial biometric features of the live subject match the provided
     *    [referencePortrait].
     *
     * The implementation is free to achieve this verification however it pleases (for example, using active
     * head-pose challenges, gaze tracking, passive texture/depth analysis, multi-frame biometric scoring, or
     * third-party biometric services).
     *
     * Throughout the verification session, the implementation can feed back real-time information, instructions,
     * status messages, and visual overlays to the user through the use of [FaceMatcherSession.updateState].
     *
     * The normal way to use this function is through the prompt machinery via
     * [org.multipaz.prompt.showFaceMatcherPrompt].
     *
     * The [referencePortrait] parameter must be an encoded image in one of the supported formats:
     * - PNG (Portable Network Graphics)
     * - JPEG (Joint Photographic Experts Group)
     * - JPEG 2000 (JP2 container format or raw J2K codestream)
     *
     * @param referencePortrait the reference portrait image bytes in PNG, JPEG, or JPEG 2000 format to verify against.
     * @return a new [FaceMatcherSession] instance for this verification session.
     * @throws IllegalArgumentException if [referencePortrait] is empty, corrupted, or not in a supported image format.
     */
    fun createSession(referencePortrait: ByteString): FaceMatcherSession

    /**
     * Creates a new [FaceMatcherLivenessSession] driving an interactive session to check user liveness and
     * capture a verified portrait photo of the subject.
     *
     * The normal way to use this function is through the prompt machinery via
     * [org.multipaz.prompt.showFaceMatcherLivenessPrompt].
     *
     * Unlike [createSession], this flow operates without a reference portrait and is typically used during
     * user onboarding, credential issuance, or provisioning flows where a fresh, verified live portrait photo
     * must be captured from the user.
     *
     * The implementation is required to verify that the subject in front of the camera is a live human being
     * (e.g. through active challenge interactions, blink or head-turn detection, or passive presentation attack
     * detection) and capture still portrait photos of the live subject for each requested pose in [poses] in
     * that order. The captured portraits are returned in [FaceMatcherLivenessPromptState.capturedImages] when
     * the session transitions to [FaceMatcherLivenessPromptState.Status.SUCCESS].
     *
     * Like matching sessions, the implementation communicates with the user throughout the interaction by calling
     * [FaceMatcherLivenessSession.updateState] to provide instructions, challenge prompts, and dynamic visual overlays.
     *
     * @param poses ordered list of [FacePose]s to capture during the session. Defaults to `[FacePose.FRONT]`.
     * @return a new [FaceMatcherLivenessSession] instance for this liveness verification and capture session.
     * @throws IllegalArgumentException if [poses] is empty.
     * @throws UnsupportedOperationException if this matcher does not support liveness detection ([supportsLiveness] is `false`).
     */
    @Throws(UnsupportedOperationException::class, IllegalArgumentException::class)
    fun createLivenessSession(
        poses: List<FacePose> = listOf(FacePose.FRONT)
    ): FaceMatcherLivenessSession {
        throw UnsupportedOperationException("$displayName does not support liveness detection")
    }
}
