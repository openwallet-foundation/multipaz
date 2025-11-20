package org.multipaz.openid4vci.credential

import kotlin.time.Instant

/**
 * Freshly-created credential along with its creation and expiration time.
 */
data class MintedCredential(
    val credential: String,
    val creation: Instant,
    val expiration: Instant
)