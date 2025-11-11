package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * Converts the certificate chain to a list of Java X.509 certificates.
 */
val X509CertChain.javaX509Certificates: List<X509Certificate>
    get() = certificates.map { certificate -> certificate.javaX509Certificate }

fun X509CertChain.Companion.fromJavaX509Certificates(
    javaX509Certificates: List<X509Certificate>
): X509CertChain {
    val certs = mutableListOf<X509Cert>()
    javaX509Certificates.forEach { certs.add(X509Cert(ByteString(it.encoded))) }
    return X509CertChain(certs)
}

fun X509CertChain.Companion.fromJavaX509Certificates(
    javaX509Certificates: Array<Certificate>
): X509CertChain {
    val certs = mutableListOf<X509Cert>()
    javaX509Certificates.forEach { certs.add(X509Cert(ByteString((it as X509Certificate).encoded))) }
    return X509CertChain(certs)
}
