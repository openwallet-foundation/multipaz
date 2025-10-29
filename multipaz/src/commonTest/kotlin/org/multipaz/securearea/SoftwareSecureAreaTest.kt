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
package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareKeyUnlockData
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SoftwareSecureAreaTest {
    @Test
    fun testEcKeyDeletion() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)

        // First create the key...
        ks.createKey(
            "testKey",
            CreateKeySettings()
        )
        val keyInfo = ks.getKeyInfo("testKey")

        // Now delete it...
        ks.deleteKey("testKey")

        // Now that we know the key doesn't exist, check that ecKeySign() throws
        try {
            ks.sign("testKey", byteArrayOf(1, 2))
            fail()
        } catch (e: IllegalArgumentException) {
            // Expected path.
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // Now delete it again, this should not fail.
        ks.deleteKey("testKey")
    }

    @Test
    fun testEcKeySigning() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        ks.createKey(
            "testKey",
            CreateKeySettings()
        )
        val keyInfo = ks.getKeyInfo("testKey")
        assertNotNull(keyInfo)
        assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        assertFalse(keyInfo.isPassphraseProtected)
        assertNull(keyInfo.passphraseConstraints)
        val dataToSign = byteArrayOf(4, 5, 6)
        val signature = try {
            ks.sign("testKey", dataToSign)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }
        Crypto.checkSignature(
            keyInfo.publicKey,
            dataToSign,
            Algorithm.ES256,
            signature
        )
    }

    @Test
    fun testEcKeyCreate() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        ks.createKey(
            "testKey",
            CreateKeySettings()
        )
        val keyInfo = ks.getKeyInfo("testKey")
        assertNotNull(keyInfo)
        assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        assertFalse(keyInfo.isPassphraseProtected)
        assertNull(keyInfo.passphraseConstraints)
    }
    
    @Test
    fun testEcKeyWithGenericCreateKeySettings() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        val challenge = byteArrayOf(1, 2, 3)
        ks.createKey("testKey", CreateKeySettings())
        val keyInfo = ks.getKeyInfo("testKey")
        assertNotNull(keyInfo)
        assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        assertFalse(keyInfo.isPassphraseProtected)
        assertNull(keyInfo.passphraseConstraints)

        // TODO: Check challenge.
        //val cert = keyInfo.attestation.certificates[0].javaX509Certificate
        //assertContentEquals(challenge, getChallenge(cert))
    }

    @Test
    fun testEcKeySigningWithKeyWithoutCorrectAlgorithm() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        ks.createKey(
            "testKey",
            SoftwareCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ECDH_P256)
                .build()
        )
        val dataToSign = byteArrayOf(4, 5, 6)
        try {
            ks.sign("testKey", dataToSign)
            fail("Signing shouldn't work with a key w/o purpose SIGN")
        } catch (e: IllegalArgumentException) {
            assertEquals("Key algorithm is not for Signing", e.message)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }
    }

    @Test
    fun testEcdh() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256)
        ks.createKey(
            "testKey",
            SoftwareCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ECDH_P256)
                .build()
        )
        val keyInfo = ks.getKeyInfo("testKey")
        assertNotNull(keyInfo)
        assertEquals(Algorithm.ECDH_P256, keyInfo.algorithm)
        assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        assertFalse(keyInfo.isPassphraseProtected)
        assertNull(keyInfo.passphraseConstraints)

        // First do the ECDH from the perspective of our side...
        val ourSharedSecret: ByteArray
        ourSharedSecret = try {
            ks.keyAgreement(
                "testKey",
                otherKey.publicKey,
            )
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // ...now do it from the perspective of the other side...
        val theirSharedSecret = Crypto.keyAgreement(otherKey, keyInfo.publicKey)

        // ... finally, check that both sides compute the same shared secret.
        assertContentEquals(theirSharedSecret, ourSharedSecret)
    }

    @Test
    fun testEcdhWithoutCorrectAlgorithm() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256)
        ks.createKey(
            "testKey",
            SoftwareCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ESP256)
                .build()
        )
        try {
            ks.keyAgreement(
                "testKey",
                otherKey.publicKey,
            )
            fail("ECDH shouldn't work with a key w/o purpose AGREE_KEY")
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        } catch (e: IllegalArgumentException) {
            assertEquals("Key algorithm is not for Key Agreement", e.message)
        }
    }

    @Test
    fun testEcKeySigningWithLockedKey() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        val passphrase = "verySekrit"
        val passphraseConstraints = PassphraseConstraints.PIN_SIX_DIGITS
        ks.createKey(
            "testKey",
            SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(true, passphrase, passphraseConstraints)
                .build()
        )
        val keyInfo = ks.getKeyInfo("testKey")
        assertNotNull(keyInfo)
        assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        assertTrue(keyInfo.isPassphraseProtected)
        assertNotNull(keyInfo.passphraseConstraints)
        assertEquals(keyInfo.passphraseConstraints, passphraseConstraints)
        val dataToSign = byteArrayOf(4, 5, 6)
        try {
            ks.sign("testKey", dataToSign)
            fail()
        } catch (e: KeyLockedException) {
            // This is the expected path.
        }

        // Try with the wrong passphrase. This should fail.
        try {
            withContext(MockKeyUnlockDataProvider(SoftwareKeyUnlockData("wrongPassphrase"))) {
                ks.sign("testKey", dataToSign)
            }
            fail()
        } catch (e: KeyLockedException) {
            // This is the expected path.
        }

        // ... and with the right passphrase. This should work.
        val signature = try {
            withContext(MockKeyUnlockDataProvider(SoftwareKeyUnlockData(passphrase))) {
                ks.sign(
                    "testKey",
                    dataToSign,
                )
            }
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // Verify the signature is correct.
        Crypto.checkSignature(
            keyInfo.publicKey,
            dataToSign,
            Algorithm.ES256,
            signature
        )
    }

    @Test
    fun testEcKeyCreationOverridesExistingAlias() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)
        ks.createKey(
            "testKey",
            CreateKeySettings()
        )
        val keyInfoOld = ks.getKeyInfo("testKey")
        val certChainOld = keyInfoOld.attestation
        ks.createKey(
            "testKey",
            CreateKeySettings()
        )
        val keyInfo = ks.getKeyInfo("testKey")
        val certChain = keyInfo.attestation
        val dataToSign = byteArrayOf(4, 5, 6)
        val signature = try {
            ks.sign("testKey", dataToSign)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // Check new key is a different cert chain.
        assertNotEquals(certChainOld, certChain)

        // Check new key is used to sign.
        Crypto.checkSignature(
            keyInfo.publicKey,
            dataToSign,
            Algorithm.ES256,
            signature
        )
    }

    @Test
    fun testEcKeySigningAllAlgorithms() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)

        val algorithms = Algorithm.entries.filter {
            it.fullySpecified && it.isSigning && Crypto.supportedCurves.contains(it.curve)
        }
        for (algorithm in algorithms) {
            ks.createKey(
                "testKey",
                SoftwareCreateKeySettings.Builder()
                    .setAlgorithm(algorithm)
                    .build()
            )
            val keyInfo = ks.getKeyInfo("testKey")
            assertNotNull(keyInfo)
            assertEquals(algorithm, keyInfo.algorithm)
            assertEquals(algorithm.curve, keyInfo.publicKey.curve)
            assertFalse(keyInfo.isPassphraseProtected)
            assertNull(keyInfo.passphraseConstraints)
            assertEquals(algorithm, keyInfo.algorithm)
            val dataToSign = byteArrayOf(4, 5, 6)
            val derSignature = try {
                ks.sign("testKey", dataToSign)
            } catch (e: KeyLockedException) {
                throw AssertionError(e)
            }
            Crypto.checkSignature(
                keyInfo.publicKey,
                dataToSign,
                algorithm,
                derSignature
            )
        }
    }

    @Test
    fun testEcKeyEcdhAllCurves() = runTest {
        val storage = EphemeralStorage()
        val ks = SoftwareSecureArea.create(storage)

        val algorithms = Algorithm.entries.filter {
            it.fullySpecified && it.isKeyAgreement && Crypto.supportedCurves.contains(it.curve)
        }
        for (algorithm in algorithms) {
            var otherKey = Crypto.createEcPrivateKey(algorithm.curve!!)

            ks.createKey(
                "testKey",
                SoftwareCreateKeySettings.Builder()
                    .setAlgorithm(algorithm)
                    .build()
            )
            val keyInfo = ks.getKeyInfo("testKey")
            assertNotNull(keyInfo)
            assertEquals(algorithm, keyInfo.algorithm)
            assertEquals(algorithm.curve, keyInfo.publicKey.curve)
            assertFalse(keyInfo.isPassphraseProtected)
            assertNull(keyInfo.passphraseConstraints)

            // First do the ECDH from the perspective of our side...
            var ourSharedSecret: ByteArray
            ourSharedSecret = try {
                ks.keyAgreement(
                    "testKey",
                    otherKey.publicKey,
                )
            } catch (e: KeyLockedException) {
                throw AssertionError(e)
            }

            // ...now do it from the perspective of the other side...
            var theirSharedSecret = Crypto.keyAgreement(otherKey, keyInfo.publicKey)

            // ... finally, check that both sides compute the same shared secret.
            assertContentEquals(theirSharedSecret, ourSharedSecret)
        }
    }

    @Test
    fun testBatchCreateKey() = runTest {
        val storage = EphemeralStorage()
        val sa = SoftwareSecureArea.create(storage)
        val batchCreateKeyResult = sa.batchCreateKey(10, CreateKeySettings(algorithm = Algorithm.ESP256))
        assertEquals(batchCreateKeyResult.keyInfos.size, 10)
        assertNull(batchCreateKeyResult.openid4vciKeyAttestationJws)
        for (n in 0..9) {
            val keyInfo = batchCreateKeyResult.keyInfos[n]
            val dataToSign = byteArrayOf(4, 5, 6)
            val signature = sa.sign(keyInfo.alias, dataToSign)
            Crypto.checkSignature(
                keyInfo.publicKey,
                dataToSign,
                keyInfo.algorithm,
                signature
            )
        }
    }

    private class MockKeyUnlockDataProvider(
        val keyUnlockData: KeyUnlockData
    ): KeyUnlockDataProvider {
        override suspend fun getKeyUnlockData(
            secureArea: SecureArea,
            alias: String,
            unlockReason: UnlockReason
        ): KeyUnlockData = keyUnlockData
    }
}
