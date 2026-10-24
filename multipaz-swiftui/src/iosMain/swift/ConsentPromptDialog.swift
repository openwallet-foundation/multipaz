import SwiftUI

private struct ConsentPromptDialogData: Identifiable, Equatable {
    let id = UUID()
    let state: PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, PresentmentSelection>
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
                    if state is PromptDialogModelNoDialogState<ConsentPromptDialogModel.ConsentPromptRequest, PresentmentSelection> {
                        data = nil
                    } else if state is PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, PresentmentSelection> {
                        data = ConsentPromptDialogData(state: state as! PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, PresentmentSelection>)
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
                    presentmentData: data.state.parameters!.presentmentData,
                    requester: data.state.parameters!.requester,
                    trustedRequesterIdentity: data.state.parameters!.trustedRequesterIdentity,
                    preselectedDocuments: data.state.parameters!.preselectedDocuments,
                    transactionDataRendererRepository: data.state.parameters!.transactionDataRendererRepository,
                    onDocumentsInFocus: { documents in
                        data.state.parameters!.onDocumentsInFocus(documents)
                    },
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
