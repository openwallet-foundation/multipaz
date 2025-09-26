package org.multipaz.prompt

import androidx.biometric.BiometricPrompt.CryptoObject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.securearea.UserAuthenticationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * [PromptModel] for Android platform.
 *
 * On Android [PromptModel] is also a [ViewModel]. [promptModelScope] that it exposes is
 * automatically cancelled when this [ViewModel] is cleared.
 *
 * In addition to [passphrasePromptModel], Android UI must provide bindings for two more
 * dialog kinds: [biometricPromptModel] and [scanNfcPromptModel].
 */
class AndroidPromptModel: ViewModel(), PromptModel {
    override val passphrasePromptModel = SinglePromptModel<PassphraseRequest, String?>()
    val biometricPromptModel = SinglePromptModel<BiometricPromptState, Boolean>()
    val scanNfcPromptModel = SinglePromptModel<NfcDialogParameters<Any>, Any?>(
        lingerDuration = 2.seconds
    )

    @Volatile
    private var scope: CoroutineScope? = null

    override val promptModelScope: CoroutineScope
        get() {
            val scope = this.scope
            return if (scope != null) {
                scope
            } else {
                val newScope = CoroutineScope(viewModelScope.coroutineContext + this)
                this.scope = newScope
                newScope
            }
        }

    override fun onCleared() {
        super.onCleared()
        scope?.cancel()
        scope = null
    }

    companion object {
        fun get(coroutineContext: CoroutineContext) =
            PromptModel.get(coroutineContext) as AndroidPromptModel
    }
}

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
suspend fun showBiometricPrompt(
    cryptoObject: CryptoObject?,
    title: String,
    subtitle: String,
    userAuthenticationTypes: Set<UserAuthenticationType>,
    requireConfirmation: Boolean
): Boolean {
    val promptModel = AndroidPromptModel.get(coroutineContext)
    return promptModel.biometricPromptModel.displayPrompt(
        BiometricPromptState(
            cryptoObject,
            title,
            subtitle,
            userAuthenticationTypes,
            requireConfirmation
        )
    )
}

/**
 * Parameters needed for UI to display and run NFC dialog. See
 * [org.multipaz.nfc.NfcTagReader.scan] for more information.
 *
 * @param initialMessage the message to initially show in the dialog or `null` to not show a dialog at all.
 * @param interactionFunction the function which is called when the tag is in the field.
 * @param context the [CoroutineContext] to use for calls which blocks the calling thread.
 */
class NfcDialogParameters<out T>(
    val initialMessage: String?,
    val interactionFunction: suspend (tag: NfcIsoTag) -> T?,
    val context: CoroutineContext
)

class BiometricPromptState(
    val cryptoObject: CryptoObject?,
    val title: String,
    val subtitle: String,
    val userAuthenticationTypes: Set<UserAuthenticationType>,
    val requireConfirmation: Boolean
)
