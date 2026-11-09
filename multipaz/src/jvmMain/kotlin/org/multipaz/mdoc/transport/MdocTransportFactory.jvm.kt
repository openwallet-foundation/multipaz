package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole

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
 */
internal fun createBleCentralManagerJvm(
    packetIoFactory: suspend () -> HciPacketIo = {
        LinuxHciSocketPacketIo().also { it.open() }
    }
): BleCentralManager = BleCentralManagerImpl(
    hal = BleCentralHalHci(packetIoFactory = packetIoFactory),
    requireL2capPeripheralConnectedFirst = true
)

/**
 * Creates a [BlePeripheralManager] on Linux JVM backed by [BlePeripheralHalHci] and [LinuxHciSocketPacketIo].
 *
 * See [createBleCentralManagerJvm] and [LinuxHciSocketPacketIo] for configuration and permissions details.
 */
internal fun createBlePeripheralManagerJvm(
    packetIoFactory: suspend () -> HciPacketIo = {
        LinuxHciSocketPacketIo().also { it.open() }
    }
): BlePeripheralManager = BlePeripheralManagerImpl(
    hal = BlePeripheralHalHci(packetIoFactory = packetIoFactory)
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
