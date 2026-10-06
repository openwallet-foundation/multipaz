import SwiftUI

public struct PromptDialogs: View {
    let promptModel: PromptModel
    let toHumanReadable: @MainActor (Reason, PassphraseConstraints?) async -> ReasonHumanReadable

    public init(
        promptModel: PromptModel,
        toHumanReadable: @escaping @MainActor (Reason, PassphraseConstraints?) async -> ReasonHumanReadable = defaultToHumanReadable
    ) {
        self.promptModel = promptModel
        self.toHumanReadable = toHumanReadable
    }

    public var body: some View {
        PassphrasePromptDialog(
            model: promptModel.getPassphraseDialogModel(),
            toHumanReadable: toHumanReadable
        )
        ConsentPromptDialog(model: promptModel.getConsentPromptDialogModel())
        FaceMatcherPromptDialog(
            model: promptModel.getFaceMatcherDialogModel(),
            toHumanReadable: toHumanReadable
        )
    }
}
