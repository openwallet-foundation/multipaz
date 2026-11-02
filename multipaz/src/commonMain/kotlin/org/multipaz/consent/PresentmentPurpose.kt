package org.multipaz.consent

/**
 * High-level, opinionated purpose classification for a credential presentment use-case.
 */
enum class PresentmentPurpose {
    /**
     * Authorizing or confirming a payment transaction or presenting a payment credential.
     */
    PAYMENT,

    /**
     * Proving the holder's age (e.g. `age_over_18`, `age_over_21`, `age_in_years`, or `age_birth_year`).
     */
    AGE_VERIFICATION,

    /**
     * Proving the holder's driving privileges.
     */
    DRIVING_PRIVILEGES,

    /**
     * Proving the holder's residential address or locality.
     */
    ADDRESS_VERIFICATION,

    /**
     * Verifying the holder's identity (e.g. name, date of birth, document number, nationality).
     */
    IDENTITY_VERIFICATION,

    /**
     * Presenting a loyalty or membership credential.
     */
    LOYALTY_VERIFICATION,

    /**
     * Presenting a ticket or boarding pass credential.
     */
    TICKET_VERIFICATION,
}
