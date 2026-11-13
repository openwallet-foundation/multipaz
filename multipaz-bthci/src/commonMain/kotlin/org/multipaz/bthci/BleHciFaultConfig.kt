package org.multipaz.bthci

import kotlin.time.Duration

/**
 * Fault-injection configuration for [BlePeripheralHalHci] and [BtHciMdocTransportFactory] to
 * deterministically stress-test BLE Central retry paths over a virtual or physical HCI controller
 * (such as Android's `netsimd` / Rootcanal).
 *
 * @property initialAdvertisingDelay delay before enabling `HCI_LE_Set_Advertise_Enable(1)` on the
 *   first advertising window after reporting `onAdvertisingStarted`, forcing the Central's initial
 *   scan window(s) to time out and increment `MdocTransportStats.bleNumScanRestarts`.
 * @property disconnectMidHandshakeAttempts number of initial BLE connections to terminate via
 *   `HCI_Disconnect (0x0406)` during GATT service discovery, forcing the Central to reconnect and
 *   increment `MdocTransportStats.bleNumGattConnectRetries`.
 * @property failMtuExchangeAttempts number of initial `ATT_EXCHANGE_MTU_REQ (0x02)` requests to
 *   reject with `ATT_ERROR_RSP (0x06 Request Not Supported)`, exercising
 *   `MdocTransportStats.bleNumMtuRequestRetries` and fallback MTU handling.
 * @property failServiceDiscoveryAttempts number of initial Primary Service discovery requests
 *   (`ATT_FIND_BY_TYPE_VALUE_REQ` or `ATT_READ_BY_GROUP_TYPE_REQ` at handle `1`) to reject with
 *   `ATT_ERROR_RSP (0x0A Attribute Not Found)`, exercising
 *   `MdocTransportStats.bleNumServiceDiscoveryRetries`.
 * @property failCharacteristicDiscoveryAttempts number of initial Characteristic declaration
 *   discovery requests (`ATT_READ_BY_TYPE_REQ` for `0x2803`) to reject with `ATT_ERROR_RSP (0x0A
 *   Attribute Not Found)`, exercising `MdocTransportStats.bleNumCharacteristicDiscoveryRetries`.
 * @property failCharacteristicReadAttempts number of initial ISO characteristic value reads
 *   (`ATT_READ_REQ` on `L2CAP` or `Ident`) to reject with `ATT_ERROR_RSP (0x0E Unlikely Error)`,
 *   exercising `MdocTransportStats.bleNumCharacteristicReadRetries`.
 * @property failCccdWriteAttempts number of initial CCCD (`0x2902`) descriptor writes
 *   (`ATT_WRITE_REQ`) to reject with `ATT_ERROR_RSP (0x0E Unlikely Error)`, exercising
 *   `MdocTransportStats.bleNumDescriptorWriteRetries`.
 * @property failL2capConnectAttempts number of initial `L2CAP_LE_CREDIT_BASED_CONNECTION_REQ
 *   (0x14)` requests to reject with `L2CAP_LE_CREDIT_BASED_CONNECTION_RSP (0x15, result=0x0004 No
 *   resources available)`, exercising `MdocTransportStats.bleNumL2capConnectRetries`.
 */
data class BleHciFaultConfig(
    val initialAdvertisingDelay: Duration = Duration.ZERO,
    val disconnectMidHandshakeAttempts: Int = 0,
    val failMtuExchangeAttempts: Int = 0,
    val failServiceDiscoveryAttempts: Int = 0,
    val failCharacteristicDiscoveryAttempts: Int = 0,
    val failCharacteristicReadAttempts: Int = 0,
    val failCccdWriteAttempts: Int = 0,
    val failL2capConnectAttempts: Int = 0,
)
