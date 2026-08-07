package org.multipaz.revocation

/**
 * Represents the state of a document revocation check.
 */
enum class RevocationCheckState {
    /** The credential/identifier is valid and not revoked or suspended. */
    VALID,
    /** The credential/identifier is explicitly revoked. */
    INVALID,
    /** The credential/identifier is currently suspended. */
    SUSPENDED,
    /** The revocation status is unknown, not provided, or could not be verified/downloaded. */
    UNKNOWN
}
