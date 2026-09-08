package org.multipaz.compose.document

import kotlinx.coroutines.test.runTest
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentModelTest {

    private suspend fun createTestDocumentStore(): DocumentStore {
        val storage = EphemeralStorage()
        val softwareSecureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder()
            .add(softwareSecureArea)
            .build()
        return buildDocumentStore(storage, secureAreaRepository) {}
    }

    @Test
    fun testGetAndSetDocumentOrder() = runTest {
        val store = createTestDocumentStore()
        val doc1 = store.createDocument(displayName = "Doc 1")
        val doc2 = store.createDocument(displayName = "Doc 2")
        val doc3 = store.createDocument(displayName = "Doc 3")

        val model = DocumentModel.create(store, null)
        val initialOrder = listOf(doc1.identifier, doc2.identifier, doc3.identifier)
        assertEquals(initialOrder, model.documentOrder)

        // Reorder documents
        val newOrder = listOf(doc3.identifier, doc1.identifier, doc2.identifier)
        model.setDocumentOrder(newOrder)

        assertEquals(newOrder, model.documentOrder)
        assertEquals(newOrder, model.documentInfos.value.map { it.document.identifier })

        // Check persistence in new DocumentModel instance
        val model2 = DocumentModel.create(store, null)
        assertEquals(newOrder, model2.documentOrder)
    }

    @Test
    fun testSetDocumentOrderPartialList() = runTest {
        val store = createTestDocumentStore()
        val doc1 = store.createDocument(displayName = "Doc 1")
        val doc2 = store.createDocument(displayName = "Doc 2")
        val doc3 = store.createDocument(displayName = "Doc 3")

        val model = DocumentModel.create(store, null)

        // Setting order for a subset moves specified doc to front, preserving relative order of rest
        model.setDocumentOrder(listOf(doc2.identifier))
        val expected = listOf(doc2.identifier, doc1.identifier, doc3.identifier)
        assertEquals(expected, model.documentOrder)
    }

    @Test
    fun testSetDocumentOrderWithDuplicatesAndUnknownIds() = runTest {
        val store = createTestDocumentStore()
        val doc1 = store.createDocument(displayName = "Doc 1")
        val doc2 = store.createDocument(displayName = "Doc 2")
        val doc3 = store.createDocument(displayName = "Doc 3")

        val model = DocumentModel.create(store, null)

        // Duplicates and unknown IDs handled gracefully
        model.setDocumentOrder(listOf("unknown_id", doc2.identifier, doc2.identifier, doc1.identifier))
        val expected = listOf(doc2.identifier, doc1.identifier, doc3.identifier)
        assertEquals(expected, model.documentOrder)
    }

    @Test
    fun testSetDocumentPosition() = runTest {
        val store = createTestDocumentStore()
        val doc1 = store.createDocument(displayName = "Doc 1")
        val doc2 = store.createDocument(displayName = "Doc 2")
        val doc3 = store.createDocument(displayName = "Doc 3")

        val model = DocumentModel.create(store, null)
        val docInfo3 = model.documentInfos.value.find { it.document.identifier == doc3.identifier }!!

        // Move doc3 to position 0
        model.setDocumentPosition(docInfo3, 0)
        assertEquals(listOf(doc3.identifier, doc1.identifier, doc2.identifier), model.documentOrder)

        // Move doc3 to position 2
        model.setDocumentPosition(docInfo3, 2)
        assertEquals(listOf(doc1.identifier, doc2.identifier, doc3.identifier), model.documentOrder)

        // Invalid position throws
        assertFailsWith<IllegalArgumentException> {
            model.setDocumentPosition(docInfo3, 5)
        }
    }
}
