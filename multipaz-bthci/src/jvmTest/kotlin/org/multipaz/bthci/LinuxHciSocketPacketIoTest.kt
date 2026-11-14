package org.multipaz.bthci

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.BleCentralConfig
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.milliseconds

class LinuxHciSocketPacketIoTest {
    /**
     * Verifies [LinuxHciSocketPacketIo] with a pre-opened file descriptor (`preOpenedHciFd`):
     * - Adopts an inherited/pre-opened packet socket fd without requiring `CAP_NET_ADMIN`.
     * - Leaves the pre-opened fd open when the first BLE session closes, sending `HCI_Reset` on close
     *   and draining any unread `HCI_Command_Complete` bytes from the kernel socket receive buffer
     *   when the second BLE session opens and reuses the same fd.
     */
    @Test
    fun preOpenedHciFdReusedAcrossMultipleSessionsOnLinux() = runTest {
        val osName = System.getProperty("os.name") ?: ""
        if (!osName.contains("Linux", ignoreCase = true)) {
            return@runTest
        }
        withContext(Dispatchers.Default) {
            val (clientFd, controllerSideFd) = LinuxHciSocketPacketIo.createSocketPairForTesting()
            try {
                for (session in 1..2) {
                    val bus = BleHciTransportTest.VirtualHciBus()
                    val controllerBridgeIo = LinuxHciSocketPacketIo(preOpenedHciFd = controllerSideFd).also {
                        it.open()
                    }
                    val bridgeScope = CoroutineScope(Dispatchers.IO)
                    val txBridgeJob = bridgeScope.launch {
                        while (true) {
                            val pkt = controllerBridgeIo.readPacket() ?: break
                            bus.centralIo.writePacket(pkt)
                        }
                    }
                    val rxBridgeJob = bridgeScope.launch {
                        while (true) {
                            val pkt = bus.centralIo.readPacket() ?: break
                            try {
                                controllerBridgeIo.writePacket(pkt)
                            } catch (_: Throwable) {
                                break
                            }
                        }
                    }

                    val serviceUuid = UUID.randomUUID()
                    val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

                    val centralFactory = BtHciMdocTransportFactory(
                        centralConfig = BleCentralConfig(
                            scanRestartTimeout = 20.milliseconds,
                            postScanDelay = 1.milliseconds,
                            connectRetryDelay = 10.milliseconds,
                            discoverServicesRetryDelay = 10.milliseconds,
                            requireL2capPeripheralConnectedFirst = true,
                        ),
                        packetIoFactory = {
                            LinuxHciSocketPacketIo(preOpenedHciFd = clientFd).also { it.open() }
                        },
                    )
                    val peripheralFactory = BtHciMdocTransportFactory(
                        packetIoFactory = { bus.peripheralIo },
                    )
                    val connectionMethod = MdocConnectionMethod.Ble.GattCentral(
                        uuid = serviceUuid,
                    )

                    val mdoc = centralFactory.createTransport(
                        connectionMethod = connectionMethod,
                        role = MdocRole.MDOC,
                        options = MdocTransportOptions(bleUseL2CAP = true),
                    )
                    val reader = peripheralFactory.createTransport(
                        connectionMethod = connectionMethod,
                        role = MdocRole.MDOC_READER,
                        options = MdocTransportOptions(bleUseL2CAP = true),
                    )

                    try {
                        reader.advertise()
                        coroutineScope {
                            val readerOpen = async { reader.open(eSenderKey) }
                            val mdocOpen = async { mdoc.open(eSenderKey) }
                            readerOpen.await()
                            mdocOpen.await()
                        }

                        val payload = "session-$session-over-preopened-fd".encodeToByteArray()
                        coroutineScope {
                            val recvJob = async { reader.waitForMessage() }
                            mdoc.sendMessage(payload)
                            assertContentEquals(payload, recvJob.await())
                        }
                    } finally {
                        mdoc.close()
                        reader.close()
                        delay(10.milliseconds)
                        bus.centralIo.close()
                        bus.peripheralIo.close()
                        controllerBridgeIo.close()
                        txBridgeJob.cancelAndJoin()
                        rxBridgeJob.cancelAndJoin()
                        bridgeScope.cancel()
                    }
                }
            } finally {
                LinuxHciSocketPacketIo.closeFdForTesting(clientFd)
                LinuxHciSocketPacketIo.closeFdForTesting(controllerSideFd)
            }
        }
    }
}
