import SwiftUI
import Multipaz
import MultipazSwift

struct DocumentScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    let documentInfo: DocumentInfo

    init(documentInfo: DocumentInfo) {
        self.documentInfo = documentInfo
    }
    
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(uiImage: documentInfo.cardArt)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity)
                KvPair("Document Name", string: documentInfo.document.displayName ?? "Unknown")
                KvPair("Document Type", string: documentInfo.document.typeDisplayName ?? "Unknown")
                KvPair("Identifier", string: documentInfo.document.identifier)
                KvPair("Created", instant: documentInfo.document.created)
                KvPair("Provisioned", bool: documentInfo.document.provisioned)
                KvPair("Card art", numBytes: documentInfo.document.cardArt?.size ?? -1)
                KvPair("Issuer logo", numBytes: documentInfo.document.issuerLogo?.size ?? -1)
                KvPair(
                    "Authorization data",
                    numBytes: documentInfo.document.authorizationData?.size ?? -1
                )
                KvPair("Credentials")
                let domains = Set(documentInfo.credentialInfos.map(\.credential.domain))
                ForEach(domains.sorted(), id: \.self) { domain in
                    Text(domain + " domain").italic()
                        .padding(.leading, 10)
                    ForEach(documentInfo.credentialInfos, id: \.self) { credentialInfo in
                        KvPair(
                            credentialInfo.credential.credentialType,
                            string: "Usage count \(credentialInfo.credential.usageCount). Click for details"
                        )
                        .padding(.leading, 20)
                        .onTapGesture {
                            viewModel.path.append(Destination.credentialScreen(credentialInfo: credentialInfo))
                        }
                    }
                }
                VStack {
                    Button(
                        role: .destructive,
                        action: {
                            Task {
                                try await viewModel.documentStore.deleteDocument(identifier: documentInfo.document.identifier)
                            }
                            viewModel.path.removeLast()
                        }
                    ) {
                        Text("Delete document")
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                }
                .frame(maxWidth: .infinity)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Document")
        .padding()
    }
}
