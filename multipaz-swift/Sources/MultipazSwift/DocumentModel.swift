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
 * Model that loads documents from a ``DocumentStore`` and keeps them updated.
 *
 * It exposes the documents as ``DocumentInfo`` and listens to live updates from the store.
 *
 * If a ``Document`` has no cardArt the model creates a default stock cardArt.
 *
 * - Parameters:
 *   - documentTypeRepository: a ``DocumentTypeRepository`` with information about document types or nil.
 */
@MainActor
@Observable
public class DocumentModel {
    
    let documentTypeRepository: DocumentTypeRepository?
    
    public init(documentTypeRepository: DocumentTypeRepository?) {
        self.documentTypeRepository = documentTypeRepository
    }
    
    public var documentInfos: [DocumentInfo] = []

    private var documentStore: DocumentStore!

    public func setDocumentStore(documentStore: DocumentStore) async {
        self.documentStore = documentStore

        for document in try! await documentStore.listDocuments(sort: true) {
            await documentInfos.append(getDocumentInfo(document))
        }
        Task {
            for await event in documentStore.eventFlow {
                if event is DocumentAdded {
                    let document = try! await documentStore.lookupDocument(identifier: event.documentId)
                    if document != nil {
                        await self.documentInfos.append(getDocumentInfo(document!))
                    }
                } else if event is DocumentUpdated {
                    let index = self.documentInfos.firstIndex { documentInfo in
                        documentInfo.document.identifier == event.documentId
                    }
                    if (index != nil) {
                        self.documentInfos[index!] = await getDocumentInfo(self.documentInfos[index!].document)
                    }
                } else if event is DocumentDeleted {
                    self.documentInfos.removeAll { documentInfo in
                        documentInfo.document.identifier == event.documentId
                    }
                }
            }
        }
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
