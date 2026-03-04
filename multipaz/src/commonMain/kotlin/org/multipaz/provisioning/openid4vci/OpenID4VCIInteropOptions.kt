package org.multipaz.provisioning.openid4vci

/**
 * Opt-in compatibility switches for non-standard OpenID4VCI server behavior.
 *
 * All flags are disabled by default to keep strict, spec-first behavior.
 */
data class OpenID4VCIInteropOptions(
    /**
     * Treat `token_endpoint_auth_methods_supported=["public"]` as equivalent to `none`.
     *
     * This is non-standard metadata aliasing and should only be enabled for specific servers.
     */
    val allowPublicTokenEndpointAuthMethodAlias: Boolean = false,

    /**
     * Accept HTTP `200 OK` as success for PAR in addition to standard `201 Created`.
     */
    val allowParHttpStatusOk: Boolean = false,

    /**
     * Enable tolerant credential offer parsing for known non-standard input forms
     * (legacy scheme normalization, plain URL coercion, string-wrapped JSON).
     */
    val allowLenientCredentialOfferParsing: Boolean = false,

    /**
     * Enable nonce endpoint fallback sequence `DPoP -> Bearer -> none` plus GET retry for
     * HTML/non-JSON responses.
     */
    val allowNonceEndpointAuthFallback: Boolean = false
)
