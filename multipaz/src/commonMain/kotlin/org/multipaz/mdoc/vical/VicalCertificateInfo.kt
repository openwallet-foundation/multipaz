package org.multipaz.mdoc.vical

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.X509Cert

/**
 * An entry in a VICAL according to ISO/IEC 18013-5:2021.
 *
 * @property certificate the X.509 certificate.
 * @property docTypes a list of document types for which the certificate may be used as a trust point, contains at
 *   least one element.
 * @property ski the Subject Key Identifier of the X.509 certificate.
 * @property certificateProfiles list of Uniform Resource Name (URN) according to RFC 8141, if available.
 * @property issuingAuthority name of Issuing Authority or `null`.
 * @property issuingCountry  ISO3166-1 or ISO3166-2 depending on the issuing authority or `null`.
 * @property stateOrProvinceName State or province name of the certificate issuing authority
 * @property extensions proprietary extensions. Added in 18013-5 Second Edition.
 */
data class VicalCertificateInfo(
    val certificate: X509Cert,
    val docTypes: List<String>,
    val ski: ByteString = ByteString(certificate.subjectKeyIdentifier!!),
    val certificateProfiles: List<String>? = null,
    val issuingAuthority: String? = null,
    val issuingCountry: String? = null,
    val stateOrProvinceName: String? = null,
    val extensions: Map<String, DataItem> = emptyMap()
) {
    init {
        require(docTypes.isNotEmpty())
    }
}