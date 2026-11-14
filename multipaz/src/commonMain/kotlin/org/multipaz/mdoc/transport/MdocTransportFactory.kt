package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole

/**
 * An interface used to create [MdocTransport] instances.
 */
interface MdocTransportFactory {
    /**
     * Creates a new [MdocTransport] for a single [connectionMethod].
     *
     * @param connectionMethod the connection method to advertise or connect to.
     * @param role the role of the [MdocTransport].
     * @param options options for the [MdocTransport].
     * @return an [MdocTransport] instance, ready to be used.
     * @throws IllegalArgumentException if [connectionMethod] is unsupported or invalid for [role].
     */
    fun createTransport(
        connectionMethod: MdocConnectionMethod,
        role: MdocRole,
        options: MdocTransportOptions = MdocTransportOptions(),
    ): MdocTransport

    /**
     * Creates the [MdocTransport] instances needed to serve [connectionMethods].
     *
     * If [connectionMethods] is empty, this returns `emptyList()`.
     *
     * Implementations that support dual-mode BLE Peripheral operation coalesce co-hosted BLE
     * Peripheral modes sharing the same non-null service UUID (i.e.,
     * [MdocConnectionMethod.Ble.DirectL2capPeripheral] and [MdocConnectionMethod.Ble.GattPeripheral]
     * for [MdocRole.MDOC], or [MdocConnectionMethod.Ble.DirectL2capCentral] and
     * [MdocConnectionMethod.Ble.GattCentral] for [MdocRole.MDOC_READER]) onto a single
     * [MdocTransport], so the returned list may contain fewer elements than [connectionMethods].
     * The default implementation invokes [createTransport] for each element in [connectionMethods].
     *
     * @param connectionMethods the connection methods to create transports for.
     * @param role the role of the [MdocTransport] instances.
     * @param options options for the [MdocTransport] instances.
     * @return the created [MdocTransport] instances, or `emptyList()` if [connectionMethods] is empty.
     * @throws IllegalArgumentException if any entry in [connectionMethods] is unsupported or invalid
     *   for [role].
     */
    fun createTransports(
        connectionMethods: List<MdocConnectionMethod>,
        role: MdocRole,
        options: MdocTransportOptions = MdocTransportOptions(),
    ): List<MdocTransport> = connectionMethods.map { createTransport(it, role, options) }

    /**
     * The default factory for the platform.
     */
    object Default : MdocTransportFactory {
        override fun createTransport(
            connectionMethod: MdocConnectionMethod,
            role: MdocRole,
            options: MdocTransportOptions,
        ): MdocTransport = defaultMdocTransportFactoryCreateTransport(connectionMethod, role, options)

        override fun createTransports(
            connectionMethods: List<MdocConnectionMethod>,
            role: MdocRole,
            options: MdocTransportOptions,
        ): List<MdocTransport> = defaultMdocTransportFactoryCreateTransports(connectionMethods, role, options)
    }
}

internal expect fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions,
): MdocTransport

internal expect fun defaultMdocTransportFactoryCreateTransports(
    connectionMethods: List<MdocConnectionMethod>,
    role: MdocRole,
    options: MdocTransportOptions,
): List<MdocTransport>
