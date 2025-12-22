/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.trustmanagement

import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509KeyUsage
import kotlin.time.Instant
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.toHex
import kotlin.collections.containsKey
import kotlin.collections.get

/**
 * Object with utility functions for the TrustManager.
 */
internal object TrustManagerUtil {
    /**
     * Check whether a certificate is self-signed
     */
    fun isSelfSigned(certificate: X509Cert): Boolean =
        certificate.issuer == certificate.subject

    /**
     * Check that the key usage is the creation of digital signatures.
     */
    fun checkKeyUsageDocumentSigner(certificate: X509Cert) {
        check(certificate.keyUsage.contains(X509KeyUsage.DIGITAL_SIGNATURE)) {
            "Document Signer certificate is not a signing certificate"
        }
    }

    /**
     * Check the validity period of a certificate (based on the system date).
     */
    fun checkValidity(
        certificate: X509Cert,
        atTime: Instant
    ) {
        // check if the certificate is currently valid
        // NOTE does not check if it is valid within the validity period of
        // the issuing CA
        check(atTime >= certificate.validityNotBefore) {
            "Certificate is not yet valid ($atTime < ${certificate.validityNotBefore}"
        }
        check(atTime <= certificate.validityNotAfter) {
            "Certificate is no longer valid ($atTime > ${certificate.validityNotAfter})"
        }
    }

    /**
     * Check that the key usage is to sign certificates.
     */
    fun checkKeyUsageCaCertificate(caCertificate: X509Cert) {
        check(caCertificate.keyUsage.contains(X509KeyUsage.KEY_CERT_SIGN)) {
            "CA certificate doesn't have the key usage to sign certificates"
        }
    }

    /**
     * Check that the issuer in [certificate] is equal to the subject in
     * [caCertificate].
     */
    fun checkCaIsIssuer(certificate: X509Cert, caCertificate: X509Cert) {
        val issuerName = certificate.issuer.name
        val nameCA = caCertificate.subject.name
        if (issuerName != nameCA) {
            throw IllegalStateException("CA certificate '$nameCA' isn't the issuer of the certificate before it. It should be '$issuerName'")
        }
    }

    /**
     * Verify the signature of the [certificate] with the public key of the
     * [caCertificate].
     */
    suspend fun verifySignature(certificate: X509Cert, caCertificate: X509Cert) =
        try {
            certificate.verify(caCertificate.ecPublicKey)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "Certificate '${certificate.subject}' could not be verified with the public key of CA certificate '${caCertificate.subject}'"
            )
        }

    internal suspend fun verifyX509TrustChain(
        chain: List<X509Cert>,
        atTime: Instant,
        skiToTrustPoint: Map<String, TrustPoint>
    ): TrustResult {
        // TODO: add support for customValidators similar to PKIXCertPathChecker
        try {
            val trustPoints = getAllTrustPointsForX509Cert(chain, skiToTrustPoint)
            val completeChain = chain.plus(trustPoints.map { it.certificate })
            try {
                validateCertificationTrustPath(completeChain, atTime)
                return TrustResult(
                    isTrusted = true,
                    trustPoints = trustPoints,
                    trustChain = X509CertChain(completeChain)
                )
            } catch (e: Throwable) {
                // there are validation errors, but the trust chain could be built.
                return TrustResult(
                    isTrusted = false,
                    trustPoints = trustPoints,
                    trustChain = X509CertChain(completeChain),
                    error = e
                )
            }
        } catch (e: Throwable) {
            // No CA certificate found for the passed in chain.
            //
            // However, handle the case where the passed in chain _is_ a trust point. This won't
            // happen for mdoc issuer auth (the IACA cert is never part of the chain) but can happen
            // with mdoc reader auth, especially at mDL test events where each participant
            // just submits a certificate for the key that their reader will be using.
            //
            if (chain.size == 1) {
                val trustPoint = skiToTrustPoint[chain[0].subjectKeyIdentifier!!.toHex()]
                if (trustPoint != null) {
                    return TrustResult(
                        isTrusted = true,
                        trustChain = X509CertChain(chain),
                        listOf(trustPoint),
                        error = null
                    )
                }
            }
            // no CA certificate could be found.
            return TrustResult(
                isTrusted = false,
                error = e
            )
        }
    }

    private fun getAllTrustPointsForX509Cert(
        chain: List<X509Cert>,
        skiToTrustPoint: Map<String, TrustPoint>
    ): List<TrustPoint> {
        val result = mutableListOf<TrustPoint>()

        // only an exception if not a single CA certificate is found
        var caCertificate: TrustPoint? = findCaCertificate(chain, skiToTrustPoint)
            ?: throw IllegalStateException("No trusted root certificate could not be found")
        result.add(caCertificate!!)
        while (caCertificate != null && !isSelfSigned(caCertificate.certificate)) {
            caCertificate = findCaCertificate(listOf(caCertificate.certificate), skiToTrustPoint)
            if (caCertificate != null) {
                result.add(caCertificate)
            }
        }
        return result
    }

    /**
     * Find a CA Certificate for a certificate chain.
     */
    private fun findCaCertificate(
        chain: List<X509Cert>,
        skiToTrustPoint: Map<String, TrustPoint>
    ): TrustPoint? {
        chain.forEach { cert ->
            cert.authorityKeyIdentifier?.toHex().let {
                if (skiToTrustPoint.containsKey(it)) {
                    return skiToTrustPoint[it]
                }
            }
        }
        return null
    }

    /**
     * Validate the certificate trust path.
     */
    private suspend fun validateCertificationTrustPath(
        certificationTrustPath: List<X509Cert>,
        atTime: Instant
    ) {
        val certIterator = certificationTrustPath.iterator()
        val leafCertificate = certIterator.next()
        checkKeyUsageDocumentSigner(leafCertificate)
        checkValidity(leafCertificate, atTime)

        var previousCertificate = leafCertificate
        var caCertificate: X509Cert? = null
        while (certIterator.hasNext()) {
            caCertificate = certIterator.next()
            checkKeyUsageCaCertificate(caCertificate)
            checkCaIsIssuer(previousCertificate, caCertificate)
            verifySignature(previousCertificate, caCertificate)
            previousCertificate = caCertificate
        }
        if (caCertificate != null && isSelfSigned(caCertificate)) {
            // check the signature of the self signed root certificate
            verifySignature(caCertificate, caCertificate)
        }
    }

}