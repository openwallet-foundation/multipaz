import Foundation

/// Default implementation for converting a [Reason] to a [ReasonHumanReadable] for display
/// in prompt dialogs.
///
/// Handles ``ReasonHumanReadable`` (passes through as-is) and ``PresentmentUnlockReason``
/// with an appropriate subtitle based on whether the expected input is biometric, PIN, or
/// passphrase. All other ``Reason`` values are mapped to a generic unlock prompt.
///
/// - Parameters:
///   - reason: The reason a secure unlock prompt is being requested.
///   - passphraseConstraints: Optional constraints indicating whether passphrase input is
///     expected, and if so, whether it must be numerical.
/// - Returns: A ``ReasonHumanReadable`` containing localized title/subtitle text for display.
@MainActor
public func defaultToHumanReadable(
    reason: Reason,
    passphraseConstraints: PassphraseConstraints?
) async -> ReasonHumanReadable {
    if let humanReadable = reason as? ReasonHumanReadable {
        return humanReadable
    }
    if reason is PresentmentUnlockReason {
        let subtitle: String
        if passphraseConstraints == nil {
            subtitle = NSLocalizedString(
                "key_unlock_present_bio_subtitle",
                value: "Authentication is required",
                comment: "Subtitle for biometric unlock when presenting a document"
            )
        } else if passphraseConstraints!.requireNumerical {
            subtitle = NSLocalizedString(
                "aks_unlock_present_pin_subtitle",
                value: "Enter the PIN associated with the document",
                comment: "Subtitle for PIN unlock when presenting a document"
            )
        } else {
            subtitle = NSLocalizedString(
                "key_unlock_present_passphrase_subtitle",
                value: "Enter the passphrase associated with the document",
                comment: "Subtitle for passphrase unlock when presenting a document"
            )
        }
        return ReasonHumanReadable(
            title: NSLocalizedString(
                "key_unlock_present_title",
                value: "Verify it's you to share the document",
                comment: "Title for unlock prompt when presenting a document"
            ),
            subtitle: subtitle,
            requireConfirmation: false
        )
    }
    if reason is FaceMatchingReason {
        return ReasonHumanReadable(
            title: NSLocalizedString(
                "face_matcher_prompt_default_title",
                value: "Verify it's you",
                comment: "Title for face matching prompt"
            ),
            subtitle: NSLocalizedString(
                "face_matcher_prompt_default_subtitle",
                value: "Look at the camera to verify your identity",
                comment: "Subtitle for face matching prompt"
            ),
            requireConfirmation: false
        )
    }
    return ReasonHumanReadable(
        title: NSLocalizedString(
            "key_unlock_default_title",
            value: "Verify it's you",
            comment: "Title for generic unlock prompt"
        ),
        subtitle: NSLocalizedString(
            "key_unlock_default_subtitle",
            value: "Authentication is required",
            comment: "Subtitle for generic unlock prompt"
        ),
        requireConfirmation: false
    )
}
