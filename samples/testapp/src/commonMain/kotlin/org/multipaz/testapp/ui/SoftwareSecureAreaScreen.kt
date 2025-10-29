package org.multipaz.testapp.ui

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
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.UnlockReason

private val TAG = "SoftwareSecureAreaScreen"

@Preview
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
            for ((passphraseRequired, description) in arrayOf(
                Pair(true, "- Passphrase"),
                Pair(false, ""),
            )) {
                // For brevity, only do passphrase for P-256 Signature and P-256 Key Agreement)
                if (algorithm.curve!! != EcCurve.P256) {
                    if (passphraseRequired) {
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
    showToast: (message: String) -> Unit) {
    Logger.d(
        TAG,
        "swTest algorithm:$algorithm passphrase:$passphrase"
    )
    try {
        swTestUnguarded(softwareSecureArea, algorithm, passphrase, passphraseConstraints, showToast)
    } catch (e: Throwable) {
        e.printStackTrace();
        showToast("${e.message}")
    }
}

private suspend fun swTestUnguarded(
    softwareSecureArea: SoftwareSecureArea,
    algorithm: Algorithm,
    passphrase: String?,
    passphraseConstraints: PassphraseConstraints?,
    showToast: (message: String) -> Unit) {

    val builder = SoftwareCreateKeySettings.Builder()
        .setAlgorithm(algorithm)
    if (passphrase != null) {
        builder.setPassphraseRequired(true, passphrase, passphraseConstraints)
    }

    softwareSecureArea.createKey("testKey", builder.build())

    val unlockReason = UnlockReason.HumanReadable(
        title = "Enter Knowledge Factor",
        subtitle = "This is used to decrypt the private key material. " +
                "In this sample the knowledge factor is '1111' but try " +
                "entering something else to check out error handling",
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

