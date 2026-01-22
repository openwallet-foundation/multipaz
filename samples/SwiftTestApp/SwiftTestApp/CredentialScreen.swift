import SwiftUI
import Multipaz
import MultipazSwift

struct CredentialScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    let credentialInfo: CredentialInfo
    
    var body: some View {
        
        ScrollView {
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
                        viewModel.path.append(Destination.claimsScreen(credentialInfo: credentialInfo))
                    }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Credential")
        .padding()
    }
}
