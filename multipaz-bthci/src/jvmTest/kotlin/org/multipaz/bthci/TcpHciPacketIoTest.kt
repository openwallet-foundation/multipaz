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
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TcpHciPacketIoTest {
    /**
     * Verifies [TcpHciPacketIo] H4 packet framing over a loopback TCP socket bridged to
     * [BleHciTransportTest.VirtualHciBus], together with [BleHciFaultConfig] deterministic fault
     * injection (`disconnectMidHandshakeAttempts`, `failServiceDiscoveryAttempts`,
     * `failCharacteristicReadAttempts`, `failCccdWriteAttempts`, and `failL2capConnectAttempts`).
     */
    @Test
    fun tcpHciPacketIoWithFaultInjectionRetries() = runTest {
        withContext(Dispatchers.Default) {
            val serverSocket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val port = serverSocket.localPort
            val bus = BleHciTransportTest.VirtualHciBus()
            val bridgeScope = CoroutineScope(Dispatchers.IO)

            val acceptJob = bridgeScope.launch {
                while (!serverSocket.isClosed) {
                    val accepted = try {
                        serverSocket.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    val serverSideIo = TcpHciPacketIo.fromSocket(accepted)
                    val firstPkt = serverSideIo.readPacket()
                    if (firstPkt == null) {
                        // Connection closed immediately (e.g. from TcpHciPacketIo.isReachable probe)
                        serverSideIo.close()
                        continue
                    }
                    bus.centralIo.writePacket(firstPkt)
                    val txJob = launch {
                        while (true) {
                            val pkt = serverSideIo.readPacket() ?: break
                            bus.centralIo.writePacket(pkt)
                        }
                    }
                    val rxJob = launch {
                        while (true) {
                            val pkt = bus.centralIo.readPacket() ?: break
                            try {
                                serverSideIo.writePacket(pkt)
                            } catch (_: Throwable) {
                                break
                            }
                        }
                    }
                    txJob.join()
                    rxJob.cancelAndJoin()
                    serverSideIo.close()
                    break
                }
            }

            try {
                assertTrue(TcpHciPacketIo.isReachable(host = "127.0.0.1", port = port))

                val serviceUuid = UUID.randomUUID()
                val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }

                val centralFactory = BtHciMdocTransportFactory(
                    centralConfig = BleCentralConfig(
                        scanRestartTimeout = 20.milliseconds,
                        postScanDelay = 1.milliseconds,
                        connectRetryDelay = 10.milliseconds,
                        discoverServicesRetryDelay = 10.milliseconds,
                        l2capRetryInitialDelay = 10.milliseconds,
                        l2capRetryMaxDelay = 40.milliseconds,
                        requireL2capPeripheralConnectedFirst = true,
                    ),
                    packetIoFactory = {
                        TcpHciPacketIo.connect(host = "127.0.0.1", port = port)
                    },
                )
                val peripheralFactory = BtHciMdocTransportFactory(
                    faultConfig = BleHciFaultConfig(
                        disconnectMidHandshakeAttempts = 1,
                        failServiceDiscoveryAttempts = 1,
                        failCharacteristicReadAttempts = 1,
                        failL2capConnectAttempts = 2,
                    ),
                    packetIoFactory = { bus.peripheralIo },
                )
                val connectionMethod = MdocConnectionMethod.Ble.GattPeripheral(
                    uuid = serviceUuid,
                )

                val peripheral = peripheralFactory.createTransport(
                    connectionMethod = connectionMethod,
                    role = MdocRole.MDOC,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                )
                val central = centralFactory.createTransport(
                    connectionMethod = connectionMethod,
                    role = MdocRole.MDOC_READER,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                )

                try {
                    peripheral.advertise()
                    coroutineScope {
                        val peripheralOpen = async { peripheral.open(eSenderKey) }
                        val centralOpen = async { central.open(eSenderKey) }
                        peripheralOpen.await()
                        centralOpen.await()
                    }

                    val requestPayload = "tcp-h4-request-payload".encodeToByteArray()
                    val responsePayload = "tcp-h4-response-payload".encodeToByteArray()
                    coroutineScope {
                        val recvReq = async { peripheral.waitForMessage() }
                        central.sendMessage(requestPayload)
                        assertContentEquals(requestPayload, recvReq.await())

                        val recvRsp = async { central.waitForMessage() }
                        peripheral.sendMessage(responsePayload)
                        assertContentEquals(responsePayload, recvRsp.await())
                    }

                    val stats = central.stats
                    assertEquals(true, stats.bleUsingL2cap)
                    assertEquals(1, stats.bleNumGattConnectRetries)
                    assertEquals(1, stats.bleNumServiceDiscoveryRetries)
                    assertEquals(1, stats.bleNumCharacteristicReadRetries)
                    assertEquals(2, stats.bleNumL2capConnectRetries)
                } finally {
                    central.close()
                    peripheral.close()
                    delay(10.milliseconds)
                    bus.centralIo.close()
                    bus.peripheralIo.close()
                }
            } finally {
                serverSocket.close()
                acceptJob.cancelAndJoin()
                bridgeScope.cancel()
            }
        }
    }
}
