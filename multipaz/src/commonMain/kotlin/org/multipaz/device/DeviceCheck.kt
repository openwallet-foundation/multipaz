package org.multipaz.device

import org.multipaz.securearea.SecureArea
import kotlinx.io.bytestring.ByteString

/**
 * Generates statements validating device/OS/app integrity. Details of these
 * statements are inherently platform-specific.
 */
expect object DeviceCheck {
    /**
     * Generates a device attestation that proves the integrity of the device/OS/wallet app
     * and creates a certain opaque private key that resides securely on the device.
     *
     * The only operation that this opaque private key can be used for is generating
     * assertions using [generateAssertion] method.
     *
     * The party requesting the attestation can verify it using [DeviceAttestation.validate]
     * and can use the object to request further assertions using [generateAssertion].
     *
     * @param secureArea a platform-specific [SecureArea], on Android use [AndroidKeystoreSecureArea]
     *   and on iOS use [SecureEnclaveSecureArea].
     * @param challenge should come from the party requesting the attestation, for freshness.
     * @param secret optional secret for identifying the client, only used in [DeviceAttestationSoftware].
     * @return a [DeviceAttestationResult] containing a [DeviceAttestation] which can be sent to the party
     *   requesting the attestation.
     */
    suspend fun generateAttestation(
        secureArea: SecureArea,
        challenge: ByteString,
        secret: String? = null
    ): DeviceAttestationResult

    /**
     * Generates [DeviceAssertion] - an [Assertion] which is signed using the key generated using
     * by [generateAttestation] method.
     *
     * Note that the exact format for the signature is platform-dependent.
     *
     * Validity of the assertion can be verified using [DeviceAttestation.validateAssertion] using
     * the [DeviceAttestation] object that corresponds to the given [deviceAttestationId].
     *
     * @param secureArea must be the same value as was passed to [generateAttestation] method.
     * @param deviceAttestationId the attestation id from the [DeviceAttestationResult] obtained
     *   from the [generateAttestation] call.
     * @param assertion the assertion of make, e.g. [AssertionNonce].
     * @return a [DeviceAssertion] which contains proof of [assertion] which can be sent to the
     *   party requesting the assertion.
     */
    suspend fun generateAssertion(
        secureArea: SecureArea,
        deviceAttestationId: String,
        assertion: Assertion
    ): DeviceAssertion
}