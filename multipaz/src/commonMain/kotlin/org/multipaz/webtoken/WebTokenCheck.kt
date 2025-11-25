package org.multipaz.webtoken

/**
 * Defines a specific type of JWT validation.
 *
 * @param webTokenClaim if present, checks that the specific property is of the given value
 */
enum class WebTokenCheck(
    val webTokenClaim: WebTokenClaim<String>? = null
) {
    IDENT,  // value is jti/cti partition name (typically clientId)
    TRUST,  // value is the path where to find trusted key
    CHALLENGE,  // value is challenge jwt property name (current specs use "nonce" or "challenge")
    X5C_CN_ISS_MATCH,  // "required" to check that 'iss' matched cert's subject 'CN'
    NONCE(WebTokenClaim.Nonce),  // direct nonce value check, prefer CHALLENGE
    TYP(WebTokenClaim.Typ),
    AUD(WebTokenClaim.Aud),
    ISS(WebTokenClaim.Iss),
    SUB(WebTokenClaim.Sub),
    HTU(WebTokenClaim.Htu),
    HTM(WebTokenClaim.Htm),
    ATH(WebTokenClaim.Ath),
}