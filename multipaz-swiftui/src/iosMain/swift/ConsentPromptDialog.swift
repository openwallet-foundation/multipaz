import SwiftUI

private struct ConsentPromptDialogData: Identifiable, Equatable {
    let id = UUID()
    let state: PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialSelection>
}

struct ConsentPromptDialog: View {
    let model: ConsentPromptDialogModel

    @State private var data: ConsentPromptDialogData? = nil

    init(model: ConsentPromptDialogModel) {
        self.model = model
    }
    
    var body: some View {
        VStack {}
            .task {
                for await state in model.dialogState {
                    if state is PromptDialogModelNoDialogState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialSelection> {
                        data = nil
                    } else if state is PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialSelection> {
                        data = ConsentPromptDialogData(state: state as! PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialSelection>)
                    }
                }
            }
            .onChange(of: data) { oldValue, newValue in
                if newValue == nil {
                    oldValue?.state.resultChannel.close(cause: PromptDismissedException())
                }
            }
            .sheet(item: $data) { data in
                Consent(
                    consentData: data.state.parameters!.consentData,
                    requester: data.state.parameters!.requester,
                    trustedRequesterIdentity: data.state.parameters!.trustedRequesterIdentity,
                    onConfirm: { selection in
                        Task {
                            try await data.state.resultChannel.send(element: selection)
                            self.data = nil
                        }
                    },
                    onCancel: {
                        data.state.resultChannel.close(cause: PromptDismissedException())
                        self.data = nil
                    }
                )
                .presentationDragIndicator(.hidden)
            }
    }
}
