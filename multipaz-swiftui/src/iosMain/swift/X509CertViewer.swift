import SwiftUI
import Combine
import UIKit

private let oidToPrettyName = [
    OID.commonName.oid: "Common name",
    OID.serialNumber.oid: "Serial number",
    OID.countryName.oid: "Country name",
    OID.localityName.oid: "Locality",
    OID.stateOrProvinceName.oid: "State or province",
    OID.organizationName.oid: "Organization",
    OID.organizationalUnitName.oid: "Organizational unit",
]

/// Shows an X.509 certificate or certificate chain.
///
/// When initialized with a chain containing more than one certificate, a segmented control is
/// displayed at the top allowing the user to switch between the leaf, intermediate, and root
/// certificates.
public struct X509CertViewer: View {
    let certificates: [X509Cert]
    @State private var selectedCertIndex: Int = 0

    /// Creates a viewer for a single ``X509Cert``.
    ///
    /// - Parameter certificate: The ``X509Cert`` to display.
    public init(certificate: X509Cert) {
        self.certificates = [certificate]
    }

    /// Creates a viewer for an ``X509CertChain``.
    ///
    /// - Parameter certChain: The ``X509CertChain`` to display.
    public init(certChain: X509CertChain) {
        self.certificates = certChain.certificates
    }

    /// Creates a viewer for an array of ``X509Cert``s representing a certificate chain.
    ///
    /// - Parameter certificates: The certificates in the chain (from leaf to root).
    public init(certificates: [X509Cert]) {
        self.certificates = certificates
    }

    private func tabLabel(for index: Int, count: Int) -> String {
        if index == 0 {
            return "Leaf"
        } else if index == count - 1 {
            return "Root"
        } else if count == 3 {
            return "Intermediate"
        } else {
            return "Int. \(index)"
        }
    }

