import SwiftUI

func getIconName(claim: Claim) -> String {
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
        case .none: return "gear"
        }
    }
    return "gear"
}

struct ClaimsSection : View {

    let claims: [Claim]

    var body: some View {
        let columns = [
            GridItem(.flexible()),
            GridItem(.flexible())
        ]
        LazyVGrid(columns: columns, alignment: .leading, spacing: 10) {
            ForEach(claims, id: \.self) { claim in
                HStack {
                    Image(systemName: getIconName(claim: claim))
                        .imageScale(.small)
                    Text("\(claim .displayName)")
                        .font(.system(size: 14))
                }
            }
        }
        .foregroundColor(.primary)
    }
}

struct RequestedDocumentSection : View {
 
    let rpName: String
    let requester: Requester
    let encryptionRequested: Bool
    let encryptionTargetTrustMetadata: TrustMetadata?
    let document: Document
    let retainedClaims: [Claim]
    let notRetainedClaims: [Claim]
    let showOptionsButton: Bool
    let onOptionsTapped: () -> Void

    var body: some View {
        let isKnown = requester.origin != nil || (rpName != "Unknown requester" && rpName != "Unknown website" && rpName != "Unknown verifier")
        
        let sharedText: String = {
            if !encryptionRequested {
                return isKnown ? "This data will be shared with \(rpName):" : "This data will be shared:"
            }
            if let encTargetName = encryptionTargetTrustMetadata?.displayName {
                return isKnown ? "\(rpName) is requesting this data on behalf of \(encTargetName):" : "This data is requested on behalf of \(encTargetName):"
            }
            return isKnown ? "\(rpName) is requesting this data on behalf of an unknown party:" : "This data is requested on behalf of an unknown party:"
        }()

        let storedText: String = {
            if !encryptionRequested {
                return isKnown ? "This data will be stored by \(rpName):" : "This data will be stored:"
            }
            if let encTargetName = encryptionTargetTrustMetadata?.displayName {
                return isKnown ? "\(rpName) is requesting this data on behalf of \(encTargetName) who will store it:" : "This data is requested on behalf of \(encTargetName) who will store it:"
            }
            return isKnown ? "\(rpName) is requesting this data on behalf of an unknown party who will store it:" : "This data is requested on behalf of an unknown party who will store it:"
        }()

        HStack(alignment: .center) {
            Image(uiImage: document.renderCardArt())
                .resizable()
                .scaledToFit()
                .frame(height: 40)
            if let displayName = document.displayName {
                VStack(alignment: .leading, spacing: 5) {
                    Text(displayName)
                        .font(.headline)
                    if let typeDisplayName = document.typeDisplayName {
                        Text(typeDisplayName)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                Text("Unknown Document")
            }
            if showOptionsButton {
                Spacer()
                Button(action: {
                    onOptionsTapped()
                }) {
                    Image(systemName: "chevron.right")
                        .foregroundColor(.gray)
                }
            }
        }

        if (!notRetainedClaims.isEmpty) {
            VStack(alignment: .leading, spacing: 10) {
                Text(sharedText)
                    .font(.system(size: 14, weight: .bold))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                ClaimsSection(claims: notRetainedClaims)
            }
        }
        if (!retainedClaims.isEmpty) {
            VStack(alignment: .leading, spacing: 10) {
                Text(storedText)
                    .font(.system(size: 14, weight: .bold))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                ClaimsSection(claims: retainedClaims)
            }
        }
    }
}

func getRelyingPartyName(
    requester: Requester,
    trustMetadata: TrustMetadata?,
) -> String {
    if trustMetadata != nil {
        if let displayName = trustMetadata?.displayName {
            return displayName
        } else {
            return "Trusted verifier"
        }
    } else if let origin = requester.origin {
        return origin
    } else {
        return "Unknown requester"
    }
}

struct RelyingPartySection : View {

    let rpName: String
    let trustMetadata: TrustMetadata?
    let onRequesterClicked: () -> Void

    var body: some View {

        VStack(spacing: 10) {
            if let iconUrl = trustMetadata?.displayIconUrl {
                AsyncImage(url: URL(string: iconUrl)) { phase in
                    if let image = phase.image {
                        image
                            .resizable()
                            .scaledToFit()
                            .frame(height: 80)
                            .onTapGesture { onRequesterClicked() }
                    } else if phase.error != nil {
                        Image(systemName: "xmark.circle")
                            .foregroundColor(.red)
                            .font(.largeTitle)
                            .onTapGesture { onRequesterClicked() }
                    } else {
                        ProgressView()
                            .onTapGesture { onRequesterClicked() }
                    }
                }
            } else if let iconData = trustMetadata?.displayIcon {
                let uiImage = UIImage(data: iconData.toNSData())!
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(height: 80)
                    .onTapGesture { onRequesterClicked() }
            }

            Text(rpName)
                .font(.system(size: 22, weight: .bold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .onTapGesture { onRequesterClicked() }
        }
    }
}

struct InfoSection: View {
    let markdown: String
    let showWarning: Bool
    
    var body: some View {
        HStack(alignment: .center) {
            Image(systemName: showWarning ? "exclamationmark.triangle" : "info.circle")
                .imageScale(.small)
                .foregroundStyle(showWarning ? .red : .primary)
            Text(try! AttributedString(markdown: markdown))
                .font(.system(size: 14))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(showWarning ? .red : .primary)
        }
    }
}



private enum ConsentDestinations: Hashable {
    case showRequesterInfo
    case pickSolution(useCaseIndex: Int)
}

/// A ``View`` which asks the user to approve sharing of a credentials.
///
/// - Parameters:
///   - consentData: the consent data containing use cases and solutions.
///   - requester: the relying party which is requesting the data.
///   - trustMetadata:``TrustMetadata`` conveying the level of trust in the requester, if any.
///   - maxHeight: the maximum height of the view.
///   - onConfirm: callback when the user presses the Share button with the credentials that were selected.
///   - onCancel: callback when the user presses the Cancel button.
public struct Consent: View {
    let maxHeight: CGFloat
    let consentData: ConsentData
    let requester: Requester
    let trustedRequesterIdentity: TrustedRequesterIdentity?
    let onConfirm: (_: CredentialSelection) -> Void
    let onCancel: () -> Void

    public init(
        consentData: ConsentData,
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
        maxHeight: CGFloat = .infinity,
        onConfirm: @escaping (_: CredentialSelection) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.consentData = consentData
        self.requester = requester
        self.trustedRequesterIdentity = trustedRequesterIdentity
        self.maxHeight = maxHeight
        self.onConfirm = onConfirm
        self.onCancel = onCancel
    }

    @State private var path = NavigationPath()
    @State private var selections: [Int] = []
    @State private var sheetHeight: CGFloat = 450

    private func getInitialSelections(preselectedDocuments: [Document]) -> [Int] {
        var result: [Int] = []
        for i in 0..<consentData.useCases.count {
            let useCase = consentData.useCases[i]
            if preselectedDocuments.isEmpty {
                result.append(useCase.solutions.count > 0 ? 0 : -1)
            } else {
                let preselectedSet = Set(preselectedDocuments)
                var matchingIndex = -1
                for j in 0..<useCase.solutions.count {
                    let solution = useCase.solutions[j]
                    if solution.credentials.count > 0 {
                        var allMatch = true
                        for k in 0..<solution.credentials.count {
                            let doc = solution.credentials[k].match.credential.document
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
        let rpName = getRelyingPartyName(
            requester: requester,
            trustMetadata: trustedRequesterIdentity?.trustMetadata
        )
        NavigationStack(path: $path) {
            VStack {
                if selections.isEmpty {
                    ProgressView()
                } else {
                    ConsentMain(
                        maxHeight: maxHeight,
                        consentData: consentData,
                        rpName: rpName,
                        requester: requester,
                        trustMetadata: trustedRequesterIdentity?.trustMetadata,
                        selections: selections,
                        sheetHeight: $sheetHeight,
                        isActive: path.isEmpty,
                        onRequesterClicked: {
                            if trustedRequesterIdentity != nil {
                                path.append(ConsentDestinations.showRequesterInfo)
                            }
                        },
                        onNavigateToPickSolution: { idx in
                            path.append(ConsentDestinations.pickSolution(useCaseIndex: idx))
                        },
                        onConfirm: onConfirm,
                        onCancel: onCancel
                    )
                }
            }
            .navigationTitle("Share Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("")
                }
            }
            .onAppear {
                if selections.isEmpty {
                    selections = getInitialSelections(preselectedDocuments: [])
                }
            }
            .navigationDestination(for: ConsentDestinations.self) { destination in
                switch destination {
                case .showRequesterInfo:
                    ShowRequesterInfo(
                        maxHeight: maxHeight,
                        requester: requester,
                        trustedRequesterIdentity: trustedRequesterIdentity!
                    )
                case .pickSolution(let useCaseIndex):
                    PickSolutionView(
                        maxHeight: maxHeight,
                        useCase: consentData.useCases[useCaseIndex],
                        currentSolutionIndex: selections[useCaseIndex],
                        onSolutionSelected: { solutionIndex in
                            selections[useCaseIndex] = solutionIndex
                            path.removeLast()
                        }
                    )
                }
            }
        }
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.visible)
    }
}

private struct ShowRequesterInfo: View {
    let maxHeight: CGFloat
    let requester: Requester
    let trustedRequesterIdentity: TrustedRequesterIdentity
    @State private var currentPage: Int = 0
    @State private var pageHeights: [Int: CGFloat] = [:]

    var body: some View {
        SmartSheet(maxHeight: maxHeight, updateDetents: false) {
        } content: {
            let certificates = trustedRequesterIdentity.identity.certChain.certificates
            VStack {
                TabView(selection: $currentPage) {
                    ForEach(0..<certificates.count, id: \.self) { index in
                        X509CertViewer(certificate: certificates[index])
                            .padding()
                            .tag(index)
                            .measurePageHeight(index)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .frame(height: pageHeights[currentPage] ?? 300)
                .onPreferenceChange(PageHeightsKey.self) { heights in
                    self.pageHeights = heights
                }
            }
        } footer: { isAtBottom, scrollDown in
            let certificates = trustedRequesterIdentity.identity.certChain.certificates
            if certificates.count > 1 {
                HStack(spacing: 4) {
                    ForEach(0..<certificates.count, id: \.self) { index in
                        Circle()
                            .fill(
                                index == currentPage
                                ? Color.blue
                                : Color.primary.opacity(0.2)
                            )
                            .frame(width: 8, height: 8)
                    }
                }
                .frame(height: 30)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 8)
            }
        }
        .navigationTitle("Requester info")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(.editor)
    }
}

extension View {
    fileprivate func cardStyle() -> some View {
        self
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemGroupedBackground))
                    .shadow(color: Color.black.opacity(0.1), radius: 8, x: 0, y: 4)
            )
    }
    
    fileprivate func measurePageHeight(_ index: Int) -> some View {
        background(
            GeometryReader { proxy in
                Color.clear
                    .preference(
                        key: PageHeightsKey.self,
                        value: [index: proxy.size.height]
                    )
            }
        )
    }
}

private struct PageHeightsKey: PreferenceKey {
    static let defaultValue: [Int: CGFloat] = [:]
    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
        value.merge(nextValue()) { (_, new) in new }
    }
}

private struct ConsentMain: View {
    let maxHeight: CGFloat
    let consentData: ConsentData
    let rpName: String
    let requester: Requester
    let trustMetadata: TrustMetadata?
    let selections: [Int]
    @Binding var sheetHeight: CGFloat
    let isActive: Bool
    let onRequesterClicked: () -> Void
    let onNavigateToPickSolution: (Int) -> Void
    let onConfirm: (_: CredentialSelection) -> Void
    let onCancel: () -> Void

    var body: some View {
        SmartSheet(maxHeight: maxHeight, updateDetents: false, heightBinding: isActive ? $sheetHeight : nil) {
            RelyingPartySection(
                rpName: rpName,
                trustMetadata: trustMetadata,
                onRequesterClicked: onRequesterClicked
            )
            .padding(.horizontal)
            .padding(.bottom)
            .padding(.top, -25)
        } content: {
            VStack {
                VStack(spacing: 10) {
                    ForEach(0..<consentData.useCases.count, id: \.self) { idx in
                        FloatingItemList {
                            UseCaseSection(
                                rpName: rpName,
                                requester: requester,
                                trustMetadata: trustMetadata,
                                useCase: consentData.useCases[idx],
                                selectionIndex: selections[idx],
                                onNavigateToPickSolution: {
                                    onNavigateToPickSolution(idx)
                                }
                            )
                        }
                    }
                    
                    FloatingItemList {
                        FloatingItemContainer {
                            let (infoText, showWarning) = getDisclaimer(requester: requester, trustMetadata: trustMetadata, rpName: rpName)
                            InfoSection(markdown: infoText, showWarning: showWarning)
                        }
                        if let disclaimer = trustMetadata?.disclaimer {
                            FloatingItemContainer {
                                HStack(alignment: .top) {
                                    Image(systemName: "info.circle")
                                        .imageScale(.small)
                                    Text(disclaimer)
                                        .font(.system(size: 14))
                                        .multilineTextAlignment(.leading)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                }
                .padding()
            }
        } footer: { isAtBottom, scrollDown in
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Button(action : { onCancel() }) {
                        Text("Cancel")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                    
                    let buttonText = if (isAtBottom) {
                        "Share"
                    } else {
                        "More"
                    }
                    Button(action : {
                        if (!isAtBottom) {
                            scrollDown()
                        } else {
                            onConfirm(consentData.toCredentialSelection(selections: selections.map { KotlinInt(int: Int32($0)) }))
                        }
                    }) {
                        Text(buttonText)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                }
                .padding()
            }
        }
    }
}

private struct UseCaseSection: View {
    let rpName: String
    let requester: Requester
    let trustMetadata: TrustMetadata?
    let useCase: ConsentUseCase
    let selectionIndex: Int
    let onNavigateToPickSolution: () -> Void

    var body: some View {
        let isSelected = selectionIndex >= 0
        let solutionIndexToShow = isSelected ? selectionIndex : 0
        
        if useCase.solutions.count > solutionIndexToShow {
            let solution = useCase.solutions[solutionIndexToShow]
            let showChevron = useCase.optional || useCase.solutions.count > 1
            let opacity = isSelected ? 1.0 : 0.5
            
            Group {
                if !isSelected {
                    let firstCred = solution.credentials.first
                    let typeDisplayName = firstCred?.match.credential.document.typeDisplayName ?? "Unknown Document"
                    FloatingItemContainer {
                        HStack {
                            Image(systemName: "slash.circle")
                                .foregroundColor(.secondary)
                            VStack(alignment: .leading) {
                                Text("No document returned")
                                    .font(.body)
                                    .fontWeight(.medium)
                                Text(typeDisplayName)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            if showChevron {
                                Spacer()
                                Button(action: onNavigateToPickSolution) {
                                    Image(systemName: "chevron.right")
                                        .foregroundColor(.gray)
                                }
                            }
                        }
                        .opacity(opacity)
                    }
                } else {
                    ForEach(0..<solution.credentials.count, id: \.self) { credIdx in
                        let credential = solution.credentials[credIdx]
                        let match = credential.match
                        
                        let retainedClaims = Array(match.claims.filter( {
                            if $0.value is MdocClaim {
                                return ($0.key as! MdocRequestedClaim).intentToRetain == true
                            } else {
                                return false
                            }
                        }).values).sorted(by: { a, b in
                            if a is MdocClaim {
                                return (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
                            } else {
                                return (a as! JsonClaim).displayName < (b as! JsonClaim).displayName
                            }
                        })

                        let notRetainedClaims = Array(match.claims.filter( {
                            if $0.value is MdocClaim {
                                return ($0.key as! MdocRequestedClaim).intentToRetain == false
                            } else {
                                return true
                            }
                        }).values).sorted(by: { a, b in
                            if a is MdocClaim {
                                return (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
                            } else {
                                return (a as! JsonClaim).displayName < (b as! JsonClaim).displayName
                            }
                        })
                        
                        FloatingItemContainer {
                            VStack(alignment: .leading, spacing: 10) {
                                RequestedDocumentSection(
                                    rpName: rpName,
                                    requester: requester,
                                    encryptionRequested: credential.encryptionRequested,
                                    encryptionTargetTrustMetadata: credential.encryptionTargetTrustMetadata,
                                    document: match.credential.document,
                                    retainedClaims: retainedClaims,
                                    notRetainedClaims: notRetainedClaims,
                                    showOptionsButton: showChevron && (credIdx == 0),
                                    onOptionsTapped: onNavigateToPickSolution
                                )
                            }
                        }
                    }
                }
            }
        } else {
            FloatingItemContainer {
                Text("No credentials available")
            }
        }
    }
}

private struct PickSolutionView: View {
    let maxHeight: CGFloat
    let useCase: ConsentUseCase
    let currentSolutionIndex: Int
    let onSolutionSelected: (Int) -> Void

    var body: some View {
        SmartSheet(maxHeight: maxHeight, updateDetents: false) {
        } content: {
            VStack(spacing: 10) {
                ForEach(0..<useCase.solutions.count, id: \.self) { idx in
                    let solution = useCase.solutions[idx]
                    FloatingItemList {
                        ForEach(0..<solution.credentials.count, id: \.self) { credIdx in
                            let credential = solution.credentials[credIdx]
                            FloatingItemContainer {
                                HStack {
                                    Image(uiImage: credential.match.credential.document.renderCardArt())
                                        .resizable()
                                        .scaledToFit()
                                        .frame(height: 40)
                                    
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(credential.match.credential.document.displayName ?? "Unknown Document")
                                            .font(.body)
                                            .fontWeight(.medium)
                                        if let type = credential.match.credential.document.typeDisplayName {
                                            Text(type)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        onSolutionSelected(idx)
                    }
                }

                if useCase.optional {
                    FloatingItemList {
                        FloatingItemContainer {
                            HStack {
                                Text("Don't share anything")
                                    .foregroundColor(.red)
                                    .fontWeight(.medium)
                            }
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        onSolutionSelected(-1)
                    }
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

func getDisclaimer(
    requester: Requester,
    trustMetadata: TrustMetadata?,
    rpName: String
) -> (String, Bool) {
    if requester.origin == nil {
        if let privacyPolicyUrl = trustMetadata?.privacyPolicyUrl {
            return (
                "The identity reader requesting this data is trusted. " +
                "Review the [\(rpName) privacy policy](\(privacyPolicyUrl))",
                false
            )
        } else if trustMetadata != nil {
            return ("The identity reader requesting this data is trusted", false)
        } else {
            return (
                "The identity reader requesting this data is unknown. " +
                "Make sure you are comfortable sharing this data",
                true
            )
        }
    } else {
        if let privacyPolicyUrl = trustMetadata?.privacyPolicyUrl {
            return (
                "The website requesting this data is trusted. " +
                "Review the [\(rpName) privacy policy](\(privacyPolicyUrl))",
                false
            )
        } else if trustMetadata != nil {
            return ("The website requesting this data is trusted", false)
        } else {
            return (
                "The website requesting this data is unknown. " +
                "Make sure you are comfortable sharing this data",
                true
            )
        }
    }
}
