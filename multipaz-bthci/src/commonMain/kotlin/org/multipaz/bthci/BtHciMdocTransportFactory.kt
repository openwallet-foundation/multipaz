package org.multipaz.bthci

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.BleCentralConfig
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.createBleMdocTransport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An [MdocTransportFactory] that creates BLE [MdocTransport] instances backed by Multipaz's
 * user-space Bluetooth HCI/L2CAP/ATT stack ([BleCentralHalHci] and [BlePeripheralHalHci]).
 *
 * Non-BLE connection methods (such as [org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc])
 * are delegated to [fallbackFactory].
 *
 * On Linux JVM, the default [packetIoFactory] opens a `LinuxHciSocketPacketIo` bound via
 * `HCI_CHANNEL_USER` (or adopts a pre-opened file descriptor configured via
 * `-Dorg.multipaz.ble.hciFd=<fd>` or `MULTIPAZ_BLE_HCI_FD=<fd>`). On other platforms, callers must
 * supply an explicit [packetIoFactory] (for example, over USB or UART H4).
 *
 * @param gapDeviceName the GAP Device Name (`0x2A00`) exposed on `CID 0x0004` (defaults to `"Multipaz"`).
 * @param randomAddress optional 6-byte static random LE address in HCI little-endian byte order
 *   (where byte 5 is the MSB with top two bits set to `11`), or `null` to generate a fresh random
 *   address per session.
 * @param scanInterval the LE scan interval (`2.5 ms` to `10.24 s`, defaults to `60.milliseconds`).
 * @param scanWindow the LE scan window (`2.5 ms` to `10.24 s`, must be `<= scanInterval`, defaults to
 *   `30.milliseconds`).
 * @param connectTimeout per-attempt timeout for `HCI_LE_Create_Connection` before cancelling and
 *   retrying (defaults to `3.seconds`).
 * @param centralConfig BLE Central retry, scan-restart, and L2CAP connection timing parameters.
 * @param faultConfig optional [BleHciFaultConfig] for injecting deterministic BLE handshake faults.
 * @param fallbackFactory factory used for non-BLE [MdocConnectionMethod]s (defaults to
 *   [MdocTransportFactory.Default]).
 * @param packetIoFactory factory that opens an [HciPacketIo] transport for each BLE session.
 */
class BtHciMdocTransportFactory(
    private val gapDeviceName: String = "Multipaz",
    randomAddress: ByteArray? = null,
    private val scanInterval: Duration = 60.milliseconds,
    private val scanWindow: Duration = 30.milliseconds,
    private val connectTimeout: Duration = 3.seconds,
    private val centralConfig: BleCentralConfig = BleCentralConfig(
        scanRestartTimeout = 3.seconds,
        requireL2capPeripheralConnectedFirst = true,
    ),
    private val faultConfig: BleHciFaultConfig = BleHciFaultConfig(),
    private val fallbackFactory: MdocTransportFactory = MdocTransportFactory.Default,
    private val packetIoFactory: suspend () -> HciPacketIo = ::openDefaultHciPacketIo,
) : MdocTransportFactory {
    private val randomAddressBytes: ByteArray? = randomAddress?.copyOf()

    init {
        if (randomAddressBytes != null) {
            require(randomAddressBytes.size == 6) {
                "Expected 6-byte random address, got ${randomAddressBytes.size}"
            }
        }
    }

    override fun createTransport(
        connectionMethod: MdocConnectionMethod,
        role: MdocRole,
        options: MdocTransportOptions
    ): MdocTransport {
        return when (connectionMethod) {
            is MdocConnectionMethodBle -> createBleMdocTransport(
                connectionMethod = connectionMethod,
                role = role,
                options = options,
                centralConfig = centralConfig,
                centralHalFactory = {
                    BleCentralHalHci(
                        gapDeviceName = gapDeviceName,
                        randomAddress = randomAddressBytes?.copyOf(),
                        scanInterval = scanInterval,
                        scanWindow = scanWindow,
                        connectTimeout = connectTimeout,
                        packetIoFactory = packetIoFactory,
                    )
                },
                peripheralHalFactory = {
                    BlePeripheralHalHci(
                        gapDeviceName = gapDeviceName,
                        randomAddress = randomAddressBytes?.copyOf(),
                        faultConfig = faultConfig,
                        packetIoFactory = packetIoFactory,
                    )
                },
            )
            else -> fallbackFactory.createTransport(connectionMethod, role, options)
        }
    }
}
