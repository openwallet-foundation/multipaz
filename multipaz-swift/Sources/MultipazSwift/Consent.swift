import Multipaz
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
    let document: Document
    let retainedClaims: [Claim]
    let notRetainedClaims: [Claim]
    let showOptionsButton: Bool

    var body: some View {
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
                    // TODO: go to screen to allow user to select document
                    print("Chevron tapped")
                }) {
                    Image(systemName: "chevron.down.circle")
                        .imageScale(.large)
                }
            }
        }

        Divider()

        if (!notRetainedClaims.isEmpty) {
            VStack(alignment: .leading, spacing: 10) {
                Text("This data will be shared with \(rpName):")
                    .font(.system(size: 14, weight: .bold))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                ClaimsSection(claims: notRetainedClaims)
            }
        }
        if (!retainedClaims.isEmpty) {
            VStack(alignment: .leading, spacing: 10) {
                Text("This data will be stored by \(rpName):")
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

struct CombinationSection: View {
    let rpName: String
    let requester: Requester
    let trustMetadata: TrustMetadata?
    fileprivate let combination: Combination

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            
            ForEach(0..<combination.elements.count, id: \.self) { idx in
                let element = combination.elements[idx]
                // TODO: add ability to select match if more than one...
                let match = element.matches.first!

                let retainedClaims = Array(match.claims.filter( {
                    ($0.key as! MdocRequestedClaim).intentToRetain == true
                }).values).sorted(by: { a, b in
                    (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
                })

                let notRetainedClaims = Array(match.claims.filter( {
                    ($0.key as! MdocRequestedClaim).intentToRetain == false
                }).values).sorted(by: { a, b in
                    (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
                })

                RequestedDocumentSection(
                    rpName: rpName,
                    document: match.credential.document,
                    retainedClaims: retainedClaims,
                    notRetainedClaims: notRetainedClaims,
                    showOptionsButton: element.matches.count > 1
                )
            }

            // Note: on iOS we do not support apps requesting data so no need to handle the case
            // where requester.origin is nil and requester.appId isn't.
            //
            let (infoText, showWarning) = if requester.origin == nil {
                if let privacyPolicyUrl = trustMetadata?.privacyPolicyUrl {
                    (
                        "The identity reader requesting this data is trusted. " +
                        "Review the [\(rpName) privacy policy](\(privacyPolicyUrl))",
                        false
                    )
                } else if trustMetadata != nil {
                    ("The identity reader requesting this data is trusted", false)
                } else {
                    (
                        "The identity reader requesting this data is unknown. " +
                        "Make sure you are comfortable sharing this data",
                        true
                    )
                }
            } else {
                if let privacyPolicyUrl = trustMetadata?.privacyPolicyUrl {
                    (
                        "The website requesting this data is trusted. " +
                        "Review the [\(rpName) privacy policy](\(privacyPolicyUrl))",
                        false
                    )
                } else if trustMetadata != nil {
                    ("The website requesting this data is trusted", false)
                } else {
                    (
                        "The website requesting this data is unknown. " +
                        "Make sure you are comfortable sharing this data",
                        true
                    )
                }
            }
            Divider()
            InfoSection(markdown: infoText, showWarning: showWarning)
        }
    }
}

private enum ConsentDestinations: Hashable {
    case showRequesterInfo
}

/// A ``View`` which asks the user to approve sharing of a credentials.
///
/// - Parameters:
///   - credentialPresentmentData: the combinations of credentials and claims that the user can select.
///   - requester: the relying party which is requesting the data.
///   - trustMetadata:``TrustMetadata`` conveying the level of trust in the requester, if any.
///   - maxHeight: the maximum height of the view.
///   - onConfirm: callback when the user presses the Share button with the credentials that were selected.
///   - onCancel: callback when the user presses the Cancel button.
public struct Consent: View {
    let maxHeight: CGFloat
    let credentialPresentmentData: CredentialPresentmentData
    let requester: Requester
    let trustMetadata: TrustMetadata?
    let onConfirm: (_: CredentialPresentmentSelection) -> Void
    let onCancel: () -> Void

    fileprivate let combinations: [Combination]

    public init(
        credentialPresentmentData: CredentialPresentmentData,
        requester: Requester,
        trustMetadata: TrustMetadata?,
        maxHeight: CGFloat = .infinity,
        onConfirm: @escaping (_: CredentialPresentmentSelection) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.credentialPresentmentData = credentialPresentmentData
        // TODO: take preselectedDocuments
        self.combinations = credentialPresentmentData.generateCombinations(preselectedDocuments: [])
        self.requester = requester
        self.trustMetadata = trustMetadata
        self.maxHeight = maxHeight
        self.onConfirm = onConfirm
        self.onCancel = onCancel
    }

    @State private var path = NavigationPath()

    public var body: some View {
        let rpName = getRelyingPartyName(
            requester: requester,
            trustMetadata: trustMetadata
        )
        NavigationStack(path: $path) {
            VStack {
                ConsentMain(
                    maxHeight: maxHeight,
                    credentialPresentmentData: credentialPresentmentData,
                    rpName: rpName,
                    requester: requester,
                    trustMetadata: trustMetadata,
                    combinations: combinations,
                    onRequesterClicked: {
                        if requester.certChain != nil {
                            path.append(ConsentDestinations.showRequesterInfo)
                        }
                    },
                    onConfirm: onConfirm,
                    onCancel: onCancel
                )
            }
            .navigationDestination(for: ConsentDestinations.self) { destination in
                switch destination {
                case .showRequesterInfo:
                    ShowRequesterInfo(
                        maxHeight: maxHeight,
                        requester: requester
                    )
                }
            }
        }
    }
}

private struct ShowRequesterInfo: View {
    let maxHeight: CGFloat
    let requester: Requester
    @State private var currentPage: Int = 0
    @State private var tabHeight: CGFloat = 300

    var body: some View {
        VStack {
            SmartSheet(maxHeight: maxHeight) {
            } content: {
                let certificates = requester.certChain!.certificates
                VStack {
                    TabView(selection: $currentPage) {
                        ForEach(0..<certificates.count, id: \.self) { index in
                            X509CertViewer(certificate: certificates[index])
                                .tag(index)
                                .readHeight(to: $tabHeight)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                    .frame(height: tabHeight)
                }
            } footer: { isAtBottom, scrollDown in
                let certificates = requester.certChain!.certificates
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
        }
        .navigationTitle("Requester info")
    }
}

extension View {
    /// Reads the height of a view and pushes it to a Binding.
    fileprivate func readHeight(to binding: Binding<CGFloat>) -> some View {
        background(
            GeometryReader { proxy in
                Color.clear
                    .preference(key: TabLayerHeightKey.self, value: proxy.size.height)
            }
        )
        .onPreferenceChange(TabLayerHeightKey.self) { height in
            // Only update if the change is significant to avoid layout loops
            if abs(binding.wrappedValue - height) > 1 {
                binding.wrappedValue = height
            }
        }
    }
}

private struct TabLayerHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct ConsentMain: View {
    let maxHeight: CGFloat
    let credentialPresentmentData: CredentialPresentmentData
    let rpName: String
    let requester: Requester
    let trustMetadata: TrustMetadata?
    let combinations: [Combination]
    let onRequesterClicked: () -> Void
    let onConfirm: (_: CredentialPresentmentSelection) -> Void
    let onCancel: () -> Void

    var body: some View {
        SmartSheet(maxHeight: maxHeight) {
            RelyingPartySection(
                rpName: rpName,
                trustMetadata: trustMetadata,
                onRequesterClicked: onRequesterClicked
            )
            .padding()
        } content: {
            VStack(spacing: 10) {
                VStack {
                    let combination = combinations.first!
                    CombinationSection(
                        rpName: rpName,
                        requester: requester,
                        trustMetadata: trustMetadata,
                        combination: combination
                    )
                }
                .padding()
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemGroupedBackground))
                        .shadow(color: Color.black.opacity(0.1), radius: 8, x: 0, y: 4)
                )
                .padding(.vertical, 20)
            }
            .padding(.horizontal)
        } footer: { isAtBottom, scrollDown in
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
                        onConfirm(credentialPresentmentData.select(preselectedDocuments: []))
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

private struct CombinationElement {
    let matches: [CredentialPresentmentSetOptionMemberMatch]
}

private struct Combination {
    let elements: [CombinationElement]
}

extension CredentialPresentmentData {

    fileprivate func generateCombinations(preselectedDocuments: [Document]) -> [Combination] {
        var combinations: [Combination] = []
        let consolidated = self.consolidate()

        var credentialSetsMaxPath: [Int] = []
        for credentialSet in consolidated.credentialSets {
            let extraSlot = credentialSet.optional ? 1 : 0
            credentialSetsMaxPath.append(credentialSet.options.count + extraSlot)
        }

        for path in credentialSetsMaxPath.generateAllPaths() {
            var elements: [CombinationElement] = []

            for (credentialSetNum, credentialSet) in consolidated.credentialSets.enumerated() {
                let omitCredentialSet = (path[credentialSetNum] == credentialSet.options.count)
                if omitCredentialSet {
                    assert(credentialSet.optional, "Path indicated omission for non-optional set")
                } else {
                    let option = credentialSet.options[path[credentialSetNum]]
                    for member in option.members {
                        elements.append(CombinationElement(matches: member.matches))
                    }
                }
            }
            combinations.append(Combination(elements: elements))
        }

        if preselectedDocuments.isEmpty {
            return combinations
        }

        let setOfPreselectedDocuments = Set(preselectedDocuments)

        for combination in combinations {
            if combination.elements.count == preselectedDocuments.count {
                var chosenElements: [CombinationElement] = []

                for element in combination.elements {
                    let match = element.matches.first { match in
                        setOfPreselectedDocuments.contains(match.credential.document)
                    }
                    
                    guard let foundMatch = match else {
                        continue
                    }
                    
                    chosenElements.append(CombinationElement(matches: [foundMatch]))
                }

                // Winner, winner, chicken dinner!
                return [Combination(elements: chosenElements)]
            }
        }

        print("Error picking combination for pre-selected documents")
        return combinations
    }
}

extension Array where Element == Int {
    
    /// Given a list [X0, X1, ...], generates a list of lists where the `n`th position
    /// iterates from 0 up to Xn.
    fileprivate func generateAllPaths() -> [[Int]] {
        if isEmpty {
            return [[]]
        }
        var allPaths: [[Int]] = []
        var currentPath = Array(repeating: 0, count: count)
        
        generate(index: 0, currentPath: &currentPath, allPaths: &allPaths, maxPath: self)
        
        return allPaths
    }
    
    private func generate(
        index: Int,
        currentPath: inout [Int],
        allPaths: inout [[Int]],
        maxPath: [Int]
    ) {
        if index == maxPath.count {
            allPaths.append(currentPath)
            return
        }
        
        for value in 0..<maxPath[index] {
            currentPath[index] = value
            generate(index: index + 1, currentPath: &currentPath, allPaths: &allPaths, maxPath: maxPath)
        }
    }
}
