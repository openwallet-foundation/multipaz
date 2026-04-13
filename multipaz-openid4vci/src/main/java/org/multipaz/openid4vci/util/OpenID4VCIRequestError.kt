package org.multipaz.openid4vci.util

import java.lang.Exception

/**
 * Represents an error as it commonly formatted in OpenID specs: error code and description.
 *
 * If thrown from a request handler, will be passed to the client in JSON format.
 */
class OpenID4VCIRequestError(
    val code: String,
    val description: String
): Exception("Error: $code, $description")