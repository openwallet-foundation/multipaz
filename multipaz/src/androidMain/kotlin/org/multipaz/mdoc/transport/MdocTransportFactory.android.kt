package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
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
        options: MdocTransportOptions,
    ): MdocTransport = createTransports(listOf(connectionMethod), role, options).single()

    override fun createTransports(
        connectionMethods: List<MdocConnectionMethod>,
        role: MdocRole,
        options: MdocTransportOptions,
    ): List<MdocTransport> = createMdocTransports(
        connectionMethods = connectionMethods,
        role = role,
        options = options,
        centralConfig = centralConfig,
        centralHalFactory = { BleCentralHalAndroid() },
        peripheralHalFactory = { BlePeripheralHalAndroid() },
        nonBleTransportFactory = { method ->
            when (method) {
                is MdocConnectionMethod.Nfc -> when (role) {
                    MdocRole.MDOC -> NfcTransportMdoc(role, options, method)
                    MdocRole.MDOC_READER -> NfcTransportMdocReader(role, options, method)
                }
                else -> throw IllegalArgumentException("$method is not supported")
            }
        },
    )
}

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions,
): MdocTransport = AndroidMdocTransportFactory().createTransport(connectionMethod, role, options)

internal actual fun defaultMdocTransportFactoryCreateTransports(
    connectionMethods: List<MdocConnectionMethod>,
    role: MdocRole,
    options: MdocTransportOptions,
): List<MdocTransport> = AndroidMdocTransportFactory().createTransports(connectionMethods, role, options)
