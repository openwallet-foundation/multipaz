package org.multipaz.document

import kotlinx.io.bytestring.ByteString
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.storage.Storage

/**
 * Interface for application metadata for a [Document].
 *
 * Objects returned in [Document.metadata] must implement this interface.
 *
 * The way application-level metadata works changed in a significant way in the release 0.97.0.
 * If you first started to use this interface before release, please read the following information
 * carefully.
 *
 * Starting in 0.97.0 the data stored in this object is purely for application use, Multipaz library
 * itself only maintains this object, but does not use it. Fields that existed in 0.96.0 and earlier
 * except for `other`, have been moved to the [Document] object itself; the `other` field now
 * corresponds to this object.
 *
 * If the data table that used for persistent storage [DocumentStore] ([Document.defaultTableSpec]
 * by default) contains data compatible with 0.96.0, it must be migrated going to 0.97.0+. Multipaz
 * provides some migration support in two important cases but only if [DocumentStore] uses
 * the default table. You you use a custom table, you'll need to do migration yourself.
 *
 * Here are the supported migration paths:
 *  - If you used `DocumentMetadata` object directly or through delegation in 0.96.0 or before,
 *   Multipaz will update the table data automatically. The data in `other` field in
 *   `DocumentMetadata` will be passed to the factory function that you set in
 *   [DocumentStore.Builder.setDocumentMetadataFactory], if `other` was not null, this function must
 *   be provided.
 *  - If you had your own serialization for [AbstractDocumentMetadata], and that serialization is
 *   not CBOR map with `provisioned` field, that serialization will be passed to the factory
 *   function. It is your application's responsibility to update fields in the [Document] object
 *   after the migration (since Multipaz migration code cannot parse your custom serialization).
 *  - In all other cases, you have to create fully custom migration implementation. You must
 *   set [Document.customSchema0_97_0_MigrationFn] before creating the [DocumentStore]. This migration
 *   function will be invoked for each document only when the migration is needed; old serialized
 *   data will be passed in, this function must return new serialized data structured as serialized
 *   CBOR map with the fields as defined in `org.multipaz.document.DocumentData` as it is defined
 *   in the release 0.97.0 (this object may change in the future releases, but migration to from
 *   0.97.0 to the future release will be handled by Multipaz).
 *
 *  In all cases, make sure to test your migration well.
 */
interface AbstractDocumentMetadata {
    fun serialize(): ByteString

    /**
     * Delete this metadata, called when the document to which it belongs is going away.
     *
     * In particular, data that resides in the storage and the secure area should be deleted.
     *
     * @param secureAreaRepository secure area repository to look up an appropriate [SecureArea]
     * @param storage interface to the storage
     */
    suspend fun cleanup(
        secureAreaRepository: SecureAreaRepository,
        storage: Storage
    ) {}
}
