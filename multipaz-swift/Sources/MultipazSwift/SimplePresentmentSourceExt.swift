@preconcurrency import Multipaz

// Swift-friendly version of SimplePresentmentSource() constructor which takes
// suspend functions as parameters.
//
extension SimplePresentmentSource.Companion {

    /// Creates a new ``SimplePresentmentSource``.
    ///
    ///- Parameters:
    ///   - documetStore: the [DocumentStore] which holds credentials that can be presented.
    ///   - documentTypeRepository: a [DocumentTypeRepository] which holds metadata for document types.
    ///   - zkSystemRepository: the [ZkSystemRepository] to use or `nil`.
    ///   - resolveTrustFn: a function which can be used to determine if a requester is trusted.
    ///   - showConsentPromptFn: a [ShowConsentPromptFn] used show a consent prompt is required.
    ///   - preferSignatureToKeyAgreement: whether to use mdoc ECDSA authentication even if mdoc MAC authentication is possible (ISO mdoc only).
    ///   - domainMdocSignature: the domain to use for ``MdocCredential`` instances using mdoc ECDSA authentication or `nil`.
    ///   - domainMdocKeyAgreement: the domain to use for ``MdocCredential`` instances using mdoc MAC authentication or `nil`.
    ///   - domainKeylessSdJwt: the domain to use for ``KeylessSdJwtVcCredential`` instances or `nil`.
    ///   - domainKeyBoundSdJwt: the domain to use for ``KeyBoundSdJwtVcCredential`` instances or `nil`.
    public func create(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        zkSystemRepository: ZkSystemRepository? = nil,
        resolveTrustFn: @escaping @Sendable (
            _ requester: Requester
        ) async -> TrustMetadata?,
        showConsentPromptFn: @escaping @Sendable (
            _ requester: Requester,
            _ trustMetadata: TrustMetadata?,
            _ credentialPresentmentData: CredentialPresentmentData,
            _ preselectedDocuments: [Document],
            _ onDocumentsInFocus: @escaping @Sendable (_ documents: [Document]) -> Void,
        ) async -> CredentialPresentmentSelection?,
        preferSignatureToKeyAgreement: Bool = true,
        domainMdocSignature: String? = nil,
        domainMdocKeyAgreement: String? = nil,
        domainKeylessSdJwt: String? = nil,
        domainKeyBoundSdJwt: String? = nil,
    ) -> SimplePresentmentSource {
        return SimplePresentmentSource(
            documentStore: documentStore,
            documentTypeRepository: documentTypeRepository,
            zkSystemRepository: zkSystemRepository,
            resolveTrustFn: ResolveTrustHandler(f: resolveTrustFn),
            showConsentPromptFn: ShowConsentPromptHandler(f: showConsentPromptFn),
            preferSignatureToKeyAgreement: preferSignatureToKeyAgreement,
            domainMdocSignature: domainMdocSignature,
            domainMdocKeyAgreement: domainMdocKeyAgreement,
            domainKeylessSdJwt: domainKeylessSdJwt,
            domainKeyBoundSdJwt: domainKeyBoundSdJwt
        )
    }

}

private class ResolveTrustHandler: KotlinSuspendFunction1 {
    let f: @Sendable (
        _ requester: Requester
    ) async -> TrustMetadata?
    
    init(f: @escaping @Sendable (_ requester: Requester) async -> TrustMetadata?) {
        self.f = f
    }

    func __invoke(p1: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let requester = p1 as! Requester
        let f = self.f
        Task {
            let value = await f(requester)
            completionHandler(value, nil)
        }
    }
}

private class ShowConsentPromptHandler: KotlinSuspendFunction5 {
    let f: @Sendable (
        _ requester: Requester,
        _ trustMetadata: TrustMetadata?,
        _ credentialPresentmentData: CredentialPresentmentData,
        _ preselectedDocuments: [Document],
        _ onDocumentsInFocus: @escaping @Sendable (_ documents: [Document]) -> Void,
    ) async -> CredentialPresentmentSelection?
    
    init(f: @escaping @Sendable (
        _ requester: Requester,
        _ trustMetadata: TrustMetadata?,
        _ credentialPresentmentData: CredentialPresentmentData,
        _ preselectedDocuments: [Document],
        _ onDocumentsInFocus: @escaping @Sendable (_ documents: [Document]) -> Void,
    ) async -> CredentialPresentmentSelection?) {
        self.f = f
    }

    func __invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let requester = p1 as! Requester
        let trustMetadata = p2 as! TrustMetadata?
        let credentialPresentmentData = p3 as! CredentialPresentmentData
        let preselectedDocuments = p4 as! [Document]
        let f = self.f
        Task {
            // TODO: The cast for onDocumentsInFocus fails at runtime, figure out how to make it work
            let value = await f(
                requester,
                trustMetadata,
                credentialPresentmentData,
                preselectedDocuments,
                { documents in }
            )
            completionHandler(value, nil)
        }
    }
}
