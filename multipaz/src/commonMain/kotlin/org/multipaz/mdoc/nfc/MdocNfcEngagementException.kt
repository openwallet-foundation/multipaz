package org.multipaz.mdoc.nfc

/**
 * Exception thrown or reported when NFC engagement fails before handover completes.
 */
class MdocNfcEngagementException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
