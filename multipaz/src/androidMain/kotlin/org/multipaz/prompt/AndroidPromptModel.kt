package org.multipaz.prompt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.multipaz.R
import org.multipaz.context.applicationContext
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.presentment.PresentmentUnlockReason

/**
 * [PromptModel] for Android platform.
 */
class AndroidPromptModel private constructor(builder: Builder): PromptModel(builder) {
    override val promptModelScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob() + this)

    private val uiLauncher: suspend (PromptDialogModel<*,*>) -> Unit = builder.uiLauncher

    override suspend fun launchUi(dialogModel: PromptDialogModel<*, *>) {
        uiLauncher.invoke(dialogModel)
    }

    fun cancel() {
        promptModelScope.cancel()
    }

    class Builder(
        val uiLauncher: suspend (PromptDialogModel<*,*>) -> Unit = {},
        toHumanReadable: ConvertToHumanReadableFn = ::defaultConvertToHumanReadable
    ): PromptModel.Builder(toHumanReadable) {

        override fun addCommonDialogs() = apply {
            super.addCommonDialogs()
            addPromptDialogModel(BiometricPromptDialogModel())
            addPromptDialogModel(ScanNfcPromptDialogModel())
        }

        override fun build(): AndroidPromptModel {
            return AndroidPromptModel(this)
        }
    }

    companion object {
        suspend fun get() = PromptModel.get() as AndroidPromptModel

        /**
         * Converts [Reason] to human-readable form.
         *
         * This is default implementation of [ConvertToHumanReadableFn].
         */
        suspend fun defaultConvertToHumanReadable(
            unlockReason: Reason,
            passphraseConstraints: PassphraseConstraints?
        ): Reason.HumanReadable =
            if (unlockReason is Reason.HumanReadable) {
                unlockReason
            } else {
                val res = applicationContext.resources
                when (unlockReason) {
                    is PresentmentUnlockReason -> {
                        val subtitleRes = if (passphraseConstraints == null) {
                            R.string.key_unlock_present_bio_subtitle
                        } else if (passphraseConstraints.requireNumerical) {
                            R.string.aks_unlock_present_pin_subtitle
                        } else {
                            R.string.key_unlock_present_passphrase_subtitle
                        }
                        Reason.HumanReadable(
                            title = res.getString(R.string.key_unlock_present_title),
                            subtitle = res.getString(subtitleRes),
                            requireConfirmation = false
                        )
                    }
                    else -> {
                        Reason.HumanReadable(
                            title = res.getString(R.string.key_unlock_default_title),
                            subtitle = res.getString(R.string.key_unlock_default_subtitle),
                            requireConfirmation = false
                        )
                    }
                }
            }
    }
}
