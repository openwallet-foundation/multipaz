//
//  DocumentProviderExtension.swift
//  DocumentProviderExtension
//
//  Created by David Zeuthen on 10/8/25.
//  Copyright © 2025 orgName. All rights reserved.
//

import ExtensionKit
import IdentityDocumentServices
import IdentityDocumentServicesUI
@preconcurrency import Multipaz
import SwiftUI

func getPresentmentSource() async -> PresentmentSource {
    let storage = TestAppConfiguration.shared.storage
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
    
    let ephemeralStorage = EphemeralStorage(clock: KotlinClockCompanion().getSystem())
    let readerTrustManager = TrustManagerLocal(storage: ephemeralStorage, identifier: "default", partitionId: "default_default")
    try! await readerTrustManager.addX509Cert(
        certificate: X509Cert.companion.fromPem(
            pemEncoding: """
                -----BEGIN CERTIFICATE-----
                MIICaTCCAe+gAwIBAgIQtzUvFDCKLUBWQAZ4UnCw5zAKBggqhkjOPQQDAzA3MQswCQYDVQQGDAJV
                UzEoMCYGA1UEAwwfdmVyaWZpZXIubXVsdGlwYXoub3JnIFJlYWRlciBDQTAeFw0yNTA2MTkyMjE2
                MzJaFw0zMDA2MTkyMjE2MzJaMDcxCzAJBgNVBAYMAlVTMSgwJgYDVQQDDB92ZXJpZmllci5tdWx0
                aXBhei5vcmcgUmVhZGVyIENBMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEa6oCzC8rfHfwVOmQf83W
                yHEQFE8HrLK+NxsufJDrSFgMXjhRvPt3fIjlMyRAaf94Y25Ux9tXg+28EzzB/xG7q8P/FQ9nOSJk
                w4cQJVdD/ufN599uVdfp1URdG95Vncuoo4G/MIG8MA4GA1UdDwEB/wQEAwIBBjASBgNVHRMBAf8E
                CDAGAQH/AgEAMFYGA1UdHwRPME0wS6BJoEeGRWh0dHBzOi8vZ2l0aHViLmNvbS9vcGVud2FsbGV0
                LWZvdW5kYXRpb24tbGFicy9pZGVudGl0eS1jcmVkZW50aWFsL2NybDAdBgNVHQ4EFgQUsYQ5hS9K
                buq/6mKtvFHQgfdIhykwHwYDVR0jBBgwFoAUsYQ5hS9Kbuq/6mKtvFHQgfdIhykwCgYIKoZIzj0E
                AwMDaAAwZQIwKh87sK/cMbzuc9PFvyiSRedr2RoP0fuFK0X8ddOpi6hEMOapHL/Gs/QByROCpDpk
                AjEA2yLSJDZEu1GI8uChAsDBZwJPtv5KHUjq1Vpok69SNn+zzb1mNpqmiey+tchPBjZm
                -----END CERTIFICATE-----
                """.trimmingCharacters(in: .whitespacesAndNewlines)
        ),
        metadata: TrustMetadata(
            displayName: "Multipaz Identity Verifier",
            displayIcon: nil,
            displayIconUrl: "https://www.multipaz.org/multipaz-logo-200x200.png",
            privacyPolicyUrl: "https://apps.multipaz.org",
            disclaimer: nil,
            testOnly: true,
            extensions: [:]
        )
    )
    try! await readerTrustManager.addX509Cert(
        certificate: X509Cert.companion.fromPem(
            pemEncoding: """
                -----BEGIN CERTIFICATE-----
                MIICPzCCAcWgAwIBAgIQBpWf6aJhn7GaGv3AffPk8TAKBggqhkjOPQQDAzAiMSAwHgYDVQQDDBdN
                dWx0aXBheiBURVNUIFJlYWRlciBDQTAeFw0yNTA3MjYyMDEwMzBaFw0zMDA3MjYyMDEwMzBaMCIx
                IDAeBgNVBAMMF011bHRpcGF6IFRFU1QgUmVhZGVyIENBMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE
                L/cxWy6+d5Yf5LX/qkPQhyIhUGoPBIdlJxcaJ/l8gJOOvNSTQlBUvuzD8paQkZKs6fHvt3aGLiGL
                /bLYMhiQHmO7kVpz9DCI6+X82aZfiaSLMiHCrBC9yF1QiqahaKZxo4G/MIG8MA4GA1UdDwEB/wQE
                AwIBBjASBgNVHRMBAf8ECDAGAQH/AgEAMFYGA1UdHwRPME0wS6BJoEeGRWh0dHBzOi8vZ2l0aHVi
                LmNvbS9vcGVud2FsbGV0LWZvdW5kYXRpb24tbGFicy9pZGVudGl0eS1jcmVkZW50aWFsL2NybDAd
                BgNVHQ4EFgQU0B8Z/qjh8qzVXpR5JDdtmPmVx+kwHwYDVR0jBBgwFoAU0B8Z/qjh8qzVXpR5JDdt
                mPmVx+kwCgYIKoZIzj0EAwMDaAAwZQIxALxFZApDi8GcLiF6DXM41Krw+gtjxg4xzQfScuwgBtXf
                KPyHJ0RVMukttE+BEKNzjwIwHW7yJad8/+oSQf6hDo/JtMcdCvUk/gvzczJX7dDUpOGIxEmLmnCg
                H2bY+I2qhZCt
                -----END CERTIFICATE-----
                """.trimmingCharacters(in: .whitespacesAndNewlines)
        ),
        metadata: TrustMetadata(
            displayName: "David's Identity Verifier",
            displayIcon: nil,
            displayIconUrl: "https://www.multipaz.org/multipaz-logo-200x200.png",
            privacyPolicyUrl: "https://apps.multipaz.org",
            disclaimer: nil,
            testOnly: true,
            extensions: [:]
        )
    )
    
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
        domainMdocSignature: TestAppUtils.shared.CREDENTIAL_DOMAIN_MDOC_USER_AUTH,
        domainMdocKeyAgreement: TestAppUtils.shared.CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH,
        domainKeylessSdJwt: nil,
        domainKeyBoundSdJwt: nil
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

// Hack to work around the "shared source" problem. This will break if we
// ever call any Swift functions to load resources in the MultipazSwift
// framework but that's unlikely since this is mostly Kotlin anyway.
//
extension Foundation.Bundle {
    static var module: Bundle = main
}
