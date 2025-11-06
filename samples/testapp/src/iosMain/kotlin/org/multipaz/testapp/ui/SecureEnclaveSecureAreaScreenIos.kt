package org.multipaz.testapp.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureEnclaveCreateKeySettings
import org.multipaz.securearea.SecureEnclaveKeyUnlockData
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.securearea.SecureEnclaveUserAuthType
import org.multipaz.securearea.UnlockReason
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private val TAG = "SecureEnclaveSecureAreaScreen"

private val secureEnclaveStorage = EphemeralStorage()

private val secureEnclaveSecureAreaProvider = SecureAreaProvider {
    SecureEnclaveSecureArea.create(secureEnclaveStorage)
}

@Composable
actual fun SecureEnclaveSecureAreaScreen(showToast: (message: String) -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    LazyColumn {
        item {
            TextButton(
                onClick = { seTest(Algorithm.ESP256,
                    setOf(),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Signature") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ESP256,
                    setOf(SecureEnclaveUserAuthType.DEVICE_PASSCODE),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Signature - Auth (Passcode)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ESP256,
                    setOf(SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Signature - Auth (Biometrics)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ESP256,
                    setOf(SecureEnclaveUserAuthType.DEVICE_PASSCODE, SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Signature - Auth (Passcode AND Biometrics)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ESP256,
                    setOf(SecureEnclaveUserAuthType.USER_PRESENCE),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Signature - Auth (Passcode OR Biometrics)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ECDH_P256,
                    setOf(),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Key Agreement") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ECDH_P256,
                    setOf(SecureEnclaveUserAuthType.DEVICE_PASSCODE),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Key Agreement - Auth (Passcode)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ECDH_P256,
                    setOf(SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Key Agreement - Auth (Biometrics)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ECDH_P256,
                    setOf(SecureEnclaveUserAuthType.DEVICE_PASSCODE, SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Key Agreement - Auth (Passcode AND Biometrics)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ECDH_P256,
                    setOf(SecureEnclaveUserAuthType.USER_PRESENCE),
                    coroutineScope,
                    showToast) },
                content = { Text("P-256 Key Agreement - Auth (Passcode OR Biometrics)") }
            )
        }

    }
}

private fun seTest(
    algorithm: Algorithm,
    userAuthTypes: Set<SecureEnclaveUserAuthType>,
    coroutineScope: CoroutineScope,
    showToast: (message: String) -> Unit
) {
    coroutineScope.launch {
        try {
            seTestUnguarded(algorithm, userAuthTypes, showToast)
        } catch (e: Throwable) {
            e.printStackTrace();
            showToast("${e.message}")
        }
    }
}

private suspend fun seTestUnguarded(
    algorithm: Algorithm,
    userAuthTypes: Set<SecureEnclaveUserAuthType>,
    showToast: (message: String) -> Unit
) {
    val secureEnclaveSecureArea = secureEnclaveSecureAreaProvider.get()
    secureEnclaveSecureArea.createKey(
        "testKey",
        SecureEnclaveCreateKeySettings.Builder()
            .setAlgorithm(algorithm)
            .setUserAuthenticationRequired(!userAuthTypes.isEmpty(), userAuthTypes)
            .build()
    )

    val laContext = platform.LocalAuthentication.LAContext()
    laContext.localizedReason = "Authenticate to use key"

    val keyUnlockData = SecureEnclaveKeyUnlockData(laContext)

    if (algorithm.isSigning) {
        val dataToSign = "data".encodeToByteArray()
        val t0 = Clock.System.now()
        val signature = withContext(TestKeyUnlockDataProvider(keyUnlockData)) {
            secureEnclaveSecureArea.sign(
                "testKey",
                dataToSign,
            )
        }
        val t1 = Clock.System.now()
        Logger.d(
            TAG,
            "Made signature with key " +
                    "r=${signature.r.toHex()} s=${signature.s.toHex()}",
        )
        showToast("Signed (${t1 - t0})")
    } else {
        val otherKeyPairForEcdh = Crypto.createEcPrivateKey(EcCurve.P256)
        val t0 = Clock.System.now()
        val Zab = withContext(TestKeyUnlockDataProvider(keyUnlockData)) {
            secureEnclaveSecureArea.keyAgreement(
                "testKey",
                otherKeyPairForEcdh.publicKey,
            )
        }
        val t1 = Clock.System.now()
        Logger.dHex(
            TAG,
            "Calculated ECDH ",
            Zab)
        showToast("ECDH (${t1 - t0})")
    }
}

private class TestKeyUnlockDataProvider(
    val keyUnlockData: KeyUnlockData
): KeyUnlockDataProvider {
    override suspend fun getKeyUnlockData(
        secureArea: SecureArea,
        alias: String,
        unlockReason: UnlockReason
    ): KeyUnlockData = keyUnlockData
}
