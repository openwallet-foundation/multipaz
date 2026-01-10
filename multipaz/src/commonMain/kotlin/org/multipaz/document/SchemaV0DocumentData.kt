package org.multipaz.document

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Data stored in the document table (see [Document.defaultTableSpec]) for schema version 0
 * (up until Multipaz release 0.96.0) when `DocumentMetadata` class was used.
 */
@CborSerializable
internal data class SchemaV0DocumentData(
    val provisioned: Boolean = false,
    val displayName: String? = null,
    val typeDisplayName: String? = null,
    val cardArt: ByteString? = null,
    val issuerLogo: ByteString? = null,
    val authorizationData: ByteString? = null,
    val other: ByteString? = null  // serialized AbstractDocumentMetadata
) {
    companion object
}