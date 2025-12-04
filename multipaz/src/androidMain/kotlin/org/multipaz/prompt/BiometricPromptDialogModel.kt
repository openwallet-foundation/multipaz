package org.multipaz.prompt

import androidx.biometric.BiometricPrompt.CryptoObject
import org.multipaz.securearea.UserAuthenticationType

/**
 * [PromptDialogModel] to display a biometric prompt on Android.
 *
 * See [AndroidPromptModel.showBiometricPrompt] that is a thin wrapper over this class functionality
 * for more info.
 */
class BiometricPromptDialogModel:
    PromptDialogModel<BiometricPromptDialogModel.BiometricPromptState, Boolean>() {
    object DialogType: PromptDialogModel.DialogType<BiometricPromptDialogModel>

    override val dialogType: DialogType get() = DialogType

    /**
     * Parameters for the biometric prompt.
     *
     * @property cryptoObject optional [CryptoObject] to be associated with the authentication.
     * @property title the title for the authentication prompt.
     * @property subtitle the subtitle for the authentication prompt.
     * @property userAuthenticationTypes the set of allowed user authentication types, must contain at least one element.
     * @property requireConfirmation set to `true` to require explicit user confirmation after presenting passive biometric.
     */
    data class BiometricPromptState(
        val cryptoObject: CryptoObject?,
        val title: String,
        val subtitle: String,
        val userAuthenticationTypes: Set<UserAuthenticationType>,
        val requireConfirmation: Boolean
    )
}