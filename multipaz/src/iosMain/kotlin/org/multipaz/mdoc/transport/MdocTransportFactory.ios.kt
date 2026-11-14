package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions,
): MdocTransport = defaultMdocTransportFactoryCreateTransports(
    connectionMethods = listOf(connectionMethod),
    role = role,
    options = options,
).single()

internal actual fun defaultMdocTransportFactoryCreateTransports(
    connectionMethods: List<MdocConnectionMethod>,
    role: MdocRole,
    options: MdocTransportOptions,
): List<MdocTransport> = createMdocTransportsFromManagers(
    connectionMethods = connectionMethods,
    role = role,
    options = options,
    centralManagerFactory = { createBleCentralManagerIos() },
    peripheralManagerFactory = { createBlePeripheralManagerIos() },
    nonBleTransportFactory = { method ->
        when (method) {
            is MdocConnectionMethod.Nfc -> when (role) {
                MdocRole.MDOC -> throw NotImplementedError("Not yet implemented")
                MdocRole.MDOC_READER -> NfcTransportMdocReader(role, options, method)
            }
            else -> throw IllegalArgumentException("$method is not supported")
        }
    },
)
