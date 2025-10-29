package org.multipaz.prompt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.securearea.UnlockReason
import org.multipaz.securearea.PassphraseConstraints

/**
 * [PromptModel] for iOS platform.
 */
class IosPromptModel(
    override val toHumanReadable: ConvertToHumanReadableFn = ::defaultConvertToHumanReadable
): PromptModel {
    override val passphrasePromptModel = SinglePromptModel<PassphraseRequest, String?>()

    override val promptModelScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob() + this)
    }

    companion object {
        /**
         * Converts [UnlockReason] to human-readable form.
         *
         * This is default implementation of [ConvertToHumanReadableFn].
         */
        suspend fun defaultConvertToHumanReadable(
            unlockReason: UnlockReason,
            passphraseConstraints: PassphraseConstraints?
        ): UnlockReason.HumanReadable =
            unlockReason as? UnlockReason.HumanReadable
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
                        UnlockReason.HumanReadable(
                            title = "Verify it's you to share the document",
                            subtitle = subtitle,
                            requireConfirmation = false
                        )
                    }

                    else -> {
                        UnlockReason.HumanReadable(
                            title = "Verify it's you",
                            subtitle = "Authentication is required",
                            requireConfirmation = false
                        )
                    }
                }
    }
}