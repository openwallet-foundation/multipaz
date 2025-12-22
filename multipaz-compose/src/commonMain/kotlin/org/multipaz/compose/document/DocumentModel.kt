package org.multipaz.compose.document

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.multipaz.compose.decodeImage
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.Document
import org.multipaz.document.DocumentDeleted
import org.multipaz.document.DocumentEvent
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.default_card_art
import org.multipaz.util.Logger

/**
 * Model that loads documents from a [DocumentStore] and keeps them updated.
 *
 * It exposes a [kotlinx.coroutines.flow.StateFlow] of all documents as [DocumentInfo]
 * and listens to live updates from the store. If a [Document] has no cardArt the model
 * creates a default stock cardArt.
 *
 * @param scope launches coroutines
 * @param documentStore the [DocumentStore] which manages [Document] and [Credential] instances.
 * @param documentTypeRepository a [DocumentTypeRepository] with information about document types or `null`.
 */
class DocumentModel(
    val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val documentStore: DocumentStore,
    val documentTypeRepository: DocumentTypeRepository?,
) {
    private val _documentInfos = MutableStateFlow<Map<String, DocumentInfo>>(emptyMap())
    val documentInfos: StateFlow<Map<String, DocumentInfo>> = _documentInfos

    init {
        scope.launch {
            val docIds = documentStore.listDocuments()
            docIds.forEach { documentId ->
                updateDocumentInfo(documentId)
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
        if (event is DocumentDeleted) {
            _documentInfos.update { current ->
                current
                    .toMutableMap()
                    .apply { remove(id) }
                    .sortedMap()
            }
        } else {
            documentStore.lookupDocument(id)?.let { document ->
                _documentInfos.update { current ->
                    current
                        .toMutableMap()
                        .apply { this[id] = document.toDocumentInfo() }
                        .sortedMap()
                }
            }
        }
    }

    private suspend fun Document.toDocumentInfo(): DocumentInfo {
        val cardArtBitMap = decodeImage(metadata.cardArt?.toByteArray() ?: byteArrayOf())
        val defaultCardArtBitMap = decodeImage(Res.drawable.default_card_art.toByteArray())
        val cardArt = if (metadata.cardArt == null) {
            renderFallbackCardArt(
                defaultCardArtBitMap,
                metadata.displayName,
                metadata.typeDisplayName
            )

        } else {
            cardArtBitMap
        }
        return DocumentInfo(
            document = this,
            cardArt = cardArt,
            credentialInfos = buildCredentialInfos(documentTypeRepository)
        )
    }

    companion object {
        private const val TAG = "DocumentModel"

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

        private suspend fun DrawableResource.toByteArray(): ByteArray =
            getDrawableResourceBytes(
                getSystemResourceEnvironment(),
                this
            )

        private fun Map<String, DocumentInfo>.sortedMap(): Map<String, DocumentInfo> =
            this.entries
                .sortedBy { it.key }
                .associate { it.toPair() }
    }
}

internal expect fun DocumentModel.renderFallbackCardArt(
    fallbackBaseImage: ImageBitmap,
    primaryText: String?,
    secondaryText: String?
): ImageBitmap

