package org.multipaz.device

import kotlinx.io.bytestring.ByteString
import org.multipaz.securearea.SecureArea

actual object DeviceCheck {
    actual suspend fun generateAttestation(
        secureArea: SecureArea,
        challenge: ByteString
    ): DeviceAttestationResult {
        TODO("generateAttestation not yet implemented")
    }

    actual suspend fun generateAssertion(
        secureArea: SecureArea,
        deviceAttestationId: String,
        assertion: Assertion
    ): DeviceAssertion {
        TODO("generateAssertion not yet implemented")
    }
}