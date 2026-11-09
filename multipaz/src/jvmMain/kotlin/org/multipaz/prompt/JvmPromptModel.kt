package org.multipaz.prompt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.securearea.PassphraseConstraints
import kotlin.coroutines.EmptyCoroutineContext

/**
 * [PromptModel] for JVM / Desktop platform.
 */
class JvmPromptModel private constructor(builder: Builder) : PromptModel(builder) {

    override val promptModelScope =
        CoroutineScope(resolveDispatcher() + SupervisorJob() + this)

    class Builder(
        toHumanReadable: ConvertToHumanReadableFn = ::defaultConvertToHumanReadable
    ) : PromptModel.Builder(toHumanReadable) {

        override fun build(): JvmPromptModel {
            return JvmPromptModel(this)
        }
    }

    companion object {
        private fun resolveDispatcher(): CoroutineDispatcher = try {
            Dispatchers.Main.immediate.also {
                it.isDispatchNeeded(EmptyCoroutineContext)
            }
        } catch (_: Throwable) {
            Dispatchers.Default
        }

        /**
         * Converts [Reason] to human-readable form.
         */
        suspend fun defaultConvertToHumanReadable(
            unlockReason: Reason,
            passphraseConstraints: PassphraseConstraints?
        ): Reason.HumanReadable =
            unlockReason as? Reason.HumanReadable
                ?: when (unlockReason) {
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
