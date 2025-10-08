import ExtensionKit
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
import Multipaz
import Combine

extension PresentmentSource: @unchecked Sendable {}
extension Iso18013Request: @unchecked Sendable {}
extension X509Cert: @unchecked Sendable {}

@MainActor
final class RequestAuthorizationViewModel: ObservableObject {
    @Published var isLoading: Bool = true

    var source: PresentmentSource!
    var credentialPresentmentData: CredentialPresentmentData!
    var requester: Requester!
    var trustPoint: TrustPoint? = nil

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
                    let now = Date.now
                    let trustResult = try! await source.readerTrustManager.verify(chain: certChain, atTime: now.toKotlinInstant())
                    if (trustResult.isTrusted == true) {
                        trustPoint = trustResult.trustPoints.first
                    }
                }
            }
            print("Prepared PresentmentSource in \(sourceDuration.toMilliseconds()) msec")
            print("Calculated request and trust point in \(calcDuration.toMilliseconds()) msec")
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
            if (viewModel.isLoading) {
                VStack {
                    ProgressView()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Consent(
                    credentialPresentmentData: viewModel.credentialPresentmentData,
                    requester: viewModel.requester,
                    trustPoint: viewModel.trustPoint,
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
        .background(Color(white: 0.85))
    }
}
