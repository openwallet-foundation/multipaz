import Foundation
import Multipaz
import SwiftUI
import UIKit

@MainActor
enum ConsentGoldenScreenshotRunner {
    static var isGoldenRun: Bool {
        let args = CommandLine.arguments
        return args.contains("--generate-consent-goldens") || args.contains("--verify-consent-goldens")
    }

    static var isUpdateMode: Bool {
        CommandLine.arguments.contains("--generate-consent-goldens")
    }

    static var repoRootPath: String {
        let args = CommandLine.arguments
        if let idx = args.firstIndex(of: "--generate-consent-goldens"), idx + 1 < args.count {
            return args[idx + 1]
        }
        if let idx = args.firstIndex(of: "--verify-consent-goldens"), idx + 1 < args.count {
            return args[idx + 1]
        }
        return "/Users/davidz/StudioProjects/multipaz"
    }

    static func runAll(in hostVC: GoldenCanvasViewController) async {
        let repoRoot = repoRootPath
        let goldenRootDir = "\(repoRoot)/multipaz-swiftui/golden-screenshots"
        let resourcesRootDir = "\(repoRoot)/multipaz-compose/src/androidUnitTest/resources"
        let statusFilePath = "/tmp/swiftui_consent_goldens_status.txt"
        try? FileManager.default.removeItem(atPath: statusFilePath)

        NSTimeZone.default = Foundation.TimeZone(identifier: "UTC")!
        swiftUiTimeZoneForTesting = Foundation.TimeZone(identifier: "UTC")!
        let fixedNow = SwiftConsentGoldenEcosystem.companion.NOW.toNSDate()
        swiftUiClockForTesting = { fixedNow }
        UIView.setAnimationsEnabled(false)

        do {
            let eco = try await SwiftConsentGoldenEcosystem.companion.create { relPath in
                let fullPath = relPath.hasPrefix("mpzpass/")
                    ? "\(repoRoot)/\(relPath)"
                    : "\(resourcesRootDir)/\(relPath)"
                let data = try! Data(contentsOf: URL(fileURLWithPath: fullPath))
                return data.toByteString()
            }
            let txRepo = buildGoldenTransactionDataRendererRepository()

            var mismatches: [String] = []
            var totalCaptured = 0

            func runScenario(
                categoryDir: String,
                scenarioId: String,
                pair: GoldenRequesterPair,
                presentmentData: PresentmentData,
                requesterHistory: RequesterHistory? = SwiftConsentGoldenEcosystem.companion.firstTimeHistory(),
                classifyPurposesFn: @escaping ClassifyPurposesSwiftFn = { r, t, pd, sels in
                    defaultClassifyPurposes(
                        requester: r,
                        trustedRequesterIdentity: t,
                        presentmentData: pd,
                        selections: sels
                    )
                },
                classifyRiskFn: @escaping ClassifyRiskSwiftFn = { r, t, pd, sels, purposes in
                    defaultClassifyRisk(
                        requester: r,
                        trustedRequesterIdentity: t,
                        presentmentData: pd,
                        selections: sels,
                        purposes: purposes
                    )
                },
                darkTheme: Bool = false,
                steps: (GoldenScenarioScope) async throws -> Void
            ) async throws {
                let controller = ConsentTestController()
                let rootView = Consent(
                    presentmentData: presentmentData,
                    requester: pair.requester,
                    trustedRequesterIdentity: pair.trustedRequesterIdentity,
                    preselectedDocuments: [],
                    maxHeight: .infinity,
                    transactionDataRendererRepository: txRepo,
                    requesterHistory: requesterHistory,
                    classifyPurposesFn: classifyPurposesFn,
                    classifyRiskFn: classifyRiskFn,
                    testController: controller,
                    onDocumentsInFocus: { _ in },
                    onConfirm: { _ in },
                    onCancel: {}
                )
                .background(Color(UIColor.systemGroupedBackground))
                .environment(\.colorScheme, darkTheme ? .dark : .light)

                let hostingController = UIHostingController(rootView: AnyView(rootView))
                hostingController.safeAreaRegions = []
                hostingController.overrideUserInterfaceStyle = darkTheme ? .dark : .light
                hostingController.traitOverrides.userInterfaceLevel = .elevated
                hostingController.view.backgroundColor = UIColor.systemGroupedBackground

                hostVC.mount(hostingController)
                defer {
                    hostVC.unmount(hostingController)
                }

                let scope = GoldenScenarioScope(
                    categoryDir: categoryDir,
                    scenarioId: scenarioId,
                    goldenRootDir: goldenRootDir,
                    updateGoldens: isUpdateMode,
                    hostingController: hostingController,
                    controller: controller,
                    onCaptured: { totalCaptured += 1 },
                    onMismatch: { mismatches.append($0) }
                )
                await scope.waitForReady()
                try await steps(scope)
            }

            // =====================================================================================
            // Category 1: 01_normal_single
            // =====================================================================================
            let cat1 = "01_normal_single"

            // 1.1 age_verification_first_time
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_VINEYARDS)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(categoryDir: cat1, scenarioId: "age_verification_first_time", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showDocumentDetails()
                    try await s.capture("doc_details")
                    await s.pop()
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                    await s.showFirstRequesterCertChain()
                    try await s.capture("cert_chain")
                }
            }

