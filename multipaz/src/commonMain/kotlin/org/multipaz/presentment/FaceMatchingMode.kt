package org.multipaz.presentment

/**
 * Mode indicating whether and when face matching should be performed during credential presentment.
 */
enum class FaceMatchingMode {
    /**
     * Never perform any face matching.
     */
    NEVER,

    /**
     * Perform face matching only if requested by the requester via the `CHV_1` data element
     * and the credential returned is properly configured to return the result in a device-signed
     * data element.
     */
    ONLY_IF_REQUESTED,

    /**
     * Always perform face matching, regardless of whether it's requested or if the credential returned
     * is properly configured to return the result in a device-signed data element.
     */
    ALWAYS
}
