import SwiftUI
import Multipaz

private enum RequestType: String, CaseIterable {
    case mdlUsTransportation = "mDL: US transportation"
    case mdlAgeOver21AndPortrait = "mDL: Age over 21 + portrait"
    case mdlMandatory = "mDL: Mandatory data elements"
    case mdlAll = "mDL: All data elements"
    case mdlNameAndAddressPartiallyStored = "mDL: Name and address (partially stored)"
    case mdlNameAndAddressAllStored = "mDL: Name and address (all stored)"
    case photoIdMandatory = "PhotoID: Mandatory data elements (two docs)"
    case openid4vpComplexExampleFromAppendixD = "Complex example from OpenID4VP Appendix D"
    case boardingPassAndMdl = "Boarding pass AND mDL"
    case boardingPassOrMdl = "Boarding pass OR mDL"
}

private enum TrustPointType: String, CaseIterable {
    case utopiaBrewery = "Utopia Brewery"
    case utopiaBreweryNoPrivacyPolicy = "Utopia Brewery (no privacy policy)"
    case multipazIdentityReader = "Multipaz Identity Reader"
    case none = "None"
}

private enum VerifierOrigin: String, CaseIterable {
    case none = "None"
    case verifierMultipazOrg = "https://verifier.multipaz.org"
    case otherExampleCom = "https://other.example.com"
}

struct ConsentPromptScreen: View {
    @Environment(ViewModel.self) private var viewModel
    @State private var selectedRequestTypeString = RequestType.allCases.first!.rawValue
    @State private var selectedTrustPointTypeString = TrustPointType.allCases.first!.rawValue
    @State private var selectedVerifierOriginString = VerifierOrigin.allCases.first!.rawValue

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Request")
                    .font(.headline)
                ComboBox(
                    options: RequestType.allCases.map { $0.rawValue },
                    selection: $selectedRequestTypeString,
                    placeholder: "Pick one..."
                )
                
                Text("Trust Point")
                    .font(.headline)
                ComboBox(
                    options: TrustPointType.allCases.map { $0.rawValue },
                    selection: $selectedTrustPointTypeString,
                    placeholder: "Pick one..."
                )
                
                Text("Verifier Origin")
                    .font(.headline)
                ComboBox(
                    options: VerifierOrigin.allCases.map { $0.rawValue },
                    selection: $selectedVerifierOriginString,
                    placeholder: "Pick one..."
                )
            
                VStack {
                    Button(action: {
                        Task {
                            let consentData = await calcConsentData(
                                requestType: RequestType(rawValue: selectedRequestTypeString)!,
                                trustPointType: TrustPointType(rawValue: selectedTrustPointTypeString)!,
                                verifierOrigin: VerifierOrigin(rawValue: selectedVerifierOriginString)!
                            )
                            let selection = try await promptModelRequestConsent(
                                requester: consentData.requester,
                                trustMetadata: consentData.trustMetadata,
                                credentialPresentmentData: consentData.presentmentData,
                                preselectedDocuments: [],
                                onDocumentsInFocus: { documents in }
                            )
                            if selection != nil {
                                print("Selection: \(selection!)")
                            } else {
                                print("Consent prompt was dismissed!")
                            }
                        }
                    }) {
                        Text("Show consent prompt")
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                }
                .frame(maxWidth: .infinity)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Consent Prompt")
        .padding()
    }
}

private struct ConsentData {
    let requester: Requester
    let presentmentData: CredentialPresentmentData
    let trustMetadata: TrustMetadata?
}