            // 1.2 age_verification_returning_user
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_VINEYARDS)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(
                    categoryDir: cat1,
                    scenarioId: "age_verification_returning_user",
                    pair: pair,
                    presentmentData: pd,
                    requesterHistory: SwiftConsentGoldenEcosystem.companion.returningUserHistory(shareCount: 3)
                ) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 1.3 age_over_21_eu_av_over_the_internet
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_VINEYARDS,
                    origin: "https://store.utopiavineyards.example.com"
                )
                let pd = try await eco.executeAgeVerificationCannedDcql(cannedRequestId: "age_over_21")
                try await runScenario(categoryDir: cat1, scenarioId: "age_over_21_eu_av_over_the_internet", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showDocumentDetails()
                    try await s.capture("doc_details")
                }
            }

            // 1.4 driving_privileges_with_disclaimer
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_DOT)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "us-transportation")
                try await runScenario(categoryDir: cat1, scenarioId: "driving_privileges_with_disclaimer", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showDocumentDetails()
                    try await s.capture("doc_details")
                    await s.pop()
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 1.5 address_verification_partial_retention
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_MARKETPLACE,
                    origin: "https://checkout.utopiamarketplace.example.com"
                )
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "name-and-address-partially-stored")
                try await runScenario(categoryDir: cat1, scenarioId: "address_verification_partial_retention", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showDocumentDetails()
                    try await s.capture("doc_details")
                }
            }

            // 1.6 identity_multiple_matching_docs
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES,
                    origin: "https://checkin.utopiaairlines.example.com"
                )
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "given_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "family_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "birth_date"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    },
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(PhotoID.shared.PHOTO_ID_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "given_name"] },
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "family_name"] },
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "birth_date"] },
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "portrait"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [
                        ["mdl"],
                        ["photoid"]
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat1, scenarioId: "identity_multiple_matching_docs", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showPickSolution(useCaseIndex: 0)
                    try await s.capture("chooser")
                    await s.selectSolution(useCaseIndex: 0, solutionIndex: 1)
                    try await s.capture("main_switched")
                }
            }

            // 1.7 loyalty_card
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_WHOLESALE)
                let pd = try await eco.executeLoyaltyCannedDcql(cannedRequestId: "mandatory")
                try await runScenario(categoryDir: cat1, scenarioId: "loyalty_card", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // 1.8 movie_ticket_sdjwt
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_CINEMA)
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "ticket",
                      "format": "dc+sd-jwt",
                      "meta": { "vct_values": ["\(UtopiaMovieTicket.shared.MOVIE_TICKET_VCT)"] },
                      "claims": [
                        { "path": ["ticket_id"] },
                        { "path": ["movie"] },
                        { "path": ["show_date_time"] },
                        { "path": ["theater_id"] },
                        { "path": ["seat_id"] }
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat1, scenarioId: "movie_ticket_sdjwt", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showDocumentDetails()
                    try await s.capture("doc_details")
                    await s.pop()
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 1.9 unverified_proximity_reader_age_over_21
            do {
                let pair = eco.unverifiedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_UNVERIFIED_LOUNGE)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(categoryDir: cat1, scenarioId: "unverified_proximity_reader_age_over_21", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 1.10 anonymous_proximity_reader_age_over_21
            do {
                let pair = eco.anonymousProximityRequester()
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(categoryDir: cat1, scenarioId: "anonymous_proximity_reader_age_over_21", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // =====================================================================================
            // Category 2: 02_transactions
            // =====================================================================================
            let cat2 = "02_transactions"

            // 2.1 payment_with_claims
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_MARKETPLACE,
                    origin: "https://checkout.utopiamarketplace.example.com"
                )
                let pd = try await eco.executePaymentCannedDcql(
                    cannedRequestId: "payment_transaction",
                    payeeName: "Utopia Marketplace",
                    amount: 78.50,
                    tipRequested: true
                )
                try await runScenario(categoryDir: cat2, scenarioId: "payment_with_claims", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showPickSolution(useCaseIndex: 0)
                    try await s.capture("chooser")
                }
            }

            // 2.2 payment_confirmation_only
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_WHOLESALE)
                let pd = try await eco.executePaymentCannedDcql(
                    cannedRequestId: "payment_transaction_only_conf",
                    payeeName: "Utopia Wholesale",
                    amount: 142.80,
                    tipRequested: false
                )
                try await runScenario(categoryDir: cat2, scenarioId: "payment_confirmation_only", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // 2.3 transit_fare_custom_renderer
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_METRO_TRANSIT)
                let pd = try await eco.executeTransitFareCannedDcql(cannedRequestId: "payment_transaction_only_conf")
                try await runScenario(categoryDir: cat2, scenarioId: "transit_fare_custom_renderer", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showTransactionDetails()
                    try await s.capture("tx_details")
                }
            }

            // 2.4 ap2_payment_delegate
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES,
                    origin: "https://book.utopiaair.example.com"
                )
                let pd = try await eco.executeAp2MandateCannedDcql(cannedRequestId: "payment_transaction_only_conf")
                try await runScenario(categoryDir: cat2, scenarioId: "ap2_payment_delegate", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showTransactionDetails()
                    try await s.capture("tx_details")
                }
            }

            // =====================================================================================
            // Category 3: 03_multi_credential
            // =====================================================================================
            let cat3 = "03_multi_credential"

            // 3.1 mdl_and_boarding_pass_single_use_case
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "given_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "family_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "birth_date"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    },
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(PhotoID.shared.PHOTO_ID_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "given_name"] },
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "family_name"] },
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "birth_date"] },
                        { "path": ["\(PhotoID.shared.ISO_23220_2_NAMESPACE)", "portrait"] }
                      ]
                    },
                    {
                      "id": "boarding-pass",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(UtopiaBoardingPass.shared.BOARDING_PASS_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "passenger_name"] },
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "flight_number"] },
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "seat_number"] },
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "departure_time"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [
                        ["mdl", "boarding-pass"],
                        ["photoid", "boarding-pass"]
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat3, scenarioId: "mdl_and_boarding_pass_single_use_case", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showPickSolution(useCaseIndex: 0)
                    try await s.capture("chooser")
                }
            }

            // 3.2 mdl_or_boarding_pass
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "given_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "family_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "birth_date"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    },
                    {
                      "id": "boarding-pass",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(UtopiaBoardingPass.shared.BOARDING_PASS_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "passenger_name"] },
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "flight_number"] },
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "seat_number"] },
                        { "path": ["\(UtopiaBoardingPass.shared.BOARDING_PASS_NS)", "departure_time"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [
                        ["mdl"],
                        ["boarding-pass"]
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat3, scenarioId: "mdl_or_boarding_pass", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showPickSolution(useCaseIndex: 0)
                    try await s.capture("chooser")
                }
            }

            // 3.3 mandatory_mdl_and_optional_loyalty
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_CAR_RENTALS,
                    origin: "https://reserve.utopiacarrentals.example.com"
                )
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "given_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "family_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "birth_date"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "driving_privileges"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    },
                    {
                      "id": "loyalty",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(Loyalty.shared.LOYALTY_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(Loyalty.shared.LOYALTY_NAMESPACE)", "membership_number"] },
                        { "path": ["\(Loyalty.shared.LOYALTY_NAMESPACE)", "tier"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "required": true,
                      "options": [ ["mdl"] ]
                    },
                    {
                      "required": false,
                      "options": [ ["loyalty"] ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat3, scenarioId: "mandatory_mdl_and_optional_loyalty", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main_both_selected")
                    await s.showPickSolution(useCaseIndex: 1)
                    try await s.capture("chooser_optional")
                    await s.selectSolution(useCaseIndex: 1, solutionIndex: -1)
                    try await s.capture("main_optional_omitted")
                }
            }

            // =====================================================================================
            // Category 4: 04_encryption
            // =====================================================================================
            let cat4 = "04_encryption"

            // 4.1 trusted_requester_trusted_target
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeBorderCrossingEncryptionRequest(
                    intentToRetain: false,
                    encryptionTargetRpId: SwiftConsentGoldenEcosystem.companion.RP_CBP,
                    encryptionTargetTrusted: true
                )
                try await runScenario(categoryDir: cat4, scenarioId: "trusted_requester_trusted_target", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                    await s.showFirstEncryptionTargetCertChain()
                    try await s.capture("enc_target_cert_chain")
                }
            }

            // 4.2 trusted_requester_trusted_target_multiple_choices
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeBorderCrossingEncryptionRequestMultipleChoices(
                    intentToRetain: false,
                    encryptionTargetRpId: SwiftConsentGoldenEcosystem.companion.RP_CBP,
                    encryptionTargetTrusted: true
                )
                try await runScenario(categoryDir: cat4, scenarioId: "trusted_requester_trusted_target_multiple_choices", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showPickSolution(useCaseIndex: 0)
                    try await s.capture("chooser")
                    await s.selectSolution(useCaseIndex: 0, solutionIndex: 1)
                    try await s.capture("main_switched")
                }
            }

            // 4.3 trusted_requester_trusted_target_retained
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeBorderCrossingEncryptionRequest(
                    intentToRetain: true,
                    encryptionTargetRpId: SwiftConsentGoldenEcosystem.companion.RP_CBP,
                    encryptionTargetTrusted: true
                )
                try await runScenario(categoryDir: cat4, scenarioId: "trusted_requester_trusted_target_retained", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // =====================================================================================
            // Category 5: 05_issuer_metadata
            // =====================================================================================
            let cat5 = "05_issuer_metadata"

            // 5.1 mpzpass_loyalty_card
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_MARKETPLACE,
                    origin: "https://checkout.utopiamarketplace.example.com"
                )
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdoc",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.example.loyalty.1"
                      },
                      "claims": [
                        { "path": ["org.example.loyalty.1", "given_name"] },
                        { "path": ["org.example.loyalty.1", "family_name"] },
                        { "path": ["org.example.loyalty.1", "merchant_name"] },
                        { "path": ["org.example.loyalty.1", "membership_number"] },
                        { "path": ["org.example.loyalty.1", "tier"] },
                        { "path": ["org.example.loyalty.1", "bonus_points"] },
                        { "path": ["org.example.loyalty.1", "valid_from"] },
                        { "path": ["org.example.loyalty.1", "valid_until"] }
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat5, scenarioId: "mpzpass_loyalty_card", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showDocumentDetails()
                    try await s.capture("doc_details")
                }
            }

            // =====================================================================================
            // Category 6: 06_warning_untrusted
            // =====================================================================================
            let cat6 = "06_warning_untrusted"

            // 6.1 unverified_website_age_over_18
            do {
                let pair = eco.unverifiedWebRequester(origin: "https://age-check.utopia-gaming.example.com")
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "age_over_18"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat6, scenarioId: "unverified_website_age_over_18", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 6.2 unverified_app_age_over_21
            do {
                let pair = eco.unverifiedAppRequester(appId: SwiftConsentGoldenEcosystem.companion.APP_NIGHTCLUB_SCANNER)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(categoryDir: cat6, scenarioId: "unverified_app_age_over_21", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // 6.3 untrusted_requester_pii_requested
            do {
                let pair = eco.unverifiedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_UNVERIFIED_LOUNGE)
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "given_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "family_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "birth_date"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat6, scenarioId: "untrusted_requester_pii_requested", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 6.4 trusted_requester_untrusted_encryption_target
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeBorderCrossingEncryptionRequest(
                    intentToRetain: false,
                    encryptionTargetRpId: SwiftConsentGoldenEcosystem.companion.RP_UNVERIFIED_BROKER,
                    encryptionTargetTrusted: false
                )
                try await runScenario(categoryDir: cat6, scenarioId: "trusted_requester_untrusted_encryption_target", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 6.5 anonymous_requester_trusted_target
            do {
                let pair = eco.anonymousProximityRequester()
                let pd = try await eco.executeBorderCrossingEncryptedOnlyRequest(
                    intentToRetain: false,
                    encryptionTargetRpId: SwiftConsentGoldenEcosystem.companion.RP_CBP,
                    encryptionTargetTrusted: true
                )
                try await runScenario(categoryDir: cat6, scenarioId: "anonymous_requester_trusted_target", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 6.6 untrusted_requester_intent_to_retain
            do {
                let pair = eco.unverifiedWebRequester(origin: "https://age-check.utopia-gaming.example.com")
                let pd = try await eco.executeMdocRequestWithPurposeHints(
                    docType: DrivingLicense.shared.MDL_DOCTYPE,
                    nameSpaces: [
                        DrivingLicense.shared.MDL_NAMESPACE: [
                            "age_over_21": KotlinBoolean(value: true),
                            "portrait": KotlinBoolean(value: true)
                        ]
                    ],
                    purposeHints: [:]
                )
                try await runScenario(categoryDir: cat6, scenarioId: "untrusted_requester_intent_to_retain", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // 6.7 untrusted_requester_transaction_data
            do {
                let pair = eco.unverifiedWebRequester(origin: "https://pay.unverified-merchant.example.com")
                let pd = try await eco.executePaymentCannedDcql(
                    cannedRequestId: "payment_transaction_only_conf",
                    payeeName: "Unverified Utopia Merchant",
                    amount: 299.99,
                    tipRequested: false
                )
                try await runScenario(categoryDir: cat6, scenarioId: "untrusted_requester_transaction_data", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // =====================================================================================
            // Category 7: 07_high_risk
            // =====================================================================================
            let cat7 = "07_high_risk"

            // 7.1 anonymous_website_pii_requested
            do {
                let pair = eco.anonymousWebRequester(origin: "https://verify.unknown-portal.example.com")
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "given_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "family_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "birth_date"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(categoryDir: cat7, scenarioId: "anonymous_website_pii_requested", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main_waiting")
                    await s.completeCoolOff()
                    try await s.capture("main_ready")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 7.2 trusted_requester_unusual_overbroad
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_VINEYARDS)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "full")
                try await runScenario(categoryDir: cat7, scenarioId: "trusted_requester_unusual_overbroad", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main_top")
                    await s.scrollToBottom()
                    try await s.capture("main_scrolled_waiting")
                    await s.completeCoolOff()
                    try await s.capture("main_scrolled_ready")
                }
            }

            // 7.3 trusted_requester_mismatched_purpose_hint
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_VINEYARDS)
                let pd = try await eco.executeMdocRequestWithPurposeHints(
                    docType: DrivingLicense.shared.MDL_DOCTYPE,
                    nameSpaces: [
                        DrivingLicense.shared.MDL_NAMESPACE: [
                            "age_over_21": KotlinBoolean(value: false),
                            "portrait": KotlinBoolean(value: false),
                            "given_name": KotlinBoolean(value: false),
                            "family_name": KotlinBoolean(value: false),
                            "resident_address": KotlinBoolean(value: false)
                        ]
                    ],
                    purposeHints: ["org.iso.18013.5.1.mDL": KotlinInt(int: 1)]
                )
                try await runScenario(categoryDir: cat7, scenarioId: "trusted_requester_mismatched_purpose_hint", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // 7.4 trusted_requester_anonymous_encryption_target
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeBorderCrossingEncryptionRequest(
                    intentToRetain: false,
                    encryptionTargetRpId: SwiftConsentGoldenEcosystem.companion.RP_UNVERIFIED_BROKER,
                    encryptionTargetTrusted: false,
                    includeRecipientCertificates: false
                )
                try await runScenario(categoryDir: cat7, scenarioId: "trusted_requester_anonymous_encryption_target", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            // 7.5 anonymous_website_intent_to_retain
            do {
                let pair = eco.anonymousWebRequester(origin: "https://age-check.utopia-gaming.example.com")
                let pd = try await eco.executeMdocRequestWithPurposeHints(
                    docType: DrivingLicense.shared.MDL_DOCTYPE,
                    nameSpaces: [
                        DrivingLicense.shared.MDL_NAMESPACE: [
                            "age_over_21": KotlinBoolean(value: true),
                            "portrait": KotlinBoolean(value: true)
                        ]
                    ],
                    purposeHints: [:]
                )
                try await runScenario(categoryDir: cat7, scenarioId: "anonymous_website_intent_to_retain", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // 7.6 anonymous_website_transaction_data
            do {
                let pair = eco.anonymousWebRequester(origin: "https://pay.unverified-merchant.example.com")
                let pd = try await eco.executePaymentCannedDcql(
                    cannedRequestId: "payment_transaction_only_conf",
                    payeeName: "Unverified Utopia Merchant",
                    amount: 299.99,
                    tipRequested: false
                )
                try await runScenario(categoryDir: cat7, scenarioId: "anonymous_website_transaction_data", pair: pair, presentmentData: pd) { s in
                    try await s.capture("main")
                }
            }

            // =====================================================================================
            // Category 8: 08_custom_policy
            // =====================================================================================
            let cat8 = "08_custom_policy"

            // 8.1 custom_warning_for_trusted_rp_retaining_data
            do {
                let pair = eco.trustedWebRequester(
                    rpId: SwiftConsentGoldenEcosystem.companion.RP_MARKETPLACE,
                    origin: "https://checkout.utopiamarketplace.example.com"
                )
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "name-and-address-all-stored")
                try await runScenario(
                    categoryDir: cat8,
                    scenarioId: "custom_warning_for_trusted_rp_retaining_data",
                    pair: pair,
                    presentmentData: pd,
                    classifyRiskFn: { r, t, data, sels, purposes in
                        SwiftConsentGoldenEcosystem.companion.strictRetentionClassifyRisk(
                            requester: r,
                            trustedRequesterIdentity: t,
                            presentmentData: data,
                            selections: sels,
                            purposes: purposes
                        )
                    }
                ) { s in
                    try await s.capture("main")
                }
            }

            // 8.2 custom_normal_for_allowlisted_first_party_app
            do {
                let pair = eco.unverifiedAppRequester(appId: SwiftConsentGoldenEcosystem.companion.APP_WALLET_VERIFIER)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(
                    categoryDir: cat8,
                    scenarioId: "custom_normal_for_allowlisted_first_party_app",
                    pair: pair,
                    presentmentData: pd,
                    classifyRiskFn: { r, t, data, sels, purposes in
                        SwiftConsentGoldenEcosystem.companion.allowlistAppClassifyRisk(
                            requester: r,
                            trustedRequesterIdentity: t,
                            presentmentData: data,
                            selections: sels,
                            purposes: purposes
                        )
                    }
                ) { s in
                    try await s.capture("main")
                }
            }

            // =====================================================================================
            // Category 9: 09_dark_mode
            // =====================================================================================
            let cat9 = "09_dark_mode"

            // 9.1 normal_age_verification
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_VINEYARDS)
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(
                    categoryDir: cat9,
                    scenarioId: "normal_age_verification",
                    pair: pair,
                    presentmentData: pd,
                    darkTheme: true
                ) { s in
                    try await s.capture("main")
                }
            }

            // 9.2 warning_untrusted_website
            do {
                let pair = eco.unverifiedWebRequester(origin: "https://age-check.utopia-gaming.example.com")
                let pd = try await eco.executeDrivingLicenseCannedDcql(cannedRequestId: "age_over_21_and_portrait")
                try await runScenario(
                    categoryDir: cat9,
                    scenarioId: "warning_untrusted_website",
                    pair: pair,
                    presentmentData: pd,
                    darkTheme: true
                ) { s in
                    try await s.capture("main")
                }
            }

            // 9.3 high_risk_pii_requested
            do {
                let pair = eco.anonymousWebRequester(origin: "https://verify.unknown-portal.example.com")
                let pd = try await eco.executeDcql(dcqlJson: """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "\(DrivingLicense.shared.MDL_DOCTYPE)" },
                      "claims": [
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "given_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "family_name"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "birth_date"] },
                        { "path": ["\(DrivingLicense.shared.MDL_NAMESPACE)", "portrait"] }
                      ]
                    }
                  ]
                }
                """)
                try await runScenario(
                    categoryDir: cat9,
                    scenarioId: "high_risk_pii_requested",
                    pair: pair,
                    presentmentData: pd,
                    darkTheme: true
                ) { s in
                    try await s.capture("main")
                }
            }

            // 9.4 requester_info_with_encryption_target
            do {
                let pair = eco.trustedProximityRequester(rpId: SwiftConsentGoldenEcosystem.companion.RP_AIRLINES)
                let pd = try await eco.executeBorderCrossingEncryptionRequest(
                    intentToRetain: false,
                    encryptionTargetRpId: SwiftConsentGoldenEcosystem.companion.RP_CBP,
                    encryptionTargetTrusted: true
                )
                try await runScenario(
                    categoryDir: cat9,
                    scenarioId: "requester_info_with_encryption_target",
                    pair: pair,
                    presentmentData: pd,
                    darkTheme: true
                ) { s in
                    await s.showRequesterInfo()
                    try await s.capture("requester_info")
                }
            }

            if mismatches.isEmpty {
                let summary = "OK: Captured \(totalCaptured) SwiftUI golden screenshots.\n"
                try? summary.write(toFile: statusFilePath, atomically: true, encoding: .utf8)
                print(summary)
                exit(0)
            } else {
                let summary = "FAILED (\(mismatches.count) mismatches):\n" + mismatches.joined(separator: "\n") + "\n"
                try? summary.write(toFile: statusFilePath, atomically: true, encoding: .utf8)
                print(summary)
                exit(1)
            }
        } catch {
            let errText = "ERROR: \(error)\n"
            try? errText.write(toFile: statusFilePath, atomically: true, encoding: .utf8)
            print(errText)
            exit(1)
        }
    }
}

@MainActor
final class GoldenScenarioScope {
    private static let width: CGFloat = 412.0
    private static let maxHeight: CGFloat = 915.0

    let categoryDir: String
    let scenarioId: String
    let goldenRootDir: String
    let updateGoldens: Bool
    let hostingController: UIHostingController<AnyView>
    let controller: ConsentTestController
    let onCaptured: () -> Void
    let onMismatch: (String) -> Void
    private var keepMaxHeight: Bool = false

    init(
        categoryDir: String,
        scenarioId: String,
        goldenRootDir: String,
        updateGoldens: Bool,
        hostingController: UIHostingController<AnyView>,
        controller: ConsentTestController,
        onCaptured: @escaping () -> Void,
        onMismatch: @escaping (String) -> Void
    ) {
        self.categoryDir = categoryDir
        self.scenarioId = scenarioId
        self.goldenRootDir = goldenRootDir
        self.updateGoldens = updateGoldens
        self.hostingController = hostingController
        self.controller = controller
        self.onCaptured = onCaptured
        self.onMismatch = onMismatch
    }

    func pumpFrames(_ count: Int = 8) async {
        for _ in 0..<count {
            hostingController.view.setNeedsLayout()
            hostingController.view.layoutIfNeeded()
            try? await Task.sleep(for: .milliseconds(15))
            await Task.yield()
        }
    }

    func waitForReady() async {
        for _ in 0..<60 {
            await pumpFrames(2)
            if controller.isReady && controller.scrollContentHeight > 0 {
                break
            }
        }
        await pumpFrames(6)
    }

    func capture(_ stepId: String) async throws {
        if !keepMaxHeight {
            hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
            await waitForReady()

            let currentH = hostingController.view.frame.height
            let nonScrollHeight = max(0, currentH - controller.scrollContainerHeight)
            let idealHeight = min(Self.maxHeight, max(200.0, ceil(nonScrollHeight + controller.scrollContentHeight + 2.0)))
            if abs(currentH - idealHeight) > 1.0 {
                hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: idealHeight)
                await pumpFrames(8)
            }
        } else {
            await pumpFrames(8)
        }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 2.0
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(bounds: hostingController.view.bounds, format: format)
        let image = renderer.image { _ in
            hostingController.view.drawHierarchy(in: hostingController.view.bounds, afterScreenUpdates: true)
        }

        guard let pngData = image.pngData() else {
            onMismatch("\(categoryDir)/\(scenarioId)__\(stepId).png: Failed to encode PNG")
            return
        }

        let dirUrl = URL(fileURLWithPath: "\(goldenRootDir)/\(categoryDir)")
        try FileManager.default.createDirectory(at: dirUrl, withIntermediateDirectories: true)
        let fileUrl = dirUrl.appendingPathComponent("\(scenarioId)__\(stepId).png")

        if updateGoldens {
            try pngData.write(to: fileUrl, options: .atomic)
        } else {
            if !FileManager.default.fileExists(atPath: fileUrl.path) {
                onMismatch("Missing golden file: \(fileUrl.path)")
                return
            }
        }
        onCaptured()
    }

    func showDocumentDetails(useCaseIndex: Int = 0, matchIndex: Int = 0) async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.showDocumentDetails(useCaseIndex: useCaseIndex, matchIndex: matchIndex)
        await waitForReady()
    }

    func showRequesterInfo() async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.showRequesterInfo()
        await waitForReady()
    }

    func showFirstRequesterCertChain() async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.showFirstRequesterCertChain()
        await waitForReady()
    }

    func showFirstEncryptionTargetCertChain() async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.showFirstEncryptionTargetCertChain()
        await waitForReady()
    }

    func showPickSolution(useCaseIndex: Int = 0) async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.showPickSolution(useCaseIndex: useCaseIndex)
        await waitForReady()
    }

    func selectSolution(useCaseIndex: Int = 0, solutionIndex: Int) async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.selectSolution(useCaseIndex: useCaseIndex, solutionIndex: solutionIndex)
        await waitForReady()
    }

    func showTransactionDetails() async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.showTransactionDetails()
        await waitForReady()
    }

    func pop() async {
        keepMaxHeight = false
        hostingController.view.frame = CGRect(x: 0, y: 0, width: Self.width, height: Self.maxHeight)
        controller.pop()
        await waitForReady()
    }

    func scrollToBottom() async {
        keepMaxHeight = true
        controller.scrollToBottom()
        await pumpFrames(10)
    }

    func completeCoolOff() async {
        controller.completeCoolOff()
        await pumpFrames(6)
    }
}

