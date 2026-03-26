/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.document

import org.multipaz.credential.CredentialLoader
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.credential.Credential
import org.multipaz.credential.CredentialLoaderBuilder
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mpzpass.MpzPass
import org.multipaz.provisioning.Provisioning
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.NoRecordStorageException
import org.multipaz.tags.Tags
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Class for storing real-world identity documents.
 *
 * This class is designed for storing real-world identity documents such as
 * Mobile Driving Licenses (mDL) as specified in ISO/IEC 18013-5:2021. It is however
 * not tied to that specific document format and is designed to hold any kind of
 * document, regardless of format, presentation-, or issuance-protocol used.
 *
 * This code relies on a Secure Area for keys and this dependency is abstracted
 * by the [SecureArea] interface and allows the use of different [SecureArea]
 * implementations for *Authentication Keys*) associated with documents stored
 * in the Document Store.
 *
 * It is guaranteed that once a document is created with [createDocument],
 * each subsequent call to [lookupDocument] will return the same
 * [Document] instance.
 *
 * For more details about documents stored in a [DocumentStore] see the
 * [Document] class.
 *
 * Use [buildDocumentStore] or [DocumentStore.Builder] to create a [DocumentStore] instance.
 *
 * @property storage the [Storage] to use for storing/retrieving documents.
 * @property secureAreaRepository the repository of configured [SecureArea] that can be used.
 */