    public var body: some View {
        if certificates.isEmpty {
            EmptyView()
        } else {
            let safeIndex = min(max(0, selectedCertIndex), certificates.count - 1)
            let data = CertificateViewData(from: certificates[safeIndex])
            VStack(spacing: 12) {
                if certificates.count > 1 {
                    Picker("Certificate", selection: $selectedCertIndex) {
                        ForEach(0..<certificates.count, id: \.self) { index in
                            Text(tabLabel(for: index, count: certificates.count))
                                .tag(index)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                BasicInfoView(data: data)
                SubjectView(data: data)
                IssuerView(data: data)
                PublicKeyInfoView(data: data)
                ExtensionsView(data: data)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct BasicInfoView: View {
    let data: CertificateViewData

    var body: some View {
        SegmentedItemList(title: "Basic information") {
            SegmentedItemHeadingAndText(
                heading: "Type",
                text: "X.509 version \(data.version) certificate"
            )
            .onTapGesture {
                UIPasteboard.general.string = data.pem
            }
            SegmentedItemHeadingAndText(heading: "Serial number", text: data.serialNumber)
            SegmentedItemHeadingAndText(heading: "Valid from", text: data.validFromFormatted)
            SegmentedItemHeadingAndText(heading: "Valid until", text: data.validUntilFormatted)
            validityLine
        }
    }

    @ViewBuilder
    private var validityLine: some View {
        let now = swiftUiClockForTesting?() ?? Date()

        if now > data.validUntil {
            SegmentedItemHeadingAndText(
                heading: "Validity Info",
                text: AttributedString(
                    "This certificate is no longer valid, it expired \(data.validUntil.relativeTimeText)",
                    attributes: {
                        var container = AttributeContainer()
                        container.foregroundColor = .red
                        return container
                    }()
                )
            )
        } else if data.validFrom > now {
            SegmentedItemHeadingAndText(
                heading: "Validity Info",
                text: AttributedString(
                    "This certificate is not yet valid, it will be valid \(data.validFrom.relativeTimeText)",
                    attributes: {
                        var container = AttributeContainer()
                        container.foregroundColor = .red
                        return container
                    }()
                )
            )
        } else {
            SegmentedItemHeadingAndText(
                heading: "Validity Info",
                text: "Currently valid, expires \(data.validUntil.relativeTimeText)"
            )
        }
    }
}

private struct SubjectView: View {
    let data: CertificateViewData

    var body: some View {
        if !data.subject.components.isEmpty {
            SegmentedItemList(title: "Subject") {
                let oids = Array(data.subject.components.keys)
                ForEach(0..<oids.count, id: \.self) { oidIdx in
                    let componentOid = oids[oidIdx]
                    let componentName = oidToPrettyName[componentOid] ?? "Other name (OID \(componentOid))"
                    let componentValue = data.subject.components[componentOid]!
                    SegmentedItemHeadingAndText(
                        heading: componentName,
                        text: componentValue.value
                    )
                }
            }
        }
    }
}

private struct IssuerView: View {
    let data: CertificateViewData

    var body: some View {
        if !data.issuer.components.isEmpty {
            SegmentedItemList(title: "Issuer") {
                let oids = Array(data.issuer.components.keys)
                ForEach(0..<oids.count, id: \.self) { oidIdx in
                    let componentOid = oids[oidIdx]
                    let componentName = oidToPrettyName[componentOid] ?? "Other name (OID \(componentOid))"
                    let componentValue = data.issuer.components[componentOid]!
                    SegmentedItemHeadingAndText(
                        heading: componentName,
                        text: componentValue.value
                    )
                }
            }
        }
    }
}

private struct PublicKeyInfoView: View {
    let data: CertificateViewData

    var body: some View {
        SegmentedItemList(title: "Public key information") {
            SegmentedItemHeadingAndText(heading: "Algorithm", text: data.pkAlgorithm)
            if let curve = data.pkNamedCurve {
                SegmentedItemHeadingAndText(heading: "Named curve", text: curve)
            }
            SegmentedItemHeadingAndText(heading: "Value", text: data.pkValue)
        }
    }
}

private struct ExtensionsView: View {
    let data: CertificateViewData

    var body: some View {
        if !data.extensions.isEmpty {
            ForEach(Array(data.extensions.enumerated()), id: \.offset) { index, ext in
                SegmentedItemList(title: index == 0 ? "Extensions" : nil) {
                    SegmentedItemHeadingAndText(heading: "Critical", text: ext.isCritical ? "Yes" : "No")
                    SegmentedItemHeadingAndText(heading: "OID", text: ext.oid)
                    SegmentedItemHeadingAndText(heading: "Value", text: ext.value)
                }
            }
        }
    }
}

fileprivate struct CertificateViewData {
    let version: String
    let serialNumber: String
    let validFrom: Date
    let validUntil: Date
    let pem: String
    let subject: X500Name
    let issuer: X500Name
    let pkAlgorithm: String
    let pkNamedCurve: String?
    let pkValue: String
    fileprivate let extensions: [FormattedExtension]

    var validFromFormatted: String {
        var style = Date.FormatStyle(date: .complete, time: .standard)
        if let tz = swiftUiTimeZoneForTesting {
            style.timeZone = tz
        }
        return validFrom.formatted(style)
    }

    var validUntilFormatted: String {
        var style = Date.FormatStyle(date: .complete, time: .standard)
        if let tz = swiftUiTimeZoneForTesting {
            style.timeZone = tz
        }
        return validUntil.formatted(style)
    }

    // Factory method to map from the shared X509Cert type
    init(from cert: X509Cert) {
        // NOTE: In a real app, you would access the properties of `cert` here.
        // Since I cannot see the SharedModule code, I am assuming properties exist.

        self.version = String(cert.version) // Example access
        self.serialNumber = cert.serialNumber.value.unsignedBigIntToString(base: 10)
        self.validFrom = cert.validityNotBefore.toNSDate()
        self.validUntil = cert.validityNotAfter .toNSDate()
        self.pem = cert.toPem()
        self.subject = cert.subject
        self.issuer = cert.issuer
        self.pkAlgorithm = cert.signatureAlgorithm.description_
        self.pkNamedCurve = cert.signatureAlgorithm.curve?.name
        do {
            self.pkValue = try ASN1.shared.encode(
                obj: (ASN1.shared.decode(derEncoded: cert.tbsCertificate) as! ASN1Sequence).elements[6]
            ).toHex(upperCase: false, byteDivider: " ", decodeAsString: false)
        } catch {
            self.pkValue = "Error getting public key: \(error)"
        }
        self.extensions = cert.formatExtensions()
    }
}

// MARK: - Helpers

extension Date {
    /// Rough translation of `durationFromNowText`
    var relativeTimeText: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: self, relativeTo: swiftUiClockForTesting?() ?? Date())
    }
}

private struct FormattedExtension: Equatable {
    let id = UUID()
    let isCritical: Bool
    let oid: String
    let value: String
}

extension X509Cert {

    fileprivate func formatExtensions() -> [FormattedExtension] {

        return self.extensions.map { ext in

            var displayValue: String

            // Note: In Swift, OID constants from KMP usually bridge as static properties.
            // e.g. OID.shared.X509_EXTENSION... or OID.X509_EXTENSION...
            switch ext.oid {

            case OID.x509ExtensionSubjectKeyIdentifier.oid:
                displayValue = self.subjectKeyIdentifier?.toHex(upperCase: false, byteDivider: " ", decodeAsString: false) ?? ""

            case OID.x509ExtensionKeyUsage.oid:
                displayValue = self.keyUsage
                .map { $0.description_ } // Assuming description is available
                .joined(separator: ", ")

            case OID.x509ExtensionBasicConstraints.oid:
                displayValue = parseBasicConstraints(data: ext.data)

            case OID.x509ExtensionAuthorityKeyIdentifier.oid:
                displayValue = self.authorityKeyIdentifier?.toHex(upperCase: false, byteDivider: " ", decodeAsString: false) ?? ""

            case OID.x509ExtensionAndroidKeystoreAttestation.oid:
                // Assuming parser is available in SharedModule
                displayValue = AndroidAttestationExtensionParser(cert: self).prettyPrint()

            case OID.x509ExtensionAndroidKeystoreProvisioningInformation.oid:
                // CBOR Diagnostics
                do {
                    displayValue = Cbor.shared.toDiagnostics(
                        item: try Cbor.shared.decode(encodedCbor: ext.data.toByteArray(startIndex: 0, endIndex: ext.data.size)),
                        options: [.prettyPrint]
                    )
                } catch {
                    displayValue = "Failed to decode CBOR: \(error)"
                }

            case OID.x509ExtensionMultipazExtension.oid:
                displayValue = MultipazExtension.companion.fromCbor(
                    data: ext.data.toByteArray(startIndex: 0, endIndex: ext.data.size)
                ).prettyPrint()

            default:
                // Fallback: Try ASN.1 decode, else Hex
                displayValue = tryParseAsn1OrDefault(data: ext.data)
            }

            // OID Lookup Logic
            let oidLabel: String
            if let entry = OID.companion.lookupByOid(oid: ext.oid) {
                oidLabel = "\(ext.oid) \(entry.description_)"
            } else {
                oidLabel = ext.oid
            }

            return FormattedExtension(
                isCritical: ext.isCritical,
                oid: oidLabel,
                value: displayValue.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        }
    }

    private func parseBasicConstraints(data: ByteString) -> String {
        do {
            guard let seq = try ASN1.shared.decode(derEncoded: data.toByteArray(startIndex: 0, endIndex: data.size)) as? ASN1Sequence else {
                return "Error: Not a sequence"
            }
            var sb = ""
            if let caBool = seq.elements.first as? ASN1Boolean {
                sb += "CA: \(caBool.value)\n"
            }
            if seq.elements.count > 1, let pathLen = seq.elements[1] as? ASN1Integer {
                sb += "pathLenConstraint: \(pathLen.toLong())\n"
            }
            return sb
        } catch {
            return "Error decoding: \(error)"
        }
    }

    private func tryParseAsn1OrDefault(data: ByteString) -> String {
        do {
            let decoded = try ASN1.shared.decode(derEncoded: data.toByteArray(startIndex: 0, endIndex: data.size))
            return ASN1.shared.print(obj: decoded!)
        } catch {
            return data.toHex(upperCase: false, byteDivider: " ", decodeAsString: false)
        }
    }
}
