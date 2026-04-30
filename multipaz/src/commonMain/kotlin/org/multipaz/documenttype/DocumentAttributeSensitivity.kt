package org.multipaz.documenttype

/**
 * Attribute sensitivity classification.
 *
 * These values are ordered in terms of sensitivity, ranging from [VALIDITY] being the least sensitive and [PII]
 * being the highest. New values may be added in the future so applications should be careful serializing these
 * values to storage or transmitting them to other applications.
 *
 * These levels should always be considered in context (including whether a presentment is proximity or online), and
 * is not an indication of risk by themselves. For example for presentment of a credential to a website level
 * [PORTRAIT_IMAGE] should be considered risky whereas for proximity presentment it's not.
 */
enum class DocumentAttributeSensitivity {
    /**
     * The attribute conveys information about the validity of the underlying document or credential.
     */
    VALIDITY,

    /**
     * The attribute conveys information about the issuer of the document.
     */
    ISSUER,

    /**
     * The attribute conveys information about the subject's age, e.g. `age_over_21` or `age_in_years`. This does
     * not include e.g. a subject's birthdate since this can reliably identify an individual.
     */
    AGE_INFORMATION,

    /**
     * The holder's portrait image, used to prove that the individual presenting a credential is the authorized holder.
     */
    PORTRAIT_IMAGE,

    // TODO: when we add support for holder-device-binding we want to add a new sensitivity-level for this
    //  data element (which will hold a boolean/enum) which will likely sit between ISSUER and AGE_INFORMATION.

    /**
     * The attribute contains Personally Identifiable Information about the subject, for example name, street address,
     * or birthdate.
     */
    PII,
}