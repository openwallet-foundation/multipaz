package org.multipaz.mdoc.vical

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.X509Cert
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Instant

private const val TAG = "VicalCertificateInfo"

/**
 * An entry in a VICAL according to ISO/IEC 18013-5 Annex C.
 *
 * @property certificate the X.509 certificate.
 * @property docTypes a list of document types for which the certificate may be used as a trust point, contains at
 *   least one element.
 * @property serialNumber the unsigned big-endian bytes of the certificate's serial number.
 * @property ski the Subject Key Identifier of the X.509 certificate.
 * @property certificateProfiles list of Uniform Resource Name (URN) according to RFC 8141, if available.
 * @property issuingAuthority name of Issuing Authority or `null`.
 * @property issuingCountry ISO3166-1 or ISO3166-2 depending on the issuing authority or `null`.
 * @property stateOrProvinceName state or province name of the certificate issuing authority or `null`.
 * @property issuer DER-encoded X.500 `Issuer` field of the certificate, if included in the VICAL entry.
 * @property subject DER-encoded X.500 `Subject` field of the certificate, if included in the VICAL entry.
 * @property notBefore `notBefore` validity timestamp of the certificate, if included in the VICAL entry.
 * @property notAfter `notAfter` validity timestamp of the certificate, if included in the VICAL entry.
 * @property extensions proprietary extensions. Added in 18013-5 Second Edition.
 */
data class VicalCertificateInfo(
    val certificate: X509Cert,
    val docTypes: List<String>,
    val serialNumber: ByteString = certificate.serialNumber.toUnsignedByteString(),
    val ski: ByteString = ByteString(certificate.subjectKeyIdentifier!!),
    val certificateProfiles: List<String>? = null,
    val issuingAuthority: String? = null,
    val issuingCountry: String? = certificate.subject.countryName,
    val stateOrProvinceName: String? = certificate.subject.stateOrProvinceName,
    val issuer: ByteString? = certificate.encodedIssuer,
    val subject: ByteString? = certificate.encodedSubject,
    val notBefore: Instant? = certificate.validityNotBefore,
    val notAfter: Instant? = certificate.validityNotAfter,
    val extensions: Map<String, DataItem> = emptyMap()
) {
    init {
        if (docTypes.isEmpty()) {
            Logger.w(TAG, "Ignoring empty docTypes for certificate with ski ${ski.toHex()}")
        }
        require(certificateProfiles == null || certificateProfiles.isNotEmpty()) {
            "certificateProfiles cannot be empty when present"
        }
    }
}