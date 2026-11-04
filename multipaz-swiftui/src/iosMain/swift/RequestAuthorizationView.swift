import ExtensionKit
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
import Combine

@MainActor
final class RequestAuthorizationViewModel: ObservableObject {
    private static let TAG = "RequestAuthorizationView"

    @Published var isLoading: Bool = true
    @Published var errorMessage: String? = nil

    var source: PresentmentSource!
    var presentmentData: PresentmentData!
    var requester: Requester!
    var trustedRequesterIdentity: TrustedRequesterIdentity? = nil
    private var consentContinuation: CheckedContinuation<PresentmentSelection?, Never>? = nil

    func confirmConsent(selection: PresentmentSelection) {
        if let continuation = consentContinuation {
            consentContinuation = nil
            continuation.resume(returning: selection)
        }
    }

    func cancelConsent() {
        if let continuation = consentContinuation {
            consentContinuation = nil
            continuation.resume(returning: nil)
        }
    }

    func handleError(_ error: Error, cancel: @escaping () -> Void) async {
        let kotlinException = (error as NSError).kotlinException
        if kotlinException is PresentmentCanceledException {
            cancel()
            return
        }
        let message: String
        if kotlinException is PresentmentCannotSatisfyRequestException ||
            kotlinException is Iso18015ResponseException ||
            kotlinException is DcqlCredentialQueryException {
            message = "The request could not be satisfied"
        } else if kotlinException is AuthenticationFailedException {
            message = "Authentication failed"
        } else if kotlinException is FaceNotMatchedException {
            message = "Face match failed"
        } else {
            message = "Something went wrong"
        }
        withAnimation(.easeInOut(duration: 0.25)) {
            self.errorMessage = message
            self.isLoading = false
        }
        try? await Task.sleep(for: .seconds(2))
        cancel()
    }

    func startLoadingRequest(
        requestContext: ISO18013MobileDocumentRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource
    ) {
        self.isLoading = true
        self.errorMessage = nil
        Task {
            let clock = ContinuousClock()
            let sourceDuration = await clock.measure {
                source = await getPresentmentSource()
            }
            do {
                let calcDuration = try await clock.measure {
                    var requesterIdentities: [RequesterIdentity] = []
                    for auth in requestContext.request.requestAuthentications {
                        let certChain = auth.authenticationCertificateChain.map { secCert in
                            X509Cert(encoded: ByteString(bytes: (SecCertificateCopyData(secCert) as Data).toByteArray()))
                        }
                        requesterIdentities.append(
                            Iso18013RequesterIdentity(certChain: X509CertChain(certificates: certChain))
                        )
                    }
                    requester = Requester(
                        requesterIdentities: requesterIdentities,
                        appId: nil,
                        origin: requestContext.requestingWebsiteOrigin?.getOrigin()
                    )
                    if !requesterIdentities.isEmpty {
                        trustedRequesterIdentity = try await source.resolveTrust(requester: requester)
                    }

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
                    presentmentData = try await request.execute(
                        presentmentSource: source,
                        keyAgreementPossible: [],
                        requesterIdentities: requesterIdentities
                    )
                }
                Logger.shared.d(
                    tag: Self.TAG,
                    msg: "Prepared source in \(sourceDuration.toMilliseconds()) ms, request in \(calcDuration.toMilliseconds()) ms (identities=\(requester.requesterIdentities.count), trusted='\(trustedRequesterIdentity?.trustMetadata.displayName ?? "nil")')"
                )
                self.isLoading = false
            } catch {
                Logger.shared.e(tag: Self.TAG, msg: "Error handling ISO 18013-5 request: \(error)")
                await handleError(error) {
                    requestContext.cancel()
                }
            }
        }
    }

#if compiler(>=6.4)
    @available(iOS 27.3, *)
    func startLoadingRawMdocRequest(
        requestContext: ISO18013MobileDocumentRawRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource
    ) {
        self.isLoading = true
        self.errorMessage = nil
        Task {
            let clock = ContinuousClock()
            let sourceDuration = await clock.measure {
                source = await getPresentmentSource()
            }
            Logger.shared.d(
                tag: Self.TAG,
                msg: "Prepared source for raw ISO 18013-5 request in \(sourceDuration.toMilliseconds()) ms"
            )
            do {
                let wrappedSource = source.withShowConsentPrompt { [weak self] requester, trustedRequesterIdentity, presentmentData, _, _ in
                    guard let self = self else { return nil }
                    self.requester = requester
                    self.trustedRequesterIdentity = trustedRequesterIdentity
                    self.presentmentData = presentmentData
                    self.isLoading = false
                    return await withCheckedContinuation { continuation in
                        self.consentContinuation = continuation
                    }
                }
                let responseString = try await digitalCredentialsPresentment(
                    protocol: "org-iso-mdoc",
                    data: String(data: requestContext.requestData, encoding: .utf8)!,
                    appId: nil,
                    origin: requestContext.requestingWebsiteOrigin?.getOrigin() ?? "",
                    preselectedDocuments: [],
                    source: wrappedSource,
                    onDocumentsInFocus: { _ in },
                    onTelemetry: { _ in }
                )
                let responseJson = try JSONSerialization.jsonObject(with: responseString.data(using: .utf8)!) as! [String: Any]
                let responseData = responseJson["data"] as! [String: Any]
                let responseBase64 = responseData["response"] as! String
                let response = responseBase64.fromBase64Url().toNSData()
                requestContext.sendResponse(ISO18013MobileDocumentResponse(responseData: response))
            } catch {
                Logger.shared.e(tag: Self.TAG, msg: "Error handling raw ISO 18013-5 request: \(error)")
                await handleError(error) {
                    requestContext.cancel()
                }
            }
        }
    }

