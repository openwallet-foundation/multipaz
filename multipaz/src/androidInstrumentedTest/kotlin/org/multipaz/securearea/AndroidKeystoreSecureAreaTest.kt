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

import android.content.pm.PackageManager
import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.multipaz.android.TestUtil
import org.multipaz.context.initializeApplication
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.storage.android.AndroidStorage
import org.multipaz.util.AndroidAttestationExtensionParser
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import org.junit.Assert
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.IOException
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlinx.io.bytestring.ByteString
import org.multipaz.device.AndroidKeystoreSecurityLevel
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AndroidKeystoreSecureAreaTest {

    private lateinit var secureAreaProvider: SecureAreaProvider<AndroidKeystoreSecureArea>

    @Before
    fun setup() {
        initializeApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val storage = AndroidStorage(databasePath = null, clock = Clock.System)
        secureAreaProvider = SecureAreaProvider {
            AndroidKeystoreSecureArea.create(storage)
        }
    }

    @Test
    fun testEcKeyDeletion() = runTest {
        val settings = AndroidKeystoreCreateKeySettings.Builder(ByteString(1, 2, 3)).build()

        val ks = secureAreaProvider.get()

        // First create the key...
        ks.createKey("testKey", settings)

        // Now delete it...
        ks.deleteKey("testKey")

        // Now that we know the key doesn't exist, check that ecKeySign() throws
        try {
            ks.sign("testKey", byteArrayOf(1, 2))
            Assert.fail()
        } catch (e: IllegalArgumentException) {
            // Expected path.
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // Now delete it again, this should not fail.
        ks.deleteKey("testKey")
    }

    @Test
    fun testEcKeySigning() {
        testEcKeySigningHelper(false)
    }

    @Test
    fun testEcKeySigningStrongBox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
        testEcKeySigningHelper(true)
    }

    fun testEcKeySigningHelper(useStrongBox: Boolean) = runTest {
        val ks = secureAreaProvider.get()
        val challenge = ByteString(1, 2, 3)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .setUseStrongBox(useStrongBox)
            .build()
        ks.createKey("testKey", settings)
        val keyInfo = ks.getKeyInfo("testKey")
        val attestation = keyInfo.attestation
        Assert.assertTrue(attestation.certChain!!.certificates.isNotEmpty())
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        Assert.assertEquals(useStrongBox, keyInfo.isStrongBoxBacked)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(0.seconds, keyInfo.userAuthenticationTimeout)
        Assert.assertTrue(keyInfo.userAuthenticationTypes.isEmpty())
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)
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
    fun testEcKeySigningAuthBound() {
        testEcKeySigningAuthBoundHelper(false)
    }

    @Test
    fun testEcKeySigningAuthBoundStrongBox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
        testEcKeySigningAuthBoundHelper(true)
    }

    fun testEcKeySigningAuthBoundHelper(useStrongBox: Boolean) = runTest {
        Assume.assumeFalse(TestUtil.isRunningOnEmulator)
        val ks = secureAreaProvider.get()

        val challenge = ByteString(1, 2, 3)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .setUseStrongBox(useStrongBox)
            .setUserAuthenticationRequired(
                true,
                42.milliseconds,
                setOf(
                    UserAuthenticationType.LSKF,
                    UserAuthenticationType.BIOMETRIC
                )
            )
            .build()
        ks.createKey("testKey", settings)
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        Assert.assertEquals(useStrongBox, keyInfo.isStrongBoxBacked)
        Assert.assertTrue(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(42.milliseconds, keyInfo.userAuthenticationTimeout)
        Assert.assertEquals(
            setOf(UserAuthenticationType.LSKF, UserAuthenticationType.BIOMETRIC),
            keyInfo.userAuthenticationTypes
        )
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)
        val dataToSign = byteArrayOf(4, 5, 6)
        try {
            ks.sign("testKey", dataToSign)
            Assert.fail("Should not be reached")
        } catch (e: KeyLockedException) {
            /* expected path */
        }
    }

    @Test
    fun testEcKeyAuthenticationTypeLskf() = runTest {
        Assume.assumeFalse(TestUtil.isRunningOnEmulator)
        // setUserAuthenticationParameters() is only available on API 30 or later.
        //
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

        val ks = secureAreaProvider.get()
        val type = setOf(UserAuthenticationType.LSKF)
        val challenge = ByteString(1, 2, 3)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .setUserAuthenticationRequired(true, 42.milliseconds, type)
            .build()
        ks.createKey("testKey", settings)
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        Assert.assertFalse(keyInfo.isStrongBoxBacked)
        Assert.assertTrue(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(42.milliseconds, keyInfo.userAuthenticationTimeout)
        Assert.assertEquals(type, keyInfo.userAuthenticationTypes)
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)
        val dataToSign = byteArrayOf(4, 5, 6)
        try {
            ks.sign("testKey", dataToSign)
            Assert.fail("Should not be reached")
        } catch (e: KeyLockedException) {
            /* expected path */
        }
    }

    @Test
    fun testEcKeyAuthenticationTypeBiometric() = runTest {
        Assume.assumeFalse(TestUtil.isRunningOnEmulator)
        // setUserAuthenticationParameters() is only available on API 30 or later.
        //
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

        val ks = secureAreaProvider.get()
        val type = setOf(UserAuthenticationType.BIOMETRIC)
        val challenge = ByteString(1, 2, 3)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .setUserAuthenticationRequired(true, 42.milliseconds, type)
            .build()
        ks.createKey("testKey", settings)
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        Assert.assertFalse(keyInfo.isStrongBoxBacked)
        Assert.assertTrue(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(42.milliseconds, keyInfo.userAuthenticationTimeout)
        Assert.assertEquals(type, keyInfo.userAuthenticationTypes)
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)
        val dataToSign = byteArrayOf(4, 5, 6)
        try {
            ks.sign("testKey", dataToSign)
            Assert.fail("Should not be reached")
        } catch (e: KeyLockedException) {
            /* expected path */
        }
    }

    @Test
    fun testEcKeyAuthenticationTypeNone() {
        // setUserAuthenticationParameters() is only available on API 30 or later.
        //
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val type = setOf<UserAuthenticationType>()
        val challenge = ByteString(1, 2, 3)
        try {
            AndroidKeystoreCreateKeySettings.Builder(challenge)
                .setUserAuthenticationRequired(true, 42.milliseconds, type)
                .build()
            Assert.fail("Should not be reached")
        } catch (e: IllegalArgumentException) {
            /* expected path */
        }
    }

    // Curve 25519 on Android is currently broken, see b/282063229 for details. Ignore test for now.
    @Ignore
    @Test
    fun testEcKeySigningEd25519() = runTest {
        // ECDH is only available on Android 12 or later (only HW-backed on Keymint 1.0 or later)
        //
        // Also note it's not available on StrongBox.
        //
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val ks = secureAreaProvider.get()

        val challenge = ByteString(1, 2, 3)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .setAlgorithm(Algorithm.ED25519)
            .build()
        val keyInfo = ks.createKey("testKey", settings)
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.isNotEmpty())
        Assert.assertEquals(Algorithm.ED25519, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.ED25519, keyInfo.publicKey.curve)
        Assert.assertFalse(keyInfo.isStrongBoxBacked)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(0.seconds, keyInfo.userAuthenticationTimeout)
        Assert.assertTrue(keyInfo.userAuthenticationTypes.isEmpty())
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)
        val dataToSign = byteArrayOf(4, 5, 6)
        val signature = try {
            ks.sign("testKey", dataToSign)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }
        Crypto.checkSignature(
            keyInfo.publicKey,
            dataToSign,
            Algorithm.EDDSA,
            signature
        )
    }

    @Test
    @Throws(IOException::class)
    fun testEcKeySigningWithKeyWithoutCorrectPurpose() = runTest {
        // According to https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_HARDWARE_KEYSTORE
        // ECDH is available if FEATURE_HARDWARE_KEYSTORE is >= 100.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_HARDWARE_KEYSTORE, 100
            )
        )

        val ks = secureAreaProvider.get()
        ks.createKey(
            "testKey",
            AndroidKeystoreCreateKeySettings.Builder(ByteString(1, 2, 3))
                .setAlgorithm(Algorithm.ECDH_P256)
                .build()
        )
        val dataToSign = byteArrayOf(4, 5, 6)
        try {
            ks.sign("testKey", dataToSign)
            Assert.fail("Signing shouldn't work with a key w/o KEY_PURPOSE_SIGN")
        } catch (e: IllegalArgumentException) {
            // Expected path.
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }
    }

    @Test
    fun testEcdh() {
        // According to https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_HARDWARE_KEYSTORE
        // ECDH is available if FEATURE_HARDWARE_KEYSTORE is >= 100.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_HARDWARE_KEYSTORE, 100
            )
        )
        testEcdhHelper(false)
    }

    @Test
    fun testEcdhStrongBox() {
        // According to https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_STRONGBOX_KEYSTORE
        // ECDH is available if FEATURE_STRONGBOX_KEYSTORE is >= 100.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_STRONGBOX_KEYSTORE, 100
            )
        )
        testEcdhHelper(true)
    }

    fun testEcdhHelper(useStrongBox: Boolean) = runTest {
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val ks = secureAreaProvider.get()
        ks.createKey(
            "testKey",
            AndroidKeystoreCreateKeySettings.Builder(ByteString(1, 2, 3))
                .setUseStrongBox(useStrongBox)
                .setAlgorithm(Algorithm.ECDH_P256)
                .build()
        )
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.size >= 1)
        Assert.assertEquals(Algorithm.ECDH_P256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        Assert.assertEquals(useStrongBox, keyInfo.isStrongBoxBacked)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(0.seconds, keyInfo.userAuthenticationTimeout)
        Assert.assertTrue(keyInfo.userAuthenticationTypes.isEmpty())
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)

        // First do the ECDH from the perspective of our side...
        val ourSharedSecret: ByteArray
        ourSharedSecret = try {
            ks.keyAgreement("testKey", otherKey.publicKey)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // ...now do it from the perspective of the other side...
        val theirSharedSecret = Crypto.keyAgreement(otherKey, keyInfo.publicKey)

        // ... finally, check that both sides compute the same shared secret.
        Assert.assertArrayEquals(theirSharedSecret, ourSharedSecret)
    }

    // Curve 25519 on Android is currently broken, see b/282063229 for details. Ignore test for now.
    @Ignore
    @Test
    fun testEcdhX25519() = runTest {
        // ECDH is only available on Android 12 or later (only HW-backed on Keymint 1.0 or later)
        //
        // Also note it's not available on StrongBox.
        //
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        val ks = secureAreaProvider.get()

        val otherKey = Crypto.createEcPrivateKey(EcCurve.X25519)
        ks.createKey(
            "testKey",
            AndroidKeystoreCreateKeySettings.Builder(ByteString(1, 2, 3))
                .setAlgorithm(Algorithm.ECDH_X25519)
                .build()
        )
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.size >= 1)
        Assert.assertEquals(Algorithm.ECDH_X25519, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.X25519, keyInfo.publicKey.curve)
        Assert.assertFalse(keyInfo.isStrongBoxBacked)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(0.seconds, keyInfo.userAuthenticationTimeout)
        Assert.assertTrue(keyInfo.userAuthenticationTypes.isEmpty())
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)

        // First do the ECDH from the perspective of our side...
        val ourSharedSecret: ByteArray = try {
            ks.keyAgreement("testKey", otherKey.publicKey)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // ...now do it from the perspective of the other side...
        val theirSharedSecret = Crypto.keyAgreement(otherKey, keyInfo.publicKey)

        // ... finally, check that both sides compute the same shared secret.
        Assert.assertArrayEquals(theirSharedSecret, ourSharedSecret)
    }

    @Test
    @Throws(IOException::class)
    fun testEcdhWithoutCorrectPurpose() = runTest {
        // According to https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_HARDWARE_KEYSTORE
        // ECDH is available if FEATURE_HARDWARE_KEYSTORE is >= 100.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_HARDWARE_KEYSTORE, 100
            )
        )
        val ks = secureAreaProvider.get()
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256)
        ks.createKey(
            "testKey",
            AndroidKeystoreCreateKeySettings.Builder(ByteString(1, 2, 3))
                .setAlgorithm(Algorithm.ESP256)
                .build()
        )
        try {
            ks.keyAgreement("testKey", otherKey.publicKey)
            Assert.fail("ECDH shouldn't work with a key w/o KEY_PURPOSE_AGREE_KEY")
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        } catch (e: IllegalArgumentException) {
            // Expected path.
        }
    }

    @Test
    fun testEcKeyCreationDuplicateAlias() = runTest {
        val ks = secureAreaProvider.get()
        val challenge = ByteString(1, 2, 3)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge).build()
        ks.createKey("testKey", settings)
        val keyInfoOld: KeyInfo = ks.getKeyInfo("testKey")
        Assert.assertTrue(keyInfoOld.attestation.certChain!!.certificates.size >= 1)
        ks.createKey("testKey", settings)
        val keyInfo: KeyInfo = ks.getKeyInfo("testKey")
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.size >= 1)
        val dataToSign = byteArrayOf(4, 5, 6)
        val signature = try {
            ks.sign("testKey", dataToSign)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // Check new key is a different cert chain.
        Assert.assertNotEquals(
            keyInfoOld.attestation.certChain!!.certificates[0].ecPublicKey,
            keyInfo.attestation.certChain!!.certificates[0].ecPublicKey
        )

        // Check new key is used to sign.
        Crypto.checkSignature(
            keyInfo.publicKey,
            dataToSign,
            Algorithm.ES256,
            signature
        )
    }

    @Test
    @Throws(IOException::class)
    fun testAttestation() {
        testAttestationHelper(false)
    }

    @Test
    @Throws(IOException::class)
    fun testAttestationStrongBox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
        testAttestationHelper(true)
    }

    @Throws(IOException::class)
    fun testAttestationHelper(useStrongBox: Boolean) = runTest {
        val ks = secureAreaProvider.get()
        val validFromCalendar: Calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        validFromCalendar[2023, 5, 15, 0, 0] = 0
        val validUntilCalendar: Calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        validUntilCalendar[2024, 5, 15, 0, 0] = 0
        val validFrom = fromEpochMilliseconds(validFromCalendar.timeInMillis)
        val validUntil = fromEpochMilliseconds(validUntilCalendar.timeInMillis)
        val challenge = ByteString(1, 2, 3)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .setUseStrongBox(useStrongBox)
            .setValidityPeriod(validFrom, validUntil)
            .build()
        ks.deleteKey("testKey")
        ks.createKey("testKey", settings)

        // On Android, at least three certificates are present in the chain.
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.size >= 3)
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        Assert.assertEquals(useStrongBox, keyInfo.isStrongBoxBacked)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(0.seconds, keyInfo.userAuthenticationTimeout)
        Assert.assertTrue(keyInfo.userAuthenticationTypes.isEmpty())
        Assert.assertNull(keyInfo.attestKeyAlias)
        Assert.assertEquals(validFrom, keyInfo.validFrom)
        Assert.assertEquals(validUntil, keyInfo.validUntil)

        // Check the attestation extension
        val parser = AndroidAttestationExtensionParser(
            keyInfo.attestation.certChain!!.certificates[0]
        )
        Assert.assertArrayEquals(challenge.toByteArray(), parser.attestationChallenge)
        val securityLevel = parser.keymasterSecurityLevel
        if (!TestUtil.isRunningOnEmulator) {
            Assert.assertEquals(
                if (useStrongBox) {
                    AndroidKeystoreSecurityLevel.STRONG_BOX
                } else {
                    AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT
                },
                securityLevel
            )
        }

        // tag 400: https://source.android.com/docs/security/features/keystore/tags#active_datetime
        Assert.assertEquals(
            validFrom.toEpochMilliseconds(),
            parser.getSoftwareAuthorizationLong(400)
        )

        // tag 401: https://source.android.com/docs/security/features/keystore/tags#origination_expire_datetime
        Assert.assertEquals(
            validUntil.toEpochMilliseconds(),
            parser.getSoftwareAuthorizationLong(401)
        )
    }

    @Test
    @Throws(IOException::class)
    fun testAttestKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY))
        testAttestKeyHelper(false)
    }

    @Test
    @Throws(IOException::class)
    fun testAttestKeyStrongBox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY))
        Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
        testAttestKeyHelper(true)
    }

    @Throws(IOException::class)
    fun testAttestKeyHelper(useStrongBox: Boolean) = runTest {
        val ks = secureAreaProvider.get()

        val attestKeyAlias = "icTestAttestKey"
        ks.deleteKey(attestKeyAlias)
        val attestKeyInfo = ks.createKey(attestKeyAlias,
            AndroidKeystoreCreateKeySettings.Builder(ByteString(1, 2, 3))
                .setAlgorithm(Algorithm.ANDROID_KEYSTORE_ATTEST_KEY)
                .setUseStrongBox(useStrongBox)
                .build()
        )
        val challenge = ByteString(4, 5, 6, 7)
        val settings = AndroidKeystoreCreateKeySettings.Builder(challenge)
            .setAttestKeyAlias(attestKeyAlias)
            .setUseStrongBox(useStrongBox)
            .build()
        ks.deleteKey("testKey")
        ks.createKey("testKey", settings)
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.isNotEmpty())
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        Assert.assertEquals(useStrongBox, keyInfo.isStrongBoxBacked)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(0.seconds, keyInfo.userAuthenticationTimeout)
        Assert.assertTrue(keyInfo.userAuthenticationTypes.isEmpty())
        Assert.assertEquals(attestKeyAlias, keyInfo.attestKeyAlias)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)

        // When using an attest key, the first certificate is signed by the AttestKey
        keyInfo.attestation.certChain.certificates[0].verify(
            attestKeyInfo.publicKey
        )
        // The rest of the chain is the attestation for AttestKey
        assertEquals(
            keyInfo.attestation.certChain.certificates.subList(1, keyInfo.attestation.certChain.certificates.size),
            attestKeyInfo.attestation.certChain!!.certificates
        )

        // Check the attestation extension
        val parser = AndroidAttestationExtensionParser(
            keyInfo.attestation.certChain!!.certificates[0]
        )
        Assert.assertArrayEquals(challenge.toByteArray(), parser.attestationChallenge)
        val securityLevel = parser.keymasterSecurityLevel
        if (!TestUtil.isRunningOnEmulator) {
            Assert.assertEquals(
                if (useStrongBox) {
                    AndroidKeystoreSecurityLevel.STRONG_BOX
                } else {
                    AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT
                },
                securityLevel
            )
        }
    }

    @Test
    @Throws(IOException::class)
    fun testUsingGenericCreateKeySettings() = runTest {
        val ks = secureAreaProvider.get()
        val challenge = ByteString(1, 2, 3)
        ks.createKey("testKey", CreateKeySettings(Algorithm.ESP256, challenge))
        val keyInfo = ks.getKeyInfo("testKey")
        Assert.assertNotNull(keyInfo)
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertEquals(EcCurve.P256, keyInfo.publicKey.curve)
        val parser = AndroidAttestationExtensionParser(
            keyInfo.attestation.certChain!!.certificates[0]
        )
        Assert.assertArrayEquals(challenge.toByteArray(), parser.attestationChallenge)
        ks.deleteKey("testKey")
    }
}
