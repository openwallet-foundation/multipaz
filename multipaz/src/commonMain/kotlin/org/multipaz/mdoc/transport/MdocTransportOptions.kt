package org.multipaz.mdoc.transport

/**
 * Options for using a [MdocTransport].
 *
 * @property bleUseL2CAP set to `true` to advertise/use the ISO/IEC 18013-5:2021 GATT L2CAP PSM characteristic
 *   (`0000000A` / `0000000B`) when operating in a GATT BLE mode, or `false` otherwise. Note that Second Edition
 *   direct BLE L2CAP (`BleOptions` keys `2` / `3`) is modeled directly via
 *   [org.multipaz.mdoc.connectionmethod.MdocConnectionMethod.Ble.DirectL2capPeripheral] and
 *   [org.multipaz.mdoc.connectionmethod.MdocConnectionMethod.Ble.DirectL2capCentral].
 * @property bleExportPeripheralAddress set to `true` to include the peripheral's BLE device address
 *   (`BleOptions` key `20` / BLE OOB AD type `0x1B`) in engagement connection methods when acting as a
 *   BLE peripheral and the underlying Bluetooth stack exposes it, or `false` to omit it.
 * @property bleUsePeripheralAddress set to `true` to connect directly using the peer's BLE peripheral
 *   device address without scanning for the service UUID when acting as a BLE central and the peer
 *   provided a peripheral address in engagement (if supported by the underlying Bluetooth stack), or
 *   `false` to always scan for the service UUID.
 */
data class MdocTransportOptions(
    val bleUseL2CAP: Boolean = false,
    val bleExportPeripheralAddress: Boolean = true,
    val bleUsePeripheralAddress: Boolean = true,
)
