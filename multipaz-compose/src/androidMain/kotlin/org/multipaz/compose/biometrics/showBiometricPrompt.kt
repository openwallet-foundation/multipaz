package org.multipaz.compose.biometrics

import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.CryptoObject
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.multipaz.securearea.UserAuthenticationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.biometric_prompt_negative_btn_lskf
import org.multipaz.multipaz_compose.generated.resources.biometric_prompt_negative_btn_no_lskf
import kotlin.coroutines.resumeWithException

/**
 * Prompts user for authentication.
 *
 * To dismiss the prompt programmatically, cancel the job the coroutine was launched in.
 *
 * @param cryptoObject optional [CryptoObject] to be associated with the authentication.
 * @param title the title for the authentication prompt.
 * @param subtitle the subtitle for the authentication prompt.
 * @param userAuthenticationTypes the set of allowed user authentication types, must contain at least one element.
 * @param requireConfirmation set to `true` to require explicit user confirmation after presenting passive biometric.
 * @return `true` if authentication succeed, `false` if the user dismissed the prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun showBiometricPrompt(
    activity: FragmentActivity,
    cryptoObject: CryptoObject?,
    title: String,
    subtitle: String,
    userAuthenticationTypes: Set<UserAuthenticationType>,
    requireConfirmation: Boolean
): Boolean {
    val negativeButtonText = getString(Res.string.biometric_prompt_negative_btn_lskf)
    val negativeButtonTextNoLskf = getString(Res.string.biometric_prompt_negative_btn_no_lskf)
    // BiometricPrompt must be called from the UI thread.
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            showBiometricPromptAsync(
                cryptoObject = cryptoObject,
                activity = activity,
                title = title,
                subtitle = subtitle,
                negativeButtonText = negativeButtonText,
                negativeButtonTextNoLskf = negativeButtonTextNoLskf,
                userAuthenticationTypes = userAuthenticationTypes,
                requireConfirmation = requireConfirmation,
                onSuccess = { continuation.resume(true, null) },
                onDismissed = { continuation.resume(false, null) },
                onError = { error -> continuation.resumeWithException(error) },
            )
        }
    }
}

private fun showBiometricPromptAsync(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeButtonText: String,
    negativeButtonTextNoLskf: String,
    cryptoObject: CryptoObject?,
    userAuthenticationTypes: Set<UserAuthenticationType>,
    requireConfirmation: Boolean,
    onSuccess: () -> Unit,
    onDismissed: () -> Unit,
    onError: (error: Throwable) -> Unit,
) {
    if (userAuthenticationTypes.isEmpty()) {
        onError(
            IllegalStateException(
                "userAuthenticationTypes must contain at least one authentication type"
            )
        )
    }
    ShowBiometricPrompt(
        activity = activity,
        title = title,
        subtitle = subtitle,
        negativeButtonText = negativeButtonText,
        negativeButtonTextNoLskf = negativeButtonTextNoLskf,
        cryptoObject = cryptoObject,
        userAuthenticationTypes = userAuthenticationTypes,
        requireConfirmation = requireConfirmation,
        onSuccess = onSuccess,
        onDismissed = onDismissed,
        onError = onError
    ).authenticate()
}

private class ShowBiometricPrompt(
    private val activity: FragmentActivity,
    private val title: String,
    private val subtitle: String,
    private val negativeButtonText: String,
    private val negativeButtonTextNoLskf: String,
    private val cryptoObject: CryptoObject?,
    private val userAuthenticationTypes: Set<UserAuthenticationType>,
    private val requireConfirmation: Boolean,
    private val onSuccess: () -> Unit,
    private val onDismissed: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private var lskfOnNegativeBtn: Boolean = false

    private val biometricAuthCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(
            errorCode: Int,
            errorString: CharSequence
        ) {
            super.onAuthenticationError(errorCode, errorString)
            if (setOf(
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_UNABLE_TO_PROCESS
                ).contains(errorCode) && lskfOnNegativeBtn
            ) {
                // if no delay is injected, then biometric prompt's auth callbacks would not be called
                Handler(Looper.getMainLooper()).postDelayed({
                    authenticateLskf()
                }, 100)
            } else if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                onDismissed()
            } else if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && !lskfOnNegativeBtn) {
                onDismissed()
            } else {
                onError(IllegalStateException("onAuthenticationError callback with code $errorCode: $errorString"))
            }
        }

        override fun onAuthenticationSucceeded(
            result: BiometricPrompt.AuthenticationResult
        ) {
            super.onAuthenticationSucceeded(result)
            onSuccess()
        }
    }

    private val biometricPrompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        biometricAuthCallback
    )

    fun authenticate() {
        lskfOnNegativeBtn = userAuthenticationTypes.contains(UserAuthenticationType.LSKF)
        if (userAuthenticationTypes.contains(UserAuthenticationType.BIOMETRIC)) {
            authenticateBiometric()
        } else {
            authenticateLskf()
        }
    }

    private fun authenticateBiometric() {
        val negativeTxt =
            if (lskfOnNegativeBtn) {
                negativeButtonText
            } else {
                negativeButtonTextNoLskf
            }

        val biometricPromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeTxt)
            .setConfirmationRequired(requireConfirmation)
            .build()

        if (cryptoObject != null) {
            biometricPrompt.authenticate(biometricPromptInfo, cryptoObject)
        } else {
            biometricPrompt.authenticate(biometricPromptInfo)
        }
    }

    private fun authenticateLskf() {
        lskfOnNegativeBtn = false

        val lskfPromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(requireConfirmation)
            .build()

        if (cryptoObject != null) {
            biometricPrompt.authenticate(lskfPromptInfo, cryptoObject)
        } else {
            biometricPrompt.authenticate(lskfPromptInfo)
        }
    }
}
