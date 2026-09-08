package org.multipaz.revocation

/**
 * Result of checking a document's revocation status.
 *
 * @property state high-level state of the revocation status check.
 * @param isTrusted indicates if revocation data was validated to be trusted (including signature
 *  verification), for non-[RevocationCheckState.UNKNOWN] state this can only be `false` when
 *  `onlyTrusted` [RevocationChecker.check] parameter was set to `false`
 * @property error when non-`null` explains why the check failed (when [state]
 *  is [RevocationCheckState.UNKNOWN]) or stale (for other [state] values).
 */
data class RevocationCheckResult(
    val state: RevocationCheckState,
    val isTrusted: Boolean,
    val error: Throwable? = null
)