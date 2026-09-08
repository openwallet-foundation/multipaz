package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.multipaz.crypto.Algorithm
import org.multipaz.prompt.Reason

import org.multipaz.securearea.software.SoftwareUserAuthType

private val TAG = "SoftwareSecureAreaScreen"

@Composable
fun SoftwareSecureAreaScreen(
    softwareSecureArea: SoftwareSecureArea,
    promptModel: PromptModel,
    showToast: (message: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope { promptModel }

    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            Text(text = "Implementation: ${Crypto.provider}")
        }
        for (algorithm in softwareSecureArea.supportedAlgorithms) {
            for ((passphraseRequired, userAuthTypes, description) in arrayOf(
                Triple(false, emptySet<SoftwareUserAuthType>(), ""),
                Triple(true, emptySet<SoftwareUserAuthType>(), "- Passphrase"),
                Triple(
                    false,
                    setOf(SoftwareUserAuthType.PASSCODE, SoftwareUserAuthType.BIOMETRIC),
                    "- User Auth (Passcode or Biometric)"
                ),
                Triple(
                    false,
                    setOf(SoftwareUserAuthType.PASSCODE),
                    "- User Auth (Passcode only)"
                ),
                Triple(
                    false,
                    setOf(SoftwareUserAuthType.BIOMETRIC),
                    "- User Auth (Biometric only)"
                ),
                Triple(
                    true,
                    setOf(SoftwareUserAuthType.PASSCODE, SoftwareUserAuthType.BIOMETRIC),
                    "- Passphrase & User Auth"
                ),
            )) {
                // For brevity, only do passphrase / user auth for P-256 Signature and P-256 Key Agreement
                if (algorithm.curve!! != EcCurve.P256) {
                    if (passphraseRequired || userAuthTypes.isNotEmpty()) {
                        continue;
                    }
                }

                item {
                    TextButton(onClick = {

                        coroutineScope.launch {
                            swTest(
                                softwareSecureArea = softwareSecureArea,
                                algorithm = algorithm,
                                passphrase = if (passphraseRequired) {
                                    "1111"
                                } else {
                                    null
                                },
                                passphraseConstraints = if (passphraseRequired) {
                                    PassphraseConstraints.PIN_FOUR_DIGITS
                                } else {
                                    null
                                },
                                userAuthTypes = userAuthTypes,
                                showToast = showToast
                            )
                        }
                    })
                    {
                        Text(
                            text = "$algorithm $description",
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

private suspend fun swTest(
    softwareSecureArea: SoftwareSecureArea,
    algorithm: Algorithm,
    passphrase: String?,
    passphraseConstraints: PassphraseConstraints?,
    userAuthTypes: Set<SoftwareUserAuthType>,
    showToast: (message: String) -> Unit) {
    Logger.d(
        TAG,
        "swTest algorithm:$algorithm passphrase:$passphrase userAuthTypes:$userAuthTypes"
    )
    try {
        swTestUnguarded(softwareSecureArea, algorithm, passphrase, passphraseConstraints, userAuthTypes, showToast)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        e.printStackTrace();
        showToast("${e.message}")
    }
}

private suspend fun swTestUnguarded(
    softwareSecureArea: SoftwareSecureArea,
    algorithm: Algorithm,
    passphrase: String?,
    passphraseConstraints: PassphraseConstraints?,
    userAuthTypes: Set<SoftwareUserAuthType>,
    showToast: (message: String) -> Unit) {

    val builder = SoftwareCreateKeySettings.Builder()
        .setAlgorithm(algorithm)
    if (passphrase != null) {
        builder.setPassphraseRequired(true, passphrase, passphraseConstraints)
    }
    if (userAuthTypes.isNotEmpty()) {
        builder.setUserAuthenticationRequired(
            true,
            userAuthTypes
        )
    }

    softwareSecureArea.createKey("testKey", builder.build())

    val unlockReason = Reason.HumanReadable(
        title = "Authentication Required",
        subtitle = "Authentication is required to use this software-backed key",
        requireConfirmation = false
    )

    if (algorithm.isSigning) {
        try {
            val t0 = Clock.System.now()
            val signature = softwareSecureArea.sign(
                "testKey",
                "data".encodeToByteArray(),
                unlockReason,
            )
            val t1 = Clock.System.now()
            Logger.d(
                TAG,
                "Made signature in " +
                        "r=${signature.r.toHex()} s=${signature.s.toHex()}"
            )
            showToast("Signed in (${t1 - t0})")
        } catch (e: KeyLockedException) {
            e.printStackTrace();
            showToast("${e.message}")
        }
    } else {
        val otherKeyPairForEcdh = Crypto.createEcPrivateKey(algorithm.curve!!)
        try {
            val t0 = Clock.System.now()
            val Zab = softwareSecureArea.keyAgreement(
                "testKey",
                otherKeyPairForEcdh.publicKey,
                unlockReason,
            )
            val t1 = Clock.System.now()
            Logger.dHex(
                TAG,
                "Calculated ECDH",
                Zab)
            showToast("ECDH in (${t1 - t0})")
        } catch (e: KeyLockedException) {
            e.printStackTrace();
            showToast("${e.message}")
        }
    }
}

