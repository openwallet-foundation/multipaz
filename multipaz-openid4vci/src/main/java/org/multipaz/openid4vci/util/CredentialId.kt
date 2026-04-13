package org.multipaz.openid4vci.util

import org.multipaz.cbor.annotation.CborSerializable

/**
 * A credential is identified by its [bucket] and [index].
 *
 * All credentials in the same bucket are placed in a single revocation list. They are all of the
 * same format (ISO mdoc vs SD-JWT) and are all signed by the same private key.
 *
 * @param bucket bucket id is a short string that identifies credential format and signing key
 * @param index a small integer that identifies the specific credential in the bucket
 */
@CborSerializable
data class CredentialId(
    val bucket: String,
    val index: Int
) {
    companion object
}