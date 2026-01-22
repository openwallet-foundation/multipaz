import SwiftUI

struct StartScreen: View {
    @Environment(ViewModel.self) private var viewModel
    
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Button(action: { viewModel.path.append(Destination.aboutScreen) }) {
                    Text("About")
                }
                Button(action: { viewModel.path.append(Destination.certificateExamplesScreen) }) {
                    Text("Certificate Viewer")
                }
                Button(action: { viewModel.path.append(Destination.documentStoreScreen) }) {
                    Text("Document Store")
                }
                Button(action: { viewModel.path.append(Destination.consentPromptScreen) }) {
                    Text("Consent Prompt")
                }
                Button(action: { viewModel.path.append(Destination.passphrasePromptScreen) }) {
                    Text("Passphrase Prompt")
                }
                Button(action: { viewModel.path.append(Destination.iso18013ProximityPresentmentScreen) }) {
                    Text("ISO 18013-5 Proximity Presentment")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
    }
}
