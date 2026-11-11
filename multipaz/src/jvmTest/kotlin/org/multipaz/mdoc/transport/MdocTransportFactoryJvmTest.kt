package org.multipaz.mdoc.transport

import kotlinx.coroutines.test.runTest
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MdocTransportFactoryJvmTest {
    @Test
    fun defaultFactorySupportsNfcAndRejectsBleOnJvm() = runTest {
        val nfcMethod = MdocConnectionMethodNfc(
            commandDataFieldMaxLength = 0xFF,
            responseDataFieldMaxLength = 0x100
        )
        val nfcMdoc = MdocTransportFactory.Default.createTransport(
            connectionMethod = nfcMethod,
            role = MdocRole.MDOC,
            options = MdocTransportOptions()
        )
        assertTrue(nfcMdoc is NfcTransportMdoc)
        nfcMdoc.close()

        val nfcReader = MdocTransportFactory.Default.createTransport(
            connectionMethod = nfcMethod,
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions()
        )
        assertTrue(nfcReader is NfcTransportMdocReader)
        nfcReader.close()

        val bleMethod = MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = UUID.randomUUID()
        )
        val err = assertFailsWith<IllegalArgumentException> {
            MdocTransportFactory.Default.createTransport(
                connectionMethod = bleMethod,
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions()
            )
        }
        assertTrue(err.message!!.contains("multipaz-bthci"))
    }
}
