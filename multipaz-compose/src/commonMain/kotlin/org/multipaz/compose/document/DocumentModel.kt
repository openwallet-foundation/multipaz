package org.multipaz.compose.document

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.decodeImage
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.document.Document
import org.multipaz.document.DocumentAdded
import org.multipaz.document.DocumentDeleted
import org.multipaz.document.DocumentEvent
import org.multipaz.document.DocumentStore
import org.multipaz.document.DocumentUpdated
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.Logger
import org.multipaz.util.LruCache

private const val TAG = "DocumentModel"

private data class DocumentModelStorageData(
    var sortingOrder: Map<String, Int> = emptyMap()
) {
    fun toDataItem(): DataItem {
        return buildCborMap {
            putCborMap("documentOrder") {
                for((key, value) in sortingOrder) {
                    put(key, value)
                }
            }
        }
    }

    suspend fun save(
        table: StorageTable,
        partitionId: String
    ) {
        table.update(
            key = KEY_NAME,
            data = ByteString(Cbor.encode(toDataItem())),
            partitionId = partitionId
        )
    }

    companion object {
        const val KEY_NAME = "DocumentModelStorageData"

        suspend fun load(
            table: StorageTable,
            partitionId: String
        ): DocumentModelStorageData {
            table.get(KEY_NAME, partitionId)?.let {
                return fromDataItem(Cbor.decode(it.toByteArray()))
            }
            val data = DocumentModelStorageData()
            table.insert(KEY_NAME, ByteString(Cbor.encode(data.toDataItem())), partitionId)
            return data
        }

        fun fromDataItem(dataItem: DataItem): DocumentModelStorageData {
            var sortingOrder = emptyMap<String, Int>()
            try {
                if (dataItem.hasKey("documentOrder")) {
                    sortingOrder = dataItem["documentOrder"].asMap.map { (key, value) ->
                        key.asTstr to value.asNumber.toInt()
                    }.toMap()
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "Error decoding sortingOrder", e)
            }
            return DocumentModelStorageData(sortingOrder)
        }
    }
}

/**
 * Model that loads documents from a [DocumentStore] and keeps them updated.
 *
 * This model exposes a [StateFlow] of all documents as [DocumentInfo]
 * and listens to live updates from the store. If a [Document] has no card art the model
 * creates a default card art using [Branding.renderFallbackCardArt]. The model also
 * maintains a persistent order of documents and applications can call e.g.
 * [setDocumentPosition] to change the order.
 *
 * @param documentStore the [DocumentStore] which manages [Document] and [Credential] instances.
 * @param documentTypeRepository a [DocumentTypeRepository] with information about document types or `null`.
 * @param storage the [Storage] used for storing document order.
 * @param storagePartition the partition of [storage] to use.
 */
