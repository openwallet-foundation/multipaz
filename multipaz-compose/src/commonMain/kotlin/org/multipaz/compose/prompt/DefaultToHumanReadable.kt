package org.multipaz.compose.prompt

import org.jetbrains.compose.resources.getString
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.aks_unlock_present_pin_subtitle
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_subtitle
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_title
import org.multipaz.multipaz_compose.generated.resources.key_unlock_default_subtitle
import org.multipaz.multipaz_compose.generated.resources.key_unlock_default_title
import org.multipaz.multipaz_compose.generated.resources.key_unlock_present_bio_subtitle
import org.multipaz.multipaz_compose.generated.resources.key_unlock_present_passphrase_subtitle
import org.multipaz.multipaz_compose.generated.resources.key_unlock_present_title
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.prompt.ConvertToHumanReadableFn
import org.multipaz.prompt.FaceMatchingReason
import org.multipaz.prompt.Reason
import org.multipaz.securearea.PassphraseConstraints

/**
 * Default implementation of [ConvertToHumanReadableFn] for use in [PromptDialogs].
 *
 * Uses translated string resources from the multipaz-compose module. Handles
 * [Reason.HumanReadable] (passes through as-is), [PresentmentUnlockReason] with
 * appropriate subtitle based on whether the expected input is biometric, PIN, or passphrase,
 * and [FaceMatchingReason] for face matching prompts.
 * All other [Reason] values are mapped to a generic unlock prompt.
 *
 * @param unlockReason the reason a secure unlock prompt is being requested.
 * @param passphraseConstraints optional constraints that indicate whether passphrase input is
 * expected, and if so, whether it must be numerical.
 * @return a [Reason.HumanReadable] containing localized title/subtitle text for display.
 */
suspend fun defaultToHumanReadable(
    unlockReason: Reason,
    passphraseConstraints: PassphraseConstraints?
): Reason.HumanReadable = when (unlockReason) {
    is Reason.HumanReadable -> unlockReason
    is PresentmentUnlockReason -> {
        val subtitle = when {
            passphraseConstraints == null -> getString(Res.string.key_unlock_present_bio_subtitle)
            passphraseConstraints.requireNumerical -> getString(Res.string.aks_unlock_present_pin_subtitle)
            else -> getString(Res.string.key_unlock_present_passphrase_subtitle)
        }
        Reason.HumanReadable(
            title = getString(Res.string.key_unlock_present_title),
            subtitle = subtitle,
            requireConfirmation = false
        )
    }

    is FaceMatchingReason -> Reason.HumanReadable(
        title = getString(Res.string.face_matcher_prompt_default_title),
        subtitle = getString(Res.string.face_matcher_prompt_default_subtitle),
        requireConfirmation = false
    )

    else -> Reason.HumanReadable(
        title = getString(Res.string.key_unlock_default_title),
        subtitle = getString(Res.string.key_unlock_default_subtitle),
        requireConfirmation = false
    )
}
