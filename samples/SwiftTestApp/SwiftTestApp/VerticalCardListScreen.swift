import SwiftUI
import Multipaz

struct VerticalCardListScreen: View {
    @Environment(ViewModel.self) private var viewModel

    let focusedDocumentId: String?
    let animateListTransitions: Bool

    init(focusedDocumentId: String? = nil, animateListTransitions: Bool = false) {
        self.focusedDocumentId = focusedDocumentId
        self.animateListTransitions = animateListTransitions
    }

    private var focusedDocument: DocumentInfo? {
        viewModel.documentModel.documentInfos.first {
            $0.document.identifier == focusedDocumentId
        }
    }

    private var isPreviousScreenCardList: Bool {
        if viewModel.path.count >= 2 {
            if case .verticalCardListScreen = viewModel.path[viewModel.path.count - 2] {
                return true
            }
        }
        return false
    }

    private func handleBack() {
        if isPreviousScreenCardList {
            viewModel.verticalCardListState.unfocus {
                viewModel.popWithoutAnimation()
            }
        } else {
            viewModel.path.removeLast()
        }
    }

    var body: some View {
        @Bindable var listState = viewModel.verticalCardListState
        VStack {
            VerticalCardList(
                cardInfos: viewModel.documentModel.documentInfos,
                focusedCard: focusedDocument,
                unfocusedVisiblePercent: 25, // Show a bit more of the overlapping cards
                allowCardReordering: true,
                showStackWhileFocused: true,
                paddingTop: 16,
                paddingBottom: 16,
                state: listState,
                animateListTransitions: animateListTransitions,
                topContent: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Top Content Demo")
                            .font(.headline)
                            .bold()
                        Text("This content appears above cards when no card is focused and is hidden when a card is focused.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .cornerRadius(12)
                },
                showCardInfo: { cardInfo in
                    let docInfo = cardInfo as! DocumentInfo
                    VStack {
                        Text("\(docInfo.document.displayName ?? "Document") is focused")
                        Spacer()
                        Button(action: {
                            viewModel.push(.documentScreen(documentId: docInfo.document.identifier))
                        }) {
                            Text("More info")
                                .cornerRadius(12)
                        }
                    }
                },
                emptyContent: {
                    // This view appears inside the dashed placeholder
                    VStack(spacing: 12) {
                        Image(systemName: "plus.rectangle.on.rectangle")
                            .font(.system(size: 32))
                            .foregroundColor(.gray)
                        Text("No Documents")
                            .font(.headline)
                            .foregroundColor(.gray)
                        Text("Tap to add your first pass or ID")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                },
                onCardReordered: { cardInfo, newIndex in
                    let document = cardInfo as! DocumentInfo
                    print("User moved \(document.document.displayName ?? "card") to index \(newIndex)")
                    Task {
                        try? await viewModel.documentModel.setDocumentPosition(documentInfo: document, position: newIndex)
                    }
                },
                onCardFocused: { cardInfo in
                    viewModel.push(.verticalCardListScreen(
                        focusedDocumentId: cardInfo.identifier,
                        animateListTransitions: true
                    ))
                },
                onCardFocusedTapped: { _ in
                    handleBack()
                },
                onCardFocusedStackTapped: { _ in
                    handleBack()
                }
            )
        }
        .id(focusedDocumentId ?? "root")
        .navigationBarBackButtonHidden(focusedDocumentId != nil)
        .navigationTitle("Vertical Card List")
        .toolbar {
            if focusedDocumentId != nil {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: {
                        handleBack()
                    }) {
                        Image(systemName: "chevron.backward")
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                TopBarActionsView(
                    state: viewModel.verticalCardListState,
                    documentModel: viewModel.documentModel
                )
            }
        }
        .background {
            ScreenEdgeSwipeGesture(isEnabled: focusedDocumentId != nil) {
                handleBack()
            }
        }
    }
}

private struct TopBarActionsView: View {
    @Bindable var state: VerticalCardListState
    let documentModel: DocumentModel

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 4) {
                Text("Top Content")
                    .font(.caption2)
                    .lineLimit(1)
                    .fixedSize()
                Toggle("", isOn: Binding(
                    get: { state.showTopContent },
                    set: { newValue in
                        withAnimation(.easeInOut(duration: 0.38)) {
                            state.showTopContent = newValue
                        }
                    }
                ))
                .toggleStyle(.switch)
                .labelsHidden()
                .controlSize(.small)
            }
            Button(action: {
                Task {
                    try? await documentModel.setDocumentOrder(
                        documentOrder: documentModel.documentOrder.shuffled()
                    )
                }
            }) {
                Text("Shuffle")
                    .font(.caption)
            }
        }
    }
}

