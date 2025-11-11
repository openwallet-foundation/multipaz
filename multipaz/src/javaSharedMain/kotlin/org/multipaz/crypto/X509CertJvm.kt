package org.multipaz.crypto

import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The Java X509 certificate from the encoded certificate data.
 */
val X509Cert.javaX509Certificate: X509Certificate
    get() {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val certBais = ByteArrayInputStream(encoded.toByteArray())
            return cf.generateCertificate(certBais) as X509Certificate
        } catch (e: CertificateException) {
            throw IllegalStateException("Error decoding certificate blob", e)
        }
    }
