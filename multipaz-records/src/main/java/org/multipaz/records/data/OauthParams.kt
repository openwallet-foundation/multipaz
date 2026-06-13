package org.multipaz.records.data

import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

@CborSerializable
data class OauthParams(
    val scope: String,
    val codeChallenge: ByteString,
    val clientState: String?,
    val redirectUri: String,
    val expiration: Instant,
) {
    companion object Companion
}