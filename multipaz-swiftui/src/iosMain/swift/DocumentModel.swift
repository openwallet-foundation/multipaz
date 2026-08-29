import UIKit
import Combine
import SwiftUI

/// A structure with information about a ``Credential``.
public struct CredentialInfo: Hashable {
    /// A reference to the ``Credential`` this information is about.
    public let credential: Credential
    
    /// The claims in the credential.
    public let claims: [Claim]
    
    /// Information about the key-binding key if the credential is a ``SecureAreaBoundCredential``.
    public let keyInfo: KeyInfo?
    
    /// True if the credential is a ``SecureAreaBoundCredential`` and the key has been invalidated.
    public let keyInvalidated: Bool
}

/// A structure with information about a ``Document``.
public struct DocumentInfo: Hashable, CardInfo {
    /// A unique identifier for the document.
    public var identifier: String { document.identifier }

    /// A reference to the ``Document`` this information is about.
    public let document: Document
    
    /// Card art for the document.
    public let cardArt: UIImage
    
    /// Badges for the document.
    public let badges: [CardBadge]
    
    /// The credentials for the document.
    public let credentialInfos: [CredentialInfo]
    
    public static func == (lhs: DocumentInfo, rhs: DocumentInfo) -> Bool {
        return lhs.document.identifier == rhs.document.identifier
    }
}

/**
 * Errors that can be thrown by ``DocumentModel``.
 */
public enum DocumentModelError: Error {
    case noSuchDocument
    case positionOutOfRange
}

/**
 * Model that loads documents from a ``DocumentStore`` and keeps them updated.
 *
 * The model exposes the documents as ``DocumentInfo`` and listens to live updates from the store
 * and maintains a persistent order which can be changed using ``setDocumentPosition(documentInfo:position:)``.
 *
 * If a ``Document`` has no card art the model creates a default stock card art.
 */
@MainActor
@Observable
public class DocumentModel {
    
    let documentTypeRepository: DocumentTypeRepository?
    let documentOrderKey: String
    let badgeFunction: @Sendable (
        _ document: Document
    ) async -> [DocumentBadge]

    /**
     * Initialization for ``DocumentModel``.
     *
     * - Parameters:
     *   - documentStore: the ``DocumentStore`` to use as a source of truth.
     *   - documentTypeRepository: a ``DocumentTypeRepository`` with information about document types or nil.
     *   - documentOrderKey: the name of the key to use for storing the document order in the ``Tags`` object associated with  ``documentStore``.
     *   - badgeFunction: a function to return badges for a document.
     */
    public init(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository?,
        documentOrderKey: String = "org.multipaz.DocumentModel.orderingKey",
        badgeFunction: @escaping @Sendable (
            _ document: Document
        ) async -> [DocumentBadge] = { document in [] }
    ) async throws {
        self.documentTypeRepository = documentTypeRepository
        self.documentOrderKey = documentOrderKey
        self.badgeFunction = badgeFunction
        self.documentStore = documentStore
        self.storageData = if let encoded = try await documentStore.getTags().getByteString(key: documentOrderKey) {
            DocumentModelStorageData.fromDataItem(
                try Cbor.shared.decode(encodedCbor: encoded.toByteArray(startIndex: 0, endIndex: encoded.size))
            )
        } else {
            DocumentModelStorageData()
        }

        var initialDocumentInfos: [DocumentInfo] = []
        for document in try await documentStore.listDocuments(sort: true) {
            initialDocumentInfos.append(try await getDocumentInfo(document))
        }
        self._documentInfos = initialDocumentInfos
        Task {
            for await event in documentStore.eventFlow {
                if event is DocumentAdded {
                    let document = try await documentStore.lookupDocument(identifier: event.documentId)
                    if document != nil {
                        await self._documentInfos.append(try getDocumentInfo(document!))
                    }
                } else if event is DocumentUpdated {
                    let index = self._documentInfos.firstIndex { documentInfo in
                        documentInfo.document.identifier == event.documentId
                    }
                    do {
                        if (index != nil) {
                            self._documentInfos[index!] = try await getDocumentInfo(self._documentInfos[index!].document)
                        }
                    } catch {
                        print("Ignoring error in getDocumentInfo() for DocumentUpdated event: \(error)")
                    }
                } else if event is DocumentDeleted {
                    self._documentInfos.removeAll { documentInfo in
                        documentInfo.document.identifier == event.documentId
                    }
                }
            }
        }
    }
    
    private var _documentInfos: [DocumentInfo] = []

    /**
     * The list of document IDs in their current display order.
     */
    public var documentOrder: [String] {
        documentInfos.map { $0.document.identifier }
    }

    /**
     * Gets the list of document IDs in their current display order.
     *
     * - Returns: list of document IDs in order.
     */
    public func getDocumentOrder() -> [String] {
        return documentOrder
    }