    @available(iOS 27.3, *)
    func startLoadingOpenID4VPRequest(
        requestContext: OpenID4VPRawRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource
    ) {
        self.isLoading = true
        self.errorMessage = nil
        Task {
            let clock = ContinuousClock()
            let sourceDuration = await clock.measure {
                source = await getPresentmentSource()
            }
            Logger.shared.d(
                tag: Self.TAG,
                msg: "Prepared source for OpenID4VP request in \(sourceDuration.toMilliseconds()) ms"
            )
            let protocolString: String
            switch requestContext.exchangeProtocol {
            case .unsignedV1:
                protocolString = "openid4vp-v1-unsigned"
            case .signedV1:
                protocolString = "openid4vp-v1-signed"
            case .multisignedV1:
                protocolString = "openid4vp-v1-multisigned"
            @unknown default:
                protocolString = requestContext.exchangeProtocol.rawValue
            }
            do {
                let wrappedSource = source.withShowConsentPrompt { [weak self] requester, trustedRequesterIdentity, presentmentData, _, _ in
                    guard let self = self else { return nil }
                    self.requester = requester
                    self.trustedRequesterIdentity = trustedRequesterIdentity
                    self.presentmentData = presentmentData
                    self.isLoading = false
                    return await withCheckedContinuation { continuation in
                        self.consentContinuation = continuation
                    }
                }
                let responseString = try await digitalCredentialsPresentment(
                    protocol: protocolString,
                    data: String(data: requestContext.requestData, encoding: .utf8)!,
                    appId: nil,
                    origin: requestContext.requestingWebsiteOrigin?.getOrigin() ?? "",
                    preselectedDocuments: [],
                    source: wrappedSource,
                    onDocumentsInFocus: { _ in },
                    onTelemetry: { _ in }
                )
                let responseJson = try JSONSerialization.jsonObject(with: responseString.data(using: .utf8)!) as! [String: Any]
                let responseDataDict = responseJson["data"] as! [String: Any]
                let responseData = try JSONSerialization.data(withJSONObject: responseDataDict)
                requestContext.sendResponse(OpenID4VPRawRequestContext.Response(responseData: responseData))
            } catch {
                Logger.shared.e(tag: Self.TAG, msg: "Error handling OpenID4VP request: \(error)")
                await handleError(error) {
                    requestContext.cancel()
                }
            }
        }
    }
#endif
}

