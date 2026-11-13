package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.role.MdocRole

/**
 * An [MdocTransportFactory] for Android that uses the platform Android Bluetooth stack
 * (`BluetoothGatt`, `BluetoothLeScanner`, `BluetoothGattServer`, `BluetoothLeAdvertiser`, and
 * `BluetoothSocket` L2CAP channels) with configurable [BleCentralConfig] retry and timeout parameters.
 *
 * @param centralConfig BLE Central retry, scan-restart, and L2CAP connection timing parameters.
 */
class AndroidMdocTransportFactory(
    private val centralConfig: BleCentralConfig = BleCentralConfig()
) : MdocTransportFactory {
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
                centralHalFactory = { BleCentralHalAndroid() },
                peripheralHalFactory = { BlePeripheralHalAndroid() }
            )
            is MdocConnectionMethodNfc -> {
                when (role) {
                    MdocRole.MDOC -> NfcTransportMdoc(role, options, connectionMethod)
                    MdocRole.MDOC_READER -> NfcTransportMdocReader(role, options, connectionMethod)
                }
            }
            else -> throw IllegalArgumentException("$connectionMethod is not supported")
        }
    }
}

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions
): MdocTransport = AndroidMdocTransportFactory().createTransport(connectionMethod, role, options)
