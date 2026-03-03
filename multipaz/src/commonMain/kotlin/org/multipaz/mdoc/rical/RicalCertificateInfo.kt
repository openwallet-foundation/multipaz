package org.multipaz.mdoc.rical

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.X509Cert

/**
 * An entry in a RICAL according to ISO/IEC 18013-5 Second Edition Annex F.
 *
 * @property certificate the X.509 certificate.
 * @property serialNumber the serial number of the certificate.
 * @property isTrustAnchor A boolean value indicating whether the CA certificate is intended to be used
 *   as a trust anchor. If set to true, the certificate shall be treated as a trust anchor during certificate path
 *   validation. If set to false, the certificate shall not be used as a trust anchor.
 * @property ski the Subject Key Identifier of the X.509 certificate.
 * @property type the type of the certificate, if available.
 * @property trustConstraints a list of [RicalTrustConstraint], may be empty.
 * @property name a human-readable name, if available.
 * @property issuingCountry  ISO3166-1 or ISO3166-2 depending on the issuing authority or `null`.
 * @property stateOrProvinceName State or province name of the certificate issuing authority
 * @property extensions proprietary extensions, if available.
 */
data class RicalCertificateInfo(
    val certificate: X509Cert,
    val isTrustAnchor: Boolean = true,
    val serialNumber: ByteString = ByteString(certificate.serialNumber.value),
    val ski: ByteString = ByteString(certificate.subjectKeyIdentifier!!),
    val type: String? = null,
    val trustConstraints: List<RicalTrustConstraint> = emptyList(),
    val name: String? = null,
    val issuingCountry: String? = null,
    val stateOrProvinceName: String? = null,
    val extensions: Map<String, DataItem> = emptyMap(),
)