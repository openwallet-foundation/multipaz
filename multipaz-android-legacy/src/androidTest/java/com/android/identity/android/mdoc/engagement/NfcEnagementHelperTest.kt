/*
 * Copyright 2022 The Android Open Source Project
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
package com.android.identity.android.mdoc.engagement

import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.identity.android.legacy.Crypto
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransportOptions
import com.android.identity.android.util.NfcUtil
import kotlinx.io.bytestring.buildByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Simple
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodWifiAware
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.util.UUID
import org.multipaz.util.toHex
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.multipaz.util.appendUInt16
import org.multipaz.util.getUInt16
import java.security.Security
import java.util.Arrays
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class NfcEnagementHelperTest {
    // Do NOT add BouncyCastle at setup time - we want to run tests against the normal AndroidOpenSSL JCA provider

    @Test
    @SmallTest
    @Throws(Exception::class)
    fun testStaticHandover() {
        val helper: NfcEngagementHelper?
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val listener: NfcEngagementHelper.Listener = object : NfcEngagementHelper.Listener {
            override fun onTwoWayEngagementDetected() {}
            override fun onHandoverSelectMessageSent() {}
            override fun onDeviceConnecting() {}
            override fun onDeviceConnected(transport: DataTransport) {}
            override fun onError(error: Throwable) {}
        }
        val executor: Executor = Executors.newSingleThreadExecutor()
        val builder = NfcEngagementHelper.Builder(
            context,
            eDeviceKey.publicKey,
            DataTransportOptions.Builder().build(),
            listener,
            executor
        )

        // Include all ConnectionMethods that can exist in OOB data
        val connectionMethods: MutableList<MdocConnectionMethod> = ArrayList()
        val bleUuid = UUID.randomUUID()
        connectionMethods.add(
            MdocConnectionMethodBle(
                true,
                true,
                bleUuid,
                bleUuid
            )
        )
        connectionMethods.add(
            MdocConnectionMethodNfc(
                0xffff,
                0xffff
            )
        )
        connectionMethods.add(
            MdocConnectionMethodWifiAware(
                null,
                null,
                null,
                null
            )
        )
        builder.useStaticHandover(connectionMethods)
        helper = builder.build()
        helper.testingDoNotStartTransports()

        // Select Type 4 Tag NDEF app
        val ndefAppId = NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION
        var responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduApplicationSelect(ndefAppId)
        )
        Assert.assertNotNull(responseApdu)
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu)

        // Select CC file
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID)
        )
        Assert.assertNotNull(responseApdu)
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu)

        // Get CC file
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduReadBinary(0, 15)
        )
        Assert.assertNotNull(responseApdu)
        // The response is the CC file followed by STATUS_WORD_OK. Keep in sync with
        // NfcEngagementHelper.handleSelectFile() for the contents.
        Assert.assertEquals("000f207fff7fff0406e1047fff00ff9000", responseApdu.toHex())

        // Select NDEF file
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID)
        )
        Assert.assertNotNull(responseApdu)
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu)

        // Get length of Initial NDEF message
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduReadBinary(0, 2)
        )
        Assert.assertNotNull(responseApdu)
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(4, responseApdu.size.toLong())
        Assert.assertEquals(0x9000, responseApdu.getUInt16(2).toInt())
        val initialNdefMessageSize = responseApdu.getUInt16(0).toInt()

        // Read Initial NDEF message
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduReadBinary(2, initialNdefMessageSize)
        )
        Assert.assertNotNull(responseApdu)
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals((initialNdefMessageSize + 2).toLong(), responseApdu.size.toLong())
        Assert.assertEquals(0x9000, responseApdu.getUInt16(initialNdefMessageSize).toInt())

        val initialNdefMessage = Arrays.copyOf(responseApdu, responseApdu.size - 2)

        // The Initial NDEF message should contain Handover Select. Check this.
        val hs = NfcUtil.parseHandoverSelectMessage(initialNdefMessage)
        Assert.assertNotNull(hs)
        val parser = EngagementParser(hs!!.encodedDeviceEngagement)
        // Check the returned DeviceEngagement
        val e = parser.parse()
        Assert.assertEquals("1.0", e.version)
        Assert.assertEquals(0, e.originInfos.size.toLong())
        Assert.assertEquals(eDeviceKey.publicKey, e.eSenderKey)
        Assert.assertEquals(0, e.connectionMethods.size.toLong())
        // Check the synthesized ConnectionMethod (from returned OOB data in HS)
        Assert.assertEquals(connectionMethods.size.toLong(), hs.connectionMethods.size.toLong())
        for (n in connectionMethods.indices) {
            Assert.assertEquals(
                connectionMethods[n].toString(),
                hs.connectionMethods[n].toString()
            )
        }

        // Checks that the helper returns the correct DE and Handover
        val expectedHandover = Cbor.encode(
            CborArray.builder()
                .add(initialNdefMessage) // Handover Select message
                .add(Simple.NULL) // Handover Request message
                .end()
                .build()
        )
        Assert.assertArrayEquals(expectedHandover, helper.handover)
        Assert.assertArrayEquals(hs.encodedDeviceEngagement, helper.deviceEngagement)
        helper.close()
    }

    @Throws(Exception::class)
    private fun testNegotiatedHandoverHelper(useLargeRequestMessage: Boolean) {
        val helper: NfcEngagementHelper?
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val listener: NfcEngagementHelper.Listener = object : NfcEngagementHelper.Listener {
            override fun onTwoWayEngagementDetected() {}
            override fun onHandoverSelectMessageSent() {}
            override fun onDeviceConnecting() {}
            override fun onDeviceConnected(transport: DataTransport) {}
            override fun onError(error: Throwable) {}
        }
        val executor: Executor = Executors.newSingleThreadExecutor()
        val builder = NfcEngagementHelper.Builder(
            context,
            eDeviceKey.publicKey,
            DataTransportOptions.Builder().build(),
            listener,
            executor
        )

        // Include all ConnectionMethods that can exist in OOB data
        val connectionMethods: MutableList<MdocConnectionMethod> = ArrayList()
        val bleUuid = UUID.randomUUID()
        connectionMethods.add(
            MdocConnectionMethodBle(
                true,
                true,
                bleUuid,
                bleUuid
            )
        )
        connectionMethods.add(
            MdocConnectionMethodNfc(
                0xffff,
                0xffff
            )
        )
        connectionMethods.add(
            MdocConnectionMethodWifiAware(
                null,
                null,
                null,
                null
            )
        )
        builder.useNegotiatedHandover()
        helper = builder.build()
        helper.testingDoNotStartTransports()

        // Select Type 4 Tag NDEF app
        val ndefAppId = NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION
        var responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduApplicationSelect(ndefAppId)
        )
        Assert.assertNotNull(responseApdu)
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu)

        // Select CC file
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID)
        )
        Assert.assertNotNull(responseApdu)
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu)

        // Get CC file
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduReadBinary(0, 15)
        )
        Assert.assertNotNull(responseApdu)
        // The response is the CC file followed by STATUS_WORD_OK. Keep in sync with
        // NfcEngagementHelper.handleSelectFile() for the contents.
        Assert.assertEquals("000f207fff7fff0406e1047fff00009000", responseApdu.toHex())

        // Select NDEF file
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID)
        )
        Assert.assertNotNull(responseApdu)
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu)

        // Get length of Initial NDEF message
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduReadBinary(0, 2)
        )
        Assert.assertNotNull(responseApdu)
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(4, responseApdu.size.toLong())
        Assert.assertEquals(0x9000, responseApdu.getUInt16(2).toInt())
        val initialNdefMessageSize = responseApdu.getUInt16(0).toInt()

        // Read Initial NDEF message
        responseApdu = helper.nfcProcessCommandApdu(
            NfcUtil.createApduReadBinary(2, initialNdefMessageSize)
        )
        Assert.assertNotNull(responseApdu)
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals((initialNdefMessageSize + 2).toLong(), responseApdu.size.toLong())
        Assert.assertEquals(0x9000, responseApdu.getUInt16(initialNdefMessageSize).toInt())
        val initialNdefMessage = Arrays.copyOf(responseApdu, responseApdu.size - 2)

        // The Initial NDEF message should contain a Service Parameter record for the
        // urn:nfc:sn:handover service.
        val handoverServiceRecord = NfcUtil.findServiceParameterRecordWithName(
            initialNdefMessage,
            "urn:nfc:sn:handover"
        )
        Assert.assertNotNull(handoverServiceRecord)
        val (tnepVersion, serviceNameUri, tnepCommunicationMode, tWaitMillis, nWait) =
            NfcUtil.parseServiceParameterRecord(handoverServiceRecord!!)
        Assert.assertEquals(0x10, tnepVersion.toLong())
        Assert.assertEquals("urn:nfc:sn:handover", serviceNameUri)
        Assert.assertEquals(0x00, tnepCommunicationMode.toLong())
        Assert.assertEquals(8.0, tWaitMillis, 0.001)
        Assert.assertEquals(15, nWait.toLong())

        // Keep the following code in sync with verificationHelper.startNegotiatedHandover()

        // Select the service, the resulting NDEF message is specified in
        // in Tag NDEF Exchange Protocol Technical Specification Version 1.0
        // section 4.3 TNEP Status Message
        val serviceSelectResponse = ndefTransact(
            helper,
            NfcUtil.createNdefMessageServiceSelect("urn:nfc:sn:handover")
        )
        val tnepStatusRecord = NfcUtil.findTnepStatusRecord(serviceSelectResponse)
        Assert.assertNotNull(tnepStatusRecord)
        val tnepStatusPayload = tnepStatusRecord!!.payload
        Assert.assertNotNull(tnepStatusPayload)
        Assert.assertEquals(1, tnepStatusPayload.size.toLong())
        Assert.assertEquals(0x00, tnepStatusPayload[0].toLong())
        var encodedReaderEngagement: ByteArray? = null
        if (useLargeRequestMessage) {
            encodedReaderEngagement = Cbor.encode(
                CborMap.builder()
                    .put("stuff", ByteArray(512))
                    .end()
                    .build()
            )
        }
        // Now send Handover Request, the resulting NDEF message is Handover Response..
        val hrMessage = NfcUtil.createNdefMessageHandoverRequest(
            connectionMethods,
            encodedReaderEngagement,
            DataTransportOptions.Builder().build()
        )
        if (useLargeRequestMessage) {
            Assert.assertTrue(hrMessage.size >= 256 - 2)
        } else {
            Assert.assertTrue(hrMessage.size < 256 - 2)
        }
        val hsMessage = ndefTransact(helper, hrMessage)
        val hs = NfcUtil.parseHandoverSelectMessage(hsMessage)
        Assert.assertNotNull(hs)
        val parser = EngagementParser(hs!!.encodedDeviceEngagement)
        // Check the returned DeviceEngagement
        val e = parser.parse()
        Assert.assertEquals("1.0", e.version)
        Assert.assertEquals(0, e.originInfos.size.toLong())
        Assert.assertEquals(eDeviceKey.publicKey, e.eSenderKey)
        Assert.assertEquals(0, e.connectionMethods.size.toLong())

        // Check the synthesized ConnectionMethod (from returned OOB data in HS)... we expect
        // only one to be returned and we expect it to be the BLE one and only the Central
        // Client mode.
        Assert.assertEquals(1, hs.connectionMethods.size.toLong())
        val cm = hs.connectionMethods[0] as MdocConnectionMethodBle
        Assert.assertFalse(cm.supportsPeripheralServerMode)
        Assert.assertTrue(cm.supportsCentralClientMode)
        Assert.assertNull(cm.peripheralServerModeUuid)
        Assert.assertEquals(cm.centralClientModeUuid, bleUuid)

        // Checks that the helper returns the correct DE and Handover
        val expectedHandover = Cbor.encode(
            CborArray.builder()
                .add(hsMessage) // Handover Select message
                .add(hrMessage) // Handover Request message
                .end()
                .build()
        )
        Assert.assertArrayEquals(expectedHandover, helper.handover)
        Assert.assertArrayEquals(hs.encodedDeviceEngagement, helper.deviceEngagement)
        helper.close()
    }

    // This tests the path where Handover Request is small enough to be sent using
    // one UPDATE_BINARY message. This is permitted by [T4T] Section 7.5.5 NDEF Write
    // Procedure.
    @Test
    @SmallTest
    @Throws(Exception::class)
    fun testNegotiatedHandover() {
        testNegotiatedHandoverHelper(false)
    }

    // This tests the path where Handover Request is larger than 255 bytes so at
    // the usual [T4T] Section 7.5.5 NDEF Write Procedure w/ 3 or more UPDATE_BINARY
    // messages are to be used.
    @Test
    @SmallTest
    @Throws(Exception::class)
    fun testNegotiatedHandoverWithLargeRequestMessage() {
        testNegotiatedHandoverHelper(true)
    }

    companion object {
        fun ndefTransact(helper: NfcEngagementHelper, ndefMessage: ByteArray): ByteArray {
            var responseApdu: ByteArray
            if (ndefMessage.size < 256 - 2) {
                // Fits in a single UPDATE_BINARY command
                val data = buildByteString { appendUInt16(ndefMessage.size).append(ndefMessage) }.toByteArray()
                responseApdu = helper.nfcProcessCommandApdu(
                    NfcUtil.createApduUpdateBinary(0, data)
                )
                Assert.assertNotNull(responseApdu)
                Assert.assertEquals(NfcUtil.STATUS_WORD_OK, responseApdu)
            } else {
                // First command is UPDATE_BINARY to reset length
                responseApdu = helper.nfcProcessCommandApdu(
                    NfcUtil.createApduUpdateBinary(0, byteArrayOf(0x00, 0x00))
                )
                Assert.assertNotNull(responseApdu)
                Assert.assertEquals(NfcUtil.STATUS_WORD_OK, responseApdu)

                // Subsequent commands are UPDATE_BINARY with payload, chopped into bits no longer
                // than 255 bytes each
                var offset = 0
                var remaining = ndefMessage.size
                while (remaining > 0) {
                    val numBytesToWrite = Math.min(remaining, 255)
                    val bytesToWrite =
                        Arrays.copyOfRange(ndefMessage, offset, offset + numBytesToWrite)
                    responseApdu = helper.nfcProcessCommandApdu(
                        NfcUtil.createApduUpdateBinary(offset + 2, bytesToWrite)
                    )
                    Assert.assertNotNull(responseApdu)
                    Assert.assertEquals(NfcUtil.STATUS_WORD_OK, responseApdu)
                    remaining -= numBytesToWrite
                    offset += numBytesToWrite
                }

                // Final command is UPDATE_BINARY to write the length
                val encodedLength = buildByteString { appendUInt16(ndefMessage.size) }.toByteArray()
                responseApdu = helper.nfcProcessCommandApdu(NfcUtil.createApduUpdateBinary(0, encodedLength))
                Assert.assertNotNull(responseApdu)
                Assert.assertEquals(NfcUtil.STATUS_WORD_OK, responseApdu)
            }

            // Finally, read the NDEF response message.. first get the length
            responseApdu = helper.nfcProcessCommandApdu(
                NfcUtil.createApduReadBinary(0, 2)
            )
            Assert.assertNotNull(responseApdu)
            // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
            // don't know the length.
            Assert.assertEquals(4, responseApdu.size.toLong())
            Assert.assertEquals(0x9000, responseApdu.getUInt16(2).toInt())
            val ndefMessageSize = responseApdu.getUInt16(0).toInt()

            // Read NDEF message
            responseApdu = helper.nfcProcessCommandApdu(
                NfcUtil.createApduReadBinary(2, ndefMessageSize)
            )
            Assert.assertNotNull(responseApdu)
            // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
            // don't know the length.
            Assert.assertEquals((ndefMessageSize + 2).toLong(), responseApdu.size.toLong())
            Assert.assertEquals(0x9000, responseApdu.getUInt16(ndefMessageSize).toInt())
            return responseApdu.copyOf(responseApdu.size - 2)
        }
    }
}