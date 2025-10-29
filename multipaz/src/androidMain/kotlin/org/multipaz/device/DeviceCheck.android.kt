package org.multipaz.device

import org.multipaz.securearea.AndroidKeystoreCreateKeySettings
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.SecureArea
import kotlinx.io.bytestring.ByteString

/**
 * Generates statements validating device/app/OS integrity. Details of these
 * statements are inherently platform-specific.
 */
actual object DeviceCheck {
    actual suspend fun generateAttestation(
        secureArea: SecureArea,
        challenge: ByteString
    ): DeviceAttestationResult {
        val keySettings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .build()
        val keyInfo = secureArea.createKey(null, keySettings)
        return DeviceAttestationResult(
            deviceAttestationId = keyInfo.alias,
            deviceAttestation = DeviceAttestationAndroid(keyInfo.attestation.certChain!!)
        )
    }

    actual suspend fun generateAssertion(
        secureArea: SecureArea,
        deviceAttestationId: String,
        assertion: Assertion
    ): DeviceAssertion {
        val assertionData = assertion.toCbor()
        val signature = secureArea.sign(
            alias = deviceAttestationId,
            dataToSign = assertionData
        )
        return DeviceAssertion(
            assertionData = ByteString(assertionData),
            platformAssertion = ByteString(signature.toCoseEncoded())
        )
    }
}