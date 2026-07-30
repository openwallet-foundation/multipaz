package org.multipaz.cbor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CdnTests {

    @Test
    fun testIntegers() {
        assertEquals(Uint(12345UL), Cdn.parse("12345"))
        assertEquals(Nint(12345UL), Cdn.parse("-12345"))
        assertEquals(Uint(0UL), Cdn.parse("0"))
        assertEquals(Uint(255UL), Cdn.parse("0xff"))
        assertEquals(Uint(511UL), Cdn.parse("0o777"))
        assertEquals(Uint(10UL), Cdn.parse("0b1010"))

        assertEquals("12345", Cdn.encode(Uint(12345UL)))
        assertEquals("-12345", Cdn.encode(Nint(12345UL)))
    }

    @Test
    fun testFloats() {
        assertEquals(CborDouble(3.14159), Cdn.parse("3.14159"))
        assertEquals(CborDouble(-0.5), Cdn.parse("-0.5"))
        assertEquals(CborDouble(Double.POSITIVE_INFINITY), Cdn.parse("Infinity"))
        assertEquals(CborDouble(Double.NEGATIVE_INFINITY), Cdn.parse("-Infinity"))

        val nanParsed = Cdn.parse("NaN")
        assertTrue(nanParsed is CborDouble && nanParsed.value.isNaN())
    }

    @Test
    fun testSimpleValues() {
        assertEquals(Simple.TRUE, Cdn.parse("true"))
        assertEquals(Simple.FALSE, Cdn.parse("false"))
        assertEquals(Simple.NULL, Cdn.parse("null"))
        assertEquals(Simple.UNDEFINED, Cdn.parse("undefined"))
        assertEquals(Simple(23U), Cdn.parse("simple(23)"))

        assertEquals("true", Cdn.encode(Simple.TRUE))
        assertEquals("false", Cdn.encode(Simple.FALSE))
        assertEquals("null", Cdn.encode(Simple.NULL))
        assertEquals("undefined", Cdn.encode(Simple.UNDEFINED))
    }

    @Test
    fun testTextStrings() {
        assertEquals(Tstr("hello world"), Cdn.parse("\"hello world\""))
        assertEquals(Tstr("hello\nworld"), Cdn.parse("\"hello\\nworld\""))
        assertEquals(Tstr("A"), Cdn.parse("\"\\u0041\""))
        assertEquals(Tstr("multi\nline"), Cdn.parse("\"\"\"multi\nline\"\"\""))

        assertEquals("\"hello world\"", Cdn.encode(Tstr("hello world")))
    }

    @Test
    fun testByteStrings() {
        assertEquals(Bstr(byteArrayOf(0x01, 0x02, 0x03, 0x04)), Cdn.parse("h'01020304'"))
        assertEquals(Bstr(byteArrayOf(0x01, 0x02, 0x03, 0x04)), Cdn.parse("b64'AQIDBA=='"))
        assertEquals(Bstr("hello".encodeToByteArray()), Cdn.parse("'hello'"))
        assertEquals(Bstr("raw bytes".encodeToByteArray()), Cdn.parse("'''raw bytes'''"))

        val bstr = Bstr(byteArrayOf(0x01, 0x02))
        assertEquals("h'0102'", Cdn.encode(bstr, CdnGeneratorOptions(byteStringFormat = ByteStringFormat.HEX)))
        val encodedB64 = Cdn.encode(bstr, CdnGeneratorOptions(byteStringFormat = ByteStringFormat.BASE64))
        assertTrue(encodedB64.startsWith("b64'AQI"))
    }

    @Test
    fun testArrays() {
        val expected = CborArray.builder().add(1).add(2).add(3).end().build()
        assertEquals(expected, Cdn.parse("[1, 2, 3]"))

        val indefExpected = CborArray(mutableListOf<DataItem>(Uint(1UL), Uint(2UL)), indefiniteLength = true)
        assertEquals(indefExpected, Cdn.parse("[_ 1, 2]"))

        assertEquals("[1, 2, 3]", Cdn.encode(expected))
        assertEquals("[_ 1, 2]", Cdn.encode(indefExpected))
    }

    @Test
    fun testMaps() {
        val expected = CborMap.builder().put("key", "value").put(1, 2).end().build()
        assertEquals(expected, Cdn.parse("{\"key\": \"value\", 1: 2}"))
        assertEquals(expected, Cdn.parse("{\"key\" => \"value\", 1 => 2}"))

        val indefExpected = CborMap(mutableMapOf<DataItem, DataItem>(Tstr("a") to Uint(1UL)), indefiniteLength = true)
        assertEquals(indefExpected, Cdn.parse("{_ \"a\": 1}"))
    }

    @Test
    fun testIndefiniteLengthStrings() {
        val parsedBstr = Cdn.parse("(_ h'0102', h'0304')")
        assertTrue(parsedBstr is IndefLengthBstr)
        assertEquals(2, parsedBstr.chunks.size)
        assertContentEquals(byteArrayOf(1, 2), parsedBstr.chunks[0])
        assertContentEquals(byteArrayOf(3, 4), parsedBstr.chunks[1])

        val tstrIndef = IndefLengthTstr(listOf("hello ", "world"))
        assertEquals(tstrIndef, Cdn.parse("(_ \"hello \", \"world\")"))
    }

    @Test
    fun testTaggedAndEmbeddedCbor() {
        val tagged = Tagged(32L, Tstr("https://example.com"))
        assertEquals(tagged, Cdn.parse("32(\"https://example.com\")"))

        val embeddedCbor = Cdn.parse("<< 1234 >>")
        assertTrue(embeddedCbor is Tagged && embeddedCbor.tagNumber == Tagged.ENCODED_CBOR)
        assertEquals(Uint(1234UL), Cbor.decode((embeddedCbor.taggedItem as Bstr).value))

        assertEquals("<< 1234 >>", Cdn.encode(embeddedCbor))
    }

    @Test
    fun testComments() {
        val cdnWithComments = """
            // This is a line comment
            {
                /* block comment */
                "foo": /* inside map */ "bar" // trailing comment
            }
        """.trimIndent()

        val parsed = Cdn.parse(cdnWithComments)
        assertEquals(CborMap.builder().put("foo", "bar").end().build(), parsed)
    }

    @Test
    fun testApplicationExtensions() {
        val dtParsed = Cdn.parse("dt'2026-07-27T16:00:00Z'")
        assertEquals(Tagged(Tagged.DATE_TIME_STRING, Tstr("2026-07-27T16:00:00Z")), dtParsed)

        val ipv4Parsed = Cdn.parse("ip'192.168.1.1'")
        assertEquals(Tagged(52L, Bstr(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))), ipv4Parsed)

        val ipv6Parsed = Cdn.parse("ip'2001:db8::1'")
        assertTrue(ipv6Parsed is Tagged && ipv6Parsed.tagNumber == 54L)

        assertEquals("dt'2026-07-27T16:00:00Z'", Cdn.encode(dtParsed))
        assertEquals("ip'192.168.1.1'", Cdn.encode(ipv4Parsed))
    }

    @Test
    fun testSequenceParsing() {
        val seqText = "1, 2, \"hello\", true"
        val sequence = Cdn.parseSequence(seqText)
        assertEquals(4, sequence.size)
        assertEquals(Uint(1UL), sequence[0])
        assertEquals(Uint(2UL), sequence[1])
        assertEquals(Tstr("hello"), sequence[2])
        assertEquals(Simple.TRUE, sequence[3])
    }

    @Test
    fun testPrettyPrintOptions() {
        val map = CborMap.builder().put("a", 1).put("b", 2).end().build()
        val prettyCdn = Cdn.encode(map, CdnGeneratorOptions.Pretty)
        assertTrue(prettyCdn.contains("\n"))
        assertTrue(prettyCdn.contains("  \"a\": 1"))
    }

    @Test
    fun testSyntaxErrors() {
        val ex = assertFailsWith<CdnException> {
            Cdn.parse("{ invalid json }")
        }
        assertTrue(ex.message!!.contains("CDN parse error"))
    }

    @Test
    fun testCustomExtensionRegistryPassing() {
        val customRegistry = CdnExtensionRegistry()
        customRegistry.register(object : CdnExtension {
            override val identifier: String = "custom"
            override fun parseLiteral(content: String, delimiter: Char): DataItem {
                return Tstr("CUSTOM:$content")
            }
            override fun format(item: DataItem, options: CdnGeneratorOptions): String? {
                if (item is Tstr && item.value.startsWith("CUSTOM:")) {
                    return "custom'${item.value.removePrefix("CUSTOM:")}'"
                }
                return null
            }
        })

        val item = Cdn.parse("custom'hello'", customRegistry)
        assertEquals(Tstr("CUSTOM:hello"), item)
        val formatted = Cdn.encode(item, customRegistry)
        assertEquals("custom'hello'", formatted)
    }

    @Test
    fun testFloatExponentAndEmbeddedSequence() {
        val floatItem = Cdn.parse("1.5e-3")
        assertTrue(floatItem is CborDouble)
        assertEquals(0.0015, (floatItem as CborDouble).value, 1e-9)

        val embeddedSeq = Cdn.parse("<< 10, 20 >>")
        assertTrue(embeddedSeq is Tagged && embeddedSeq.tagNumber == 24L)
        val bytes = (embeddedSeq.taggedItem as Bstr).value
        val (offset1, decoded1) = Cbor.decode(bytes, 0)
        val (_, decoded2) = Cbor.decode(bytes, offset1)
        assertEquals(Uint(10UL), decoded1)
        assertEquals(Uint(20UL), decoded2)
    }

    @Test
    fun testUserCoseKeySnippetWithSlashAndHashComments() {
        val snippet = """
            {
             /kty/ 1 : 4, # Symmetric
             /alg/ 3 : 5, # HMAC 256-256
              /k/ -1 : h'6684523ab17337f173500e5728c628547cb37df
                         e68449c65f885d1b73b49eae1'
            }
        """.trimIndent()
        val item = Cdn.parse(snippet)
        assertTrue(item is CborMap)
        val map = item as CborMap
        assertEquals(Uint(4UL), map[Uint(1UL)])
        assertEquals(Uint(5UL), map[Uint(3UL)])
        assertTrue(map[Nint(1UL)] is Bstr)
        val kBytes = (map[Nint(1UL)] as Bstr).value
        assertEquals(32, kBytes.size)
    }

    @Test
    fun testRoundtrip() {
        val items = listOf(
            Uint(42UL),
            Nint(99UL),
            CborDouble(12.34),
            Tstr("roundtrip test"),
            Bstr(byteArrayOf(0x0a, 0x0b, 0x0c)),
            CborArray.builder().add("element").add(true).end().build(),
            CborMap.builder().put("x", 10).put("y", 20).end().build(),
            Tagged(0L, Tstr("2026-07-27T16:00:00Z"))
        )

        for (item in items) {
            val cdnStr = Cdn.encode(item)
            val decodedItem = Cdn.parse(cdnStr)
            assertEquals(item, decodedItem, "Roundtrip failed for item: $item")
        }
    }
}
