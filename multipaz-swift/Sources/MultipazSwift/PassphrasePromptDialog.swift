import SwiftUI
@preconcurrency import Multipaz

private struct PassphrasePromptDialogData: Identifiable, Equatable {
    let id = UUID()
    let state: PromptDialogModelDialogShownState<PassphrasePromptDialogModel.PassphraseRequest, NSString>
}

struct PassphrasePromptDialog: View {
    let model: PassphrasePromptDialogModel

    @State private var data: PassphrasePromptDialogData? = nil
    
    init(model: PassphrasePromptDialogModel) {
        self.model = model
    }
    
    var body: some View {
        VStack {}
        .task {
            for await state in model.dialogState {
                if state is PromptDialogModelNoDialogState<PassphrasePromptDialogModel.PassphraseRequest, NSString> {
                    data = nil
                } else if state is PromptDialogModelDialogShownState<PassphrasePromptDialogModel.PassphraseRequest, NSString> {
                    data = PassphrasePromptDialogData(state: state as!
                        PromptDialogModelDialogShownState<PassphrasePromptDialogModel.PassphraseRequest,NSString>)
                }
            }
        }
        .onChange(of: data) { oldValue, newValue in
            if newValue == nil {
                oldValue?.state.resultChannel.close(cause: PromptDismissedException())
            }
        }
        .sheet(item: $data) { data in
            SmartSheet(maxHeight: .infinity) {
                HStack {
                    Spacer()
                    Button {
                        data.state.resultChannel.close(cause: PromptDismissedException())
                        self.data = nil
                    } label: {
                        Image(systemName: "xmark")
                            .font(.title2)
                            .foregroundStyle(.black)
                            .padding()
                    }
                }
            } content: {
                PassphraseInputView(
                    title: data.state.parameters!.title,
                    subtitle: data.state.parameters!.subtitle,
                    constraints: data.state.parameters!.passphraseConstraints,
                    passphraseEvaluator: { enteredPassphrase in
                        let evaluation = try! await data.state.parameters?.passphraseEvaluator?.invoke(p1: enteredPassphrase)
                        let kfType = if data.state.parameters!.passphraseConstraints.requireNumerical {
                            "PIN"
                        } else {
                            "passphrase"
                        }
                        if (evaluation is PassphraseEvaluation.OK) {
                            return nil
                        } else if (evaluation is PassphraseEvaluation.TooManyAttempts) {
                            data.state.resultChannel.close(cause: PromptDismissedException())
                            self.data = nil
                            return "Too many attempts"
                        } else if (evaluation is PassphraseEvaluation.TryAgain) {
                            return "Wrong \(kfType), try again"
                        } else if (evaluation is PassphraseEvaluation.TryAgainAttemptsRemain) {
                            let remaining = (evaluation as! PassphraseEvaluation.TryAgainAttemptsRemain).remainingAttempts
                            if remaining > 1 {
                                return "Wrong \(kfType), try again, \(remaining) attempts left"
                            } else {
                                return "Wrong \(kfType), try again, \(remaining) attempt left"
                            }
                        }
                        assertionFailure("Shouldn't get here, evaluation: \(evaluation)")
                        return nil
                    },
                    onSuccess: { enteredPassphrase in
                        Task {
                            try! await data.state.resultChannel.send(element: enteredPassphrase)
                            self.data = nil
                        }
                    }
                )
            } footer: { isAtBottom, scrollDown in
            }
            .presentationDragIndicator(.hidden)
        }
    }
}
