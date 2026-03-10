package org.multipaz.crypto

import kotlin.time.Instant

/**
 * Exception thrown when X509 certificate chain validation fails.
 *
 * See [X509CertChain.validate]
 */
sealed class X509CertChainValidationException(
    message: String
): Exception("Certificate chain is not valid: $message") {
    /** There is a certificate in the chain which was not signed by the following one */
    class Signature: X509CertChainValidationException(
        message = "signature verification failed",
    )
    /**
     * There is a certificate in the chain which has issuer not matching subject of the
     * following certificate.
     */
    class SubjectIssuerMismatch: X509CertChainValidationException(
        message = "subject does not match issuer"
    )
    /**
     * An expired certificate in the chain (at the validation time).
     *
     * @param time time when certificate expired
     */
    class Expired(time: Instant): X509CertChainValidationException(
        message = "certificate is expired at $time",
    )
    /**
     * An certificate in the chain which is not yet valid (at the validation time).
     *
     * @param time time when certificate will be valid
     */
    class NotYetValid(time: Instant): X509CertChainValidationException(
        message = "certificate is not valid before $time",
    )
    /**
     * An intermediate or root certificate that did not have key usage allowing signing other
     * certificates.
     */
    class KeyUsageMissing: X509CertChainValidationException(
        message = "non-leaf certificate without KEY_CERT_SIGN key usage",
    )
    /**
     * An intermediate or root certificate that did not have basic constraints extension.
     */
    class BasicConstraintsMissing: X509CertChainValidationException(
        message = "non-leaf certificate without basic constraints specified",
    )
    /**
     * An intermediate or root certificate that did not have `CA` set to `true` in its basic
     * constraints extension.
     */
    class BasicConstraintsNotCA: X509CertChainValidationException(
        message = "non-leaf certificate not marked as 'CA' in basic constraints",
    )
    /**
     * An intermediate or root certificate that did not have path length in basic constraints
     * that is long enough for this certificate chain.
     */
    class BasicConstraintsPathLength: X509CertChainValidationException(
        message = "path length exceeded in basic constraints",
    )
}