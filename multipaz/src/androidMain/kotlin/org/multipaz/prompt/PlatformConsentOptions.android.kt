package org.multipaz.prompt

/**
 * Platform-specific options for the consent prompt on Android.
 *
 * @property showOnBiometricPrompt if `true`, qualifying simple consent prompts will be shown
 *   embedded in an `EmbeddedBiometricPrompt` on Android 17 QPR2 (API 37.2) and later.
 */
actual open class PlatformConsentOptions(
    val showOnBiometricPrompt: Boolean = true,
)
