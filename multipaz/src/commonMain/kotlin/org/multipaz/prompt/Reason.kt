package org.multipaz.prompt

import org.multipaz.securearea.PassphraseConstraints

/**
 * Provides a reason why certain operation is needed or is being performed.
 *
 * For instance, it may be a reason why a key in [org.multipaz.securearea.SecureArea] needs to
 * be unlocked and it could provide the context of the operation that the key is going to be
 * involved in (signing or key agreement), e.g. a credential being presented.
 *
 * A reason can be taken into account when implementing
 * [org.multipaz.securearea.KeyUnlockDataProvider] or when using a user prompt to obtain
 * authorization in [PromptModel.toHumanReadable].
 *
 * This is an interface and thus the set of possible values is not restricted. Applications are
 * encouraged to create application-specific implementation of this interface for use in the
 * application back-end code.
 */
interface Reason {
    /**
     * No reason for the operation is given.
     *
     * TODO: right now this merely creates a generic prompt. We probably should split this in two
     *  distinct values: one that will pop a generic prompt and another for use with
     *  [org.multipaz.securearea.SecureArea] keys that are not expected to be locked (and would
     *  outright cause an exception if the key is locked).
     */
    object Unspecified: Reason

    /**
     * Human-readable explanation for the operation.
     *
     * Note: [title] and [subtitle] are presented to the user and thus **must** be localized.
     *
     * Multipaz library itself never uses values of this type for back-end operations. However,
     * when [PromptModel] is used for key unlock prompts, every other [Reason] value is
     * ultimately converted to a [Reason.HumanReadable]. See [PromptModel.toHumanReadable]
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
    ): Reason
}

/**
 * A function that converts arbitrary [Reason] to a human-readable kind [Reason.HumanReadable].
 *
 * The result is then used to create a prompt for the user input. If the expected input
 * requires user to enter application-defined data (e.g. a PIN or a passphrase),
 * `passphraseConstraints` describes the required input. When the expected input is determined
 * by the OS (e.g. biometrics), `passphraseConstraints` is `null`.
 */
typealias ConvertToHumanReadableFn = suspend (
    unlockReason: Reason,
    passphraseConstraints: PassphraseConstraints?
) -> Reason.HumanReadable
