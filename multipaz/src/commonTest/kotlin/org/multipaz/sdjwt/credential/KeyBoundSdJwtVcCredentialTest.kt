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
package org.multipaz.sdjwt.credential

import kotlinx.coroutines.test.runTest
import org.multipaz.credential.CredentialLoader
import org.multipaz.crypto.Algorithm
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.securearea.BatchCreateKeyResult
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyBoundSdJwtVcCredentialTest {
    private lateinit var credentialLoader: CredentialLoader
    private lateinit var documentStore: DocumentStore
    private lateinit var secureAreaRepository: SecureAreaRepository
    private lateinit var storage: EphemeralStorage
    private lateinit var secureArea: TestSecureArea

    @BeforeTest
    fun setup() = runTest {
        storage = EphemeralStorage()
        secureArea = TestSecureArea(
            delegate = SoftwareSecureArea.create(storage)
        )
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(secureArea)
            .build()
        documentStore = buildDocumentStore(storage = storage, secureAreaRepository = secureAreaRepository) {}
    }

    @Test
    fun testCreateBatch() = runTest {
        val document = documentStore.createDocument()

        // Create a key with a specific alias
        val createKeySettings = SoftwareCreateKeySettings.Builder()
            .setAlgorithm(Algorithm.ESP256)
            .setPassphraseRequired(false, null, null)
            .build()

        // Create first MdocCredential with this key
        val vct = "the_vct"
        val (originalCredentials, openid4vciKeyAttestation) = KeyBoundSdJwtVcCredential.createBatch(
            numberOfCredentials = 2,
            document = document,
            domain = "domain",
            secureArea = secureArea,
            vct = vct,
            createKeySettings = createKeySettings
        )

        // Assert that the SecureArea.batchCreateKey method was called with the correct parameters
        assertTrue(secureArea.batchCreateKeyCalled)
        assertEquals(2, secureArea.keyCount)
        assertEquals(createKeySettings, secureArea.createKeySettings)

        // Assert that the credentials were created correctly

        assertEquals(2, originalCredentials.size)
        assertNull(openid4vciKeyAttestation)

        // assert that credential is added to document
        val credentials = document.getCredentials()
        // assert that document credentials contain all original credentials
        assertTrue(credentials.containsAll(originalCredentials))
    }

    /**
     * A test implementation of [SecureArea] that records the parameters passed to
     * [SecureArea.batchCreateKey].
     */
    class TestSecureArea(
        private val delegate: SecureArea
    ) : SecureArea by delegate {

        var batchCreateKeyCalled = false
            private set
        var keyCount = 0
            private set
        var createKeySettings: SoftwareCreateKeySettings? = null
            private set

        override suspend fun batchCreateKey(
            numKeys: Int,
            createKeySettings: CreateKeySettings
        ): BatchCreateKeyResult {
            batchCreateKeyCalled = true
            keyCount = numKeys
            this.createKeySettings = createKeySettings as SoftwareCreateKeySettings
            return super.batchCreateKey(numKeys, createKeySettings)
        }
    }
}