class DocumentModel(
    val documentStore: DocumentStore,
    val documentTypeRepository: DocumentTypeRepository?,
    val storage: Storage = EphemeralStorage(),
    val storagePartition: String = "default",
) {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val _documentInfos = MutableStateFlow<List<DocumentInfo>>(emptyList())
    private lateinit var table: StorageTable
    private lateinit var storageData: DocumentModelStorageData

    /**
     * A list of [DocumentInfo] for the documents in [documentStore].
     */
    val documentInfos: StateFlow<List<DocumentInfo>> = _documentInfos.asStateFlow()

    init {
        scope.launch {
            table = storage.getTable(documentModelTableSpec)
            storageData = DocumentModelStorageData.load(table, storagePartition)

            val docIds = documentStore.listDocumentIds()
            docIds.forEach { documentId ->
                updateDocumentInfo(documentId, DocumentAdded(documentId))
            }

            documentStore.eventFlow
                .onEach { event ->
                    Logger.i(
                        TAG,
                        "DocumentStore event ${event::class.simpleName} ${event.documentId}"
                    )
                    updateDocumentInfo(event = event)

                }
                .launchIn(scope)
        }
    }

    private suspend fun updateDocumentInfo(
        documentId: String? = null,
        event: DocumentEvent? = null,
    ) {
        val id = event?.documentId ?: documentId ?: return
        when (event) {
            is DocumentAdded -> {
                documentStore.lookupDocument(id)?.let { document ->
                    _documentInfos.update { current ->
                        current
                            .toMutableList()
                            .apply {
                                add(document.toDocumentInfo())
                            }.sorted()
                    }
                }
            }
            is DocumentDeleted -> {
                _documentInfos.update { current ->
                    current
                        .toMutableList()
                        .apply {
                            find { documentInfo -> documentInfo.document.identifier == id }?.let {
                                remove(it)
                            }
                        }.sorted()
                }
            }
            is DocumentUpdated -> {
                documentStore.lookupDocument(id)?.let { document ->
                    _documentInfos.update { current ->
                        current
                            .toMutableList()
                            .apply {
                                val existingDocumentInfo =
                                    find { documentInfo -> documentInfo.document.identifier == id }
                                if (existingDocumentInfo == null) {
                                    Logger.w(TAG, "Didn't find DocumentInfo for document with id $id")
                                } else {
                                    val newDocumentInfo = document.toDocumentInfo()
                                    if (newDocumentInfo != existingDocumentInfo) {
                                        remove(existingDocumentInfo)
                                        add(newDocumentInfo)
                                    } else {
                                        Logger.w(TAG, "DocumentInfo for document with id $id didn't change")
                                    }
                                }
                            }.sorted()
                    }
                }
            }
            null -> {}
        }
    }

    private fun List<DocumentInfo>.sorted(): List<DocumentInfo> {
        return this.sortedWith { a, b ->
            val sa = storageData.sortingOrder[a.document.identifier]
            val sb = storageData.sortingOrder[b.document.identifier]
            if (sa != null && sb != null) {
                if (sa != sb) {
                    return@sortedWith sa.compareTo(sb)
                }
            }
            return@sortedWith a.document.created.compareTo(b.document.created)
        }
    }

    /**
     * Sets the position of a document.
     *
     * @param documentInfo the [DocumentInfo] to set the position for.
     * @param position the new position, zero-based.
     * @throws IllegalArgumentException if [documentInfo] doesn't exist in the model or if [position]
     *   exceed the number of documents in the store.
     */
    suspend fun setDocumentPosition(
        documentInfo: DocumentInfo,
        position: Int
    ) {
        val documentInfos = _documentInfos.value.toMutableList()
        if (!documentInfos.remove(documentInfo)) {
            throw IllegalArgumentException("Passed in documentInfo is not in list")
        }
        documentInfos.add(
            index = position,
            element = documentInfo,
        )
        val sortingOrder = mutableMapOf<String, Int>()
        documentInfos.forEachIndexed { index, documentInfo ->
            sortingOrder.put(documentInfo.document.identifier, index)
        }
        storageData.sortingOrder = sortingOrder
        storageData.save(table, storagePartition)
        _documentInfos.value = documentInfos
    }

    // Use a simple LRU cache to avoid decoding the same cardArt over and over again
    private val cardArtCache = LruCache<ByteString, ImageBitmap>(5)

    private suspend fun Document.toDocumentInfo(): DocumentInfo {
        cardArt?.let {
            val image = it.toByteArray()
            val imageSha256 = ByteString(Crypto.digest(Algorithm.SHA256, image))
            var cardArt = cardArtCache.get(imageSha256)
            if (cardArt == null) {
                cardArt = decodeImage(image)
                cardArtCache.put(imageSha256, cardArt)
            }
            return DocumentInfo(
                document = this,
                cardArt = cardArt,
                credentialInfos = buildCredentialInfos(documentTypeRepository)
            )
        }
        return DocumentInfo(
            document = this,
            cardArt = Branding.Current.value.renderFallbackCardArt(this),
            credentialInfos = buildCredentialInfos(documentTypeRepository)
        )
    }

    companion object {
        private val documentModelTableSpec = StorageTableSpec(
            name = "DocumentModel",
            supportPartitions = true,
            supportExpiration = false,
        )

        private suspend fun Document.buildCredentialInfos(
            documentTypeRepository: DocumentTypeRepository?
        ): List<CredentialInfo> {
            return getCredentials().map { credential ->
                val keyInfo = if (credential is SecureAreaBoundCredential) {
                    credential.secureArea.getKeyInfo(credential.alias)
                } else {
                    null
                }
                val keyInvalidated = if (credential is SecureAreaBoundCredential) {
                    credential.secureArea.getKeyInvalidated(credential.alias)
                } else {
                    false
                }
                val claims = if (credential.isCertified) {
                    credential.getClaims(documentTypeRepository)
                } else {
                    emptyList()
                }
                CredentialInfo(
                    credential = credential,
                    claims = claims,
                    keyInfo = keyInfo,
                    keyInvalidated = keyInvalidated
                )
            }
        }
    }
}

