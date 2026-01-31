@preconcurrency import Multipaz
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
 * If a ``Document`` has no cardArt the model creates a default stock cardArt.
 */
@MainActor
@Observable
public class DocumentModel {
    
    let documentTypeRepository: DocumentTypeRepository?
    let storage: Storage
    let storagePartition: String

    /**
     * Initialization for ``DocumentModel``.
     *
     * - Parameters:
     *   - documentTypeRepository: a ``DocumentTypeRepository`` with information about document types or nil.
     *   - storage: the [Storage] used for storing document order.
     *   - storagePartition: the partition of [storage] to use.
     */
    public init(
        documentTypeRepository: DocumentTypeRepository?,
        storage: Storage = EphemeralStorage(clock: KotlinClockCompanion.shared.getSystem()),
        storagePartition: String = "default"
    ) {
        self.documentTypeRepository = documentTypeRepository
        self.storage = storage
        self.storagePartition = storagePartition
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
    private var table: StorageTable!
    private var storageData: DocumentModelStorageData!
    
    private let documentModelTableSpec = StorageTableSpec(
        name: "DocumentModel",
        supportPartitions: true,
        supportExpiration: false,
        schemaVersion: 0
    )

    public func setDocumentStore(documentStore: DocumentStore) async {
        self.documentStore = documentStore
        self.table = try! await storage.getTable(spec: documentModelTableSpec)
        self.storageData = try! await DocumentModelStorageData.load(
            table: self.table,
            partitionId: self.storagePartition
        )

        for document in try! await documentStore.listDocuments(sort: true) {
            await _documentInfos.append(getDocumentInfo(document))
        }
        Task {
            for await event in documentStore.eventFlow {
                if event is DocumentAdded {
                    let document = try! await documentStore.lookupDocument(identifier: event.documentId)
                    if document != nil {
                        await self._documentInfos.append(getDocumentInfo(document!))
                    }
                } else if event is DocumentUpdated {
                    let index = self._documentInfos.firstIndex { documentInfo in
                        documentInfo.document.identifier == event.documentId
                    }
                    if (index != nil) {
                        self._documentInfos[index!] = await getDocumentInfo(self._documentInfos[index!].document)
                    }
                } else if event is DocumentDeleted {
                    self._documentInfos.removeAll { documentInfo in
                        documentInfo.document.identifier == event.documentId
                    }
                }
            }
        }
    }
    
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
        try! await storageData.save(table: table, partitionId: storagePartition)
    }

    private func getDocumentInfo(_ document: Document) async -> DocumentInfo {
        var credentialInfos: [CredentialInfo] = []
        for credential in try! await document.getCredentials() {
            await credentialInfos.append(getCredentialInfo(credential))
        }
        return DocumentInfo(
            document: document,
            cardArt: document.renderCardArt(),
            credentialInfos: credentialInfos
        )
    }

    private func getCredentialInfo(_ credential: Credential) async -> CredentialInfo {

        var keyInfo: KeyInfo? = nil
        var keyInvalidated = false
        if let secureAreaBoundCredential = credential as? SecureAreaBoundCredential {
            keyInfo = try! await secureAreaBoundCredential.secureArea.getKeyInfo(alias: secureAreaBoundCredential.alias)
            keyInvalidated = try! await secureAreaBoundCredential.isInvalidated().boolValue
        }
        let claims: [Claim] = if credential.isCertified {
            try! await credential.getClaims(documentTypeRepository: documentTypeRepository)
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
    private static let keyName = "DocumentModelStorageData"
    
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
    
    func save(table: StorageTable, partitionId: String) async throws {
        let data = try Cbor.shared.encode(item: toDataItem())
        
        try await table.update(
            key: Self.keyName,
            data: ByteString(data: data, startIndex: 0, endIndex: data.size),
            partitionId: partitionId,
            expiration: nil
        )
    }
    
    static func load(table: StorageTable, partitionId: String) async throws -> DocumentModelStorageData {
        // Try to get existing data
        if let data = try await table.get(key: keyName, partitionId: partitionId) {
            let dataItem = try Cbor.shared.decode(encodedCbor: data.toByteArray(startIndex: 0, endIndex: data.size))
            return fromDataItem(dataItem)
        }
        
        // If not found, create new, insert, and return
        let newData = DocumentModelStorageData()
        let encodedData = try Cbor.shared.encode(item: newData.toDataItem())
        
        try await table.insert(
            key: keyName,
            data: ByteString(data: encodedData, startIndex: 0, endIndex: encodedData.size),
            partitionId: partitionId,
            expiration: KotlinInstant.companion.DISTANT_FUTURE
        )
        
        return newData
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
