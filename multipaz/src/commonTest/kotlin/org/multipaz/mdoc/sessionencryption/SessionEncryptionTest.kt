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
package org.multipaz.mdoc.sessionencryption

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.Constants
import org.multipaz.util.fromHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionEncryptionTest {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testReaderAgainstVectors() = runTest {
        val eReaderKey: EcPrivateKey = EcPrivateKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex()
        )

        // Strip the #6.24 tag since our APIs expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes = 
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val sessionTranscript = Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor
        val deviceEngagementBytes = sessionTranscript[0]
        val encodedDeviceEngagement = deviceEngagementBytes.asTagged.asBstr
        val engagementParser = EngagementParser(encodedDeviceEngagement)
        val engagement = engagementParser.parse()
        val eDeviceKey = engagement.eSenderKey
        val sessionEncryption = SessionEncryption(
            MdocRole.MDOC_READER,
            eReaderKey,
            eDeviceKey,
            Cbor.encode(sessionTranscript)
        )

        // Check that encryption works.
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_ESTABLISHMENT.fromHex(),
            sessionEncryption.encryptMessage(
                TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex(),
                null
            )
        )

        // Check that decryption works.
        var result = sessionEncryption.decryptMessage(
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_DATA.fromHex()
        )
        assertNull(result.second)
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex(),
            result.first
        )

        // Check that we parse status correctly.
        result = sessionEncryption.decryptMessage(
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TERMINATION.fromHex()
        )
        assertEquals(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION, result.second)
        assertNull(result.first)

        // Check we can generate messages with status.
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TERMINATION.fromHex(),
            sessionEncryption.encryptMessage(
                null,
                Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
            )
        )
    }

    @Test
    fun testDeviceAgainstVectors() = runTest {
        val eDeviceKey: EcPrivateKey = EcPrivateKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_D.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_Y.fromHex()
        )

        // Strip the #6.24 tag since our API expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes = 
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val sessionTranscript = Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor
        val sessionEstablishment = TestVectors.ISO_18013_5_ANNEX_D_SESSION_ESTABLISHMENT.fromHex()
        val eReaderKey = Cbor.decode(sessionEstablishment)["eReaderKey"]
            .asTaggedEncodedCbor.asCoseKey.ecPublicKey
        val sessionEncryptionDevice = SessionEncryption(
            MdocRole.MDOC,
            eDeviceKey,
            eReaderKey,
            Cbor.encode(sessionTranscript)
        )

        // Check that decryption works.
        var result = sessionEncryptionDevice.decryptMessage(
            sessionEstablishment
        )
        assertNull(result.second)
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex(),
            result.first
        )

        // Check that encryption works.
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_DATA.fromHex(),
            sessionEncryptionDevice.encryptMessage(
                TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex(),
                null
            )
        )

        // Check that we parse status correctly.
        result = sessionEncryptionDevice.decryptMessage(
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TERMINATION.fromHex()
        )
        assertEquals(
            Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            result.second
        )
        assertNull(result.first)

        // Check we can generate messages with status.
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TERMINATION.fromHex(),
            sessionEncryptionDevice.encryptMessage(null, 20L)
        )
    }

    private suspend fun testCurve(curve: EcCurve) {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return
        }

        val eReaderKey = Crypto.createEcPrivateKey(curve)
        val eDeviceKey = Crypto.createEcPrivateKey(curve)
        val encodedSessionTranscript = byteArrayOf(1, 2, 3)
        val sessionEncryptionReader = SessionEncryption(
            MdocRole.MDOC_READER,
            eReaderKey,
            eDeviceKey.publicKey,
            encodedSessionTranscript
        )
        sessionEncryptionReader.setSendSessionEstablishment(false)
        val sessionEncryptionHolder = SessionEncryption(
            MdocRole.MDOC,
            eDeviceKey,
            eReaderKey.publicKey,
            encodedSessionTranscript
        )
        for (i in 1..3) {
            // have reader generate sessionEstablishment and check holder decryption of sessionEstablishment
            val mdocRequest = sessionEncryptionReader.encryptMessage(
                TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex(),
                null
            )
            var result = sessionEncryptionHolder.decryptMessage(
                mdocRequest
            )
            assertNull(result.second)
            assertContentEquals(
                TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex(),
                result.first
            )
            assertEquals(i.toLong(), sessionEncryptionReader.numMessagesEncrypted.toLong())
            assertEquals(i.toLong(), sessionEncryptionHolder.numMessagesDecrypted.toLong())

            // have holder generate deviceResponse and check reader decryption of deviceResponse
            val deviceResponse = sessionEncryptionHolder.encryptMessage(
                TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex(),
                null
            )
            result = sessionEncryptionReader.decryptMessage(deviceResponse)
            assertNull(result.second)
            assertContentEquals(
                TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex(),
                result.first
            )
            assertEquals(i.toLong(), sessionEncryptionHolder.numMessagesEncrypted.toLong())
            assertEquals(i.toLong(), sessionEncryptionReader.numMessagesDecrypted.toLong())
        }
    }

    @Test
    fun testP256() = runTest {
        testCurve(EcCurve.P256)
    }

    @Test
    fun testP384() = runTest {
        testCurve(EcCurve.P384)
    }

    @Test
    fun testP521() = runTest {
        testCurve(EcCurve.P521)
    }

    @Test
    fun testBrainpool256() = runTest {
        testCurve(EcCurve.BRAINPOOLP256R1)
    }

    @Test
    fun testBrainpool320() = runTest {
        testCurve(EcCurve.BRAINPOOLP320R1)
    }

    @Test
    fun testBrainpool384() = runTest {
        testCurve(EcCurve.BRAINPOOLP384R1)
    }

    @Test
    fun testBrainpool521() = runTest {
        testCurve(EcCurve.BRAINPOOLP512R1)
    }

    @Test
    fun testX25519() = runTest {
        testCurve(EcCurve.X25519)
    }

    @Test
    fun testX448() = runTest {
        testCurve(EcCurve.X448)
    }
}
