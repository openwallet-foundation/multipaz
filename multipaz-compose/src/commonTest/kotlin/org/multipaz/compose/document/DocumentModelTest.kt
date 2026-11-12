package org.multipaz.compose.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentBadgeColor
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyInfo
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        model.close()
    }

    @Test
    fun testCloseStopsEventFlowUpdates() = runTest {
        val store = createTestDocumentStore()
        val doc1 = store.createDocument(displayName = "Doc 1")
        val model = DocumentModel.create(store, null)
        assertEquals(listOf(doc1.identifier), model.documentOrder)

        model.close()
        store.createDocument(displayName = "Doc 2")
        // Give Dispatchers.Default collector an opportunity if it were still active
        withContext(Dispatchers.Default) {
            delay(50.milliseconds)
        }
        yield()
        assertEquals(listOf(doc1.identifier), model.documentOrder)
    }

    @Test
    fun testDocumentCredentialUsageUpdatedRefreshesBadgesOnly() = runTest {
        val storage = EphemeralStorage()
        val baseSecureArea = SoftwareSecureArea.create(storage)
        var getKeyInfoCalls = 0
        var getKeyInvalidatedCalls = 0
        val countingSecureArea = object : SecureArea by baseSecureArea {
            override suspend fun getKeyInfo(alias: String): KeyInfo {
                getKeyInfoCalls += 1
                return baseSecureArea.getKeyInfo(alias)
            }

            override suspend fun getKeyInvalidated(alias: String): Boolean {
                getKeyInvalidatedCalls += 1
                return baseSecureArea.getKeyInvalidated(alias)
            }
        }
        val secureAreaRepository = SecureAreaRepository.Builder()
            .add(countingSecureArea)
            .build()
        val store = buildDocumentStore(storage, secureAreaRepository) {}
        val doc = store.createDocument(displayName = "Doc 1")
        val cred = MdocCredential.create(
            document = doc,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = countingSecureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = CreateKeySettings(),
        )

        val model = DocumentModel.create(
            documentStore = store,
            documentTypeRepository = null,
            badgeFunction = { d ->
                val uses = d.getCredentials().sumOf { it.usageCount }
                listOf(DocumentBadge("Uses: $uses", DocumentBadgeColor(0, 128, 0)))
            }
        )
        try {
            assertEquals("Uses: 0", model.documentInfos.value.single().badges.single().text)
            val keyInfoCallsBefore = getKeyInfoCalls
            val keyInvalidatedCallsBefore = getKeyInvalidatedCalls

            cred.increaseUsageCount()

            withTimeout(2.seconds) {
                model.documentInfos.first { infos ->
                    infos.singleOrNull()?.badges?.singleOrNull()?.text == "Uses: 1"
                }
            }
            assertEquals(keyInfoCallsBefore, getKeyInfoCalls)
            assertEquals(keyInvalidatedCallsBefore, getKeyInvalidatedCalls)
        } finally {
            model.close()
        }
    }
}
