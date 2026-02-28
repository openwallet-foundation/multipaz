package org.multipaz.trustmanagement

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509Cert

/**
 * Base class for trust entries.
 *
 * @property identifier a unique identifier for the trust entry.
 * @property metadata a [TrustMetadata] with metadata about the trust entry.
 */
@CborSerializable
sealed class TrustEntry(
    open val identifier: String,
    open val metadata: TrustMetadata,
) {
    companion object
}

/**
 * A X.509 certificate based trust entry.
 *
 * @property certificate the X.509 root certificate for the CA for the trustpoint.
 */
data class TrustEntryX509Cert(
    override val identifier: String,
    override val metadata: TrustMetadata,
    val certificate: X509Cert,
): TrustEntry(identifier, metadata)

/**
 * A VICAL based trust entry.
 *
 * @property encodedSignedVical the bytes of the VICAL.
 */
data class TrustEntryVical(
    override val identifier: String,
    override val metadata: TrustMetadata,
    val encodedSignedVical: ByteString
): TrustEntry(identifier, metadata)

/**
 * A RICAL based trust entry.
 *
 * @property encodedSignedRical the bytes of the RICAL.
 */
data class TrustEntryRical(
    override val identifier: String,
    override val metadata: TrustMetadata,
    val encodedSignedRical: ByteString
): TrustEntry(identifier, metadata)
