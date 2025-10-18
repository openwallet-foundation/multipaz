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

import org.multipaz.claim.Claim
import org.multipaz.credential.Credential
import org.multipaz.credential.CredentialLoader
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.time.Instant;
import kotlinx.io.bytestring.ByteString
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentStoreTest {
    private lateinit var storage: Storage
    private lateinit var secureAreaRepository: SecureAreaRepository

    // This isn't really used, we only use a single domain.
    private val CREDENTIAL_DOMAIN = "domain"

    @BeforeTest
    fun setup() = runBlocking {
        storage = EphemeralStorage()
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(SoftwareSecureArea.create(storage))
            .build()
    }

    private fun runDocumentTest(testBody: suspend TestScope.(docStore: DocumentStore) -> Unit) {
        runTest {
            val documentStore = buildDocumentStore(
                storage = storage,
                secureAreaRepository = secureAreaRepository
            ) {
                addCredentialImplementation(TestSecureAreaBoundCredential.CREDENTIAL_TYPE) { document ->
                    TestSecureAreaBoundCredential(document)
                }
                addCredentialImplementation(TestCredential.CREDENTIAL_TYPE) { document ->
                    TestCredential(document)
                }
            }
            testBody(documentStore)
        }
    }
    
    @Test
    fun testListDocuments() = runDocumentTest { documentStore ->
        assertEquals(0, documentStore.listDocuments().size.toLong())
        val documents = (0..9).map { documentStore.createDocument() }
        assertEquals(10, documentStore.listDocuments().size.toLong())
        val deletedId = documents[1].identifier
        documentStore.deleteDocument(deletedId)
        val remainingIds = documents.map { it.identifier }.filter { it != deletedId }.toSet()
        assertEquals(remainingIds, documentStore.listDocuments().toSet())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEventFlow() = runTest {
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {
            addCredentialImplementation(TestSecureAreaBoundCredential.CREDENTIAL_TYPE) { document ->
                TestSecureAreaBoundCredential(document)
            }
        }

        val events = mutableListOf<DocumentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            documentStore.eventFlow.toList(events)
        }

        val doc0 = documentStore.createDocument()
        runCurrent()
        assertEquals(DocumentAdded(doc0.identifier), events.last())

        val doc1 = documentStore.createDocument()
        runCurrent()
        assertEquals(DocumentAdded(doc1.identifier), events.last())

        val doc2 = documentStore.createDocument()
        runCurrent()
        assertEquals(DocumentAdded(doc2.identifier), events.last())

        doc2.simpleMetadata.markAsProvisioned()
        runCurrent()
        assertEquals(DocumentUpdated(doc2.identifier), events.last())

        doc1.simpleMetadata.setMetadata("foo", "bar", null, null, null)
        runCurrent()
        assertEquals(DocumentUpdated(doc1.identifier), events.last())

        documentStore.deleteDocument(doc0.identifier)
        runCurrent()
        assertEquals(DocumentDeleted(doc0.identifier), events.last())

        documentStore.deleteDocument(doc2.identifier)
        runCurrent()
        assertEquals(DocumentDeleted(doc2.identifier), events.last())

        documentStore.deleteDocument(doc1.identifier)
        runCurrent()
        assertEquals(DocumentDeleted(doc1.identifier), events.last())
    }

    @Test
    fun testCreationDeletion() = runDocumentTest { documentStore ->
        val document = documentStore.createDocument()

        val document2 = documentStore.lookupDocument(document.identifier)
        assertSame(document, document2)

        assertNull(documentStore.lookupDocument("nonExistingDocument"))

        documentStore.deleteDocument(document.identifier)
        assertNull(documentStore.lookupDocument(document.identifier))
    }

    /* Validates that the same instance is returned for the same document name. This
     * relies on Document.equals() not being overridden.
     */
    @Test
    fun testCaching() = runDocumentTest { documentStore ->
        val a = documentStore.createDocument()
        val b = documentStore.createDocument()
        assertNotSame(a, b)
        assertNotEquals(a.identifier, b.identifier)
        assertSame(a, documentStore.lookupDocument(a.identifier))
        assertSame(b, documentStore.lookupDocument(b.identifier))
        documentStore.deleteDocument(a.identifier)
        assertNull(documentStore.lookupDocument(a.identifier))
        assertEquals(b, documentStore.lookupDocument(b.identifier))
    }

    @Test
    fun testCredentialUsage() = runDocumentTest { documentStore ->
        val document = documentStore.createDocument()
        val timeBeforeValidity = Instant.fromEpochMilliseconds(40)
        val timeValidityBegin = Instant.fromEpochMilliseconds(50)
        val timeDuringValidity = Instant.fromEpochMilliseconds(100)
        val timeValidityEnd = Instant.fromEpochMilliseconds(150)
        val timeAfterValidity = Instant.fromEpochMilliseconds(200)

        // By default, we don't have any credentials nor any pending credentials.
        assertEquals(0, document.getCertifiedCredentials().size.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())

        // Since none are certified or even pending yet, we can't present anything.
        assertNull(document.findCredential(CREDENTIAL_DOMAIN, timeDuringValidity))

        // Create ten credentials...
        for (n in 0..9) {
            TestCredential(
                document,
                null,
                CREDENTIAL_DOMAIN
            ).addToDocument()
        }
        assertEquals(0, document.getCertifiedCredentials().size.toLong())
        assertEquals(10, document.getPendingCredentials().size.toLong())

        // ... and certify all of them
        var n = 0
        for (pendingCredential in document.getPendingCredentials()) {
            val issuerProvidedAuthenticationData = byteArrayOf(1, 2, n.toByte())
            pendingCredential.certify(
                issuerProvidedAuthenticationData,
                timeValidityBegin,
                timeValidityEnd
            )
            n += 1
        }
        assertEquals(10, document.getCertifiedCredentials().size.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())

        // If at a time before anything is valid, should not be able to present
        assertNull(document.findCredential(CREDENTIAL_DOMAIN, timeBeforeValidity))

        // Ditto for right after
        assertNull(document.findCredential(CREDENTIAL_DOMAIN, timeAfterValidity))

        // Check we're able to present at a time when the credentials are valid
        var credential = document.findCredential(CREDENTIAL_DOMAIN, timeDuringValidity)
        assertNotNull(credential)
        assertEquals(0, credential.usageCount.toLong())

        // B/c of how findCredential(CREDENTIAL_DOMAIN) we know we get the first credential. Match
        // up with expected issuer signed data as per above.
        assertEquals(0.toByte().toLong(), credential.issuerProvidedData[2].toLong())
        assertEquals(0, credential.usageCount.toLong())
        credential.increaseUsageCount()
        assertEquals(1, credential.usageCount.toLong())

        // Simulate nine more presentations, all of them should now be used up
        n = 0
        while (n < 9) {
            credential = document.findCredential(CREDENTIAL_DOMAIN, timeDuringValidity)
            assertNotNull(credential)

            // B/c of how findCredential(CREDENTIAL_DOMAIN) we know we get the credentials after
            // the first one in order. Match up with expected issuer signed data as per above.
            assertEquals(
                (n + 1).toByte().toLong(), credential!!.issuerProvidedData[2].toLong()
            )
            credential.increaseUsageCount()
            n++
        }

        // All ten credentials should now have a use count of 1.
        for (credential in document.getCertifiedCredentials()) {
            assertEquals(1, credential.usageCount.toLong())
        }

        // Simulate ten more presentations
        n = 0
        while (n < 10) {
            credential = document.findCredential(CREDENTIAL_DOMAIN, timeDuringValidity)
            assertNotNull(credential)
            credential.increaseUsageCount()
            n++
        }

        // All ten credentials should now have a use count of 2.
        for (credential in document.getCertifiedCredentials()) {
            assertEquals(2, credential.usageCount.toLong())
        }

        val secureArea = secureAreaRepository.getImplementation(SoftwareSecureArea.IDENTIFIER)!!

        // Create and certify five replacements
        n = 0
        while (n < 5) {
            TestSecureAreaBoundCredential.create(
                document,
                null,
                CREDENTIAL_DOMAIN,
                secureArea,
                CreateKeySettings()
            )
            n++
        }
        assertEquals(10, document.getCertifiedCredentials().size.toLong())
        assertEquals(5, document.getPendingCredentials().size.toLong())
        n = 10
        for (pendingCredential in document.getPendingCredentials()) {
            pendingCredential.certify(
                ByteArray(0),
                timeValidityBegin,
                timeValidityEnd
            )
            n++
        }
        assertEquals(15, document.getCertifiedCredentials().size.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())

        // Simulate ten presentations and check we get the newly created ones
        n = 0
        while (n < 10) {
            credential = document.findCredential(CREDENTIAL_DOMAIN, timeDuringValidity)
            assertNotNull(credential)
            assertEquals(0, credential!!.issuerProvidedData.size.toLong())
            credential.increaseUsageCount()
            n++
        }

        // All fifteen credentials should now have a use count of 2.
        for (credential in document.getCertifiedCredentials()) {
            assertEquals(2, credential.usageCount.toLong())
        }

        // Simulate 15 more presentations
        n = 0
        while (n < 15) {
            credential = document.findCredential(CREDENTIAL_DOMAIN, timeDuringValidity)
            assertNotNull(credential)
            credential!!.increaseUsageCount()
            n++
        }

        // All fifteen credentials should now have a use count of 3. This shows that
        // we're hitting the credentials evenly (both old and new).
        for (credential in document.getCertifiedCredentials()) {
            assertEquals(3, credential.usageCount.toLong())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCredentialPersistence() = runTest {
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {
            addCredentialImplementation(TestSecureAreaBoundCredential.CREDENTIAL_TYPE) { document ->
                TestSecureAreaBoundCredential(document)
            }
        }

        var n: Int
        val timeValidityBegin = Instant.fromEpochMilliseconds(50)
        val timeValidityEnd = Instant.fromEpochMilliseconds(150)
        val document = documentStore.createDocument()
        assertEquals(0, document.getCertifiedCredentials().size.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())

        val secureArea = secureAreaRepository.getImplementation(SoftwareSecureArea.IDENTIFIER)!!

        // Create ten pending credentials and certify four of them
        n = 0
        while (n < 4) {
            TestSecureAreaBoundCredential.create(
                document,
                null,
                CREDENTIAL_DOMAIN,
                secureArea,
                CreateKeySettings()
            )
            n++
        }
        assertEquals(0, document.getCertifiedCredentials().size.toLong())
        assertEquals(4, document.getPendingCredentials().size.toLong())
        n = 0
        for (credential in document.getPendingCredentials()) {
            // Because we check that we serialize things correctly below, make sure
            // the data and validity times vary for each credential...
            credential.certify(
                byteArrayOf(1, 2, n.toByte()),
                Instant.fromEpochMilliseconds(timeValidityBegin.toEpochMilliseconds() + n),
                Instant.fromEpochMilliseconds(timeValidityEnd.toEpochMilliseconds() + 2 * n)
            )
            for (m in 0 until n) {
                credential.increaseUsageCount()
            }
            assertEquals(n.toLong(), credential.usageCount.toLong())
            n++
        }
        assertEquals(4, document.getCertifiedCredentials().size.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())
        n = 0
        while (n < 6) {
            TestSecureAreaBoundCredential.create(
                document,
                null,
                CREDENTIAL_DOMAIN,
                secureArea,
                CreateKeySettings()
            )
            n++
        }
        val pending = document.getPendingCredentials()
        val certified = document.getCertifiedCredentials()
        assertEquals(4, certified.size.toLong())
        assertEquals(6, pending.size.toLong())

        runCurrent()
        val documentStore2 = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {
            addCredentialImplementation(TestSecureAreaBoundCredential.CREDENTIAL_TYPE) { document ->
                TestSecureAreaBoundCredential(document)
            }
        }

        val document2 = documentStore2.lookupDocument(document.identifier)
        assertNotNull(document2)
        val certified2 = document2.getCertifiedCredentials()
        val pending2 = document2.getPendingCredentials()

        assertEquals(4, certified2.size.toLong())
        assertEquals(6, pending2.size.toLong())

        // Now check that what we loaded matches what we created in-memory just above. We
        // use the fact that the order of the credentials are preserved across save/load.
        val it1 = certified.sortedBy { it.identifier }.iterator()
        val it2 = certified2.sortedBy { it.identifier }.iterator()
        n = 0
        while (n < 4) {
            val doc1 = it1.next() as TestSecureAreaBoundCredential
            val doc2 = it2.next() as TestSecureAreaBoundCredential
            assertEquals(doc1.identifier, doc2.identifier)
            assertEquals(doc1.alias, doc2.alias)
            assertEquals(doc1.validFrom, doc2.validFrom)
            assertEquals(doc1.validUntil, doc2.validUntil)
            assertEquals(doc1.usageCount.toLong(), doc2.usageCount.toLong())
            assertContentEquals(doc1.issuerProvidedData, doc2.issuerProvidedData)
            assertEquals(doc1.getAttestation(), doc2.getAttestation())
            n++
        }
        val itp1 = pending.sortedBy { it.identifier }.iterator()
        val itp2 = pending2.sortedBy { it.identifier }.iterator()
        n = 0
        while (n < 6) {
            val doc1 = itp1.next() as TestSecureAreaBoundCredential
            val doc2 = itp2.next() as TestSecureAreaBoundCredential
            assertEquals(doc1.identifier, doc2.identifier)
            assertEquals(doc1.alias, doc2.alias)
            assertEquals(doc1.getAttestation(), doc2.getAttestation())
            n++
        }
    }

    @Test
    fun testDocumentMetadata() = runTest {
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {
            addCredentialImplementation(TestSecureAreaBoundCredential.CREDENTIAL_TYPE) { document ->
                TestSecureAreaBoundCredential(document)
            }
        }
        val document = documentStore.createDocument {
            val appData = it as DocumentMetadata
            appData.setMetadata("init", "", null, null, null)
        }
        val appData = document.simpleMetadata
        assertFalse(appData.provisioned)
        assertEquals("init", appData.displayName)
        appData.markAsProvisioned()
        assertTrue(appData.provisioned)
        appData.setMetadata("foo", "bar", ByteString(1, 2, 3), null, null)
        assertEquals("foo", appData.displayName)
        assertEquals(ByteString(1, 2, 3), appData.cardArt)

        val documentStore2 = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {
            addCredentialImplementation(TestSecureAreaBoundCredential.CREDENTIAL_TYPE) { document ->
                TestSecureAreaBoundCredential(document)
            }
        }
        val document2 = documentStore2.lookupDocument(document.identifier)
        assertNotNull(document2)
        val appData2 = document2.simpleMetadata
        assertTrue(appData2.provisioned)
        assertEquals("foo", appData2.displayName)
        assertEquals(ByteString(1, 2, 3), appData2.cardArt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCredentialValidity() = runDocumentTest { documentStore ->
        val document = documentStore.createDocument()

        // We want to check the behavior for when the holder has a birthday and the issuer
        // carefully sends half the MSOs to be used before the birthday (with age_in_years set to
        // 17) and half the MSOs for after the birthday (with age_in_years set to 18).
        //
        // The validity periods are carefully set so the MSOs for 17 are have validUntil set to
        // to the holders birthday and the MSOs for 18 are set so validFrom starts at the birthday.
        //
        val timeValidityBegin = Instant.fromEpochMilliseconds(50)
        val timeOfUseBeforeBirthday = Instant.fromEpochMilliseconds(80)
        val timeOfBirthday = Instant.fromEpochMilliseconds(100)
        val timeOfUseAfterBirthday = Instant.fromEpochMilliseconds(120)
        val timeValidityEnd = Instant.fromEpochMilliseconds(150)

        val secureArea = secureAreaRepository.getImplementation(SoftwareSecureArea.IDENTIFIER)!!

        // Create and certify ten credentials. Put age_in_years as the issuer provided data so we can
        // check it below.
        var n = 0
        while (n < 10) {
            TestSecureAreaBoundCredential.create(
                document,
                null,
                CREDENTIAL_DOMAIN,
                secureArea,
                CreateKeySettings(),
            )
            n++
        }
        runCurrent()
        val pendingCredentials = document.getPendingCredentials()
        assertEquals(10, pendingCredentials.size.toLong())
        n = 0
        for (pendingCredential in pendingCredentials) {
            if (n < 5) {
                pendingCredential.certify(byteArrayOf(17), timeValidityBegin, timeOfBirthday)
            } else {
                pendingCredential.certify(byteArrayOf(18), timeOfBirthday, timeValidityEnd)
            }
            n++
        }

        // Simulate ten presentations before the birthday
        n = 0
        while (n < 10) {
            val credential =
                document.findCredential(CREDENTIAL_DOMAIN, timeOfUseBeforeBirthday)
            assertNotNull(credential)
            // Check we got a credential with age 17.
            assertEquals(
                17.toByte().toLong(), credential!!.issuerProvidedData[0].toLong()
            )
            credential.increaseUsageCount()
            n++
        }

        // Simulate twenty presentations after the birthday
        n = 0
        while (n < 20) {
            val credential =
                document.findCredential(CREDENTIAL_DOMAIN, timeOfUseAfterBirthday)
            assertNotNull(credential)
            // Check we got a credential with age 18.
            assertEquals(
                18.toByte().toLong(), credential!!.issuerProvidedData[0].toLong()
            )
            credential.increaseUsageCount()
            n++
        }

        // Examine the credentials. The first five should have use count 2, the
        // latter five use count 4.
        n = 0
        for (credential in document.getCertifiedCredentials()) {
            if (n++ < 5) {
                assertEquals(2, credential.usageCount.toLong())
            } else {
                assertEquals(4, credential.usageCount.toLong())
            }
        }
    }

    @Test
    fun testCredentialReplacement() = runDocumentTest { documentStore ->
        val document = documentStore.createDocument()
        assertEquals(0, document.getCertifiedCredentials().size.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())
        val secureArea = secureAreaRepository.getImplementation(SoftwareSecureArea.IDENTIFIER)!!
        for (n in 0..9) {
            val pendingCredential = TestSecureAreaBoundCredential.create(
                document,
                null,
                CREDENTIAL_DOMAIN,
                secureArea,
                CreateKeySettings()
            )
            pendingCredential.certify(
                byteArrayOf(0, n.toByte()),
                Instant.fromEpochMilliseconds(100),
                Instant.fromEpochMilliseconds(200)
            )
        }
        assertEquals(0, document.getPendingCredentials().size.toLong())
        assertEquals(10, document.getCertifiedCredentials().size.toLong())

        // Now replace the fifth credential
        val credToReplace = document.getCertifiedCredentials()[5] as SecureAreaBoundCredential
        assertContentEquals(byteArrayOf(0, 5), credToReplace.issuerProvidedData)
        val pendingCredential = TestSecureAreaBoundCredential.create(
            document,
            credToReplace.identifier,
            CREDENTIAL_DOMAIN,
            secureArea,
            CreateKeySettings()
        )
        // ... it's not replaced until certify() is called
        assertEquals(1, document.getPendingCredentials().size.toLong())
        assertEquals(10, document.getCertifiedCredentials().size.toLong())
        pendingCredential.certify(
            byteArrayOf(1, 0),
            Instant.fromEpochMilliseconds(100),
            Instant.fromEpochMilliseconds(200)
        )
        // ... now it should be gone.
        assertEquals(0, document.getPendingCredentials().size.toLong())
        assertEquals(10, document.getCertifiedCredentials().size.toLong())

        // Check that it was indeed the fifth credential that was replaced inspecting issuer-provided data.
        // We rely on some implementation details on how ordering works... also cross-reference
        // with data passed into certify() functions above.
        var count = 0
        for (credential in document.getCertifiedCredentials()) {
            val expectedData = arrayOf(
                byteArrayOf(0, 0),
                byteArrayOf(0, 1),
                byteArrayOf(0, 2),
                byteArrayOf(0, 3),
                byteArrayOf(0, 4),
                byteArrayOf(0, 6),
                byteArrayOf(0, 7),
                byteArrayOf(0, 8),
                byteArrayOf(0, 9),
                byteArrayOf(1, 0)
            )
            assertContentEquals(expectedData[count++], credential.issuerProvidedData)
        }

        // Test the case where the replacement credential is prematurely deleted. The credential
        // being replaced should no longer reference it has a replacement...
        val toBeReplaced = document.getCertifiedCredentials()[0]
        var replacement = TestSecureAreaBoundCredential.create(
            document,
            toBeReplaced.identifier,
            CREDENTIAL_DOMAIN,
            secureArea,
            CreateKeySettings()
        )
        assertEquals(toBeReplaced.identifier, replacement.replacementForIdentifier)
        assertSame(replacement, document.getReplacementCredentialFor(toBeReplaced.identifier))
        document.deleteCredential(replacement.identifier)
        assertNull(document.getReplacementCredentialFor(toBeReplaced.identifier))

        // Similarly, test the case where the credential to be replaced is prematurely deleted.
        // The replacement credential should no longer indicate it's a replacement credential.
        replacement = TestSecureAreaBoundCredential.create(
            document,
            toBeReplaced.identifier,
            CREDENTIAL_DOMAIN,
            secureArea,
            CreateKeySettings()
        )
        assertEquals(toBeReplaced.identifier, replacement.replacementForIdentifier)
        assertEquals(replacement, document.getReplacementCredentialFor(toBeReplaced.identifier))
        document.deleteCredential(toBeReplaced.identifier)
        assertNull(replacement.replacementForIdentifier)
    }

    @Test
    fun concurrentRead() = runDocumentTest { documentStore ->
        // One coroutine repeatedly reads documents and credentials from the store
        // (imitating UI) and another repeatedly adds and deletes a document
        val frontEndJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                for (documentId in documentStore.listDocuments()) {
                    // May be deleted before we load it
                    val document = documentStore.lookupDocument(documentId) ?: continue
                    if (Random.Default.nextBoolean()) {
                        document.deleteCache()
                    }
                    document.getCredentials()
                }
                yield()  // so that this coroutine does not spin without a chance to be cancelled
            }
        }
        // this imitates back-end
        for (m in 0..300) {
            val document = documentStore.createDocument()
            for (n in 0..9) {
                TestCredential(
                    document,
                    null,
                    CREDENTIAL_DOMAIN
                ).addToDocument()
                yield()
            }
            if (Random.Default.nextBoolean()) {
                documentStore.deleteDocument(document.identifier)
            }
        }
        frontEndJob.cancelAndJoin()
    }

    class TestCredential: Credential {
        constructor(document: Document, asReplacementFor: String?, domain: String)
            : super(document, asReplacementFor, domain) {}

        constructor(document: Document) : super(document)

        override val credentialType: String
            get() = CREDENTIAL_TYPE

        override fun getClaims(documentTypeRepository: DocumentTypeRepository?): List<Claim> {
            throw NotImplementedError()
        }

        companion object {
            const val CREDENTIAL_TYPE = "keyless"
        }
    }

    class TestSecureAreaBoundCredential : SecureAreaBoundCredential {
        companion object {
            const val CREDENTIAL_TYPE = "key-bound"

            suspend fun create(
                document: Document,
                asReplacementForIdentifier: String?,
                domain: String,
                secureArea: SecureArea,
                createKeySettings: CreateKeySettings
            ): TestSecureAreaBoundCredential {
                return TestSecureAreaBoundCredential(
                    document,
                    asReplacementForIdentifier,
                    domain,
                    secureArea,
                ).apply {
                    generateKey(createKeySettings)
                }
            }
        }

        private constructor(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            secureArea: SecureArea,
        ) : super(document, asReplacementForIdentifier, domain, secureArea) {
        }

        constructor(
            document: Document
        ) : super(document) {}

        override val credentialType: String
            get() = CREDENTIAL_TYPE

        override fun getClaims(documentTypeRepository: DocumentTypeRepository?): List<Claim> {
            throw NotImplementedError()
        }
    }

    val Document.simpleMetadata: DocumentMetadata
        get() = metadata as DocumentMetadata
}
