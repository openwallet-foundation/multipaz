package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.MlDsaSignature
import org.multipaz.crypto.MlKemPublicKey
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureEnclaveCreateKeySettings
import org.multipaz.securearea.SecureEnclaveKeyUnlockData
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.securearea.SecureEnclaveUserAuthType
import org.multipaz.prompt.Reason
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
            TextButton(
                onClick = { seTest(Algorithm.ML_DSA_65,
                    setOf(),
                    coroutineScope,
                    showToast) },
                content = { Text("ML-DSA-65 Signature") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ML_DSA_65,
                    setOf(SecureEnclaveUserAuthType.USER_PRESENCE),
                    coroutineScope,
                    showToast) },
                content = { Text("ML-DSA-65 Signature - Auth (User Presence)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ML_DSA_87,
                    setOf(),
                    coroutineScope,
                    showToast) },
                content = { Text("ML-DSA-87 Signature") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ML_KEM_768,
                    setOf(),
                    coroutineScope,
                    showToast) },
                content = { Text("ML-KEM-768 Decapsulation") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ML_KEM_768,
                    setOf(SecureEnclaveUserAuthType.USER_PRESENCE),
                    coroutineScope,
                    showToast) },
                content = { Text("ML-KEM-768 Decapsulation - Auth (User Presence)") }
            )
            TextButton(
                onClick = { seTest(Algorithm.ML_KEM_1024,
                    setOf(),
                    coroutineScope,
                    showToast) },
                content = { Text("ML-KEM-1024 Decapsulation") }
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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

    val keyUnlockData = SecureEnclaveKeyUnlockData(
        secureArea = secureEnclaveSecureArea,
        alias = "testKey",
        authenticationContext = laContext
    )

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
        val signatureDesc = when (signature) {
            is EcSignature -> "r=${signature.r.toHex()} s=${signature.s.toHex()}"
            is MlDsaSignature -> "signature=${signature.signature.toHex()}"
            else -> "signature=$signature"
        }
        Logger.d(TAG, "Made signature with key $signatureDesc")
        showToast("Signed (${t1 - t0})")
    } else if (algorithm.isKeyEncapsulation) {
        val keyInfo = secureEnclaveSecureArea.getKeyInfo("testKey")
        val kemResult = Crypto.kemEncapsulate(keyInfo.publicKey as MlKemPublicKey)
        val t0 = Clock.System.now()
        val sharedSecret = withContext(TestKeyUnlockDataProvider(keyUnlockData)) {
            secureEnclaveSecureArea.kemDecapsulate(
                "testKey",
                kemResult.ciphertext,
            )
        }
        val t1 = Clock.System.now()
        check(sharedSecret.contentEquals(kemResult.sharedSecret.encoded))
        kemResult.close()
        Logger.dHex(TAG, "Decapsulated shared secret ", sharedSecret)
        showToast("Decapsulated (${t1 - t0})")
    } else {
        Crypto.createEcPrivateKey(EcCurve.P256).use { otherKeyPairForEcdh ->
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
                Zab
            )
            showToast("ECDH (${t1 - t0})")
        }
    }
}

private class TestKeyUnlockDataProvider(
    val keyUnlockData: KeyUnlockData
): KeyUnlockDataProvider {
    override suspend fun getKeyUnlockData(
        secureArea: SecureArea,
        alias: String,
        algorithm: Algorithm,
        unlockReason: Reason
    ): KeyUnlockData = keyUnlockData
}
