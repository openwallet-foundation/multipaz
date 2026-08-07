package org.multipaz.revocation

import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Instant


/**
 * Abstract class that represents some kind of revocation data
 *
 * @property [creationTime] time when this object was created
 * @property [expirationTime] time when this object expires and should be refreshed
 */
@CborSerializable
sealed class RevocationData {
    abstract val creationTime: Instant
    abstract val expirationTime: Instant

    companion object
}