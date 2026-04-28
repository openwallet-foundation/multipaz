
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
    ///   - eventLogger: an [EventLogger] for logging events or `nil`.
    ///   - resolveTrustFn: a function which can be used to determine if a requester is trusted.
    ///   - showConsentPromptFn: a [ShowConsentPromptFn] used show a consent prompt is required.
    ///   - preferSignatureToKeyAgreement: whether to use mdoc ECDSA authentication even if mdoc MAC authentication is possible (ISO mdoc only).
    ///   - domainsMdocSignature: the domains to use for ``MdocCredential`` instances using mdoc ECDSA authentication.
    ///   - domainsMdocKeyAgreement: the domains to use for ``MdocCredential`` instances using mdoc MAC authentication.
    ///   - domainsKeylessSdJwt: the domains to use for ``KeylessSdJwtVcCredential`` instances.
    ///   - domainsKeyBoundSdJwt: the domains to use for ``KeyBoundSdJwtVcCredential`` instances.
    public func create(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        zkSystemRepository: ZkSystemRepository? = nil,
        eventLogger: EventLogger? = nil,
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
        getBadgesFn: @escaping @Sendable (
            _ document: Document
        ) async -> [DocumentBadge] = { document in [] },
        preferSignatureToKeyAgreement: Bool = true,
        domainsMdocSignature: [ String ] = [],
        domainsMdocKeyAgreement: [ String ] = [],
        domainsKeylessSdJwt: [ String ] = [],
        domainsKeyBoundSdJwt: [ String ] = [],
    ) -> SimplePresentmentSource {
        return SimplePresentmentSource(
            documentStore: documentStore,
            documentTypeRepository: documentTypeRepository,
            zkSystemRepository: zkSystemRepository,
            eventLogger: eventLogger,
            resolveTrustFn: ResolveTrustHandler(f: resolveTrustFn),
            showConsentPromptFn: ShowConsentPromptHandler(f: showConsentPromptFn),
            getBadgesFn: GetBadgesHandler(f: getBadgesFn),
            preferSignatureToKeyAgreement: preferSignatureToKeyAgreement,
            domainsMdocSignature: domainsMdocSignature,
            domainsMdocKeyAgreement: domainsMdocKeyAgreement,
            domainsKeylessSdJwt: domainsKeylessSdJwt,
            domainsKeyBoundSdJwt: domainsKeyBoundSdJwt
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

private class GetBadgesHandler: KotlinSuspendFunction1 {
    let f: @Sendable (
        _ document: Document
    ) async -> [DocumentBadge]
    
    init(f: @escaping @Sendable (_ document: Document) async -> [DocumentBadge]) {
        self.f = f
    }

    func __invoke(p1: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let document = p1 as! Document
        let f = self.f
        Task {
            let value = await f(document)
            completionHandler(value, nil)
        }
    }
}
