package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.device.AssertionNonce
import org.multipaz.device.DeviceAttestationValidationData
import org.multipaz.device.DeviceCheck
import org.multipaz.util.Platform

@Composable
fun DeviceCheckScreen(
    showToast: (message: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        item {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        doDeviceCheck(
                            strict = true,
                            showToast = showToast
                        )
                    }
                }
            ) {
                Text("DeviceCheck (Strict)")
            }
        }

        item {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        doDeviceCheck(
                            strict = false,
                            showToast = showToast
                        )
                    }
                }
            ) {
                Text("DeviceCheck (Non-strict)")
            }
        }

        item {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val deviceAttestationResult = DeviceCheck.generateAttestation(
                                secureArea = Platform.getSecureArea(Platform.nonBackedUpStorage),
                                challenge = ByteString(1, 2, 3),
                                secret = "MultipazSecret"
                            )
                            val deviceAssertion = DeviceCheck.generateAssertion(
                                secureArea = Platform.getSecureArea(Platform.nonBackedUpStorage),
                                deviceAttestationId = deviceAttestationResult.deviceAttestationId,
                                assertion = AssertionNonce("Multipaz".encodeToByteString())
                            )
                            showToast(deviceAssertion.toString())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            e.printStackTrace()
                            showToast("Error: ${e.message}")
                        }
                    }
                }
            ) {
                Text("Test AssertionNonce")
            }
        }
    }
}

private suspend fun doDeviceCheck(
    strict: Boolean,
    showToast: (message: String) -> Unit
) {
    try {
        val deviceAttestationResult = DeviceCheck.generateAttestation(
            secureArea = Platform.getSecureArea(Platform.nonBackedUpStorage),
            challenge = ByteString(1, 2, 3),
            secret = "MultipazSecret"
        )

        val validationData = if (strict) {
            DeviceAttestationValidationData(
                attestationChallenge = ByteString(1, 2, 3),
                softwareAccepted = false,
                softwareSecrets = emptySet(),
                // Since we don't currently publish an iOS app, we can't really change these
                iosReleaseBuild = false,
                iosAppIdentifiers = emptySet(),
                androidGmsAttestation = true,
                androidVerifiedBootGreen = true,
                androidRequiredKeyMintSecurityLevel = AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                androidAppSignatureCertificateDigests = emptySet(),
                androidAppPackageNames = setOf("org.multipaz.testapp"),
            )
        } else {
            DeviceAttestationValidationData(
                attestationChallenge = ByteString(1, 2, 3),
                softwareAccepted = true,
                softwareSecrets = setOf("MultipazSecret"),
                iosReleaseBuild = false,
                iosAppIdentifiers = emptySet(),
                androidGmsAttestation = false,
                androidVerifiedBootGreen = false,
                androidRequiredKeyMintSecurityLevel = AndroidKeystoreSecurityLevel.SOFTWARE,
                androidAppSignatureCertificateDigests = emptySet(),
                androidAppPackageNames = setOf("org.multipaz.testapp"),
            )
        }

        deviceAttestationResult.deviceAttestation.validate(validationData)
        showToast(deviceAttestationResult.toString())
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        e.printStackTrace()
        showToast("Error: ${e.message}")
    }
}