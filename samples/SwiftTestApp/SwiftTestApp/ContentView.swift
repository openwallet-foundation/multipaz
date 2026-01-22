import SwiftUI
import Multipaz
import MultipazSwift

struct ContentView: View {
    @State private var viewModel = ViewModel()
    
    @State private var qrCode: UIImage? = nil
    
    var body: some View {
        
        NavigationStack(path: $viewModel.path) {
            VStack {
                if (viewModel.isLoading) {
                    VStack {
                        ProgressView()
                    }
                } else {
                    StartScreen()
                }
            }
            .navigationDestination(for: Destination.self) { destination in
                PromptDialogs(promptModel: viewModel.promptModel)
                switch destination {
                case .startScreen: StartScreen()
                case .aboutScreen: AboutScreen()
                case .documentStoreScreen: DocumentStoreScreen()
                case .documentScreen(let documentInfo): DocumentScreen(documentInfo: documentInfo)
                case .credentialScreen(let credentialInfo): CredentialScreen(credentialInfo: credentialInfo)
                case .claimsScreen(let credentialInfo): ClaimsScreen(credentialInfo: credentialInfo)
                case .consentPromptScreen: ConsentPromptScreen()
                case .passphrasePromptScreen: PassphrasePromptScreen()
                case .iso18013ProximityPresentmentScreen: Iso18013ProximityPresentmentScreen()
                case .certificateViewerScreen(let certificates): CertificateViewerScreen(certificates: certificates)
                case .certificateExamplesScreen: CertificateExamplesScreen()
                }
            }
        }
        .environment(viewModel)
        .onAppear { Task { await viewModel.load() } }
    }
}
