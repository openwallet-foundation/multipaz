import Foundation
import UIKit
import Multipaz
import MultipazSwift
import Observation
import SwiftUI

@MainActor
@Observable
class ViewModel {

    var path = NavigationPath()

    var isLoading: Bool = true

    var storage: Storage!
    var secureArea: SecureArea!
    var secureAreaRepository: SecureAreaRepository!
    var documentTypeRepository: DocumentTypeRepository!
    var documentStore: DocumentStore!
    var documentModel: DocumentModel!
    var readerTrustManager: TrustManagerLocal!

    let promptModel = Platform.shared.promptModel
    
    private let presentmentModel = PresentmentModel()

    //var provisioningModel: ProvisioningModel!
    //var provisioningState: ProvisioningModel.State = ProvisioningModel.Idle()
            
    func load() async {
        PromptModel.Companion.shared.setGlobal(promptModel: promptModel)
        
        storage = IosStorage(
            storageFileUrl: FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: "group.org.multipaz.SwiftTestApp")!
                .appendingPathComponent("storage.db"),
            excludeFromBackup: true
        )
        secureArea = try! await Platform.shared.getSecureArea(storage: storage)
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(secureArea: secureArea)
            .build()
        documentTypeRepository = DocumentTypeRepository()
        documentTypeRepository.addDocumentType(documentType: DrivingLicense.shared.getDocumentType())
        documentTypeRepository.addDocumentType(documentType: PhotoID.shared.getDocumentType())
        documentTypeRepository.addDocumentType(documentType: UtopiaBoardingPass.shared.getDocumentType())
        documentStore = DocumentStore.Builder(
            storage: storage,
            secureAreaRepository: secureAreaRepository
        ).build()
        readerTrustManager = TrustManagerLocal(storage: storage, identifier: "default", partitionId: "default_default")
        try! await readerTrustManager.deleteAll()
        try! await readerTrustManager.addX509Cert(
            certificate: X509Cert.companion.fromPem(
                pemEncoding: """
                -----BEGIN CERTIFICATE-----
                MIICYTCCAeegAwIBAgIQOSV5JyesOLKHeDc+0qmtuTAKBggqhkjOPQQDAzAzMQswCQYDVQQGDAJV
                UzEkMCIGA1UEAwwbTXVsdGlwYXogSWRlbnRpdHkgUmVhZGVyIENBMB4XDTI1MDcwNTEyMjAyMVoX
                DTMwMDcwNTEyMjAyMVowMzELMAkGA1UEBgwCVVMxJDAiBgNVBAMMG011bHRpcGF6IElkZW50aXR5
                IFJlYWRlciBDQTB2MBAGByqGSM49AgEGBSuBBAAiA2IABD4UX5jabDLuRojEp9rsZkAEbP8Icuj3
                qN4wBUYq6UiOkoULMOLUb+78Ygonm+sJRwqyDJ9mxYTjlqliW8PpDfulQZejZo2QGqpB9JPInkrC
                Bol5T+0TUs0ghkE5ZQBsVKOBvzCBvDAOBgNVHQ8BAf8EBAMCAQYwEgYDVR0TAQH/BAgwBgEB/wIB
                ADBWBgNVHR8ETzBNMEugSaBHhkVodHRwczovL2dpdGh1Yi5jb20vb3BlbndhbGxldC1mb3VuZGF0
                aW9uLWxhYnMvaWRlbnRpdHktY3JlZGVudGlhbC9jcmwwHQYDVR0OBBYEFM+kr4eQcxKWLk16F2Rq
                zBxFcZshMB8GA1UdIwQYMBaAFM+kr4eQcxKWLk16F2RqzBxFcZshMAoGCCqGSM49BAMDA2gAMGUC
                MQCQ+4+BS8yH20KVfSK1TSC/RfRM4M9XNBZ+0n9ePg9ftXUFt5e4lBddK9mL8WznJuoCMFuk8ey4
                lKnb4nubv5iPIzwuC7C0utqj7Fs+qdmcWNrSYSiks2OEnjJiap1cPOPk2g==
                -----END CERTIFICATE-----
                """.trimmingCharacters(in: .whitespacesAndNewlines)
            ),
            metadata: TrustMetadata(
                displayName: "Multipaz Identity Reader",
                displayIcon: UIImage(named: "multipaz-logo")!.pngData()!.toByteString(),
                displayIconUrl: nil,
                privacyPolicyUrl: nil,
                disclaimer: nil,
                testOnly: true,
                extensions: [:]
            )
        )
        try! await readerTrustManager.addX509Cert(
            certificate: X509Cert.companion.fromPem(
                pemEncoding: """
                -----BEGIN CERTIFICATE-----
                MIICiTCCAg+gAwIBAgIQQd/7PXEzsmI+U14J2cO1bjAKBggqhkjOPQQDAzBHMQswCQYDVQQGDAJV
                UzE4MDYGA1UEAwwvTXVsdGlwYXogSWRlbnRpdHkgUmVhZGVyIENBIChVbnRydXN0ZWQgRGV2aWNl
                cykwHhcNMjUwNzE5MjMwODE0WhcNMzAwNzE5MjMwODE0WjBHMQswCQYDVQQGDAJVUzE4MDYGA1UE
                AwwvTXVsdGlwYXogSWRlbnRpdHkgUmVhZGVyIENBIChVbnRydXN0ZWQgRGV2aWNlcykwdjAQBgcq
                hkjOPQIBBgUrgQQAIgNiAATqihOe05W3nIdyVf7yE4mHJiz7tsofcmiNTonwYsPKBbJwRTHa7AME
                +ToAfNhPMaEZ83lBUTBggsTUNShVp1L5xzPS+jK0tGJkR2ny9+UygPGtUZxEOulGK5I8ZId+35Gj
                gb8wgbwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwVgYDVR0fBE8wTTBLoEmg
                R4ZFaHR0cHM6Ly9naXRodWIuY29tL29wZW53YWxsZXQtZm91bmRhdGlvbi1sYWJzL2lkZW50aXR5
                LWNyZWRlbnRpYWwvY3JsMB0GA1UdDgQWBBSbz9r9IFmXjiGGnH3Siq90geurxTAfBgNVHSMEGDAW
                gBSbz9r9IFmXjiGGnH3Siq90geurxTAKBggqhkjOPQQDAwNoADBlAjEAomqjfJe2k162S5Way3sE
                BTcj7+DPvaLJcsloEsj/HaThIsKWqQlQKxgNu1rE/XryAjB/Gq6UErgWKlspp+KpzuAAWaKk+bMj
                cM4aKOKOU3itmB+9jXTQ290Dc8MnWVwQBs4=
                -----END CERTIFICATE-----
                """.trimmingCharacters(in: .whitespacesAndNewlines)
            ),
            metadata: TrustMetadata(
                displayName: "Multipaz Identity Reader (Untrusted Devices)",
                displayIcon: UIImage(named: "multipaz-logo")!.pngData()!.toByteString(),
                displayIconUrl: nil,
                privacyPolicyUrl: nil,
                disclaimer: nil,
                testOnly: true,
                extensions: [:]
            )
        )
        try! await readerTrustManager.addX509Cert(
                certificate: X509Cert.companion.fromPem(
                    pemEncoding: """
                        -----BEGIN CERTIFICATE-----
                        MIICfjCCAgSgAwIBAgIQJcmMK89tPNDdH7WpEBuqQDAKBggqhkjOPQQDAzBAMTEwLwYDVQQDDChW
                        ZXJpZmllciBSb290IGF0IGh0dHBzOi8vd3MuZGF2aWR6MjUubmV0MQswCQYDVQQGDAJVUzAeFw0y
                        NjAxMjgxMzExMDhaFw00MTAxMjQxMzExMDhaMEAxMTAvBgNVBAMMKFZlcmlmaWVyIFJvb3QgYXQg
                        aHR0cHM6Ly93cy5kYXZpZHoyNS5uZXQxCzAJBgNVBAYMAlVTMHYwEAYHKoZIzj0CAQYFK4EEACID
                        YgAEuSk/1XRVNYel5yV3RgxtUNlUE85dLTjyKItqz1RUNyOZ7ZHzH4oadb6WnCcLbl5Px+f6i8yt
                        cyh4diTQWG2gtuSRxo05PfeZR2rBy0ToZvoVgI9j8nDbfyRGEMrSTHf4o4HCMIG/MA4GA1UdDwEB
                        /wQEAwIBBjASBgNVHRMBAf8ECDAGAQH/AgEBMCIGA1UdEgQbMBmGF2h0dHBzOi8vd3MuZGF2aWR6
                        MjUubmV0MDUGA1UdHwQuMCwwKqAooCaGJGh0dHBzOi8vd3MuZGF2aWR6MjUubmV0L2NybC92ZXJp
                        ZmllcjAdBgNVHQ4EFgQU1TlDuv6QRGOCxyVsiV4KfUT0yvMwHwYDVR0jBBgwFoAU1TlDuv6QRGOC
                        xyVsiV4KfUT0yvMwCgYIKoZIzj0EAwMDaAAwZQIwUSENplERttXfOr7yHxbdIhcHdlVEaXLUDbPy
                        XcXW1hbL168wE0ykh6v0grJcD/P1AjEA23KTndS1cXfSi5jLDyB+OZY6O5EpVhxjxwZDwucfo2L1
                        zPTt/emPh8XuL625gPbY
                        -----END CERTIFICATE-----
                        """.trimmingCharacters(in: .whitespacesAndNewlines)
                ),
                metadata: TrustMetadata(
                    displayName: "David's Identity Verifier",
                    displayIcon: UIImage(named: "multipaz-logo")!.pngData()!.toByteString(),
                    displayIconUrl: nil,
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
                        MIICrjCCAjSgAwIBAgIQPBwq4BiWYFZE6A+NyGDT8jAKBggqhkjOPQQDAzBMMT0wOwYDVQQDDDRW
                        ZXJpZmllciBSb290IGF0IGh0dHBzOi8vaXNzdWVyLm11bHRpcGF6Lm9yZy9yZWNvcmRzMQswCQYD
                        VQQGDAJVUzAeFw0yNjAxMDUxNjM0MzNaFw00MTAxMDExNjM0MzNaMEwxPTA7BgNVBAMMNFZlcmlm
                        aWVyIFJvb3QgYXQgaHR0cHM6Ly9pc3N1ZXIubXVsdGlwYXoub3JnL3JlY29yZHMxCzAJBgNVBAYM
                        AlVTMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEY3+0sjs0mzzXVlxSfAsimOl9pviPCMONvjT7a7ZR
                        5FuQATIYnHPK8Qu/YJtwG7LWMPgsUR6H9fwyfLMqHZ309z+MJyDgKcn5tmlCyT0rslJzqWQeC1oB
                        /tXsFcc9Y5dto4HaMIHXMA4GA1UdDwEB/wQEAwIBBjASBgNVHRMBAf8ECDAGAQH/AgEBMC4GA1Ud
                        EgQnMCWGI2h0dHBzOi8vaXNzdWVyLm11bHRpcGF6Lm9yZy9yZWNvcmRzMEEGA1UdHwQ6MDgwNqA0
                        oDKGMGh0dHBzOi8vaXNzdWVyLm11bHRpcGF6Lm9yZy9yZWNvcmRzL2NybC92ZXJpZmllcjAdBgNV
                        HQ4EFgQUkdd4v76+FW8lvSXKJ+I/z0D+JCUwHwYDVR0jBBgwFoAUkdd4v76+FW8lvSXKJ+I/z0D+
                        JCUwCgYIKoZIzj0EAwMDaAAwZQIwWJx6Dn0NRjKCXiRKesqOKlA+CI5MhTDP9uj5T857U8alpOsD
                        Ho923n0DcjK5o/GeAjEAkEUFodNSrClSunFQAN+63KMqZmyNyS/pBi7k3CH1gTzC/kC9uU4yADKe
                        MTZj3/iH
                        -----END CERTIFICATE-----
                        """.trimmingCharacters(in: .whitespacesAndNewlines)
                ),
                metadata: TrustMetadata(
                    displayName: "Multipaz Identity Verifier",
                    displayIcon: UIImage(named: "multipaz-logo")!.pngData()!.toByteString(),
                    displayIconUrl: nil,
                    privacyPolicyUrl: "https://apps.multipaz.org",
                    disclaimer: nil,
                    testOnly: true,
                    extensions: [:]
                )
            )

        /*
        self.provisioningModel = ProvisioningModel.companion.create(
            documentStore: documentStore,
            secureArea: secureArea,
            httpClient: HttpClient(engineFactory: Darwin()) { config in
                config.followRedirects = false
            },
            promptModel: Platform.shared.promptModel,
            documentMetadataInitializer: { documentMetadata, credentialDisplay, issuerDisplay in
                print("Setting metadata from \(credentialDisplay) and \(issuerDisplay)")
                try! await documentMetadata.setMetadata(
                    displayName: credentialDisplay.text,
                    typeDisplayName: credentialDisplay.text, // TODO: doctype instead
                    cardArt: credentialDisplay.logo,
                    issuerLogo: issuerDisplay.logo,
                    other: nil
                )
            }
        )
         */
        
        let dcApi = DigitalCredentialsCompanion.shared.Default
        if dcApi.available {
            try! await dcApi.startExportingCredentials(
                documentStore: documentStore,
                documentTypeRepository: documentTypeRepository
            )
        }
        
        documentModel = DocumentModel(
            documentTypeRepository: documentTypeRepository,
            storage: storage
        )
        await documentModel.setDocumentStore(documentStore: documentStore)
    
        isLoading = false
    }
    
    private func getIsRunningOnSimulator() -> Bool {
#if targetEnvironment(simulator)
        return true
#else
        return false
#endif
    }

    func addSelfsignedMdoc(
        documentType: DocumentType,
        displayName: String,
        typeDisplayName: String,
        cardArtResourceName: String,
    ) async {
        let now = Date.now
        let signedAt = now
        let validFrom = now
        let validUntil = Calendar.current.date(byAdding: .year, value: 1, to: validFrom)!
        let iacaKey = try! await Crypto.shared.createEcPrivateKey(curve: EcCurve.p256)
        let iacaCert = try! await MdocUtil.shared.generateIacaCertificate(
            iacaKey: AsymmetricKey.AnonymousExplicit(privateKey: iacaKey, algorithm: Algorithm.esp256),
            subject: X500Name.companion.fromName(name: "CN=Test IACA Key"),
            serial: ASN1Integer.companion.fromRandom(numBits: 128, random: KotlinRandom.companion),
            validFrom: validFrom.toKotlinInstant().truncateToWholeSeconds(),
            validUntil: validUntil.toKotlinInstant().truncateToWholeSeconds(),
            issuerAltNameUrl: "https://issuer.example.com",
            crlUrl: "https://issuer.example.com/crl"
        )
        let dsKey = try! await Crypto.shared.createEcPrivateKey(curve: EcCurve.p256)
        let dsCert = try! await MdocUtil.shared.generateDsCertificate(
            iacaKey: AsymmetricKey.X509CertifiedExplicit(
                certChain: X509CertChain(certificates: [iacaCert]),
                privateKey: dsKey,
                algorithm: Algorithm.esp256
            ),
            dsKey: dsKey.publicKey,
            subject: X500Name.companion.fromName(name: "CN=Test DS Key"),
            serial:  ASN1Integer.companion.fromRandom(numBits: 128, random: KotlinRandom.companion),
            validFrom: validFrom.toKotlinInstant().truncateToWholeSeconds(),
            validUntil: validUntil.toKotlinInstant().truncateToWholeSeconds(),
        )
        let document = try! await documentStore.createDocument(
            displayName: displayName,
            typeDisplayName: typeDisplayName,
            cardArt: UIImage(named: cardArtResourceName)!.pngData()!.toByteString(),
            issuerLogo: nil,
            authorizationData: nil,
            created: now.toKotlinInstant(),
            metadata: nil
        )
        let _ = try! await documentType.createMdocCredentialWithSampleData(
            document: document,
            secureArea: secureArea,
            createKeySettings: CreateKeySettings(
                algorithm: Algorithm.esp256,
                nonce: ByteStringBuilder(initialCapacity: 3).appendString(string: "123").toByteString(),
                userAuthenticationRequired: getIsRunningOnSimulator() ? false : true,
                userAuthenticationTimeout: 0,
                validFrom: nil,
                validUntil: nil
            ),
            dsKey: AsymmetricKey.X509CertifiedExplicit(
                certChain: X509CertChain(certificates: [dsCert]),
                privateKey: dsKey,
                algorithm: Algorithm.esp256
            ),
            signedAt: signedAt.toKotlinInstant().truncateToWholeSeconds(),
            validFrom: validFrom.toKotlinInstant().truncateToWholeSeconds(),
            validUntil: validUntil.toKotlinInstant().truncateToWholeSeconds(),
            expectedUpdate: nil,
            domain: "mdoc",
            randomProvider: KotlinRandom.companion
        )
        try! await document.edit(editActionFn: { editor in
            editor.provisioned = true
        })
    }
    
    func getSource() -> PresentmentSource {
        return SimplePresentmentSource.companion.create(
            documentStore: documentStore,
            documentTypeRepository: documentTypeRepository,
            zkSystemRepository: nil,
            resolveTrustFn: { requester in
                if let certChain = requester.certChain {
                    let result = try! await self.readerTrustManager.verify(
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
                try! await promptModelRequestConsent(
                    requester: requester,
                    trustMetadata: trustMetadata,
                    credentialPresentmentData: credentialPresentmentData,
                    preselectedDocuments: preselectedDocuments,
                    onDocumentsInFocus: { documents in onDocumentsInFocus(documents) }
                )
            },
            preferSignatureToKeyAgreement: false,
            domainMdocSignature: "mdoc",
        )
    }
}