private struct ScreenEdgeSwipeGesture: UIViewRepresentable {
    let isEnabled: Bool
    let action: () -> Void

    func makeUIView(context: Context) -> GestureView {
        let view = GestureView()
        view.coordinator = context.coordinator
        return view
    }

    func updateUIView(_ uiView: GestureView, context: Context) {
        context.coordinator.action = action
        context.coordinator.isEnabled = isEnabled
        uiView.updateGesture()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(isEnabled: isEnabled, action: action)
    }

    class GestureView: UIView {
        var coordinator: Coordinator?
        private var panGesture: UIPanGestureRecognizer?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            updateGesture()
        }

        func updateGesture() {
            guard let window = self.window, let coordinator = coordinator else { return }
            if panGesture == nil {
                let gesture = UIPanGestureRecognizer(target: coordinator, action: #selector(Coordinator.handlePan(_:)))
                gesture.delegate = coordinator
                window.addGestureRecognizer(gesture)
                panGesture = gesture
            }
            panGesture?.isEnabled = coordinator.isEnabled
        }

        override func willMove(toWindow newWindow: UIWindow?) {
            super.willMove(toWindow: newWindow)
            if newWindow == nil, let gesture = panGesture, let window = self.window {
                window.removeGestureRecognizer(gesture)
                panGesture = nil
            }
        }
    }

    class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var isEnabled: Bool
        var action: () -> Void
        private var isTriggered = false

        init(isEnabled: Bool, action: @escaping () -> Void) {
            self.isEnabled = isEnabled
            self.action = action
        }

        @objc func handlePan(_ recognizer: UIPanGestureRecognizer) {
            guard isEnabled else { return }
            let translation = recognizer.translation(in: recognizer.view)
            let velocity = recognizer.velocity(in: recognizer.view)

            switch recognizer.state {
            case .changed:
                if !isTriggered && translation.x > 30 && velocity.x > 80 && abs(translation.x) > abs(translation.y) * 1.2 {
                    isTriggered = true
                    action()
                }
            case .ended, .cancelled, .failed:
                isTriggered = false
            default:
                break
            }
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            return true
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard isEnabled, let pan = gestureRecognizer as? UIPanGestureRecognizer, let view = pan.view else { return false }
            let velocity = pan.velocity(in: view)
            return velocity.x > 0 && abs(velocity.x) > abs(velocity.y) * 1.2
        }
    }
}

// A sample detail view to inject into the `showDocumentInfo` slot
struct DocumentDetailCard: View {
    let docInfo: DocumentInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(docInfo.document.displayName ?? "Unknown Document")
                .font(.title2)
                .bold()

            Divider()

            if docInfo.credentialInfos.isEmpty {
                Text("No credentials found on this document.")
                    .foregroundColor(.secondary)
            } else {
                ForEach(docInfo.credentialInfos, id: \.credential.identifier) { cred in
                    HStack {
                        VStack(alignment: .leading) {
                            Text("Credential")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Text(cred.credential.identifier)
                                .font(.body)
                        }
                        Spacer()
                        if cred.keyInvalidated {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.red)
                        } else {
                            Image(systemName: "checkmark.seal.fill")
                                .foregroundColor(.green)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            Button(action: {
                // Handle action
            }) {
                Text("View Full Details")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .padding(.top, 8)
        }
        .padding()
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(20)
        .padding(.horizontal, 16)
        .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 5)
    }
}
