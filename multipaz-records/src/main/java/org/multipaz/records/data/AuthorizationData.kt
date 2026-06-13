package org.multipaz.records.data

import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

@CborSerializable
data class AuthorizationData(
    val qualifiedId: String,
    val codeChallenge: ByteString,
    val expiration: Instant
) {
    companion object
}