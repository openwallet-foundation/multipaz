package org.multipaz.nfc

import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.io.bytestring.toHexString
import org.multipaz.util.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NdefMessageTest {

    private val isWasm: Boolean by lazy {
        Platform.name.startsWith("WebAssembly")
    }

    @Test
    fun testInvalidParsing() {
        // Kotlin/Wasm doesn't have the same array access semantics as other platforms. In particular,
        // out-of-bounds array access exceptions are uncatchable in Kotlin, manifested as WebAssembly
        // traps. So we ignore this test on Kotlin/Wasm...
        if (isWasm) {
            return
        }
        // From https://cs.android.com/android/platform/superproject/main/+/main:cts/tests/tests/ndef/src/android/ndef/cts/NdefTest.java;l=90?q=NdefTest.java
        val invalidMessages = listOf(
            "",                      // too short
            "D0",                    // too short
            "D000",                  // too short
            "D000000000",            // too long
            "500000",                // missing MB
            "900000",                // missing ME
            "C0000000",              // long record, too short
            "C000000000",            // long record, too short
            "C0000000000000",        // long record, too long
            "D801030100000000",      // SR w/ payload&type&id, too short
            "D8010301000000000000",  // SR w/ payload&type&id, too long
            "D800000100",            // TNF_EMPTY cannot have id field
            "900000100000",          // 2 records, missing ME
            "F50000",                // CF and ME set
            "D60000",                // TNF_UNCHANGED without chunking
            "B600010156000102",      // TNF_UNCHANGED in first chunk
            "C500FFFFFFFF",          // heap-smash check
        )
        for (encodedHex in invalidMessages) {
            assertFailsWith(
                IllegalArgumentException::class,
                "Encoded message '$encodedHex' should fail decoding"
            ) {
                val messsage = NdefMessage.fromEncoded(encodedHex.fromHex())
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun testValidParsing() {
        val blob255 = ByteString(ByteArray(255) { 65 })
        val blob255Str = blob255.toHexString()
        val blob256 = ByteString(ByteArray(256) { 66 })
        val blob256Str = blob256.toHexString()

        // from https://cs.android.com/android/platform/superproject/main/+/main:cts/tests/tests/ndef/src/android/ndef/cts/NdefTest.java;l=119;bpv=0;bpt=1
        val testData = listOf<Pair<String, NdefMessage>>(
            // short record
            Pair(
                "d0 00 00",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.EMPTY)))
            ),
            // full length record
            Pair(
                "c0 00 00 00 00 00",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.EMPTY)))
            ),
            // SR with ID flag and 0-length id
            Pair(
                "d8 00 00 00",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.EMPTY)))
            ),
            // SR with ID flag and 1-length id
            Pair(
                "d9 00 00 01 00",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.WELL_KNOWN, id = ByteString(0))))
            ),
            // ID flag and 1-length id
            Pair(
                "c9 00 00 00 00 00 01 00",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.WELL_KNOWN, id = ByteString(0))))
            ),
            // SR with payload
            Pair(
                "d1 00 03 01 02 03",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.WELL_KNOWN, payload = ByteString(1, 2, 3))))
            ),
            // SR with payload and type
            Pair(
                "d1 01 03 09 01 02 03",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.WELL_KNOWN, type = ByteString(9), payload = ByteString(1, 2, 3))))
            ),
            // SR with payload, type and id
            Pair(
                "d9 01 03 01 08 09 01 02 03",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.WELL_KNOWN, type = ByteString(8), id = ByteString(9), payload = ByteString(1, 2, 3))))
            ),
            // payload, type and id
            Pair(
                "c9 01 00 00 00 03 01 08 09 01 02 03",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.WELL_KNOWN, type = ByteString(8), id = ByteString(9),
                    payload = ByteString(1, 2, 3))))
            ),
            // 2 records
            Pair(
                "90 00 00 50 00 00",
                NdefMessage(listOf(
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                ))
            ),
            // 3 records
            Pair(
                "90 00 00 10 00 00 50 00 00",
                NdefMessage(listOf(
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                ))
            ),
            // chunked record (2 chunks)
            Pair(
                "b5 00 01 01 56 00 01 02",
                NdefMessage(listOf(
                    NdefRecord(NdefRecord.Tnf.UNKNOWN, payload = ByteString(1, 2)),
                ))
            ),
            // chunked record (3 chunks)
            Pair(
                "b5 00 00 36 00 01 01 56 00 01 02",
                NdefMessage(listOf(
                    NdefRecord(NdefRecord.Tnf.UNKNOWN, payload = ByteString(1, 2)),
                ))
            ),
            // chunked with id and type
            Pair(
                "ba 01 00 01 08 09 36 00 01 01 56 00 01 02",
                NdefMessage(listOf(
                    NdefRecord(NdefRecord.Tnf.MIME_MEDIA, type = ByteString(8), id = ByteString(9), payload = ByteString(1, 2)),
                ))
            ),
            // 3 records, 7 chunks
            Pair(
                "b4 01 01 21 01 36 00 02 02 03 16 00 01 04 10 00 00 32 01 02 21 0b 0c 36 00 01 0d 56 00 01 0e",
                NdefMessage(listOf(
                    NdefRecord(NdefRecord.Tnf.EXTERNAL_TYPE, type = ByteString(0x21), payload = ByteString(1, 2, 3, 4)),
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                    NdefRecord(NdefRecord.Tnf.MIME_MEDIA, type = ByteString(0x21), payload = ByteString(11, 12, 13, 14)),
                ))
            ),
            // 255 byte payload
            Pair(
                "c5 00 00 00 00 ff " + blob255Str,
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.UNKNOWN, payload = blob255)))
            ),
            // 256 byte payload
            Pair(
                "c5 00 00 00 01 00 " + blob256Str,
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.UNKNOWN, payload = blob256)))
            ),
            // 255 byte type
            Pair(
                "d2 ff 00" + blob255Str,
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.MIME_MEDIA, type = blob255)))
            ),
            // 255 byte id
            Pair(
                "da 00 00 ff" + blob255Str,
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.MIME_MEDIA, id = blob255)))
            ),
            // A Smart Poster containing a URL and no text (nested NDEF Messages)
            Pair(
                "d1 02 0f 53 70 d1 01 0b 55 01 67 6f 6f 67 6c 65 2e 63 6f 6d",
                NdefMessage(listOf(
                    NdefRecord(
                        NdefRecord.Tnf.WELL_KNOWN,
                        type = Nfc.RTD_SMART_POSTER,
                        payload = ByteString(NdefMessage(listOf(
                            NdefRecord.createUri("http://www.google.com")
                        )).encode())
                    )
                ))
            ),
        )

        for ((hexEncoding, message) in testData) {
            assertEquals(
                message,
                NdefMessage.fromEncoded(hexEncoding.replace(" ", "").fromHex()),
                "Decoding of $hexEncoding is not what was expected"
            )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun testEncodeDecode() {
        val blob256 = ByteString(ByteArray(256) { 66 })
        val blob256Str = blob256.toHexString()

        // from https://cs.android.com/android/platform/superproject/main/+/main:cts/tests/tests/ndef/src/android/ndef/cts/NdefTest.java;l=454;bpv=0;bpt=1
        val testData = listOf<Pair<String, NdefMessage>>(
            // single short record
            Pair(
                "d8 00 00 00",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.EMPTY)))
            ),
            // with id
            Pair(
                "dd 00 00 01 09",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.UNKNOWN, id = ByteString(9))))
            ),
            // with type
            Pair(
                "d4 01 00 09",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.EXTERNAL_TYPE, type = ByteString(9))))
            ),
            // with payload
            Pair(
                "d5 00 01 09",
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.UNKNOWN, payload = ByteString(9))))
            ),
            // 3 records
            Pair(
                "98 00 00 00 18 00 00 00 58 00 00 00",
                NdefMessage(listOf(
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                    NdefRecord(NdefRecord.Tnf.EMPTY),
                ))
            ),
            // 256 byte payload
            Pair(
                "c5 00 00 00 01 00 " + blob256Str,
                NdefMessage(listOf(NdefRecord(NdefRecord.Tnf.UNKNOWN, payload = blob256)))
            ),

            // from https://cs.android.com/android/platform/superproject/main/+/main:cts/tests/tests/ndef/src/android/ndef/cts/NdefTest.java;l=311;bpv=0;bpt=1
            Pair(
                "d1 01 08 55 01" + "nfc.com".encodeToByteArray().toHex(),
                NdefMessage(listOf(
                    NdefRecord.createUri("http://www.nfc.com"),
                ))
            ),
            Pair(
                "d1 01 0d 55 05" + "+35891234567".encodeToByteArray().toHex(),
                NdefMessage(listOf(
                    NdefRecord.createUri("tel:+35891234567"),
                ))
            ),
            Pair(
                "d1 01 04 55 00" + "foo".encodeToByteArray().toHex(),
                NdefMessage(listOf(
                    NdefRecord.createUri("foo"),
                ))
            ),
       )

        for ((hexEncoding, message) in testData) {
            assertEquals(
                hexEncoding.replace(" ", ""),
                message.encode().toHex(),
                "Encoding of $message to ${message.encode()} is not what was expected"
            )

            assertEquals(
                message,
                NdefMessage.fromEncoded(hexEncoding.replace(" ", "").fromHex()),
                "Decoding of $hexEncoding is not what was expected"
            )
        }
    }

    @Test
    fun testCreateUri() {
        // from https://cs.android.com/android/platform/superproject/main/+/main:cts/tests/tests/ndef/src/android/ndef/cts/NdefTest.java;l=518;bpv=0;bpt=1
        val testData = listOf<Pair<String?, NdefRecord>>(
            // absolute uri
            Pair(
                "http://www.android.com",
                NdefRecord(NdefRecord.Tnf.ABSOLUTE_URI, type = "http://www.android.com".encodeToByteString())
            ),
            // wkt uri
            Pair(
                "http://www.android.com",
                NdefRecord.createUri("http://www.android.com")
            ),
            // smart poster with absolute uri
            Pair(
                "http://www.android.com",
                NdefRecord(
                    NdefRecord.Tnf.WELL_KNOWN,
                    type = Nfc.RTD_SMART_POSTER,
                    payload = ByteString(NdefMessage(listOf(
                        NdefRecord(NdefRecord.Tnf.ABSOLUTE_URI, type = "http://www.android.com".encodeToByteString())
                    )).encode())
                )
            ),
            // smart poster with wkt uri
            Pair(
                "http://www.android.com",
                NdefRecord(
                    NdefRecord.Tnf.WELL_KNOWN,
                    type = Nfc.RTD_SMART_POSTER,
                    payload = ByteString(NdefMessage(listOf(
                        NdefRecord.createUri("http://www.android.com")
                    )).encode())
                )
            ),
            // smart poster with text and wkt uri
            Pair(
                "http://www.android.com",
                NdefRecord(
                    NdefRecord.Tnf.WELL_KNOWN,
                    type = Nfc.RTD_SMART_POSTER,
                    payload = ByteString(NdefMessage(listOf(
                        NdefRecord(NdefRecord.Tnf.WELL_KNOWN, Nfc.RTD_TEXT, ByteString(), ByteString()),
                        NdefRecord.createUri("http://www.android.com")
                    )).encode())
                )
            ),

            // Not a URI
            Pair(
                null,
                NdefRecord(NdefRecord.Tnf.EMPTY)
            ),
            Pair(
                null,
                NdefRecord.createMime("text/plain", "Some Text".encodeToByteArray())
            ),
        )

        for ((uri, record) in testData) {
            assertEquals(uri, record.uri)
        }
    }

    @Test
    fun testCreateMimeType() {
        val testData = listOf<Triple<String?, ByteString, NdefRecord>>(
            Triple(
                "text/plain",
                "Some Text".encodeToByteString(),
                NdefRecord.createMime("text/plain", "Some Text".encodeToByteArray())
            ),
        )

        for ((mimeType, payload, record) in testData) {
            assertEquals(mimeType, record.mimeType)
            assertEquals(payload, record.payload)
        }
    }
}