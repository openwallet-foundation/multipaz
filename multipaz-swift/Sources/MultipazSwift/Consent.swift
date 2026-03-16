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
    let document: Document
    let retainedClaims: [Claim]
    let notRetainedClaims: [Claim]
    let showOptionsButton: Bool
    let onOptionsTapped: () -> Void

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
                    onOptionsTapped()
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
    let matchSelections: [Int]
    let onShowOptions: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            
            ForEach(0..<combination.elements.count, id: \.self) { idx in
                let element = combination.elements[idx]
                let matchIndex = idx < matchSelections.count ? matchSelections[idx] : 0
                let match = element.matches[matchIndex]
                
                let retainedClaims = Array(match.claims.filter( {
                    if $0 is MdocClaim {
                        ($0.key as! MdocRequestedClaim).intentToRetain == true
                    } else {
                        false
                    }
                }).values).sorted(by: { a, b in
                    if a is MdocClaim {
                        (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
                    } else {
                        (a as! JsonClaim).displayName < (b as! JsonClaim).displayName
                    }
                })

                let notRetainedClaims = Array(match.claims.filter( {
                    if $0 is MdocClaim {
                        ($0.key as! MdocRequestedClaim).intentToRetain == false
                    } else {
                        true
                    }
                }).values).sorted(by: { a, b in
                    if a is MdocClaim {
                        (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
                    } else {
                        (a as! JsonClaim).displayName < (b as! JsonClaim).displayName
                    }
                })

                RequestedDocumentSection(
                    rpName: rpName,
                    document: match.credential.document,
                    retainedClaims: retainedClaims,
                    notRetainedClaims: notRetainedClaims,
                    showOptionsButton: element.matches.count > 1,
                    onOptionsTapped: { onShowOptions(idx) }
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
    // Store heights for each page index
    @State private var pageHeights: [Int: CGFloat] = [:]

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
                                .measurePageHeight(index)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                    // Dynamically size based on the current page's measured height
                    .frame(height: pageHeights[currentPage] ?? 300)
                    .onPreferenceChange(PageHeightsKey.self) { heights in
                        self.pageHeights = heights
                    }
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
    /// Applies the standard card styling used in the consent flow
    fileprivate func cardStyle() -> some View {
        self
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemGroupedBackground))
                    .shadow(color: Color.black.opacity(0.1), radius: 8, x: 0, y: 4)
            )
    }
    
    /// Measures the height of a page and tags it with the index using PreferenceKey
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

/// A PreferenceKey that aggregates heights for multiple pages in a dictionary.
private struct PageHeightsKey: PreferenceKey {
    static let defaultValue: [Int: CGFloat] = [:]
    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
        value.merge(nextValue()) { (_, new) in new }
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

    @State private var isFlipped = false
    @State private var activeElementIndex = 0
    @State private var currentPage: Int = 0
    
    // Tracks selections: [CombinationIndex][ElementIndex] -> MatchIndex
    @State private var allMatchSelections: [[Int]] = []
    
    // Store heights for each page index
    @State private var pageHeights: [Int: CGFloat] = [:]

    private func ensureSelectionsInitialized() {
        if allMatchSelections.isEmpty {
            allMatchSelections = combinations.map { combination in
                Array(repeating: 0, count: combination.elements.count)
            }
        }
    }

    var body: some View {
        SmartSheet(maxHeight: maxHeight) {
            RelyingPartySection(
                rpName: rpName,
                trustMetadata: trustMetadata,
                onRequesterClicked: onRequesterClicked
            )
            .padding()
            .onAppear { ensureSelectionsInitialized() }
        } content: {
            VStack(spacing: 10) {
                if !allMatchSelections.isEmpty {
                    TabView(selection: $currentPage) {
                        ForEach(0..<combinations.count, id: \.self) { index in
                            let combination = combinations[index]
                            let currentSelections = allMatchSelections[index]
                            
                            FlipView(
                                isFlipped: isFlipped,
                                front: {
                                    CombinationSection(
                                        rpName: rpName,
                                        requester: requester,
                                        trustMetadata: trustMetadata,
                                        combination: combination,
                                        matchSelections: currentSelections,
                                        onShowOptions: { elementIdx in
                                            activeElementIndex = elementIdx
                                            withAnimation {
                                                isFlipped = true
                                            }
                                        }
                                    )
                                    .cardStyle()
                                },
                                back: {
                                    DocumentSelectionView(
                                        combination: combination,
                                        elementIndex: activeElementIndex,
                                        initialSelection: currentSelections.indices.contains(activeElementIndex) ? currentSelections[activeElementIndex] : 0,
                                        onBack: {
                                            withAnimation {
                                                isFlipped = false
                                            }
                                        },
                                        onSelect: { newIndex in
                                            allMatchSelections[index][activeElementIndex] = newIndex
                                            withAnimation {
                                                isFlipped = false
                                            }
                                        }
                                    )
                                    .cardStyle()
                                }
                            )
                            .padding(.vertical, 20)
                            .padding(.horizontal)
                            .tag(index)
                            .measurePageHeight(index)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                    // Dynamically size the TabView to the height of the CURRENT page.
                    .frame(height: pageHeights[currentPage] ?? 300)
                    .onPreferenceChange(PageHeightsKey.self) { heights in
                        self.pageHeights = heights
                    }
                } else {
                     // Loading state or empty
                     ProgressView()
                        .frame(height: 200)
                }
            }
        } footer: { isAtBottom, scrollDown in
            VStack(spacing: 0) {
                // Pager Indicators
                if !isFlipped && combinations.count > 1 {
                    HStack(spacing: 4) {
                        ForEach(0..<combinations.count, id: \.self) { index in
                            Circle()
                                .fill(
                                    index == currentPage
                                    ? Color.blue
                                    : Color.primary.opacity(0.2)
                                )
                                .frame(width: 8, height: 8)
                        }
                    }
                    .padding(.bottom, 12)
                }
                
                if !isFlipped {
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
                                if combinations.indices.contains(currentPage), allMatchSelections.indices.contains(currentPage) {
                                    let combination = combinations[currentPage]
                                    let selections = allMatchSelections[currentPage]
                                    
                                    var selectedMatches: [CredentialPresentmentSetOptionMemberMatch] = []
                                    for (idx, element) in combination.elements.enumerated() {
                                        let matchIdx = idx < selections.count ? selections[idx] : 0
                                        selectedMatches.append(element.matches[matchIdx])
                                    }
                                    onConfirm(CredentialPresentmentSelection(matches: selectedMatches))
                                }
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
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
    }
}

private struct FlipView<Front: View, Back: View>: View {
    var isFlipped: Bool
    var front: () -> Front
    var back: () -> Back
    
    init(isFlipped: Bool, @ViewBuilder front: @escaping () -> Front, @ViewBuilder back: @escaping () -> Back) {
        self.isFlipped = isFlipped
        self.front = front
        self.back = back
    }
    
    var body: some View {
        ZStack {
            front()
                .opacity(isFlipped ? 0 : 1)
            back()
                .opacity(isFlipped ? 1 : 0)
                .rotation3DEffect(.degrees(180), axis: (x: 0, y: 1, z: 0))
        }
        .rotation3DEffect(.degrees(isFlipped ? 180 : 0), axis: (x: 0, y: 1, z: 0))
    }
}

private struct DocumentSelectionView: View {
    let combination: Combination
    let elementIndex: Int
    let initialSelection: Int
    let onBack: () -> Void
    let onSelect: (Int) -> Void

    @State private var selectedIndex: Int
    
    init(combination: Combination, elementIndex: Int, initialSelection: Int, onBack: @escaping () -> Void, onSelect: @escaping (Int) -> Void) {
        self.combination = combination
        self.elementIndex = elementIndex
        self.initialSelection = initialSelection
        self.onBack = onBack
        self.onSelect = onSelect
        _selectedIndex = State(initialValue: initialSelection)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            ZStack {
                HStack {
                    Button(action: onBack) {
                        Image(systemName: "arrow.backward")
                            .imageScale(.large)
                    }
                    Spacer()
                }
                Text("Select document")
                    .font(.headline)
            }
            .padding(.bottom, 10)

            Divider()

            // List of matches
            if elementIndex < combination.elements.count {
                let element = combination.elements[elementIndex]
                ForEach(0..<element.matches.count, id: \.self) { idx in
                    let match = element.matches[idx]
                    VStack {
                        HStack {
                            Image(uiImage: match.credential.document.renderCardArt())
                                .resizable()
                                .scaledToFit()
                                .frame(height: 40)
                            
                            VStack(alignment: .leading, spacing: 2) {
                                Text(match.credential.document.displayName ?? "Unknown Document")
                                    .font(.body)
                                    .fontWeight(.medium)
                                if let type = match.credential.document.typeDisplayName {
                                    Text(type)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            
                            Image(systemName: selectedIndex == idx ? "circle.inset.filled" : "circle")
                                .imageScale(.large)
                                .foregroundStyle(selectedIndex == idx ? .blue : .gray)
                        }
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selectedIndex = idx
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                                onSelect(idx)
                            }
                        }
                        Divider()
                    }
                }
            }
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
