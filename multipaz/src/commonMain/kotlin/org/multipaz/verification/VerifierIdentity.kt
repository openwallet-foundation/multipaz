package org.multipaz.verification

import org.multipaz.crypto.AsymmetricKey

/**
 * Class that represents a Verifier identity from the verifier's perspective.
 *
 * Verifier can have multiple identities and use a subset of them to sign verification requests.
 *
 * @property key X509-certified key used to sign verification requests
 * @property clientId the client ID, e.g. `x509_san_dns:verifier.multipaz.org` or `null`; it is only
 *  used in OpenID4VP context.
 */
class VerifierIdentity(
    val key: AsymmetricKey.X509Certified,
    val clientId: String? = null
)