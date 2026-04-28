import SwiftUI
import Multipaz

struct DocumentStoreScreen: View {
    @Environment(ViewModel.self) private var viewModel

    @AppStorage("focusedDocumentId") private var focusedDocumentId: String = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Button(action: {
                    Task {
                        await viewModel.addSelfsignedMdoc(
                            documentType: DrivingLicense.shared.getDocumentType(locale: LocalizedStrings.shared.getCurrentLocale()),
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
                            documentType: PhotoID.shared.getDocumentType(locale: LocalizedStrings.shared.getCurrentLocale()),
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
                            documentType: EUPersonalID.shared.getDocumentType(locale: LocalizedStrings.shared.getCurrentLocale()),
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
                            documentType: AgeVerification.shared.getDocumentType(locale: LocalizedStrings.shared.getCurrentLocale()),
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
                
                CardCarousel(
                    cardInfos: viewModel.documentModel.documentInfos,
                    initialCardInfo: viewModel.documentModel.documentInfos.first { $0.identifier == focusedDocumentId },
                    allowReordering: true,
                    onCardClicked: { cardInfo in
                        let documentInfo = cardInfo as! DocumentInfo
                        viewModel.path.append(Destination.documentScreen(documentId: documentInfo.document.identifier))
                    },
                    onCardFocused: { cardInfo in
                        focusedDocumentId = cardInfo.identifier
                    },
                    onCardReordered: { cardInfo, oldPosition, newPosition in
                        let documentInfo = cardInfo as! DocumentInfo
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
                    selectedCardInfo: { cardInfo, documentIdx, numDocuments in
                        HStack {
                            if let documentInfo = cardInfo as? DocumentInfo {
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
                    emptyCardContent: {
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
