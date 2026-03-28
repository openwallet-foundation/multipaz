package org.multipaz.device

import kotlinx.io.bytestring.ByteString
import org.multipaz.securearea.SecureArea

actual object DeviceCheck {
    actual suspend fun generateAttestation(
        secureArea: SecureArea,
        challenge: ByteString,
        secret: String?
    ): DeviceAttestationResult {
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