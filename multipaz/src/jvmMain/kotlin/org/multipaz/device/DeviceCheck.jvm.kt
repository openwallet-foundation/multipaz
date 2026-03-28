package org.multipaz.device

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.securearea.SecureArea
import org.multipaz.util.Logger

/**
 * Generates statements validating device/app/OS integrity. Details of these
 * statements are inherently platform-specific.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object DeviceCheck {
    private const val TAG = "DeviceCheck"

    actual suspend fun generateAttestation(
        secureArea: SecureArea,
        challenge: ByteString,
        secret: String?
    ): DeviceAttestationResult {
        if (challenge.isEmpty()) {
            Logger.w(TAG, "Generating an attestation with an empty challenge is not secure")
        }
        return DeviceAttestationSoftware.generateAttestation(
            secureArea = secureArea,
            challenge = challenge,
            secret = secret
        )
    }

    actual suspend fun generateAssertion(
        secureArea: SecureArea,
        deviceAttestationId: String,
        assertion: Assertion
    ): DeviceAssertion {
        return DeviceAttestationSoftware.generateAssertion(
            secureArea = secureArea,
            deviceAttestationId = deviceAttestationId,
            assertion = assertion
        )
    }
}