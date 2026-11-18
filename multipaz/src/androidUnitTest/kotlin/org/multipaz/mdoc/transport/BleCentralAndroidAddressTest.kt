package org.multipaz.mdoc.transport

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BleCentralAndroidAddressTest {

    @Test
    fun formatAndroidBluetoothAddressProducesUppercaseColonHex() {
        assertEquals(
            "C6:50:40:30:20:0A",
            formatAndroidBluetoothAddress(
                ByteString(0xC6.toByte(), 0x50, 0x40, 0x30, 0x20, 0x0A)
            )
        )
        assertEquals(
            "00:AB:CD:EF:01:99",
            formatAndroidBluetoothAddress(
                ByteString(0x00, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01, 0x99.toByte())
            )
        )
        assertFailsWith<IllegalArgumentException> {
            formatAndroidBluetoothAddress(ByteString(0x01, 0x02, 0x03, 0x04, 0x05))
        }
    }
}
