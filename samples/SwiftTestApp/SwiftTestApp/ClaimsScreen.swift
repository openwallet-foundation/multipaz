import SwiftUI
import Multipaz
import MultipazSwift

struct ClaimsScreen: View {
    
    @Environment(ViewModel.self) private var viewModel
    
    let credentialInfo: CredentialInfo
    
    var body: some View {
        
        ScrollView {
            let tz = TimeZone.Companion.shared.currentSystemDefault()
            VStack(alignment: .leading, spacing: 20) {
                ForEach(credentialInfo.claims, id:\.self) { claim in
                    if claim.attribute?.type is DocumentAttributeType.Picture {
                        if let mdocClaim = claim as? MdocClaim {
                            // Handle some picture attributes might be set to Simple.NULL value
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
        .navigationTitle("Claims")
        .padding()
    }
}