    public var documentInfos: [DocumentInfo] {
        _documentInfos.sorted { (a: DocumentInfo, b: DocumentInfo) -> Bool in
            let sa = storageData.sortingOrder[a.document.identifier]
            let sb = storageData.sortingOrder[b.document.identifier]
            if let sa = sa, let sb = sb {
                if sa != sb {
                    return sa < sb
                }
            } else if sa != nil {
                return true
            } else if sb != nil {
                return false
            }
            return Document.Comparator.shared.compare(a: a.document, b: b.document) < 0
        }
    }

    private var documentStore: DocumentStore!
    private var storageData: DocumentModelStorageData!
    
    /**
     * Sets the ordering of documents in the model.
     *
     * Any documents in the store that are not included in `documentOrder` will be placed
     * after the specified documents, maintaining their relative order.
     *
     * - Parameter documentOrder: the list of document IDs in the desired order.
     */
    public func setDocumentOrder(
        documentOrder: [String]
    ) async throws {
        var newOrder: [String] = []
        var seen = Set<String>()
        for id in documentOrder {
            if seen.insert(id).inserted {
                newOrder.append(id)
            }
        }
        for docInfo in self.documentInfos {
            if seen.insert(docInfo.document.identifier).inserted {
                newOrder.append(docInfo.document.identifier)
            }
        }
        var sortingOrder: [String: Int] = [:]
        for (index, id) in newOrder.enumerated() {
            sortingOrder[id] = index
        }
        storageData = DocumentModelStorageData(sortingOrder: sortingOrder)
        try await documentStore.getTags().edit(
            editActionFn: { tags in
                await tags.setByteString(
                    key: self.documentOrderKey,
                    value: ByteString(bytes: Cbor.shared.encode(item: self.storageData.toDataItem()))
                )
            }
        )
    }

    /**
     * Sets the position of a document.
     *
     * - Parameters:
     *  - documentInfo: the ``DocumentInfo`` to set position for.
     *  - position: the position to set.
     * - Throws: ``DocumentModelError.noSuchDocument`` if the given ``DocumentInfo`` doesn't exist.
     * - Throws: ``DocumentModelError.positionOutOfRange`` if the given position is out of range.
     */
    public func setDocumentPosition(
        documentInfo: DocumentInfo,
        position: Int
    ) async throws {
        var currentOrder = self.documentOrder
        guard let index = currentOrder.firstIndex(of: documentInfo.document.identifier) else {
            throw DocumentModelError.noSuchDocument
        }
        currentOrder.remove(at: index)
        if position < 0 || position > currentOrder.count {
            throw DocumentModelError.positionOutOfRange
        }
        currentOrder.insert(documentInfo.document.identifier, at: position)
        try await setDocumentOrder(documentOrder: currentOrder)
    }

    private func getDocumentInfo(_ document: Document) async throws -> DocumentInfo {
        var credentialInfos: [CredentialInfo] = []
        for credential in try await document.getCredentials() {
            await credentialInfos.append(try getCredentialInfo(credential))
        }
        return DocumentInfo(
            document: document,
            cardArt: document.renderCardArt(),
            badges: await badgeFunction(document).map { docBadge in
                CardBadge(
                    text: docBadge.text,
                    color: Color(
                        red: Double(docBadge.color.red)/255.0,
                        green: Double(docBadge.color.green)/255.0,
                        blue: Double(docBadge.color.blue)/255.0
                    )
                )
            },
            credentialInfos: credentialInfos
        )
    }

    private func getCredentialInfo(_ credential: Credential) async throws -> CredentialInfo {

        var keyInfo: KeyInfo? = nil
        var keyInvalidated = false
        if let secureAreaBoundCredential = credential as? SecureAreaBoundCredential {
            keyInfo = try await secureAreaBoundCredential.secureArea.getKeyInfo(alias: secureAreaBoundCredential.alias)
            keyInvalidated = try await secureAreaBoundCredential.isInvalidated().boolValue
        }
        let claims: [Claim] = if credential.isCertified {
            try await credential.getClaims(documentTypeRepository: documentTypeRepository)
        } else {
            []
        }
        return CredentialInfo(
            credential: credential,
            claims: claims,
            keyInfo: keyInfo,
            keyInvalidated: keyInvalidated
        )
    }
}



fileprivate struct DocumentModelStorageData {
    var sortingOrder: [String: Int] = [:]
    
    func toDataItem() -> DataItem {
        let builder = CborMap.companion.builder()
        let innerBuilder = builder.putMap(key: Tstr(value: "documentOrder"))
        for (key, value) in sortingOrder {
            innerBuilder.put(
                key: Tstr(value: key),
                value: value >= 0 ? Uint(value: UInt64(value)) : Nint(value: UInt64(value))
            )
        }
        return builder.end()!.build()
    }
    
    static func fromDataItem(_ dataItem: DataItem) -> DocumentModelStorageData {
        var sortingOrder: [String: Int] = [:]
        if dataItem.hasKey(key: "documentOrder") {
            for (key, value) in dataItem.get(key: "documentOrder").asMap {
                sortingOrder[key.asTstr] = Int(value.asNumber)
            }
        }
        return DocumentModelStorageData(sortingOrder: sortingOrder)
    }
}
