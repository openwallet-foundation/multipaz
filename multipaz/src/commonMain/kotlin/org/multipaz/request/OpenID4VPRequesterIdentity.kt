package org.multipaz.request

import org.multipaz.crypto.X509CertChain

/**
 * [RequesterIdentity] in the context of OpenID4VP protocol.
 *
 * @property certChain certificate chain associated with a signature
 * @property clientId OpenID4VP `client_id` parameter; different identities may use different
 *  client ids
 */
data class OpenID4VPRequesterIdentity(
    override val certChain: X509CertChain,
    val clientId: String
): RequesterIdentity() {
    companion object {
        private const val X509_SAN_DNS_PREFIX = "x509_san_dns:"

        /**
         * Creates the identity of a signer of an OpenID4VP request, checking that [clientId] is bound to
         * [certChain] as required by its Client Identifier Prefix.
         *
         * For the `x509_san_dns` prefix, OpenID4VP 1.0 Section 5.9.3 requires the Client Identifier without the
         * prefix to match a `dNSName` subject alternative name of the leaf certificate. Other prefixes are not
         * checked.
         *
         * @param certChain the certificate chain the request was signed with, leaf certificate first.
         * @param clientId the `client_id` of the request.
         * @return the identity.
         * @throws IllegalArgumentException if [clientId] is not bound to [certChain].
         */
        fun fromSignedRequest(certChain: X509CertChain, clientId: String): OpenID4VPRequesterIdentity {
            if (clientId.startsWith(X509_SAN_DNS_PREFIX)) {
                val dnsName = clientId.substring(X509_SAN_DNS_PREFIX.length)
                val leafDnsNames = certChain.certificates.first().subjectAlternativeNameDnsNames
                require(dnsName.isNotEmpty() && leafDnsNames.any { it.equals(dnsName, ignoreCase = true) }) {
                    "client_id '$clientId' does not match a dNSName subject alternative name of the leaf " +
                        "certificate: $leafDnsNames"
                }
            }
            return OpenID4VPRequesterIdentity(certChain, clientId)
        }
    }
}
