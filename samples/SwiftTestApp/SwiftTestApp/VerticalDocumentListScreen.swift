import SwiftUI
import Multipaz

struct VerticalDocumentListScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    @SceneStorage("focusedDocumentId") private var focusedDocumentId: String?

    @SceneStorage("focusedDocumentShowMoreInfo") private var focusedDocumentShowMoreInfo: Bool = false

    @Environment(\.dismiss) private var dismiss
    
    var body: some View {
        let focusedDocument = viewModel.documentModel.documentInfos.first {
            $0.document.identifier == focusedDocumentId
        }
        VStack {
            VerticalDocumentList(
                documentModel: viewModel.documentModel,
                focusedDocument: focusedDocument,
                unfocusedVisiblePercent: 25, // Show a bit more of the overlapping cards
                allowDocumentReordering: true,
                showStackWhileFocused: true,
                showDocumentInfo: { docInfo in
                    VStack {
                        Text("\(docInfo.document.displayName ?? "Document") is focused")
                        Spacer()
                        if (!focusedDocumentShowMoreInfo) {
                            Text("Tap card for more info")
                        } else {
                            Button(action: {
                                viewModel.path.append(Destination.documentScreen(documentId: docInfo.document.identifier))
                            }) {
                                Text("Even more info")
                                    .cornerRadius(12)
                            }
                            .padding(.top, 8)
                        }
                    }
                },
                emptyDocumentContent: {
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
                onDocumentReordered: { document, newIndex in
                    print("User moved \(document.document.displayName ?? "card") to index \(newIndex)")
                    Task {
                        try? await viewModel.documentModel.setDocumentPosition(documentInfo: document, position: newIndex)
                    }
                },
                onDocumentFocused: { documentInfo in
                    focusedDocumentId = documentInfo.document.identifier
                },
                onDocumentFocusedTapped: { _ in
                    focusedDocumentShowMoreInfo = true
                },
                onDocumentFocusedStackTapped: { _ in
                    focusedDocumentId = nil
                }
            )
        }
        .navigationTitle(focusedDocument != nil
                         ? (focusedDocumentShowMoreInfo ? "Document Focused (more)" : "Document Focused")
                         : "Vertical Document List"
        )
        // Override back button to unfocus...
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(action: {
                    if focusedDocumentId != nil {
                        if (focusedDocumentShowMoreInfo) {
                            focusedDocumentShowMoreInfo = false
                        } else {
                            focusedDocumentId = nil
                        }
                    } else {
                        dismiss()
                    }
                }) {
                    // Recreate the native iOS back button appearance
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 17, weight: .semibold))
                    }
                }
            }
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
