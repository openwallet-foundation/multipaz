import SwiftUI
import Multipaz

struct ContentView: View {
    @State private var viewModel = ViewModel()
    
    @State private var qrCode: UIImage? = nil
    
    @State private var provisioningIsActive: Bool = false

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
                case .verticalDocumentListScreen: VerticalDocumentListScreen()
                case .credentialScreen(let credentialInfo): CredentialScreen(credentialInfo: credentialInfo)
                case .claimsScreen(let credentialInfo): ClaimsScreen(credentialInfo: credentialInfo)
                case .consentPromptScreen: ConsentPromptScreen()
                case .passphrasePromptScreen: PassphrasePromptScreen()
                case .iso18013ProximityPresentmentScreen: Iso18013ProximityPresentmentScreen()
                case .certificateViewerScreen(let certificates): CertificateViewerScreen(certificates: certificates)
                case .certificateExamplesScreen: CertificateExamplesScreen()
                }
            }
            .sheet(
                isPresented: $provisioningIsActive,
                onDismiss: {
                    print("Canceling provisioning")
                    viewModel.provisioningModel.cancel()
                }
            ) {
                NavigationStack {
                    VStack {
                        ProvisioningView(
                            provisioningModel: viewModel.provisioningModel,
                            waitForRedirectLinkInvocation: { state in
                                return await viewModel.provisioningSupport.waitForAppLinkInvocation(state: state)
                            }
                        )
                    }
                    .navigationTitle("Provisioning")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button {
                                viewModel.provisioningModel.cancel()
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.gray)
                            }
                        }
                    }
                }
            }
        }
        .environment(viewModel)
        .onAppear {
            Task {
                await viewModel.load()
                for await newState in viewModel.provisioningModel.state {
                    if newState is ProvisioningModel.Idle {
                        provisioningIsActive = false
                    } else {
                        provisioningIsActive = true
                    }
                }
            }
        }
        .onOpenURL { url in
            print("handling \(url)")
            if url.absoluteString.starts(with: "openid-credential-offer://") ||
                url.absoluteString.starts(with: "haip-vci://") {
                Task {
                    //await viewModel.provisioningSupport.processAppLinkInvocation(
                    //    url: url.absoluteString
                    //)
                    viewModel.provisioningModel.launchOpenID4VCIProvisioning(
                        offerUri: url.absoluteString,
                        clientPreferences: viewModel.provisioningSupport.getOpenID4VCIClientPreferences(),
                        backend: viewModel.provisioningSupport.getOpenID4VCIBackend()
                    )
                }
            } else {
                print("Unhandled URL \(url)")
            }
        }
    }
}
