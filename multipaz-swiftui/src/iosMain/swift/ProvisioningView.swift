import SwiftUI

/// A UI Panel (implemented as a SwiftUI `VStack`) that interacts with the user and
/// drives credential provisioning via the provided ``ProvisioningModel``.
public struct ProvisioningView: View {
    
    /// The model managing the credential provisioning logic.
    public let provisioningModel: ProvisioningModel
    
    /// A closure that waits for a redirect URL with the given state parameter to be navigated to in the browser.
    ///
    /// - Parameter state: The unique state string passed to the authorization URL.
    /// - Returns: The full redirect URL containing the matching state.
    public var waitForRedirectLinkInvocation: (String) async -> String
    
    /// The current state of the provisioning flow, mirrored from the Kotlin model.
    ///
    /// This state is updated asynchronously via the `.task` modifier.
    @State private var currentState: ProvisioningModel.State?
    
    @Environment(\.openURL) private var openURL
    
    /// Initializes the Provisioning view.
    ///
    /// - Parameters:
    ///   - provisioningModel: The model that manages credential provisioning.
    ///   - waitForRedirectLinkInvocation: The async callback for handling deep links.
    public init(
        provisioningModel: ProvisioningModel,
        waitForRedirectLinkInvocation: @escaping (String) async -> String
    ) {
        self.provisioningModel = provisioningModel
        self.waitForRedirectLinkInvocation = waitForRedirectLinkInvocation
        _currentState = State(initialValue: provisioningModel.state.value)
    }
    
    public var body: some View {
        VStack {
            if let state = currentState {
                content(for: state)
            } else {
                ProgressView()
                    .onAppear {
                        currentState = provisioningModel.state.value
                    }
            }
        }
        .padding()
        .task {
            for await newState in provisioningModel.state {
                self.currentState = newState
            }
        }
    }
    
    @ViewBuilder
    private func content(for state: ProvisioningModel.State) -> some View {
        switch state {
        case let authorizingState as ProvisioningModel.Authorizing:
            Authorize(
                provisioningModel: provisioningModel,
                waitForRedirectLinkInvocation: waitForRedirectLinkInvocation,
                challenges: authorizingState.authorizationChallenges
            )
            
        case let errorState as ProvisioningModel.Error:
            ProvisioningErrorView(error: errorState.err)

        case is ProvisioningModel.Idle:
            StatusText(text: "Provisioning Idle")
        case is ProvisioningModel.Initial:
            StatusText(text: "Provisioning Initial")
        case is ProvisioningModel.Connected:
            StatusText(text: "Connected")
        case is ProvisioningModel.ProcessingAuthorization:
            StatusText(text: "Processing Authorization")
        case is ProvisioningModel.Authorized:
            StatusText(text: "Authorized")
        case is ProvisioningModel.RequestingCredentials:
            StatusText(text: "Requesting Credentials")
        case is ProvisioningModel.CredentialsIssued:
            StatusText(text: "Credentials Issued")
            
        default:
            StatusText(text: "Unknown State")
        }
    }
}

// MARK: - Internal Sub-Views

/// Displays status text centered on the screen.
struct StatusText: View {
    let text: String
    
    var body: some View {
        Text(text)
            .font(.title)
            .multilineTextAlignment(.center)
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .center)
    }
}

/// Displays error information.
struct ProvisioningErrorView: View {
    let error: KotlinThrowable
    
    var body: some View {
        if let authError = error as? AuthorizationException {
            VStack {
                Text("Authorization Failed")
                    .font(.title)
                    .multilineTextAlignment(.center)
                    .padding(8)
                
                Text("Error code: \(authError.code)")
                    .font(.body)
                    .padding(4)
                
                if authError.description != nil {
                    Text(authError.description)
                        .font(.body)
                        .padding(4)
                }
            }
        } else {
            Text("Provisioning Error: \(error.message)")
                .font(.title)
                .multilineTextAlignment(.center)
                .padding(8)
        }
    }
}

