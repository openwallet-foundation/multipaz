package org.multipaz.securearea

import org.multipaz.prompt.PromptModel

/**
 * Provides a reason why a key in [SecureArea] needs to be unlocked and the context of the
 * operation that the key is going to be involved (signing or key agreement).
 *
 * An unlock reason can be taken into account when implementing [KeyUnlockDataProvider] or when
 * using a user prompt to obtain authorization in [PromptModel.toHumanReadable].
 *
 * This is an interface and thus the set of possible values is not restricted. Applications are
 * encouraged to create application-specific implementation of this interface for use in the
 * application back-end code.
 */
interface UnlockReason {
    /**
     * No reason for the operation is given.
     *
     * TODO: right now this merely creates a generic prompt. We probably should split this in two
     *  distinct values: one that will pop a generic prompt and another for use with keys that
     *  are not expected to be locked (and would outright cause an exception if the key is locked).
     */
    object Unspecified: UnlockReason

    /**
     * Human-readable explanation for the operation.
     *
     * Note: [title] and [subtitle] are presented to the user and thus **must** be localized.
     *
     * Multipaz library itself never uses values of this type for back-end operations. However,
     * when [PromptModel] is used for key unlock prompts, every other [UnlockReason] value is
     * ultimately converted to a [UnlockReason.HumanReadable]. See [PromptModel.toHumanReadable]
     * property (which can be used to customize unlock prompts).
     *
     * @property title the title to show in the authentication prompt.
     * @property subtitle the subtitle to show in the authentication prompt.
     * @property requireConfirmation if active user confirmation is required when used for passive
     *    biometrics.
     */
    data class HumanReadable(
        val title: String,
        val subtitle: String,
        val requireConfirmation: Boolean
    ): UnlockReason
}