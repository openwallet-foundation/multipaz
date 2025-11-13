package org.multipaz.provisioning.openid4vci

/**
 * OpenID client authentication required by the server.
 *
 * Client authentication (unless it is `NONE`) tells the issuance server that our wallet/client can
 * be trusted to keep the credentials. Issuance server communicates acceptable forms of
 * client authentication in `.well-known/oauth-authorization-server` metadata file,
 * using `token_endpoint_auth_methods_supported` array.
 */
internal enum class ClientAuthenticationType {
    NONE,  // No client authentication is required by the issuer
    CLIENT_ASSERTION,  // JWT bearer client assertion, see RFC 7523
    CLIENT_ATTESTATION  // Wallet attestation as defined in OpenID4VCI 1.0 Appendix E.
}