import SwiftUI
import Multipaz

public struct PromptDialogs: View {
    let promptModel: PromptModel
    
    public init(promptModel: PromptModel) {
        self.promptModel = promptModel
    }
    
    public var body: some View {
        PassphrasePromptDialog(model: promptModel.getPassphraseDialogModel())
        ConsentPromptDialog(model: promptModel.getConsentPromptDialogModel())
    }
}
