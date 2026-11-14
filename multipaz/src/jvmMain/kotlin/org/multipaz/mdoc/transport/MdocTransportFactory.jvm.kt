package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions,
): MdocTransport {
    if (connectionMethod is MdocConnectionMethod.Nfc) {
        return when (role) {
            MdocRole.MDOC -> NfcTransportMdoc(role, options, connectionMethod)
            MdocRole.MDOC_READER -> NfcTransportMdocReader(role, options, connectionMethod)
        }
    }
    throw IllegalArgumentException(
        "$connectionMethod is not supported by MdocTransportFactory.Default on JVM; " +
            "for Bluetooth HCI use BtHciMdocTransportFactory from multipaz-bthci"
    )
}

internal actual fun defaultMdocTransportFactoryCreateTransports(
    connectionMethods: List<MdocConnectionMethod>,
    role: MdocRole,
    options: MdocTransportOptions,
): List<MdocTransport> = connectionMethods.map {
    defaultMdocTransportFactoryCreateTransport(it, role, options)
}
