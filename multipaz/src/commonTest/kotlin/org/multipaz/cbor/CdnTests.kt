package org.multipaz.cbor

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.buildX509Cert
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

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
        val expected = buildCborArray { add(1); add(2); add(3) }
        assertEquals(expected, Cdn.parse("[1, 2, 3]"))

        val indefExpected = CborArray(mutableListOf<DataItem>(Uint(1UL), Uint(2UL)), indefiniteLength = true)
        assertEquals(indefExpected, Cdn.parse("[_ 1, 2]"))

        assertEquals("[1, 2, 3]", Cdn.encode(expected))
        assertEquals("[_ 1, 2]", Cdn.encode(indefExpected))
    }

    @Test
    fun testMaps() {
        val expected = buildCborMap { put("key", "value"); put(1, 2) }
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
        assertTrue(embeddedCbor is Bstr)
        assertEquals(Uint(1234UL), Cbor.decode(embeddedCbor.asBstr))
        assertEquals("<< 1234 >>", Cdn.encode(embeddedCbor, CdnGeneratorOptions(useEmbeddedCborOpportunistically = true)))

        val taggedEmbeddedCbor = Cdn.parse("24(<< 1234 >>)")
        assertTrue(taggedEmbeddedCbor is Tagged && taggedEmbeddedCbor.tagNumber == Tagged.ENCODED_CBOR)
        assertEquals(Uint(1234UL), Cbor.decode((taggedEmbeddedCbor.taggedItem as Bstr).value))
        assertEquals("24(<< 1234 >>)", Cdn.encode(taggedEmbeddedCbor))
    }

    @Test
    fun testUseEmbeddedCborOpportunistically() {
        val protectedHeader = Cbor.encode(buildCborMap { put(1L, -7L) })
        val payload = Cbor.encode(buildCborMap { put("foo", "bar") })
        val coseSign1 = Tagged(
            18L,
            buildCborArray {
                add(protectedHeader)
                add(buildCborMap {})
                add(payload)
                add(byteArrayOf(0x01, 0x02))
            }
        )

        val cdnWithEmbedded = Cdn.encode(coseSign1, CdnGeneratorOptions(useEmbeddedCborOpportunistically = true, annotateCoseOpportunistically = false))
        assertEquals("18([<< {1: -7} >>, {}, << {\"foo\": \"bar\"} >>, h'0102'])", cdnWithEmbedded)

        val cdnWithoutEmbedded = Cdn.encode(coseSign1, CdnGeneratorOptions(useEmbeddedCborOpportunistically = false, annotateCoseOpportunistically = false))
        assertEquals("18([h'a10126', {}, h'a163666f6f63626172', h'0102'])", cdnWithoutEmbedded)

        val mapWithEmbeddedBstr = buildCborMap {
            put("plain_bytes", byteArrayOf(0x01, 0x02))
            put("cbor_bytes", Cbor.encode(Tstr("hello")))
        }
        val cdnMapWithEmbedded = Cdn.encode(mapWithEmbeddedBstr, CdnGeneratorOptions(useEmbeddedCborOpportunistically = true))
        assertEquals("{\"plain_bytes\": h'0102', \"cbor_bytes\": << \"hello\" >>}", cdnMapWithEmbedded)

        // Verify round trip parsing: << ... >> parses into untagged Bstr
        val parsedBstr = Cdn.parse("<< \"hello\" >>")
        assertEquals(Bstr(Cbor.encode(Tstr("hello"))), parsedBstr)

        // Verify round trip parsing: 24(<< ... >>) parses into Tagged(24, Bstr)
        val parsedTagged = Cdn.parse("24(<< \"hello\" >>)")
        assertEquals(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(Tstr("hello")))), parsedTagged)
    }

    @Test
    fun testUseEmbeddedCertsOpportunistically() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val cert = buildX509Cert(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, Algorithm.ES256),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Test Cert"),
            issuer = X500Name.fromName("CN=Test Cert"),
            validFrom = Instant.fromEpochMilliseconds(1000000000000L),
            validUntil = Instant.fromEpochMilliseconds(2000000000000L)
        ) {}

        val certBstr = Bstr(cert.encoded.toByteArray())
        val cdnWithCert = Cdn.encode(certBstr, CdnGeneratorOptions(useEmbeddedCertsOpportunistically = true))
        assertTrue(cdnWithCert.startsWith("cert'''\n# Subject DN: CN=Test Cert\n# Issuer DN: CN=Test Cert\n-----BEGIN CERTIFICATE-----\n"))
        assertTrue(cdnWithCert.endsWith("\n-----END CERTIFICATE-----\n'''"))

        val prettyCertCdn = Cdn.encode(buildCborMap { put(33L, certBstr) }, CdnGeneratorOptions.Pretty)
        assertTrue(prettyCertCdn.contains("33:\n  # Subject DN: CN=Test Cert\n  # Issuer DN: CN=Test Cert\n  cert'''\n    -----BEGIN CERTIFICATE-----\n"))

        val certArrayMap = buildCborMap {
            put(33L, buildCborArray {
                add(certBstr)
                add(certBstr)
            })
        }
        val prettyCertArrayCdn = Cdn.encode(certArrayMap, CdnGeneratorOptions.Pretty)
        assertTrue(prettyCertArrayCdn.contains("33: [\n    # Subject DN: CN=Test Cert\n    # Issuer DN: CN=Test Cert\n    cert'''\n"))
        assertFalse(prettyCertArrayCdn.contains("[\n\n"))
        assertFalse(prettyCertArrayCdn.contains("''',\n\n"))

        val parsedBack = Cdn.parse(cdnWithCert)
        assertEquals(certBstr, parsedBack)

        val cdnWithoutCert = Cdn.encode(certBstr, CdnGeneratorOptions(useEmbeddedCertsOpportunistically = false))
        assertTrue(cdnWithoutCert.startsWith("h'"))
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
        assertEquals(buildCborMap { put("foo", "bar") }, parsed)
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
        val map = buildCborMap { put("a", 1); put("b", 2) }
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
            override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? {
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
        assertTrue(embeddedSeq is Bstr)
        val bytes = embeddedSeq.asBstr
        val (offset1, decoded1) = Cbor.decode(bytes, 0)
        val (_, decoded2) = Cbor.decode(bytes, offset1)
        assertEquals(Uint(10UL), decoded1)
        assertEquals(Uint(20UL), decoded2)

        val taggedSeq = Cdn.parse("24(<< 10, 20 >>)")
        assertTrue(taggedSeq is Tagged && taggedSeq.tagNumber == 24L)
        val taggedBytes = (taggedSeq.taggedItem as Bstr).value
        val (tOffset1, tDecoded1) = Cbor.decode(taggedBytes, 0)
        val (_, tDecoded2) = Cbor.decode(taggedBytes, tOffset1)
        assertEquals(Uint(10UL), tDecoded1)
        assertEquals(Uint(20UL), tDecoded2)
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
            buildCborArray { add("element"); add(true) },
            buildCborMap { put("x", 10); put("y", 20) },
            Tagged(0L, Tstr("2026-07-27T16:00:00Z"))
        )

        for (item in items) {
            val cdnStr = Cdn.encode(item)
            val decodedItem = Cdn.parse(cdnStr)
            assertEquals(item, decodedItem, "Roundtrip failed for item: $item")
        }
    }

    @Test
    fun testOpportunisticCoseSign1() {
        val protectedMap = buildCborMap {
            put(1, -7)
            put(33, Bstr("certData".encodeToByteArray()))
        }
        val protectedBytes = Cbor.encode(protectedMap)
        val coseSign1 = Tagged(
            18L,
            buildCborArray {
                add(Bstr(protectedBytes))
                add(buildCborMap {})
                add(Bstr("payload".encodeToByteArray()))
                add(Bstr("signature".encodeToByteArray()))
            }
        )

        val prettyAnnotated = Cdn.encode(coseSign1, CdnGeneratorOptions.Pretty)
        assertTrue(prettyAnnotated.contains("# COSE_Sign1"))
        assertTrue(prettyAnnotated.contains("/protected/"))
        assertTrue(prettyAnnotated.contains("/unprotected/"))
        assertTrue(prettyAnnotated.contains("/payload/"))
        assertTrue(prettyAnnotated.contains("/signature/"))
        assertTrue(prettyAnnotated.contains("/alg/"))
        assertTrue(prettyAnnotated.contains("# ES256: ECDSA with SHA-256"))
        assertTrue(prettyAnnotated.contains("/x5chain/"))

        val roundtripItem = Cdn.parse(prettyAnnotated)
        assertEquals(coseSign1, roundtripItem)
    }

    @Test
    fun testOpportunisticCoseMac0() {
        val protectedMap = buildCborMap {
            put(1, 5)
        }
        val protectedBytes = Cbor.encode(protectedMap)
        val coseMac0 = Tagged(
            17L,
            buildCborArray {
                add(Bstr(protectedBytes))
                add(buildCborMap {})
                add(Bstr("payload".encodeToByteArray()))
                add(Bstr("macTag".encodeToByteArray()))
            }
        )

        val prettyAnnotated = Cdn.encode(coseMac0, CdnGeneratorOptions.Pretty)
        assertTrue(prettyAnnotated.contains("# COSE_Mac0"))
        assertTrue(prettyAnnotated.contains("/protected/"))
        assertTrue(prettyAnnotated.contains("/unprotected/"))
        assertTrue(prettyAnnotated.contains("/payload/"))
        assertTrue(prettyAnnotated.contains("/tag/"))
        assertTrue(prettyAnnotated.contains("/alg/"))
        assertTrue(prettyAnnotated.contains("# HMAC_SHA256: HMAC with SHA-256"))

        val roundtripItem = Cdn.parse(prettyAnnotated)
        assertEquals(coseMac0, roundtripItem)
    }

    @Test
    fun testOpportunisticUntaggedCoseMac0() {
        val protectedMap = buildCborMap {
            put(1, 5)
        }
        val protectedBytes = Cbor.encode(protectedMap)
        val untaggedCoseMac0 = buildCborArray {
            add(Bstr(protectedBytes))
            add(buildCborMap {})
            add(Bstr("payload".encodeToByteArray()))
            add(Bstr("macTag".encodeToByteArray()))
        }

        val prettyAnnotated = Cdn.encode(untaggedCoseMac0, CdnGeneratorOptions.Pretty)
        assertTrue(prettyAnnotated.contains("# COSE_Mac0"))
        assertTrue(prettyAnnotated.contains("/protected/"))
        assertTrue(prettyAnnotated.contains("/unprotected/"))
        assertTrue(prettyAnnotated.contains("/payload/"))
        assertTrue(prettyAnnotated.contains("/tag/"))
        assertTrue(prettyAnnotated.contains("/alg/"))
        assertTrue(prettyAnnotated.contains("# HMAC_SHA256: HMAC with SHA-256"))

        val roundtripItem = Cdn.parse(prettyAnnotated)
        assertEquals(untaggedCoseMac0, roundtripItem)
    }

    @Test
    fun testOpportunisticCoseKey() {
        val ecKey = buildCborMap {
            put(1, 2)
            put(3, -7)
            put(-1, 1)
            put(-2, Bstr(ByteArray(32)))
            put(-3, Bstr(ByteArray(32)))
        }

        val prettyAnnotated = Cdn.encode(ecKey, CdnGeneratorOptions.Pretty)
        assertTrue(prettyAnnotated.contains("# COSE_Key"))
        assertTrue(prettyAnnotated.contains("/kty/ 1: 2, # EC2"))
        assertTrue(prettyAnnotated.contains("/alg/ 3: -7, # ES256: ECDSA with SHA-256"))
        assertTrue(prettyAnnotated.contains("/crv/ -1: 1, # P-256"))
        assertTrue(prettyAnnotated.contains("/x/ -2"))
        assertTrue(prettyAnnotated.contains("/y/ -3"))

        val roundtripItem = Cdn.parse(prettyAnnotated)
        assertEquals(ecKey, roundtripItem)
    }

    @Test
    fun testOpportunisticCoseDisabled() {
        val ecKey = buildCborMap {
            put(1, 2)
            put(3, -7)
            put(-1, 1)
        }
        val noCoseOptions = CdnGeneratorOptions(prettyPrint = true, annotateCoseOpportunistically = false)
        val text = Cdn.encode(ecKey, noCoseOptions)
        assertTrue(!text.contains("/ kty /"))
        assertTrue(!text.contains("# EC2"))
    }
}
