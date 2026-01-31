import SwiftUI
import Multipaz
import MultipazSwift

struct DocumentStoreScreen: View {
    @Environment(ViewModel.self) private var viewModel

    @AppStorage("focusedDocumentId") private var focusedDocumentId: String = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Button(action: {
                    Task {
                        await viewModel.addSelfsignedMdoc(
                            documentType: DrivingLicense.shared.getDocumentType(),
                            displayName: "Erika's Driving License",
                            typeDisplayName: "Utopia Driving License",
                            cardArtResourceName: "driving_license_card_art"
                        )
                    }
                }) {
                    Text("Add self-signed mDL")
                }
                Button(action: {
                    Task {
                        await viewModel.addSelfsignedMdoc(
                            documentType: PhotoID.shared.getDocumentType(),
                            displayName: "Erika's PhotoID",
                            typeDisplayName: "Utopia PhotoID",
                            cardArtResourceName: "photo_id_card_art"
                        )
                    }
                }) {
                    Text("Add self-signed PhotoID")
                }
                Button(action: {
                    Task {
                        await viewModel.addSelfsignedMdoc(
                            documentType: EUPersonalID.shared.getDocumentType(),
                            displayName: "Erika's PID",
                            typeDisplayName: "Utopia PID",
                            cardArtResourceName: "pid_card_art"
                        )
                    }
                }) {
                    Text("Add self-signed PID")
                }
                Button(action: {
                    Task {
                        await viewModel.addSelfsignedMdoc(
                            documentType: AgeVerification.shared.getDocumentType(),
                            displayName: "Erika's Age Verification Credential",
                            typeDisplayName: "Utopia Age Verification Credential",
                            cardArtResourceName: "av18_card_art"
                        )
                    }
                }) {
                    Text("Add self-signed Age Verification Credential")
                }
                
                Button(
                    role: .destructive,
                    action: {
                        Task {
                            for document in try! await viewModel.documentStore.listDocuments(sort: true) {
                                try! await viewModel.documentStore.deleteDocument(identifier: document.identifier)
                            }
                        }
                    }
                ) {
                    Text("Delete all documents")
                }
                
                DocumentCarousel(
                    documentModel: viewModel.documentModel,
                    initialDocumentId: focusedDocumentId,
                    allowReordering: true,
                    onDocumentClicked: { documentInfo in
                        viewModel.path.append(Destination.documentScreen(documentInfo: documentInfo))
                    },
                    onDocumentFocused: { documentInfo in
                        focusedDocumentId = documentInfo.document.identifier
                    },
                    onDocumentReordered: { documentInfo, oldPosition, newPosition in
                        Task {
                            do {
                                try await viewModel.documentModel.setDocumentPosition(
                                    documentInfo: documentInfo,
                                    position: newPosition
                                )
                            } catch {
                                print("Error setting document position: \(error)")
                            }
                        }
                    },
                    selectedDocumentInfo: { documentInfo, documentIdx, numDocuments in
                        HStack {
                            if let documentInfo = documentInfo {
                                Text("\(documentIdx + 1) of \(numDocuments): " +
                                     (documentInfo.document.displayName ?? "(No displayName)")
                                )
                                .font(.subheadline)
                                .bold()
                            } else {
                                Text("Drag to reorder")
                                    .font(.subheadline)
                                    .bold()
                            }
                        }
                    },
                    emptyDocumentContent: {
                        Text("No documents in store")
                            .foregroundStyle(Color.secondary)
                    }
                )
            }
            .padding()
        }
        .navigationTitle("Document Store")
    }
}
