import SwiftUI
import UIKit

public typealias ClassifyPurposesSwiftFn = (
    _ requester: Requester,
    _ trustedRequesterIdentity: TrustedRequesterIdentity?,
    _ presentmentData: PresentmentData,
    _ selections: [KotlinInt]
) -> Set<PresentmentPurpose>

public typealias ClassifyRiskSwiftFn = (
    _ requester: Requester,
    _ trustedRequesterIdentity: TrustedRequesterIdentity?,
    _ presentmentData: PresentmentData,
    _ selections: [KotlinInt],
    _ purposes: Set<PresentmentPurpose>
) -> ConsentRiskAssessment

private func getIconName(claim: Claim) -> String {
    if let attribute = claim.attribute {
        switch attribute.icon {
        case .person: return "person"
        case .today: return "calendar.badge.plus"
        case .dateRange: return "calendar"
        case .calendarClock: return "calendar"
        case .accountBalance: return "building.columns"
        case .numbers: return "number"
        case .accountBox: return "person.crop.circle"
        case .directionsCar: return "car"
        case .language: return "globe"
        case .emergency: return "staroflife"
        case .place: return "mappin.and.ellipse"
        case .signature: return "signature"
        case .militaryTech: return "star.circle"
        case .stars: return "star.circle"
        case .face: return "face.smiling"
        case .fingerprint: return "touchid"
        case .eyeTracking: return "eye"
        case .airportShuttle: return "bus"
        case .panoramaWideAngle: return "pano"
        case .image: return "photo"
        case .locationCity: return "building.2"
        case .directions: return "arrow.trianglehead.turn.up.right.diamond"
        case .house: return "house"
        case .flag: return "flag"
        case .apartment: return "building.2"
        case .languageJapaneseKana: return "character.bubble"
        case .globe: return "globe"
        case .phone: return "phone"
        case .badge: return "person.crop.circle"
        case .email: return "envelope"
        case .none: return "person"
        }
    }
    return "person"
}

private extension Array where Element == Claim {
    func sortedBySensitivity() -> [Claim] {
        return self.sorted { a, b in
            let sensA = (a.attribute?.sensitivity ?? DocumentAttributeSensitivity.pii).ordinal
            let sensB = (b.attribute?.sensitivity ?? DocumentAttributeSensitivity.pii).ordinal
            return sensA > sensB
        }
    }

    func salientClaims() -> [Claim] {
        let filtered = self.filter { claim in
            let sensitivity = claim.attribute?.sensitivity
            return sensitivity != DocumentAttributeSensitivity.issuer &&
                sensitivity != DocumentAttributeSensitivity.validity
        }
        return filtered.isEmpty ? self : filtered
    }
}

private extension PresentmentCredentialMatch {
    func resolveTypeDisplayName() -> String {
        if let typeDisplayName = credential.document.typeDisplayName {
            return typeDisplayName
        }
        if let src = source as? PresentmentCredentialMatchSourceIso18013 {
            return src.docRequest.docType
        }
        if let src = source as? PresentmentCredentialMatchSourceOpenID4VP {
            return src.credentialQuery.mdocDocType
                ?? src.credentialQuery.vctValues?.first
                ?? src.credentialQuery.id
        }
        return "Unknown Document"
    }
}

private struct ClaimSection {
    let storesData: Bool
    let encryptionRequested: Bool
    let encryptionTargetCertificationChain: X509CertChain?
    let encryptionTargetTrustMetadata: TrustMetadata?
    let claims: [Claim]
}

private struct DocumentMatchGroup {
    let firstMatchIndex: Int
    let matches: [PresentmentCredentialMatch]
    let claimSections: [ClaimSection]

    var credential: Credential { matches[0].credential }
    var document: Document { credential.document }
}

private struct RecipientBucketKey: Equatable {
    let encryptionRequested: Bool
    let encryptionTargetCertificationChain: X509CertChain?
    let encryptionTargetTrustMetadata: TrustMetadata?
}

private func groupMatchesByDocument(_ matches: [PresentmentCredentialMatch]) -> [DocumentMatchGroup] {
    var docOrder: [String] = []
    var groupsByDocId: [String: (firstIndex: Int, matches: [PresentmentCredentialMatch])] = [:]
    for (index, match) in matches.enumerated() {
        let docId = match.credential.document.identifier
        if var existing = groupsByDocId[docId] {
            existing.matches.append(match)
            groupsByDocId[docId] = existing
        } else {
            docOrder.append(docId)
            groupsByDocId[docId] = (firstIndex: index, matches: [match])
        }
    }
    return docOrder.compactMap { docId in
        guard let entry = groupsByDocId[docId] else { return nil }
        return DocumentMatchGroup(
            firstMatchIndex: entry.firstIndex,
            matches: entry.matches,
            claimSections: buildClaimSections(entry.matches)
        )
    }
}

private func buildClaimSections(_ groupMatches: [PresentmentCredentialMatch]) -> [ClaimSection] {
    let orderedMatches = groupMatches.filter { !$0.encryptionRequested } +
        groupMatches.filter { $0.encryptionRequested }
    var byRecipient: [(key: RecipientBucketKey, matches: [PresentmentCredentialMatch])] = []
    for match in orderedMatches {
        let key = RecipientBucketKey(
            encryptionRequested: match.encryptionRequested,
            encryptionTargetCertificationChain: match.encryptionTargetCertificationChain,
            encryptionTargetTrustMetadata: match.encryptionTargetTrustMetadata
        )
        if let idx = byRecipient.firstIndex(where: { $0.key == key }) {
            byRecipient[idx].matches.append(match)
        } else {
            byRecipient.append((key: key, matches: [match]))
        }
    }

    var sections: [ClaimSection] = []
    for (recipient, recipientMatches) in byRecipient {
        var notStoredDedup: [Claim] = []
        for match in recipientMatches {
            for (key, claim) in match.claims {
                if let mdocReq = key as? MdocRequestedClaim, mdocReq.intentToRetain {
                    continue
                }
                if !notStoredDedup.contains(claim) {
                    notStoredDedup.append(claim)
                }
            }
        }
        let notStoredClaims = notStoredDedup.sortedBySensitivity()

        var storedDedup: [Claim] = []
        for match in recipientMatches {
            for (key, claim) in match.claims {
                if let mdocReq = key as? MdocRequestedClaim, mdocReq.intentToRetain {
                    if !storedDedup.contains(claim) {
                        storedDedup.append(claim)
                    }
                }
            }
        }
        let storedClaims = storedDedup.sortedBySensitivity()

        if !notStoredClaims.isEmpty {
            sections.append(
                ClaimSection(
                    storesData: false,
                    encryptionRequested: recipient.encryptionRequested,
                    encryptionTargetCertificationChain: recipient.encryptionTargetCertificationChain,
                    encryptionTargetTrustMetadata: recipient.encryptionTargetTrustMetadata,
                    claims: notStoredClaims
                )
            )
        }
        if !storedClaims.isEmpty {
            sections.append(
                ClaimSection(
                    storesData: true,
                    encryptionRequested: recipient.encryptionRequested,
                    encryptionTargetCertificationChain: recipient.encryptionTargetCertificationChain,
                    encryptionTargetTrustMetadata: recipient.encryptionTargetTrustMetadata,
                    claims: storedClaims
                )
            )
        }
    }
    return sections
}

private enum ClaimsGridCell {
    case claim(Claim)
    case more(Int)
    case viewDetails
}

private struct ClaimsGridView: View {
    let allClaims: [Claim]
    let onViewDetailsClicked: (() -> Void)?

    init(allClaims: [Claim], onViewDetailsClicked: (() -> Void)? = nil) {
        self.allClaims = allClaims
        self.onViewDetailsClicked = onViewDetailsClicked
    }

    var body: some View {
        let salient = allClaims.salientClaims()
        let (visibleClaims, hiddenCount): ([Claim], Int) = {
            if allClaims.count - salient.count == 1 {
                return (allClaims, 0)
            } else {
                return (salient, allClaims.count - salient.count)
            }
        }()

        let cells: [ClaimsGridCell] = {
            var list: [ClaimsGridCell] = visibleClaims.map { .claim($0) }
            if hiddenCount > 0 {
                list.append(.more(hiddenCount))
            }
            if onViewDetailsClicked != nil {
                list.append(.viewDetails)
            }
            return list
        }()

        let columns = [
            GridItem(.flexible(), alignment: .leading),
            GridItem(.flexible(), alignment: .leading)
        ]
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(0..<cells.count, id: \.self) { idx in
                switch cells[idx] {
                case .claim(let claim):
                    HStack(spacing: 8) {
                        Image(systemName: getIconName(claim: claim))
                            .imageScale(.small)
                        Text(claim.displayName)
                            .font(.subheadline)
                            .multilineTextAlignment(.leading)
                    }
                    .padding(.vertical, 2)
                case .more(let count):
                    HStack(spacing: 8) {
                        Image(systemName: "ellipsis")
                            .imageScale(.small)
                        Text("And \(count) more")
                            .font(.subheadline)
                            .multilineTextAlignment(.leading)
                    }
                    .padding(.vertical, 2)
                case .viewDetails:
                    if let onViewDetailsClicked = onViewDetailsClicked {
                        ViewDetailsChipButton(action: onViewDetailsClicked)
                            .padding(.vertical, 2)
                    }
                }
            }
        }
        .foregroundStyle(.primary)
    }
}

