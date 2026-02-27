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
import kotlinx.io.bytestring.toHexString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID
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
        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false)!!.first
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
    fun testConnectionMethodBle() {
        val uuidPeripheral = UUID(0U, 1U)
        val uuidCentral = UUID(123456789U, 987654321U)
        var cm = MdocConnectionMethodBle(
            true,
            true,
            uuidPeripheral,
            uuidCentral
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodBle?
        assertNotNull(decoded)
        assertTrue(decoded.supportsPeripheralServerMode)
        assertTrue(decoded.supportsCentralClientMode)
        assertEquals(uuidPeripheral, decoded.peripheralServerModeUuid)
        assertEquals(uuidCentral, decoded.centralClientModeUuid)
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

        // For use in NFC, the UUIDs have to be the same
        val uuidBoth = UUID(0U, 2U)
        cm = MdocConnectionMethodBle(
            true,
            true,
            uuidBoth,
            uuidBoth
        )
        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false).first
        val decodedNfc = MdocConnectionMethodBle.fromNdefRecord(ndefRecord, MdocRole.MDOC, null)!!
        assertEquals(cm, decodedNfc)
    }

    @Test
    fun testConnectionMethodBleOnlyCentralClient() {
        val uuid = UUID(123456789U, 987654321U)
        val cm = MdocConnectionMethodBle(
            false,
            true,
            null,
            uuid
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodBle?
        assertNotNull(decoded)
        assertFalse(decoded.supportsPeripheralServerMode)
        assertTrue(decoded.supportsCentralClientMode)
        assertNull(decoded.peripheralServerModeUuid)
        assertEquals(uuid, decoded.centralClientModeUuid)
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

        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false).first
        val decodedNfc = MdocConnectionMethodBle.fromNdefRecord(ndefRecord, MdocRole.MDOC, null)!!
        assertEquals(cm, decodedNfc)
    }

    @Test
    fun testConnectionMethodBleOnlyPeripheralServer() {
        val uuid = UUID(0U, 1U)
        val cm = MdocConnectionMethodBle(
            true,
            false,
            uuid,
            null
        )
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodBle?
        assertNotNull(decoded)
        assertTrue(decoded.supportsPeripheralServerMode)
        assertFalse(decoded.supportsCentralClientMode)
        assertEquals(uuid, decoded.peripheralServerModeUuid)
        assertNull(decoded.centralClientModeUuid)
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

        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false).first
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
        assertEquals("foobar", decoded!!.passphraseInfoPassphrase)
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

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun testConnectionMethodDisambiguateMdoc() {
        val disambiguated = MdocConnectionMethod.disambiguate(
            listOf(
                MdocConnectionMethodNfc(4096, 4096),
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = true,
                    supportsCentralClientMode = true,
                    peripheralServerModeUuid = UUID(0U, 1U),
                    centralClientModeUuid = UUID(0U, 2U),
                    peripheralServerModePsm = 192,
                    peripheralServerModeMacAddress = ByteString(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
                )
            ),
            MdocRole.MDOC
        )
        assertEquals(3, disambiguated.size.toLong())
        assertTrue(disambiguated[0] is MdocConnectionMethodNfc)
        assertTrue(disambiguated[1] is MdocConnectionMethodBle)
        assertTrue(disambiguated[2] is MdocConnectionMethodBle)

        val bleCc = disambiguated[1] as MdocConnectionMethodBle
        assertFalse(bleCc.supportsPeripheralServerMode)
        assertTrue(bleCc.supportsCentralClientMode)
        assertNull(bleCc.peripheralServerModeUuid)
        assertEquals(UUID(0U, 2U), bleCc.centralClientModeUuid)
        assertEquals(192, bleCc.peripheralServerModePsm)
        assertEquals("112233445566", bleCc.peripheralServerModeMacAddress!!.toHexString())

        val blePs = disambiguated[2] as MdocConnectionMethodBle
        assertTrue(blePs.supportsPeripheralServerMode)
        assertFalse(blePs.supportsCentralClientMode)
        assertEquals(UUID(0U, 1U), blePs.peripheralServerModeUuid)
        assertNull(blePs.centralClientModeUuid)
        assertNull(blePs.peripheralServerModePsm)
        assertNull(blePs.peripheralServerModeMacAddress)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun testConnectionMethodDisambiguateMdocReader() {
        val disambiguated = MdocConnectionMethod.disambiguate(
            listOf(
                MdocConnectionMethodNfc(4096, 4096),
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = true,
                    supportsCentralClientMode = true,
                    peripheralServerModeUuid = UUID(0U, 1U),
                    centralClientModeUuid = UUID(0U, 2U),
                    peripheralServerModePsm = 192,
                    peripheralServerModeMacAddress = ByteString(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
                )
            ),
            MdocRole.MDOC_READER
        )
        assertEquals(3, disambiguated.size.toLong())
        assertTrue(disambiguated[0] is MdocConnectionMethodNfc)
        assertTrue(disambiguated[1] is MdocConnectionMethodBle)
        assertTrue(disambiguated[2] is MdocConnectionMethodBle)

        val bleCc = disambiguated[1] as MdocConnectionMethodBle
        assertFalse(bleCc.supportsPeripheralServerMode)
        assertTrue(bleCc.supportsCentralClientMode)
        assertNull(bleCc.peripheralServerModeUuid)
        assertEquals(UUID(0U, 2U), bleCc.centralClientModeUuid)
        assertNull(bleCc.peripheralServerModePsm)
        assertNull(bleCc.peripheralServerModeMacAddress)

        val blePs = disambiguated[2] as MdocConnectionMethodBle
        assertTrue(blePs.supportsPeripheralServerMode)
        assertFalse(blePs.supportsCentralClientMode)
        assertEquals(UUID(0U, 1U), blePs.peripheralServerModeUuid)
        assertNull(blePs.centralClientModeUuid)
        assertEquals(192, blePs.peripheralServerModePsm)
        assertEquals("112233445566", blePs.peripheralServerModeMacAddress!!.toHexString())
    }

    @Test
    fun testConnectionMethodCombineUuidNotSame() {
        val disambiguated = listOf(
            MdocConnectionMethodNfc(4096, 4096),
            MdocConnectionMethodBle(
                true,
                false,
                UUID(0U, 1U),
                null
            ),
            MdocConnectionMethodBle(
                false,
                true,
                null,
                UUID(0U, 2U)
            )
        )
        assertFailsWith<IllegalArgumentException> {
            val combined = MdocConnectionMethod.combine(disambiguated)
        }
    }

    @Test
    fun testConnectionMethodCombineUuid() {
        val uuid = UUID(0U, 3U)
        val disambiguated = listOf(
            MdocConnectionMethodNfc(4096, 4096),
            MdocConnectionMethodBle(
                true,
                false,
                uuid,
                null
            ),
            MdocConnectionMethodBle(
                false,
                true,
                null,
                uuid
            )
        )
        val combined = MdocConnectionMethod.combine(disambiguated)
        assertEquals(2, combined.size.toLong())
        val ble = combined[0] as MdocConnectionMethodBle
        assertTrue(ble.supportsPeripheralServerMode)
        assertTrue(ble.supportsCentralClientMode)
        assertEquals(uuid, ble.peripheralServerModeUuid)
        assertEquals(uuid, ble.centralClientModeUuid)
    }
}