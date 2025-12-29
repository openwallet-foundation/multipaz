package org.multipaz.cbor

import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.util.Platform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CborTests {

    private fun cborToString(item: DataItem) = Cbor.toDiagnostics(
        item,
        setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
    )

    private fun encodeNumber(value: Long) = Cbor.toDiagnostics(
        Cbor.encode(value.toDataItem()),
        setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
    )

    private fun encodeFloat(value: Float) = Cbor.toDiagnostics(
        Cbor.encode(value.toDataItem()),
        setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
    )

    private fun encodeDouble(value: Double) = Cbor.toDiagnostics(
        Cbor.encode(value.toDataItem()),
        setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
    )

    @Test
    fun basic() {
        assertEquals(
            "[\"tstr\", h'010203fe', false, true, 42, -42]",
            cborToString(
                buildCborArray {
                    add("tstr")
                    add(byteArrayOf(1, 2, 3, 254.toByte()))
                    add(false)
                    add(true)
                    add(42)
                    add(-42)
                }
            )
        )
    }

    @Test
    fun numbers() {
        assertEquals("0", encodeNumber(0))
        assertEquals("23", encodeNumber(23))
        assertEquals("24", encodeNumber(24))
        assertEquals("255", encodeNumber(0xff))
        assertEquals("256", encodeNumber(0x100))
        assertEquals("257", encodeNumber(0x101))
        assertEquals("65535", encodeNumber(0xffff))
        assertEquals("65536", encodeNumber(0x10000))
        assertEquals("65537", encodeNumber(0x10001))
        assertEquals("4294967295", encodeNumber(0xffffffff))
        assertEquals("4294967296", encodeNumber(0x100000000))
        assertEquals("4294967297", encodeNumber(0x100000001))
        assertEquals("-23", encodeNumber(-23))
        assertEquals("-24", encodeNumber(-24))
        assertEquals("-255", encodeNumber(-0xff))
        assertEquals("-256", encodeNumber(-0x100))
        assertEquals("-257", encodeNumber(-0x101))
        assertEquals("-65535", encodeNumber(-0xffff))
        assertEquals("-65536", encodeNumber(-0x10000))
        assertEquals("-65537", encodeNumber(-0x10001))
        assertEquals("-4294967295", encodeNumber(-0xffffffff))
        assertEquals("-4294967296", encodeNumber(-0x100000000))
        assertEquals("-4294967297", encodeNumber(-0x100000001))
    }

    // Note: our tests rely on the output of Cbor.toDiagnostics() which is using a platform-
    // specific way to print float and doubles since there is no requirement in RFC 8949 on
    // how these should be formatted. Therefore, we need to adjust what we expect depending
    // on the platform.
    private val isJsOrWasm: Boolean by lazy { isJs || isWasm }

    private val isJs: Boolean by lazy {
        Platform.name.startsWith("JavaScript")
    }

    private val isWasm: Boolean by lazy {
        Platform.name.startsWith("WebAssembly")
    }

    @Test
    fun floats() {
        assertEquals(if (isJs) "0" else "0.0", encodeFloat(0.0f))
        assertEquals(if (isJs) "0.10000000149011612" else "0.1", encodeFloat(0.1f))
        assertEquals(if (isJs) "42" else "42.0", encodeFloat(42.0f))
        assertEquals(if (isJs) "42.099998474121094" else "42.1", encodeFloat(42.1f))
        assertEquals(if (isJs) "1.401298464324817e-45" else if (isWasm) "1e-45" else "1.4E-45", encodeFloat(Float.MIN_VALUE))
        assertEquals(if (isJs) "3.4028234663852886e+38" else if (isWasm) "3.4028235e+38" else "3.4028235E38", encodeFloat(Float.MAX_VALUE))
        assertEquals("NaN", encodeFloat(Float.NaN))
        assertEquals("-Infinity", encodeFloat(Float.NEGATIVE_INFINITY))
        assertEquals("Infinity", encodeFloat(Float.POSITIVE_INFINITY))
    }

    @Test
    fun doubles() {
        assertEquals(if (isJs) "0" else "0.0", encodeDouble(0.0))
        assertEquals("0.1", encodeDouble(0.1))
        assertEquals(if (isJs) "42" else "42.0", encodeDouble(42.0))
        assertEquals("42.1", encodeDouble(42.1))
        assertEquals(if (isJsOrWasm) "5e-324" else "4.9E-324", encodeDouble(Double.MIN_VALUE))
        assertEquals(if (isJsOrWasm) "1.7976931348623157e+308" else "1.7976931348623157E308", encodeDouble(Double.MAX_VALUE))
        assertEquals("NaN", encodeDouble(Double.NaN))
        assertEquals("-Infinity", encodeDouble(Double.NEGATIVE_INFINITY))
        assertEquals("Infinity", encodeDouble(Double.POSITIVE_INFINITY))
    }

    @Test
    fun array() {
        assertEquals(
            "[\"tstr\", h'010203fe', false, true, 42, -42]",
            cborToString(
                buildCborArray {
                    add("tstr")
                    add(byteArrayOf(1, 2, 3, 254.toByte()))
                    add(false)
                    add(true)
                    add(42)
                    add(-42)
                }
            )
        )
    }

    @Test
    fun arrayWithMap() {
        assertEquals(
            "[\n" +
                    "  \"tstr\",\n" +
                    "  h'010203fe',\n" +
                    "  false,\n" +
                    "  true,\n" +
                    "  42,\n" +
                    "  -42,\n" +
                    "  {\n" +
                    "    \"foo\": \"bar\",\n" +
                    "    \"baz\": \"bang\"\n" +
                    "  }\n" +
                    "]",
            cborToString(
                buildCborArray {
                    add("tstr")
                    add(byteArrayOf(1, 2, 3, 254.toByte()))
                    add(false)
                    add(true)
                    add(42)
                    add(-42)
                    addCborMap {
                        put("foo", "bar")
                        put("baz", "bang")
                    }
                }
            )
        )
    }

    @Test
    fun arrayWithArray() {
        assertEquals(
            "[\n" +
                    "  \"tstr\",\n" +
                    "  h'010203fe',\n" +
                    "  false,\n" +
                    "  true,\n" +
                    "  42,\n" +
                    "  -42,\n" +
                    "  [\"first\", \"second\"]\n" +
                    "]",
            cborToString(
                buildCborArray {
                    add("tstr")
                    add(byteArrayOf(1, 2, 3, 254.toByte()))
                    add(false)
                    add(true)
                    add(42)
                    add(-42)
                    addCborArray {
                        add("first")
                        add("second")
                    }
                }
            )
        )
    }

    @Test
    fun map() {
        assertEquals(
            "{\n" +
                    "  \"a\": h'010203fe',\n" +
                    "  \"b\": false,\n" +
                    "  \"c\": true,\n" +
                    "  \"d\": 42,\n" +
                    "  \"e\": -42\n" +
                    "}",
            cborToString(
                buildCborMap {
                    put("a", "tstr")
                    put("a", byteArrayOf(1, 2, 3, 254.toByte()))
                    put("b", false)
                    put("c", true)
                    put("d", 42)
                    put("e", -42)
                }
            )
        )
    }

    @Test
    fun mapWithArray() {
        assertEquals(
            "{\n" +
                    "  \"a\": h'010203fe',\n" +
                    "  \"b\": false,\n" +
                    "  \"c\": true,\n" +
                    "  \"d\": 42,\n" +
                    "  \"e\": -42,\n" +
                    "  \"f\": [\"first\", \"second\"]\n" +
                    "}",
            cborToString(
                buildCborMap {
                    put("a", "tstr")
                    put("a", byteArrayOf(1, 2, 3, 254.toByte()))
                    put("b", false)
                    put("c", true)
                    put("d", 42)
                    put("e", -42)
                    putCborArray("f") {
                        add("first")
                        add("second")
                    }
                }
            )
        )
    }

    @Test
    fun mapWithMaps() {
        assertEquals(
            "{\n" +
                    "  \"a\": h'010203fe',\n" +
                    "  \"b\": false,\n" +
                    "  \"c\": true,\n" +
                    "  \"d\": 42,\n" +
                    "  \"e\": -42,\n" +
                    "  \"f\": {\n" +
                    "    \"aa\": \"foo\",\n" +
                    "    \"ab\": \"bar\"\n" +
                    "  }\n" +
                    "}",
            cborToString(
                buildCborMap {
                    put("a", "tstr")
                    put("a", byteArrayOf(1, 2, 3, 254.toByte()))
                    put("b", false)
                    put("c", true)
                    put("d", 42)
                    put("e", -42)
                    putCborMap("f") {
                        put("aa", "foo")
                        put("ab", "bar")
                    }
                }
            )
        )
    }

    @Test
    fun tags() {
        assertEquals(
            "1(1707165181)",
            cborToString(Tagged(Tagged.DATE_TIME_NUMBER, Uint(1707165181UL)))
        )

        assertEquals(
            "0(\"2024-02-05T20:33:01Z\")",
            cborToString(Tagged(Tagged.DATE_TIME_STRING, Tstr("2024-02-05T20:33:01Z")))
        )
    }

    @Test
    fun tagsInArraysAndMaps() {
        assertEquals(
            "[\n" +
                    "  1(1707165181),\n" +
                    "  0(\"2024-02-05T20:33:01Z\"),\n" +
                    "  {\n" +
                    "    \"a\": 1(1707165181),\n" +
                    "    \"b\": 0(\"2024-02-05T20:33:01Z\")\n" +
                    "  }\n" +
                    "]",
            cborToString(
                buildCborArray {
                    addTagged(Tagged.DATE_TIME_NUMBER, Uint(1707165181UL))
                    addTagged(Tagged.DATE_TIME_STRING, Tstr("2024-02-05T20:33:01Z"))
                    addCborMap {
                        putTagged("a", Tagged.DATE_TIME_NUMBER, Uint(1707165181UL))
                        putTagged("b", Tagged.DATE_TIME_STRING, Tstr("2024-02-05T20:33:01Z"))
                    }
                }
            )
        )
    }

    @Test
    fun tagEncodedCbor() {
        assertEquals(
            "[\n" +
                    "  1(1707165181),\n" +
                    "  24(<< [\"first\", \"second\"] >>)\n" +
                    "]",
            cborToString(
                buildCborArray {
                    addTagged(Tagged.DATE_TIME_NUMBER, Uint(1707165181UL))
                    addTagged(
                        Tagged.ENCODED_CBOR, Bstr(
                            Cbor.encode(
                                buildCborArray {
                                    add("first")
                                    add("second")
                                }
                            )
                        )
                    )
                }
            )
        )
    }

    @Test
    fun rawCborItem() {
        assertEquals(
            "[\n" +
                    "  [\"first\", \"second\"],\n" +
                    "  \"some tstr\",\n" +
                    "  \"Sprinkled in item\",\n" +
                    "  42\n" +
                    "]",
            cborToString(
                buildCborArray {
                    add(RawCbor(
                        Cbor.encode(
                            buildCborArray {
                                add("first")
                                add("second")
                            }
                        )
                    ))
                    add(RawCbor(Cbor.encode("some tstr".toDataItem())))
                    add("Sprinkled in item")
                    add(RawCbor(Cbor.encode(42.toDataItem())))
                }
            )
        )
    }

    @Test
    fun indefiniteLengthBstr() {
        assertEquals(
            "(_ h'010203', h'040506')",
            cborToString(IndefLengthBstr(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))))
        )
    }

    @Test
    fun indefiniteLengthTstr() {
        assertEquals(
            "(_ \"hello \", \"world\")",
            cborToString(IndefLengthTstr(listOf("hello ", "world")))
        )
    }

    @Test
    fun indefiniteLengthArray() {
        assertEquals(
            "[_ \"foo\", \"bar\"]",
            cborToString(CborArray(mutableListOf("foo".toDataItem(), "bar".toDataItem()), true))
        )
    }

    @Test
    fun indefiniteLengthMap() {
        assertEquals(
            "{_\n" +
                    "  \"foo\": \"fooVal\",\n" +
                    "  \"bar\": \"barVar\"\n" +
                    "}",
            cborToString(
                CborMap(
                    mutableMapOf(
                        Pair("foo".toDataItem(), "fooVal".toDataItem()),
                        Pair("bar".toDataItem(), "barVar".toDataItem())
                    ),
                    true
                )
            )
        )
    }

    private fun encodeAndDecodeNumber(value: Long): String {
        val encodedData = Cbor.encode(value.toDataItem())
        return cborToString(Cbor.decode(encodedData))
    }

    fun assertEncodeDecode(item: DataItem) {
        val encodedData = Cbor.encode(item)
        val decodedItem = Cbor.decode(encodedData)
        assertEquals(decodedItem, item)
    }

    @Test
    fun decodeBasic() {
        assertEncodeDecode("tstr".toDataItem())
        assertEncodeDecode(byteArrayOf(1, 2, 3).toDataItem())
        assertEncodeDecode(42.toDataItem())
        assertEncodeDecode((-42).toDataItem())
        assertEncodeDecode(true.toDataItem())
        assertEncodeDecode(false.toDataItem())
        assertEncodeDecode(Simple.NULL)
        assertEncodeDecode(Simple.UNDEFINED)
        assertEncodeDecode(0.0.toDataItem())
        assertEncodeDecode(42.0.toDataItem())
        assertEncodeDecode((-42.1).toDataItem())
        assertEncodeDecode(Double.MIN_VALUE.toDataItem())
        assertEncodeDecode(Double.MAX_VALUE.toDataItem())
        assertEncodeDecode(Double.NaN.toDataItem())
        assertEncodeDecode(Double.NEGATIVE_INFINITY.toDataItem())
        assertEncodeDecode(Double.POSITIVE_INFINITY.toDataItem())
        assertEncodeDecode(0.0f.toDataItem())
        assertEncodeDecode(42.0f.toDataItem())
        assertEncodeDecode((-42.5f).toDataItem())
        // Not working on Kotlin/JS
        //assertEncodeDecode(Float.MIN_VALUE.toDataItem())
        //assertEncodeDecode(Float.MAX_VALUE.toDataItem())
        assertEncodeDecode(Float.NaN.toDataItem())
        assertEncodeDecode(Float.NEGATIVE_INFINITY.toDataItem())
        assertEncodeDecode(Float.POSITIVE_INFINITY.toDataItem())
    }

    @Test
    fun decodeNumbers() {
        assertEquals("0", encodeAndDecodeNumber(0))
        assertEquals("23", encodeAndDecodeNumber(23))
        assertEquals("24", encodeAndDecodeNumber(24))
        assertEquals("255", encodeAndDecodeNumber(0xff))
        assertEquals("256", encodeAndDecodeNumber(0x100))
        assertEquals("257", encodeAndDecodeNumber(0x101))
        assertEquals("65535", encodeAndDecodeNumber(0xffff))
        assertEquals("65536", encodeAndDecodeNumber(0x10000))
        assertEquals("65537", encodeAndDecodeNumber(0x10001))
        assertEquals("4294967295", encodeAndDecodeNumber(0xffffffff))
        assertEquals("4294967296", encodeAndDecodeNumber(0x100000000))
        assertEquals("4294967297", encodeAndDecodeNumber(0x100000001))
        assertEquals("-23", encodeAndDecodeNumber(-23))
        assertEquals("-24", encodeAndDecodeNumber(-24))
        assertEquals("-255", encodeAndDecodeNumber(-0xff))
        assertEquals("-256", encodeAndDecodeNumber(-0x100))
        assertEquals("-257", encodeAndDecodeNumber(-0x101))
        assertEquals("-65535", encodeAndDecodeNumber(-0xffff))
        assertEquals("-65536", encodeAndDecodeNumber(-0x10000))
        assertEquals("-65537", encodeAndDecodeNumber(-0x10001))
        assertEquals("-4294967295", encodeAndDecodeNumber(-0xffffffff))
        assertEquals("-4294967296", encodeAndDecodeNumber(-0x100000000))
        assertEquals("-4294967297", encodeAndDecodeNumber(-0x100000001))
    }

    @Test
    fun decodeArray() {
        assertEncodeDecode(
            buildCborArray {
                add("foo")
                add("bar")
            }
        )
    }

    @Test
    fun decodeMap() {
        assertEncodeDecode(
            buildCborMap {
                put("foo", "a")
                put("bar", "b")
            }
        )
    }

    @Test
    fun decodeTags() {
        assertEncodeDecode(
            buildCborArray {
                addTagged(Tagged.DATE_TIME_NUMBER, Uint(1707165181UL))
                addTagged(Tagged.DATE_TIME_STRING, Tstr("2024-02-05T20:33:01Z"))
            }
        )
    }

    @Test
    fun decodeRawCbor() {
        // This is a bit special because when we decode we never create any RawCborItem instances
        val itemForRaw = buildCborArray {
            add("first")
            add("second")
        }
        val encodedData = Cbor.encode(
            buildCborArray {
                add(RawCbor(Cbor.encode(itemForRaw)))
                add("a string")
            }
        )
        val decodedItem = Cbor.decode(encodedData)

        val expectedItem = buildCborArray {
            add(itemForRaw)
            add("a string")
        }
        assertEquals(expectedItem, decodedItem)
    }

    private fun encHalfFloat(first: Int, second: Int): ByteArray {
        return byteArrayOf(
            ((MajorType.SPECIAL.type shl 5) + 25).toByte(),
            first.toByte(),
            second.toByte()
        )
    }

    // https://en.wikipedia.org/wiki/Half-precision_floating-point_format#Half_precision_examples
    @Test
    fun decodeHalfFloats() {
        assertEquals(0.0f, (Cbor.decode(encHalfFloat(0x00, 0x00)) as CborFloat).value)
        assertEquals(
            if (isJs) 0.00000005960464477539063 else 0.000000059604645f,
            (Cbor.decode(encHalfFloat(0x00, 0x01)) as CborFloat).value
        )
        assertEquals(
            if (isJs) 0.00006097555160522461 else 0.00006097555f,
            (Cbor.decode(encHalfFloat(0x03, 0xff)) as CborFloat).value
        )
        assertEquals(if (isJs) 0.333251953125 else 0.33325195f, (Cbor.decode(encHalfFloat(0x35, 0x55)) as CborFloat).value)
        assertEquals(if (isJs) 0.99951171875 else 0.9995117f, (Cbor.decode(encHalfFloat(0x3b, 0xff)) as CborFloat).value)
        assertEquals(1.0f, (Cbor.decode(encHalfFloat(0x3c, 0x00)) as CborFloat).value)
        assertEquals(if (isJs) 1.0009765625 else 1.0009766f, (Cbor.decode(encHalfFloat(0x3c, 0x01)) as CborFloat).value)
        assertEquals(65504.0f, (Cbor.decode(encHalfFloat(0x7b, 0xff)) as CborFloat).value)
        assertEquals(
            Float.POSITIVE_INFINITY,
            (Cbor.decode(encHalfFloat(0x7c, 0x00)) as CborFloat).value
        )
        assertEquals(-0.0f, (Cbor.decode(encHalfFloat(0x80, 0x00)) as CborFloat).value)
        assertEquals(-2.0f, (Cbor.decode(encHalfFloat(0xc0, 0x00)) as CborFloat).value)
        assertEquals(
            Float.NEGATIVE_INFINITY,
            (Cbor.decode(encHalfFloat(0xfc, 0x00)) as CborFloat).value
        )
    }

    @Test
    fun itemToString() {
        assertEquals("Bstr(0102037f)", Bstr(byteArrayOf(1, 2, 3, 0x7f)).toString())
        assertEquals("Tstr(\"foo\")", Tstr("foo").toString())
        assertEquals("Uint(42)", Uint(42UL).toString())
        assertEquals("Nint(42)", Nint(42UL).toString())
        assertEquals("Simple(FALSE)", Simple.FALSE.toString())
        assertEquals("Simple(TRUE)", Simple.TRUE.toString())
        assertEquals("Simple(UNDEFINED)", Simple.UNDEFINED.toString())
        assertEquals("Simple(NULL)", Simple.NULL.toString())
        assertEquals("Simple(19)", Simple(19U).toString())
        assertEquals(
            "TaggedItem(42, Tstr(\"bar\"))",
            Tagged(42L, Tstr("bar")).toString()
        )
        assertEquals(
            "CborArray(Tstr(\"a\"), Tstr(\"b\"), Nint(5))",
            buildCborArray {
                add("a")
                add("b")
                add(-5)
            }.toString()
        )
        assertEquals(
            "CborMap(Tstr(\"a\") -> Tstr(\"foo\"), Nint(5) -> Tstr(\"baz\"))",
            buildCborMap {
                put("a", "foo")
                put(-5, "baz")
            }.toString()
        )
    }

    private fun encodeBstrLength(length: ULong): ByteArray {
        val b = ByteStringBuilder()
        Cbor.encodeLength(b, MajorType.BYTE_STRING, length)
        return b.toByteString().toByteArray()
    }

    // This checks we are using the fewest possible bytes for encoding lengths. This is required for
    // Preferred Serialization according to RFC 8949 4.2.1. Core Deterministic Encoding Requirements
    @Test
    fun lengthEncodings() {
        assertEquals("40", encodeBstrLength(0UL).toHex())
        assertEquals("41", encodeBstrLength(1UL).toHex())
        assertEquals("57", encodeBstrLength(23UL).toHex())
        assertEquals("5818", encodeBstrLength(24UL).toHex())
        assertEquals("5819", encodeBstrLength(25UL).toHex())
        assertEquals("58ff", encodeBstrLength((1UL shl 8) - 1UL).toHex())
        assertEquals("590100", encodeBstrLength((1UL shl 8)).toHex())
        assertEquals("590101", encodeBstrLength((1UL shl 8) + 1UL).toHex())
        assertEquals("59ffff", encodeBstrLength((1UL shl 16) - 1UL).toHex())
        assertEquals("5a00010000", encodeBstrLength((1UL shl 16)).toHex())
        assertEquals("5a00010001", encodeBstrLength((1UL shl 16) + 1UL).toHex())
        assertEquals("5affffffff", encodeBstrLength((1UL shl 32) - 1UL).toHex())
        assertEquals("5b0000000100000000", encodeBstrLength((1UL shl 32)).toHex())
        assertEquals("5b0000000100000001", encodeBstrLength((1UL shl 32) + 1UL).toHex())
    }

    data class TestVector(
        val expectedDiagnostics: String,
        val hexEncoding: String
    )

    // This is from https://www.rfc-editor.org/rfc/rfc8949.html#name-examples-of-encoded-cbor-da
    // and modified to work with the specifics of our implementation of diagnostics
    //
    private val diagnosticsTestVectors = listOf(
        TestVector("0", "00"),
        TestVector("1", "01"),
        TestVector("10", "0a"),
        TestVector("23", "17"),
        TestVector("24", "1818"),
        TestVector("25", "1819"),
        TestVector("100", "1864"),
        TestVector("1000", "1903e8"),
        TestVector("1000000", "1a000f4240"),
        TestVector("1000000000000", "1b000000e8d4a51000"),
        // TODO: add bignum support, from Appendix A:
        //  In the diagnostic notation provided for bignums, their intended numeric value is
        //  shown as a decimal number (such as 18446744073709551616) instead of a tagged byte
        //  string (such as 2(h'010000000000000000')).
        //TestVector("18446744073709551615", "1bffffffffffffffff"),
        //TestVector("18446744073709551616", "c249010000000000000000"),
        //TestVector("-18446744073709551616", "3bffffffffffffffff"),
        //TestVector("-18446744073709551617", "c349010000000000000000"),
        TestVector("-1", "20"),
        TestVector("-10", "29"),
        TestVector("-100", "3863"),
        TestVector("-1000", "3903e7"),
        TestVector(if (isJs) "0" else "0.0", "f90000"),
        TestVector(if (isJs) "0" else "-0.0", "f98000"),
        TestVector(if (isJs) "1" else "1.0", "f93c00"),
        TestVector("1.1", "fb3ff199999999999a"),
        TestVector("1.5", "f93e00"),
        TestVector(if (isJs) "65504" else "65504.0", "f97bff"),
        TestVector(if (isJs) "100000" else "100000.0", "fa47c35000"),
        TestVector(if (isJs) "3.4028234663852886e+38" else if (isWasm) "3.4028235e+38" else "3.4028235E38", "fa7f7fffff"),
        TestVector(if (isJsOrWasm) "1e+300" else "1.0E300", "fb7e37e43c8800759c"),
        TestVector(if (isJs) "5.960464477539063e-8" else if (isWasm) "5.9604645e-8" else "5.9604645E-8", "f90001"),
        TestVector(if (isJs) "0.00006103515625" else if (isWasm) "0.000061035156" else "6.1035156E-5", "f90400"),
        TestVector(if (isJs) "-4" else "-4.0", "f9c400"),
        TestVector("-4.1", "fbc010666666666666"),
        TestVector("Infinity", "f97c00"),
        TestVector("NaN", "f97e00"),
        TestVector("-Infinity", "f9fc00"),
        TestVector("Infinity", "fa7f800000"),
        TestVector("NaN", "fa7fc00000"),
        TestVector("-Infinity", "faff800000"),
        TestVector("Infinity", "fb7ff0000000000000"),
        TestVector("NaN", "fb7ff8000000000000"),
        TestVector("-Infinity", "fbfff0000000000000"),
        TestVector("false", "f4"),
        TestVector("true", "f5"),
        TestVector("null", "f6"),
        TestVector("undefined", "f7"),
        TestVector("simple(16)", "f0"),
        TestVector("simple(255)", "f8ff"),
        TestVector("0(\"2013-03-21T20:04:00Z\")", "c074323031332d30332d32315432303a30343a30305a"),
        TestVector("1(1363896240)", "c11a514b67b0"),
        TestVector(if (isJsOrWasm) "1(1363896240.5)" else "1(1.3638962405E9)", "c1fb41d452d9ec200000"),
        TestVector("23(h'01020304')", "d74401020304"),
        TestVector("24(<< \"IETF\" >>)", "d818456449455446"),
        TestVector(
            "32(\"http://www.example.com\")",
            "d82076687474703a2f2f7777772e6578616d706c652e636f6d"
        ),
        TestVector("h''", "40"),
        TestVector("h'01020304'", "4401020304"),
        TestVector("\"\"", "60"),
        TestVector("\"a\"", "6161"),
        TestVector("\"IETF\"", "6449455446"),
        TestVector("\"\\\"\\\\\"", "62225c"),
        TestVector("\"\u00fc\"", "62c3bc"),
        TestVector("\"\u6c34\"", "63e6b0b4"),
        TestVector("\"\ud800\udd51\"", "64f0908591"),
        TestVector("[]", "80"),
        TestVector("[1, 2, 3]", "83010203"),
        TestVector("[1, [2, 3], [4, 5]]", "8301820203820405"),
        TestVector(
            "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25]",
            "98190102030405060708090a0b0c0d0e0f101112131415161718181819"
        ),
        TestVector("{}", "a0"),
        TestVector("{1: 2, 3: 4}", "a201020304"),
        TestVector("{\"a\": 1, \"b\": [2, 3]}", "a26161016162820203"),
        TestVector("[\"a\", {\"b\": \"c\"}]", "826161a161626163"),
        TestVector(
            "{\"a\": \"A\", \"b\": \"B\", \"c\": \"C\", \"d\": \"D\", \"e\": \"E\"}",
            "a56161614161626142616361436164614461656145"
        ),
        TestVector("(_ h'0102', h'030405')", "5f42010243030405ff"),
        TestVector("(_ \"strea\", \"ming\")", "7f657374726561646d696e67ff"),
        TestVector("[_ ]", "9fff"),
        TestVector("[_ 1, [2, 3], [_ 4, 5]]", "9f018202039f0405ffff"),
        TestVector("[_ 1, [2, 3], [4, 5]]", "9f01820203820405ff"),
        TestVector("[1, [2, 3], [_ 4, 5]]", "83018202039f0405ff"),
        TestVector("[1, [_ 2, 3], [4, 5]]", "83019f0203ff820405"),
        TestVector(
            "[_ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25]",
            "9f0102030405060708090a0b0c0d0e0f101112131415161718181819ff"
        ),
        TestVector("{_ \"a\": 1, \"b\": [_ 2, 3]}", "bf61610161629f0203ffff"),
        TestVector("[\"a\", {_ \"b\": \"c\"}]", "826161bf61626163ff"),
        TestVector("{_ \"Fun\": true, \"Amt\": -2}", "bf6346756ef563416d7421ff")
    )

    @Test
    fun diagnosticsVectors() {
        for (testVector in diagnosticsTestVectors) {
            val encodedCbor = testVector.hexEncoding.fromHex()
            assertEquals(testVector.expectedDiagnostics, Cbor.toDiagnostics(encodedCbor))
        }
    }

    // These are from https://www.rfc-editor.org/rfc/rfc8949.html#name-examples-of-cbor-data-items
    private val nonWellformedExamples = listOf(
        // End of input in a head:
        "18", "19", "1a", "1b", "19 01", "1a 01 02", "1b 01 02 03 04 05 06 07", "38", "58",
        "78", "98", "9a 01 ff 00", "b8", "d8", "f8", "f9 00", "fa 00 00", "fb 00 00 00",

        // Definite-length strings with short data:
        "41", "61", "5a ff ff ff ff 00", "5b ff ff ff ff ff ff ff ff 01 02 03",
        "7a ff ff ff ff 00", "7b 7f ff ff ff ff ff ff ff 01 02 03",

        // Definite-length maps and arrays not closed with enough items:
        "81", "81 81 81 81 81 81 81 81 81", "82 00", "a1", "a2 01 02", "a1 00", "a2 00 00 00",

        // Tag number not followed by tag content:
        "c0",

        // Indefinite-length strings not closed by a "break" stop code:
        "5f 41 00", "7f 61 00",

        // Indefinite-length maps and arrays not closed by a "break" stop code:
        "9f", "9f 01 02", "bf", "bf 01 02 01 02", "81 9f", "9f 80 00",
        "9f 9f 9f 9f 9f ff ff ff ff", "9f 81 9f 81 9f 9f ff ff ff",

        // A few examples for the five subkinds of well-formedness error kind 3 (syntax error)
        // are shown below.
        //
        // Subkind 1:
        // Reserved additional information values:
        "1c", "1d", "1e", "3c", "3d", "3e", "5c", "5d", "5e", "7c", "7d", "7e", "9c", "9d", "9e",
        "bc", "bd", "be", "dc", "dd", "de", "fc", "fd", "fe",

        // Subkind 2:
        // Reserved two-byte encodings of simple values:
        "f8 00", "f8 01", "f8 18", "f8 1f",

        // Subkind 3:
        // Indefinite-length string chunks not of the correct type:
        "5f 00 ff", "5f 21 ff", "5f 61 00 ff", "5f 80 ff", "5f a0 ff", "5f c0 00 ff", "5f e0 ff",
        "7f 41 00 ff",
        // Indefinite-length string chunks not definite length:
        "5f 5f 41 00 ff ff", "7f 7f 61 00 ff ff",

        // Subkind 4:
        // Break occurring on its own outside of an indefinite-length item:
        "ff",
        // Break occurring in a definite-length array or map or a tag:
        "81 ff", "82 00 ff", "a1 ff", "a1 ff 00", "a1 00 ff", "a2 00 00 ff", "9f 81 ff",
        "9f 82 9f 81 9f 9f ff ff ff ff",
        // Break in an indefinite-length map that would lead to an odd number of items (break in a
        // value position):
        "bf 00 ff", "bf 00 00 00 ff",
        // Subkind 5:
        // Major type 0, 1, 6 with additional information 31:
        "1f", "3f", "df",
    )

    @Test
    fun nonWellformedThrowsWhenDecoding() {
        // Kotlin/Wasm doesn't have the same array access semantics as other platforms. In particular,
        // out-of-bounds array access exceptions are uncatchable in Kotlin, manifested as WebAssembly
        // traps. So we ignore this test on Kotlin/Wasm...
        if (isWasm) {
            return
        }
        for (hexEncodedData in nonWellformedExamples) {
            val data = hexEncodedData.replace(" ", "").fromHex()
            assertFailsWith(IllegalArgumentException::class) {
                Cbor.decode(data)
            }
        }
    }

    // ---

    @Test
    fun parseHelperBasic() {
        assertContentEquals(byteArrayOf(1, 42), byteArrayOf(1, 42).toDataItem().asBstr)
        assertEquals("Tstr", "Tstr".toDataItem().asTstr)
        assertEquals(42, 42.toDataItem().asNumber)
        assertEquals(-35, (-35).toDataItem().asNumber)
        assertEquals(42.0, 42.0.toDataItem().asDouble, 0.01)
        assertEquals(43.0f, 43.0f.toDataItem().asFloat, 0.01f)
        assertEquals(true, true.toDataItem().asBoolean)
        assertEquals(false, false.toDataItem().asBoolean)

        assertFailsWith(IllegalArgumentException::class) { 42.toDataItem().asBstr }
        assertFailsWith(IllegalArgumentException::class) { 42.toDataItem().asTstr }
        assertFailsWith(IllegalArgumentException::class) { 4.0.toDataItem().asNumber }
        assertFailsWith(IllegalArgumentException::class) { 42.toDataItem().asDouble }
        assertFailsWith(IllegalArgumentException::class) { 42.toDataItem().asFloat }
        assertFailsWith(IllegalArgumentException::class) { 42.toDataItem().asBoolean }
    }

    @Test
    fun parseHelperMap() {
        val map = buildCborMap {
            put("foo0", "Tstr".toDataItem())
            put("foo1", byteArrayOf(1, 2, 3))
            put("foo2", 42)
            put("foo3", -35)
            put("foo4", 42.0)
            put("foo5", 43.0f)
            put("foo6", true)
            put("foo7", false)
        }
        assertEquals("Tstr", map["foo0"].asTstr)
        assertContentEquals(byteArrayOf(1, 2, 3), map["foo1"].asBstr)
        assertEquals(42, map["foo2"].asNumber)
        assertEquals(-35, map["foo3"].asNumber)
        assertEquals(42.0, map["foo4"].asDouble, 0.01)
        assertEquals(43.0f, map["foo5"].asFloat, 0.01f)
        assertEquals(true, map["foo6"].asBoolean)
        assertEquals(false, map["foo7"].asBoolean)

        assertFailsWith(IllegalArgumentException::class) { map["foo1"].asTstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo2"].asTstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo3"].asTstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo4"].asTstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo5"].asTstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo6"].asTstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo7"].asTstr }

        assertFailsWith(IllegalArgumentException::class) { map["foo0"].asBstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo2"].asBstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo3"].asBstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo4"].asBstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo5"].asBstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo6"].asBstr }
        assertFailsWith(IllegalArgumentException::class) { map["foo7"].asBstr }

        assertFailsWith(IllegalArgumentException::class) { map["foo0"].asNumber }
        assertFailsWith(IllegalArgumentException::class) { map["foo1"].asNumber }
        assertFailsWith(IllegalArgumentException::class) { map["foo4"].asNumber }
        assertFailsWith(IllegalArgumentException::class) { map["foo5"].asNumber }
        assertFailsWith(IllegalArgumentException::class) { map["foo6"].asNumber }
        assertFailsWith(IllegalArgumentException::class) { map["foo7"].asNumber }

        assertFailsWith(IllegalArgumentException::class) { map["foo0"].asDouble }
        assertFailsWith(IllegalArgumentException::class) { map["foo1"].asDouble }
        assertFailsWith(IllegalArgumentException::class) { map["foo2"].asDouble }
        assertFailsWith(IllegalArgumentException::class) { map["foo3"].asDouble }
        assertFailsWith(IllegalArgumentException::class) { map["foo5"].asDouble }
        assertFailsWith(IllegalArgumentException::class) { map["foo6"].asDouble }
        assertFailsWith(IllegalArgumentException::class) { map["foo7"].asDouble }

        assertFailsWith(IllegalArgumentException::class) { map["foo0"].asFloat }
        assertFailsWith(IllegalArgumentException::class) { map["foo1"].asFloat }
        assertFailsWith(IllegalArgumentException::class) { map["foo2"].asFloat }
        assertFailsWith(IllegalArgumentException::class) { map["foo3"].asFloat }
        assertFailsWith(IllegalArgumentException::class) { map["foo4"].asFloat }
        assertFailsWith(IllegalArgumentException::class) { map["foo6"].asFloat }
        assertFailsWith(IllegalArgumentException::class) { map["foo7"].asFloat }

        assertFailsWith(IllegalArgumentException::class) { map["foo0"].asBoolean }
        assertFailsWith(IllegalArgumentException::class) { map["foo1"].asBoolean }
        assertFailsWith(IllegalArgumentException::class) { map["foo2"].asBoolean }
        assertFailsWith(IllegalArgumentException::class) { map["foo3"].asBoolean }
        assertFailsWith(IllegalArgumentException::class) { map["foo4"].asBoolean }
        assertFailsWith(IllegalArgumentException::class) { map["foo5"].asBoolean }
    }

    @Test
    fun parseHelperArray() {
        val array = buildCborArray {
            add("Tstr".toDataItem())
            add(byteArrayOf(1, 2, 3))
            add(42)
            add(-35)
            add(42.0)
            add(43.0f)
            add(true)
            add(false)
        }

        assertEquals("Tstr", array[0].asTstr)
        assertContentEquals(byteArrayOf(1, 2, 3), array[1].asBstr)
        assertEquals(42, array[2].asNumber)
        assertEquals(-35, array[3].asNumber)
        assertEquals(42.0, array[4].asDouble, 0.01)
        assertEquals(43.0f, array[5].asFloat, 0.01f)
        assertEquals(true, array[6].asBoolean)
        assertEquals(false, array[7].asBoolean)

        assertFailsWith(IllegalArgumentException::class) { array[1].asTstr }
        assertFailsWith(IllegalArgumentException::class) { array[0].asBstr }
        assertFailsWith(IllegalArgumentException::class) { array[0].asNumber }
        assertFailsWith(IllegalArgumentException::class) { array[0].asDouble }
        assertFailsWith(IllegalArgumentException::class) { array[0].asFloat }
        assertFailsWith(IllegalArgumentException::class) { array[0].asBoolean }
    }

    @Test
    fun mapLookupException() {
        val map = buildCborMap {
            put("foo0", "Tstr".toDataItem())
        }
        assertEquals("Tstr", map["foo0"].asTstr)
        assertFailsWith(IllegalStateException::class) { map["foo1"] }
    }

    @Test
    fun mapGetOrDefault() {
        val map = buildCborMap {
            put("foo0", "Tstr".toDataItem())
        }
        assertEquals("Tstr", map.getOrDefault("foo0", "dTstr".toDataItem()).asTstr)
        assertEquals("dTstr", map.getOrDefault("foo1", "dTstr".toDataItem()).asTstr)
    }

    @Test
    fun mapGetOrNull() {
        val map = buildCborMap {
            put("foo0", "Tstr".toDataItem())
        }
        assertEquals("Tstr".toDataItem(), map.getOrNull("foo0"))
        assertEquals(null, map.getOrNull("foo1"))
    }

    @Test
    fun arrayLookupException() {
        val array = buildCborArray {
            add("Tstr".toDataItem())
            add("OtherTstr".toDataItem())
        }
        assertEquals("Tstr", array[0].asTstr)
        assertEquals("OtherTstr", array[1].asTstr)
        assertFailsWith(IndexOutOfBoundsException::class) { array[2] }
    }

    @Test
    fun parseHelperTag() {
        val innerDataItem = buildCborArray {
            add("first")
            add("second")
        }
        val dataItem = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(innerDataItem)))
        assertEquals(innerDataItem, dataItem.asTaggedEncodedCbor)
    }

    @Test
    fun toDateTimeString() {
        assertEquals(
            "0(\"1970-01-01T00:00:00Z\")",
            Cbor.toDiagnostics(Instant.fromEpochMilliseconds(0).toDataItemDateTimeString()))

        assertEquals(
            "0(\"2001-09-09T01:46:40Z\")",
            Cbor.toDiagnostics(Instant.fromEpochMilliseconds(1000000000000).toDataItemDateTimeString()))

        // Check that fractions of a second is printed (only) if the fraction is non-zero.
        assertEquals(
            "0(\"1970-01-01T00:16:40.500Z\")",
            Cbor.toDiagnostics(Instant.fromEpochSeconds(1000, 500000000).toDataItemDateTimeString()))
    }

    @Test
    fun testBuildCborArray() {
        assertEquals(
            """
                [
                  "stuff",
                  42,
                  [
                    "foo",
                    "bar",
                    [
                      "baz",
                      ["end"]
                    ],
                    {
                      "a": "0",
                      "b": "1",
                      "c": {
                        "a": "0",
                        "b": "1"
                      },
                      0: 2,
                      1: 3,
                      2: {
                        "a": "0",
                        "b": "1"
                      },
                      false: {
                        "a": "0",
                        "b": "1",
                        "depth": {
                          "a": "0",
                          "b": "1"
                        }
                      }
                    }
                  ]
                ]
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                buildCborArray {
                    add("stuff")
                    add(42)
                    addCborArray {
                        add("foo")
                        add("bar")
                        addCborArray {
                            add("baz")
                            addCborArray {
                                add("end")
                            }
                        }
                        addCborMap {
                            put("a", "0")
                            put("b", "1")
                            putCborMap("c") {
                                put("a", "0")
                                put("b", "1")
                            }
                            put(0, 2)
                            put(1, 3)
                            putCborMap(2) {
                                put("a", "0")
                                put("b", "1")
                            }
                            putCborMap(Simple.FALSE) {
                                put("a", "0")
                                put("b", "1")
                                putCborMap("depth") {
                                    put("a", "0")
                                    put("b", "1")
                                }
                            }
                        }
                    }
                },
                options = setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
            ).trim()
        )
    }

    @Test
    fun testBuildCborMap() {
        assertEquals(
            """
                {
                  "foo": 1,
                  "bar": "stuff",
                  42: ["foo"],
                  "foobar": ["bar"],
                  true: ["baz"],
                  "complicated": [
                    "foo",
                    "bar",
                    [
                      "baz",
                      ["end"]
                    ]
                  ],
                  "anotherMap": {
                    "foo0": "bar0"
                  },
                  43: {
                    "foo1": "bar1"
                  },
                  false: {
                    "foo2": "bar2",
                    true: {
                      "foo3": "bar3"
                    }
                  }
                }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                buildCborMap {
                    put("foo", 1)
                    put("bar", "stuff")
                    put(42, "baz")
                    putCborArray("foobar") {
                        add("bar")
                    }
                    putCborArray(42) {
                        add("foo")
                    }
                    putCborArray(Simple.TRUE) {
                        add("baz")
                    }
                    putCborArray("complicated") {
                        add("foo")
                        add("bar")
                        addCborArray {
                            add("baz")
                            addCborArray {
                                add("end")
                            }
                        }
                    }
                    putCborMap("anotherMap") {
                        put("foo0", "bar0")
                    }
                    putCborMap(43) {
                        put("foo1", "bar1")
                    }
                    putCborMap(Simple.FALSE) {
                        put("foo2", "bar2")
                        putCborMap(Simple.TRUE) {
                            put("foo3", "bar3")
                        }
                    }
                },
                options = setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
            ).trim()
        )
    }

    @Test
    fun testToJson() {
        assertEquals(JsonPrimitive("AQIDBA"),Bstr(byteArrayOf(1, 2, 3, 4)).toJson())

        assertEquals("Hello \"World\"",Tstr("Hello \"World\"").toJson().jsonPrimitive.content)
        assertEquals(JsonPrimitive(42),Uint(42UL).toJson())
        assertEquals(JsonPrimitive(-25),Nint(25UL).toJson())

        assertEquals(JsonPrimitive(42.0),CborDouble(42.0).toJson())
        assertEquals(JsonNull,CborDouble(Double.NaN).toJson())
        assertEquals(JsonNull,CborDouble(Double.NEGATIVE_INFINITY).toJson())
        assertEquals(JsonNull,CborDouble(Double.POSITIVE_INFINITY).toJson())
        assertEquals(JsonPrimitive(-25.0f),CborFloat(-25.0f).toJson())
        assertEquals(JsonNull,CborFloat(Float.NaN).toJson())
        assertEquals(JsonNull,CborFloat(Float.NEGATIVE_INFINITY).toJson())
        assertEquals(JsonNull,CborFloat(Float.POSITIVE_INFINITY).toJson())

        assertEquals(
            buildJsonArray {
                add(1)
                add(2)
                add("foobar")
            },
            buildCborArray {
                add(1)
                add(2)
                add("foobar")
            }.toJson()
        )

        assertEquals(
            buildJsonObject {
                put("a", 1)
                put("b", 2)
                put("c", "foobar")
                put("42", "bazPos")
                put("-25", "bazNeg")
                put(if (isJs) "42" else "42.0", "bazDPos")
                put(if (isJs) "-25" else "-25.0", "bazFNeg")
                put("h'01020304'", "bazBin")
            },
            buildCborMap {
                put("a", 1)
                put("b", 2)
                put("c", "foobar")
                put(42, "bazPos")
                put(-25, "bazNeg")
                put(CborDouble(42.0), Tstr("bazDPos"))
                put(CborFloat(-25.0f), Tstr("bazFNeg"))
                put(Bstr(byteArrayOf(1, 2, 3, 4)), Tstr("bazBin"))
            }.toJson()
        )

        assertEquals(JsonPrimitive(true),Simple.TRUE.toJson())
        assertEquals(JsonPrimitive(false),Simple.FALSE.toJson())
        assertEquals(JsonNull,Simple.NULL.toJson())
        assertEquals(JsonNull,Simple(42U).toJson())

        assertEquals(
            JsonPrimitive("AQIDDw"),
            Tagged(
                tagNumber = Tagged.ENCODING_HINT_BASE64URL,
                taggedItem = Bstr(byteArrayOf(1, 2, 3, 15)),
            ).toJson()
        )
        assertEquals(
            JsonPrimitive("AQIDDw=="),
            Tagged(
                tagNumber = Tagged.ENCODING_HINT_BASE64_WITH_PADDING,
                taggedItem = Bstr(byteArrayOf(1, 2, 3, 15)),
            ).toJson()
        )
        assertEquals(
            JsonPrimitive("0102030F"),
            Tagged(
                tagNumber = Tagged.ENCODING_HINT_HEX,
                taggedItem = Bstr(byteArrayOf(1, 2, 3, 15)),
            ).toJson()
        )
        assertEquals(
            JsonPrimitive("AQIDDw"),
            Tagged(
                tagNumber = 42,
                taggedItem = Bstr(byteArrayOf(1, 2, 3, 15)),
            ).toJson()
        )
    }
}