private struct ViewDetailsChipButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("View details")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(Color.secondary.opacity(0.4), lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

private let tipOptions: [(percent: Double, label: String)] = [
    (0.0, "No tip"),
    (10.0, "10%"),
    (15.0, "15%"),
    (20.0, "20%"),
    (25.0, "25%")
]

private func formatAmount(_ amount: Double) -> String {
    let roundedCents = Int64((amount * 100.0).rounded())
    let dollars = roundedCents / 100
    let cents = abs(roundedCents % 100)
    return String(format: "%lld.%02lld", dollars, cents)
}

private struct ChipFlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var currentX: CGFloat = 0
        var currentY: CGFloat = 0
        var lineHeight: CGFloat = 0
        var maxRowWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if currentX + size.width > maxWidth && currentX > 0 {
                maxRowWidth = max(maxRowWidth, currentX - spacing)
                currentX = 0
                currentY += lineHeight + lineSpacing
                lineHeight = 0
            }
            currentX += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        maxRowWidth = max(maxRowWidth, max(0, currentX - spacing))
        let totalHeight = currentY + lineHeight
        return CGSize(width: min(maxWidth, maxRowWidth), height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var currentX: CGFloat = bounds.minX
        var currentY: CGFloat = bounds.minY
        var lineHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if currentX + size.width > bounds.maxX && currentX > bounds.minX {
                currentX = bounds.minX
                currentY += lineHeight + lineSpacing
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: currentX, y: currentY), proposal: ProposedViewSize(size))
            currentX += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

private struct DisplayTransactionData: View {
    let transactionData: TransactionData<AnyObject>
    let hasClaims: Bool
    let userInput: TransactionUserInput?
    let onUserInputChanged: (TransactionUserInput) -> Void

    var body: some View {
        if transactionData.type == PaymentTransaction.shared || transactionData.type.identifier == PaymentTransaction.shared.identifier {
            if let payload = transactionData.payload as? PaymentTransaction.Payload {
                let tipPercent = (userInput as? PaymentTransaction.UserInput)?.tipPercent ?? 0.0
                let headerText = hasClaims ? "This payment will also be approved:" : "This payment will be approved:"
                VStack(alignment: .leading, spacing: 6) {
                    Text(headerText)
                        .font(.subheadline.weight(.semibold))
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)

                    if !payload.payee.name.isEmpty {
                        HStack(spacing: 8) {
                            Image(systemName: "storefront")
                                .imageScale(.small)
                            Text("Payee: \(payload.payee.name)")
                                .font(.subheadline)
                        }
                    }

                    let amountText: String = {
                        if payload.tipRequested?.boolValue == true && tipPercent > 0.0 {
                            let tipAmount = ceil(payload.amount * tipPercent) / 100.0
                            let totalAmount = payload.amount + tipAmount
                            return "Amount: \(formatAmount(totalAmount)) \(payload.currency) (tip: \(formatAmount(tipAmount)) \(payload.currency))"
                        } else {
                            return "Amount: \(formatAmount(payload.amount)) \(payload.currency)"
                        }
                    }()

                    HStack(spacing: 8) {
                        Image(systemName: "creditcard")
                            .imageScale(.small)
                        Text(amountText)
                            .font(.subheadline)
                    }

                    if payload.tipRequested?.boolValue == true {
                        Text("Add tip")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.top, 4)

                        ChipFlowLayout(spacing: 8, lineSpacing: 6) {
                            ForEach(tipOptions, id: \.percent) { option in
                                let isSelected = (tipPercent == option.percent)
                                Button(action: {
                                    onUserInputChanged(PaymentTransaction.UserInput(tipPercent: option.percent))
                                }) {
                                    Text(option.label)
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
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else if transactionData.type == PingTransaction.shared || transactionData.type.identifier == PingTransaction.shared.identifier {
            if let payload = transactionData.payload as? PingTransaction.Payload {
                let headerText = hasClaims ? "This test \"ping\" transaction will also be approved:" : "This test \"ping\" transaction will be approved:"
                VStack(alignment: .leading, spacing: 6) {
                    Text(headerText)
                        .font(.subheadline.weight(.semibold))
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    if let str = payload.string {
                        HStack(spacing: 8) {
                            Image(systemName: "info.circle")
                                .imageScale(.small)
                            Text("String: \(str)")
                                .font(.subheadline)
                        }
                    }
                    if let blob = payload.blob {
                        let byteArray = blob.toByteArray(startIndex: 0, endIndex: blob.size)
                        HStack(spacing: 8) {
                            Image(systemName: "info.circle")
                                .imageScale(.small)
                            Text("Blob: \(byteArray.toBase64Url())")
                                .font(.subheadline)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            let headerText = hasClaims ? "This \(transactionData.type.displayName) transaction will also be approved:" : "This \(transactionData.type.displayName) transaction will be approved:"
            VStack(alignment: .leading, spacing: 6) {
                Text(headerText)
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct RequesterDisplayData {
    var name: String? = nil
    var iconData: ByteString? = nil
    var iconUrl: String? = nil
    var disclaimer: String? = nil
}

private func buildRequesterDisplayData(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?
) -> RequesterDisplayData {
    if let trustMetadata = trustedRequesterIdentity?.trustMetadata {
        return RequesterDisplayData(
            name: trustMetadata.displayName,
            iconData: trustMetadata.displayIcon,
            iconUrl: trustMetadata.displayIconUrl,
            disclaimer: trustMetadata.disclaimer
        )
    } else if requester.isWebOrigin {
        let origin = requester.origin ?? ""
        return RequesterDisplayData(
            name: origin.isEmpty ? "Unknown website" : origin
        )
    } else if let appId = requester.appId {
        return RequesterDisplayData(
            name: appId
        )
    } else {
        return RequesterDisplayData()
    }
}

private func resolveRequesterHeadlineName(
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    trustedRequesterIdentity: TrustedRequesterIdentity?
) -> String {
    return requesterDisplayData.name
        ?? trustedRequesterIdentity?.identity.certChain.certificates.last?.subject.name
        ?? (requester.requesterIdentities.isEmpty ? "Anonymous requester" : "Unknown requester")
}

private func calcSharedWithText(
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    storesData: Bool,
    encryptionRequested: Bool,
    encryptionTargetTrustMetadata: TrustMetadata?
) -> String {
    let knownRequesterName: String? = {
        if let name = requesterDisplayData.name {
            return name
        } else if requester.isWebOrigin, let origin = requester.origin {
            return origin
        } else if let appId = requester.appId {
            return appId
        } else {
            return nil
        }
    }()

    if !encryptionRequested {
        if storesData {
            if let name = knownRequesterName {
                return "This data will be stored by \(name):"
            } else {
                return "This data will be stored:"
            }
        } else {
            if let name = knownRequesterName {
                return "This data will be shared with \(name):"
            } else {
                return "This data will be shared:"
            }
        }
    }

    if let encTargetName = encryptionTargetTrustMetadata?.displayName {
        if storesData {
            if let name = knownRequesterName {
                return "\(name) is requesting this data on behalf of \(encTargetName) who will store it:"
            } else {
                return "This data is requested on behalf of \(encTargetName) who will store it:"
            }
        } else {
            if let name = knownRequesterName {
                return "\(name) is requesting this data on behalf of \(encTargetName):"
            } else {
                return "This data is requested on behalf of \(encTargetName):"
            }
        }
    } else {
        if storesData {
            if let name = knownRequesterName {
                return "\(name) is requesting this data on behalf of an unknown party who will store it:"
            } else {
                return "This data is requested on behalf of an unknown party who will store it:"
            }
        } else {
            if let name = knownRequesterName {
                return "\(name) is requesting this data on behalf of an unknown party:"
            } else {
                return "This data is requested on behalf of an unknown party:"
            }
        }
    }
}

private let orderedPresentmentPurposes: [PresentmentPurpose] = [
    .identityVerification,
    .ageVerification,
    .drivingPrivileges,
    .loyaltyVerification,
    .ticketVerification,
    .addressVerification,
    .payment,
]

private func purposeItemText(purpose: PresentmentPurpose, includePossessive: Bool) -> String {
    switch purpose {
    case .payment:
        return "payment"
    case .ageVerification:
        return includePossessive ? "your age" : "age"
    case .drivingPrivileges:
        return includePossessive ? "your driving privileges" : "driving privileges"
    case .addressVerification:
        return includePossessive ? "your address" : "address"
    case .identityVerification:
        return includePossessive ? "your identity" : "identity"
    case .loyaltyVerification:
        return includePossessive ? "your loyalty membership" : "loyalty membership"
    case .ticketVerification:
        return includePossessive ? "your ticket" : "ticket"
    }
}

private func calcPurposeSubtitle(purposes: Set<PresentmentPurpose>) -> String {
    let ordered = orderedPresentmentPurposes.filter { purposes.contains($0) }
    switch ordered.count {
    case 0:
        return "is requesting information"
    case 1:
        switch ordered[0] {
        case .payment: return "is requesting payment"
        case .ageVerification: return "is requesting your age"
        case .drivingPrivileges: return "is requesting your driving privileges"
        case .addressVerification: return "is requesting your address"
        case .identityVerification: return "is requesting your identity"
        case .loyaltyVerification: return "is requesting your loyalty membership"
        case .ticketVerification: return "is requesting your ticket"
        }
    default:
        var usedPossessive = false
        let items: [String] = ordered.map { purpose in
            let includePossessive = !usedPossessive
            if purpose != .payment {
                usedPossessive = true
            }
            return purposeItemText(purpose: purpose, includePossessive: includePossessive)
        }
        if items.count == 2 {
            return "is requesting \(items[0]) and \(items[1])"
        } else {
            let prefix = items.dropLast().joined(separator: ", ")
            return "is requesting \(prefix), and \(items.last!)"
        }
    }
}

private struct RelyingPartySection: View {
    let requester: Requester
    let requesterDisplayData: RequesterDisplayData
    let trustedRequesterIdentity: TrustedRequesterIdentity?
    let purposes: Set<PresentmentPurpose>

    var body: some View {
        let requesterName = resolveRequesterHeadlineName(
            requester: requester,
            requesterDisplayData: requesterDisplayData,
            trustedRequesterIdentity: trustedRequesterIdentity
        )

        VStack(spacing: 4) {
            Group {
                if let iconData = requesterDisplayData.iconData,
                   let uiImage = UIImage(data: iconData.toNSData()) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 80, height: 80)
                } else if let iconUrl = requesterDisplayData.iconUrl,
                          let url = URL(string: iconUrl) {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image {
                            image
                                .resizable()
                                .scaledToFit()
                                .frame(width: 80, height: 80)
                        } else if phase.error != nil {
                            Image(systemName: requester.isWebOrigin ? "globe" : "building.2")
                                .font(.system(size: 52, weight: .light))
                                .foregroundStyle(.secondary)
                                .frame(width: 80, height: 80)
                        } else {
                            ProgressView()
                                .frame(width: 80, height: 80)
                        }
                    }
                } else {
                    Image(systemName: requester.isWebOrigin ? "globe" : "building.2")
                        .font(.system(size: 52, weight: .light))
                        .foregroundStyle(.secondary)
                        .frame(width: 80, height: 80)
                }
            }
            .padding(.bottom, 8)

            Text(requesterName)
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            Text(calcPurposeSubtitle(purposes: purposes))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
    }
}

private func isRequesterIdentifiable(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?
) -> Bool {
    return trustedRequesterIdentity != nil ||
        (requester.isWebOrigin && !(requester.origin ?? "").isEmpty) ||
        requester.appId != nil ||
        !requester.requesterIdentities.isEmpty
}

private func calcRelyingPartySentences(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    trustMetadata: TrustMetadata?,
    showUntrustedWarning: Bool,
    showAnonymousWarning: Bool,
    requesterHistoryLoaded: Bool,
    requesterHistoryEntry: RequesterHistoryEntry?,
    hasEncryptionTargetPrivacyPolicy: Bool
) -> [String] {
    let relyingPartyName: String? = trustMetadata?.displayName
        ?? trustedRequesterIdentity?.identity.certChain.certificates.last?.subject.name
        ?? (requester.origin?.isEmpty == false ? requester.origin : nil)
        ?? requester.appId

    let untrustedSentence: String? = {
        if showAnonymousWarning {
            if requester.isWebOrigin {
                return "The website requesting this data is unknown. Make sure you are comfortable sharing this data"
            } else if requester.appId != nil {
                return "The application requesting this data is unknown. Make sure you are comfortable sharing this data"
            } else {
                return "The identity reader requesting this data is anonymous. Make sure you are comfortable sharing this data"
            }
        } else if showUntrustedWarning {
            if requester.isWebOrigin {
                return "The website requesting this data is unknown. Make sure you are comfortable sharing this data"
            } else if requester.appId != nil {
                return "The application requesting this data is unknown. Make sure you are comfortable sharing this data"
            } else {
                return "The identity reader requesting this data is unknown. Make sure you are comfortable sharing this data"
            }
        } else {
            return nil
        }
    }()

    let historySentence: String? = {
        guard requesterHistoryLoaded else { return nil }
        if let name = relyingPartyName {
            if requesterHistoryEntry != nil {
                return "You have shared with **\(name)** before"
            } else {
                return "This is the first time you are sharing with **\(name)**"
            }
        } else if isRequesterIdentifiable(requester: requester, trustedRequesterIdentity: trustedRequesterIdentity) {
            if requesterHistoryEntry != nil {
                return "You have shared with this requester before"
            } else {
                return "This is the first time you are sharing with this requester"
            }
        } else {
            return nil
        }
    }()

    let moreInfoSentence: String = {
        if trustMetadata?.privacyPolicyUrl != nil || hasEncryptionTargetPrivacyPolicy {
            return "[More information and privacy policy](https://multipaz.org/requester_info)"
        } else {
            return "[More information](https://multipaz.org/requester_info)"
        }
    }()

    var list: [String] = []
    if let untrustedSentence = untrustedSentence {
        list.append(untrustedSentence)
    }
    if let historySentence = historySentence {
        list.append(historySentence)
    }
    list.append(moreInfoSentence)
    return list
}

private let orderedRiskReasons: [ConsentRiskReason] = [
    .untrustedRequester,
    .anonymousRequester,
    .unusualRequest,
    .piiRequested,
    .untrustedEncryptionTarget,
    .intentToRetain,
    .transactionData,
]

private func calcRelyingPartyParagraphs(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    trustMetadata: TrustMetadata?,
    riskAssessment: ConsentRiskAssessment,
    requesterHistoryLoaded: Bool,
    requesterHistoryEntry: RequesterHistoryEntry?,
    hasEncryptionTargetPrivacyPolicy: Bool
) -> [String] {
    let sentences = calcRelyingPartySentences(
        requester: requester,
        trustedRequesterIdentity: trustedRequesterIdentity,
        trustMetadata: trustMetadata,
        showUntrustedWarning: riskAssessment.reasons.contains(.untrustedRequester),
        showAnonymousWarning: riskAssessment.reasons.contains(.anonymousRequester),
        requesterHistoryLoaded: requesterHistoryLoaded,
        requesterHistoryEntry: requesterHistoryEntry,
        hasEncryptionTargetPrivacyPolicy: hasEncryptionTargetPrivacyPolicy
    )

    var paragraphs: [String] = []
    if !sentences.isEmpty {
        let joined = sentences.map { sentence in
            var s = sentence
            while s.hasSuffix(".") {
                s.removeLast()
            }
            return s
        }.joined(separator: ". ") + "."
        paragraphs.append(joined)
    }

    for reason in orderedRiskReasons where riskAssessment.reasons.contains(reason) {
        switch reason {
        case .untrustedRequester, .anonymousRequester:
            break
        case .unusualRequest:
            paragraphs.append("This requester is asking for an unusual combination of information. Make sure you trust them before sharing.")
        case .piiRequested:
            paragraphs.append("This request includes personal information that can be used to identify you. Only continue if you trust the requester.")
        case .untrustedEncryptionTarget:
            paragraphs.append("Part of your information will be encrypted for an unknown recipient. Only continue if you trust the requester.")
        case .intentToRetain:
            paragraphs.append("This requester intends to store your information. Only continue if you trust the requester.")
        case .transactionData:
            paragraphs.append("This request authorizes a transaction with an unverified requester. Only continue if you trust the requester.")
        }
    }

    return paragraphs
}

private struct RelyingPartyInfoContent: View {
    let paragraphs: [String]
    let disclaimer: String?
    let showWarningIcon: Bool
    let onShowRequesterInfo: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !paragraphs.isEmpty {
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: showWarningIcon ? "exclamationmark.triangle" : "info.circle")
                        .imageScale(.medium)
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, paragraph in
                            let attributed = (try? AttributedString(markdown: paragraph)) ?? AttributedString(paragraph)
                            Text(attributed)
                                .font(.footnote)
                                .multilineTextAlignment(.leading)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .environment(\.openURL, OpenURLAction { _ in
                    onShowRequesterInfo()
                    return .handled
                })
            }

            if let disclaimer = disclaimer {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "info.circle")
                        .imageScale(.medium)
                    Text(disclaimer)
                        .font(.footnote)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CredentialViewerRow: View {
    let document: Document
    let showChevron: Bool
    let onChevronClicked: () -> Void

    var body: some View {
        let rowContent = HStack(spacing: 12) {
            Image(uiImage: document.renderCardArt())
                .resizable()
                .scaledToFit()
                .frame(width: 60, height: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(document.displayName ?? "No Document Title")
                    .font(.headline)
                    .foregroundStyle(.primary)
                if let typeDisplayName = document.typeDisplayName {
                    Text(typeDisplayName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if showChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
        }

        if showChevron {
            Button(action: onChevronClicked) {
                rowContent
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(Color(UIColor.separator), lineWidth: 1)
                    )
                    .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
        } else {
            rowContent
        }
    }
}

private struct CredentialViewerNotSelectedRow: View {
    let typeDisplayName: String
    let showChevron: Bool
    let onChevronClicked: () -> Void

    var body: some View {
        let rowContent = HStack(spacing: 12) {
            Image(systemName: "nosign")
                .font(.system(size: 22))
                .foregroundStyle(.secondary)
                .frame(width: 60, height: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text("No document shared")
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(typeDisplayName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if showChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
        }

        if showChevron {
            Button(action: onChevronClicked) {
                rowContent
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(Color(UIColor.separator), lineWidth: 1)
                    )
                    .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
        } else {
            rowContent
        }
    }
}

/// Identifies the use-case and match index that the document details page shows.
private struct DocumentDetailsTarget: Hashable {
    let useCaseIndex: Int
    let matchIndex: Int
}

/// The transaction data the details page shows: one application-drawn group of one credential.
private struct TransactionDetailsTarget {
    let match: PresentmentCredentialMatch
    let identifier: String
    let items: [TransactionData<AnyObject>]
    let renderer: TransactionDataRendererSwiftUi
    let hasClaims: Bool
}

/// A page inside the consent prompt showing the application's details view for one group of
/// transaction data. It has no confirm button: the user goes back to the main page to decide.
private struct TransactionDetailsView: View {
    let maxHeight: CGFloat
    let target: TransactionDetailsTarget
    let requester: Requester
    let trustedRequesterIdentity: TrustedRequesterIdentity?
    let purposes: Set<PresentmentPurpose>
    let userInput: TransactionUserInput?
    let onUserInputChanged: (TransactionUserInput) -> Void
    let isActive: Bool
    let onScrollMetricsChange: ((CGFloat, CGFloat) -> Void)?

    var body: some View {
        SmartSheet(
            maxHeight: maxHeight,
            updateDetents: false,
            isActive: isActive,
            onScrollMetricsChange: onScrollMetricsChange
        ) {
        } content: {
            if let details = target.renderer.details {
                details(TransactionDataContext(
                    transactionData: target.items,
                    requester: requester,
                    trustedRequesterIdentity: trustedRequesterIdentity,
                    hasClaims: target.hasClaims,
                    purposes: purposes,
                    userInput: userInput,
                    onUserInputChanged: onUserInputChanged
                ))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
        } footer: { _, _ in
        }
        .navigationTitle("Details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(.editor)
    }
}

private struct RenderClaimValueView: View {
    let claim: Claim

    var body: some View {
        let tz = swiftUiTimeZoneForTesting.flatMap { TimeZone.Companion.shared.of(zoneId: $0.identifier) }
            ?? TimeZone.Companion.shared.currentSystemDefault()
        if claim.attribute?.type is DocumentAttributeType.Picture {
            let imageData: Data? = {
                if let mdocClaim = claim as? MdocClaim, let bstr = mdocClaim.value as? Bstr {
                    return bstr.value.toNSData()
                } else if let jsonClaim = claim as? JsonClaim {
                    return jsonClaim.value.jsonPrimitive.content.fromBase64Url().toNSData()
                }
                return nil
            }()
            if let data = imageData, let uiImage = UIImage(data: data) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 100, alignment: .leading)
            } else {
                Text(claim.render(timeZone: tz))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } else if claim.attribute?.type is DocumentAttributeType.Boolean {
            let rendered = claim.render(timeZone: tz)
            let display: String = {
                switch rendered.lowercased() {
                case "true": return "True"
                case "false": return "False"
                default: return rendered
                }
            }()
            Text(display)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        } else {
            Text(claim.render(timeZone: tz))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

private struct DocumentDetailsView: View {
    let maxHeight: CGFloat
    let useCase: PresentmentUseCase
    let selectionIndex: Int
    let matchIndex: Int
    let requester: Requester
    let requesterDisplayData: RequesterDisplayData
    let isActive: Bool
    let onScrollMetricsChange: ((CGFloat, CGFloat) -> Void)?

    var body: some View {
        let isSelected = selectionIndex >= 0
        let solutionIndexToShow = isSelected ? selectionIndex : 0
        let solution = solutionIndexToShow < useCase.solutions.count ? useCase.solutions[solutionIndexToShow] : nil
        let targetGroup: DocumentMatchGroup? = {
            guard let solution = solution else { return nil }
            let groups = groupMatchesByDocument(solution.matches)
            let targetMatch: PresentmentCredentialMatch? = {
                if matchIndex < solution.matches.count {
                    return solution.matches[matchIndex]
                }
                return solution.matches.first
            }()
            guard let targetMatch = targetMatch else { return groups.first }
            return groups.first { $0.document.identifier == targetMatch.credential.document.identifier }
                ?? groups.first
        }()

        SmartSheet(
            maxHeight: maxHeight,
            updateDetents: false,
            isActive: isActive,
            onScrollMetricsChange: onScrollMetricsChange
        ) {
        } content: {
            if let group = targetGroup, isSelected {
                VStack(alignment: .leading, spacing: 12) {
                    SegmentedItemContainer(index: 0, count: 1) {
                        CredentialViewerRow(
                            document: group.document,
                            showChevron: false,
                            onChevronClicked: {}
                        )
                    }

                    ForEach(0..<group.claimSections.count, id: \.self) { sectionIdx in
                        let section = group.claimSections[sectionIdx]
                        let sectionHeader = calcSharedWithText(
                            requester: requester,
                            requesterDisplayData: requesterDisplayData,
                            storesData: section.storesData,
                            encryptionRequested: section.encryptionRequested,
                            encryptionTargetTrustMetadata: section.encryptionTargetTrustMetadata
                        )
                        SegmentedItemList(title: sectionHeader) {
                            ForEach(0..<section.claims.count, id: \.self) { claimIdx in
                                let claim = section.claims[claimIdx]
                                SegmentedItemContainer {
                                    HStack(alignment: .center, spacing: 16) {
                                        Image(systemName: getIconName(claim: claim))
                                            .imageScale(.medium)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(claim.displayName)
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(.primary)
                                            RenderClaimValueView(claim: claim)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding()
            }
        } footer: { _, _ in
        }
        .navigationTitle("Details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(.editor)
    }
}

private enum ConsentDestinations: Hashable {
    case showRequesterInfo
    case certificateChain
    case documentDetails(DocumentDetailsTarget)
    case pickSolution(useCaseIndex: Int)
    case transactionDetails
}

/// Programmatic test controller for driving ``Consent`` navigation and state transitions
/// in deterministic unit and golden-screenshot tests.
@MainActor
public final class ConsentTestController {
    public internal(set) var scrollContainerHeight: CGFloat = 0
    public internal(set) var scrollContentHeight: CGFloat = 0
    public internal(set) var isHistoryReady: Bool = false
    public internal(set) var isSelectionsReady: Bool = false

    internal var onShowRequesterInfo: (() -> Void)?
    internal var onShowDocumentDetails: ((Int, Int) -> Void)?
    internal var onShowPickSolution: ((Int) -> Void)?
    internal var onSelectSolution: ((Int, Int) -> Void)?
    internal var onShowTransactionDetails: (() -> Void)?
    internal var onShowFirstRequesterCertChain: (() -> Void)?
    internal var onShowFirstEncryptionTargetCertChain: (() -> Void)?
    internal var onPop: (() -> Void)?
    internal var onScrollToBottom: (() -> Void)?
    internal var onCompleteCoolOff: (() -> Void)?

    public init() {}

    public var isReady: Bool {
        isSelectionsReady && isHistoryReady && scrollContainerHeight > 0
    }

    public func resetScrollMetrics() {
        scrollContainerHeight = 0
        scrollContentHeight = 0
    }

    public func showRequesterInfo() {
        resetScrollMetrics()
        onShowRequesterInfo?()
    }

    public func showDocumentDetails(useCaseIndex: Int = 0, matchIndex: Int = 0) {
        resetScrollMetrics()
        onShowDocumentDetails?(useCaseIndex, matchIndex)
    }

    public func showPickSolution(useCaseIndex: Int = 0) {
        resetScrollMetrics()
        onShowPickSolution?(useCaseIndex)
    }

    public func selectSolution(useCaseIndex: Int = 0, solutionIndex: Int) {
        resetScrollMetrics()
        onSelectSolution?(useCaseIndex, solutionIndex)
    }

    public func showTransactionDetails() {
        resetScrollMetrics()
        onShowTransactionDetails?()
    }

    public func showFirstRequesterCertChain() {
        resetScrollMetrics()
        onShowFirstRequesterCertChain?()
    }

    public func showFirstEncryptionTargetCertChain() {
        resetScrollMetrics()
        onShowFirstEncryptionTargetCertChain?()
    }

    public func pop() {
        resetScrollMetrics()
        onPop?()
    }

    public func scrollToBottom() {
        onScrollToBottom?()
    }

    public func completeCoolOff() {
        onCompleteCoolOff?()
    }
}

/// A ``View`` which asks the user to approve sharing of credentials.
///
/// - Parameters:
///   - presentmentData: the presentment data containing use cases and solutions.
///   - requester: the relying party which is requesting the data.
///   - trustedRequesterIdentity: the identity of the trusted requester, if any.
///   - preselectedDocuments: list of documents that should be preselected.
///   - maxHeight: the maximum height of the view.
///   - transactionDataRendererRepository: the ``TransactionDataRendererRepository`` to find renderers for
///     transaction data in, or `nil` to use only the built-in rendering.
///   - requesterHistory: an optional ``RequesterHistory`` used to query whether the user has shared
///     credentials with `requester` before.
///   - classifyPurposesFn: function used to classify the ``PresentmentPurpose``s of the request.
///   - classifyRiskFn: function used to evaluate the ``ConsentRiskAssessment`` of the request.
///   - testController: an optional ``ConsentTestController`` used by snapshot tests.
///   - onDocumentsInFocus: callback invoked with the list of documents currently in focus as the user changes selections.
///   - onConfirm: callback when the user presses the Continue button with the credentials that were selected.
///   - onCancel: callback when the user presses the Cancel button.
public struct Consent: View {
    let maxHeight: CGFloat
    let presentmentData: PresentmentData
    let requester: Requester
    let trustedRequesterIdentity: TrustedRequesterIdentity?
    let preselectedDocuments: [Document]
    let transactionDataRendererRepository: TransactionDataRendererRepository?
    let requesterHistory: RequesterHistory?
    let classifyPurposesFn: ClassifyPurposesSwiftFn
    let classifyRiskFn: ClassifyRiskSwiftFn
    let testController: ConsentTestController?
    let onDocumentsInFocus: ((_ documents: [Document]) -> Void)?
    let onConfirm: (_: PresentmentSelection) -> Void
    let onCancel: () -> Void

    public init(
        presentmentData: PresentmentData,
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
        preselectedDocuments: [Document] = [],
        maxHeight: CGFloat = .infinity,
        transactionDataRendererRepository: TransactionDataRendererRepository? = nil,
        requesterHistory: RequesterHistory? = nil,
        classifyPurposesFn: @escaping ClassifyPurposesSwiftFn = { requester, trustedRequesterIdentity, presentmentData, selections in
            defaultClassifyPurposes(
                requester: requester,
                trustedRequesterIdentity: trustedRequesterIdentity,
                presentmentData: presentmentData,
                selections: selections
            )
        },
        classifyRiskFn: @escaping ClassifyRiskSwiftFn = { requester, trustedRequesterIdentity, presentmentData, selections, purposes in
            defaultClassifyRisk(
                requester: requester,
                trustedRequesterIdentity: trustedRequesterIdentity,
                presentmentData: presentmentData,
                selections: selections,
                purposes: purposes
            )
        },
        testController: ConsentTestController? = nil,
        onDocumentsInFocus: ((_ documents: [Document]) -> Void)? = nil,
        onConfirm: @escaping (_: PresentmentSelection) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.presentmentData = presentmentData
        self.requester = requester
        self.trustedRequesterIdentity = trustedRequesterIdentity
        self.preselectedDocuments = preselectedDocuments
        self.maxHeight = maxHeight
        self.transactionDataRendererRepository = transactionDataRendererRepository
        self.requesterHistory = requesterHistory
        self.classifyPurposesFn = classifyPurposesFn
        self.classifyRiskFn = classifyRiskFn
        self.testController = testController
        self.onDocumentsInFocus = onDocumentsInFocus
        self.onConfirm = onConfirm
        self.onCancel = onCancel
    }

    @State private var path = NavigationPath()
    @State private var selections: [Int] = []
    @State private var transactionUserInput: [PresentmentCredentialMatch: [String: TransactionUserInput]] = [:]
    @State private var sheetHeight: CGFloat = 450
    @State private var isSheetScrollable: Bool = false
    @State private var coolOffComplete: Bool = false
    @State private var scrollToBottomTrigger: Int = 0
    @State private var requesterHistoryEntry: RequesterHistoryEntry? = nil
    @State private var requesterHistoryLoaded: Bool = false
    @State private var transactionDetails: TransactionDetailsTarget? = nil
    @State private var selectedCertChain: X509CertChain? = nil

    private func getDocumentsForSelections(_ selections: [Int]) -> [Document] {
        var documents: [Document] = []
        for (useCaseIndex, solutionIndex) in selections.enumerated() {
            if useCaseIndex < presentmentData.useCases.count && solutionIndex >= 0 {
                let useCase = presentmentData.useCases[useCaseIndex]
                if solutionIndex < useCase.solutions.count {
                    let solution = useCase.solutions[solutionIndex]
                    for match in solution.matches {
                        let doc = match.credential.document
                        if !documents.contains(where: { $0.identifier == doc.identifier }) {
                            documents.append(doc)
                        }
                    }
                }
            }
        }
        return documents
    }

    private func getInitialSelections(preselectedDocuments: [Document]) -> [Int] {
        var result: [Int] = []
        for i in 0..<presentmentData.useCases.count {
            let useCase = presentmentData.useCases[i]
            if preselectedDocuments.isEmpty {
                result.append(useCase.solutions.count > 0 ? 0 : -1)
            } else {
                let preselectedSet = Set(preselectedDocuments)
                var matchingIndex = -1
                for j in 0..<useCase.solutions.count {
                    let solution = useCase.solutions[j]
                    if solution.matches.count > 0 {
                        var allMatch = true
                        for k in 0..<solution.matches.count {
                            let doc = solution.matches[k].credential.document
                            if !preselectedSet.contains(doc) {
                                allMatch = false
                                break
                            }
                        }
                        if allMatch {
                            matchingIndex = Int(j)
                            break
                        }
                    }
                }

                if matchingIndex != -1 {
                    result.append(matchingIndex)
                } else if useCase.optional {
                    result.append(-1)
                } else {
                    result.append(useCase.solutions.count > 0 ? 0 : -1)
                }
            }
        }
        return result
    }

    public var body: some View {
        let requesterDisplayData = buildRequesterDisplayData(
            requester: requester,
            trustedRequesterIdentity: trustedRequesterIdentity
        )
        let kotlinSelections = selections.map { KotlinInt(int: Int32($0)) }
        let purposes: Set<PresentmentPurpose> = selections.isEmpty
            ? Set()
            : classifyPurposesFn(requester, trustedRequesterIdentity, presentmentData, kotlinSelections)
        let riskAssessment: ConsentRiskAssessment = selections.isEmpty
            ? ConsentRiskAssessment(level: .normal, reasons: Set())
            : classifyRiskFn(requester, trustedRequesterIdentity, presentmentData, kotlinSelections, purposes)
        let currentSelection: PresentmentSelection? = selections.isEmpty
            ? nil
            : presentmentData.toPresentmentSelection(selections: kotlinSelections, transactionUserInput: [:])
        let encryptionTargets: [EncryptionTargetInfo] = {
            guard let currentSelection = currentSelection else { return [] }
            var list: [EncryptionTargetInfo] = []
            for match in currentSelection.matches where match.encryptionRequested {
                let target = EncryptionTargetInfo(
                    certChain: match.encryptionTargetCertificationChain,
                    trustMetadata: match.encryptionTargetTrustMetadata
                )
                if !list.contains(target) {
                    list.append(target)
                }
            }
            return list
        }()

        let onMetrics: ((CGFloat, CGFloat) -> Void)? = testController != nil ? { containerH, contentH in
            testController?.scrollContainerHeight = containerH
            testController?.scrollContentHeight = contentH
        } : nil

        NavigationStack(path: $path) {
            VStack {
                if selections.isEmpty {
                    ProgressView()
                } else {
                    ConsentMain(
                        maxHeight: maxHeight,
                        presentmentData: presentmentData,
                        requester: requester,
                        trustedRequesterIdentity: trustedRequesterIdentity,
                        requesterDisplayData: requesterDisplayData,
                        purposes: purposes,
                        riskAssessment: riskAssessment,
                        requesterHistoryLoaded: requesterHistoryLoaded,
                        requesterHistoryEntry: requesterHistoryEntry,
                        transactionDataRendererRepository: transactionDataRendererRepository,
                        selections: selections,
                        transactionUserInput: $transactionUserInput,
                        sheetHeight: $sheetHeight,
                        isSheetScrollable: $isSheetScrollable,
                        coolOffComplete: $coolOffComplete,
                        scrollToBottomTrigger: scrollToBottomTrigger,
                        isTesting: testController != nil,
                        isActive: path.isEmpty,
                        onScrollMetricsChange: onMetrics,
                        onShowRequesterInfo: {
                            path.append(ConsentDestinations.showRequesterInfo)
                        },
                        onShowDocumentDetails: { target in
                            path.append(ConsentDestinations.documentDetails(target))
                        },
                        onNavigateToPickSolution: { idx in
                            path.append(ConsentDestinations.pickSolution(useCaseIndex: idx))
                        },
                        onShowTransactionDetails: { target in
                            transactionDetails = target
                            path.append(ConsentDestinations.transactionDetails)
                        },
                        onConfirm: onConfirm,
                        onCancel: onCancel
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .containerBackground(.clear, for: .navigation)
            .navigationTitle("Share Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("")
                }
            }
            .onAppear {
                if selections.isEmpty {
                    selections = getInitialSelections(preselectedDocuments: preselectedDocuments)
                    let initialDocs = getDocumentsForSelections(selections)
                    onDocumentsInFocus?(initialDocs)
                }
                if let tc = testController {
                    tc.isSelectionsReady = true
                    tc.isHistoryReady = (requesterHistory == nil || requesterHistoryLoaded)
                    tc.onShowRequesterInfo = {
                        path.append(ConsentDestinations.showRequesterInfo)
                    }
                    tc.onShowDocumentDetails = { ucIdx, matchIdx in
                        path.append(ConsentDestinations.documentDetails(DocumentDetailsTarget(useCaseIndex: ucIdx, matchIndex: matchIdx)))
                    }
                    tc.onShowPickSolution = { ucIdx in
                        path.append(ConsentDestinations.pickSolution(useCaseIndex: ucIdx))
                    }
                    tc.onSelectSolution = { ucIdx, solIdx in
                        if ucIdx < selections.count {
                            selections[ucIdx] = solIdx
                            let updatedDocs = getDocumentsForSelections(selections)
                            onDocumentsInFocus?(updatedDocs)
                        }
                        if !path.isEmpty {
                            path.removeLast()
                        }
                    }
                    tc.onShowTransactionDetails = {
                        for (ucIdx, useCase) in presentmentData.useCases.enumerated() {
                            let solIdx = ucIdx < selections.count ? selections[ucIdx] : 0
                            if solIdx >= 0 && solIdx < useCase.solutions.count {
                                let solution = useCase.solutions[solIdx]
                                for match in solution.matches {
                                    let docId = match.credential.document.identifier
                                    let hasClaims = solution.matches.contains {
                                        $0.credential.document.identifier == docId && !$0.claims.isEmpty
                                    }
                                    let blocks = transactionDataBlocks(match.transactionData, repository: transactionDataRendererRepository)
                                    for block in blocks {
                                        if case .wallet(let identifier, let items, let renderer) = block {
                                            transactionDetails = TransactionDetailsTarget(
                                                match: match,
                                                identifier: identifier,
                                                items: items,
                                                renderer: renderer,
                                                hasClaims: hasClaims
                                            )
                                            path.append(ConsentDestinations.transactionDetails)
                                            return
                                        }
                                    }
                                }
                            }
                        }
                    }
                    tc.onShowFirstRequesterCertChain = {
                        let identities: [RequesterIdentity] = {
                            if !requester.requesterIdentities.isEmpty {
                                return requester.requesterIdentities
                            } else if let trustedId = trustedRequesterIdentity?.identity {
                                return [trustedId]
                            } else {
                                return []
                            }
                        }()
                        if let firstChain = identities.first?.certChain {
                            selectedCertChain = firstChain
                            path.append(ConsentDestinations.certificateChain)
                        }
                    }
                    tc.onShowFirstEncryptionTargetCertChain = {
                        for (ucIdx, useCase) in presentmentData.useCases.enumerated() {
                            let solIdx = ucIdx < selections.count ? selections[ucIdx] : 0
                            if solIdx >= 0 && solIdx < useCase.solutions.count {
                                for match in useCase.solutions[solIdx].matches where match.encryptionRequested {
                                    if let certChain = match.encryptionTargetCertificationChain {
                                        selectedCertChain = certChain
                                        path.append(ConsentDestinations.certificateChain)
                                        return
                                    }
                                }
                            }
                        }
                    }
                    tc.onPop = {
                        if !path.isEmpty {
                            path.removeLast()
                        }
                    }
                    tc.onScrollToBottom = {
                        scrollToBottomTrigger += 1
                    }
                    tc.onCompleteCoolOff = {
                        coolOffComplete = true
                    }
                }
            }
            .task {
                if let requesterHistory = requesterHistory {
                    requesterHistoryEntry = try? await requesterHistory.getEntry(
                        requester: requester,
                        trustedRequesterIdentity: trustedRequesterIdentity
                    )
                    requesterHistoryLoaded = true
                } else {
                    requesterHistoryEntry = nil
                    requesterHistoryLoaded = false
                }
                testController?.isHistoryReady = true
            }
            .navigationDestination(for: ConsentDestinations.self) { destination in
                Group {
                    switch destination {
                    case .showRequesterInfo:
                        ShowRequesterInfo(
                            maxHeight: maxHeight,
                            requester: requester,
                            trustedRequesterIdentity: trustedRequesterIdentity,
                            requesterDisplayData: requesterDisplayData,
                            encryptionTargets: encryptionTargets,
                            requesterHistoryLoaded: requesterHistoryLoaded,
                            requesterHistoryEntry: requesterHistoryEntry,
                            isActive: path.count == 1,
                            onScrollMetricsChange: onMetrics,
                            onShowCertChain: { certChain in
                                selectedCertChain = certChain
                                path.append(ConsentDestinations.certificateChain)
                            }
                        )
                    case .certificateChain:
                        if let certChain = selectedCertChain {
                            CertificateChainSheetView(
                                maxHeight: maxHeight,
                                certChain: certChain,
                                isActive: true,
                                onScrollMetricsChange: onMetrics
                            )
                        }
                    case .documentDetails(let target):
                        if target.useCaseIndex < presentmentData.useCases.count {
                            DocumentDetailsView(
                                maxHeight: maxHeight,
                                useCase: presentmentData.useCases[target.useCaseIndex],
                                selectionIndex: selections[target.useCaseIndex],
                                matchIndex: target.matchIndex,
                                requester: requester,
                                requesterDisplayData: requesterDisplayData,
                                isActive: true,
                                onScrollMetricsChange: onMetrics
                            )
                        }
                    case .pickSolution(let useCaseIndex):
                        PickSolutionView(
                            maxHeight: maxHeight,
                            useCase: presentmentData.useCases[useCaseIndex],
                            currentSolutionIndex: selections[useCaseIndex],
                            isActive: true,
                            onScrollMetricsChange: onMetrics,
                            onSolutionSelected: { solutionIndex in
                                selections[useCaseIndex] = solutionIndex
                                let updatedDocs = getDocumentsForSelections(selections)
                                onDocumentsInFocus?(updatedDocs)
                                path.removeLast()
                            }
                        )
                    case .transactionDetails:
                        if let target = transactionDetails {
                            TransactionDetailsView(
                                maxHeight: maxHeight,
                                target: target,
                                requester: requester,
                                trustedRequesterIdentity: trustedRequesterIdentity,
                                purposes: purposes,
                                userInput: transactionUserInput[target.match]?[target.identifier],
                                onUserInputChanged: { input in
                                    var current = transactionUserInput[target.match] ?? [:]
                                    current[target.identifier] = input
                                    transactionUserInput[target.match] = current
                                },
                                isActive: true,
                                onScrollMetricsChange: onMetrics
                            )
                        }
                    }
                }
                .containerBackground(.clear, for: .navigation)
            }
        }
        .background(isSheetScrollable ? Color(UIColor.systemGroupedBackground) : Color.clear)
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.visible)
    }
}

private struct EncryptionTargetInfo: Equatable {
    let certChain: X509CertChain?
    let trustMetadata: TrustMetadata?
}

private func formatHistoryDate(_ date: Date) -> String {
    var style = Date.FormatStyle(date: .abbreviated, time: .shortened)
    if let tz = swiftUiTimeZoneForTesting {
        style.timeZone = tz
    }
    return date.formatted(style)
}

private struct ShowRequesterInfo: View {
    let maxHeight: CGFloat
    let requester: Requester
    let trustedRequesterIdentity: TrustedRequesterIdentity?
    let requesterDisplayData: RequesterDisplayData
    let encryptionTargets: [EncryptionTargetInfo]
    let requesterHistoryLoaded: Bool
    let requesterHistoryEntry: RequesterHistoryEntry?
    let isActive: Bool
    let onScrollMetricsChange: ((CGFloat, CGFloat) -> Void)?
    let onShowCertChain: (X509CertChain) -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        let trustMetadata = trustedRequesterIdentity?.trustMetadata
        let requesterName = resolveRequesterHeadlineName(
            requester: requester,
            requesterDisplayData: requesterDisplayData,
            trustedRequesterIdentity: trustedRequesterIdentity
        )
        let privacyPolicyUrl = trustMetadata?.privacyPolicyUrl
        let topGroupCount = privacyPolicyUrl != nil ? 2 : 1

        let websiteOrigin: String? = {
            guard requester.isWebOrigin, let origin = requester.origin, !origin.isEmpty else {
                return nil
            }
            return origin
        }()
        let appDisplay = requester.appId
        let identities: [RequesterIdentity] = {
            if !requester.requesterIdentities.isEmpty {
                return requester.requesterIdentities
            } else if let trustedId = trustedRequesterIdentity?.identity {
                return [trustedId]
            } else {
                return []
            }
        }()
        let encryptionTargetCertChains: [X509CertChain] = {
            var chains: [X509CertChain] = []
            for target in encryptionTargets {
                if let chain = target.certChain, !chains.contains(chain) {
                    chains.append(chain)
                }
            }
            return chains
        }()
        let techSegmentCount = (websiteOrigin != nil ? 1 : 0) +
            (appDisplay != nil ? 1 : 0) +
            identities.count +
            encryptionTargetCertChains.count

        SmartSheet(
            maxHeight: maxHeight,
            updateDetents: false,
            isActive: isActive,
            onScrollMetricsChange: onScrollMetricsChange
        ) {
        } content: {
            VStack(alignment: .leading, spacing: 12) {
                // 1. Trust & Verification Summary + Privacy Policy
                VStack(spacing: segmentGap) {
                    SegmentedItemContainer(index: 0, count: topGroupCount) {
                        HStack(alignment: .center, spacing: 16) {
                            if let iconData = requesterDisplayData.iconData,
                               let uiImage = UIImage(data: iconData.toNSData()) {
                                Image(uiImage: uiImage)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 40, height: 40)
                            } else if let iconUrl = requesterDisplayData.iconUrl,
                                      let url = URL(string: iconUrl) {
                                AsyncImage(url: url) { phase in
                                    if let image = phase.image {
                                        image
                                            .resizable()
                                            .scaledToFit()
                                            .frame(width: 40, height: 40)
                                    } else {
                                        Image(systemName: trustMetadata != nil ? "checkmark.shield" : "exclamationmark.triangle")
                                            .imageScale(.large)
                                            .frame(width: 24, height: 24)
                                    }
                                }
                            } else {
                                Image(systemName: trustMetadata != nil ? "checkmark.shield" : "exclamationmark.triangle")
                                    .imageScale(.large)
                                    .frame(width: 24, height: 24)
                            }

                            let titleText: String = {
                                if trustMetadata != nil {
                                    return "\(requesterName) is a verified requester"
                                } else if !requester.requesterIdentities.isEmpty {
                                    return "Unverified requester"
                                } else {
                                    return "Anonymous requester"
                                }
                            }()
                            let bodyText: String = {
                                if trustMetadata != nil {
                                    return "The wallet verified the digital signature on this request and confirmed that \(requesterName) is in the wallet's trust list."
                                } else if !requester.requesterIdentities.isEmpty {
                                    return "This request was signed with a digital certificate, but the certificate is not in the wallet's trust list. The wallet cannot verify the requester's identity."
                                } else {
                                    return "This requester did not provide a digital certificate or signature to authenticate its identity."
                                }
                            }()

                            VStack(alignment: .leading, spacing: 4) {
                                Text(titleText)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.primary)
                                Text(bodyText)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                if trustMetadata?.testOnly == true {
                                    Text("Note: This requester is configured for testing only.")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if let disclaimer = trustMetadata?.disclaimer {
                                    Text(disclaimer)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }

                    if let privacyPolicyUrl = privacyPolicyUrl {
                        Button {
                            if let url = URL(string: privacyPolicyUrl) {
                                openURL(url)
                            }
                        } label: {
                            SegmentedItemContainer(index: 1, count: topGroupCount) {
                                HStack(alignment: .center, spacing: 16) {
                                    Image(systemName: "doc.text")
                                        .imageScale(.medium)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("\(requesterName) privacy policy")
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(.primary)
                                        Text(privacyPolicyUrl)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    Image(systemName: "arrow.up.right.square")
                                        .imageScale(.medium)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }

                ForEach(Array(encryptionTargets.enumerated()), id: \.offset) { _, target in
                    EncryptionTargetTrustGroupView(target: target)
                }

                // 2. Past Interactions
                if requesterHistoryLoaded && isRequesterIdentifiable(requester: requester, trustedRequesterIdentity: trustedRequesterIdentity) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Past interactions")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .padding(.bottom, 8)
                        SegmentedItemContainer(index: 0, count: 1) {
                            HStack(alignment: .center, spacing: 16) {
                                Image(systemName: "clock.arrow.circlepath")
                                    .imageScale(.medium)
                                VStack(alignment: .leading, spacing: 2) {
                                    if let entry = requesterHistoryEntry {
                                        let countTitle = entry.shareCount == 1
                                            ? "Shared 1 time"
                                            : "Shared \(entry.shareCount) times"
                                        Text(countTitle)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(.primary)
                                        if entry.shareCount > 1 {
                                            let firstDate = entry.firstSharedAt.toNSDate()
                                            let firstStr = "\(formatHistoryDate(firstDate)) (\(firstDate.relativeTimeText))"
                                            Text("First shared: \(firstStr)")
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                        }
                                        let lastDate = entry.lastSharedAt.toNSDate()
                                        let lastStr = "\(formatHistoryDate(lastDate)) (\(lastDate.relativeTimeText))"
                                        Text("Last shared: \(lastStr)")
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    } else {
                                        Text("No previous shares")
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(.primary)
                                        Text("You have not shared credentials with this requester before.")
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                }

                // 3. Technical Details
                if techSegmentCount > 0 {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Technical details")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .padding(.bottom, 8)
                        VStack(spacing: segmentGap) {
                            let websiteOffset = 0
                            let appOffset = websiteOrigin != nil ? 1 : 0
                            let identitiesOffset = appOffset + (appDisplay != nil ? 1 : 0)
                            let encChainsOffset = identitiesOffset + identities.count

                            if let websiteOrigin = websiteOrigin {
                                SegmentedItemContainer(index: websiteOffset, count: techSegmentCount) {
                                    HStack(alignment: .center, spacing: 16) {
                                        Image(systemName: "globe")
                                            .imageScale(.medium)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("Website")
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(.primary)
                                            Text(websiteOrigin)
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }

                            if let appDisplay = appDisplay {
                                SegmentedItemContainer(index: appOffset, count: techSegmentCount) {
                                    HStack(alignment: .center, spacing: 16) {
                                        Image(systemName: "square.grid.2x2")
                                            .imageScale(.medium)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("Application")
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(.primary)
                                            Text(appDisplay)
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }

                            ForEach(0..<identities.count, id: \.self) { chainIndex in
                                let identity = identities[chainIndex]
                                let chainTitle = identities.count == 1
                                    ? "Certificate chain"
                                    : "Certificate chain #\(chainIndex + 1)"
                                let clientId = (identity as? OpenID4VPRequesterIdentity)?.clientId
                                CertificateChainSegmentView(
                                    index: identitiesOffset + chainIndex,
                                    count: techSegmentCount,
                                    title: chainTitle,
                                    certChain: identity.certChain,
                                    clientId: clientId,
                                    onShowCertChain: onShowCertChain
                                )
                            }

                            ForEach(0..<encryptionTargetCertChains.count, id: \.self) { chainIndex in
                                let certChain = encryptionTargetCertChains[chainIndex]
                                let chainTitle = encryptionTargetCertChains.count == 1
                                    ? "Encryption target certificate chain"
                                    : "Encryption target certificate chain #\(chainIndex + 1)"
                                CertificateChainSegmentView(
                                    index: encChainsOffset + chainIndex,
                                    count: techSegmentCount,
                                    title: chainTitle,
                                    certChain: certChain,
                                    clientId: nil,
                                    onShowCertChain: onShowCertChain
                                )
                            }
                        }
                    }
                }
            }
            .padding()
        } footer: { _, _ in
        }
        .navigationTitle("Requester information")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(.editor)
    }
}

private struct EncryptionTargetTrustGroupView: View {
    let target: EncryptionTargetInfo
    @Environment(\.openURL) private var openURL

    var body: some View {
        let trustMetadata = target.trustMetadata
        let targetName = trustMetadata?.displayName
            ?? target.certChain?.certificates.last?.subject.name
            ?? (target.certChain == nil ? "Anonymous requester" : "Unknown requester")
        let privacyPolicyUrl = trustMetadata?.privacyPolicyUrl
        let groupCount = privacyPolicyUrl != nil ? 2 : 1

        VStack(spacing: segmentGap) {
            SegmentedItemContainer(index: 0, count: groupCount) {
                HStack(alignment: .center, spacing: 16) {
                    if let iconData = trustMetadata?.displayIcon,
                       let uiImage = UIImage(data: iconData.toNSData()) {
                        Image(uiImage: uiImage)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 40, height: 40)
                    } else if let iconUrl = trustMetadata?.displayIconUrl,
                              let url = URL(string: iconUrl) {
                        AsyncImage(url: url) { phase in
                            if let image = phase.image {
                                image
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 40, height: 40)
                            } else {
                                Image(systemName: trustMetadata != nil ? "checkmark.shield" : "exclamationmark.triangle")
                                    .imageScale(.large)
                                    .frame(width: 24, height: 24)
                            }
                        }
                    } else {
                        Image(systemName: trustMetadata != nil ? "checkmark.shield" : "exclamationmark.triangle")
                            .imageScale(.large)
                            .frame(width: 24, height: 24)
                    }

                    let titleText: String = {
                        if trustMetadata != nil {
                            return "Verified encryption target: \(targetName)"
                        } else if target.certChain != nil {
                            return "Unverified encryption target"
                        } else {
                            return "Anonymous encryption target"
                        }
                    }()
                    let bodyText: String = {
                        if trustMetadata != nil {
                            return "\(targetName) is in the wallet's trust list. Part of your information will be encrypted so only \(targetName) can read it."
                        } else if target.certChain != nil {
                            return "Part of your information will be encrypted for a recipient whose certificate is not in the wallet's trust list."
                        } else {
                            return "Part of your information will be encrypted for a recipient that did not provide a verifiable identity."
                        }
                    }()

                    VStack(alignment: .leading, spacing: 4) {
                        Text(titleText)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                        Text(bodyText)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if trustMetadata?.testOnly == true {
                            Text("Note: This requester is configured for testing only.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if let disclaimer = trustMetadata?.disclaimer {
                            Text(disclaimer)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }

            if let privacyPolicyUrl = privacyPolicyUrl {
                Button {
                    if let url = URL(string: privacyPolicyUrl) {
                        openURL(url)
                    }
                } label: {
                    SegmentedItemContainer(index: 1, count: groupCount) {
                        HStack(alignment: .center, spacing: 16) {
                            Image(systemName: "doc.text")
                                .imageScale(.medium)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(targetName) privacy policy")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.primary)
                                Text(privacyPolicyUrl)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "arrow.up.right.square")
                                .imageScale(.medium)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct CertificateChainSegmentView: View {
    let index: Int
    let count: Int
    let title: String
    let certChain: X509CertChain
    let clientId: String?
    let onShowCertChain: (X509CertChain) -> Void

    var body: some View {
        let certs = certChain.certificates
        let leafName = certs.first?.subject.name
        let rootName = certs.last?.subject.name
        let subjectSummary: String? = {
            if let leafName = leafName, let rootName = rootName, leafName != rootName {
                return "\(leafName) (issued by \(rootName))"
            } else if let leafName = leafName {
                return leafName
            } else if let rootName = rootName {
                return rootName
            } else {
                return nil
            }
        }()
        let certCountSummary = certs.count == 1 ? "1 certificate" : "\(certs.count) certificates"

        Button {
            onShowCertChain(certChain)
        } label: {
            SegmentedItemContainer(index: index, count: count, showChevron: true) {
                HStack(alignment: .center, spacing: 16) {
                    Image(systemName: "person.text.rectangle")
                        .imageScale(.medium)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                        if let subjectSummary = subjectSummary {
                            Text(subjectSummary)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        if let clientId = clientId {
                            Text("Client ID: \(clientId)")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Text(certCountSummary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

private struct CertificateChainSheetView: View {
    let maxHeight: CGFloat
    let certChain: X509CertChain
    let isActive: Bool
    let onScrollMetricsChange: ((CGFloat, CGFloat) -> Void)?

    var body: some View {
        SmartSheet(
            maxHeight: maxHeight,
            updateDetents: false,
            isActive: isActive,
            onScrollMetricsChange: onScrollMetricsChange
        ) {
        } content: {
            X509CertViewer(certChain: certChain)
                .padding()
        } footer: { _, _ in
        }
        .navigationTitle("Certificate chain")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(.editor)
    }
}

private struct ConsentMain: View {
    let maxHeight: CGFloat
    let presentmentData: PresentmentData
    let requester: Requester
    let trustedRequesterIdentity: TrustedRequesterIdentity?
    let requesterDisplayData: RequesterDisplayData
    let purposes: Set<PresentmentPurpose>
    let riskAssessment: ConsentRiskAssessment
    let requesterHistoryLoaded: Bool
    let requesterHistoryEntry: RequesterHistoryEntry?
    let transactionDataRendererRepository: TransactionDataRendererRepository?
    let selections: [Int]
    @Binding var transactionUserInput: [PresentmentCredentialMatch: [String: TransactionUserInput]]
    @Binding var sheetHeight: CGFloat
    @Binding var isSheetScrollable: Bool
    @Binding var coolOffComplete: Bool
    let scrollToBottomTrigger: Int
    let isTesting: Bool
    let isActive: Bool
    let onScrollMetricsChange: ((CGFloat, CGFloat) -> Void)?
    let onShowRequesterInfo: () -> Void
    let onShowDocumentDetails: (DocumentDetailsTarget) -> Void
    let onNavigateToPickSolution: (Int) -> Void
    let onShowTransactionDetails: (TransactionDetailsTarget) -> Void
    let onConfirm: (_: PresentmentSelection) -> Void
    let onCancel: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    private var trustMetadata: TrustMetadata? { trustedRequesterIdentity?.trustMetadata }

    var body: some View {
        let riskLevel = riskAssessment.level
        let hasEncryptionTargetPrivacyPolicy: Bool = {
            for (index, useCase) in presentmentData.useCases.enumerated() {
                let selIndex = index < selections.count ? selections[index] : -1
                if selIndex >= 0 && selIndex < useCase.solutions.count {
                    let solution = useCase.solutions[selIndex]
                    if solution.matches.contains(where: { $0.encryptionRequested && $0.encryptionTargetTrustMetadata?.privacyPolicyUrl != nil }) {
                        return true
                    }
                }
            }
            return false
        }()

        let rpParagraphs = calcRelyingPartyParagraphs(
            requester: requester,
            trustedRequesterIdentity: trustedRequesterIdentity,
            trustMetadata: trustMetadata,
            riskAssessment: riskAssessment,
            requesterHistoryLoaded: requesterHistoryLoaded,
            requesterHistoryEntry: requesterHistoryEntry,
            hasEncryptionTargetPrivacyPolicy: hasEncryptionTargetPrivacyPolicy
        )
        let hasRpInfo = !rpParagraphs.isEmpty || requesterDisplayData.disclaimer != nil

        SmartSheet(
            maxHeight: maxHeight,
            updateDetents: false,
            heightBinding: isActive ? $sheetHeight : nil,
            isScrollableBinding: isActive ? $isSheetScrollable : nil,
            scrollToBottomTrigger: scrollToBottomTrigger,
            isActive: isActive,
            onScrollMetricsChange: onScrollMetricsChange
        ) {
            RelyingPartySection(
                requester: requester,
                requesterDisplayData: requesterDisplayData,
                trustedRequesterIdentity: trustedRequesterIdentity,
                purposes: purposes
            )
            .padding(.horizontal)
            .padding(.bottom, 12)
            .padding(.top, -20)
        } content: {
            VStack(spacing: 12) {
                if riskLevel != .normal && hasRpInfo {
                    let isDark = colorScheme == .dark
                    let (rpContainerColor, rpBorderColor): (Color, Color) = {
                        if riskLevel == .highRisk {
                            if isDark {
                                return (Color(red: 0x4A / 255.0, green: 0x1C / 255.0, blue: 0x1A / 255.0),
                                        Color(red: 0xB3 / 255.0, green: 0x26 / 255.0, blue: 0x1E / 255.0))
                            } else {
                                return (Color(red: 0xFC / 255.0, green: 0xE8 / 255.0, blue: 0xE6 / 255.0),
                                        Color(red: 0xD9 / 255.0, green: 0x30 / 255.0, blue: 0x25 / 255.0))
                            }
                        } else {
                            if isDark {
                                return (Color(red: 0x3E / 255.0, green: 0x30 / 255.0, blue: 0x12 / 255.0),
                                        Color(red: 0xF9 / 255.0, green: 0xAB / 255.0, blue: 0x00 / 255.0))
                            } else {
                                return (Color(red: 0xFE / 255.0, green: 0xF7 / 255.0, blue: 0xE0 / 255.0),
                                        Color(red: 0xF9 / 255.0, green: 0xAB / 255.0, blue: 0x00 / 255.0))
                            }
                        }
                    }()

                    RelyingPartyInfoContent(
                        paragraphs: rpParagraphs,
                        disclaimer: requesterDisplayData.disclaimer,
                        showWarningIcon: true,
                        onShowRequesterInfo: onShowRequesterInfo
                    )
                    .padding(16)
                    .background(rpContainerColor)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(rpBorderColor, lineWidth: 1)
                    )
                }

                let includeSingleUseCaseBottomRpInfo = (riskLevel == .normal && hasRpInfo && presentmentData.useCases.count == 1)

                ForEach(0..<presentmentData.useCases.count, id: \.self) { idx in
                    SegmentedItemList {
                        UseCaseSection(
                            useCaseIndex: idx,
                            requester: requester,
                            requesterDisplayData: requesterDisplayData,
                            trustedRequesterIdentity: trustedRequesterIdentity,
                            purposes: purposes,
                            transactionDataRendererRepository: transactionDataRendererRepository,
                            useCase: presentmentData.useCases[idx],
                            selectionIndex: selections[idx],
                            transactionUserInput: transactionUserInput,
                            onTransactionUserInputChanged: { match, typeId, input in
                                var current = transactionUserInput[match] ?? [:]
                                current[typeId] = input
                                transactionUserInput[match] = current
                            },
                            onShowDocumentDetails: onShowDocumentDetails,
                            onShowTransactionDetails: onShowTransactionDetails,
                            onNavigateToPickSolution: {
                                onNavigateToPickSolution(idx)
                            },
                            bottomRpInfoParagraphs: includeSingleUseCaseBottomRpInfo ? rpParagraphs : nil,
                            bottomRpInfoDisclaimer: includeSingleUseCaseBottomRpInfo ? requesterDisplayData.disclaimer : nil,
                            onShowRequesterInfo: onShowRequesterInfo
                        )
                    }
                }

                if riskLevel == .normal && hasRpInfo && presentmentData.useCases.count > 1 {
                    SegmentedItemContainer(index: 0, count: 1) {
                        RelyingPartyInfoContent(
                            paragraphs: rpParagraphs,
                            disclaimer: requesterDisplayData.disclaimer,
                            showWarningIcon: false,
                            onShowRequesterInfo: onShowRequesterInfo
                        )
                    }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        } footer: { isAtBottom, scrollDown in
            ConsentButtonFooter(
                isAtBottom: isAtBottom,
                requireCoolOff: riskLevel == .highRisk,
                isTesting: isTesting,
                coolOffComplete: $coolOffComplete,
                onScrollDown: scrollDown,
                onConfirm: {
                    onConfirm(presentmentData.toPresentmentSelection(
                        selections: selections.map { KotlinInt(int: Int32($0)) },
                        transactionUserInput: transactionUserInput
                    ))
                },
                onCancel: onCancel
            )
        }
    }
}

private struct ConsentButtonFooter: View {
    let isAtBottom: Bool
    let requireCoolOff: Bool
    let isTesting: Bool
    @Binding var coolOffComplete: Bool
    let onScrollDown: () -> Void
    let onConfirm: () -> Void
    let onCancel: () -> Void

    @State private var coolOffProgress: CGFloat = 0.0

    var body: some View {
        let showMoreButton = !isAtBottom
        let isWaiting = requireCoolOff && !showMoreButton && !coolOffComplete
        let effectiveProgress: CGFloat = isTesting ? (isWaiting ? 0.35 : 0.0) : coolOffProgress
        let buttonText: String = {
            if showMoreButton {
                return "More"
            } else if isWaiting {
                return "Wait…"
            } else {
                return "Continue"
            }
        }()

        HStack(spacing: 12) {
            Button(action: onCancel) {
                Text("Cancel")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        Capsule()
                            .stroke(Color(UIColor.separator), lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)

            Button(action: {
                if showMoreButton {
                    onScrollDown()
                } else if !isWaiting {
                    onConfirm()
                }
            }) {
                Text(buttonText)
                    .font(.headline)
                    .foregroundStyle(isWaiting ? Color.primary.opacity(0.6) : Color.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background {
                        GeometryReader { geo in
                            if isWaiting {
                                ZStack(alignment: .leading) {
                                    Rectangle()
                                        .fill(Color.primary.opacity(0.12))
                                    Rectangle()
                                        .fill(Color.accentColor.opacity(0.28))
                                        .frame(width: geo.size.width * effectiveProgress)
                                }
                            } else {
                                Rectangle()
                                    .fill(Color.accentColor)
                            }
                        }
                    }
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(isWaiting)
        }
        .padding(.horizontal)
        .padding(.top, 8)
        .padding(.bottom, 16)
        .task(id: isWaiting) {
            guard !isTesting else { return }
            if isWaiting {
                coolOffProgress = 0.0
                withAnimation(.linear(duration: 5.0)) {
                    coolOffProgress = 1.0
                }
                do {
                    try await Task.sleep(for: .seconds(5))
                    if !Task.isCancelled {
                        coolOffComplete = true
                    }
                } catch {
                    coolOffProgress = 0.0
                }
            } else {
                coolOffProgress = 0.0
            }
        }
    }
}

private struct UseCaseSection: View {
    let useCaseIndex: Int
    let requester: Requester
    let requesterDisplayData: RequesterDisplayData
    let trustedRequesterIdentity: TrustedRequesterIdentity?
    let purposes: Set<PresentmentPurpose>
    let transactionDataRendererRepository: TransactionDataRendererRepository?
    let useCase: PresentmentUseCase
    let selectionIndex: Int
    let transactionUserInput: [PresentmentCredentialMatch: [String: TransactionUserInput]]
    let onTransactionUserInputChanged: (PresentmentCredentialMatch, String, TransactionUserInput) -> Void
    let onShowDocumentDetails: (DocumentDetailsTarget) -> Void
    let onShowTransactionDetails: (TransactionDetailsTarget) -> Void
    let onNavigateToPickSolution: () -> Void
    let bottomRpInfoParagraphs: [String]?
    let bottomRpInfoDisclaimer: String?
    let onShowRequesterInfo: () -> Void

    var body: some View {
        let isSelected = selectionIndex >= 0
        let solutionIndexToShow = isSelected ? selectionIndex : 0

        if useCase.solutions.count > solutionIndexToShow {
            let solution = useCase.solutions[solutionIndexToShow]
            let documentGroups = groupMatchesByDocument(solution.matches)
            let canSwitchSolution = useCase.optional || useCase.solutions.count > 1
            let showBottomSwitchSegment = isSelected && canSwitchSolution && documentGroups.count > 1
            let opacity = isSelected ? 1.0 : 0.5

            Group {
                if !isSelected {
                    ForEach(0..<documentGroups.count, id: \.self) { groupIdx in
                        let group = documentGroups[groupIdx]
                        SegmentedItemContainer {
                            CredentialViewerNotSelectedRow(
                                typeDisplayName: group.matches[0].resolveTypeDisplayName(),
                                showChevron: groupIdx == 0 && canSwitchSolution,
                                onChevronClicked: onNavigateToPickSolution
                            )
                            .opacity(opacity)
                        }
                    }
                } else {
                    ForEach(0..<documentGroups.count, id: \.self) { groupIdx in
                        let group = documentGroups[groupIdx]
                        let canSwitchOnCredential = canSwitchSolution && (documentGroups.count == 1)

                        SegmentedItemContainer {
                            CredentialViewerRow(
                                document: group.document,
                                showChevron: canSwitchOnCredential,
                                onChevronClicked: onNavigateToPickSolution
                            )
                        }

                        if !group.claimSections.isEmpty {
                            let onViewDetails = {
                                onShowDocumentDetails(
                                    DocumentDetailsTarget(
                                        useCaseIndex: useCaseIndex,
                                        matchIndex: group.firstMatchIndex
                                    )
                                )
                            }
                            SegmentedItemContainer {
                                VStack(alignment: .leading, spacing: 10) {
                                    ForEach(0..<group.claimSections.count, id: \.self) { sectionIdx in
                                        let section = group.claimSections[sectionIdx]
                                        let isLastSection = sectionIdx == group.claimSections.count - 1
                                        let headerText = calcSharedWithText(
                                            requester: requester,
                                            requesterDisplayData: requesterDisplayData,
                                            storesData: section.storesData,
                                            encryptionRequested: section.encryptionRequested,
                                            encryptionTargetTrustMetadata: section.encryptionTargetTrustMetadata
                                        )
                                        Text(headerText)
                                            .font(.subheadline.weight(.semibold))
                                            .multilineTextAlignment(.leading)
                                            .fixedSize(horizontal: false, vertical: true)
                                        ClaimsGridView(
                                            allClaims: section.claims,
                                            onViewDetailsClicked: isLastSection ? onViewDetails : nil
                                        )
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }

                        let hasClaims = !group.claimSections.isEmpty
                        let txMatches = group.matches.filter { !$0.transactionData.isEmpty }
                        ForEach(0..<txMatches.count, id: \.self) { txIdx in
                            let match = txMatches[txIdx]
                            let blocks = transactionDataBlocks(match.transactionData, repository: transactionDataRendererRepository)
                            SegmentedItemContainer {
                                VStack(alignment: .leading, spacing: 8) {
                                    ForEach(0..<blocks.count, id: \.self) { blockIdx in
                                        switch blocks[blockIdx] {
                                        case .builtIn(let data):
                                            DisplayTransactionData(
                                                transactionData: data,
                                                hasClaims: hasClaims,
                                                userInput: transactionUserInput[match]?[data.type.identifier],
                                                onUserInputChanged: { userInput in
                                                    onTransactionUserInputChanged(match, data.type.identifier, userInput)
                                                }
                                            )
                                        case .wallet(let identifier, let items, let renderer):
                                            let target = TransactionDetailsTarget(
                                                match: match,
                                                identifier: identifier,
                                                items: items,
                                                renderer: renderer,
                                                hasClaims: hasClaims
                                            )
                                            renderer.summary(TransactionDataContext(
                                                transactionData: items,
                                                requester: requester,
                                                trustedRequesterIdentity: trustedRequesterIdentity,
                                                hasClaims: hasClaims,
                                                purposes: purposes,
                                                userInput: transactionUserInput[match]?[identifier],
                                                onUserInputChanged: { userInput in
                                                    onTransactionUserInputChanged(match, identifier, userInput)
                                                }
                                            ))
                                            if renderer.details != nil {
                                                ViewDetailsChipButton {
                                                    onShowTransactionDetails(target)
                                                }
                                            }
                                        }
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }

                    if showBottomSwitchSegment {
                        SegmentedItemContainer {
                            Button(action: onNavigateToPickSolution) {
                                HStack(spacing: 8) {
                                    Text("Share something else…")
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(.primary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(.secondary)
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .stroke(Color(UIColor.separator), lineWidth: 1)
                                )
                                .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if bottomRpInfoParagraphs != nil || bottomRpInfoDisclaimer != nil {
                    SegmentedItemContainer {
                        RelyingPartyInfoContent(
                            paragraphs: bottomRpInfoParagraphs ?? [],
                            disclaimer: bottomRpInfoDisclaimer,
                            showWarningIcon: false,
                            onShowRequesterInfo: onShowRequesterInfo
                        )
                    }
                }
            }
        } else {
            SegmentedItemContainer {
                Text("No credentials available")
            }
        }
    }
}

private struct PickSolutionView: View {
    let maxHeight: CGFloat
    let useCase: PresentmentUseCase
    let currentSolutionIndex: Int
    let isActive: Bool
    let onScrollMetricsChange: ((CGFloat, CGFloat) -> Void)?
    let onSolutionSelected: (Int) -> Void

    var body: some View {
        SmartSheet(
            maxHeight: maxHeight,
            updateDetents: false,
            isActive: isActive,
            onScrollMetricsChange: onScrollMetricsChange
        ) {
        } content: {
            VStack(spacing: 12) {
                ForEach(0..<useCase.solutions.count, id: \.self) { idx in
                    let solution = useCase.solutions[idx]
                    let solutionGroups = groupMatchesByDocument(solution.matches)
                    let isSelectedSolution = (idx == currentSolutionIndex)
                    Button {
                        onSolutionSelected(idx)
                    } label: {
                        SegmentedItemContainer(index: 0, count: 1, selected: isSelectedSolution) {
                            HStack(alignment: .center, spacing: 12) {
                                VStack(alignment: .leading, spacing: 12) {
                                    ForEach(0..<solutionGroups.count, id: \.self) { groupIdx in
                                        let group = solutionGroups[groupIdx]
                                        CredentialViewerRow(
                                            document: group.document,
                                            showChevron: false,
                                            onChevronClicked: {}
                                        )
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)

                                Image(systemName: isSelectedSolution ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 22))
                                    .foregroundStyle(isSelectedSolution ? Color.accentColor : Color.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }

                if useCase.optional {
                    let isNotSharingSelected = currentSolutionIndex < 0
                    let fallbackTypeName = useCase.solutions.first
                        .map { groupMatchesByDocument($0.matches) }?
                        .map { $0.matches[0].resolveTypeDisplayName() }
                        .joined(separator: ", ") ?? ""
                    Button {
                        onSolutionSelected(-1)
                    } label: {
                        SegmentedItemContainer(index: 0, count: 1, selected: isNotSharingSelected) {
                            HStack(alignment: .center, spacing: 12) {
                                Image(systemName: "nosign")
                                    .font(.system(size: 22))
                                    .foregroundStyle(.primary)
                                    .frame(width: 60, height: 40)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Don't share anything")
                                        .font(.headline)
                                        .foregroundStyle(.primary)
                                    if !fallbackTypeName.isEmpty {
                                        Text(fallbackTypeName)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)

                                Image(systemName: isNotSharingSelected ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 22))
                                    .foregroundStyle(isNotSharingSelected ? Color.accentColor : Color.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        } footer: { _, _ in
        }
        .navigationTitle("Select what to share")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(.editor)
    }
}
