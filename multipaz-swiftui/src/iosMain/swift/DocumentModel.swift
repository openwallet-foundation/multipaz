import UIKit
import Combine

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
public struct DocumentInfo: Hashable {
    /// A reference to the ``Document`` this information is about.
    public let document: Document
    
    /// Card art for the document.
    public let cardArt: UIImage
    
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

    /**
     * Initialization for ``DocumentModel``.
     *
     * - Parameters:
     *   - documentStore: the ``DocumentStore`` to use as a source of truth.
     *   - documentTypeRepository: a ``DocumentTypeRepository`` with information about document types or nil.
     *   - documentOrderKey: the name of the key to use for storing the document order in the ``Tags`` object associated with  ``documentStore``.
     */
    public init(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository?,
        documentOrderKey: String = "org.multipaz.DocumentModel.orderingKey"
    ) async throws {
        self.documentTypeRepository = documentTypeRepository
        self.documentOrderKey = documentOrderKey
        self.documentStore = documentStore
        self.storageData = if let encoded = try await documentStore.getTags().getByteString(key: documentOrderKey) {
            DocumentModelStorageData.fromDataItem(
                try Cbor.shared.decode(encodedCbor: encoded.toByteArray(startIndex: 0, endIndex: encoded.size))
            )
        } else {
            DocumentModelStorageData()
        }

        for document in try await documentStore.listDocuments(sort: true) {
            await _documentInfos.append(try getDocumentInfo(document))
        }
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
                            self._documentInfos[index!] = await try getDocumentInfo(self._documentInfos[index!].document)
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

    public var documentInfos: [DocumentInfo] {
        _documentInfos.sorted { (a: DocumentInfo, b: DocumentInfo) -> Bool in
            let sa = storageData.sortingOrder[a.document.identifier]
            let sb = storageData.sortingOrder[b.document.identifier]
            if sa != nil && sb != nil {
                if sa != sb {
                    return sa! < sb!
                }
            }
            return Document.Comparator.shared.compare(a: a.document, b: b.document) < 0
        }
    }

    private var documentStore: DocumentStore!
    private var storageData: DocumentModelStorageData!
    
    /**
     * Sets the position of a document.
     *
     * - Parameters:
     *  - documentInfo: the ``DocumentInfo`` to set position for.
     *  - position: the position to set.
     * - Throws: ``DocumentError.noSuchDocument`` if the given ``DocumentInfo`` doesn't exist.
     * - Throws: ``DocumentError.positionOutOfRange`` if the given position is out of range.
     */
    public func setDocumentPosition(
        documentInfo: DocumentInfo,
        position: Int
    ) async throws {
        var documentInfos = self.documentInfos
        let index = documentInfos.firstIndex(of: documentInfo)
        if index == nil {
            throw DocumentModelError.noSuchDocument
        }
        documentInfos.remove(at: index!)
        if position < 0 || position > documentInfos.count {
            throw DocumentModelError.positionOutOfRange
        }
        documentInfos.insert(documentInfo, at: position)
        var sortingOrder: [String:Int] = [:]
        documentInfos.enumerated().forEach { index, di in
            sortingOrder[di.document.identifier] = index
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

    private func getDocumentInfo(_ document: Document) async throws -> DocumentInfo {
        var credentialInfos: [CredentialInfo] = []
        for credential in try await document.getCredentials() {
            await credentialInfos.append(try getCredentialInfo(credential))
        }
        return DocumentInfo(
            document: document,
            cardArt: document.renderCardArt(),
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
