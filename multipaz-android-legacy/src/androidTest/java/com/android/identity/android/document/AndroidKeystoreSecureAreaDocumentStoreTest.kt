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
package com.android.identity.android.document

import androidx.test.platform.app.InstrumentationRegistry
import com.android.identity.android.TestUtil
import org.multipaz.claim.Claim
import org.multipaz.context.initializeApplication
import org.multipaz.securearea.AndroidKeystoreCreateKeySettings
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.storage.Storage
import org.multipaz.storage.android.AndroidStorage
import org.multipaz.util.AndroidAttestationExtensionParser
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.document.buildDocumentStore

// See DocumentStoreTest in non-Android tests for main tests for DocumentStore. These
// tests are just for the Android-specific bits including attestation.
//
class AndroidKeystoreSecureAreaDocumentStoreTest {
    companion object {
        private const val CREDENTIAL_DOMAIN = "domain"
    }

    private lateinit var storage: Storage
    private lateinit var secureAreaRepository: SecureAreaRepository

    @Before
    fun setup() = runBlocking {
        initializeApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        storage = AndroidStorage(":memory:")
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(AndroidKeystoreSecureArea.create(storage))
            .build()
    }

    @Test
    fun testBasic() = runBlocking {
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository,
        ) {}
        val document = documentStore.createDocument()

        // Create pending credential and check its attestation
        val authKeyChallenge = ByteString(20, 21, 22)
        val secureArea =
            secureAreaRepository.getImplementation(AndroidKeystoreSecureArea.IDENTIFIER)
        val pendingCredential = TestSecureAreaBoundCredential.create(
            document,
            null,
            CREDENTIAL_DOMAIN,
            secureArea!!,
            AndroidKeystoreCreateKeySettings.Builder(authKeyChallenge).build()
        )
        Assert.assertFalse(pendingCredential.isCertified)
        val attestation = pendingCredential.getAttestation()
        val parser =
            AndroidAttestationExtensionParser(attestation.certChain!!.certificates[0])
        Assert.assertArrayEquals(
            authKeyChallenge.toByteArray(),
            parser.attestationChallenge
        )
        if (!TestUtil.isRunningOnEmulator) {
            Assert.assertEquals(
                AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                parser.keymasterSecurityLevel
            )
        }

        // Check we can load the document...
        val document2 = documentStore.lookupDocument(document.identifier)
        Assert.assertSame(document2, document)

        Assert.assertNull(documentStore.lookupDocument("nonExistingDocument"))
    }

    class TestSecureAreaBoundCredential : SecureAreaBoundCredential {
        companion object {
            const val CREDENTIAL_TYPE = "android-key-bound"
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
}
