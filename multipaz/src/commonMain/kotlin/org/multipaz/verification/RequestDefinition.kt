package org.multipaz.verification

import org.multipaz.cbor.annotation.CborSerializable

/**
 * An object that defines the semantics of a verification request.
 *
 * The following data should be supplied:
 * - the set of documents that would satisfy the request
 * - the set of claims from each document
 * - transaction data for each requested document
 */
@CborSerializable
sealed class RequestDefinition {
    companion object
}