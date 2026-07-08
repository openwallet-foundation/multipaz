package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.Reason
import org.multipaz.prompt.showBiometricPrompt

object AndroidKeystoreDefaultKeyUnlockDataProvider: KeyUnlockDataProvider {
    override suspend fun getKeyUnlockData(
        secureArea: SecureArea,
        alias: String,
        algorithm: Algorithm,
        unlockReason: Reason
    ): KeyUnlockData {
        check(secureArea is AndroidKeystoreSecureArea)
        val unlockData = AndroidKeystoreKeyUnlockData(secureArea, alias)
        val keyInfo = secureArea.getKeyInfo(alias)
        val promptModel = try {
            AndroidPromptModel.get()
        } catch (_: PromptModelNotAvailableException) {
            throw KeyLockedException("Key is locked and PromptModel is not available to unlock interactively")
        }
        val humanReadable = promptModel.toHumanReadable(unlockReason, null)
        if (!promptModel.showBiometricPrompt(
                cryptoObject = if (algorithm.isSigning) {
                    unlockData.getCryptoObjectForSigning()
                } else if (algorithm.isKeyAgreement) {
                    unlockData.cryptoObjectForKeyAgreement
                } else {
                    throw IllegalArgumentException("Algorithm isn't for signing or key agreement")
                },
                reason = unlockReason,
                userAuthenticationTypes = keyInfo.userAuthenticationTypes,
                requireConfirmation = humanReadable.requireConfirmation
            )
        ) {
            throw KeyLockedException("User canceled authentication")
        }
        return unlockData
    }
}