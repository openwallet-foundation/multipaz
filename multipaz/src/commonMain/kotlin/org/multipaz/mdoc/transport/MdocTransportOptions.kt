package org.multipaz.mdoc.transport

/**
 * Options for using a [MdocTransport].
 *
 * @property bleUseL2CAP set to `true` to advertise/use the ISO/IEC 18013-5:2021 GATT L2CAP PSM characteristic
 *   (`0000000A` / `0000000B`) when operating in a GATT BLE mode, or `false` otherwise. Note that Second Edition
 *   direct BLE L2CAP (`BleOptions` keys `2` / `3`) is modeled directly via
 *   [org.multipaz.mdoc.connectionmethod.MdocConnectionMethod.Ble.DirectL2capPeripheral] and
 *   [org.multipaz.mdoc.connectionmethod.MdocConnectionMethod.Ble.DirectL2capCentral].
 */
data class MdocTransportOptions(
    val bleUseL2CAP: Boolean = false,
)
