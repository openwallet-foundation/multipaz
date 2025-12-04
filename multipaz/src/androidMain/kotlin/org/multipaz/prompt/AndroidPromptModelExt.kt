package org.multipaz.prompt

import androidx.biometric.BiometricPrompt.CryptoObject
import org.multipaz.securearea.UserAuthenticationType

/**
 * Prompts user for authentication through biometrics.
 *
 * To dismiss the prompt programmatically, cancel the job the coroutine was launched in.
 *
 * To obtain [title] and [subtitle] back-end code generally should create a [Reason] object and
 * use [PromptModel.toHumanReadable] to convert it to human-readable form. This gives
 * application code a chance to customize user-facing messages.
 *
 * @param cryptoObject optional [CryptoObject] to be associated with the authentication.
 * @param title the title for the authentication prompt.
 * @param subtitle the subtitle for the authentication prompt.
 * @param userAuthenticationTypes the set of allowed user authentication types, must contain at least one element.
 * @param requireConfirmation set to `true` to require explicit user confirmation after presenting passive biometric.
 * @return `true` if authentication succeed, `false` if the user dismissed the prompt.
 */
suspend fun AndroidPromptModel.showBiometricPrompt(
    cryptoObject: CryptoObject?,
    title: String,
    subtitle: String,
    userAuthenticationTypes: Set<UserAuthenticationType>,
    requireConfirmation: Boolean
): Boolean {
    return getDialogModel(BiometricPromptDialogModel.DialogType).displayPrompt(
        BiometricPromptDialogModel.BiometricPromptState(
            cryptoObject,
            title,
            subtitle,
            userAuthenticationTypes,
            requireConfirmation
        )
    )
}
