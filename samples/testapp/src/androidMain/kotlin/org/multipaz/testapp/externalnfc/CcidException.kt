package org.multipaz.testapp.externalnfc

import java.io.IOException

/**
 * Custom exception class for CCID-specific errors.
 * This exception is thrown when an error occurs during communication with the
 * smart card, such as an invalid response or a command failure.
 *
 * @param message A descriptive message for the exception.
 */
class CcidException(message: String) : IOException(message)
