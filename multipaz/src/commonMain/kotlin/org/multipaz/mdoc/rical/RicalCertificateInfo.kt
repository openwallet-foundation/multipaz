package org.multipaz.mdoc.rical

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.X509Cert

data class RicalCertificateInfo(
    val certificate: X509Cert,
    val serialNumber: ByteString = ByteString(certificate.serialNumber.value),
    val ski: ByteString = ByteString(certificate.subjectKeyIdentifier!!),
    val type: String? = null,
    // TODO: val trustConstraints:
    val name: String? = null,
    val issuingCountry: String? = null,
    val stateOrProvinceName: String? = null,
    val extensions: Map<String, DataItem>? = null,
)