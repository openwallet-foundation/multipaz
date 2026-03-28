package org.multipaz.device

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.securearea.AndroidKeystoreCreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.util.Logger

/**
 * Generates statements validating device/app/OS integrity. Details of these
 * statements are inherently platform-specific.
 */
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
        val keySettings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .build()
        val keyInfo = secureArea.createKey(null, keySettings)
        return DeviceAttestationResult(
            deviceAttestationId = keyInfo.alias,
            deviceAttestation = DeviceAttestationAndroid(
                certificateChain = keyInfo.attestation.certChain!!,
            )
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