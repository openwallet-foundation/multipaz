import SwiftUI
import Multipaz

struct DocumentScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    let documentId: String

    init(documentId: String) {
        self.documentId = documentId
    }
    
    var documentInfo: DocumentInfo? {
        viewModel.documentModel.documentInfos.first {
            $0.document.identifier == documentId
        }
    }
    
    var body: some View {
        ScrollView {
            if let documentInfo = documentInfo {
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
                            if (credentialInfo.credential.domain == domain) {
                                let trailingType = if credentialInfo.credential.isCertified {
                                    ""
                                } else {
                                    " (Pending)"
                                }
                                KvPair(
                                    credentialInfo.credential.credentialType + trailingType,
                                    string: "Usage count \(credentialInfo.credential.usageCount). Click for details"
                                )
                                .padding(.leading, 20)
                                .onTapGesture {
                                    viewModel.path.append(Destination.credentialScreen(
                                        documentId: documentInfo.document.identifier,
                                        credentialId: credentialInfo.credential.identifier
                                    ))
                                }
                            }
                        }
                    }
                    VStack {
                        Button(
                            action: {
                                Task {
                                    let authorizationData = documentInfo.document.authorizationData
                                    if authorizationData == nil {
                                        print("No authorizationData for credential")
                                    } else {
                                        do {
                                            try await viewModel.provisioningModel.openID4VCIRefreshCredentials(
                                                document: documentInfo.document,
                                                authorizationData: authorizationData!,
                                                clientPreferences: viewModel.provisioningSupport.getOpenID4VCIClientPreferences(),
                                                backend: viewModel.provisioningSupport.getOpenID4VCIBackend()
                                            )
                                        } catch {
                                            print("Error refreshing: \(error)")
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Refresh credentials")
                        }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                        
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
        }
        .navigationTitle("Document")
        .padding()
    }
}
