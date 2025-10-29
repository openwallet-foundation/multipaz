package org.multipaz.securearea

import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import kotlin.coroutines.coroutineContext

object AndroidKeystoreDefaultKeyUnlockDataProvider: KeyUnlockDataProvider {
    override suspend fun getKeyUnlockData(
        secureArea: SecureArea,
        alias: String,
        unlockReason: UnlockReason
    ): KeyUnlockData {
        check(secureArea is AndroidKeystoreSecureArea)
        val unlockData = AndroidKeystoreKeyUnlockData(secureArea, alias)
        val keyInfo = secureArea.getKeyInfo(alias)
        val promptModel = try {
            AndroidPromptModel.get(coroutineContext)
        } catch (_: PromptModelNotAvailableException) {
            throw KeyLockedException("Key is locked and PromptModel is not available to unlock interactively")
        }
        val humanReadable = promptModel.toHumanReadable(unlockReason, null)
        if (!promptModel.showBiometricPrompt(
                cryptoObject = unlockData.getCryptoObjectForSigning(),
                title = humanReadable.title,
                subtitle = humanReadable.subtitle,
                userAuthenticationTypes = keyInfo.userAuthenticationTypes,
                requireConfirmation = humanReadable.requireConfirmation
            )
        ) {
            throw KeyLockedException("User canceled authentication")
        }
        return unlockData
    }
}