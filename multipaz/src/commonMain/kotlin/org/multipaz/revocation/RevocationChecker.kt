package org.multipaz.revocation

import kotlinx.coroutines.CancellationException
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509Cert
import org.multipaz.trustmanagement.TrustManagerInterface
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Interface for checking and managing the revocation status of digital credentials.
 *
 * Handles downloading, caching, and verifying ISO/IEC 18013-5 or IETF SD-JWT revocation payloads
 * such as status lists and identifier lists.
 */
interface RevocationChecker {
    /**
     * Checks the revocation state for a given non-null [RevocationStatus] payload.
     *
     * This method **never throws** exceptions for non-cancellation errors (such as network, HTTP,
     * timeout, parsing, or signature verification failures). Instead, those errors are caught
     * internally and returned as a [RevocationCheckResult] with state
     * [RevocationCheckState.UNKNOWN] and the underlying exception in [RevocationCheckResult.error].
     * Standard coroutine [CancellationException]s are preserved and rethrown.
     *
     * @param revocationStatus The revocation status object (StatusList or IdentifierList) extracted
     *  from the presentation.
     * @param issuerCert The top-level certificate chain of the issuer / document signer (e.g. AICA
     *  certificate for ISO mdoc credentials); it is used for signature verification unless it is
     *  included in the [revocationStatus] (uncommon); if this is `null`, and certificate in the
     *  [revocationStatus] is `null`, [onlyTrusted] should be false, or the check will always
     *  return [RevocationCheckState.UNKNOWN] in [RevocationCheckResult.state]
     * @param onlyTrusted Only accept trusted revocation data (valid and correctly signed)
     * @param atTime The point in time at which to evaluate revocation status validity. Defaults
     *  to current system time.
     * @param bypassCache If true, forces downloading a fresh status/identifier list payload from
     *  the network rather than using cached data.
     * @param preferJwt Prefer status list data in JWT format, rather than more compact CWT; mostly
     *  exposed for testing
     * @return [RevocationCheckResult] indicating whether the credential is valid, revoked,
     *  suspended, or unknown.
     */
    suspend fun check(
        revocationStatus: RevocationStatus,
        issuerCert: X509Cert? = null,
        onlyTrusted: Boolean = true,
        atTime: Instant = Clock.System.now(),
        bypassCache: Boolean = false,
        preferJwt: Boolean = false
    ): RevocationCheckResult

    /**
     * Clears all cached revocation status and identifier list entries from storage.
     */
    suspend fun clearCache()
}

/**
 * Convenience extension to check the revocation state for a given [Credential].
 *
 * @see RevocationChecker.check for details.
 *
 * @param credential credential for which the revocation state should .
 * @param trustManager trust manager to find the root certificate for the credential issuer.
 * @param onlyTrusted only use revocation data which could be validated (including signature check)
 * @param atTime The point in time at which to evaluate revocation status validity. Defaults
 *  to current system time.
 * @param bypassCache If true, forces downloading a fresh status/identifier list payload from
 *  the network rather than using cached data.
 * @param preferJwt Prefer status list data in JWT format, rather than more compact CWT; mostly
 *  exposed for testing
 * @return [RevocationCheckResult] indicating whether the credential is valid, revoked,
 *  suspended, or unknown.
 */
suspend fun RevocationChecker.check(
    credential: Credential,
    trustManager: TrustManagerInterface,
    onlyTrusted: Boolean = true,
    atTime: Instant = Clock.System.now(),
    bypassCache: Boolean = false,
    preferJwt: Boolean = false
): RevocationCheckResult = credential.getRevocationInfo(trustManager)?.let {
    check(it.revocationStatus, it.certificate, onlyTrusted, atTime, bypassCache, preferJwt)
} ?: RevocationCheckResult(
    state = RevocationCheckState.UNKNOWN,
    isTrusted = false,
    error = IllegalStateException("No revocation data in the credential")
)
