package org.multipaz.prompt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.securearea.PassphraseConstraints

/**
 * [PromptModel] for iOS platform.
 */
class IosPromptModel private constructor(builder: Builder): PromptModel(builder) {

    override val promptModelScope =
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob() + this)

    class Builder(
        toHumanReadable: ConvertToHumanReadableFn = ::defaultConvertToHumanReadable
    ): PromptModel.Builder(toHumanReadable) {

        override fun build(): IosPromptModel {
            return IosPromptModel(this)
        }
    }

    companion object {
        /**
         * Converts [Reason] to human-readable form.
         *
         * This is default implementation of [ConvertToHumanReadableFn].
         */
        suspend fun defaultConvertToHumanReadable(
            unlockReason: Reason,
            passphraseConstraints: PassphraseConstraints?
        ): Reason.HumanReadable =
            unlockReason as? Reason.HumanReadable
                ?: when (unlockReason) {
                    // TODO: get strings from (localizable) resources or move this functionality
                    //  to a higher level (multipaz-compose?)
                    is PresentmentUnlockReason -> {
                        val subtitle = if (passphraseConstraints == null) {
                            "Authentication is required"
                        } else if (passphraseConstraints.requireNumerical) {
                            "Enter the PIN associated with the document"
                        } else {
                            "Enter the passphrase associated with the document"
                        }
                        Reason.HumanReadable(
                            title = "Verify it's you to share the document",
                            subtitle = subtitle,
                            requireConfirmation = false
                        )
                    }

                    else -> {
                        Reason.HumanReadable(
                            title = "Verify it's you",
                            subtitle = "Authentication is required",
                            requireConfirmation = false
                        )
                    }
                }
    }
}