import Foundation
import ObjectiveC

private var handlersHolderKey: UInt8 = 0

private final class HandlersHolder: NSObject {
    let resolveTrustHandler: AnyObject
    let showConsentPromptHandler: AnyObject
    let getBadgesHandler: AnyObject
    
    init(resolveTrustHandler: AnyObject, showConsentPromptHandler: AnyObject, getBadgesHandler: AnyObject) {
        self.resolveTrustHandler = resolveTrustHandler
        self.showConsentPromptHandler = showConsentPromptHandler
        self.getBadgesHandler = getBadgesHandler
    }
}

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
        resolveTrustFn: @escaping @MainActor @Sendable (
            _ requester: Requester
        ) async -> TrustedRequesterIdentity?,
        showConsentPromptFn: @escaping @MainActor @Sendable (
            _ requester: Requester,
            _ trustMetadata: TrustedRequesterIdentity?,
            _ consentData: ConsentData,
            _ preselectedDocuments: [Document],
            _ onDocumentsInFocus: @escaping @MainActor @Sendable (_ documents: [Document]) -> Void,
        ) async -> CredentialSelection?,
        getBadgesFn: @escaping @MainActor @Sendable (
            _ document: Document
        ) async -> [DocumentBadge] = { document in [] },
        preferSignatureToKeyAgreement: Bool = true,
        domainsMdocSignature: [ String ] = [],
        domainsMdocKeyAgreement: [ String ] = [],
        domainsKeylessSdJwt: [ String ] = [],
        domainsKeyBoundSdJwt: [ String ] = [],
    ) -> SimplePresentmentSource {
        let resolveTrustHandler = ResolveTrustHandler(f: resolveTrustFn)
        let showConsentPromptHandler = ShowConsentPromptHandler(f: showConsentPromptFn)
        let getBadgesHandler = GetBadgesHandler(f: getBadgesFn)
        
        let source = SimplePresentmentSource(
            documentStore: documentStore,
            documentTypeRepository: documentTypeRepository,
            zkSystemRepository: zkSystemRepository,
            eventLogger: eventLogger,
            resolveTrustFn: resolveTrustHandler,
            showConsentPromptFn: showConsentPromptHandler,
            getBadgesFn: getBadgesHandler,
            preferSignatureToKeyAgreement: preferSignatureToKeyAgreement,
            domainsMdocSignature: domainsMdocSignature,
            domainsMdocKeyAgreement: domainsMdocKeyAgreement,
            domainsKeylessSdJwt: domainsKeylessSdJwt,
            domainsKeyBoundSdJwt: domainsKeyBoundSdJwt
        )
        
        let holder = HandlersHolder(
            resolveTrustHandler: resolveTrustHandler,
            showConsentPromptHandler: showConsentPromptHandler,
            getBadgesHandler: getBadgesHandler
        )
        objc_setAssociatedObject(
            source,
            &handlersHolderKey,
            holder,
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
        
        return source
    }

}

private func runResolveTrust(
    requester: Requester,
    f: @escaping @MainActor @Sendable (Requester) async -> TrustedRequesterIdentity?,
    completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void
) {
    Task { @MainActor in
        let value = await f(requester)
        completionHandler(value, nil)
    }
}

private func runShowConsentPrompt(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    consentData: ConsentData,
    preselectedDocuments: [Document],
    f: @escaping @MainActor @Sendable (
        _ requester: Requester,
        _ trustedRequesterIdentity: TrustedRequesterIdentity?,
        _ consentData: ConsentData,
        _ preselectedDocuments: [Document],
        _ onDocumentsInFocus: @escaping @MainActor @Sendable (_ documents: [Document]) -> Void
    ) async -> CredentialSelection?,
    completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void
) {
    Task { @MainActor in
        // TODO: The cast for onDocumentsInFocus fails at runtime, figure out how to make it work
        let value = await f(
            requester,
            trustedRequesterIdentity,
            consentData,
            preselectedDocuments,
            { documents in }
        )
        completionHandler(value, nil)
    }
}

private func runGetBadges(
    document: Document,
    f: @escaping @MainActor @Sendable (Document) async -> [DocumentBadge],
    completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void
) {
    Task { @MainActor in
        let value = await f(document)
        completionHandler(value, nil)
    }
}

private class ResolveTrustHandler: KotlinSuspendFunction1 {
    let f: @MainActor @Sendable (
        _ requester: Requester
    ) async -> TrustedRequesterIdentity?
    
    init(f: @escaping @MainActor @Sendable (_ requester: Requester) async -> TrustedRequesterIdentity?) {
        self.f = f
    }

    func __invoke(p1: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let requester = p1 as! Requester
        runResolveTrust(requester: requester, f: self.f, completionHandler: completionHandler)
    }
}

private class ShowConsentPromptHandler: KotlinSuspendFunction5 {
    let f: @MainActor @Sendable (
        _ requester: Requester,
        _ trustedRequesterIdentity: TrustedRequesterIdentity?,
        _ consentData: ConsentData,
        _ preselectedDocuments: [Document],
        _ onDocumentsInFocus: @escaping @MainActor @Sendable (_ documents: [Document]) -> Void,
    ) async -> CredentialSelection?
    
    init(f: @escaping @MainActor @Sendable (
        _ requester: Requester,
        _ trustedRequesterIdentity: TrustedRequesterIdentity?,
        _ consentData: ConsentData,
        _ preselectedDocuments: [Document],
        _ onDocumentsInFocus: @escaping @MainActor @Sendable (_ documents: [Document]) -> Void,
    ) async -> CredentialSelection?) {
        self.f = f
    }

    func __invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let requester = p1 as! Requester
        let trustedRequesterIdentity = p2 as! TrustedRequesterIdentity?
        let consentData = p3 as! ConsentData
        let preselectedDocuments = p4 as! [Document]
        runShowConsentPrompt(
            requester: requester,
            trustedRequesterIdentity: trustedRequesterIdentity,
            consentData: consentData,
            preselectedDocuments: preselectedDocuments,
            f: self.f,
            completionHandler: completionHandler
        )
    }
}

private class GetBadgesHandler: KotlinSuspendFunction1 {
    let f: @MainActor @Sendable (
        _ document: Document
    ) async -> [DocumentBadge]
    
    init(f: @escaping @MainActor @Sendable (_ document: Document) async -> [DocumentBadge]) {
        self.f = f
    }

    func __invoke(p1: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let document = p1 as! Document
        runGetBadges(document: document, f: self.f, completionHandler: completionHandler)
    }
}
