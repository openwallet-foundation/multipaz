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
        val cm = MdocConnectionMethodNfc(4096, 32768)
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodNfc?
        assertNotNull(decoded)
        assertEquals(cm.commandDataFieldMaxLength, decoded.commandDataFieldMaxLength)
        assertEquals(cm.responseDataFieldMaxLength, decoded.responseDataFieldMaxLength)
        assertEquals(
            """[
  1,
  1,
  {
    0: 4096,
    1: 32768
  }
]""", Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false)!!.carrierRecord
        val decodedNfc = MdocConnectionMethodNfc.fromNdefRecord(ndefRecord, MdocRole.MDOC)!!
        assertEquals(cm, decodedNfc)
    }

    @Test
    fun testConnectionMethodHttp() {
        val cm = MdocConnectionMethodHttp(uri = "https://www.example.com/foobar")
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodHttp?
        assertNotNull(decoded)
        assertEquals(cm.uri, decoded.uri)
        assertEquals(
            """
                [
                  4,
                  1,
                  {
                    0: "https://www.example.com/foobar"
                  }
                ]
            """.trimIndent(), Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
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
    fun testConnectionMethodBle() {
        val uuidPeripheral = UUID(0U, 1U)
        val uuidCentral = UUID(123456789U, 987654321U)
        var cm = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            peripheralModeUuid = uuidPeripheral,
            centralModeUuid = uuidCentral
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodBle?
        assertNotNull(decoded)
        assertTrue(decoded.gattPeripheral)
        assertTrue(decoded.gattCentral)
        assertFalse(decoded.directL2capPeripheral)
        assertFalse(decoded.directL2capCentral)
        assertEquals(uuidPeripheral, decoded.peripheralModeUuid)
        assertEquals(uuidCentral, decoded.centralModeUuid)
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
]""", Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // For use in NFC BLE OOB, the UUIDs have to be the same
        val uuidBoth = UUID(0U, 2U)
        cm = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            peripheralModeUuid = uuidBoth,
            centralModeUuid = uuidBoth
        )
        val ndefRecords = cm.toNdefRecord(listOf(), MdocRole.MDOC, false)
        assertTrue(ndefRecords.auxiliaryRecords.isEmpty())
        val decodedNfc = MdocConnectionMethodBle.fromNdefRecord(ndefRecords.carrierRecord, MdocRole.MDOC, null)!!
        assertEquals(cm, decodedNfc)
    }

    @Test
    fun testConnectionMethodBleOnlyCentralClient() {
        val uuid = UUID(123456789U, 987654321U)
        val cm = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = uuid
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodBle?
        assertNotNull(decoded)
        assertFalse(decoded.gattPeripheral)
        assertTrue(decoded.gattCentral)
        assertNull(decoded.peripheralModeUuid)
        assertEquals(uuid, decoded.centralModeUuid)
        assertEquals(
            """[
  2,
  1,
  {
    0: false,
    1: true,
    11: h'00000000075bcd15000000003ade68b1'
  }
]""", Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false).carrierRecord
        val decodedNfc = MdocConnectionMethodBle.fromNdefRecord(ndefRecord, MdocRole.MDOC, null)!!
        assertEquals(cm, decodedNfc)
    }

    @Test
    fun testConnectionMethodBleOnlyPeripheralServer() {
        val uuid = UUID(0U, 1U)
        val cm = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            peripheralModeUuid = uuid,
            centralModeUuid = null
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodBle?
        assertNotNull(decoded)
        assertTrue(decoded.gattPeripheral)
        assertFalse(decoded.gattCentral)
        assertEquals(uuid, decoded.peripheralModeUuid)
        assertNull(decoded.centralModeUuid)
        assertEquals(
            """[
  2,
  1,
  {
    0: true,
    1: false,
    10: h'00000000000000000000000000000001'
  }
]""", Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )

        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false).carrierRecord
        val decodedNfc = MdocConnectionMethodBle.fromNdefRecord(ndefRecord, MdocRole.MDOC, null)!!
        assertEquals(cm, decodedNfc)
    }

    @Test
    fun testConnectionMethodWifiAware() {
        val cm = MdocConnectionMethodWifiAware(
            "foobar",
            42,
            43,
            ByteString(1, 2)
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodWifiAware?
        assertNotNull(decoded)
        assertEquals("foobar", decoded.passphraseInfoPassphrase)
        assertEquals(42, decoded.channelInfoChannelNumber)
        assertEquals(43, decoded.channelInfoOperatingClass)
        assertEquals(ByteString(1, 2), decoded.bandInfoSupportedBands)
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
]""", Cbor.toDiagnostics(cm.toDeviceEngagement(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
    }

    @Test
    fun testConnectionMethodDisambiguateMdocFourModes() {
        val peripheralAddr = BlePeripheralAddress(
            address = ByteString(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
            addressType = BleAddressType.RANDOM,
        )
        val disambiguated = MdocConnectionMethod.disambiguate(
            listOf(
                MdocConnectionMethodNfc(4096, 4096),
                MdocConnectionMethodBle(
                    gattPeripheral = true,
                    gattCentral = true,
                    directL2capPeripheral = true,
                    directL2capCentral = true,
                    peripheralModeUuid = UUID(0U, 1U),
                    centralModeUuid = UUID(0U, 2U),
                    directL2capPsm = 192,
                    peripheralAddress = peripheralAddr,
                )
            ),
            MdocRole.MDOC
        )
        // Order: NFC, directL2capCentral, directL2capPeripheral, gattCentral, gattPeripheral
        assertEquals(5, disambiguated.size)
        assertTrue(disambiguated[0] is MdocConnectionMethodNfc)

        // For MDOC disambiguating a peer (reader) offer: PSM & peripheralAddress belong to reader's peripheral modes (central modes!)
        val l2capCentral = disambiguated[1] as MdocConnectionMethodBle
        assertTrue(l2capCentral.directL2capCentral)
        assertEquals(1, l2capCentral.enabledModeCount)
        assertEquals(UUID(0U, 2U), l2capCentral.centralModeUuid)
        assertNull(l2capCentral.peripheralModeUuid)
        assertEquals(192, l2capCentral.directL2capPsm)
        assertEquals(peripheralAddr, l2capCentral.peripheralAddress)

        val l2capPeripheral = disambiguated[2] as MdocConnectionMethodBle
        assertTrue(l2capPeripheral.directL2capPeripheral)
        assertEquals(1, l2capPeripheral.enabledModeCount)
        assertEquals(UUID(0U, 1U), l2capPeripheral.peripheralModeUuid)
        assertNull(l2capPeripheral.centralModeUuid)
        assertNull(l2capPeripheral.directL2capPsm)
        assertNull(l2capPeripheral.peripheralAddress)

        val gattCentral = disambiguated[3] as MdocConnectionMethodBle
        assertTrue(gattCentral.gattCentral)
        assertEquals(1, gattCentral.enabledModeCount)
        assertEquals(UUID(0U, 2U), gattCentral.centralModeUuid)
        assertNull(gattCentral.peripheralModeUuid)
        assertNull(gattCentral.directL2capPsm)
        assertEquals(peripheralAddr, gattCentral.peripheralAddress)

        val gattPeripheral = disambiguated[4] as MdocConnectionMethodBle
        assertTrue(gattPeripheral.gattPeripheral)
        assertEquals(1, gattPeripheral.enabledModeCount)
        assertEquals(UUID(0U, 1U), gattPeripheral.peripheralModeUuid)
        assertNull(gattPeripheral.centralModeUuid)
        assertNull(gattPeripheral.directL2capPsm)
        assertNull(gattPeripheral.peripheralAddress)
    }

    @Test
    fun testConnectionMethodDisambiguateMdocReaderFourModes() {
        val peripheralAddr = BlePeripheralAddress(
            address = ByteString(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
            addressType = BleAddressType.RANDOM,
        )
        val disambiguated = MdocConnectionMethod.disambiguate(
            listOf(
                MdocConnectionMethodNfc(4096, 4096),
                MdocConnectionMethodBle(
                    gattPeripheral = true,
                    gattCentral = true,
                    directL2capPeripheral = true,
                    directL2capCentral = true,
                    peripheralModeUuid = UUID(0U, 1U),
                    centralModeUuid = UUID(0U, 2U),
                    directL2capPsm = 192,
                    peripheralAddress = peripheralAddr,
                )
            ),
            MdocRole.MDOC_READER
        )
        assertEquals(5, disambiguated.size)
        assertTrue(disambiguated[0] is MdocConnectionMethodNfc)

        // For MDOC_READER disambiguating a peer (mdoc) offer: PSM & peripheralAddress belong to mdoc's peripheral modes!
        val l2capCentral = disambiguated[1] as MdocConnectionMethodBle
        assertTrue(l2capCentral.directL2capCentral)
        assertEquals(1, l2capCentral.enabledModeCount)
        assertEquals(UUID(0U, 2U), l2capCentral.centralModeUuid)
        assertNull(l2capCentral.directL2capPsm)
        assertNull(l2capCentral.peripheralAddress)

        val l2capPeripheral = disambiguated[2] as MdocConnectionMethodBle
        assertTrue(l2capPeripheral.directL2capPeripheral)
        assertEquals(1, l2capPeripheral.enabledModeCount)
        assertEquals(UUID(0U, 1U), l2capPeripheral.peripheralModeUuid)
        assertEquals(192, l2capPeripheral.directL2capPsm)
        assertEquals(peripheralAddr, l2capPeripheral.peripheralAddress)

        val gattCentral = disambiguated[3] as MdocConnectionMethodBle
        assertTrue(gattCentral.gattCentral)
        assertEquals(1, gattCentral.enabledModeCount)
        assertEquals(UUID(0U, 2U), gattCentral.centralModeUuid)
        assertNull(gattCentral.directL2capPsm)
        assertNull(gattCentral.peripheralAddress)

        val gattPeripheral = disambiguated[4] as MdocConnectionMethodBle
        assertTrue(gattPeripheral.gattPeripheral)
        assertEquals(1, gattPeripheral.enabledModeCount)
        assertEquals(UUID(0U, 1U), gattPeripheral.peripheralModeUuid)
        assertNull(gattPeripheral.directL2capPsm)
        assertEquals(peripheralAddr, gattPeripheral.peripheralAddress)
    }

    @Test
    fun testConnectionMethodCombineAllowsDistinctModeUuidsAndRejectsConflicts() {
        val peripheralUuid = UUID(0U, 1U)
        val centralUuid = UUID(0U, 2U)
        val disambiguated = listOf(
            MdocConnectionMethodNfc(4096, 4096),
            MdocConnectionMethodBle(
                gattPeripheral = true,
                gattCentral = false,
                directL2capPeripheral = true,
                peripheralModeUuid = peripheralUuid,
                directL2capPsm = 129,
            ),
            MdocConnectionMethodBle(
                gattPeripheral = false,
                gattCentral = true,
                centralModeUuid = centralUuid,
            )
        )
        // Distinct peripheralModeUuid and centralModeUuid are valid in QR and CCE engagements
        val combined = MdocConnectionMethod.combine(disambiguated)
        assertEquals(2, combined.size)
        val ble = combined[0] as MdocConnectionMethodBle
        assertTrue(ble.gattPeripheral)
        assertTrue(ble.gattCentral)
        assertTrue(ble.directL2capPeripheral)
        assertFalse(ble.directL2capCentral)
        assertEquals(peripheralUuid, ble.peripheralModeUuid)
        assertEquals(centralUuid, ble.centralModeUuid)
        assertEquals(129, ble.directL2capPsm)

        // Encoding to BLE OOB NDEF still rejects distinct peripheralModeUuid and centralModeUuid
        assertFailsWith<IllegalArgumentException> {
            ble.toNdefRecord(emptyList(), MdocRole.MDOC, skipUuids = false)
        }

        // Conflicting non-null peripheralModeUuid values are rejected by combine()
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.combine(
                listOf(
                    MdocConnectionMethodBle(gattPeripheral = true, gattCentral = false, peripheralModeUuid = UUID(0U, 1U)),
                    MdocConnectionMethodBle(gattPeripheral = false, gattCentral = false, directL2capPeripheral = true, peripheralModeUuid = UUID(0U, 9U), directL2capPsm = 129)
                )
            )
        }

        // Conflicting non-null directL2capPsm values are rejected by combine()
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethod.combine(
                listOf(
                    MdocConnectionMethodBle(gattPeripheral = false, gattCentral = false, directL2capPeripheral = true, directL2capPsm = 128),
                    MdocConnectionMethodBle(gattPeripheral = false, gattCentral = false, directL2capCentral = true, directL2capPsm = 129)
                )
            )
        }
    }

    @Test
    fun testBleEngagementTypeContextRulesAndValidation() {
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val readerAddr = BlePeripheralAddress.fromEncoded("28078080bf2801".fromHex())
        val mdocAddr = BlePeripheralAddress.fromEncoded("28128b37282801".fromHex())

        // 1. DEVICE_QR: rejects directL2capCentral=true; allows directL2capPeripheral=true with PSM
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethodBle(
                gattPeripheral = false,
                gattCentral = true,
                directL2capCentral = true,
                centralModeUuid = centralUuid,
                directL2capPsm = 128,
            ).toDeviceEngagement(MdocEngagementType.DEVICE_QR)
        }
        val dqrBytes = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = centralUuid,
            directL2capPsm = 129,
            peripheralAddress = mdocAddr,
        ).toDeviceEngagement(MdocEngagementType.DEVICE_QR)
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
            Cbor.toDiagnostics(dqrBytes, setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // 2. READER_QR: rejects directL2capPeripheral=true; requires peripheralModeUuid when gattPeripheral=true
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethodBle(
                gattPeripheral = true,
                gattCentral = false,
                directL2capPeripheral = true,
                peripheralModeUuid = peripheralUuid,
                directL2capPsm = 129,
            ).toDeviceEngagement(MdocEngagementType.READER_QR)
        }
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethodBle(
                gattPeripheral = true,
                gattCentral = false,
                peripheralModeUuid = null,
            ).toDeviceEngagement(MdocEngagementType.READER_QR)
        }
        val rqrBytes = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            directL2capPeripheral = false,
            directL2capCentral = true,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
            peripheralAddress = readerAddr,
        ).toDeviceEngagement(MdocEngagementType.READER_QR)
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
            Cbor.toDiagnostics(rqrBytes, setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // 3. CCE_REQUEST: omits peripheralModeUuid (key 10) even if set; omits PSM unless directL2capCentral=true
        val cceReqPeripheralL2cap = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = peripheralUuid, // must NOT be emitted in CCE_REQUEST!
            directL2capPsm = 129, // must NOT be emitted in CCE_REQUEST when directL2capCentral=false!
        ).toDeviceEngagement(MdocEngagementType.CCE_REQUEST)
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
            Cbor.toDiagnostics(cceReqPeripheralL2cap, setOf(DiagnosticOption.PRETTY_PRINT))
        )

        // 4. CCE_SELECT: requires exactly 1 enabled mode; omits centralModeUuid (key 11) and reader's PSM/addr
        assertFailsWith<IllegalArgumentException> {
            MdocConnectionMethodBle(
                gattPeripheral = false,
                gattCentral = true,
                directL2capCentral = true,
                centralModeUuid = centralUuid,
                directL2capPsm = 128,
            ).toDeviceEngagement(MdocEngagementType.CCE_SELECT)
        }
        val cceSelCentralL2cap = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = false,
            directL2capPeripheral = false,
            directL2capCentral = true,
            centralModeUuid = centralUuid, // omitted on wire in CCE_SELECT!
            directL2capPsm = 128, // omitted on wire in CCE_SELECT!
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
        val readerAllModes = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = true,
            directL2capPeripheral = true,
            directL2capCentral = true,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
            peripheralAddress = readerAddr,
        )
        val hrRecords = readerAllModes.toNdefRecord(listOf("mdocreader"), MdocRole.MDOC_READER, skipUuids = false)
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
        // Verify ac record references ["mdocreader", "chble"]
        val parsedAc = MdocConnectionMethod.parseAlternativeCarrierRecord(hrRecords.acRecord)
        assertNotNull(parsedAc)
        assertEquals(1, parsedAc.cps)
        assertEquals("0", parsedAc.carrierDataReference)
        assertEquals(listOf("mdocreader", "chble"), parsedAc.auxiliaryDataReferences)
        val decodedReaderAll = MdocConnectionMethodBle.fromNdefRecord(
            record = hrRecords.carrierRecord,
            role = MdocRole.MDOC_READER,
            uuidToReplace = null,
            auxiliaryRecords = hrRecords.auxiliaryRecords,
        )
        assertEquals(readerAllModes, decodedReaderAll)

        // 2. Reader Hr offering ONLY mdoc peripheral modes -> LE Role 0x01 and NO 0x07 UUID in BLE OOB!
        val readerPeripheralModesOnly = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
        )
        val hrPeriphOnlyRecords = readerPeripheralModesOnly.toNdefRecord(emptyList(), MdocRole.MDOC_READER, skipUuids = false)
        assertEquals("021c01", hrPeriphOnlyRecords.carrierRecord.payload.toByteArray().toHex())
        assertEquals(
            """{
  1: true,
  2: true
}""",
            Cbor.toDiagnostics(hrPeriphOnlyRecords.auxiliaryRecords.single().payload.toByteArray(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
        assertEquals(
            readerPeripheralModesOnly,
            MdocConnectionMethodBle.fromNdefRecord(
                record = hrPeriphOnlyRecords.carrierRecord,
                role = MdocRole.MDOC_READER,
                uuidToReplace = null,
                auxiliaryRecords = hrPeriphOnlyRecords.auxiliaryRecords,
            )
        )

        // 3. Mdoc Static Hs offering gattPeripheral + directL2capPeripheral + mdocAddr -> LE Role 0x00 + 0x07 UUID + 0x1B addr + ChBleOptions
        val mdocStaticL2cap = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = peripheralUuid,
            directL2capPsm = 129,
            peripheralAddress = mdocAddr,
        )
        val hsStaticRecords = mdocStaticL2cap.toNdefRecord(listOf("mdoc"), MdocRole.MDOC, skipUuids = false)
        assertEquals(
            "021c001107e6733397764c6b89ce4823a102000000081b28128b37282801",
            hsStaticRecords.carrierRecord.payload.toByteArray().toHex()
        )
        assertEquals(
            mdocStaticL2cap,
            MdocConnectionMethodBle.fromNdefRecord(
                record = hsStaticRecords.carrierRecord,
                role = MdocRole.MDOC,
                uuidToReplace = null,
                auxiliaryRecords = hsStaticRecords.auxiliaryRecords,
            )
        )

        // 4. Mdoc Negotiated Hs selecting directL2capCentral -> LE Role 0x01, NO UUID, ChBleOptions {1: false, 2: true}
        val mdocSelectDirectL2capCentral = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = false,
            directL2capPeripheral = false,
            directL2capCentral = true,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
        )
        val hsSelectL2capCentral = mdocSelectDirectL2capCentral.toNdefRecord(
            auxiliaryReferences = listOf("mdoc"),
            role = MdocRole.MDOC,
            skipUuids = true,
        )
        assertEquals("021c01", hsSelectL2capCentral.carrierRecord.payload.toByteArray().toHex())
        assertEquals(
            """{
  1: false,
  2: true
}""",
            Cbor.toDiagnostics(hsSelectL2capCentral.auxiliaryRecords.single().payload.toByteArray(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
    }
}
