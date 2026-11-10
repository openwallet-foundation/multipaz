package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Creates a [BleCentralManager] on Linux JVM backed by [BleCentralHalHci] and [LinuxHciSocketPacketIo].
 *
 * By default, [LinuxHciSocketPacketIo] either:
 * - Adopts a pre-opened `HCI_CHANNEL_USER` file descriptor if `-Dorg.multipaz.ble.hciFd=<fd>` or
 *   `MULTIPAZ_BLE_HCI_FD=<fd>` is set (allowing unprivileged execution via a launcher that binds
 *   `hciN`, drops privileges, and `exec`s `java`), keeping the descriptor open across sessions; or
 * - Binds directly to `hci<N>` (`-Dorg.multipaz.ble.hciDeviceIndex=<N>` or `MULTIPAZ_BLE_HCI_DEV=<N>`,
 *   defaulting to `hci0`), which requires `CAP_NET_ADMIN` or `uid 0`.
 *
 * See [LinuxHciSocketPacketIo] for detailed setup instructions and a privileged launcher example.
 *
 * @param gapDeviceName the GAP Device Name (`0x2A00`) reported if the connected peripheral queries
 *   the central's GAP server on `CID 0x0004` (defaults to `"Multipaz"`).
 * @param scanInterval the LE scan interval (`2.5 ms` to `10.24 s`, defaults to `60.milliseconds`).
 * @param scanWindow the LE scan window (`2.5 ms` to `10.24 s`, must be `<= scanInterval`, defaults to
 *   `30.milliseconds`).
 * @param scanRestartTimeout if non-null, periodically stops and restarts BLE scanning after this
 *   duration while waiting for a peripheral (defaults to `3.seconds`).
 * @param connectTimeout per-attempt timeout for `HCI_LE_Create_Connection` before cancelling and
 *   retrying (defaults to `3.seconds`).
 * @param packetIoFactory factory that opens the [HciPacketIo] transport for each BLE session.
 */
internal fun createBleCentralManagerJvm(
    gapDeviceName: String = "Multipaz",
    scanInterval: Duration = 60.milliseconds,
    scanWindow: Duration = 30.milliseconds,
    scanRestartTimeout: Duration? = 3.seconds,
    connectTimeout: Duration = 3.seconds,
    packetIoFactory: suspend () -> HciPacketIo = {
        LinuxHciSocketPacketIo().also { it.open() }
    }
): BleCentralManager = BleCentralManagerImpl(
    hal = BleCentralHalHci(
        gapDeviceName = gapDeviceName,
        scanInterval = scanInterval,
        scanWindow = scanWindow,
        connectTimeout = connectTimeout,
        packetIoFactory = packetIoFactory
    ),
    scanRestartTimeout = scanRestartTimeout,
    requireL2capPeripheralConnectedFirst = true
)

/**
 * Creates a [BlePeripheralManager] on Linux JVM backed by [BlePeripheralHalHci] and [LinuxHciSocketPacketIo].
 *
 * See [createBleCentralManagerJvm] and [LinuxHciSocketPacketIo] for configuration and permissions details.
 */
internal fun createBlePeripheralManagerJvm(
    gapDeviceName: String = "Multipaz",
    packetIoFactory: suspend () -> HciPacketIo = {
        LinuxHciSocketPacketIo().also { it.open() }
    }
): BlePeripheralManager = BlePeripheralManagerImpl(
    hal = BlePeripheralHalHci(
        gapDeviceName = gapDeviceName,
        packetIoFactory = packetIoFactory
    )
)

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions
): MdocTransport {
    when (connectionMethod) {
        is MdocConnectionMethodBle -> {
            if (connectionMethod.supportsCentralClientMode &&
                connectionMethod.supportsPeripheralServerMode
            ) {
                throw IllegalArgumentException(
                    "Only Central Client or Peripheral Server mode is supported at one time, not both"
                )
            } else if (connectionMethod.supportsCentralClientMode) {
                return when (role) {
                    MdocRole.MDOC -> {
                        BleTransportCentralMdoc(
                            role,
                            options,
                            createBleCentralManagerJvm(),
                            connectionMethod.centralClientModeUuid!!,
                            connectionMethod.peripheralServerModePsm
                        )
                    }
                    MdocRole.MDOC_READER -> {
                        BleTransportCentralMdocReader(
                            role,
                            options,
                            createBlePeripheralManagerJvm(),
                            connectionMethod.centralClientModeUuid!!
                        )
                    }
                }
            } else {
                return when (role) {
                    MdocRole.MDOC -> {
                        BleTransportPeripheralMdoc(
                            role,
                            options,
                            createBlePeripheralManagerJvm(),
                            connectionMethod.peripheralServerModeUuid!!
                        )
                    }
                    MdocRole.MDOC_READER -> {
                        BleTransportPeripheralMdocReader(
                            role,
                            options,
                            createBleCentralManagerJvm(),
                            connectionMethod.peripheralServerModeUuid!!,
                            connectionMethod.peripheralServerModePsm,
                        )
                    }
                }
            }
        }
        else -> {
            throw IllegalArgumentException("$connectionMethod is not supported on JVM")
        }
    }
}
