/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.AlternativeCarrierRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdocConnectionMethodTest {

    @Test
    fun testConnectionMethodNfc() {
        val cm = MdocConnectionMethod.Nfc(4096, 32768)
        assertEquals(
            "nfc:cmd_max_length=4096:resp_max_length=32768",
            cm.toString()
        )
        val decodedList = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement())
        assertEquals(listOf(cm), decodedList)
        assertEquals(
            """[
  1,
  1,
  {
    0: 4096,
    1: 32768
  }
]""",
            Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        val ndefList = MdocConnectionMethod.encodeNdefHandoverCarriers(
            methods = listOf(cm),
            handoverType = MdocNfcHandoverType.STATIC_HS,
            sharedAuxiliaryReferences = listOf("mdoc"),
        )
        assertEquals(1, ndefList.size)
        val decodedNfc = MdocConnectionMethod.decodeNdefHandoverCarrier(
            record = ndefList.single().carrierRecord,
            senderRole = MdocRole.MDOC,
            fallbackUuid = null,
            auxiliaryRecords = ndefList.single().auxiliaryRecords,
        )
        assertEquals(listOf(cm), decodedNfc)
    }

    @Test
    fun testConnectionMethodHttp() {
        val cm = MdocConnectionMethod.Http(uri = "https://www.example.com/foobar")
        assertEquals("http:uri=https://www.example.com/foobar", cm.toString())
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement())
        assertEquals(listOf(cm), decoded)
        assertEquals(
            """
                [
                  4,
                  1,
                  {
                    0: "https://www.example.com/foobar"
                  }
                ]
            """.trimIndent(),
            Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
    }

    @Test
    fun testConnectionMethodWifiAware() {
        val cm = MdocConnectionMethod.WifiAware(
            passphraseInfoPassphrase = "foobar",
            channelInfoChannelNumber = 42,
            channelInfoOperatingClass = 43,
            bandInfoSupportedBands = ByteString(1, 2),
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement())
        assertEquals(listOf(cm), decoded)
        assertEquals(
            """[
  3,
  1,
  {
    0: "foobar",
    2: 42,
    1: 43,
    3: h'0102'
  }
]""",
            Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
    }

    @Test
    fun testBlePeripheralAddressEncodingAndValidation() {
        // Annex D.3.2 reader random address: wire = 28078080bf2801 -> big-endian BD_ADDR = 28:bf:80:80:07:28, RANDOM (0x01)
        val readerAddrWire = "28078080bf2801".fromHex()
        val decodedReaderAddr = BlePeripheralAddress.fromEncoded(readerAddrWire)
        assertEquals(ByteString(0x28, 0xbf.toByte(), 0x80.toByte(), 0x80.toByte(), 0x07, 0x28), decodedReaderAddr.address)
        assertEquals(BleAddressType.RANDOM, decodedReaderAddr.addressType)
        assertEquals("28-bf-80-80-07-28/random", decodedReaderAddr.toString())
        assertEquals("28078080bf2801", decodedReaderAddr.toEncoded().toHex())

        // Public address round-trip
        val publicAddr = BlePeripheralAddress(
            address = ByteString(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
            addressType = BleAddressType.PUBLIC,
        )
        assertEquals("66554433221100", publicAddr.toEncoded().toHex())
        assertEquals("11-22-33-44-55-66/public", publicAddr.toString())
        assertEquals(publicAddr, BlePeripheralAddress.fromEncoded(publicAddr.toEncoded()))

        // Invalid lengths & invalid type codes rejected
        assertFailsWith<IllegalArgumentException> {
            BlePeripheralAddress(ByteString(1, 2, 3, 4, 5), BleAddressType.PUBLIC)
        }
        assertFailsWith<IllegalArgumentException> {
            BlePeripheralAddress.fromEncoded(ByteArray(6))
        }
        assertFailsWith<IllegalArgumentException> {
            BlePeripheralAddress.fromEncoded("66554433221102".fromHex())
        }
    }

    @Test
    fun testBleModeSubtypesPropertiesWithUuidAndToString() {
        val uuid1 = UUID(0U, 1U)
        val uuid2 = UUID(0U, 2U)
        val addr = BlePeripheralAddress.fromEncoded("28078080bf2801".fromHex())

        val gp = MdocConnectionMethod.Ble.GattPeripheral(uuid = uuid1, peripheralAddress = addr)
        assertTrue(gp.isMdocPeripheral)
        assertFalse(gp.isMdocCentral)
        assertTrue(gp.isGatt)
        assertFalse(gp.isDirectL2cap)
        assertNull(gp.directL2capPsm)
        assertEquals("ble:gatt_peripheral:uuid=$uuid1:addr=28-bf-80-80-07-28/random", gp.toString())
        assertEquals(uuid2, gp.withUuid(uuid2).uuid)

        val gc = MdocConnectionMethod.Ble.GattCentral(uuid = uuid1)
        assertFalse(gc.isMdocPeripheral)
        assertTrue(gc.isMdocCentral)
        assertTrue(gc.isGatt)
        assertFalse(gc.isDirectL2cap)
        assertNull(gc.directL2capPsm)
        assertEquals("ble:gatt_central:uuid=$uuid1", gc.toString())
        assertEquals(uuid2, gc.withUuid(uuid2).uuid)

        val dlp = MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = uuid1, psm = 129)
        assertTrue(dlp.isMdocPeripheral)
        assertFalse(dlp.isMdocCentral)
        assertFalse(dlp.isGatt)
        assertTrue(dlp.isDirectL2cap)
        assertEquals(129, dlp.directL2capPsm)
        assertEquals("ble:direct_l2cap_peripheral:uuid=$uuid1:psm=129", dlp.toString())
        assertEquals(129, (dlp.withUuid(uuid2) as MdocConnectionMethod.Ble.DirectL2capPeripheral).psm)

        val dlc = MdocConnectionMethod.Ble.DirectL2capCentral(uuid = uuid1, psm = 128, peripheralAddress = addr)
        assertFalse(dlc.isMdocPeripheral)
        assertTrue(dlc.isMdocCentral)
        assertFalse(dlc.isGatt)
        assertTrue(dlc.isDirectL2cap)
        assertEquals(128, dlc.directL2capPsm)
        assertEquals("ble:direct_l2cap_central:uuid=$uuid1:psm=128:addr=28-bf-80-80-07-28/random", dlc.toString())
        assertEquals(128, (dlc.withUuid(uuid2) as MdocConnectionMethod.Ble.DirectL2capCentral).psm)
    }

    @Test
    fun testConnectionMethodBleSingleAndDualRoleEncoding() {
        val uuidPeripheral = UUID(0U, 1U)
        val uuidCentral = UUID(123456789U, 987654321U)

        // Single GattCentral
        val cmCentral = MdocConnectionMethod.Ble.GattCentral(uuid = uuidCentral)
        assertEquals(
            listOf(cmCentral),
            MdocConnectionMethod.fromDeviceEngagement(cmCentral.toDeviceEngagement())
        )
        assertEquals(
            """[
  2,
  1,
  {
    0: false,
    1: true,
    11: h'00000000075bcd15000000003ade68b1'
  }
]""",
            Cbor.toDiagnostics(cmCentral.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // Single GattPeripheral
        val cmPeripheral = MdocConnectionMethod.Ble.GattPeripheral(uuid = uuidPeripheral)
        assertEquals(
            listOf(cmPeripheral),
            MdocConnectionMethod.fromDeviceEngagement(cmPeripheral.toDeviceEngagement())
        )
        assertEquals(
            """[
  2,
  1,
  {
    0: true,
    1: false,
    10: h'00000000000000000000000000000001'
  }
]""",
            Cbor.toDiagnostics(cmPeripheral.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // Coalescing GattCentral + GattPeripheral with distinct UUIDs in DeviceRetrievalMethods
        val encodedDualItem = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = listOf(cmCentral, cmPeripheral),
            engagementType = MdocEngagementType.DEVICE_QR,
        )
        assertEquals(1, encodedDualItem.size)
        assertEquals(
            """[
  2,
  1,
  {
    0: true,
    1: true,
    10: h'00000000000000000000000000000001',
    11: h'00000000075bcd15000000003ade68b1'
  }
]""",
            Cbor.toDiagnostics(Cbor.encode(encodedDualItem.single()), setOf(DiagnosticOption.PRETTY_PRINT))
        )
        assertEquals(
            listOf(cmCentral, cmPeripheral),
            MdocConnectionMethod.decodeDeviceRetrievalMethods(encodedDualItem, senderRole = MdocRole.MDOC)
        )

        // Conflicting peripheral UUIDs rejected
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeDeviceRetrievalMethods(
                methods = listOf(
                    MdocConnectionMethod.Ble.GattPeripheral(uuid = UUID(0U, 1U)),
                    MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = UUID(0U, 9U), psm = 129),
                ),
                engagementType = MdocEngagementType.DEVICE_QR,
            )
        }

        // Conflicting central PSMs in a single BleOptions rejected
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeDeviceRetrievalMethods(
                methods = listOf(
                    MdocConnectionMethod.Ble.DirectL2capCentral(uuid = UUID(0U, 2U), psm = 128),
                    MdocConnectionMethod.Ble.DirectL2capCentral(uuid = UUID(0U, 2U), psm = 129),
                ),
                engagementType = MdocEngagementType.CCE_REQUEST,
            )
        }

        // Legacy ISO 18013-5 v1 NFC data transfer ([1, 1, NfcOptions]) rejected in CCE_REQUEST
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeDeviceRetrievalMethods(
                methods = listOf(MdocConnectionMethod.Nfc(4096, 4096)),
                engagementType = MdocEngagementType.CCE_REQUEST,
            )
        }
    }

    @Test
    fun testDecodeDeviceRetrievalMethodsSenderRoleAttribution() {
        val peripheralAddr = BlePeripheralAddress(
            address = ByteString(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
            addressType = BleAddressType.RANDOM,
        )
        val uuidP = UUID(0U, 1U)
        val uuidC = UUID(0U, 2U)

        // Reader offer containing all 4 BLE modes in CCE_REQUEST
        val readerEncoded = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = listOf(
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = uuidC, psm = 192, peripheralAddress = peripheralAddr),
                MdocConnectionMethod.Ble.DirectL2capPeripheral(),
                MdocConnectionMethod.Ble.GattCentral(uuid = uuidC, peripheralAddress = peripheralAddr),
                MdocConnectionMethod.Ble.GattPeripheral(),
            ),
            engagementType = MdocEngagementType.CCE_REQUEST,
        )
        val decodedFromReader = MdocConnectionMethod.decodeDeviceRetrievalMethods(
            items = readerEncoded,
            senderRole = MdocRole.MDOC_READER,
        )
        assertEquals(
            listOf(
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = uuidC, psm = 192, peripheralAddress = peripheralAddr),
                MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = null, psm = null, peripheralAddress = null),
                MdocConnectionMethod.Ble.GattCentral(uuid = uuidC, peripheralAddress = peripheralAddr),
                MdocConnectionMethod.Ble.GattPeripheral(uuid = null, peripheralAddress = null),
            ),
            decodedFromReader
        )

        // Mdoc offer in DEVICE_QR containing DirectL2capPeripheral + GattPeripheral + GattCentral + NFC
        val mdocEncoded = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = listOf(
                MdocConnectionMethod.Nfc(4096, 4096),
                MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = uuidP, psm = 129, peripheralAddress = peripheralAddr),
                MdocConnectionMethod.Ble.GattCentral(uuid = uuidC),
                MdocConnectionMethod.Ble.GattPeripheral(uuid = uuidP, peripheralAddress = peripheralAddr),
            ),
            engagementType = MdocEngagementType.DEVICE_QR,
        )
        val decodedFromMdoc = MdocConnectionMethod.decodeDeviceRetrievalMethods(
            items = mdocEncoded,
            senderRole = MdocRole.MDOC,
        )
        assertEquals(
            listOf(
                MdocConnectionMethod.Nfc(4096, 4096),
                MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = uuidP, psm = 129, peripheralAddress = peripheralAddr),
                MdocConnectionMethod.Ble.GattCentral(uuid = uuidC, peripheralAddress = null),
                MdocConnectionMethod.Ble.GattPeripheral(uuid = uuidP, peripheralAddress = peripheralAddr),
            ),
            decodedFromMdoc
        )
    }

    @Test
    fun testBleEngagementTypeContextRulesAndValidation() {
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val readerAddr = BlePeripheralAddress.fromEncoded("28078080bf2801".fromHex())
        val mdocAddr = BlePeripheralAddress.fromEncoded("28128b37282801".fromHex())

        // 1. DEVICE_QR: rejects DirectL2capCentral; allows DirectL2capPeripheral with PSM
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeDeviceRetrievalMethods(
                methods = listOf(
                    MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128)
                ),
                engagementType = MdocEngagementType.DEVICE_QR,
            )
        }
        val dqrItem = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = listOf(
                MdocConnectionMethod.Ble.GattPeripheral(uuid = peripheralUuid, peripheralAddress = mdocAddr),
                MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid),
                MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = peripheralUuid, psm = 129, peripheralAddress = mdocAddr),
            ),
            engagementType = MdocEngagementType.DEVICE_QR,
        )
        assertEquals(
            """[
  2,
  1,
  {
    0: true,
    1: true,
    2: true,
    3: false,
    10: h'00000002a12348ce896b4c76973373e6',
    11: h'00000001a12348ce896b4c76973373e6',
    20: h'28128b37282801',
    21: 129
  }
]""",
            Cbor.toDiagnostics(Cbor.encode(dqrItem.single()), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // 2. READER_QR: rejects DirectL2capPeripheral; requires uuid when GattPeripheral is offered
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeDeviceRetrievalMethods(
                methods = listOf(
                    MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = peripheralUuid, psm = 129)
                ),
                engagementType = MdocEngagementType.READER_QR,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeDeviceRetrievalMethods(
                methods = listOf(
                    MdocConnectionMethod.Ble.GattPeripheral(uuid = null)
                ),
                engagementType = MdocEngagementType.READER_QR,
            )
        }
        val rqrItem = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = listOf(
                MdocConnectionMethod.Ble.GattPeripheral(uuid = peripheralUuid),
                MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid, peripheralAddress = readerAddr),
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128, peripheralAddress = readerAddr),
            ),
            engagementType = MdocEngagementType.READER_QR,
        )
        assertEquals(
            """[
  2,
  1,
  {
    0: true,
    1: true,
    2: false,
    3: true,
    10: h'00000002a12348ce896b4c76973373e6',
    11: h'00000001a12348ce896b4c76973373e6',
    20: h'28078080bf2801',
    21: 128
  }
]""",
            Cbor.toDiagnostics(Cbor.encode(rqrItem.single()), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // 3. CCE_REQUEST: omits peripheralModeUuid (key 10) even if set; omits PSM unless DirectL2capCentral is offered
        val cceReqPeripheralL2cap = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = listOf(
                MdocConnectionMethod.Ble.GattPeripheral(uuid = peripheralUuid),
                MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = peripheralUuid, psm = 129),
            ),
            engagementType = MdocEngagementType.CCE_REQUEST,
        )
        assertEquals(
            """[
  2,
  1,
  {
    0: true,
    1: false,
    2: true,
    3: false
  }
]""",
            Cbor.toDiagnostics(Cbor.encode(cceReqPeripheralL2cap.single()), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // 4. CCE_SELECT: requires at most 1 connection method; omits centralModeUuid (key 11) and reader's PSM/addr
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeDeviceRetrievalMethods(
                methods = listOf(
                    MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid),
                    MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128),
                ),
                engagementType = MdocEngagementType.CCE_SELECT,
            )
        }
        val cceSelCentralL2cap = MdocConnectionMethod.Ble.DirectL2capCentral(
            uuid = centralUuid, // omitted on wire in CCE_SELECT!
            psm = 128, // omitted on wire in CCE_SELECT!
            peripheralAddress = readerAddr, // omitted on wire in CCE_SELECT!
        ).toDeviceEngagement(MdocEngagementType.CCE_SELECT)
        assertEquals(
            """[
  2,
  1,
  {
    0: false,
    1: false,
    2: false,
    3: true
  }
]""",
            Cbor.toDiagnostics(cceSelCentralL2cap, setOf(DiagnosticOption.PRETTY_PRINT))
        )
    }

    @Test
    fun testNdefChBleOptionsAndLeRoleAndPeripheralAddressRoundTrips() {
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val readerAddr = BlePeripheralAddress.fromEncoded("28078080bf2801".fromHex())
        val mdocAddr = BlePeripheralAddress.fromEncoded("28128b37282801".fromHex())

        // 1. Reader Negotiated Handover Hr with all 4 modes + ChBleOptions + peripheralAddress -> LE Role 0x02
        val readerAllModes = listOf(
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128, peripheralAddress = readerAddr),
            MdocConnectionMethod.Ble.DirectL2capPeripheral(),
            MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid, peripheralAddress = readerAddr),
            MdocConnectionMethod.Ble.GattPeripheral(),
        )
        val hrRecords = MdocConnectionMethod.encodeNdefHandoverCarriers(
            methods = readerAllModes,
            handoverType = MdocNfcHandoverType.NEGOTIATED_HR,
            sharedAuxiliaryReferences = listOf("mdocreader"),
        ).single()
        assertEquals(1, hrRecords.auxiliaryRecords.size)
        val hrChBle = hrRecords.auxiliaryRecords.single()
        assertEquals(Nfc.EXTERNAL_TYPE_ISO_18013_5_CH_BLE_OPTIONS, hrChBle.type.decodeToString())
        assertEquals("chble", hrChBle.id.decodeToString())
        assertEquals(
            """{
  0: 128,
  1: true,
  2: true
}""",
            Cbor.toDiagnostics(hrChBle.payload.toByteArray(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
        val parsedAc = AlternativeCarrierRecord.fromNdefRecord(
            hrRecords.alternativeCarrierRecord.generateNdefRecord()
        )
        assertNotNull(parsedAc)
        assertEquals(hrRecords.alternativeCarrierRecord, parsedAc)
        assertEquals(AlternativeCarrierRecord.CPS_ACTIVE, parsedAc.cps)
        assertEquals("0", parsedAc.carrierDataReference)
        assertEquals(listOf("mdocreader", "chble"), parsedAc.auxiliaryDataReferences)
        val decodedReaderAll = MdocConnectionMethod.decodeNdefHandoverCarrier(
            record = hrRecords.carrierRecord,
            senderRole = MdocRole.MDOC_READER,
            fallbackUuid = null,
            auxiliaryRecords = hrRecords.auxiliaryRecords,
        )
        assertEquals(readerAllModes, decodedReaderAll)

        // 2. Reader Hr offering ONLY mdoc peripheral modes -> LE Role 0x01 and NO 0x07 UUID in BLE OOB!
        val readerPeripheralModesOnly = listOf(
            MdocConnectionMethod.Ble.DirectL2capPeripheral(),
            MdocConnectionMethod.Ble.GattPeripheral(),
        )
        val hrPeriphOnlyRecords = MdocConnectionMethod.encodeNdefHandoverCarriers(
            methods = readerPeripheralModesOnly,
            handoverType = MdocNfcHandoverType.NEGOTIATED_HR,
            sharedAuxiliaryReferences = emptyList(),
        ).single()
        assertEquals("021c01", hrPeriphOnlyRecords.carrierRecord.payload.toByteArray().toHex())
        assertEquals(
            """{
  1: true,
  2: true
}""",
            Cbor.toDiagnostics(
                hrPeriphOnlyRecords.auxiliaryRecords.single().payload.toByteArray(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(
            readerPeripheralModesOnly,
            MdocConnectionMethod.decodeNdefHandoverCarrier(
                record = hrPeriphOnlyRecords.carrierRecord,
                senderRole = MdocRole.MDOC_READER,
                fallbackUuid = null,
                auxiliaryRecords = hrPeriphOnlyRecords.auxiliaryRecords,
            )
        )

        // 3. Mdoc Static Hs offering DirectL2capPeripheral + GattPeripheral + mdocAddr -> LE Role 0x00 + 0x07 UUID + 0x1B addr + ChBleOptions
        val mdocStaticL2cap = listOf(
            MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = peripheralUuid, psm = 129, peripheralAddress = mdocAddr),
            MdocConnectionMethod.Ble.GattPeripheral(uuid = peripheralUuid, peripheralAddress = mdocAddr),
        )
        val hsStaticRecords = MdocConnectionMethod.encodeNdefHandoverCarriers(
            methods = mdocStaticL2cap,
            handoverType = MdocNfcHandoverType.STATIC_HS,
            sharedAuxiliaryReferences = listOf("mdoc"),
        ).single()
        assertEquals(
            "021c001107e6733397764c6b89ce4823a102000000081b28128b37282801",
            hsStaticRecords.carrierRecord.payload.toByteArray().toHex()
        )
        assertEquals(
            mdocStaticL2cap,
            MdocConnectionMethod.decodeNdefHandoverCarrier(
                record = hsStaticRecords.carrierRecord,
                senderRole = MdocRole.MDOC,
                fallbackUuid = null,
                auxiliaryRecords = hsStaticRecords.auxiliaryRecords,
            )
        )

        // 4. Mdoc Negotiated Hs selecting DirectL2capCentral -> LE Role 0x01, NO UUID, ChBleOptions {1: false, 2: true}
        val mdocSelectDirectL2capCentral = listOf(
            MdocConnectionMethod.Ble.DirectL2capCentral(uuid = centralUuid, psm = 128)
        )
        val hsSelectL2capCentral = MdocConnectionMethod.encodeNdefHandoverCarriers(
            methods = mdocSelectDirectL2capCentral,
            handoverType = MdocNfcHandoverType.NEGOTIATED_HS,
            sharedAuxiliaryReferences = listOf("mdoc"),
            hrHadChBleOptions = true,
        ).single()
        assertEquals("021c01", hsSelectL2capCentral.carrierRecord.payload.toByteArray().toHex())
        assertEquals(
            """{
  1: false,
  2: true
}""",
            Cbor.toDiagnostics(
                hsSelectL2capCentral.auxiliaryRecords.single().payload.toByteArray(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )

        // 5. Distinct peripheral & central UUIDs rejected in Static Hs NDEF
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.encodeNdefHandoverCarriers(
                methods = listOf(
                    MdocConnectionMethod.Ble.GattPeripheral(uuid = peripheralUuid),
                    MdocConnectionMethod.Ble.GattCentral(uuid = centralUuid),
                ),
                handoverType = MdocNfcHandoverType.STATIC_HS,
            )
        }
    }
}
