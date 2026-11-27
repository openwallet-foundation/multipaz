package com.android.identity.android.mdoc.engagement

import android.content.Context
import com.android.identity.android.mdoc.deviceretrieval.VerificationHelper
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransportNfc
import com.android.identity.android.mdoc.transport.DataTransportOptions
import com.android.identity.android.util.IsoDepTransceiver
import com.android.identity.android.util.NfcUtil
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethodBle
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethodNfc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod as CoreMdocConnectionMethod
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcTransportMdoc
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NdefMessage
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.UUID
import org.multipaz.util.getUInt16
import org.multipaz.util.putUInt16
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for APDU length and `maxTransceiveLength` enforcement in `multipaz-android-legacy`
 * reader paths ([VerificationHelper], [DataTransportNfc], and [NfcUtil]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LegacyNfcReaderApduLengthTest {

    private val directExecutor = Executor { runnable -> runnable.run() }

    private class LoopbackIsoDepTransceiver(
        override val maxTransceiveLength: Int,
        private val overrideCcMle: Int? = null,
        private val overrideCcMlc: Int? = null,
        initialConnected: Boolean = false,
        private val apduHandler: (ByteArray) -> ByteArray,
    ) : IsoDepTransceiver {
        override val isExtendedLengthApduSupported: Boolean = maxTransceiveLength > 261
        override var isConnected: Boolean = initialConnected
        override var timeout: Int = 0

        val commands = mutableListOf<ByteArray>()
        private var lastSelectedFileId: Int = 0

        override fun connect() {
            isConnected = true
        }

        override fun close() {
            isConnected = false
        }

        override fun transceive(data: ByteArray): ByteArray {
            check(data.size <= maxTransceiveLength) {
                "Command APDU is ${data.size} bytes which exceeds maxTransceiveLength of $maxTransceiveLength bytes"
            }
            commands.add(data)
            val decoded = CommandApdu.decode(data)
            if (decoded.ins == Nfc.INS_SELECT && decoded.p1 == Nfc.INS_SELECT_P1_FILE) {
                lastSelectedFileId = decoded.payload.getUInt16(0).toInt()
            }
            var response = apduHandler(data)
            if (decoded.ins == Nfc.INS_READ_BINARY &&
                lastSelectedFileId == Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID &&
                response.size == 17 &&
                response[15] == 0x90.toByte() &&
                response[16] == 0x00.toByte() &&
                (overrideCcMle != null || overrideCcMlc != null)
            ) {
                response = response.copyOf()
                if (overrideCcMle != null) {
                    response.putUInt16(3, overrideCcMle.toUInt())
                }
                if (overrideCcMlc != null) {
                    response.putUInt16(5, overrideCcMlc.toUInt())
                }
            }
            check(response.size <= maxTransceiveLength) {
                "Response APDU (${response.size} bytes) exceeds maxTransceiveLength ($maxTransceiveLength)"
            }
            return response
        }
    }

    private class StubDataTransport(
        context: Context,
        options: DataTransportOptions,
        override val connectionMethodForTransport: MdocConnectionMethod,
    ) : DataTransport(context, Role.MDOC_READER, options) {
        override fun setEDeviceKeyBytes(encodedEDeviceKeyBytes: ByteArray) {}
        override fun connect() {}
        override fun close() {}
        override fun sendMessage(data: ByteArray) {}
        override fun sendTransportSpecificTerminationMessage() {}
        override fun supportsTransportSpecificTerminationMessage(): Boolean = false
    }

    private class RecordingVerificationListener : VerificationHelper.Listener {
        var receivedMethods: List<MdocConnectionMethod>? = null
        var receivedError: Throwable? = null

        override fun onReaderEngagementReady(readerEngagement: ByteArray) {}

        override fun onDeviceEngagementReceived(connectionMethods: List<MdocConnectionMethod>) {
            receivedMethods = connectionMethods
        }

        override fun onMoveIntoNfcField() {}
        override fun onDeviceConnected() {}
        override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {}
        override fun onResponseReceived(deviceResponseBytes: ByteArray) {}

        override fun onError(error: Throwable) {
            receivedError = error
        }
    }

    private fun createNfcEngagementHelperListener() = object : NfcEngagementHelper.Listener {
        override fun onTwoWayEngagementDetected() {}
        override fun onHandoverSelectMessageSent() {}
        override fun onDeviceConnecting() {}
        override fun onDeviceConnected(transport: DataTransport) {}
        override fun onError(error: Throwable) {
            fail("Unexpected NfcEngagementHelper error: $error")
        }
    }

    @Test
    fun testCreateApduReadBinaryShortAndExtended() {
        val len255 = NfcUtil.createApduReadBinary(offset = 2, length = 255)
        assertArrayEquals(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x02, 0xff.toByte()), len255)

        val len256 = NfcUtil.createApduReadBinary(offset = 2, length = 256)
        assertArrayEquals(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x02, 0x00), len256)

        val len257 = NfcUtil.createApduReadBinary(offset = 2, length = 257)
        assertArrayEquals(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x02, 0x00, 0x01, 0x01), len257)

        val len65536 = NfcUtil.createApduReadBinary(offset = 2, length = 65536)
        assertArrayEquals(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x02, 0x00, 0x00, 0x00), len65536)

        try {
            NfcUtil.createApduReadBinary(offset = 0, length = 0)
            fail("Expected IllegalArgumentException for length=0")
        } catch (_: IllegalArgumentException) {
        }

        try {
            NfcUtil.createApduReadBinary(offset = 0, length = 65537)
            fail("Expected IllegalArgumentException for length=65537")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun testVerificationHelperStaticHandoverRespectsMaxTransceiveLengthAndMle() {
        val context = RuntimeEnvironment.getApplication()
        val eDeviceKey = runBlocking { Crypto.createEcPrivateKey(EcCurve.P256).publicKey }
        val bleUuid = UUID(1UL, 2UL)
        val staticMethods = listOf(
            MdocConnectionMethodBle(true, true, bleUuid, bleUuid),
            MdocConnectionMethodNfc(0xffff, 0x10000),
            MdocConnectionMethodNfc(0x1000, 0x2000),
        )

        val scenarios = listOf(
            Triple(100, null, null),
            Triple(261, null, null),
            Triple(65536, 59, 0),
        )
        for ((maxLen, mle, mlc) in scenarios) {
            val mdocHelper = NfcEngagementHelper.Builder(
                context,
                eDeviceKey,
                DataTransportOptions.Builder().build(),
                createNfcEngagementHelperListener(),
                directExecutor,
            )
                .useStaticHandover(staticMethods)
                .build()
            mdocHelper.testingDoNotStartTransports()

            val tag = LoopbackIsoDepTransceiver(
                maxTransceiveLength = maxLen,
                overrideCcMle = mle,
                overrideCcMlc = mlc,
                apduHandler = { apdu -> mdocHelper.nfcProcessCommandApdu(apdu) },
            )
            val listener = RecordingVerificationListener()
            val verificationHelper = VerificationHelper.Builder(context, listener, directExecutor)
                .setDataTransportOptions(DataTransportOptions.Builder().build())
                .build()

            verificationHelper.startNfcHandover(tag, runAsync = false)

            assertNull(listener.receivedError)
            assertNotNull(listener.receivedMethods)
            assertEquals(VerificationHelper.EngagementMethod.NFC_STATIC_HANDOVER, verificationHelper.engagementMethod)
            assertArrayEquals(mdocHelper.deviceEngagement, verificationHelper.deviceEngagementBytesForTesting())

            val readCommands = tag.commands.filter { (it[1].toInt() and 0xff) == Nfc.INS_READ_BINARY }
            assertTrue("Expected chunked READ BINARY for maxLen=$maxLen mle=$mle", readCommands.size >= 4)
            if (maxLen == 261) {
                assertTrue(readCommands.all { it.size == 5 })
            }
            if (mle != null) {
                assertTrue(readCommands.all { CommandApdu.decode(it).le <= mle })
            }

            verificationHelper.disconnect()
            mdocHelper.close()
        }

        // Invalid MLe = 0 in CC file must report IllegalStateException via Listener.onError
        val invalidMleHelper = NfcEngagementHelper.Builder(
            context,
            eDeviceKey,
            DataTransportOptions.Builder().build(),
            createNfcEngagementHelperListener(),
            directExecutor,
        )
            .useStaticHandover(staticMethods)
            .build()
        invalidMleHelper.testingDoNotStartTransports()
        val badListener = RecordingVerificationListener()
        val badVerificationHelper = VerificationHelper.Builder(context, badListener, directExecutor)
            .setDataTransportOptions(DataTransportOptions.Builder().build())
            .build()
        badVerificationHelper.startNfcHandover(
            LoopbackIsoDepTransceiver(
                maxTransceiveLength = 65536,
                overrideCcMle = 0,
                apduHandler = { apdu -> invalidMleHelper.nfcProcessCommandApdu(apdu) },
            ),
            runAsync = false,
        )
        assertTrue(badListener.receivedError is IllegalStateException)
        badVerificationHelper.disconnect()
        invalidMleHelper.close()
    }

    @Test
    fun testVerificationHelperNegotiatedHandoverRespectsMaxTransceiveLengthAndMlcMle() {
        val context = RuntimeEnvironment.getApplication()
        val eDeviceKey = runBlocking { Crypto.createEcPrivateKey(EcCurve.P256).publicKey }
        val bleUuid = UUID(3UL, 4UL)
        val transportOptions = DataTransportOptions.Builder().build()

        val scenarios = listOf(
            Triple(100, null, null),
            Triple(261, null, null),
            Triple(65536, 25, 20),
        )
        for ((maxLen, mle, mlc) in scenarios) {
            val mdocHelper = NfcEngagementHelper.Builder(
                context,
                eDeviceKey,
                transportOptions,
                createNfcEngagementHelperListener(),
                directExecutor,
            )
                .useNegotiatedHandover()
                .build()
            mdocHelper.testingDoNotStartTransports()

            val tag = LoopbackIsoDepTransceiver(
                maxTransceiveLength = maxLen,
                overrideCcMle = mle,
                overrideCcMlc = mlc,
                apduHandler = { apdu -> mdocHelper.nfcProcessCommandApdu(apdu) },
            )
            val listener = RecordingVerificationListener()
            val verificationHelper = VerificationHelper.Builder(context, listener, directExecutor)
                .setDataTransportOptions(transportOptions)
                .build()
            verificationHelper.addNegotiatedHandoverListeningTransportForTesting(
                StubDataTransport(
                    context = context,
                    options = transportOptions,
                    connectionMethodForTransport = MdocConnectionMethodBle(
                        supportsPeripheralServerMode = false,
                        supportsCentralClientMode = true,
                        peripheralServerModeUuid = null,
                        centralClientModeUuid = bleUuid,
                    ),
                )
            )
            verificationHelper.addNegotiatedHandoverListeningTransportForTesting(
                StubDataTransport(
                    context = context,
                    options = transportOptions,
                    connectionMethodForTransport = MdocConnectionMethodNfc(0xffff, 0x10000),
                )
            )

            verificationHelper.startNfcHandover(tag, runAsync = false)

            assertNull(listener.receivedError)
            assertNotNull(listener.receivedMethods)
            assertEquals(
                VerificationHelper.EngagementMethod.NFC_NEGOTIATED_HANDOVER,
                verificationHelper.engagementMethod,
            )

            val updateCommands = tag.commands.filter { (it[1].toInt() and 0xff) == 0xd6 }
            val readCommands = tag.commands.filter { (it[1].toInt() and 0xff) == Nfc.INS_READ_BINARY }
            if (maxLen == 100) {
                assertTrue(updateCommands.size > 3)
                assertTrue(readCommands.size > 3)
            }
            if (maxLen == 261) {
                assertTrue(readCommands.all { it.size == 5 })
            }
            if (mlc != null) {
                assertTrue(updateCommands.size > 5)
                assertTrue(updateCommands.all { (it[4].toInt() and 0xff) <= mlc })
            }
            if (mle != null) {
                assertTrue(readCommands.size > 5)
                assertTrue(readCommands.all { CommandApdu.decode(it).le <= mle })
            }

            verificationHelper.disconnect()
            mdocHelper.close()
        }

        // Direct ndefTransact test at maxTransceiveLength = 253 with a 300-byte NDEF message
        val verificationHelper253 = VerificationHelper.Builder(
            context,
            RecordingVerificationListener(),
            directExecutor,
        )
            .setDataTransportOptions(transportOptions)
            .build()
        val ndefRequest = NdefMessage(
            listOf(
                NdefRecord(
                    tnf = NdefRecord.Tnf.WELL_KNOWN,
                    type = ByteString('T'.code.toByte()),
                    payload = ByteString(ByteArray(295) { (it and 0xff).toByte() }),
                )
            )
        ).encode()
        assertTrue(ndefRequest.size > 253)
        val ndefResponse = NdefMessage(
            listOf(
                NdefRecord(
                    tnf = NdefRecord.Tnf.WELL_KNOWN,
                    type = ByteString('T'.code.toByte()),
                    payload = ByteString(ByteArray(290) { ((it + 5) and 0xff).toByte() }),
                )
            )
        ).encode()
        assertTrue(ndefResponse.size > 253)

        val writeBuffer = ByteArray(1024)
        var writtenNdef: ByteArray? = null
        val respFile = ByteArray(ndefResponse.size + 2)
        respFile.putUInt16(0, ndefResponse.size.toUInt())
        ndefResponse.copyInto(respFile, destinationOffset = 2)

        val tag253 = LoopbackIsoDepTransceiver(
            maxTransceiveLength = 253,
            initialConnected = true,
        ) { apdu ->
            val decoded = CommandApdu.decode(apdu)
            when (decoded.ins) {
                0xd6 -> {
                    val offset = decoded.p1 * 0x100 + decoded.p2
                    val payload = decoded.payload.toByteArray()
                    if (offset == 0 && payload.size == 2) {
                        val nlen = payload.getUInt16(0).toInt()
                        if (nlen > 0) {
                            writtenNdef = writeBuffer.copyOfRange(2, 2 + nlen)
                        }
                    } else {
                        payload.copyInto(writeBuffer, destinationOffset = offset)
                    }
                    NfcUtil.STATUS_WORD_OK
                }
                Nfc.INS_READ_BINARY -> {
                    val offset = decoded.p1 * 0x100 + decoded.p2
                    val chunk = respFile.copyOfRange(offset, offset + decoded.le)
                    chunk + NfcUtil.STATUS_WORD_OK
                }
                else -> NfcUtil.STATUS_WORD_INSTRUCTION_NOT_SUPPORTED
            }
        }
        val actualResp = verificationHelper253.ndefTransact(tag253, ndefRequest, tWaitMillis = 0.0, nWait = 0)
        assertArrayEquals(ndefRequest, writtenNdef)
        assertArrayEquals(ndefResponse, actualResp)
        val updateCmds253 = tag253.commands.filter { (it[1].toInt() and 0xff) == 0xd6 }
        assertTrue(updateCmds253.any { (it[4].toInt() and 0xff) == 248 })
        assertTrue(updateCmds253.all { (it[4].toInt() and 0xff) <= 248 })
        val readCmds253 = tag253.commands.filter { (it[1].toInt() and 0xff) == Nfc.INS_READ_BINARY }
        assertTrue(readCmds253.all { it.size == 5 && CommandApdu.decode(it).le <= 244 })
        verificationHelper253.disconnect()

        // Negotiated Handover must reject MLc = 0 in CC file
        val zeroMlcHelper = NfcEngagementHelper.Builder(
            context,
            eDeviceKey,
            transportOptions,
            createNfcEngagementHelperListener(),
            directExecutor,
        )
            .useNegotiatedHandover()
            .build()
        zeroMlcHelper.testingDoNotStartTransports()
        val zeroMlcListener = RecordingVerificationListener()
        val zeroMlcVerificationHelper = VerificationHelper.Builder(context, zeroMlcListener, directExecutor)
            .setDataTransportOptions(transportOptions)
            .build()
        zeroMlcVerificationHelper.startNfcHandover(
            LoopbackIsoDepTransceiver(
                maxTransceiveLength = 65536,
                overrideCcMle = 256,
                overrideCcMlc = 0,
                apduHandler = { apdu -> zeroMlcHelper.nfcProcessCommandApdu(apdu) },
            ),
            runAsync = false,
        )
        assertTrue(zeroMlcListener.receivedError is IllegalStateException)
        zeroMlcVerificationHelper.disconnect()
        zeroMlcHelper.close()
    }

    @Test
    fun testDataTransportNfcReaderRespectsMaxTransceiveLengthAndResponseLimit() {
        val context = RuntimeEnvironment.getApplication()
        val transportOptions = DataTransportOptions.Builder().build()

        fun runExchangeScenario(
            maxTransceiveLength: Int,
            peerCmdMax: Long = 0xffffL,
            peerRespMax: Long = 0x10000L,
            requestBytes: ByteArray,
            responseBytes: ByteArray,
        ): List<CommandApdu> {
            val eReaderKey = runBlocking { Crypto.createEcPrivateKey(EcCurve.P256) }
            val mdoc = NfcTransportMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(),
                connectionMethod = CoreMdocConnectionMethod.Nfc(peerCmdMax, peerRespMax),
            )
            runBlocking {
                mdoc.open(buildDeviceEngagement(eReaderKey.publicKey) {}.eDeviceKeyBytes)
            }

            val reader = DataTransportNfc(
                context = context,
                role = DataTransport.Role.MDOC_READER,
                connectionMethod = MdocConnectionMethodNfc(peerCmdMax, peerRespMax),
                options = transportOptions,
            )
            val connectedLatch = CountDownLatch(1)
            val messageLatch = CountDownLatch(1)
            var listenerError: Throwable? = null
            reader.setListener(
                object : DataTransport.Listener {
                    override fun onConnecting() {}
                    override fun onConnected() = connectedLatch.countDown()
                    override fun onDisconnected() {}
                    override fun onMessageReceived() = messageLatch.countDown()
                    override fun onTransportSpecificSessionTermination() {}
                    override fun onError(error: Throwable) {
                        listenerError = error
                        connectedLatch.countDown()
                        messageLatch.countDown()
                    }
                },
                directExecutor,
            )

            val transceiver = LoopbackIsoDepTransceiver(
                maxTransceiveLength = maxTransceiveLength,
                initialConnected = true,
            ) { apdu ->
                runBlocking {
                    NfcTransportMdoc.processCommandApdu(CommandApdu.decode(apdu)).encode()
                }
            }
            reader.setIsoDepTransceiverForTesting(transceiver)

            try {
                reader.connect()
                assertTrue("Timed out waiting for connect", connectedLatch.await(5, TimeUnit.SECONDS))
                assertNull(listenerError)

                reader.sendMessage(requestBytes)
                val receivedOnMdoc = runBlocking {
                    withTimeout(5.seconds) {
                        mdoc.state.first { it != MdocTransport.State.IDLE }
                        mdoc.waitForMessage()
                    }
                }
                assertArrayEquals(requestBytes, receivedOnMdoc)

                runBlocking {
                    withTimeout(5.seconds) { mdoc.sendMessage(responseBytes) }
                }
                assertTrue("Timed out waiting for response", messageLatch.await(5, TimeUnit.SECONDS))
                assertNull(listenerError)
                assertArrayEquals(responseBytes, reader.getMessage())

                return transceiver.commands.map { CommandApdu.decode(it) }
            } finally {
                reader.close()
                runBlocking { mdoc.close() }
            }
        }

        // 1. Small maxTransceiveLength = 100 (multi-chunk ENVELOPE and clamped 61 XX GET RESPONSE)
        val cmds100 = runExchangeScenario(
            maxTransceiveLength = 100,
            requestBytes = ByteArray(300) { (it and 0xff).toByte() },
            responseBytes = ByteArray(350) { ((it + 1) and 0xff).toByte() },
        )
        assertTrue(cmds100.count { it.ins == Nfc.INS_ENVELOPE } >= 4)
        val gr100 = cmds100.filter { it.ins == Nfc.INS_GET_RESPONSE }
        assertTrue(gr100.isNotEmpty())
        assertTrue(gr100.all { it.le <= 91 })

        // 2. Short-APDU-only Android maxTransceiveLength = 261 keeps all data APDUs in short form
        val cmds261 = runExchangeScenario(
            maxTransceiveLength = 261,
            requestBytes = ByteArray(600) { (it and 0xff).toByte() },
            responseBytes = ByteArray(600) { ((it + 1) and 0xff).toByte() },
        )
        assertTrue(
            cmds261.filter { it.ins == Nfc.INS_ENVELOPE || it.ins == Nfc.INS_GET_RESPONSE }
                .all { it.payload.size < 0x100 && it.le <= 0x100 },
        )

        // 3. Exact 9-byte Case 4E boundary checks at maxTransceiveLength = 300 (DO'53' header is 4 bytes)
        for (targetDo53Size in listOf(291, 292, 294, 300, 582)) {
            runExchangeScenario(
                maxTransceiveLength = 300,
                requestBytes = ByteArray(targetDo53Size - 4) { (it and 0xff).toByte() },
                responseBytes = ByteArray(targetDo53Size - 4) { ((it + 3) and 0xff).toByte() },
            )
        }

        // 4. Asymmetric peer response limit (respMax = 200 on maxTransceiveLength = 4096)
        val cmdsAsym = runExchangeScenario(
            maxTransceiveLength = 4096,
            peerCmdMax = 4096,
            peerRespMax = 200,
            requestBytes = ByteArray(300) { (it and 0xff).toByte() },
            responseBytes = ByteArray(500) { ((it + 7) and 0xff).toByte() },
        )
        val dataCmdsAsym = cmdsAsym.filter { it.ins == Nfc.INS_ENVELOPE || it.ins == Nfc.INS_GET_RESPONSE }
        assertTrue(dataCmdsAsym.any { it.ins == Nfc.INS_GET_RESPONSE })
        assertTrue(dataCmdsAsym.all { it.le <= 200 })

        // 5. Too-small maxTransceiveLength = 9 must report IllegalArgumentException via onError
        val tooSmallReader = DataTransportNfc(
            context = context,
            role = DataTransport.Role.MDOC_READER,
            connectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000),
            options = transportOptions,
        )
        val errorLatch = CountDownLatch(1)
        var capturedError: Throwable? = null
        tooSmallReader.setListener(
            object : DataTransport.Listener {
                override fun onConnecting() {}
                override fun onConnected() {}
                override fun onDisconnected() {}
                override fun onMessageReceived() {}
                override fun onTransportSpecificSessionTermination() {}
                override fun onError(error: Throwable) {
                    capturedError = error
                    errorLatch.countDown()
                }
            },
            directExecutor,
        )
        tooSmallReader.setIsoDepTransceiverForTesting(
            LoopbackIsoDepTransceiver(maxTransceiveLength = 9, initialConnected = true) { ByteArray(0) },
        )
        tooSmallReader.connect()
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS))
        assertTrue(capturedError is IllegalArgumentException)
        tooSmallReader.close()
    }
}

private fun VerificationHelper.deviceEngagementBytesForTesting(): ByteArray {
    val field = VerificationHelper::class.java.getDeclaredField("deviceEngagement")
    field.isAccessible = true
    return field.get(this) as ByteArray
}
