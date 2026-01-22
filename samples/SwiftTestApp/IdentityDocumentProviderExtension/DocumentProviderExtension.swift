//
//  DocumentProviderExtension.swift
//  IdentityDocumentProviderExtension
//
//  Created by David Zeuthen on 1/28/26.
//

import ExtensionKit
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
@preconcurrency import Multipaz
import MultipazSwift

func getPresentmentSource() async -> PresentmentSource {
    let storage = IosStorage(
        storageFileUrl: FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.org.multipaz.SwiftTestApp")!
            .appendingPathComponent("storage.db"),
        excludeFromBackup: true
    )
    let secureArea = try! await Platform.shared.getSecureArea(storage: storage)
    let secureAreaRepository = SecureAreaRepository.Builder()
        .add(secureArea: secureArea)
        .build()
    let documentTypeRepository = DocumentTypeRepository()
    documentTypeRepository.addDocumentType(documentType: DrivingLicense.shared.getDocumentType())
    documentTypeRepository.addDocumentType(documentType: PhotoID.shared.getDocumentType())
    documentTypeRepository.addDocumentType(documentType: AgeVerification.shared.getDocumentType())
    documentTypeRepository.addDocumentType(documentType: EUPersonalID.shared.getDocumentType())
    let documentStore = DocumentStore.Builder(
        storage: storage,
        secureAreaRepository: secureAreaRepository
    ).build()
    
    let readerTrustManager = TrustManagerLocal(storage: storage, identifier: "default", partitionId: "default_default")
    
    let zkSystemRepository = ZkSystemRepository()
    // TODO: the RAM limit for IdentityDocumentProvider is 120 MB and Longfellow uses
    //   just under 500MB. So we need to disable it for now. One possible work-around
    //   is for Apple to increase the limit, another is to move the proof generation
    //   to another process and do IPC.
    /*
    let longfellow = LongfellowZkSystem()
    let circuitFilenames = [
        "6_1_4096_2945_137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6",
        "6_2_4025_2945_b4bb6f01b7043f4f51d8302a30b36e3d4d2d0efc3c24557ab9212ad524a9764e",
        "6_3_4121_2945_b2211223b954b34a1081e3fbf71b8ea2de28efc888b4be510f532d6ba76c2010",
        "6_4_4283_2945_c70b5f44a1365c53847eb8948ad5b4fdc224251a2bc02d958c84c862823c49d6"
    ]
    for filename in circuitFilenames {
        let url = Bundle.main.url(
            forResource: filename,
            withExtension: ""
        )
        let data = try! Data(contentsOf: url!)
        longfellow.addCircuit(
            circuitFilename: filename,
            circuitBytes: ByteString(bytes: data.toByteArray())
        )
    }
    zkSystemRepository.add(zkSystem: longfellow)
     */
    return SimplePresentmentSource.companion.create(
        documentStore: documentStore,
        documentTypeRepository: documentTypeRepository,
        zkSystemRepository: zkSystemRepository,
        resolveTrustFn: { requester in
            if let certChain = requester.certChain {
                let result = try! await readerTrustManager.verify(
                    chain: certChain.certificates,
                    atTime: KotlinClockCompanion().getSystem().now()
                )
                if result.isTrusted {
                    return result.trustPoints.first?.metadata
                }
            }
            return nil
        },
        showConsentPromptFn: { requester, trustMetadata, credentialPresentmentData, preselectedDocuments, onDocumentsInFocus in
            try! await promptModelSilentConsent(
                requester: requester,
                trustMetadata: trustMetadata,
                credentialPresentmentData: credentialPresentmentData,
                preselectedDocuments: preselectedDocuments,
                onDocumentsInFocus: { documents in onDocumentsInFocus(documents) }
            )
        },
        preferSignatureToKeyAgreement: false,
        domainMdocSignature: "mdoc"
    )
}

@main
struct DocumentProviderExtension: IdentityDocumentProvider {

    var body: some IdentityDocumentRequestScene {
        ISO18013MobileDocumentRequestScene { context in
            RequestAuthorizationView(
                requestContext: context,
                getPresentmentSource: {
                    return await getPresentmentSource()
                }
            )
        }
    }

    func performRegistrationUpdates() async {
        
    }
}