final class GoldenCanvasViewController: UIViewController {
    private var didStart = false

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !didStart else { return }
        didStart = true
        Task { @MainActor in
            await ConsentGoldenScreenshotRunner.runAll(in: self)
        }
    }

    func mount(_ child: UIViewController) {
        addChild(child)
        child.view.frame = CGRect(x: 0, y: 0, width: 412, height: 915)
        child.view.autoresizingMask = []
        view.addSubview(child.view)
        child.didMove(toParent: self)
    }

    func unmount(_ child: UIViewController) {
        child.willMove(toParent: nil)
        child.view.removeFromSuperview()
        child.removeFromParent()
    }
}

struct ConsentGoldenScreenshotHostView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> GoldenCanvasViewController {
        let vc = GoldenCanvasViewController()
        vc.view.backgroundColor = .black
        return vc
    }

    func updateUIViewController(_ uiViewController: GoldenCanvasViewController, context: Context) {}
}

// MARK: - Custom SwiftUI Transaction Renderers for Golden Scenarios

private func formatGoldenAmount(_ amount: Double) -> String {
    let roundedCents = Int64((amount * 100.0).rounded())
    return String(format: "%lld.%02lld", roundedCents / 100, abs(roundedCents % 100))
}

private func selectedGoldenFare(_ context: TransactionDataContext) -> GoldenTransitFareTransaction.Fare {
    let payload = context.transactionData.first!.payload as! GoldenTransitFareTransaction.Payload
    let fareId = (context.userInput as? GoldenTransitFareTransaction.UserInput)?.fareId
    return payload.fares.first { $0.id == fareId } ?? GoldenTransitFareTransaction.shared.defaultFare(payload: payload)
}