/// A ``View`` that can be used within an ``ISO18013MobileDocumentRequestScene``,
/// ``ISO18013MobileDocumentRawRequestScene``, or ``OpenID4VPRawRequestScene``
/// which shows an authorization UI and can send a document response.
///
/// While the incoming request is being evaluated against the ``PresentmentSource``, a loading
/// indicator is displayed. Once matching credentials are resolved, the ``Consent`` view is presented
/// so the user can review the requested claims and choose whether to share.
///
/// ### Cancellation and Error Handling
/// - If the user explicitly cancels the consent prompt (``PresentmentCanceledException``), the
///   underlying request context's `cancel()` method is called immediately.
/// - If an error occurs while evaluating the request or generating the response, this view plays an
///   error animation with a descriptive message for 2 seconds before calling `cancel()` on the
///   request context:
///   - `"The request could not be satisfied"` when no credentials match or the request cannot be
///     fulfilled (``PresentmentCannotSatisfyRequestException``, ``Iso18015ResponseException``, or
///     ``DcqlCredentialQueryException``).
///   - `"Authentication failed"` when user key-unlock authentication fails or is canceled
///     (``AuthenticationFailedException``).
///   - `"Face match failed"` when biometric face matching fails or is canceled
///     (``FaceNotMatchedException``).
///   - `"Something went wrong"` for any other unexpected error.
public struct RequestAuthorizationView : View {

    private let startFn: @MainActor (RequestAuthorizationViewModel) -> Void
    private let confirmFn: @MainActor (RequestAuthorizationViewModel, PresentmentSelection) -> Void
    private let cancelFn: @MainActor (RequestAuthorizationViewModel) -> Void
    private let transactionDataRendererRepository: TransactionDataRendererRepository?

    @StateObject private var viewModel = RequestAuthorizationViewModel()

    /// Creates a ``RequestAuthorizationView`` for an ``ISO18013MobileDocumentRequestContext``.
    ///
    /// If the request cannot be satisfied or an error occurs while preparing or sending the response,
    /// an error animation and descriptive message are displayed for 2 seconds before
    /// `requestContext.cancel()` is invoked. If the user cancels the consent prompt,
    /// `requestContext.cancel()` is called immediately.
    ///
    /// - Parameters:
    ///   - requestContext: An ``ISO18013MobileDocumentRequestContext`` which includes the request.
    ///   - getPresentmentSource: A function to asynchronously return a ``PresentmentSource`` which is used as the source of truth for presentment.
    ///   - transactionDataRendererRepository: The ``TransactionDataRendererRepository`` to find renderers for
    ///     transaction data in, or `nil` to use only the built-in rendering.
    public init(
        requestContext: ISO18013MobileDocumentRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource,
        transactionDataRendererRepository: TransactionDataRendererRepository? = nil
    ) {
        self.transactionDataRendererRepository = transactionDataRendererRepository
        self.startFn = { viewModel in
            viewModel.startLoadingRequest(
                requestContext: requestContext,
                getPresentmentSource: getPresentmentSource
            )
        }
        self.confirmFn = { viewModel, selection in
            Task {
                do {
                    try await requestContext.sendResponse { rawRequest in
                        let responseString = try await digitalCredentialsPresentment(
                            protocol: "org-iso-mdoc",
                            data: String(data: rawRequest.requestData, encoding: .utf8)!,
                            appId: nil,
                            origin: requestContext.requestingWebsiteOrigin?.getOrigin() ?? "",
                            preselectedDocuments: [],
                            source: viewModel.source.withShowConsentPrompt { _, _, _, _, _ in selection },
                            onDocumentsInFocus: { _ in },
                            onTelemetry: { _ in }
                        )
                        let responseJson = try JSONSerialization.jsonObject(with: responseString.data(using: .utf8)!) as! [String: Any]
                        let responseData = responseJson["data"] as! [String: Any]
                        let responseBase64 = responseData["response"] as! String
                        let response = responseBase64.fromBase64Url().toNSData()
                        return ISO18013MobileDocumentResponse(responseData: response)
                    }
                } catch {
                    Logger.shared.e(tag: "RequestAuthorizationView", msg: "Error sending ISO 18013-5 response: \(error)")
                    await viewModel.handleError(error) {
                        requestContext.cancel()
                    }
                }
            }
        }
        self.cancelFn = { _ in
            requestContext.cancel()
        }
    }

#if compiler(>=6.4)
    /// Creates a ``RequestAuthorizationView`` for an ``ISO18013MobileDocumentRawRequestContext``.
    ///
    /// If the request cannot be satisfied or an error occurs while preparing or sending the response,
    /// an error animation and descriptive message are displayed for 2 seconds before
    /// `requestContext.cancel()` is invoked. If the user cancels the consent prompt,
    /// `requestContext.cancel()` is called immediately.
    ///
    /// - Parameters:
    ///   - requestContext: An ``ISO18013MobileDocumentRawRequestContext`` which includes the raw request.
    ///   - getPresentmentSource: A function to asynchronously return a ``PresentmentSource`` which is used as the source of truth for presentment.
    ///   - transactionDataRendererRepository: The ``TransactionDataRendererRepository`` to find renderers for
    ///     transaction data in, or `nil` to use only the built-in rendering.
    @available(iOS 27.3, *)
    public init(
        requestContext: ISO18013MobileDocumentRawRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource,
        transactionDataRendererRepository: TransactionDataRendererRepository? = nil
    ) {
        self.transactionDataRendererRepository = transactionDataRendererRepository
        self.startFn = { viewModel in
            viewModel.startLoadingRawMdocRequest(
                requestContext: requestContext,
                getPresentmentSource: getPresentmentSource
            )
        }
        self.confirmFn = { viewModel, selection in
            viewModel.confirmConsent(selection: selection)
        }
        self.cancelFn = { viewModel in
            viewModel.cancelConsent()
        }
    }

