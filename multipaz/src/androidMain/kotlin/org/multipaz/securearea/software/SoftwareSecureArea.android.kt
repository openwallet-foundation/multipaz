package org.multipaz.securearea.software

import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.Reason
import org.multipaz.prompt.showBiometricPrompt
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.UserAuthenticationType

internal actual suspend fun softwareSecureAreaPerformUserAuth(
    alias: String,
    userAuthenticationTypes: Set<SoftwareUserAuthType>,
    unlockReason: Reason
): Boolean {
    val promptModel = try {
        AndroidPromptModel.get()
    } catch (_: PromptModelNotAvailableException) {
        throw KeyLockedException("Key is locked and PromptModel is not available to unlock interactively")
    }
    val androidAuthTypes = mutableSetOf<UserAuthenticationType>()
    if (userAuthenticationTypes.contains(SoftwareUserAuthType.PASSCODE)) {
        androidAuthTypes.add(UserAuthenticationType.LSKF)
    }
    if (userAuthenticationTypes.contains(SoftwareUserAuthType.BIOMETRIC)) {
        androidAuthTypes.add(UserAuthenticationType.BIOMETRIC)
    }
    val humanReadable = promptModel.toHumanReadable(unlockReason, null)
    return promptModel.showBiometricPrompt(
        cryptoObject = null,
        reason = unlockReason,
        userAuthenticationTypes = androidAuthTypes,
        requireConfirmation = humanReadable.requireConfirmation
    )
}
