import SwiftUI
import Multipaz
import MultipazSwift

struct DocumentStoreScreen: View {
    @Environment(ViewModel.self) private var viewModel

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

                let numDocs = viewModel.documentModel.documentInfos.count
                let docWord = if (numDocs == 1) { "document" } else { "documents" }
                Text("\(numDocs) \(docWord) in DocumentStore")
                    .font(.headline)
                    .bold()
                
                ForEach(viewModel.documentModel.documentInfos, id: \.self) { documentInfo in
                    HStack {
                        Image(uiImage: documentInfo.cardArt)
                            .resizable()
                            .scaledToFit()
                            .frame(height: 40)
                        Text(documentInfo.document.displayName ?? "(No displayName)")
                    }
                    .onTapGesture {
                        viewModel.path.append(Destination.documentScreen(documentInfo: documentInfo))
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Document Store")
        .padding()
    }
}