private func calcConsentData(
    requestType: RequestType,
    trustPointType: TrustPointType,
    verifierOrigin: VerifierOrigin
) async -> ConsentData {
    let storage = EphemeralStorage(clock: KotlinClockCompanion.shared.getSystem())
    let secureArea = try! await Platform.shared.getSecureArea(storage: storage)
    let secureAreaRepository = SecureAreaRepository.Builder()
        .add(secureArea: secureArea)
        .build()
    let documentTypeRepository = DocumentTypeRepository()
    documentTypeRepository.addKnownTypes(locale: LocalizedStrings.shared.getCurrentLocale())
    documentTypeRepository.addUtopiaTypes(locale: LocalizedStrings.shared.getCurrentLocale())
    let documentStore = DocumentStore.Builder(
        storage: storage,
        secureAreaRepository: secureAreaRepository
    ).build()
    
    let mdlCardArt = UIImage(named: "driving_license_card_art")!.pngData()!
    let photoIdCardArt = UIImage(named: "photo_id_card_art")!.pngData()!
    let boardingPassCardArt = UIImage(named: "boarding-pass-utopia-airlines")!.pngData()!
    let utopiaBreweryLogo = UIImage(named: "utopia-brewery")!.pngData()!

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
    
    let mdlDoc = try! await documentStore.createDocument(
        displayName: "Erika's driving license",
        typeDisplayName: "Utopia driving license",
        cardArt: mdlCardArt.toByteString(),
        issuerLogo: nil,
        authorizationData: nil,
        created: now.toKotlinInstant(),
        metadata: nil
    )
    let _ = try! await DrivingLicense.shared.getDocumentType(
        locale: LocalizedStrings.shared.getCurrentLocale()
    ).createMdocCredentialWithSampleData(
        document: mdlDoc,
        secureArea: secureArea,
        createKeySettings: CreateKeySettings(
            algorithm: Algorithm.esp256,
            nonce: ByteStringBuilder(initialCapacity: 3).appendString(string: "123").toByteString(),
            userAuthenticationRequired: true,
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

    let photoIdDoc = try! await documentStore.createDocument(
        displayName: "Erika's Photo ID",
        typeDisplayName: "Utopia Photo ID",
        cardArt: photoIdCardArt.toByteString(),
        issuerLogo: nil,
        authorizationData: nil,
        created: now.toKotlinInstant(),
        metadata: nil
    )
    let _ = try! await PhotoID.shared.getDocumentType(
        locale: LocalizedStrings.shared.getCurrentLocale()
    ).createMdocCredentialWithSampleData(
        document: photoIdDoc,
        secureArea: secureArea,
        createKeySettings: CreateKeySettings(
            algorithm: Algorithm.esp256,
            nonce: ByteStringBuilder(initialCapacity: 3).appendString(string: "123").toByteString(),
            userAuthenticationRequired: true,
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

    let photoIdDoc2 = try! await documentStore.createDocument(
        displayName: "Erika's Photo ID #2",
        typeDisplayName: "Utopia Photo ID",
        cardArt: photoIdCardArt.toByteString(),
        issuerLogo: nil,
        authorizationData: nil,
        created: now.toKotlinInstant(),
        metadata: nil
    )
    let _ = try! await PhotoID.shared.getDocumentType(
        locale: LocalizedStrings.shared.getCurrentLocale()
    ).createMdocCredentialWithSampleData(
        document: photoIdDoc2,
        secureArea: secureArea,
        createKeySettings: CreateKeySettings(
            algorithm: Algorithm.esp256,
            nonce: ByteStringBuilder(initialCapacity: 3).appendString(string: "123").toByteString(),
            userAuthenticationRequired: true,
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

    let boardingPassDoc = try! await documentStore.createDocument(
        displayName: "Utopia 815 BOS to SFO",
        typeDisplayName: "Utopia Airlines boarding pass",
        cardArt: boardingPassCardArt.toByteString(),
        issuerLogo: nil,
        authorizationData: nil,
        created: now.toKotlinInstant(),
        metadata: nil
    )
    let _ = try! await UtopiaBoardingPass.shared.getDocumentType(
        locale: LocalizedStrings.shared.getCurrentLocale()
    ).createMdocCredentialWithSampleData(
        document: boardingPassDoc,
        secureArea: secureArea,
        createKeySettings: CreateKeySettings(
            algorithm: Algorithm.esp256,
            nonce: ByteStringBuilder(initialCapacity: 3).appendString(string: "123").toByteString(),
            userAuthenticationRequired: true,
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
    
    try! await addCredentialsForOpenID4VPComplexExample(
        documentStore: documentStore,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: AsymmetricKey.X509CertifiedExplicit(
            certChain: X509CertChain(certificates: [dsCert]),
            privateKey: dsKey,
            algorithm: Algorithm.esp256
        ),
    )
    
    let mdlDocType = DrivingLicense.shared.getDocumentType(
        locale: LocalizedStrings.shared.getCurrentLocale()
    )
    let photoIdDocType = PhotoID.shared.getDocumentType(
        locale: LocalizedStrings.shared.getCurrentLocale()
    )

    let dcqlString = switch requestType {
    case .mdlUsTransportation:
        mdlDocType.cannedRequests.first(where: { cr in cr.id == "us-transportation" })!.mdocRequest!.toDcqlString()
    case .mdlAgeOver21AndPortrait:
        mdlDocType.cannedRequests.first(where: { cr in cr.id == "age_over_21_and_portrait" })!.mdocRequest!.toDcqlString()
    case .mdlMandatory:
        mdlDocType.cannedRequests.first(where: { cr in cr.id == "mandatory" })!.mdocRequest!.toDcqlString()
    case .mdlAll:
        mdlDocType.cannedRequests.first(where: { cr in cr.id == "full" })!.mdocRequest!.toDcqlString()
    case .mdlNameAndAddressPartiallyStored:
        mdlDocType.cannedRequests.first(where: { cr in cr.id == "name-and-address-partially-stored" })!.mdocRequest!.toDcqlString()
    case .mdlNameAndAddressAllStored:
        mdlDocType.cannedRequests.first(where: { cr in cr.id == "name-and-address-all-stored" })!.mdocRequest!.toDcqlString()
    case .photoIdMandatory:
        photoIdDocType.cannedRequests.first(where: { cr in cr.id == "mandatory" })!.mdocRequest!.toDcqlString()
    case .openid4vpComplexExampleFromAppendixD:
        """
            {
              "credentials": [
                {
                  "id": "pid",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://credentials.example.com/identity_credential"]
                  },
                  "claims": [
                    {"path": ["given_name"]},
                    {"path": ["family_name"]},
                    {"path": ["address", "street_address"]}
                  ]
                },
                {
                  "id": "other_pid",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://othercredentials.example/pid"]
                  },
                  "claims": [
                    {"path": ["given_name"]},
                    {"path": ["family_name"]},
                    {"path": ["address", "street_address"]}
                  ]
                },
                {
                  "id": "pid_reduced_cred_1",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://credentials.example.com/reduced_identity_credential"]
                  },
                  "claims": [
                    {"path": ["family_name"]},
                    {"path": ["given_name"]}
                  ]
                },
                {
                  "id": "pid_reduced_cred_2",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://cred.example/residence_credential"]
                  },
                  "claims": [
                    {"path": ["postal_code"]},
                    {"path": ["locality"]},
                    {"path": ["region"]}
                  ]
                },
                {
                  "id": "nice_to_have",
                  "format": "dc+sd-jwt",
                  "meta": {
                    "vct_values": ["https://company.example/company_rewards"]
                  },
                  "claims": [
                    {"path": ["rewards_number"]}
                  ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "pid" ],
                    [ "other_pid" ],
                    [ "pid_reduced_cred_1", "pid_reduced_cred_2" ]
                  ]
                },
                {
                  "required": false,
                  "options": [
                    [ "nice_to_have" ]
                  ]
                }
              ]
            }

        """
    case .boardingPassAndMdl:
        """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "family_name" ] },
                    { "path": ["org.iso.18013.5.1", "given_name" ] },
                    { "path": ["org.iso.18013.5.1", "birth_date" ] },
                    { "path": ["org.iso.18013.5.1", "issue_date" ] },
                    { "path": ["org.iso.18013.5.1", "expiry_date" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_country" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_authority" ] },
                    { "path": ["org.iso.18013.5.1", "document_number" ] },
                    { "path": ["org.iso.18013.5.1", "portrait" ] },
                    { "path": ["org.iso.18013.5.1", "un_distinguishing_sign" ] }
                  ]
                },
                {
                  "id": "boarding-pass",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.multipaz.example.boarding-pass.1"
                  },
                  "claims": [
                    { "path": ["org.multipaz.example.boarding-pass.1", "passenger_name" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "seat_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "flight_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "departure_time" ] }
                  ]
                }
              ]
            }

        """
    case .boardingPassOrMdl:
        """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "family_name" ] },
                    { "path": ["org.iso.18013.5.1", "given_name" ] },
                    { "path": ["org.iso.18013.5.1", "birth_date" ] },
                    { "path": ["org.iso.18013.5.1", "issue_date" ] },
                    { "path": ["org.iso.18013.5.1", "expiry_date" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_country" ] },
                    { "path": ["org.iso.18013.5.1", "issuing_authority" ] },
                    { "path": ["org.iso.18013.5.1", "document_number" ] },
                    { "path": ["org.iso.18013.5.1", "portrait" ] },
                    { "path": ["org.iso.18013.5.1", "un_distinguishing_sign" ] }
                  ]
                },
                {
                  "id": "boarding-pass",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.multipaz.example.boarding-pass.1"
                  },
                  "claims": [
                    { "path": ["org.multipaz.example.boarding-pass.1", "passenger_name" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "seat_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "flight_number" ] },
                    { "path": ["org.multipaz.example.boarding-pass.1", "departure_time" ] }
                  ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "mdl" ],
                    [ "boarding-pass" ]
                  ]
                }
              ]
            }
        """
    }

    let trustMetadata: TrustMetadata? = switch trustPointType {
    case .utopiaBrewery:
        TrustMetadata(
            displayName: "Utopia Brewery",
            displayIcon: utopiaBreweryLogo.toByteString(),
            displayIconUrl: nil,
            privacyPolicyUrl: "https://apps.multipaz.org",
            disclaimer: nil,
            testOnly: false,
            extensions: [:]
        )
    case .utopiaBreweryNoPrivacyPolicy:
        TrustMetadata(
            displayName: "Utopia Brewery",
            displayIcon: utopiaBreweryLogo.toByteString(),
            displayIconUrl: nil,
            privacyPolicyUrl: nil,
            disclaimer: nil,
            testOnly: false,
            extensions: [:]
        )
    case .multipazIdentityReader:
        TrustMetadata(
            displayName: "Multipaz Identity Reader",
            displayIcon: nil,
            displayIconUrl: "https://apps.multipaz.org/multipaz-logo-400x400.png",
            privacyPolicyUrl: "https://apps.multipaz.org",
            disclaimer: nil,
            testOnly: false,
            extensions: [:]
        )
    case .none:
        nil
    }
   
    let readerRootKey = try! await Crypto.shared.createEcPrivateKey(curve: .p256)
    let readerRootCert = try! await MdocUtil.shared.generateReaderRootCertificate(
        readerRootKey: AsymmetricKey.AnonymousExplicit(privateKey: readerRootKey, algorithm: Algorithm.esp256),
        subject: X500Name.companion.fromName(name: "CN=Test Reader Root Key"),
        serial: ASN1Integer.companion.fromRandom(numBits: 128, random: KotlinRandom.companion),
        validFrom: validFrom.toKotlinInstant().truncateToWholeSeconds(),
        validUntil: validUntil.toKotlinInstant().truncateToWholeSeconds(),
        crlUrl: "https://apps.multipaz.org/crl"
    )

    let readerKey = try! await Crypto.shared.createEcPrivateKey(curve: .p256)
    let readerCert = try! await MdocUtil.shared.generateReaderCertificate(
        readerRootKey: AsymmetricKey.X509CertifiedExplicit(
            certChain: X509CertChain(certificates: [readerRootCert]),
            privateKey: readerRootKey,
            algorithm: Algorithm.esp256
        ),
        readerKey: readerKey.publicKey,
        subject: X500Name.companion.fromName(name: "CN=Test Reader Key"),
        dnsName: nil,
        serial: ASN1Integer.companion.fromRandom(numBits: 128, random: KotlinRandom.companion),
        validFrom: validFrom.toKotlinInstant().truncateToWholeSeconds(),
        validUntil: validUntil.toKotlinInstant().truncateToWholeSeconds(),
        extensions: []
    )
    let readerCertChain = X509CertChain(certificates: [readerCert, readerRootCert])

    let readerCertChainToUse: X509CertChain? = switch trustPointType {
    case .utopiaBrewery:
        readerCertChain
    case .utopiaBreweryNoPrivacyPolicy:
        readerCertChain
    case .multipazIdentityReader:
        readerCertChain
    case .none:
        nil
    }

    let requester = switch verifierOrigin {
    case .none:
        Requester(certChain: readerCertChainToUse, appId: nil, origin: nil)
    case .verifierMultipazOrg:
        Requester(certChain: readerCertChainToUse, appId: nil, origin: "https://verifier.multipaz.org")
    case .otherExampleCom:
        Requester(certChain: readerCertChainToUse, appId: nil, origin: "https://other.example.com")
    }

    let source = SimplePresentmentSource.companion.create(
        documentStore: documentStore,
        documentTypeRepository: documentTypeRepository,
        resolveTrustFn: { requester in
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
        domainsMdocSignature: ["mdoc"],
        domainsKeyBoundSdJwt: ["sdjwt"]
    )

    let query = try! DcqlQuery.companion.fromJsonString(dcql: dcqlString)
    let presentmentData = try! await query.execute(
        presentmentSource: source,
        keyAgreementPossible: [],
        transactionDataMap: [:]
    )
    
    return ConsentData(
        requester: requester,
        presentmentData: presentmentData,
        trustMetadata: trustMetadata
    )
}

private func addCredentialsForOpenID4VPComplexExample(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Date,
    validFrom: Date,
    validUntil: Date,
    dsKey: AsymmetricKey
) async throws {
    try await addCredPid(
        documentStore: documentStore,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
    try await addCredPidMax(
        documentStore: documentStore,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
    try await addCredOtherPid(
        documentStore: documentStore,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
    try await addCredPidReduced1(
        documentStore: documentStore,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
    try await addCredPidReduced2(
        documentStore: documentStore,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
    try await addCredCompanyRewards(
        documentStore: documentStore,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
}

private func addCredPid(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Date,
    validFrom: Date,
    validUntil: Date,
    dsKey: AsymmetricKey
) async throws {
    let claims: [String: Any] = [
        "given_name": "Erika",
        "family_name": "Mustermann",
        "address": [
            "street_address": "Sample Street 123"
        ]
    ]
    
    let _ = try await documentStore.provisionSdJwtVc(
        displayName: "my-pid",
        vct: "https://credentials.example.com/identity_credential",
        claims: claims,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
}

private func addCredPidMax(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Date,
    validFrom: Date,
    validUntil: Date,
    dsKey: AsymmetricKey
) async throws {
    let claims: [String: Any] = [
        "given_name": "Max",
        "family_name": "Mustermann",
        "address": [
            "street_address": "Sample Street 456"
        ]
    ]
    
    let _ = try await documentStore.provisionSdJwtVc(
        displayName: "my-pid-max",
        vct: "https://credentials.example.com/identity_credential",
        claims: claims,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
}

private func addCredOtherPid(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Date,
    validFrom: Date,
    validUntil: Date,
    dsKey: AsymmetricKey
) async throws {
    let claims: [String: Any] = [
        "given_name": "Erika",
        "family_name": "Mustermann",
        "address": [
            "street_address": "Sample Street 123"
        ]
    ]
    
    let _ = try await documentStore.provisionSdJwtVc(
        displayName: "my-other-pid",
        vct: "https://othercredentials.example/pid",
        claims: claims,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
}

private func addCredPidReduced1(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Date,
    validFrom: Date,
    validUntil: Date,
    dsKey: AsymmetricKey
) async throws {
    let claims: [String: Any] = [
        "given_name": "Erika",
        "family_name": "Mustermann"
    ]
    
    let _ = try await documentStore.provisionSdJwtVc(
        displayName: "my-pid-reduced1",
        vct: "https://credentials.example.com/reduced_identity_credential",
        claims: claims,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
}

private func addCredPidReduced2(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Date,
    validFrom: Date,
    validUntil: Date,
    dsKey: AsymmetricKey
) async throws {
    let claims: [String: Any] = [
        "postal_code": 90210,
        "locality": "Beverly Hills",
        "region": "Los Angeles Basin"
    ]
    
    let _ = try await documentStore.provisionSdJwtVc(
        displayName: "my-pid-reduced2",
        vct: "https://cred.example/residence_credential",
        claims: claims,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
}

private func addCredCompanyRewards(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    signedAt: Date,
    validFrom: Date,
    validUntil: Date,
    dsKey: AsymmetricKey
) async throws {
    let claims: [String: Any] = [
        "rewards_number": 24601
    ]
    
    let _ = try await documentStore.provisionSdJwtVc(
        displayName: "my-reward-card",
        vct: "https://company.example/company_rewards",
        claims: claims,
        secureArea: secureArea,
        signedAt: signedAt,
        validFrom: validFrom,
        validUntil: validUntil,
        dsKey: dsKey
    )
}

// MARK: - DocumentStore Extension

extension DocumentStore {
    
    fileprivate func provisionSdJwtVc(
        displayName: String,
        vct: String,
        claims: [String: Any],
        secureArea: SecureArea,
        signedAt: Date,
        validFrom: Date,
        validUntil: Date,
        dsKey: AsymmetricKey
    ) async throws -> Document {
        
        let document = try await self.createDocument(
            displayName: displayName,
            typeDisplayName: vct,
            cardArt: nil,
            issuerLogo: nil,
            authorizationData: nil,
            created: Date.now.toKotlinInstant(),
            metadata: nil
        )
        
        let credential = try await KeyBoundSdJwtVcCredential.Companion.shared.create(
            document: document,
            asReplacementForIdentifier: nil,
            domain: "sdjwt",
            secureArea: secureArea,
            vct: vct,
            createKeySettings: SoftwareCreateKeySettings.Builder().build()
        )

        let nonSdClaims: [String: Any] = [
            "iss": "https://example-issuer.com",
            "vct": credential.vct,
            "iat": signedAt.toKotlinInstant().epochSeconds,
            "nbf": validFrom.toKotlinInstant().epochSeconds,
            "exp": validUntil.toKotlinInstant().epochSeconds
        ]
        
        let keyInfo = try await credential.secureArea.getKeyInfo(alias: credential.alias)
        let sdJwt = try await SdJwt.Companion.shared.create(
            issuerKey: dsKey,
            kbKey: keyInfo.publicKey,
            claims: String(
                data: try JSONSerialization.data(withJSONObject: claims, options: []),
                encoding: .utf8
            )!,
            nonSdClaims: String(
                data: try JSONSerialization.data(withJSONObject: nonSdClaims, options: []),
                encoding: .utf8
            )!,
            digestAlgorithm: Algorithm.sha256,
            random: KotlinRandom.companion,
            saltSizeNumBits: 128,
            creationTime: KotlinInstant.companion.DISTANT_PAST,
            expiresIn: nil
        )
        
        try await credential.certify(
            issuerProvidedAuthenticationData:
                ByteStringBuilder(initialCapacity: 0)
                .appendString(string: sdJwt.compactSerialization)
                .toByteString()
        )
        return document
    }
}
