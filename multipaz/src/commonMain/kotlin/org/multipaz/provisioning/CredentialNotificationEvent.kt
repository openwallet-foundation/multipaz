package org.multipaz.provisioning

/**
 * Outcome reported to a credential issuer for a credential issuance flow.
 *
 * [DELETED] is for an issuance that was unsuccessful due to a user action; it does not report
 * deletion of a credential that was previously issued and stored.
 */
enum class CredentialNotificationEvent {
    /** Credentials were successfully stored. */
    ACCEPTED,

    /** Issuance failed for a reason other than user action. */
    FAILURE,

    /** Issuance was unsuccessful due to user action. */
    DELETED
}