/// Handles the authorization flow based on the current challenge.
struct Authorize: View {
    let provisioningModel: ProvisioningModel
    var waitForRedirectLinkInvocation: (String) async -> String
    let challenges: [AuthorizationChallenge]
    
    var body: some View {
        if let challenge = challenges.first {
            switch challenge {
            case let oauthChallenge as AuthorizationChallenge.OAuth:
                EvidenceRequestWebView(
                    provisioningModel: provisioningModel,
                    waitForRedirectLinkInvocation: waitForRedirectLinkInvocation,
                    evidenceRequest: oauthChallenge
                )
            case let secretChallenge as AuthorizationChallenge.SecretText:
                EvidenceRequestSecretText(
                    provisioningModel: provisioningModel,
                    challenge: secretChallenge
                )
            default:
                EmptyView()
            }
        }
    }
}

/// A view that instructs the user to check their browser for OAuth interactions.
struct EvidenceRequestWebView: View {
    let provisioningModel: ProvisioningModel
    var waitForRedirectLinkInvocation: (String) async -> String
    let evidenceRequest: AuthorizationChallenge.OAuth
    
    @Environment(\.openURL) var openURL
    
    var body: some View {
        VStack {
            HStack {
                Text("Check your browser")
                    .multilineTextAlignment(.center)
                    .padding(8)
                    .font(.body)
            }
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .task(id: evidenceRequest.url) {
            let invokedUrl = await waitForRedirectLinkInvocation(evidenceRequest.state)
            try? await provisioningModel.provideAuthorizationResponse(
                response: AuthorizationResponse.OAuth(
                    id: evidenceRequest.id,
                    parameterizedRedirectUrl: invokedUrl
                )
            )
        }
        .task(id: evidenceRequest.url) {
            if let url = URL(string: evidenceRequest.url) {
                openURL(url)
            }
        }
    }
}

/// A view for entering a secret code/passphrase.
struct EvidenceRequestSecretText: View {
    let provisioningModel: ProvisioningModel
    let challenge: AuthorizationChallenge.SecretText
    
    var body: some View {
        let passphraseRequest = challenge.request
        let constraints = PassphraseConstraints(
            minLength: Int32(passphraseRequest.length?.intValue ?? 1),
            maxLength: Int32(passphraseRequest.length?.intValue ?? 10),
            requireNumerical: passphraseRequest.isNumeric
        )

        VStack {
            Text(passphraseRequest.description_)
                .font(.title)
                .multilineTextAlignment(.center)
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .center)
            
            if challenge.retry {
                Text("Retry")
                    .font(.title)
                    .multilineTextAlignment(.center)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .center)
            }

            // TODO: port to use PassphraseInputView which will require some changes in ProvisioningModel
            PassphraseEntryField(
                constraints: constraints,
                checkWeakPassphrase: false
            ) { passphrase, meetsRequirements, donePressed in
                if meetsRequirements && donePressed {
                    Task {
                        try? await provisioningModel.provideAuthorizationResponse(
                            response: AuthorizationResponse.SecretText(id: challenge.id, secret: passphrase)
                        )
                    }
                }
            }
        }
    }
}

/// A simple text field for entering passphrases or numeric codes.
struct PassphraseEntryField: View {
    let constraints: PassphraseConstraints
    let checkWeakPassphrase: Bool
    let onAction: (String, Bool, Bool) -> Void
    
    @State private var text: String = ""
    
    var body: some View {
        VStack {
            if constraints.requireNumerical {
                TextField("Enter Code", text: $text)
                    .keyboardType(.numberPad)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .padding()
            } else {
                SecureField("Enter Password", text: $text)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .padding()
            }
            
            Button("Done") {
                let length = text.count
                let meetsRequirements = length >= constraints.minLength && length <= constraints.maxLength
                onAction(text, meetsRequirements, true)
            }
            .padding()
        }
    }
}
