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
        .foregroundColor(.black)
    }
}

struct RequestedDocumentSection : View {

    let rpName: String
    let docMeta: AbstractDocumentMetadata
    let retainedClaims: [Claim]
    let notRetainedClaims: [Claim]

    var body: some View {
        HStack(alignment: .center) {
            if let cardArt = docMeta.cardArt {
                let uiImage = UIImage(data: cardArt.toNSData())!
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(height: 32)
            }
            if let displayName = docMeta.displayName {
                VStack(alignment: .leading, spacing: 5) {
                    Text(displayName)
                        .font(.headline)
                    if let typeDisplayName = docMeta.typeDisplayName {
                        Text(typeDisplayName)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                Text("Unknown Document")
            }
        }

        Divider()

        if (!notRetainedClaims.isEmpty) {
            VStack(alignment: .leading, spacing: 10) {
                Text("The following data will be shared with \(rpName)")
                    .font(.system(size: 14, weight: .bold))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                ClaimsSection(claims: notRetainedClaims)
            }
        }
        if (!retainedClaims.isEmpty) {
            VStack(alignment: .leading, spacing: 10) {
                Text("The following data will be shared with and stored by \(rpName)")
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
    trustPoint: TrustPoint?,
) -> String {
    if trustPoint != nil {
        if let displayName = trustPoint?.metadata.displayName {
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
    let trustPoint: TrustPoint?

    var body: some View {

        VStack(spacing: 10) {
            if let iconUrl = trustPoint?.metadata.displayIconUrl {
                AsyncImage(url: URL(string: iconUrl)) { phase in
                    if let image = phase.image {
                        image
                            .resizable()
                            .scaledToFit()
                            .frame(height: 80)
                    } else if phase.error != nil {
                        Image(systemName: "xmark.circle")
                            .foregroundColor(.red)
                            .font(.largeTitle)
                    } else {
                        ProgressView()
                    }
                }
            } else if let iconData = trustPoint?.metadata.displayIcon {
                let uiImage = UIImage(data: iconData.toNSData())!
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(height: 80)
            }

            Text("\(rpName) requests information")
                .font(.system(size: 22, weight: .bold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct InfoSection: View {
        let markdown: String
    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: "info.circle")
                .imageScale(.small)
            Text(try! AttributedString(markdown: markdown))
                .font(.system(size: 14))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// A ``View`` which asks the user to approve sharing of a credentials.
///
/// - Parameters:
///   - credentialPresentmentData: the combinations of credentials and claims that the user can select.
///   - requester: the relying party which is requesting the data.
///   - trustPoint: if the requester is in a trust-list, the ``TrustPoint`` indicating this.
///   - onConfirm: callback when the user presses the Share button with the credentials that were selected.
public struct Consent: View {

    let credentialPresentmentData: CredentialPresentmentData
    let requester: Requester
    let trustPoint: TrustPoint?
    let onConfirm: (_: CredentialPresentmentSelection) -> Void

    public init(
        credentialPresentmentData: CredentialPresentmentData,
        requester: Requester,
        trustPoint: TrustPoint?,
        onConfirm: @escaping (_: CredentialPresentmentSelection) -> Void,
    ) {
        self.credentialPresentmentData = credentialPresentmentData
        self.requester = requester
        self.trustPoint = trustPoint
        self.onConfirm = onConfirm
    }

    @State private var hasReachedEnd = true
    @State private var position: ScrollPosition = .init(point: .zero)

    public var body: some View {
        ScrollViewReader { proxy in
            let cred = credentialPresentmentData.credentialSets.first!
                .options.first!
                .members.first!
                .matches.first!
            let docMeta = cred.credential.document.metadata

            let retainedClaims = Array(cred.claims.filter( {
                ($0.key as! MdocRequestedClaim).intentToRetain == true
            }).values).sorted(by: { a, b in
                (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
            })
            let notRetainedClaims = Array(cred.claims.filter( {
                ($0.key as! MdocRequestedClaim).intentToRetain == false
            }).values).sorted(by: { a, b in
                (a as! MdocClaim).dataElementName < (b as! MdocClaim).dataElementName
            })

            let rpName = getRelyingPartyName(
                requester: requester,
                trustPoint: trustPoint
            )

            VStack(spacing: 5) {
                RelyingPartySection(
                    rpName: rpName,
                    trustPoint: trustPoint,
                )

                VStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 10) {
                            RequestedDocumentSection(
                                rpName: rpName,
                                docMeta: docMeta,
                                retainedClaims: retainedClaims,
                                notRetainedClaims: notRetainedClaims,
                            )

                            let infoText = if let privacyPolicyUrl = trustPoint?.metadata.privacyPolicyUrl {
                                "The website requesting this data has been identified and is trusted. " +
                                "Review the [\(rpName) privacy policy](\(privacyPolicyUrl)) to see how your data is being handled"
                            } else if trustPoint != nil {
                                "The website requesting this data has been identified and is trusted"
                            } else {
                                "The website requesting this data is unknown so make sure you are comfortable sharing this data with them"
                            }
                            Divider()
                            InfoSection(markdown: infoText)
                        }
                        .scrollTargetLayout()
                    }
                    .scrollPosition($position)
                    .onScrollGeometryChange(for: Bool.self) { geometry in
                        let totalHeight = geometry.contentSize.height + geometry.contentInsets.top + geometry.contentInsets.bottom
                        let currentPosition = geometry.contentOffset.y + geometry.containerSize.height
                        return currentPosition >= totalHeight - 1.0
                    } action: { _, isAtBottom in
                        hasReachedEnd = isAtBottom
                    }
                }
                .padding()
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color.white)
                )
                .padding()
            }

            let buttonText = if (hasReachedEnd) {
                "Share document"
            } else {
                "More"
            }

            Button(buttonText) {
                if (!hasReachedEnd) {
                    withAnimation {
                        position.scrollTo(y: (position.y ?? 0) + 300)
                    }
                } else {
                    onConfirm(credentialPresentmentData.select(preselectedDocuments: []))
                }
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
        }
    }
}
