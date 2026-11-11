package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.role.MdocRole

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions
): MdocTransport {
    return when (connectionMethod) {
        is MdocConnectionMethodNfc -> {
            when (role) {
                MdocRole.MDOC -> {
                    NfcTransportMdoc(
                        role,
                        options,
                        connectionMethod
                    )
                }
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
            throw IllegalArgumentException(
                "$connectionMethod is not supported by MdocTransportFactory.Default on JVM; " +
                    "for Bluetooth HCI use BtHciMdocTransportFactory from multipaz-bthci"
            )
        }
    }
}