class DocumentStore private constructor(
    val storage: Storage,
    val secureAreaRepository: SecureAreaRepository,
    internal val credentialLoader: CredentialLoader,
    internal val documentMetadataFactory: (suspend (
        documentId: String,
        data: ByteString,
    ) -> AbstractDocumentMetadata)?,
    private val documentTableSpec: StorageTableSpec = Document.defaultTableSpec,
) {
    // Use a cache so the same instance is returned by multiple lookupDocument() calls.
    // Cache is protected by the lock. Once the document is loaded it is never evicted.
    private val lock = Mutex()
    private val documentCache = mutableMapOf<String, Document>()

    init {
        check(!documentTableSpec.supportExpiration)
        check(!documentTableSpec.supportPartitions)
    }

    private var tags: Tags? = null

    /**
     * Gets a [Tags] which can be used to storing application-specific data.
     *
     * Applications must use collision-resistant keys when using the [Tags] instance.
     *
     * @return a [Tags] instance.
     */
    suspend fun getTags(): Tags {
        lock.withLock {
            if (tags != null) {
                return tags!!
            }
            val table = storage.getTable(documentStoreTableSpec)
            val encodedTags = table.get(TAGS_KEY)
            tags = Tags(
                data = encodedTags?.toByteArray()?.let { Cbor.decode(it) },
                saveFn = { newData ->
                    try {
                        table.update(TAGS_KEY, ByteString(Cbor.encode(newData)))
                    } catch (_: NoRecordStorageException) {
                        table.insert(TAGS_KEY, ByteString(Cbor.encode(newData)))
                    }
                    null
                }
            )
            return tags!!
        }
    }

    /**
     * Creates a new document.
     *
     * @param displayName User-facing name of this specific [Document] instance, e.g.
     *  "John's Passport", or `null`.
     * @param typeDisplayName User-facing name of this document type, e.g. "Utopia Passport",
     *  or `null`.
     * @param cardArt An image that represents this document to the user in the UI. Generally,
     *  the aspect ratio of 1.586 is expected (based on ID-1 from the ISO/IEC 7810). PNG format
     *  is expected and transparency is supported.
     * @param issuerLogo An image that represents the issuer of the document in the UI,
     *  e.g. passport office logo. PNG format is expected, transparency is supported and square
     *  aspect ratio is preferred.
     * @param metadata initial value for [Document.metadata]
     * @return A newly created document.
     */
    suspend fun createDocument(
        displayName: String? = null,
        typeDisplayName: String? = null,
        cardArt: ByteString? = null,
        issuerLogo: ByteString? = null,
        authorizationData: ByteString? = null,
        created: Instant = Clock.System.now(),
        metadata: AbstractDocumentMetadata? = null
    ): Document {
        val table = storage.getTable(documentTableSpec)
        val data = DocumentData(
            provisioned = false,
            created = created,
            displayName = displayName,
            typeDisplayName = typeDisplayName,
            cardArt = cardArt,
            issuerLogo = issuerLogo,
            authorizationData = authorizationData,
            metadata = metadata?.serialize()
        )
        // NB: insertion in the storage is when the document is actually added, it may be
        // inserted in the cache before we manage to call lock.withLock below
        val documentIdentifier = table.insert(key = null, ByteString(data.toCbor()))
        emitOnDocumentAdded(documentIdentifier)
        val document = lock.withLock {
            documentCache.getOrPut(documentIdentifier) {
                Document(this, documentIdentifier, data, metadata)
            }
        }
        return document
    }

    /**
     * Looks up a document in the store.
     *
     * @param identifier the identifier of the document.
     * @return the document or `null` if not found.
     */
    suspend fun lookupDocument(identifier: String): Document? {
        return lock.withLock {
            documentCache.getOrPut(identifier) {
                val table = getDocumentTable()
                val blob = table.get(identifier) ?: return@withLock null
                val data = DocumentData.fromCbor(blob.toByteArray())
                val metadata = data.metadata?.let {
                    documentMetadataFactory!!(identifier, it)
                }
                val document = Document(this, identifier, data, metadata)
                document
            }
        }
    }

    /**
     * Lists all document ids in the store.
     *
     * Ids are returned sorted *as strings*, not in the document sorting order.
     *
     * @return list of all the document identifiers in the store.
     */
    suspend fun listDocumentIds(): List<String> {
        // right now lock is not required
        return storage.getTable(documentTableSpec).enumerate()
    }

    /**
     * Lists all documents in the store.
     *
     * @param sort if true, the returned list is sorted using [Document.Comparator].
     * @return list of all the documents in the store.
     */
    suspend fun listDocuments(sort: Boolean = true): List<Document> {
        // right now lock is not required
        val list = storage.getTable(documentTableSpec).enumerate().mapNotNull {
            lookupDocument(it)
        }
        return if (sort) {
            list.sortedWith(Document.Comparator)
        } else {
            list
        }
    }

    /**
     * Deletes a document.
     *
     * If the document doesn't exist this does nothing.
     *
     * @param identifier the identifier of the document.
     */
    suspend fun deleteDocument(identifier: String) {
        lookupDocument(identifier)?.let { document ->
            emitOnDocumentDeleted(identifier)
            lock.withLock {
                document.deleteDocument()
                documentCache.remove(identifier)
            }
            document.authorizationData?.let {
                Provisioning.cleanupAuthorizationData(
                    authorizationData = it,
                    secureAreaRepository = secureAreaRepository,
                    storage = storage
                )
            }
            document.metadata?.cleanup(secureAreaRepository, storage)
        }
    }

    private val _eventFlow = MutableSharedFlow<DocumentEvent>()

    /**
     * A [SharedFlow] which can be used to listen for when credentials are added and removed
     * from the store as well as when credentials in the store have been updated.
     */
    val eventFlow
        get() = _eventFlow.asSharedFlow()


    private suspend fun emitOnDocumentAdded(documentId: String) {
        _eventFlow.emit(DocumentAdded(documentId))
    }

    internal suspend fun emitOnDocumentDeleted(documentId: String) {
        _eventFlow.emit(DocumentDeleted(documentId))
    }

    internal suspend fun emitOnDocumentChanged(documentId: String) {
        _eventFlow.emit(DocumentUpdated(documentId))
    }

    internal suspend fun getDocumentTable(): StorageTable {
        return storage.getTable(documentTableSpec)
    }

    /**
     * Imports a [MpzPass] into a [DocumentStore].
     *
     * The returned document will have the [Document.provisioned] flag set to `true` and [Document.mpzPassId]
     * will be set to [MpzPass.uniqueId].
     *
     * If the pass had been previously imported, the same [Document] will be returned and the credentials
     * will be updated.
     *
     * @param mpzPass The [MpzPass] to import.
     * @param isoMdocDomain The domain string to use when creating ISO mdoc credentials.
     * @param sdJwtVcDomain The domain string to use when creating SD-JWT VC credentials.
     * @param keylessSdJwtVcDomain the domain string to use when creating keyless SD-JWT VC credentials.
     * @return An existing [Document] if updating, otherwise a newly created [Document]. In both cases
     * the returned document will have the credentials included in [mpzPass].
     * @throws IllegalStateException if a SoftwareSecureArea implementation cannot be found in the repository.
     * @throws ImportMpzPassException if credential creation or certification fails.
     */
    @Throws(IllegalStateException::class, ImportMpzPassException::class, CancellationException::class)
    suspend fun importMpzPass(
        mpzPass: MpzPass,
        isoMdocDomain: String = "mdoc",
        sdJwtVcDomain: String = "sdjwtvc",
        keylessSdJwtVcDomain: String = "sdjwtvc_keyless"
    ): Document {
        val softwareSecureArea = secureAreaRepository.getImplementation(SoftwareSecureArea.IDENTIFIER)
            ?: throw IllegalStateException(
                "No SoftwareSecureArea implementation found"
            )

        val document = try {
            val existingDocument = listDocuments().find { it.mpzPassId == mpzPass.uniqueId }
            if (existingDocument != null) {
                existingDocument.getCredentials().forEach { credential ->
                    credential.deleteCredential()
                }
                existingDocument
            } else {
                createDocument(
                    displayName = mpzPass.name,
                    typeDisplayName = mpzPass.typeName,
                    cardArt = mpzPass.cardArt,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw ImportMpzPassException("Failed to create document", e)
        }

        try {
            mpzPass.isoMdoc.forEach { isoMdoc ->
                val importedKeyInfo = softwareSecureArea.createKey(
                    alias = null,
                    createKeySettings = SoftwareCreateKeySettings.Builder()
                        .setPrivateKey(isoMdoc.deviceKeyPrivate)
                        .build()
                )
                val credential = MdocCredential.createForExistingAlias(
                    document = document,
                    asReplacementForIdentifier = null,
                    domain = isoMdocDomain,
                    secureArea = softwareSecureArea,
                    docType = isoMdoc.docType,
                    existingKeyAlias = importedKeyInfo.alias,
                )
                credential.certify(
                    issuerProvidedAuthenticationData = ByteString(
                        Cbor.encode(buildCborMap {
                            put("nameSpaces", isoMdoc.issuerNamespaces.toDataItem())
                            put("issuerAuth", isoMdoc.issuerAuth.toDataItem())
                        })
                    )
                )
            }

            mpzPass.sdJwtVc.forEach { sdJwtVc ->
                val credential = if (sdJwtVc.deviceKeyPrivate != null) {
                    val importedKeyInfo = softwareSecureArea.createKey(
                        alias = null,
                        createKeySettings = SoftwareCreateKeySettings.Builder()
                            .setPrivateKey(sdJwtVc.deviceKeyPrivate)
                            .build()
                    )
                    KeyBoundSdJwtVcCredential.createForExistingAlias(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = sdJwtVcDomain,
                        secureArea = softwareSecureArea,
                        vct = sdJwtVc.vct,
                        existingKeyAlias = importedKeyInfo.alias,
                    )
                } else {
                    KeylessSdJwtVcCredential.create(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = keylessSdJwtVcDomain,
                        vct = sdJwtVc.vct
                    )
                }

                credential.certify(
                    issuerProvidedAuthenticationData = sdJwtVc.compactSerialization.encodeToByteString()
                )
            }

            document.edit {
                provisioned = true
                mpzPassId = mpzPass.uniqueId
            }
            return document
        } catch (e: Exception) {
            deleteDocument(document.identifier)
            if (e is CancellationException) throw e
            throw ImportMpzPassException("Failed importing credentials", e)
        }
    }

    /**
     * A builder for DocumentStore.
     *
     * @param storage the [Storage] to use for storing/retrieving documents.
     * @param secureAreaRepository the repository of configured [SecureArea] that can be used.
     */
    class Builder(
        private val storage: Storage,
        private val secureAreaRepository: SecureAreaRepository,
    ) {
        private val credentialLoaderBuilder = CredentialLoaderBuilder().apply {
            addMdocCredential()
            addKeylessSdJwtVcCredential()
            addKeyBoundSdJwtVcCredential()
        }

        private var documentMetadataFactory: (suspend (
            documentId: String,
            data: ByteString
        ) -> AbstractDocumentMetadata)? = null

        private var documentTableSpec: StorageTableSpec = Document.defaultTableSpec

        /**
         * Sets the factory function for creating [AbstractDocumentMetadata] instances.
         *
         * This should only be called if the applications wants to use [AbstractDocumentMetadata].
         * By default there is no factory and [Document.metadata] field is null.
         *
         * @param factory the factory to use.
         * @return the builder.
         */
        fun setDocumentMetadataFactory(
            factory: suspend (
                documentId: String,
                data: ByteString
            ) -> AbstractDocumentMetadata
        ): Builder {
            this.documentMetadataFactory = factory
            return this
        }

        /**
         * Add a new [Credential] implementation to document store.
         *
         * @param credentialType the credential type
         * @param createCredentialFunction a function to create a [Credential] of the given type.
         * @return the builder.
         */
        fun addCredentialImplementation(
            credentialType: String,
            createCredentialFunction: suspend (Document) -> Credential
        ): Builder {
            credentialLoaderBuilder.addCredentialImplementation(
                credentialType = credentialType,
                createCredentialFunction = createCredentialFunction
            )
            return this
        }

        /**
         * Sets the [StorageTableSpec] to use for the storage of the documents
         *
         * By default [Document.defaultTableSpec] is used.
         *
         * @param documentTableSpec the [StorageTableSpec] to use.
         * @return the builder
         */
        fun setTableSpec(
            documentTableSpec: StorageTableSpec
        ): Builder {
            this.documentTableSpec = documentTableSpec
            return this
        }

        /**
         * Builds the [DocumentStore].
         *
         * @return a [DocumentStore].
         */
        fun build(): DocumentStore {
            return DocumentStore(
                storage = storage,
                secureAreaRepository = secureAreaRepository,
                credentialLoader = credentialLoaderBuilder.build(),
                documentMetadataFactory = documentMetadataFactory,
                documentTableSpec = documentTableSpec
            )
        }
    }

    companion object {
        private const val TAG = "DocumentStore"

        private val documentStoreTableSpec = StorageTableSpec(
            name = "DocumentStore",
            supportPartitions = false,
            supportExpiration = false,
        )
        private const val TAGS_KEY = "tags"
    }
}

/**
 * Builds a [DocumentStore]
 *
 * @param storage the [Storage] to use for storing/retrieving documents.
 * @param secureAreaRepository the repository of configured [SecureArea] that can be used.
 * @param builderAction the builder action.
 * @return a [DocumentStore].
 */
inline fun buildDocumentStore(
    storage: Storage,
    secureAreaRepository: SecureAreaRepository,
    builderAction: DocumentStore.Builder.() -> Unit
): DocumentStore {
    val builder = DocumentStore.Builder(storage, secureAreaRepository)
    builder.builderAction()
    return builder.build()
}
