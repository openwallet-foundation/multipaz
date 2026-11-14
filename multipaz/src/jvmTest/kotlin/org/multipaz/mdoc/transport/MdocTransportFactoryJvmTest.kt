package org.multipaz.mdoc.transport

import kotlinx.coroutines.test.runTest
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MdocTransportFactoryJvmTest {
    @Test
    fun defaultFactorySupportsNfcAndRejectsBleOnJvm() = runTest {
        assertEquals(
            emptyList(),
            MdocTransportFactory.Default.createTransports(
                connectionMethods = emptyList(),
                role = MdocRole.MDOC,
                options = MdocTransportOptions(),
            )
        )

        val nfcMethod = MdocConnectionMethod.Nfc(
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

        val nfcReaderList = MdocTransportFactory.Default.createTransports(
            connectionMethods = listOf(nfcMethod),
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions()
        )
        val nfcReader = nfcReaderList.single()
        assertTrue(nfcReader is NfcTransportMdocReader)
        nfcReader.close()

        val bleUuid = UUID.randomUUID()
        val bleMethod = MdocConnectionMethod.Ble.GattCentral(uuid = bleUuid)
        val err = assertFailsWith<IllegalArgumentException> {
            MdocTransportFactory.Default.createTransport(
                connectionMethod = bleMethod,
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions()
            )
        }
        assertTrue(err.message!!.contains("multipaz-bthci"))

        val dualErr = assertFailsWith<IllegalArgumentException> {
            MdocTransportFactory.Default.createTransports(
                connectionMethods = listOf(
                    MdocConnectionMethod.Ble.DirectL2capCentral(uuid = bleUuid),
                    bleMethod,
                ),
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = true)
            )
        }
        assertTrue(dualErr.message!!.contains("multipaz-bthci"))
    }
}