private struct GoldenDetailRowsView: View {
    let title: String
    let rows: [(String, String)]

    var body: some View {
        SegmentedItemList(title: title) {
            ForEach(0..<rows.count, id: \.self) { idx in
                let row = rows[idx]
                SegmentedItemHeadingAndText(heading: row.0, text: row.1)
            }
        }
    }
}

private func buildGoldenTransactionDataRendererRepository() -> TransactionDataRendererRepository {
    let renderers = TransactionDataRendererRepository()

    let transitRenderer = TransactionDataRendererSwiftUi(
        summary: { context in
            let payload = context.transactionData.first!.payload as! GoldenTransitFareTransaction.Payload
            let fare = selectedGoldenFare(context)
            return AnyView(
                VStack(alignment: .leading, spacing: 6) {
                    Text("Pay \(formatGoldenAmount(fare.amount)) \(payload.currency)")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.primary)
                    Text("\(payload.operator) · \(payload.zones) · \(fare.validity)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        ForEach(0..<payload.fares.count, id: \.self) { idx in
                            let option = payload.fares[idx]
                            let isSelected = (option.id == fare.id)
                            Button {
                                context.onUserInputChanged(GoldenTransitFareTransaction.UserInput(fareId: option.id))
                            } label: {
                                Text(option.name)
                                    .font(.system(size: 13, weight: isSelected ? .bold : .regular))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(isSelected ? Color.accentColor.opacity(0.15) : Color(uiColor: .secondarySystemBackground))
                                    .foregroundStyle(isSelected ? Color.accentColor : Color.primary)
                                    .clipShape(Capsule())
                                    .overlay(
                                        Capsule()
                                            .stroke(isSelected ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: 1)
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            )
        },
        details: { context in
            let payload = context.transactionData.first!.payload as! GoldenTransitFareTransaction.Payload
            let fare = selectedGoldenFare(context)
            let rows: [(String, String)] = [
                ("Operator", payload.operator),
                ("Zones", payload.zones),
                ("Selected", "\(fare.name), \(formatGoldenAmount(fare.amount)) \(payload.currency)"),
                ("Valid", fare.validity)
            ]
            return AnyView(GoldenDetailRowsView(title: "Ticket details", rows: rows))
        }
    )

    let ap2Renderer = TransactionDataRendererSwiftUi(
        summary: { context in
            let payload = context.transactionData.first!.payload as! GoldenAp2MandateTransaction.Payload
            return AnyView(
                VStack(alignment: .leading, spacing: 4) {
                    Text("Authorize up to \(formatGoldenAmount(payload.maxAmount)) \(payload.currency)")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.primary)
                    Text("Delegate to \(payload.agentName)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(payload.intentDescription)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("\(payload.constraints) · Expires \(payload.validUntil)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            )
        },
        details: { context in
            let payload = context.transactionData.first!.payload as! GoldenAp2MandateTransaction.Payload
            let rows: [(String, String)] = [
                ("Mandate ID", payload.mandateId),
                ("Delegate agent", payload.agentName),
                ("Agent DID", payload.agentId),
                ("Merchant", payload.merchant),
                ("Authorized intent", payload.intentDescription),
                ("Maximum amount", "\(formatGoldenAmount(payload.maxAmount)) \(payload.currency)"),
                ("Constraints", payload.constraints),
                ("Expires", payload.validUntil)
            ]
            return AnyView(GoldenDetailRowsView(title: "AP2 intent mandate", rows: rows))
        }
    )

    renderers.addRenderer(identifier: GoldenTransitFareTransaction.shared.identifier, renderer: transitRenderer)
    renderers.addRenderer(identifier: GoldenAp2MandateTransaction.shared.identifier, renderer: ap2Renderer)
    return renderers
}
