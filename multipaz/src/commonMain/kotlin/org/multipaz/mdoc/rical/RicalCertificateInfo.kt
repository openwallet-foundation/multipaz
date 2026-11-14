package org.multipaz.mdoc.rical

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.X509Cert
import kotlin.time.Instant

/**
 * An entry in a RICAL according to ISO/IEC 18013-5 Second Edition Annex F.
 *
 * @property certificate the X.509 CA certificate.
 * @property serialNumber the unsigned big-endian bytes of the certificate's serial number.
 * @property ski the Subject Key Identifier of the X.509 certificate.
 * @property type collision-resistant identifier for the type of the certificate, if available.
 * @property trustConstraints a list of [RicalTrustConstraint], may be empty.
 * @property name a human-readable name of the CA, if available.
 * @property issuingCountry ISO3166-1 or ISO3166-2 country of the CA or `null`.
 * @property stateOrProvinceName state or province name of the CA or `null`.
 * @property issuer DER-encoded X.500 `Issuer` field of the certificate, if included in the RICAL entry.
 * @property subject DER-encoded X.500 `Subject` field of the certificate, if included in the RICAL entry.
 * @property notBefore `notBefore` validity timestamp of the certificate, if included in the RICAL entry.
 * @property notAfter `notAfter` validity timestamp of the certificate, if included in the RICAL entry.
 * @property extensions proprietary extensions, if available.
 */
data class RicalCertificateInfo(
    val certificate: X509Cert,
    val serialNumber: ByteString = certificate.serialNumber.toUnsignedByteString(),
    val ski: ByteString = ByteString(certificate.subjectKeyIdentifier!!),
    val type: String? = null,
    val trustConstraints: List<RicalTrustConstraint> = emptyList(),
    val name: String? = null,
    val issuingCountry: String? = certificate.subject.countryName,
    val stateOrProvinceName: String? = certificate.subject.stateOrProvinceName,
    val issuer: ByteString? = certificate.encodedIssuer,
    val subject: ByteString? = certificate.encodedSubject,
    val notBefore: Instant? = certificate.validityNotBefore,
    val notAfter: Instant? = certificate.validityNotAfter,
    val extensions: Map<String, DataItem> = emptyMap(),
)