package org.multipaz.crypto

import org.multipaz.asn1.ASN1Integer

internal fun stripLeadingZero(bytes: ByteArray): ByteArray {
    return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
        bytes.copyOfRange(1, bytes.size)
    } else {
        bytes
    }
}

internal fun toPositiveAsn1Integer(bytes: ByteArray): ASN1Integer {
    val stripped = stripLeadingZero(bytes)
    return if (stripped.isNotEmpty() && (stripped[0].toInt() and 0x80) != 0) {
        ASN1Integer(byteArrayOf(0x00) + stripped)
    } else {
        ASN1Integer(stripped)
    }
}
