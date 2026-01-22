import SwiftUI
@preconcurrency import Multipaz

private struct ConsentPromptDialogData: Identifiable, Equatable {
    let id = UUID()
    let state: PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialPresentmentSelection>
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
                    if state is PromptDialogModelNoDialogState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialPresentmentSelection> {
                        data = nil
                    } else if state is PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialPresentmentSelection> {
                        data = ConsentPromptDialogData(state: state as! PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, CredentialPresentmentSelection>)
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
                    credentialPresentmentData: data.state.parameters!.credentialPresentmentData,
                    requester: data.state.parameters!.requester,
                    trustMetadata: data.state.parameters!.trustMetadata,
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
