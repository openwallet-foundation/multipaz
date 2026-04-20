import SwiftUI
import Multipaz

struct ClaimsScreen: View {
    
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
                let tz = TimeZone.Companion.shared.currentSystemDefault()
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(credentialInfo.claims, id:\.self) { claim in
                        if claim.attribute?.type is DocumentAttributeType.Picture {
                            if let mdocClaim = claim as? MdocClaim {
                                // Some picture attributes might be set to Simple.NULL value, handle this
                                if mdocClaim.value is Bstr {
                                    KvPair(
                                        claim.displayName,
                                        encodedImage: (mdocClaim.value as! Bstr).value.toNSData()
                                    )
                                } else {
                                    KvPair(claim.displayName, string: claim.render(timeZone: tz))
                                }
                            } else if let jsonClaim = claim as? JsonClaim {
                                KvPair(
                                    claim.displayName,
                                    encodedImage: jsonClaim.value.jsonPrimitive.content.fromBase64Url().toNSData()
                                )
                            }
                        } else {
                            KvPair(claim.displayName, string: claim.render(timeZone: tz))
                        }
                    }
                }
            }
        }
        .navigationTitle("Claims")
        .padding()
    }
}
