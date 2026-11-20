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
                        let shown = state as! PromptDialogModelDialogShownState<ConsentPromptDialogModel.ConsentPromptRequest, PresentmentSelection>
                        // A renderer made for another toolkit is no renderer here: a type whose data the
                        // person must see is refused rather than drawn with the built-in rendering.
                        let params = shown.parameters!
                        let missingRenderer = TransactionDataRendererRequirementKt.missingTransactionDataRenderer(
                            transactionData: params.presentmentData.allTransactionData,
                            repository: params.transactionDataRendererRepository,
                            isUsable: { KotlinBoolean(bool: $0 is TransactionDataRendererSwiftUi) }
                        )
                        if let missingRenderer {
                            shown.resultChannel.close(cause: TransactionDataRendererNotAvailableException(typeIdentifier: missingRenderer))
                        } else {
                            data = ConsentPromptDialogData(state: shown)
                        }
                    }
                }
            }
            .onChange(of: data) { oldValue, newValue in
                if newValue == nil {
                    oldValue?.state.resultChannel.close(cause: PromptDismissedException())
                }
            }
            .sheet(item: $data) { data in
                let params = data.state.parameters!
                Consent(
                    presentmentData: params.presentmentData,
                    requester: params.requester,
                    trustedRequesterIdentity: params.trustedRequesterIdentity,
                    preselectedDocuments: params.preselectedDocuments,
                    transactionDataRendererRepository: params.transactionDataRendererRepository,
                    requesterHistory: params.requesterHistory,
                    classifyPurposesFn: params.classifyPurposesFn,
                    classifyRiskFn: params.classifyRiskFn,
                    onDocumentsInFocus: { documents in
                        params.onDocumentsInFocus(documents)
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
