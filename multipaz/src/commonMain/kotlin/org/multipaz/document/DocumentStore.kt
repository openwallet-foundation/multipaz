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
import org.multipaz.credential.Credential
import org.multipaz.credential.CredentialLoaderBuilder

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
    internal val documentMetadataFactory: suspend (
        documentId: String,
        data: ByteString?,
        saveFn: suspend (data: ByteString) -> Unit
    ) -> AbstractDocumentMetadata,
    private val documentTableSpec: StorageTableSpec = Document.defaultTableSpec
) {
    // Use a cache so the same instance is returned by multiple lookupDocument() calls.
    // Cache is protected by the lock. Once the document is loaded it is never evicted.
    private val lock = Mutex()
    private val documentCache = mutableMapOf<String, Document>()

    init {
        check(!documentTableSpec.supportExpiration)
        check(!documentTableSpec.supportPartitions)
    }

    /**
     * Creates a new document.
     *
     * The parameters passed will be available in the [Document.metadata] property of the returned
     * document and can be updated using [AbstractDocumentMetadata.setMetadata] and
     * [AbstractDocumentMetadata.markAsProvisioned].
     *
     * The initial provisioning state of the document will be `false`. This can be updated using
     * [AbstractDocumentMetadata.markAsProvisioned] when configured with one or more certified [Credential] instances.
     *
     * If a document with the given identifier already exists, it will be deleted prior to
     * creating the document.
     *
     * @param displayName User-facing name of this specific [Document] instance, e.g. "John's Passport", or `null`.
     * @param typeDisplayName User-facing name of this document type, e.g. "Utopia Passport", or `null`.
     * @param cardArt An image that represents this document to the user in the UI. Generally, the aspect
     *   ratio of 1.586 is expected (based on ID-1 from the ISO/IEC 7810). PNG format is expected
     *   and transparency is supported.
     * @param issuerLogo An image that represents the issuer of the document in the UI, e.g. passport office logo.
     *   PNG format is expected, transparency is supported and square aspect ratio is preferred.
     * @param other Additional data the application wishes to store.
     */
    suspend fun createDocument(
        displayName: String? = null,
        typeDisplayName: String? = null,
        cardArt: ByteString? = null,
        issuerLogo: ByteString? = null,
        other: ByteString? = null
    ): Document {
        return createDocument(
            metadataInitializer = {
                val metadata = it as DocumentMetadata
                metadata.setMetadata(displayName, typeDisplayName, cardArt, issuerLogo, other)
            }
        )
    }

    /**
     * Creates a new document using another [AbstractDocumentMetadata] than [DocumentMetadata].
     *
     * If a document with the given identifier already exists, it will be deleted prior to
     * creating the document.
     *
     * @param metadataInitializer a function to create an instance implementing [AbstractDocumentMetadata].
     * @return A newly created document.
     */
    suspend fun createDocument(
        metadataInitializer: suspend (metadata: AbstractDocumentMetadata) -> Unit = {}
    ): Document {
        val table = storage.getTable(documentTableSpec)
        val documentIdentifier = table.insert(key = null, ByteString())
        val document = Document(this, documentIdentifier)
        document.metadata = documentMetadataFactory(
            document.identifier,
            null,
            document::saveMetadata
        )
        metadataInitializer(document.metadata)
        lock.withLock {
            documentCache[document.identifier] = document
        }
        emitOnDocumentAdded(document.identifier)
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
                val document = Document(this, identifier)
                document.metadata = documentMetadataFactory(identifier, blob, document::saveMetadata)
                document
            }
        }
    }

    /**
     * Lists all documents in the store.
     *
     * @return list of all the document identifiers in the store.
     */
    suspend fun listDocuments(): List<String> {
        // right now lock is not required
        return storage.getTable(documentTableSpec).enumerate()
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
            lock.withLock {
                document.deleteDocument()
                documentCache.remove(identifier)
            }
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

        private var documentMetadataFactory: suspend (
            documentId: String,
            data: ByteString?,
            saveFn: suspend (data: ByteString) -> Unit
        ) -> AbstractDocumentMetadata = DocumentMetadata::create

        private var documentTableSpec: StorageTableSpec = Document.defaultTableSpec

        /**
         * Sets the factory function for creating [AbstractDocumentMetadata] instances.
         *
         * This should only be called if the applications wants to use another [AbstractDocumentMetadata]
         * implementation than [DocumentMetadata]. By default this is set to [DocumentMetadata.create]
         * which creates [DocumentMetadata] instances.
         *
         * @param factory the factory to use.
         * @return the builder.
         */
        fun setDocumentMetadataFactory(
            factory: suspend (
                documentId: String,
                data: ByteString?,
                saveFn: suspend (data: ByteString) -> Unit
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
fun buildDocumentStore(
    storage: Storage,
    secureAreaRepository: SecureAreaRepository,
    builderAction: DocumentStore.Builder.() -> Unit
): DocumentStore {
    val builder = DocumentStore.Builder(storage, secureAreaRepository)
    builder.builderAction()
    return builder.build()
}
