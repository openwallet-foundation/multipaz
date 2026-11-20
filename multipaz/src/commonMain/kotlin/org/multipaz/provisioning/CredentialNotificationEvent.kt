package org.multipaz.provisioning

/**
 * Outcome reported to a credential issuer for a credential issuance flow.
 *
 * [DELETED] is for an issuance that was unsuccessful due to a user action; it does not report
 * deletion of a credential that was previously issued and stored.
 */
enum class CredentialNotificationEvent(val value: String) {
    ACCEPTED("credential_accepted"),
    FAILURE("credential_failure"),
    DELETED("credential_deleted")
}
