package org.multipaz.mdoc.engagement

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocEngagementType
import org.multipaz.mdoc.origininfo.OriginInfoDomain
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceEngagementTest {

    @Test
    fun testAgainstVector2021() {
        val encodedDeviceEngagement = TestVectors.ISO_18013_5_ANNEX_D_DEVICE_ENGAGEMENT.fromHex()
        val deviceEngagement = DeviceEngagement.fromDataItem(Cbor.decode(encodedDeviceEngagement))
        assertEquals("1.0", deviceEngagement.version)
        val connectionMethods = deviceEngagement.connectionMethods
        assertEquals(1, connectionMethods.size.toLong())
        assertTrue(connectionMethods[0] is MdocConnectionMethod.Ble.GattCentral)
        val cmBle = connectionMethods[0] as MdocConnectionMethod.Ble.GattCentral
        assertEquals(
            "45efef74-2b2c-4837-a9a3-b0e1d05a6917",
            cmBle.uuid.toString()
        )
        assertEquals(
            TestVectors.ISO_18013_5_ANNEX_D_E_DEVICE_KEY_BYTES,
            deviceEngagement.eDeviceKeyBytes.toByteArray().toHex()
        )
    }

    @Test
    fun testNoConnectionMethodsOrOriginInfos() = runTest {
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(
            eDeviceKey = eDeviceKey.publicKey,
        ) {}
        assertEquals(deviceEngagement.eDeviceKey, eDeviceKey.publicKey)
        assertEquals("1.0", deviceEngagement.version)
        assertEquals(0, deviceEngagement.connectionMethods.size.toLong())
        assertEquals(0, deviceEngagement.originInfos.size.toLong())

        assertEquals(
            deviceEngagement,
            DeviceEngagement.fromDataItem(deviceEngagement.toDataItem())
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDeviceEngagementQrBleCentralClientMode() = runTest {
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val uuid = UUID.randomUUID()
        val deviceEngagement = buildDeviceEngagement(
            eDeviceKey = eDeviceKey.publicKey,
        ) {
            addConnectionMethod(
                MdocConnectionMethod.Ble.GattCentral(uuid = uuid)
            )
        }
        assertEquals("1.0", deviceEngagement.version)

        val parsedDeviceEngagement = DeviceEngagement.fromDataItem(
            deviceEngagement.toDataItem()
        )
        assertEquals(eDeviceKey.publicKey, parsedDeviceEngagement.eDeviceKey)
        assertEquals("1.0", parsedDeviceEngagement.version)
        assertEquals(1, parsedDeviceEngagement.connectionMethods.size)
        val cm = parsedDeviceEngagement.connectionMethods[0] as MdocConnectionMethod.Ble.GattCentral
        assertEquals(uuid, cm.uuid)
        assertEquals(0, parsedDeviceEngagement.originInfos.size)

        assertEquals(
            deviceEngagement,
            DeviceEngagement.fromDataItem(deviceEngagement.toDataItem())
        )
    }

    @Test
    fun checkOriginInfos() = runTest {
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        assertEquals(
            "DeviceEngagement version must be 1.1 or higher when originInfos or capabilities are non-empty",
            assertFailsWith(IllegalStateException::class) {
                buildDeviceEngagement(
                    version = "1.0",
                    eDeviceKey = eDeviceKey.publicKey,
                ) {
                    addOriginInfo(OriginInfoDomain("www.example.com"))
                }
            }.message
        )

        val deviceEngagement = buildDeviceEngagement(
            eDeviceKey = eDeviceKey.publicKey,
        ) {
            addOriginInfo(OriginInfoDomain("www.example.com"))
        }
        assertEquals("1.1", deviceEngagement.version)
        assertEquals(1, deviceEngagement.originInfos.size)
        assertEquals(OriginInfoDomain("www.example.com"), deviceEngagement.originInfos[0])

        assertEquals(
            deviceEngagement,
            DeviceEngagement.fromDataItem(deviceEngagement.toDataItem())
        )
    }

    @Test
    fun checkCapabilities() = runTest {
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        assertEquals(
            "DeviceEngagement version must be 1.1 or higher when originInfos or capabilities are non-empty",
            assertFailsWith(IllegalStateException::class) {
                buildDeviceEngagement(
                    version = "1.0",
                    eDeviceKey = eDeviceKey.publicKey,
                ) {
                    addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
                }
            }.message
        )

        val deviceEngagement = buildDeviceEngagement(
            eDeviceKey = eDeviceKey.publicKey,
        ) {
            addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        }
        assertEquals("1.1", deviceEngagement.version)
        assertEquals(1, deviceEngagement.capabilities.size)
        assertEquals(
            Simple.TRUE,
            deviceEngagement.capabilities[Capability.EXTENDED_REQUEST_SUPPORT]
        )

        assertEquals(
            deviceEngagement,
            DeviceEngagement.fromDataItem(deviceEngagement.toDataItem())
        )
    }

    @Test
    fun testReaderEngagement() = runTest {
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val bleMethod = MdocConnectionMethod.Ble.GattCentral(
            uuid = UUID.randomUUID()
        )
        val readerEngagement = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {
            addConnectionMethod(bleMethod)
        }
        assertEquals("1.1", readerEngagement.version)
        assertEquals(eReaderKey.publicKey, readerEngagement.eReaderKey)
        assertEquals(listOf(bleMethod), readerEngagement.connectionMethods)
        assertEquals(
            readerEngagement,
            ReaderEngagement.fromDataItem(readerEngagement.toDataItem())
        )

        // Verify READER_QR context allows reader-provided peripheralModeUuid and DirectL2capCentral
        val centralUuid = UUID.randomUUID()
        val peripheralUuid = UUID.randomUUID()
        val readerQrMethods = listOf(
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128),
            MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid),
            MdocConnectionMethod.Ble.GattPeripheral(uuid = peripheralUuid),
        )
        val readerQrEngagement = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {
            readerQrMethods.forEach { addConnectionMethod(it) }
        }
        assertEquals(
            readerQrEngagement,
            ReaderEngagement.fromDataItem(readerQrEngagement.toDataItem(MdocEngagementType.READER_QR))
        )

        // Verify CCE_SELECT rejects multiple connection methods in DeviceEngagement
        val twoMethodDe = buildDeviceEngagement(eDeviceKey = eReaderKey.publicKey) {
            addConnectionMethod(
                MdocConnectionMethod.Ble.GattPeripheral(uuid = UUID.randomUUID())
            )
            addConnectionMethod(
                MdocConnectionMethod.Ble.GattCentral(uuid = UUID.randomUUID())
            )
        }
        assertFailsWith<IllegalArgumentException> {
            twoMethodDe.toDataItem(MdocEngagementType.CCE_SELECT)
        }

        val nfcOnlyReaderEngagement = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {}
        assertTrue(nfcOnlyReaderEngagement.connectionMethods.isEmpty())
        assertFalse(nfcOnlyReaderEngagement.toDataItem().hasKey(2))
        assertEquals(
            nfcOnlyReaderEngagement,
            ReaderEngagement.fromDataItem(nfcOnlyReaderEngagement.toDataItem())
        )
    }

}