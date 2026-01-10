package org.multipaz.document

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Instant

/**
 * Storage objects used by [DocumentStore] internally to store a [Document].
 *
 * This class is internal, but it is useful to consult if direct storage manipulation is
 * needed. This class *may* change from one release to another.
 */
@CborSerializable
internal data class DocumentData(
    val provisioned: Boolean,
    val created: Instant,
    val orderingKey: String? = null,
    val displayName: String? = null,
    val typeDisplayName: String? = null,
    val cardArt: ByteString? = null,
    val issuerLogo: ByteString? = null,
    val authorizationData: ByteString? = null,
    val metadata: ByteString? = null  // serialized AbstractDocumentMetadata
) {
    companion object
}