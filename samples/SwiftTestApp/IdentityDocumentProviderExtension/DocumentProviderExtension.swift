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
import Multipaz

func getPresentmentSource() async -> PresentmentSource {
    let storage = IosStorage(
        storageFileUrl: FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.org.multipaz.SwiftTestApp")!
            .appendingPathComponent("storage.db"),
        excludeFromBackup: true
    )
    let secureArea = try! await Platform.shared.getSecureArea(storage: storage)
    let softwareSecureArea = try! await SoftwareSecureArea.companion.create(storage: storage)
    let secureAreaRepository = SecureAreaRepository.Builder()
        .add(secureArea: secureArea)
        .add(secureArea: softwareSecureArea)
        .build()
    let documentTypeRepository = DocumentTypeRepository()
    documentTypeRepository.addKnownTypes(locale: LocalizedStrings.shared.getCurrentLocale())
    documentTypeRepository.addUtopiaTypes(locale: LocalizedStrings.shared.getCurrentLocale())
    let documentStore = DocumentStore.Builder(
        storage: storage,
        secureAreaRepository: secureAreaRepository
    ).build()
    
    let readerTrustManager = TrustManager(storage: storage, identifier: "default", partitionId: "default_default")
    
    let zkSystemRepository = ZkSystemRepository()
    // Note: the RAM limit for IdentityDocumentProvider is 120 MB as of iOS 26 and
    //   Longfellow v0.9 uses around ~200MB. So until Apple increases the RAM limit
    //   for this extension ZKP will likely not work.
    //
    let longfellow = LongfellowZkSystem()
    longfellow.addDefaultCircuits()
    zkSystemRepository.add(zkSystem: longfellow)
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
        domainsMdocSignature: ["mdoc"]
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
