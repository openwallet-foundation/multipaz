package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.role.MdocRole

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions
): MdocTransport {
    when (connectionMethod) {
        is MdocConnectionMethodBle -> {
            if (connectionMethod.hasCentralMode && connectionMethod.hasPeripheralMode) {
                throw IllegalArgumentException(
                    "Only Central Client or Peripheral Server mode is supported at one time, not both"
                )
            }
            if (!connectionMethod.hasCentralMode && !connectionMethod.hasPeripheralMode) {
                throw IllegalArgumentException(
                    "Either Central Client or Peripheral Server mode must be enabled"
                )
            }
            return if (connectionMethod.hasCentralMode) {
                val uuid = requireNotNull(connectionMethod.centralModeUuid) {
                    "centralModeUuid is required when a central mode is enabled"
                }
                when (role) {
                    MdocRole.MDOC -> {
                        BleTransportCentralMdoc(
                            role = role,
                            options = options,
                            centralManager = createBleCentralManagerIos(),
                            uuid = uuid,
                            psm = connectionMethod.directL2capPsm,
                            gattEnabled = connectionMethod.gattCentral,
                            directL2capEnabled = connectionMethod.directL2capCentral,
                        )
                    }
                    MdocRole.MDOC_READER -> {
                        BleTransportCentralMdocReader(
                            role = role,
                            options = options,
                            peripheralManager = createBlePeripheralManagerIos(),
                            uuid = uuid,
                            gattEnabled = connectionMethod.gattCentral,
                            directL2capRequested = connectionMethod.directL2capCentral,
                        )
                    }
                }
            } else {
                val uuid = requireNotNull(connectionMethod.peripheralModeUuid) {
                    "peripheralModeUuid is required when a peripheral mode is enabled"
                }
                when (role) {
                    MdocRole.MDOC -> {
                        BleTransportPeripheralMdoc(
                            role = role,
                            options = options,
                            peripheralManager = createBlePeripheralManagerIos(),
                            uuid = uuid,
                            gattEnabled = connectionMethod.gattPeripheral,
                            directL2capRequested = connectionMethod.directL2capPeripheral,
                        )
                    }
                    MdocRole.MDOC_READER -> {
                        BleTransportPeripheralMdocReader(
                            role = role,
                            options = options,
                            centralManager = createBleCentralManagerIos(),
                            uuid = uuid,
                            psm = connectionMethod.directL2capPsm,
                            gattEnabled = connectionMethod.gattPeripheral,
                            directL2capEnabled = connectionMethod.directL2capPeripheral,
                        )
                    }
                }
            }
        }
        is MdocConnectionMethodNfc -> {
            return when (role) {
                MdocRole.MDOC -> throw NotImplementedError("Not yet implemented")
                MdocRole.MDOC_READER -> {
                    NfcTransportMdocReader(
                        role,
                        options,
                        connectionMethod
                    )
                }
            }
        }
        else -> {
            throw IllegalArgumentException("$connectionMethod is not supported")
        }
    }
}
