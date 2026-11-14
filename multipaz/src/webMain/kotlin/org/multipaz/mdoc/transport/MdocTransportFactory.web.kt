package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole

internal actual fun defaultMdocTransportFactoryCreateTransport(
    connectionMethod: MdocConnectionMethod,
    role: MdocRole,
    options: MdocTransportOptions,
): MdocTransport {
    throw NotImplementedError("MdocTransportFactory is not available for JS")
}

internal actual fun defaultMdocTransportFactoryCreateTransports(
    connectionMethods: List<MdocConnectionMethod>,
    role: MdocRole,
    options: MdocTransportOptions,
): List<MdocTransport> {
    if (connectionMethods.isEmpty()) {
        return emptyList()
    }
    throw NotImplementedError("MdocTransportFactory is not available for JS")
}
