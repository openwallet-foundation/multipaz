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
package com.android.identity.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID

class MdocConnectionMethodTest {
    @Test
    fun testConnectionMethodNfc() {
        val cm = MdocConnectionMethodNfc(4096, 32768)
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodNfc?
        assertNotNull(decoded)
        assertEquals(cm.commandDataFieldMaxLength, decoded!!.commandDataFieldMaxLength)
        assertEquals(cm.responseDataFieldMaxLength, decoded.responseDataFieldMaxLength)
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
        val ndefRecord = cm.toNdefRecord(listOf(), MdocRole.MDOC, false)!!.first
        val decodedNfc = MdocConnectionMethodNfc.fromNdefRecord(ndefRecord, MdocRole.MDOC)!!
        assertEquals(cm, decodedNfc)
    }

    @Test
    fun testConnectionMethodHttp() {
        val cm = MdocConnectionMethodHttp(uri = "https://www.example.com/foobar")
        val decoded = MdocConnectionMethod.fromDeviceEngagement(cm.toDeviceEngagement()) as MdocConnectionMethodHttp?
        assertNotNull(decoded)
        assertEquals(cm.uri, decoded!!.uri)
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
        assertTrue(decoded!!.supportsPeripheralServerMode)
        assertTrue(decoded.supportsCentralClientMode)
        assertEquals(uuidPeripheral, decoded.peripheralServerModeUuid)
        assertEquals(uuidCentral, decoded.centralClientModeUuid)

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

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun testConnectionMethodDisambiguateAndCombine() {
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
        assertEquals(3, disambiguated.size)
        val bleCc = disambiguated[1] as MdocConnectionMethodBle
        assertFalse(bleCc.supportsPeripheralServerMode)
        assertTrue(bleCc.supportsCentralClientMode)
        assertNull(bleCc.peripheralServerModeUuid)
        assertEquals(UUID(0U, 2U), bleCc.centralClientModeUuid)
        assertEquals(192, bleCc.peripheralServerModePsm)
        assertEquals("112233445566", bleCc.peripheralServerModeMacAddress!!.toHexString())

        assertThrows(IllegalArgumentException::class.java) {
            MdocConnectionMethod.combine(disambiguated)
        }
    }
}
