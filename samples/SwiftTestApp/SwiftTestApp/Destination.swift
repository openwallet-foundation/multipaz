import Multipaz

enum Destination: Hashable {
    case startScreen
    case aboutScreen
    case documentStoreScreen
    case documentScreen(documentId: String)
    case credentialScreen(documentId: String, credentialId: String)
    case claimsScreen(documentId: String, credentialId: String)
    case consentPromptScreen
    case passphrasePromptScreen
    case iso18013ProximityPresentmentScreen
    case certificateViewerScreen(certificates: [X509Cert])
    case certificateExamplesScreen
    case verticalCardListScreen
    case floatingItemListScreen
}
