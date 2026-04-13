package org.multipaz.openid4vci.util

import kotlin.time.Instant
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Authentication information to access System of Record.
 */
@CborSerializable
data class SystemOfRecordAccess(
    val accessToken: String,
    val accessTokenExpiration: Instant,
    val refreshToken: String?
) {
    companion object
}