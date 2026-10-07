import SwiftUI
import Multipaz

struct CredentialScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    let documentId: String
    let credentialId: String
    
    var body: some View {
        let di = viewModel.documentModel.documentInfos.first {
            $0.document.identifier == documentId
        }
        let ci = di?.credentialInfos.first {
            $0.credential.identifier == credentialId
        }
        ScrollView {
            if let credentialInfo = ci {
                VStack(alignment: .leading, spacing: 20) {
                    KvPair("Type", string: credentialInfo.credential.credentialType)
                    KvPair("Identifier", string: credentialInfo.credential.identifier)
                    KvPair("Replacement for", string: credentialInfo.credential.replacementForIdentifier ?? "Not set")
                    KvPair("Domain", string: credentialInfo.credential.domain)
                    KvPair("Usage count", string: credentialInfo.credential.usageCount.formatted())
                    KvPair("Certified", bool: credentialInfo.credential.isCertified)
                    if (credentialInfo.credential.isCertified) {
                        KvPair("Issuer provided data", numBytes: credentialInfo.credential.issuerProvidedData.size)
                        KvPair("Valid from", instant: credentialInfo.credential.validFrom)
                        KvPair("Valid until", instant: credentialInfo.credential.validUntil)
                    }
                    if let mdocCredential = credentialInfo.credential as? MdocCredential {
                        KvPair("ISO mdoc DocType", string: mdocCredential.docType)
                        KvPair("ISO mdoc MSO size", numBytes: mdocCredential.issuerAuth.payload!.size)
                        KvPair("ISO mdoc DS key certificates", string: "Click to view")
                            .onTapGesture {
                                viewModel.path.append(Destination.certificateViewerScreen(
                                    certificates: mdocCredential.issuerCertChain.certificates
                                ))
                            }
                        KvPair("Key authorizations", attributedString: keyAuthorizationsText(mdocCredential: mdocCredential))
                    }
                    if let sdjwtVcCredential = credentialInfo.credential as? SdJwtVcCredential {
                        KvPair("SD-JWT verifiable credential type", string: sdjwtVcCredential.vct)
                    }
                    if let secureAreaBoundCredential = credentialInfo.credential as? SecureAreaBoundCredential {
                        KvPair("Secure area", string: secureAreaBoundCredential.secureArea.displayName)
                        KvPair("Device key algorithm", string: credentialInfo.keyInfo!.algorithm.description_)
                        KvPair("Device key invalidated", bool: credentialInfo.keyInvalidated)
                        KvPair("Device key attestation", string: "Click for details")
                            .onTapGesture {
                                print("TODO: show attestation")
                            }
                    }
                    KvPair("Claims", string: "Click for details")
                        .onTapGesture {
                            viewModel.path.append(Destination.claimsScreen(
                                documentId: di!.document.identifier,
                                credentialId: credentialInfo.credential.identifier
                            ))
                        }
                    
                    Button(
                        role: .destructive,
                        action: {
                            Task {
                                try await credentialInfo.credential.document.deleteCredential(
                                    credentialIdentifier: credentialInfo.credential.identifier
                                )
                            }
                            viewModel.path.removeLast()
                        }
                    ) {
                        Text("Delete credential")
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .navigationTitle("Credential")
        .padding()
    }
}

private func keyAuthorizationsText(mdocCredential: MdocCredential) -> AttributedString {
    do {
        let mso = mdocCredential.mso
        let authorizedNamespaces = mso.deviceKeyAuthorizedNamespaces
        let authorizedDataElements = mso.deviceKeyAuthorizedDataElements
        if authorizedNamespaces.isEmpty && authorizedDataElements.isEmpty {
            return AttributedString("None")
        }
        var text = AttributedString()
        var firstSection = true
        if !authorizedNamespaces.isEmpty {
            firstSection = false
            var header = AttributedString("Namespaces:")
            header.inlinePresentationIntent = .stronglyEmphasized
            text.append(header)
            for ns in authorizedNamespaces {
                text.append(AttributedString("\n• \(ns)"))
            }
        }
        if !authorizedDataElements.isEmpty {
            if !firstSection {
                text.append(AttributedString("\n\n"))
            }
            var header = AttributedString("Data Elements:")
            header.inlinePresentationIntent = .stronglyEmphasized
            text.append(header)
            for (ns, elements) in authorizedDataElements.sorted(by: { $0.key < $1.key }) {
                text.append(AttributedString("\n• \(ns):"))
                for elem in elements {
                    text.append(AttributedString("\n  - \(elem)"))
                }
            }
        }
        return text
    } catch {
        return AttributedString("Error parsing MSO")
    }
}