    /// Creates a ``RequestAuthorizationView`` for an ``OpenID4VPRawRequestContext``.
    ///
    /// If the request cannot be satisfied or an error occurs while preparing or sending the response,
    /// an error animation and descriptive message are displayed for 2 seconds before
    /// `requestContext.cancel()` is invoked. If the user cancels the consent prompt,
    /// `requestContext.cancel()` is called immediately.
    ///
    /// - Parameters:
    ///   - requestContext: An ``OpenID4VPRawRequestContext`` which includes the raw OpenID4VP request.
    ///   - getPresentmentSource: A function to asynchronously return a ``PresentmentSource`` which is used as the source of truth for presentment.
    ///   - transactionDataRendererRepository: The ``TransactionDataRendererRepository`` to find renderers for
    ///     transaction data in, or `nil` to use only the built-in rendering.
    @available(iOS 27.3, *)
    public init(
        requestContext: OpenID4VPRawRequestContext,
        getPresentmentSource: @escaping () async -> PresentmentSource,
        transactionDataRendererRepository: TransactionDataRendererRepository? = nil
    ) {
        self.transactionDataRendererRepository = transactionDataRendererRepository
        self.startFn = { viewModel in
            viewModel.startLoadingOpenID4VPRequest(
                requestContext: requestContext,
                getPresentmentSource: getPresentmentSource
            )
        }
        self.confirmFn = { viewModel, selection in
            viewModel.confirmConsent(selection: selection)
        }
        self.cancelFn = { viewModel in
            viewModel.cancelConsent()
        }
    }
#endif

    public var body: some View {
        VStack {
            // TODO: Probably need custom PromptDialog() here to show e.g. PassphrasePrompt requests for Cloud HSM
            if let errorMessage = viewModel.errorMessage {
                RequestAuthorizationErrorView(message: errorMessage)
            } else if (viewModel.isLoading) {
                VStack {
                    ProgressView()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Consent(
                    presentmentData: viewModel.presentmentData,
                    requester: viewModel.requester,
                    trustedRequesterIdentity: viewModel.trustedRequesterIdentity,
                    maxHeight: .infinity,
                    transactionDataRendererRepository: transactionDataRendererRepository,
                    onConfirm: { selection in
                        confirmFn(viewModel, selection)
                    },
                    onCancel: {
                        cancelFn(viewModel)
                    }
                )
            }
        }
        .onAppear() {
            startFn(viewModel)
        }
        .frame(maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}

private struct RequestAuthorizationErrorView: View {
    let message: String

    @State private var animate: Bool = false

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 64))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .red)
                .symbolEffect(.bounce, value: animate)
                .scaleEffect(animate ? 1.0 : 0.5)
                .opacity(animate ? 1.0 : 0.0)

            Text(message)
                .font(.title3.weight(.semibold))
                .foregroundStyle(.primary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .opacity(animate ? 1.0 : 0.0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) {
                animate = true
            }
        }
    }
}

@available(iOS 26.0, *)
extension IdentityDocumentRequestSceneBuilder {
    @MainActor
    public static func buildBlock(
        _ s0: some IdentityDocumentRequestScene,
        _ s1: some IdentityDocumentRequestScene,
        _ s2: some IdentityDocumentRequestScene
    ) -> some IdentityDocumentRequestScene {
        buildBlock(buildBlock(s0, s1), s2)
    }
}
