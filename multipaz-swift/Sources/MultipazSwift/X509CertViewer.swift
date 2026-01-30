import SwiftUI
import Combine
import Multipaz

private let oidToPrettyName = [
    OID.commonName.oid: "Common Name",
    OID.serialNumber.oid: "Serial Number",
    OID.countryName.oid: "Country",
    OID.localityName.oid: "Locality",
    OID.stateOrProvinceName.oid: "State or province",
    OID.organizationName.oid: "Organization",
    OID.organizationalUnitName.oid: "Organizational unit",
]

public struct X509CertViewer: View {
    let certificate: X509Cert
    private let data: CertificateViewData

    public init(certificate: X509Cert) {
        self.certificate = certificate
        self.data = CertificateViewData(from: certificate)
    }
    
    public var body: some View {
        VStack(spacing: 16) {
            BasicInfoView(data: data)
            SubjectView(data: data)
            IssuerView(data: data)
            PublicKeyInfoView(data: data)
            ExtensionsView(data: data)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
    }
}

private struct BasicInfoView: View {
    let data: CertificateViewData
    @State private var isCopied = false

    var body: some View {
        RenderSection(title: "Basic Information") {
            VStack(spacing: 2) {
                KeyValuePairLine(key: "Type", value: "Version \(data.version)")
                Divider()
                KeyValuePairLine(key: "Serial Number", value: data.serialNumber)
                Divider()
                KeyValuePairLine(key: "Valid From", value: data.validFromFormatted)
                Divider()
                KeyValuePairLine(key: "Valid Until", value: data.validUntilFormatted)
                Divider()
                validityLine
            }
        }
    }

    @ViewBuilder
    private var validityLine: some View {
        let now = Date()
        
        if now > data.validUntil {
            KeyValuePairLine(
                key: "Validity Info",
                value: "This certificate is no longer valid, it expired \(data.validUntil.relativeTimeText)",
                valueColor: .red
            )
        } else if data.validFrom > now {
            KeyValuePairLine(
                key: "Validity Info",
                value: "This certificate is not yet valid, it will be valid \(data.validFrom.relativeTimeText)",
                valueColor: .red
            )
        } else {
            KeyValuePairLine(
                key: "Validity Info",
                value: "Valid (Expires \(data.validUntil.relativeTimeText))"
            )
        }
    }
}

private struct SubjectView: View {
    let data: CertificateViewData
    
    var body: some View {
        RenderSection(title: "Subject") {
            VStack(spacing: 2) {
                let oids = Array(data.subject.components.keys)
                ForEach(0..<oids.count, id: \.self) { oidIdx in
                    let componentOid = oids[oidIdx]
                    let componentName = oidToPrettyName[componentOid] ?? componentOid
                    let componentValue = data.subject.components[componentOid]!
                    KeyValuePairLine(
                        key: componentName,
                        value: componentValue.value
                    )
                    if oidIdx < oids.count - 1 {
                        Divider()
                    }
                }
            }
        }
    }
}

private struct IssuerView: View {
    let data: CertificateViewData
    
    var body: some View {
        RenderSection(title: "Issuer") {
            VStack(spacing: 2) {
                let oids = Array(data.issuer.components.keys)
                ForEach(0..<oids.count, id: \.self) { oidIdx in
                    let componentOid = oids[oidIdx]
                    let componentName = oidToPrettyName[componentOid] ?? componentOid
                    let componentValue = data.issuer.components[componentOid]!
                    KeyValuePairLine(
                        key: componentName,
                        value: componentValue.value
                    )
                    if oidIdx < oids.count - 1 {
                        Divider()
                    }
                }
            }
        }
    }
}

private struct PublicKeyInfoView: View {
    let data: CertificateViewData
    
    var body: some View {
        RenderSection(title: "Public Key Info") {
            VStack(spacing: 2) {
                KeyValuePairLine(key: "Algorithm", value: data.pkAlgorithm)
                if let curve = data.pkNamedCurve {
                    Divider()
                    KeyValuePairLine(key: "Named Curve", value: curve)
                }
                Divider()
                KeyValuePairLine(key: "Key Value", value: data.pkValue)
            }
        }
    }
}

private struct ExtensionsView: View {
    let data: CertificateViewData
    
    var body: some View {
        if !data.extensions.isEmpty {
            RenderSection(title: "Extensions") {
                VStack(spacing: 2) {
                    ForEach(data.extensions, id: \.oid) { ext in
                        VStack(spacing: 2) {
                            KeyValuePairLine(key: "Critical", value: ext.isCritical ? "Yes" : "No")
                            KeyValuePairLine(key: "OID", value: ext.oid)
                            KeyValuePairLine(key: "Value", value: ext.value)
                        }
                        if ext != data.extensions.last {
                            Divider()
                        }
                    }
                }
            }
        }
    }
}

struct RenderSection<Content: View>: View {
    let title: String
    let content: Content

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .center, spacing: 8) {
            HStack {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.bold)
                    .foregroundColor(.secondary)
                    .padding(.vertical, 4)
                Spacer()
            }

            content
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemGroupedBackground))
                    .shadow(color: Color.black.opacity(0.2), radius: 8, x: 0, y: 4)
            )
        }
        .frame(maxWidth: .infinity)
    }
}

struct KeyValuePairLine: View {
    let key: String
    let value: String
    var valueColor: Color = .primary

    var body: some View {
        if !value.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(key)
                    .font(.system(size: 14))
                    .fontWeight(.bold)
                    .foregroundColor(.primary)
                
                Text(value)
                    .font(.system(size: 14))
                    .foregroundColor(valueColor)
                    .textSelection(.enabled) // Allows user to copy text
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(8)
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
        validFrom.formatted(date: .complete, time: .standard)
    }

    var validUntilFormatted: String {
        validUntil.formatted(date: .complete, time: .standard)
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
        return formatter.localizedString(for: self, relativeTo: Date())
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
