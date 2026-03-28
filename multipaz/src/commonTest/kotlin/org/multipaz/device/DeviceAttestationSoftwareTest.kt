package org.multipaz.device

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class DeviceAttestationSoftwareTest {

    @Test
    fun testValidationWithoutSecret() = runTest {
        testValidation(
            secret = null,
            expectedSecrets = setOf(),
            softwareAccepted = true,
            shouldValidate = true
        )
    }

    @Test
    fun testValidationWithoutSecretExpectedSecret() = runTest {
        testValidation(
            secret = null,
            expectedSecrets = setOf("xyz"),
            softwareAccepted = true,
            shouldValidate = false
        )
    }

    @Test
    fun testValidationWithSecret() = runTest {
        testValidation(
            secret = "xyz",
            expectedSecrets = setOf("xyz"),
            softwareAccepted = true,
            shouldValidate = true
        )
    }

    @Test
    fun testValidationWithSecretNoExpectedSecrets() = runTest {
        testValidation(
            secret = "xyz",
            expectedSecrets = setOf(),
            softwareAccepted = true,
            shouldValidate = true
        )
    }

    @Test
    fun testValidationWithSecretWithDifferentExpectedSecrets() = runTest {
        testValidation(
            secret = "xyz",
            expectedSecrets = setOf("123"),
            softwareAccepted = true,
            shouldValidate = false
        )
    }

    @Test
    fun testValidationSoftwareNotAccepted() = runTest {
        testValidation(
            secret = null,
            expectedSecrets = setOf(),
            softwareAccepted = false,
            shouldValidate = false
        )
    }

    private suspend fun testValidation(
        secret: String?,
        expectedSecrets: Set<String>,
        softwareAccepted: Boolean,
        shouldValidate: Boolean
    ) {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val challenge = ByteString(42, 43)
        val deviceAttestationResult = DeviceAttestationSoftware.generateAttestation(
            secureArea = secureArea,
            challenge = challenge,
            secret = secret
        )
        val validationData = DeviceAttestationValidationData(
            attestationChallenge = challenge,
            softwareAccepted = softwareAccepted,
            softwareSecrets = expectedSecrets,
            iosReleaseBuild = false,
            iosAppIdentifiers = setOf(),
            androidGmsAttestation = false,
            androidVerifiedBootGreen = false,
            androidRequiredKeyMintSecurityLevel = AndroidKeystoreSecurityLevel.SOFTWARE,
            androidAppSignatureCertificateDigests = setOf(),
            androidAppPackageNames = setOf(),
        )
        if (shouldValidate) {
            deviceAttestationResult.deviceAttestation.validate(
                validationData = validationData,
                validateAt = Clock.System.now()
            )
        } else {
            assertFailsWith(DeviceAttestationException::class) {
                deviceAttestationResult.deviceAttestation.validate(
                    validationData = validationData,
                    validateAt = Clock.System.now()
                )
            }
        }
    }

}