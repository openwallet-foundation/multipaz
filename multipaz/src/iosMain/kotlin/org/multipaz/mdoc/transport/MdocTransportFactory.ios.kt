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
            if (connectionMethod.supportsCentralClientMode &&
                connectionMethod.supportsPeripheralServerMode) {
                throw IllegalArgumentException(
                    "Only Central Client or Peripheral Server mode is supported at one time, not both"
                )
            } else if (connectionMethod.supportsCentralClientMode) {
                return when (role) {
                    MdocRole.MDOC -> {
                        BleTransportCentralMdoc(
                            role,
                            options,
                            createBleCentralManagerIos(),
                            connectionMethod.centralClientModeUuid!!,
                            connectionMethod.peripheralServerModePsm
                        )
                    }
                    MdocRole.MDOC_READER -> {
                        BleTransportCentralMdocReader(
                            role,
                            options,
                            createBlePeripheralManagerIos(),
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
                            createBlePeripheralManagerIos(),
                            connectionMethod.peripheralServerModeUuid!!
                        )
                    }
                    MdocRole.MDOC_READER -> {
                        BleTransportPeripheralMdocReader(
                            role,
                            options,
                            createBleCentralManagerIos(),
                            connectionMethod.peripheralServerModeUuid!!,
                            connectionMethod.peripheralServerModePsm,
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
