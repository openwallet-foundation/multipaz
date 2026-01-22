import ExtensionKit
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
@preconcurrency import Multipaz
import Combine

@MainActor
final class RequestAuthorizationViewModel: ObservableObject {
    @Published var isLoading: Bool = true

    var source: PresentmentSource!
    var credentialPresentmentData: CredentialPresentmentData!
    var requester: Requester!
    var trustMetadata: TrustMetadata? = nil

    func startLoadingRequest(
        requestContext: ISO18013MobileDocumentRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource
    ) {
        self.isLoading = true
        Task {
            let clock = ContinuousClock()
            let sourceDuration = await clock.measure {
                source = await getPresentmentSource()
            }
            let calcDuration = try! await clock.measure {
                let request = Iso18013Request(
                    presentmentRequests: requestContext.request.presentmentRequests.map { presentmentRequest in
                        Iso18013PresentmentRequest(
                            documentRequestSets: presentmentRequest.documentRequestSets.map { documentRequestSet in
                                Iso18013DocumentRequestSet(requests: documentRequestSet.requests.map { request in
                                    Iso18013DocumentRequest(
                                        docType: request.documentType,
                                        nameSpaces: request.namespaces.mapValues { namespaceMap in
                                            namespaceMap.mapValues { elementInfo in
                                                Iso18013ElementInfo(isRetaining: elementInfo.isRetaining)
                                            }
                                        }
                                    )
                                })
                            },
                            isMandatory: presentmentRequest.isMandatory
                        )
                    }
                )
                credentialPresentmentData = try await request.getCredentialPresentmentData(
                    source: source,
                    keyAgreementPossible: []
                )
                requester = Requester(
                    certChain: nil,
                    appId: nil,
                    origin: requestContext.requestingWebsiteOrigin?.getOrigin()
                )

                // TODO: consider all authentications...
                if let auth = requestContext.request.requestAuthentications.first {
                    let certChain = auth.authenticationCertificateChain.map { secCert in
                        X509Cert(encoded: ByteString(bytes: (SecCertificateCopyData(secCert) as Data).toByteArray()))
                    }
                    
                    requester = Requester(
                        certChain: X509CertChain(certificates: certChain),
                        appId: nil,
                        origin: requestContext.requestingWebsiteOrigin?.getOrigin()
                    )
                    trustMetadata = try! await source.resolveTrust(requester: requester)
                }
            }
            print("Prepared PresentmentSource in \(sourceDuration.toMilliseconds()) msec")
            print("Calculated request and trustMetadata in \(calcDuration.toMilliseconds()) msec")
            self.isLoading = false
        }
    }
}

/// A ``View`` that can be used with in a ``ISO18013MobileDocumentRequestScene``
/// which shows an authorization UI and can send a document response.
///
/// - Parameters
///   - requestContext: A ``ISO18013MobileDocumentRequestContext`` which includes the request.
///   - getPresentmentSource: a function to asynchronously return a ``PresentmentSource`` which is used as the source of truth for presentment.
public struct RequestAuthorizationView : View {

    private let requestContext: ISO18013MobileDocumentRequestContext
    private let getPresentmentSource: () async -> PresentmentSource

    @StateObject private var viewModel = RequestAuthorizationViewModel()
    
    public init(
        requestContext: ISO18013MobileDocumentRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource
    ) {
        self.requestContext = requestContext
        self.getPresentmentSource = getPresentmentSource
    }

    public var body: some View {
        VStack {
            // TODO: Probably need custom PromptDialog() here to show e.g. PassphrasePrompt requests for Cloud HSM
            if (viewModel.isLoading) {
                VStack {
                    ProgressView()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Consent(
                    credentialPresentmentData: viewModel.credentialPresentmentData,
                    requester: viewModel.requester,
                    trustMetadata: viewModel.trustMetadata,
                    maxHeight: .infinity,
                    onConfirm: { selection in
                        Task {
                            try await requestContext.sendResponse { rawRequest in
                                let responseString = try! await digitalCredentialsPresentment(
                                    protocol: "org-iso-mdoc",
                                    data: String(data: rawRequest.requestData, encoding: .utf8)!,
                                    appId: nil,
                                    origin: requestContext.requestingWebsiteOrigin!.getOrigin(),
                                    preselectedDocuments: [],
                                    source: viewModel.source
                                )
                                let responseJson = try JSONSerialization.jsonObject(with: responseString.data(using: .utf8)!) as! [String: Any]
                                let responseData = responseJson["data"] as! [String: Any]
                                let responseBase64 = responseData["response"] as! String
                                let response = responseBase64.fromBase64Url().toNSData()
                                return ISO18013MobileDocumentResponse(responseData: response)
                            }
                        }
                    },
                    onCancel: {
                        requestContext.cancel()
                    }
                )
            }
        }
        .onAppear() {
            viewModel.startLoadingRequest(
                requestContext: requestContext,
                getPresentmentSource: getPresentmentSource
            )
        }
        .frame(maxHeight: .infinity)
        .background(Color(.secondarySystemBackground))
    }
}
