package org.multipaz.digitalcredentials

/**
 * An enumeration to convey whether the application is authorized to use the [DigitalCredentials] API.
 */
enum class DigitalCredentialsAuthorizationState {
    /** The user has not yet made a choice of whether to authorized the application. */
    NOT_DETERMINED,

    /** The user has authorized the application. */
    AUTHORIZED,

    /** The application is not authorized and manual intervention by the user is required. */
    NOT_AUTHORIZED,

    /** It is not known if the application is authorized. */
    UNKNOWN
}
