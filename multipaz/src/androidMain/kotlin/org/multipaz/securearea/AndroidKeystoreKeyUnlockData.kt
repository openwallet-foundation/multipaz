package org.multipaz.securearea

import kotlinx.coroutines.CancellationException
import androidx.biometric.BiometricPrompt
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.KeyUnlockData
import java.security.KeyFactory
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.InvalidKeySpecException

/**
 * A class that can be used to provide information used for unlocking a key.
 *
 * Currently only user-authentication is supported.
 *
 * @param alias the alias of the key to unlock.
 */
class AndroidKeystoreKeyUnlockData(
    override val secureArea: AndroidKeystoreSecureArea,
    override val alias: String
): KeyUnlockData {
    internal var signature: Signature? = null
    private var cryptoObjectForSigning: BiometricPrompt.CryptoObject? = null

    /**
     * Gets a [BiometricPrompt.CryptoObject] for signing data.
     *
     * This can be used with [BiometricPrompt] to unlock the key.
     * On successful authentication, this object should be passed to
     * [AndroidKeystoreSecureArea.sign].
     *
     * Note that a [BiometricPrompt.CryptoObject] is returned only if the key is
     * configured to require authentication for every use of the key, that is, when the
     * key was created with a zero timeout as per
     * [AndroidKeystoreSecureArea.CreateKeySettings.Builder.setUserAuthenticationRequired].
     *
     * @return A [BiometricPrompt.CryptoObject] or `null`.
     */
    suspend fun getCryptoObjectForSigning(): BiometricPrompt.CryptoObject? {
        if (cryptoObjectForSigning != null) {
            return cryptoObjectForSigning
        }
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            // getKey() rather than getEntry(): getEntry()'s PrivateKeyEntry rejects Ed25519
            // (private-key algo "EdDSA" vs the cert) — see AndroidKeystoreSecureArea (b/282063229).
            val privateKey = (ks.getKey(alias, null) as? PrivateKey)
                ?: throw IllegalArgumentException("No entry for alias")
            val factory = try {
                KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
            } catch (e: NoSuchAlgorithmException) {
                KeyFactory.getInstance("EC", "AndroidKeyStore") // "EdDSA" has no dedicated factory
            }
            try {
                val keyInfo = factory.getKeySpec(
                    privateKey,
                    android.security.keystore.KeyInfo::class.java
                )
                if (keyInfo.userAuthenticationValidityDurationSeconds > 0) {
                    // Key is not auth-per-op, no CryptoObject required.
                    return null
                }
            } catch (e: InvalidKeySpecException) {
                throw IllegalStateException("Given key is not an Android Keystore key", e)
            }
            val signatureAlgorithm = secureArea.getKeyInfo(alias).algorithm
            signature = Signature.getInstance(
                AndroidKeystoreSecureArea.getSignatureAlgorithmName(signatureAlgorithm)
            )
            signature!!.initSign(privateKey)
            cryptoObjectForSigning = BiometricPrompt.CryptoObject(signature!!)
            return cryptoObjectForSigning
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException(e)
        }
    }

    /**
     * Gets a [BiometricPrompt.CryptoObject] for ECDH.
     *
     * This can be used with [BiometricPrompt] to unlock the key.
     * On successful authentication, this object should be passed to
     * [AndroidKeystoreSecureArea.keyAgreement].
     *
     * Note that a [BiometricPrompt.CryptoObject] is returned only if the key is
     * configured to require authentication for every use of the key, that is, when the
     * key was created with a zero timeout as per
     * [AndroidKeystoreSecureArea.CreateKeySettings.Builder.setUserAuthenticationRequired].
     */
    val cryptoObjectForKeyAgreement: BiometricPrompt.CryptoObject?
        get() {
            if (cryptoObjectForSigning != null) {
                return cryptoObjectForSigning
            }
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                val privateKey = (ks.getKey(alias, null) as? PrivateKey)
                    ?: throw IllegalArgumentException("No entry for alias")
                val factory = try {
                    KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
                } catch (e: NoSuchAlgorithmException) {
                    KeyFactory.getInstance("EC", "AndroidKeyStore")
                }
                try {
                    val keyInfo = factory.getKeySpec(
                        privateKey,
                        android.security.keystore.KeyInfo::class.java
                    )
                    if (keyInfo.userAuthenticationValidityDurationSeconds > 0) {
                        // Key is not auth-per-op, no CryptoObject required.
                        return null
                    }
                } catch (e: InvalidKeySpecException) {
                    throw IllegalStateException("Given key is not an Android Keystore key", e)
                }

                // TODO: Unfortunately we forgot to add support in CryptoObject for KeyAgreement
                //  when we added ECHD to AOSP so this will not work until the platform gains
                //  support for constructing a CryptoObject from a KeyAgreement object. See
                //  b/282058146 and b/400115331 for details.
                throw IllegalStateException("ECDH for keys with timeout 0 is not currently supported")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw IllegalStateException(e)
            }
        }
}