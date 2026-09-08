import Foundation
import ObjectiveC

private var handlersHolderKey: UInt8 = 0

private final class HandlersHolder: NSObject {
    let selectSecureAreaHandler: AnyObject?
    
    init(selectSecureAreaHandler: AnyObject?) {
        self.selectSecureAreaHandler = selectSecureAreaHandler
    }
}

extension DocumentProvisioningHandler.Companion {

    /// Creates a new ``DocumentProvisioningHandler``.
    ///
    /// - Parameters:
    ///   - secureArea: credentials will be bound to keys from this ``SecureArea``.
    ///   - documentStore: new ``Document`` will be created in this ``DocumentStore``.
    ///   - metadataHandler: interface that initializes and updates document metadata or `nil`.
    ///   - defaultDocumentProvisioningSettings: the default ``DocumentProvisioningSettings`` to use.
    ///   - selectSecureAreaFn: optional lambda to select ``SecureArea`` and customize ``CreateKeySettings`` based on `appData` and suggested key settings.
    public func create(
        secureArea: SecureArea,
        documentStore: DocumentStore,
        metadataHandler: DocumentProvisioningHandlerAbstractDocumentMetadataHandler? = nil,
        defaultDocumentProvisioningSettings: DocumentProvisioningSettings = DocumentProvisioningSettings(),
        selectSecureAreaFn: (@MainActor @Sendable (
            _ appData: ByteString?,
            _ suggestedCreateKeySettings: CreateKeySettings
        ) async -> SelectedSecureArea)? = nil
    ) -> DocumentProvisioningHandler {
        let selectSecureAreaHandler = selectSecureAreaFn.map { SelectSecureAreaHandler(f: $0) }
        let handler = DocumentProvisioningHandler(
            secureArea: secureArea,
            documentStore: documentStore,
            metadataHandler: metadataHandler,
            defaultDocumentProvisioningSettings: defaultDocumentProvisioningSettings,
            selectSecureArea: selectSecureAreaHandler
        )
        if let selectSecureAreaHandler = selectSecureAreaHandler {
            let holder = HandlersHolder(selectSecureAreaHandler: selectSecureAreaHandler)
            objc_setAssociatedObject(
                handler,
                &handlersHolderKey,
                holder,
                .OBJC_ASSOCIATION_RETAIN_NONATOMIC
            )
        }
        return handler
    }
}

private func runSelectSecureArea(
    appData: ByteString?,
    suggestedCreateKeySettings: CreateKeySettings,
    f: @escaping @MainActor @Sendable (
        _ appData: ByteString?,
        _ suggestedCreateKeySettings: CreateKeySettings
    ) async -> SelectedSecureArea,
    completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void
) {
    Task { @MainActor in
        let value = await f(appData, suggestedCreateKeySettings)
        completionHandler(value, nil)
    }
}

private class SelectSecureAreaHandler: KotlinSuspendFunction2 {
    let f: @MainActor @Sendable (
        _ appData: ByteString?,
        _ suggestedCreateKeySettings: CreateKeySettings
    ) async -> SelectedSecureArea
    
    init(f: @escaping @MainActor @Sendable (
        _ appData: ByteString?,
        _ suggestedCreateKeySettings: CreateKeySettings
    ) async -> SelectedSecureArea) {
        self.f = f
    }

    func __invoke(p1: Any?, p2: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let appData = p1 as? ByteString
        let suggestedCreateKeySettings = p2 as! CreateKeySettings
        runSelectSecureArea(
            appData: appData,
            suggestedCreateKeySettings: suggestedCreateKeySettings,
            f: self.f,
            completionHandler: completionHandler
        )
    }
}
