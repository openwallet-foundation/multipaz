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
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItemDateTimeString
import kotlin.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class DocumentUtilTest {
    private lateinit var storage: Storage
    private lateinit var secureAreaRepository: SecureAreaRepository

    @BeforeTest
    fun setup() = runTest {
        storage = EphemeralStorage()
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(SoftwareSecureArea.create(storage))
            .build()
    }

    @Test
    fun managedCredentialHelper() = runTest {
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {
            addCredentialImplementation(TestSecureAreaBoundCredential.CREDENTIAL_TYPE) { document ->
                TestSecureAreaBoundCredential(document)
            }
        }
        val secureArea: SecureArea =
            secureAreaRepository.getImplementation(SoftwareSecureArea.IDENTIFIER)!!
        val document = documentStore.createDocument()
        assertEquals(0, document.getCertifiedCredentials().size.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())
        val authKeySettings = CreateKeySettings()
        val numCreds = 10
        val maxUsesPerCred = 5
        val minValidTime = 10.milliseconds
        var numCredsCreated: Int
        val managedCredDomain = "managedCredentials"

        // Start the process at time 100 and certify all those credentials so they're
        // valid until time 200.
        numCredsCreated = DocumentUtil.managedCredentialHelper(
            document,
            managedCredDomain,
            createCredential = { credentialToReplace ->
                TestSecureAreaBoundCredential.create(
                    document,
                    credentialToReplace,
                    managedCredDomain,
                    secureArea,
                    authKeySettings
                )
            },
            Instant.fromEpochMilliseconds(100),
            numCreds,
            maxUsesPerCred,
            minValidTime,
            false
        )
        assertEquals(numCreds.toLong(), numCredsCreated.toLong())
        assertEquals(
            numCreds.toLong(),
            document.getPendingCredentials().size.toLong()
        )
        var count = 0
        for (pak in document.getPendingCredentials()) {
            val issuerData = TestIssuerData(
                validFrom = Instant.fromEpochMilliseconds(100),
                validUntil = Instant.fromEpochMilliseconds(200),
                major = 0,
                minor = count++
            )
            pak.certify(issuerData.serialize())
        }
        // We should now have |numCreds| certified credentials and none pending
        assertEquals(0, document.getPendingCredentials().size.toLong())
        assertEquals(numCreds.toLong(), document.getCertifiedCredentials().size.toLong())

        // Certifying again at this point should not make a difference.
        numCredsCreated = DocumentUtil.managedCredentialHelper(
            document,
            managedCredDomain,
            createCredential = { credentialToReplace ->
                TestSecureAreaBoundCredential.create(
                    document,
                    credentialToReplace,
                    managedCredDomain,
                    secureArea,
                    authKeySettings
                )
            },
            Instant.fromEpochMilliseconds(100),
            numCreds,
            maxUsesPerCred,
            minValidTime,
            false
        )
        assertEquals(0, numCredsCreated.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())

        // Use up until just before the limit, and check it doesn't make a difference
        for (ak in document.getCertifiedCredentials()) {
            for (n in 0 until maxUsesPerCred - 1) {
                ak.increaseUsageCount()
            }
        }
        numCredsCreated = DocumentUtil.managedCredentialHelper(
            document,
            managedCredDomain,
            createCredential = { credentialToReplace ->
                TestSecureAreaBoundCredential.create(
                    document,
                    credentialToReplace,
                    managedCredDomain,
                    secureArea,
                    authKeySettings
                )
            },
            Instant.fromEpochMilliseconds(100),
            numCreds,
            maxUsesPerCred,
            minValidTime,
            false
        )
        assertEquals(0, numCredsCreated.toLong())
        assertEquals(0, document.getPendingCredentials().size.toLong())

        // For the first 5, use one more time and check replacements are generated for those
        // Let the replacements expire just a tad later
        count = 0
        for (ak in document.getCertifiedCredentials()) {
            ak.increaseUsageCount()
            if (++count >= 5) {
                break
            }
        }
        numCredsCreated = DocumentUtil.managedCredentialHelper(
            document,
            managedCredDomain,
            createCredential = { credentialToReplace ->
                TestSecureAreaBoundCredential.create(
                    document,
                    credentialToReplace,
                    managedCredDomain,
                    secureArea,
                    authKeySettings
                )
            },
            Instant.fromEpochMilliseconds(100),
            numCreds,
            maxUsesPerCred,
            minValidTime,
            false
        )
        assertEquals(5, numCredsCreated.toLong())
        assertEquals(5, document.getPendingCredentials().size.toLong())
        count = 0
        for (pak in document.getPendingCredentials()) {
            assertEquals(managedCredDomain, pak.domain)
            val issuerData = TestIssuerData(
                validFrom = Instant.fromEpochMilliseconds(100),
                validUntil = Instant.fromEpochMilliseconds(210),
                major = 1,
                minor = count++
            )
            pak.certify(issuerData.serialize())
        }
        // We should now have |numCreds| certified credentials and none pending
        assertEquals(0, document.getPendingCredentials().size.toLong())
        assertEquals(numCreds.toLong(), document.getCertifiedCredentials().size.toLong())
        // Check that the _right_ ones were removed by inspecting issuer-provided data.
        // We rely on some implementation details on how ordering works... also cross-reference
        // with data passed into certify() functions above.
        count = 0
        val expectedData = arrayOf(
            Pair(0, 5),
            Pair(0, 6),
            Pair(0, 7),
            Pair(0, 8),
            Pair(0, 9),
            Pair(1, 0),
            Pair(1, 1),
            Pair(1, 2),
            Pair(1, 3),
            Pair(1, 4)
        )
        for (cred in document.getCertifiedCredentials()) {
            val data = TestIssuerData.parse(cred.issuerProvidedData)
            assertEquals(expectedData[count++], Pair(data.major, data.minor))
        }

        // Now move close to the expiration date of the original five credentials.
        // This should trigger just them for replacement
        numCredsCreated = DocumentUtil.managedCredentialHelper(
            document,
            managedCredDomain,
            createCredential = { credentialToReplace ->
                val credential = TestSecureAreaBoundCredential.create(
                    document,
                    credentialToReplace,
                    managedCredDomain,
                    secureArea,
                    authKeySettings
                )
                credential
            },
            Instant.fromEpochMilliseconds(195),
            numCreds,
            maxUsesPerCred,
            minValidTime,
            false
        )
        assertEquals(5, numCredsCreated.toLong())
        assertEquals(5, document.getPendingCredentials().size.toLong())
        count = 0
        for (pak in document.getPendingCredentials()) {
            assertEquals(managedCredDomain, pak.domain)
            val issuerData = TestIssuerData(
                validFrom = Instant.fromEpochMilliseconds(100),
                validUntil = Instant.fromEpochMilliseconds(210),
                major = 2,
                minor = count++
            )
            pak.certify(issuerData.serialize())
        }
        // We should now have |numCreds| certified credentials and none pending
        assertEquals(0, document.getPendingCredentials().size.toLong())
        assertEquals(numCreds.toLong(), document.getCertifiedCredentials().size.toLong())
        // Check that the _right_ ones were removed by inspecting issuer-provided data.
        // We rely on some implementation details on how ordering works... also cross-reference
        // with data passed into certify() functions above.
        count = 0
        val newExpectedData = arrayOf(
            Pair(1, 0),
            Pair(1, 1),
            Pair(1, 2),
            Pair(1, 3),
            Pair(1, 4),
            Pair(2, 0),
            Pair(2, 1),
            Pair(2, 2),
            Pair(2, 3),
            Pair(2, 4)
        )
        for (credential in document.getCertifiedCredentials()) {
            val data = TestIssuerData.parse(credential.issuerProvidedData)
            assertEquals(newExpectedData[count++], Pair(data.major, data.minor))
        }
    }
    data class TestIssuerData(
        val validFrom: Instant,
        val validUntil: Instant,
        val major: Int = 0,
        val minor: Int = 0
    ) {
        fun serialize(): ByteString = ByteString(Cbor.encode(buildCborMap {
            put("from", validFrom.toDataItemDateTimeString())
            put("until", validUntil.toDataItemDateTimeString())
            put("major", major)
            put("minor", minor)
        }))

        companion object {
            fun parse(data: ByteString): TestIssuerData {
                val map = Cbor.decode(data.toByteArray())
                return TestIssuerData(
                    validFrom = map["from"].asDateTimeString,
                    validUntil = map["until"].asDateTimeString,
                    major = map["major"].asNumber.toInt(),
                    minor = map["minor"].asNumber.toInt(),
                )
            }
        }
    }

    class TestSecureAreaBoundCredential : SecureAreaBoundCredential {
        companion object {
            const val CREDENTIAL_TYPE = "test-key-bound"

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
        ) : super(document, asReplacementForIdentifier, domain, secureArea)

        constructor(
            document: Document
        ) : super(document)

        override val credentialType: String
            get() = CREDENTIAL_TYPE

        override suspend fun getClaims(documentTypeRepository: DocumentTypeRepository?): List<Claim> {
            throw NotImplementedError()
        }

        override suspend fun extractValidityFromIssuerData(): Pair<Instant, Instant> {
            val parsed = TestIssuerData.parse(issuerProvidedData)
            return Pair(parsed.validFrom, parsed.validUntil)
        }
    }

}
