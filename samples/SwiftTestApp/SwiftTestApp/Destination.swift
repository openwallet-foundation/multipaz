import Multipaz
import MultipazSwift

enum Destination: Hashable {
    case startScreen
case aboutScreen
    case documentStoreScreen
    case documentScreen(documentInfo: DocumentInfo)
    case credentialScreen(credentialInfo: CredentialInfo)
    case claimsScreen(credentialInfo: CredentialInfo)
    case consentPromptScreen
    case passphrasePromptScreen
    case iso18013ProximityPresentmentScreen
    case certificateViewerScreen(certificates: [X509Cert])
    case certificateExamplesScreen
}
