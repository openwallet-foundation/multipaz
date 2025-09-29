package org.multipaz.device

import kotlinx.io.bytestring.ByteString

/**
 * Data necessary to validate a [DeviceAttestation] object.
 */
data class DeviceAttestationValidationData(
    /**
     * Value of the `challange` parameter passed to [DeviceCheck.generateAttestation] method.
     */
    val attestationChallenge: ByteString,

    /**
     * Whether a release build is required on iOS. When `false`, both debug and release builds
     * are accepted.
     */
    val iosReleaseBuild: Boolean,

    /**
     * List of allowed iOS app identifiers.
     *
     * iOS app identifier that consists of a team id followed by a dot and app bundle name. If
     * empty, any app identifier is accepted.
     *
     * On iOS this is the primary method of ensuring the the app that generated a given
     * [DeviceAttestation] is legitimate, as team id is tied to your team.
     *
     * It must not be empty if [iosReleaseBuild] is `true`
     */
    val iosAppIdentifiers: Set<String>,

    /**
     * Ensure that the private key in the Android attestation is certified as legitimate using the
     * Google root private key.
     */
    val androidGmsAttestation: Boolean,

    /**
     * Require Android clients to be in verified boot state "green".
     */
    val androidVerifiedBootGreen: Boolean,

    /**
     * Minimally acceptable security level on Android.
     */
    val androidRequiredKeyMintSecurityLevel: AndroidKeystoreSecurityLevel,

    /**
     * Allowed list of Android applications signing certificates.
     *
     * Each element is the bytes of the SHA-256 of a signing certificate, see the
     * [Signature](https://developer.android.com/reference/android/content/pm/Signature) class in
     * the Android SDK for details. If empty, allow apps signed with any signature.
     */
    val androidAppSignatureCertificateDigests: Set<ByteString>,

    /**
     * Allowed list of Android application package names.
     *
     * If empty, allow any app.
     */
    val androidAppPackageNames: Set<String>
) {
    fun withChallenge(challenge: ByteString): DeviceAttestationValidationData {
        return DeviceAttestationValidationData(
            attestationChallenge = challenge,
            iosReleaseBuild = iosReleaseBuild,
            iosAppIdentifiers = iosAppIdentifiers,
            androidRequiredKeyMintSecurityLevel = androidRequiredKeyMintSecurityLevel,
            androidGmsAttestation = androidGmsAttestation,
            androidVerifiedBootGreen = androidVerifiedBootGreen,
            androidAppSignatureCertificateDigests = androidAppSignatureCertificateDigests,
            androidAppPackageNames = androidAppPackageNames
        )
    }
}