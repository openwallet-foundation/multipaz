package org.multipaz.nci

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcApduRouter
import org.multipaz.nfc.NfcApduService
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.NfcTagReader
import org.multipaz.nfc.ResponseApdu
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [NciEngine], [NciNfcController], [NfcTagReaderNci], and [NfcIsoTagNci]
 * using an in-memory virtual NXP PN7160 NCI 2.0 controller.
 */
class NciNfcTransportTest {

    /**
     * In-memory virtual NXP PN7160 NCI 2.0 controller implementing [NciPacketIo].
     */
    private class VirtualPn7160Io(
        val maxDataPayloadSize: Int = 64,
        val initialCredits: Int = 1,
        val grantCreditsOnDataTx: Boolean = initialCredits != NciConstants.CREDITS_NO_FLOW_CONTROL,
    ) : NciPacketIo {
        private val rxChannel = Channel<ByteArray>(Channel.UNLIMITED)

        @Volatile
        var hardwareResetCount: Int = 0
            private set

        @Volatile
        var closed: Boolean = false
            private set

        @Volatile
        var lastDiscoveryModes: List<Int> = emptyList()
            private set

        @Volatile
        var presenceCheckStatus: Int = NciConstants.STATUS_OK

        @Volatile
        var lastSelectedDiscoveryId: Int = -1

        val receivedCommands = mutableListOf<Pair<Int, Int>>()
        private var outboundDataBuffer = ByteArray(0)

        var onHostCompleteApdu: (suspend (ByteArray) -> Unit)? = null
        var onDiscoveryStarted: ((List<Int>) -> Unit)? = null

        override suspend fun resetHardware() {
            hardwareResetCount += 1
        }

        override suspend fun readPacket(): ByteArray? {
            return rxChannel.receiveCatching().getOrNull()
        }

        override suspend fun writePacket(packet: ByteArray) {
            val header0 = packet[0].toInt() and 0xFF
            val header1 = packet[1].toInt() and 0xFF
            val payloadLen = packet[2].toInt() and 0xFF
            val payload = packet.copyOfRange(3, 3 + payloadLen)
            val mt = header0 and NciConstants.MT_MASK
            val pbf = header0 and NciConstants.PBF_MASK

            if (mt == NciConstants.MT_CMD) {
                val gid = header0 and NciConstants.GID_MASK
                val oid = header1 and NciConstants.OID_MASK
                receivedCommands.add(gid to oid)
                handleControlCommand(gid, oid, payload)
            } else if (mt == NciConstants.MT_DATA) {
                if (grantCreditsOnDataTx) {
                    // Immediately grant 1 credit back to the host for Static RF Connection 0x00
                    sendToHost(
                        NciConstants.buildNotification(
                            gid = NciConstants.GID_CORE,
                            oid = NciConstants.OID_CORE_CONN_CREDITS,
                            payload = byteArrayOf(0x01, NciConstants.STATIC_RF_CONN_ID.toByte(), 0x01)
                        )
                    )
                }
                outboundDataBuffer += payload
                if (pbf == NciConstants.PBF_COMPLETE) {
                    val completeApdu = outboundDataBuffer
                    outboundDataBuffer = ByteArray(0)
                    onHostCompleteApdu?.invoke(completeApdu)
                }
            }
        }

        override fun close() {
            closed = true
            rxChannel.close()
        }

        fun sendToHost(packet: ByteArray) {
            rxChannel.trySend(packet)
        }

        /**
         * Sends an APDU (CommandApdu in Listen Mode or ResponseApdu in Poll Mode) from the RF peer
         * to the host, segmented across `maxDataPayloadSize` NCI Data packets.
         */
        fun sendPeerApduToHost(apduBytes: ByteArray) {
            if (apduBytes.isEmpty()) {
                sendToHost(NciConstants.buildDataPacket(NciConstants.STATIC_RF_CONN_ID, apduBytes, 0, 0, true))
                return
            }
            var offset = 0
            while (offset < apduBytes.size) {
                val len = minOf(apduBytes.size - offset, maxDataPayloadSize)
                val isLast = (offset + len) >= apduBytes.size
                sendToHost(
                    NciConstants.buildDataPacket(
                        connId = NciConstants.STATIC_RF_CONN_ID,
                        payload = apduBytes,
                        offset = offset,
                        length = len,
                        isComplete = isLast
                    )
                )
                offset += len
            }
        }

        /**
         * Simulates `RF_INTF_ACTIVATED_NTF` for an ISO-DEP target in Poll Mode (`NFC_A_PASSIVE_POLL_MODE`)
         * or a remote reader in Listen Mode (`NFC_A_PASSIVE_LISTEN_MODE`).
         */
        fun simulateIsoDepActivated(
            rfTechAndMode: Int,
            historicalBytes: ByteArray = byteArrayOf(0x4D, 0x50, 0x5A) // "MPZ"
        ) {
            // Build ATS after TL: T0 = 0x78 (TA1, TB1, TC1 present, FSCI=8 -> 256B), TA1=0x80, TB1=0x70, TC1=0x02, HistBytes
            val atsBody = byteArrayOf(0x78, 0x80.toByte(), 0x70, 0x02) + historicalBytes
            val actParams = if (NciConstants.isPollMode(rfTechAndMode)) {
                byteArrayOf(atsBody.size.toByte()) + atsBody
            } else {
                byteArrayOf(0x80.toByte(), 0x08) // RATS param
            }
            // NFC-A poll tech params: SENS_RES(2B) + NFCID1_LEN(4) + NFCID1(4B) + SEL_RES_LEN(1) + SEL_RES(0x20)
            val techParams = if (NciConstants.isPollMode(rfTechAndMode)) {
                byteArrayOf(0x04, 0x00, 0x04, 0x01, 0x02, 0x03, 0x04, 0x01, 0x20)
            } else {
                ByteArray(0)
            }
            val payload = ByteArray(11 + techParams.size + actParams.size)
            payload[0] = 0x01 // RfDiscoveryId = 1
            payload[1] = NciConstants.RF_INTERFACE_ISO_DEP.toByte()
            payload[2] = NciConstants.PROTOCOL_ISO_DEP.toByte()
            payload[3] = rfTechAndMode.toByte()
            payload[4] = maxDataPayloadSize.toByte()
            payload[5] = initialCredits.toByte()
            payload[6] = techParams.size.toByte()
            techParams.copyInto(payload, destinationOffset = 7)
            val dataExchOffset = 7 + techParams.size
            payload[dataExchOffset] = rfTechAndMode.toByte()
            payload[dataExchOffset + 1] = 0x00 // 106 kbps
            payload[dataExchOffset + 2] = 0x00 // 106 kbps
            payload[dataExchOffset + 3] = actParams.size.toByte()
            actParams.copyInto(payload, destinationOffset = dataExchOffset + 4)

            sendToHost(
                NciConstants.buildNotification(
                    gid = NciConstants.GID_RF_MGMT,
                    oid = NciConstants.OID_RF_INTF_ACTIVATED,
                    payload = payload
                )
            )
        }

        fun simulateRfDeactivated(
            deactivationType: Int = NciConstants.DEACTIVATE_TYPE_DISCOVERY,
            deactivationReason: Int = NciConstants.DEACTIVATE_REASON_RF_LINK_LOSS
        ) {
            sendToHost(
                NciConstants.buildNotification(
                    gid = NciConstants.GID_RF_MGMT,
                    oid = NciConstants.OID_RF_DEACTIVATE,
                    payload = byteArrayOf(deactivationType.toByte(), deactivationReason.toByte())
                )
            )
        }

        private fun handleControlCommand(gid: Int, oid: Int, payload: ByteArray) {
            when (gid) {
                NciConstants.GID_CORE -> {
                    when (oid) {
                        NciConstants.OID_CORE_RESET -> {
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_CORE,
                                    oid = NciConstants.OID_CORE_RESET,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte())
                                )
                            )
                            sendToHost(
                                NciConstants.buildNotification(
                                    gid = NciConstants.GID_CORE,
                                    oid = NciConstants.OID_CORE_RESET,
                                    payload = byteArrayOf(
                                        0x02, // ResetReason: CORE_RESET_CMD was received
                                        0x01, // ConfigStatus: NCI RF configuration has been reset
                                        NciConstants.NCI_VERSION_2_0.toByte(),
                                        0x04, // Manufacturer ID: NXP (0x04)
                                        0x04, 0x11, 0x60, 0x01, 0x12 // PN7160 HW/FW info
                                    )
                                )
                            )
                        }

                        NciConstants.OID_CORE_INIT -> {
                            val initRsp = ByteArray(15)
                            initRsp[0] = NciConstants.STATUS_OK.toByte()
                            initRsp[5] = 0x02 // MaxLogicalConnections
                            initRsp[7] = maxDataPayloadSize.toByte()
                            initRsp[8] = initialCredits.toByte()
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_CORE,
                                    oid = NciConstants.OID_CORE_INIT,
                                    payload = initRsp
                                )
                            )
                        }

                        NciConstants.OID_CORE_SET_CONFIG -> {
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_CORE,
                                    oid = NciConstants.OID_CORE_SET_CONFIG,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte(), 0x00)
                                )
                            )
                        }
                    }
                }

                NciConstants.GID_RF_MGMT -> {
                    when (oid) {
                        NciConstants.OID_RF_DISCOVER_MAP,
                        NciConstants.OID_RF_SET_LISTEN_MODE_ROUTING -> {
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_RF_MGMT,
                                    oid = oid,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte())
                                )
                            )
                        }

                        NciConstants.OID_RF_DISCOVER_SELECT -> {
                            lastSelectedDiscoveryId = payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_RF_MGMT,
                                    oid = oid,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte())
                                )
                            )
                            simulateIsoDepActivated(NciConstants.NFC_A_PASSIVE_POLL_MODE)
                        }

                        NciConstants.OID_RF_DISCOVER -> {
                            val numConfigs = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                            val modes = mutableListOf<Int>()
                            for (i in 0 until numConfigs) {
                                val offset = 1 + i * 2
                                if (offset < payload.size) {
                                    modes.add(payload[offset].toInt() and 0xFF)
                                }
                            }
                            lastDiscoveryModes = modes
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_RF_MGMT,
                                    oid = NciConstants.OID_RF_DISCOVER,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte())
                                )
                            )
                            onDiscoveryStarted?.invoke(modes)
                        }

                        NciConstants.OID_RF_DEACTIVATE -> {
                            val deactType = payload.firstOrNull()?.toInt()?.and(0xFF)
                                ?: NciConstants.DEACTIVATE_TYPE_IDLE
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_RF_MGMT,
                                    oid = NciConstants.OID_RF_DEACTIVATE,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte())
                                )
                            )
                            sendToHost(
                                NciConstants.buildNotification(
                                    gid = NciConstants.GID_RF_MGMT,
                                    oid = NciConstants.OID_RF_DEACTIVATE,
                                    payload = byteArrayOf(
                                        deactType.toByte(),
                                        NciConstants.DEACTIVATE_REASON_DH_REQUEST.toByte()
                                    )
                                )
                            )
                        }
                    }
                }

                NciConstants.GID_PROPRIETARY -> {
                    when (oid) {
                        NciConstants.OID_NXP_PROPRIETARY_ACT -> {
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_PROPRIETARY,
                                    oid = NciConstants.OID_NXP_PROPRIETARY_ACT,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte(), 0x00, 0x00, 0x00, 0x00)
                                )
                            )
                        }

                        NciConstants.OID_NXP_PRESENCE_CHECK -> {
                            sendToHost(
                                NciConstants.buildResponse(
                                    gid = NciConstants.GID_PROPRIETARY,
                                    oid = NciConstants.OID_NXP_PRESENCE_CHECK,
                                    payload = byteArrayOf(NciConstants.STATUS_OK.toByte())
                                )
                            )
                            sendToHost(
                                NciConstants.buildNotification(
                                    gid = NciConstants.GID_PROPRIETARY,
                                    oid = NciConstants.OID_NXP_PRESENCE_CHECK,
                                    payload = byteArrayOf(presenceCheckStatus.toByte())
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun controllerInitializationAndReaderRegistration() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val virtualIo = VirtualPn7160Io()
        val controller = NciNfcController(
            packetIo = virtualIo,
            registerWithMultipaz = true,
            scope = scope
        )
        try {
            controller.start()
            assertEquals(1, virtualIo.hardwareResetCount)
            assertTrue(NfcTagReader.getReaders().contains(controller.reader))
            assertTrue(virtualIo.receivedCommands.contains(NciConstants.GID_CORE to NciConstants.OID_CORE_RESET))
            assertTrue(virtualIo.receivedCommands.contains(NciConstants.GID_CORE to NciConstants.OID_CORE_INIT))
            assertTrue(virtualIo.receivedCommands.contains(NciConstants.GID_PROPRIETARY to NciConstants.OID_NXP_PROPRIETARY_ACT))
            assertTrue(virtualIo.receivedCommands.contains(NciConstants.GID_RF_MGMT to NciConstants.OID_RF_DISCOVER_MAP))
            assertTrue(virtualIo.receivedCommands.contains(NciConstants.GID_CORE to NciConstants.OID_CORE_SET_CONFIG))
            assertTrue(virtualIo.receivedCommands.contains(NciConstants.GID_RF_MGMT to NciConstants.OID_RF_SET_LISTEN_MODE_ROUTING))
        } finally {
            controller.stop()
            scope.cancel()
        }
        assertFalse(NfcTagReader.getReaders().contains(controller.reader))
    }

    @Test
    fun readerScanSegmentedExtendedApduAndWaitUntilRemoved() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Use 64-byte NCI data packet limit and 1 credit to exercise multi-packet PBF segmentation
        // and CORE_CONN_CREDITS_NTF flow control in both directions.
        val virtualIo = VirtualPn7160Io(maxDataPayloadSize = 64, initialCredits = 1)
        val controller = NciNfcController(
            packetIo = virtualIo,
            registerWithMultipaz = false,
            disableReaderModeDelay = 500.milliseconds,
            pollInterval = 15.milliseconds,
            scope = scope
        )
        try {
            val largeCommandPayload = ByteArray(200) { idx -> (idx and 0xFF).toByte() }
            val largeResponsePayload = ByteArray(220) { idx -> ((220 - idx) and 0xFF).toByte() }

            virtualIo.onDiscoveryStarted = { modes ->
                if (modes.contains(NciConstants.NFC_A_PASSIVE_POLL_MODE)) {
                    virtualIo.simulateIsoDepActivated(NciConstants.NFC_A_PASSIVE_POLL_MODE)
                }
            }
            virtualIo.onHostCompleteApdu = { commandBytes ->
                val cmd = CommandApdu.decode(commandBytes)
                when (cmd.ins) {
                    Nfc.INS_SELECT -> {
                        virtualIo.sendPeerApduToHost(
                            ResponseApdu(Nfc.RESPONSE_STATUS_SUCCESS).encode()
                        )
                    }
                    else -> {
                        assertContentEquals(largeCommandPayload, cmd.payload.toByteArray())
                        virtualIo.sendPeerApduToHost(
                            ResponseApdu(
                                status = Nfc.RESPONSE_STATUS_SUCCESS,
                                payload = ByteString(largeResponsePayload)
                            ).encode()
                        )
                    }
                }
            }

            val response = controller.reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    val nciTag = tag as NfcIsoTagNci
                    assertEquals(
                        ByteString(0x4D, 0x50, 0x5A),
                        nciTag.activatedEvent.historicalBytes
                    )
                    tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
                    val resp = tag.transceive(
                        CommandApdu(
                            cla = 0x00,
                            ins = 0xCB,
                            p1 = 0x00,
                            p2 = 0x00,
                            payload = ByteString(largeCommandPayload),
                            le = 65536
                        )
                    )
                    tag.close()
                    resp
                }
            )
            assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, response.status)
            assertContentEquals(largeResponsePayload, response.payload.toByteArray())

            // Verify waitUntilRemoved() stays suspended while presenceCheckStatus == STATUS_OK
            val waitJob = scope.async {
                controller.reader.waitUntilRemoved()
            }
            withContext(Dispatchers.Default) {
                delay(50.milliseconds)
            }
            assertFalse(waitJob.isCompleted)

            // Simulate tag leaving the RF field
            virtualIo.presenceCheckStatus = NciConstants.STATUS_RF_TIMEOUT_ERROR
            waitJob.await()
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun readerScanRetriesAfterTagLostException() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val virtualIo = VirtualPn7160Io()
        val controller = NciNfcController(
            packetIo = virtualIo,
            registerWithMultipaz = false,
            scope = scope
        )
        try {
            var pollStarts = 0
            virtualIo.onDiscoveryStarted = { modes ->
                if (modes.contains(NciConstants.NFC_A_PASSIVE_POLL_MODE)) {
                    pollStarts += 1
                    virtualIo.simulateIsoDepActivated(NciConstants.NFC_A_PASSIVE_POLL_MODE)
                }
            }
            virtualIo.onHostCompleteApdu = {
                if (pollStarts == 1) {
                    // First tap loses RF link mid-APDU
                    virtualIo.simulateRfDeactivated(
                        deactivationType = NciConstants.DEACTIVATE_TYPE_DISCOVERY,
                        deactivationReason = NciConstants.DEACTIVATE_REASON_RF_LINK_LOSS
                    )
                } else {
                    virtualIo.sendPeerApduToHost(
                        ResponseApdu(
                            status = Nfc.RESPONSE_STATUS_SUCCESS,
                            payload = ByteString(0xCA.toByte(), 0xFE.toByte())
                        ).encode()
                    )
                }
            }

            val payload = controller.reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    val resp = tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
                    tag.close()
                    resp.payload
                }
            )
            assertEquals(2, pollStarts)
            assertEquals(ByteString(0xCA.toByte(), 0xFE.toByte()), payload)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun listenModeRoutesApdusToNfcApduRouterAndHandlesModeTransitions() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val virtualIo = VirtualPn7160Io(maxDataPayloadSize = 64, initialCredits = 1)
        val controller = NciNfcController(
            packetIo = virtualIo,
            registerWithMultipaz = true,
            disableReaderModeDelay = 30.milliseconds,
            pollInterval = 10.milliseconds,
            scope = scope
        )

        val deactivatedReasonDeferred = CompletableDeferred<NfcDeactivationReason>()
        val testAid = ByteString(0xA0.toByte(), 0x00, 0x00, 0x02, 0x48, 0x04, 0x00)
        val largeServiceReply = ByteArray(150) { (it xor 0x5A).toByte() }

        val fakeService = object : NfcApduService() {
            override suspend fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu {
                return if (commandApdu.isApplicationSelect) {
                    ResponseApdu(Nfc.RESPONSE_STATUS_SUCCESS)
                } else {
                    ResponseApdu(
                        status = Nfc.RESPONSE_STATUS_SUCCESS,
                        payload = ByteString(largeServiceReply)
                    )
                }
            }

            override suspend fun processDeactivated(reason: NfcDeactivationReason) {
                deactivatedReasonDeferred.complete(reason)
            }
        }

        val router = object : NfcApduRouter() {
            override fun buildServices(): Map<ByteString, NfcApduService> {
                return mapOf(testAid to fakeService)
            }
        }

        try {
            val listenDiscoveryReady = CompletableDeferred<Unit>()
            virtualIo.onDiscoveryStarted = { modes ->
                if (modes == listOf(NciConstants.NFC_A_PASSIVE_LISTEN_MODE)) {
                    listenDiscoveryReady.complete(Unit)
                }
            }

            controller.start()
            router.start()
            listenDiscoveryReady.await()
            assertEquals(listOf(NciConstants.NFC_A_PASSIVE_LISTEN_MODE), virtualIo.lastDiscoveryModes)

            // 1. Simulate a remote NFC Reader activating our emulated card in Listen Mode
            virtualIo.simulateIsoDepActivated(NciConstants.NFC_A_PASSIVE_LISTEN_MODE)

            val responsesChannel = Channel<ResponseApdu>(Channel.UNLIMITED)
            virtualIo.onHostCompleteApdu = { responseBytes ->
                responsesChannel.trySend(ResponseApdu.decode(responseBytes))
            }

            // Send SELECT APPLICATION for testAid
            val selectCmd = CommandApdu(
                cla = 0x00,
                ins = Nfc.INS_SELECT,
                p1 = Nfc.INS_SELECT_P1_APPLICATION,
                p2 = Nfc.INS_SELECT_P2_APPLICATION,
                payload = testAid,
                le = 0
            )
            virtualIo.sendPeerApduToHost(selectCmd.encode())
            val selectResp = responsesChannel.receive()
            assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, selectResp.status)

            // Send ENVELOPE command and verify segmented 150-byte response APDU
            val envCmd = CommandApdu(
                cla = 0x00,
                ins = Nfc.INS_ENVELOPE,
                p1 = 0x00,
                p2 = 0x00,
                payload = ByteString(0x01, 0x02, 0x03),
                le = 0
            )
            virtualIo.sendPeerApduToHost(envCmd.encode())
            val envResp = responsesChannel.receive()
            assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, envResp.status)
            assertContentEquals(largeServiceReply, envResp.payload.toByteArray())

            // Simulate RF link loss
            virtualIo.simulateRfDeactivated(
                deactivationType = NciConstants.DEACTIVATE_TYPE_DISCOVERY,
                deactivationReason = NciConstants.DEACTIVATE_REASON_RF_LINK_LOSS
            )
            assertEquals(NfcDeactivationReason.LINK_LOSS, deactivatedReasonDeferred.await())

            // 2. Now run a Poll Mode scan() while the router is still active, and verify
            // that Listen Mode automatically resumes after waitUntilRemoved() completes!
            val resumedListenAfterScan = CompletableDeferred<Unit>()
            virtualIo.onDiscoveryStarted = { modes ->
                if (modes.contains(NciConstants.NFC_A_PASSIVE_POLL_MODE)) {
                    virtualIo.simulateIsoDepActivated(NciConstants.NFC_A_PASSIVE_POLL_MODE)
                } else if (modes == listOf(NciConstants.NFC_A_PASSIVE_LISTEN_MODE)) {
                    resumedListenAfterScan.complete(Unit)
                }
            }
            virtualIo.onHostCompleteApdu = {
                virtualIo.sendPeerApduToHost(ResponseApdu(Nfc.RESPONSE_STATUS_SUCCESS).encode())
            }

            controller.reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.selectApplication(testAid)
                    tag.close()
                    "scanned"
                }
            )
            virtualIo.presenceCheckStatus = NciConstants.STATUS_RF_TIMEOUT_ERROR
            controller.reader.waitUntilRemoved()

            resumedListenAfterScan.await()
            assertEquals(listOf(NciConstants.NFC_A_PASSIVE_LISTEN_MODE), virtualIo.lastDiscoveryModes)
        } finally {
            router.stop()
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun multiTargetDiscoverySelectsIsoDepTargetAndUnlimitedCreditsDoNotStall() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val virtualIo = VirtualPn7160Io(
            maxDataPayloadSize = 32,
            initialCredits = NciConstants.CREDITS_NO_FLOW_CONTROL,
            grantCreditsOnDataTx = false
        )
        val controller = NciNfcController(
            packetIo = virtualIo,
            registerWithMultipaz = false,
            scope = scope
        )
        try {
            virtualIo.onDiscoveryStarted = { modes ->
                if (modes.contains(NciConstants.NFC_A_PASSIVE_POLL_MODE)) {
                    // Emit RF_DISCOVER_NTF #1: DiscoveryId=1, PROTOCOL_ISO_DEP, More=1
                    virtualIo.sendToHost(
                        NciConstants.buildNotification(
                            gid = NciConstants.GID_RF_MGMT,
                            oid = NciConstants.OID_RF_DISCOVER,
                            payload = byteArrayOf(
                                0x01,
                                NciConstants.PROTOCOL_ISO_DEP.toByte(),
                                NciConstants.NFC_A_PASSIVE_POLL_MODE.toByte(),
                                0x00,
                                0x01 // More notifications follow
                            )
                        )
                    )
                    // Emit RF_DISCOVER_NTF #2: DiscoveryId=2, PROTOCOL_NFC_DEP, More=0
                    virtualIo.sendToHost(
                        NciConstants.buildNotification(
                            gid = NciConstants.GID_RF_MGMT,
                            oid = NciConstants.OID_RF_DISCOVER,
                            payload = byteArrayOf(
                                0x02,
                                NciConstants.PROTOCOL_NFC_DEP.toByte(),
                                NciConstants.NFC_A_PASSIVE_POLL_MODE.toByte(),
                                0x00,
                                0x00 // Last notification -> enters RFST_W4_HOST_SELECT
                            )
                        )
                    )
                }
            }
            virtualIo.onHostCompleteApdu = {
                virtualIo.sendPeerApduToHost(ResponseApdu(Nfc.RESPONSE_STATUS_SUCCESS).encode())
            }

            val largePayload = ByteArray(150) { (it and 0xFF).toByte() }
            val resp = controller.reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    val r = tag.transceive(
                        CommandApdu(
                            cla = 0x00,
                            ins = 0xCB,
                            p1 = 0x00,
                            p2 = 0x00,
                            payload = ByteString(largePayload),
                            le = 256
                        )
                    )
                    tag.close()
                    r
                }
            )
            assertEquals(1, virtualIo.lastSelectedDiscoveryId)
            assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, resp.status)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun stopUnblocksActiveScanPromptly() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val virtualIo = VirtualPn7160Io()
        val controller = NciNfcController(
            packetIo = virtualIo,
            registerWithMultipaz = false,
            scope = scope
        )
        val pollReady = CompletableDeferred<Unit>()
        virtualIo.onDiscoveryStarted = { modes ->
            if (modes.contains(NciConstants.NFC_A_PASSIVE_POLL_MODE)) {
                pollReady.complete(Unit)
            }
        }
        try {
            val scanDeferred = scope.async {
                controller.reader.scan(
                    message = null,
                    tagInteractionFunc = { "unreachable" }
                )
            }
            pollReady.await()
            controller.stop()
            assertFailsWith<CancellationException> {
                scanDeferred.await()
            }
        } finally {
            controller.stop()
            scope.cancel()
        }
    }
}
