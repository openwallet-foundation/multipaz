package org.multipaz.asn1

import org.multipaz.crypto.X509Cert
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.bytestring.ByteStringBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ASN1Tests {

    private fun encodeTLV(
        cls: ASN1TagClass,
        enc: ASN1Encoding,
        tag: Int,
        length: Int
    ): ByteArray {
        val bsb = ByteStringBuilder()
        ASN1.appendIdentifierAndLength(bsb, cls, enc, tag, length)
        return bsb.toByteString().toByteArray()
    }

    private fun decodeTagFromTLV(
        encoded: ByteArray
    ): Int {
        val (offset, identifierOctects) = ASN1.decodeIdentifierOctets(encoded, 0)
        return identifierOctects.tag
    }

    @Test
    fun testTLVEncoding() {
        assertEquals("1e00", encodeTLV(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x1e, 0).toHex())
        assertEquals("1f1f00", encodeTLV(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x1f, 0).toHex())
        assertEquals("1f7f00", encodeTLV(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x7f, 0).toHex())
        assertEquals("1f810000", encodeTLV(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x80, 0).toHex())
        assertEquals("1f820000", encodeTLV(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x100, 0).toHex())
        assertEquals("1fbf7f00", encodeTLV(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x1fff, 0).toHex())

        assertEquals(0x1e, decodeTagFromTLV("1e00".fromHex()))
        assertEquals(0x1f, decodeTagFromTLV("1f1f00".fromHex()))
        assertEquals(0x7f, decodeTagFromTLV("1f7f00".fromHex()))
        assertEquals(0x80, decodeTagFromTLV("1f810000".fromHex()))
        assertEquals(0x100, decodeTagFromTLV("1f820000".fromHex()))
        assertEquals(0x1fff, decodeTagFromTLV("1fbf7f00".fromHex()))
    }

    @Test
    fun testBoolean() {
        assertContentEquals("010100".fromHex(), ASN1.encode(ASN1Boolean(false)))
        assertContentEquals("0101ff".fromHex(), ASN1.encode(ASN1Boolean(true)))

        assertEquals(ASN1Boolean(false), ASN1.decode("010100".fromHex()))
        assertEquals(ASN1Boolean(true), ASN1.decode("0101ff".fromHex()))
        assertFailsWith(IllegalArgumentException::class) {
            assertEquals(ASN1Boolean(false), ASN1.decode("010101".fromHex()))
        }

        assertEquals(
            """
            SEQUENCE (2 elem)
              BOOLEAN true
              BOOLEAN false
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1Boolean(true),
                ASN1Boolean(false),
            ))).trim()
        )
    }

    @Test
    fun testInteger() {
        // See also ASN1TestsJvm.kt which tests that Long.derEncodeToByteArray() and
        // ByteArray.derDecodeAsLong() works as expected.
        //
        assertEquals("020100", ASN1.encode(ASN1Integer(0)).toHex())
        assertEquals("020101", ASN1.encode(ASN1Integer(1)).toHex())
        assertEquals("02020080", ASN1.encode(ASN1Integer(128L)).toHex())
        assertEquals("0201ff", ASN1.encode(ASN1Integer(-1L)).toHex())
        assertEquals("0202ff01", ASN1.encode(ASN1Integer(-255L)).toHex())
        assertEquals("0202ff00", ASN1.encode(ASN1Integer(-256L)).toHex())

        assertEquals(ASN1Integer(0L), ASN1.decode("020100".fromHex()))
        assertEquals(ASN1Integer(1L), ASN1.decode("020101".fromHex()))
        assertEquals(ASN1Integer(128L), ASN1.decode("02020080".fromHex()))
        assertEquals(ASN1Integer(-1L), ASN1.decode("0201ff".fromHex()))
        assertEquals(ASN1Integer(-255L), ASN1.decode("0202ff01".fromHex()))
        assertEquals(ASN1Integer(-256L), ASN1.decode("0202ff00".fromHex()))

        assertEquals(
            """
            SEQUENCE (6 elem)
              INTEGER 0
              INTEGER 1
              INTEGER 128
              INTEGER -1
              INTEGER -255
              INTEGER -256
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1Integer(0L),
                ASN1Integer(1L),
                ASN1Integer(128L),
                ASN1Integer(-1L),
                ASN1Integer(-255L),
                ASN1Integer(-256L),
            ))).trim()
        )
    }

    @Test
    fun testEnumerated() {
        assertEquals("0a0100", ASN1.encode(ASN1Integer(0, ASN1IntegerTag.ENUMERATED.tag)).toHex())
        assertEquals("0a0101", ASN1.encode(ASN1Integer(1, ASN1IntegerTag.ENUMERATED.tag)).toHex())
        assertEquals("0a020080", ASN1.encode(ASN1Integer(128L, ASN1IntegerTag.ENUMERATED.tag)).toHex())
        assertEquals("0a01ff", ASN1.encode(ASN1Integer(-1L, ASN1IntegerTag.ENUMERATED.tag)).toHex())
        assertEquals("0a02ff01", ASN1.encode(ASN1Integer(-255L, ASN1IntegerTag.ENUMERATED.tag)).toHex())
        assertEquals("0a02ff00", ASN1.encode(ASN1Integer(-256L, ASN1IntegerTag.ENUMERATED.tag)).toHex())

        assertEquals(ASN1Integer(0L, ASN1IntegerTag.ENUMERATED.tag), ASN1.decode("0a0100".fromHex()))
        assertEquals(ASN1Integer(1L, ASN1IntegerTag.ENUMERATED.tag), ASN1.decode("0a0101".fromHex()))
        assertEquals(ASN1Integer(128L, ASN1IntegerTag.ENUMERATED.tag), ASN1.decode("0a020080".fromHex()))
        assertEquals(ASN1Integer(-1L, ASN1IntegerTag.ENUMERATED.tag), ASN1.decode("0a01ff".fromHex()))
        assertEquals(ASN1Integer(-255L, ASN1IntegerTag.ENUMERATED.tag), ASN1.decode("0a02ff01".fromHex()))
        assertEquals(ASN1Integer(-256L, ASN1IntegerTag.ENUMERATED.tag), ASN1.decode("0a02ff00".fromHex()))

        assertEquals(
            """
            SEQUENCE (6 elem)
              ENUMERATED 0
              ENUMERATED 1
              ENUMERATED 128
              ENUMERATED -1
              ENUMERATED -255
              ENUMERATED -256
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1Integer(0L, ASN1IntegerTag.ENUMERATED.tag),
                ASN1Integer(1L, ASN1IntegerTag.ENUMERATED.tag),
                ASN1Integer(128L, ASN1IntegerTag.ENUMERATED.tag),
                ASN1Integer(-1L, ASN1IntegerTag.ENUMERATED.tag),
                ASN1Integer(-255L, ASN1IntegerTag.ENUMERATED.tag),
                ASN1Integer(-256L, ASN1IntegerTag.ENUMERATED.tag),
            ))).trim()
        )
    }

    @Test
    fun testNull() {
        assertContentEquals("0500".fromHex(), ASN1.encode(ASN1Null()))

        assertEquals(ASN1Null(), ASN1.decode("0500".fromHex()))

        assertEquals(
            """
            SEQUENCE (2 elem)
              NULL
              NULL
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1Null(),
                ASN1Null(),
            ))).trim()
        )
    }

    @Test
    fun testSequence() {
        assertEquals(
            "3009020100020101020102",
            ASN1.encode(
                ASN1Sequence(
                    listOf(
                        ASN1Integer(0),
                        ASN1Integer(1),
                        ASN1Integer(2),
                    )
                )
            ).toHex()
        )

        assertEquals(
            ASN1Sequence(
                listOf(
                    ASN1Integer(0),
                    ASN1Integer(1),
                    ASN1Integer(2),
                )
            ),
            ASN1.decode("3009020100020101020102".fromHex())
        )

        assertEquals(
            """
            SEQUENCE (3 elem)
              INTEGER 0
              INTEGER 1
              INTEGER 2
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1Integer(0),
                ASN1Integer(1),
                ASN1Integer(2),
            ))).trim()
        )
    }

    @Test
    fun testSet() {
        assertEquals(
            "3109020100020101020102",
            ASN1.encode(
                ASN1Set(
                    listOf(
                        ASN1Integer(0),
                        ASN1Integer(1),
                        ASN1Integer(2),
                    )
                )
            ).toHex()
        )

        assertEquals(
            ASN1Set(
                listOf(
                    ASN1Integer(0),
                    ASN1Integer(1),
                    ASN1Integer(2),
                )
            ),
            ASN1.decode("3109020100020101020102".fromHex())
        )

        assertEquals(
            """
            SET (3 elem)
              INTEGER 0
              INTEGER 1
              INTEGER 2
            """.trimIndent(),
            ASN1.print(ASN1Set(listOf(
                ASN1Integer(0),
                ASN1Integer(1),
                ASN1Integer(2),
            ))).trim()
        )
    }

    @Test
    fun testObjectIdentifier() {
        assertEquals(
            "06082a8648ce3d040303",
            ASN1.encode(ASN1ObjectIdentifier("1.2.840.10045.4.3.3")).toHex()
        )
        assertEquals(
            "0603551d0e",
            ASN1.encode(ASN1ObjectIdentifier("2.5.29.14")).toHex()
        )
        assertEquals(
            "06052b81040022",
            ASN1.encode(ASN1ObjectIdentifier("1.3.132.0.34")).toHex()
        )

        assertEquals(
            ASN1ObjectIdentifier("1.2.840.10045.4.3.3"),
            ASN1.decode("06082a8648ce3d040303".fromHex())
        )
        assertEquals(
            ASN1ObjectIdentifier("2.5.29.14"),
            ASN1.decode("0603551d0e".fromHex())
        )
        assertEquals(
            ASN1ObjectIdentifier("1.3.132.0.34"),
            ASN1.decode("06052b81040022".fromHex())
        )

        assertEquals(
            """
            SEQUENCE (2 elem)
              OBJECT IDENTIFIER 2.5.29.14 subjectKeyIdentifier (X.509 extension)
              OBJECT IDENTIFIER 1.3.132.0.34 EC Curve P-384
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1ObjectIdentifier(OID.X509_EXTENSION_SUBJECT_KEY_IDENTIFIER.oid),
                ASN1ObjectIdentifier(OID.EC_CURVE_P384.oid)
            ))).trim()
        )
    }

    @Test
    fun testTime() {
        val pairs = mapOf<ASN1Object, ByteArray>(
            ASN1Time(
                LocalDateTime(1950, 2, 1, 0, 0, 0).toInstant(TimeZone.UTC),
                ASN1TimeTag.UTC_TIME.tag
            ) to "500201000000Z".encodeToByteArray().prependPrimitiveTLV(0x17),
            ASN1Time(
                LocalDateTime(1999, 12, 31, 10, 20, 30).toInstant(TimeZone.UTC),
                ASN1TimeTag.UTC_TIME.tag
            ) to "991231102030Z".encodeToByteArray().prependPrimitiveTLV(0x17),
            ASN1Time(
                LocalDateTime(2000, 1, 1, 0, 0, 0).toInstant(TimeZone.UTC),
                ASN1TimeTag.UTC_TIME.tag
            ) to "000101000000Z".encodeToByteArray().prependPrimitiveTLV(0x17),
            ASN1Time(
                LocalDateTime(2024, 12, 6, 9, 57, 10).toInstant(TimeZone.UTC),
                ASN1TimeTag.UTC_TIME.tag
            ) to "241206095710Z".encodeToByteArray().prependPrimitiveTLV(0x17),

            ASN1Time(
                LocalDateTime(2024, 1, 1, 0, 0, 0).toInstant(TimeZone.UTC),
                ASN1TimeTag.GENERALIZED_TIME.tag
            ) to "20240101000000Z".encodeToByteArray().prependPrimitiveTLV(0x18),
            ASN1Time(
                LocalDateTime(2024, 1, 1, 0, 0, 0,
                    0.5.seconds.inWholeNanoseconds.toInt()
                ).toInstant(TimeZone.UTC),
                ASN1TimeTag.GENERALIZED_TIME.tag
            ) to "20240101000000.5Z".encodeToByteArray().prependPrimitiveTLV(0x18),
            ASN1Time(
                LocalDateTime(2024, 1, 1, 0, 0, 0,
                    0.54321.seconds.inWholeNanoseconds.toInt()
                ).toInstant(TimeZone.UTC),
                ASN1TimeTag.GENERALIZED_TIME.tag
            ) to "20240101000000.54321Z".encodeToByteArray().prependPrimitiveTLV(0x18),
        )
        for ((obj, encoded) in pairs) {
            assertEquals(obj, ASN1.decode(encoded), "error decoding ${encoded.toHex()}")
            assertEquals(encoded.toHex(), ASN1.encode(obj).toHex(), "error encoding $obj (should be ${encoded.toHex()})")
        }

        assertEquals(
            """
            SEQUENCE (2 elem)
              GeneralizedTime 2024-01-01T00:00:00.500Z
              UTCTime 1999-12-31T10:20:30Z
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1Time(
                    LocalDateTime(2024, 1, 1, 0, 0, 0,
                        0.5.seconds.inWholeNanoseconds.toInt()
                    ).toInstant(TimeZone.UTC),
                    ASN1TimeTag.GENERALIZED_TIME.tag
                ),
                ASN1Time(
                    LocalDateTime(1999, 12, 31, 10, 20, 30).toInstant(TimeZone.UTC),
                    ASN1TimeTag.UTC_TIME.tag
                )
            ))).trim()
        )
    }

    @Test
    fun testStrings() {
        val pairs = mutableMapOf<ASN1Object, ByteArray>(
            ASN1String("foobar") to "0c06".fromHex() + "foobar".encodeToByteArray()
        )
        // Tests for all string tags
        for (tag in ASN1StringTag.entries) {
            pairs.put(
                ASN1String(value = "foo", tag = tag.tag),
                byteArrayOf(tag.tag.toByte(), 3) + "foo".encodeToByteArray()
            )
        }
        for ((obj, encoded) in pairs) {
            assertEquals(obj, ASN1.decode(encoded))
            assertEquals(encoded.toHex(), ASN1.encode(obj).toHex())
        }

        assertEquals(
            """
            SEQUENCE (12 elem)
              UTF8String String with tag value 12
              NumericString String with tag value 18
              PrintableString String with tag value 19
              TeletexString String with tag value 20
              VideotexString String with tag value 21
              IA5String String with tag value 22
              GraphicString String with tag value 25
              VisibleString String with tag value 26
              GeneralString String with tag value 27
              UniversalString String with tag value 28
              CharacterString String with tag value 29
              BmpString String with tag value 30
            """.trimIndent(),
            ASN1.print(ASN1Sequence(
                ASN1StringTag.entries.map { ASN1String("String with tag value ${it.tag}", it.tag) }
            )).trim()
        )
    }

    @Test
    fun testBitString() {
        val pairs = mapOf<ASN1Object, Pair<ByteArray, String>>(
            ASN1BitString(numUnusedBits = 0, value = "4401".fromHex()) to
                    Pair("0303004401".fromHex(), "0100010000000001"),

            ASN1BitString(numUnusedBits = 2, value = "44f8".fromHex()) to
                    Pair("03030244f8".fromHex(), "01000100111110")
        )
        for ((obj, encodedAndTextual) in pairs) {
            assertEquals(obj, ASN1.decode(encodedAndTextual.first))
            assertEquals(encodedAndTextual.first.toHex(), ASN1.encode(obj).toHex())
            assertEquals(encodedAndTextual.second, (obj as ASN1BitString).renderBitString())
        }

        assertEquals(
            """
            SEQUENCE (6 elem)
              BIT STRING (3 bit) 111
              BIT STRING (5 bit) 11000
              BIT STRING (5 bit) 11111
              BIT STRING (8 bit) 11111111
              BIT STRING (16 bit) 1111111100000000
              BIT STRING (17 bit) 11111111000000001
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1BitString(5, "e0".fromHex()),
                ASN1BitString(3, "c0".fromHex()),
                ASN1BitString(3, "f8".fromHex()),
                ASN1BitString(0, "ff".fromHex()),
                ASN1BitString(0, "ff00".fromHex()),
                ASN1BitString(7, "ff0080".fromHex()),
            ))).trim()
        )

        // to/from boolean arrays
        //
        assertEquals(
            "BIT STRING (1 bit) 0",
            ASN1.print(ASN1BitString(listOf(false).toBooleanArray())).trim()
        )
        assertEquals(
            "BIT STRING (1 bit) 1",
            ASN1.print(ASN1BitString(listOf(true).toBooleanArray())).trim()
        )
        assertEquals(
            "BIT STRING (2 bit) 01",
            ASN1.print(ASN1BitString(listOf(false, true).toBooleanArray())).trim()
        )
        assertEquals(
            "BIT STRING (7 bit) 1011010",
            ASN1.print(ASN1BitString(listOf(true, false, true, true, false, true, false).toBooleanArray())).trim()
        )

        assertContentEquals(
            listOf(false).toBooleanArray(),
            ASN1BitString(listOf(false).toBooleanArray()).asBooleans()
        )
        assertContentEquals(
            listOf(true).toBooleanArray(),
            ASN1BitString(listOf(true).toBooleanArray()).asBooleans()
        )
        assertContentEquals(
            listOf(false, true).toBooleanArray(),
            ASN1BitString(listOf(false, true).toBooleanArray()).asBooleans()
        )
        assertContentEquals(
            listOf(true, false, true, true, false, true, false, true, true).toBooleanArray(),
            ASN1BitString(listOf(true, false, true, true, false, true, false, true, true).toBooleanArray()).asBooleans()
        )
    }

    @Test
    fun testOctetString() {
        val pairs = mapOf<ASN1Object, ByteArray>(
            ASN1OctetString(value = "fffe".fromHex()) to "0402fffe".fromHex(),
            ASN1OctetString(value = "".fromHex()) to "0400".fromHex(),
        )
        for ((obj, encoded) in pairs) {
            assertEquals(obj, ASN1.decode(encoded))
            assertEquals(encoded.toHex(), ASN1.encode(obj).toHex())
        }

        assertEquals(
            """
                SEQUENCE (6 elem)
                  OCTET STRING (1 byte) e0 ("�")
                  OCTET STRING (1 byte) c0 ("�")
                  OCTET STRING (1 byte) f8 ("�")
                  OCTET STRING (1 byte) ff ("�")
                  OCTET STRING (2 byte) ff 00 ("�.")
                  OCTET STRING (3 byte) ff 00 80 ("�.�")
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1OctetString("e0".fromHex()),
                ASN1OctetString("c0".fromHex()),
                ASN1OctetString("f8".fromHex()),
                ASN1OctetString("ff".fromHex()),
                ASN1OctetString("ff00".fromHex()),
                ASN1OctetString("ff0080".fromHex()),
            ))).trim()
        )
    }

    @Test
    fun testUnsupportedTag() {
        // Checks that an unsupported tag appears as ASN1RawObject
        //
        val obj = ASN1Sequence(
            listOf(
                ASN1Integer(0),
                ASN1Integer(1),
                ASN1RawObject(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x09, byteArrayOf(16, 17)),
                ASN1Integer(2),
            )
        )
        val encoded = "300d02010002010109021011020102".fromHex()

        assertEquals(obj, ASN1.decode(encoded))
        assertEquals(encoded.toHex(), ASN1.encode(obj).toHex())

        assertEquals(
            """
                SEQUENCE (1 elem)
                  UNSUPPORTED TAG class=UNIVERSAL encoding=PRIMITIVE tag=9 value=10 11 ("..")
            """.trimIndent(),
            ASN1.print(ASN1Sequence(listOf(
                ASN1RawObject(ASN1TagClass.UNIVERSAL, ASN1Encoding.PRIMITIVE, 0x09, byteArrayOf(16, 17))
            ))).trim()
        )
    }


    // This is Maryland's IACA certificate (IACA_Root_2024.cer) downloaded from
    //
    //  https://mva.maryland.gov/Pages/MDMobileID_Googlewallet.aspx
    //
    private val exampleX509Cert = X509Cert.fromPem(
        """
-----BEGIN CERTIFICATE-----
MIICxjCCAmygAwIBAgITJkV7El8K11IXqY7mz96n/EhiITAKBggqhkjOPQQDAjBq
MQ4wDAYDVQQIEwVVUy1NRDELMAkGA1UEBhMCVVMxFDASBgNVBAcTC0dsZW4gQnVy
bmllMRUwEwYDVQQKEwxNYXJ5bGFuZCBNVkExHjAcBgNVBAMTFUZhc3QgRW50ZXJw
cmlzZXMgUm9vdDAeFw0yNDAxMDUwNTAwMDBaFw0yOTAxMDQwNTAwMDBaMGoxDjAM
BgNVBAgTBVVTLU1EMQswCQYDVQQGEwJVUzEUMBIGA1UEBxMLR2xlbiBCdXJuaWUx
FTATBgNVBAoTDE1hcnlsYW5kIE1WQTEeMBwGA1UEAxMVRmFzdCBFbnRlcnByaXNl
cyBSb290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaWcKIqlAWboV93RAa5ad
0LJBn8W0/yYwtOyUlxuTxoo4SPkorKmOz3EhThC+U4WRrt13aSnCsJtK+waBFghX
u6OB8DCB7TAOBgNVHQ8BAf8EBAMCAQYwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNV
HQ4EFgQUTprRzaFBJ1SLjJsO01tlLCQ4YF0wPAYDVR0SBDUwM4EWbXZhY3NAbWRv
dC5zdGF0ZS5tZC51c4YZaHR0cHM6Ly9tdmEubWFyeWxhbmQuZ292LzBYBgNVHR8E
UTBPME2gS6BJhkdodHRwczovL215bXZhLm1hcnlsYW5kLmdvdjo1NDQzL01EUC9X
ZWJTZXJ2aWNlcy9DUkwvbURML3Jldm9jYXRpb25zLmNybDAQBgkrBgEEAYPFIQEE
A01EUDAKBggqhkjOPQQDAgNIADBFAiEAnX3+E4E5dQ+5G1rmStJTW79ZAiDTabyL
8lJuYL/nDxMCIHHkAyIJcQlQmKDUVkBr3heUd5N9Y8GWdbWnbHuwe7Om
-----END CERTIFICATE-----
        """.trimIndent())

    @Test
    fun testCertificate() {
        // Check that we encode to exactly the same bits as we decoded...
        val certificate = ASN1.decode(exampleX509Cert.encoded)
        val reencoded = ASN1.encode(certificate!!)
        assertEquals(exampleX509Cert.encoded.toHex(), reencoded.toHex())
    }

    @Test
    fun testPrettyPrint() {
        val certificate = ASN1.decode(exampleX509Cert.encoded)
        assertEquals(
            """
                SEQUENCE (3 elem)
                  SEQUENCE (8 elem)
                    [0] (1 elem)
                      INTEGER 2
                    INTEGER 26 45 7b 12 5f 0a d7 52 17 a9 8e e6 cf de a7 fc 48 62 21
                    SEQUENCE (1 elem)
                      OBJECT IDENTIFIER 1.2.840.10045.4.3.2 ECDSA coupled with SHA-256
                    SEQUENCE (5 elem)
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.8 stateOrProvinceName (X.520 DN component)
                          PrintableString US-MD
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.6 countryName (X.520 DN component)
                          PrintableString US
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.7 localityName (X.520 DN component)
                          PrintableString Glen Burnie
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.10 organizationName (X.520 DN component)
                          PrintableString Maryland MVA
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.3 commonName (X.520 DN component)
                          PrintableString Fast Enterprises Root
                    SEQUENCE (2 elem)
                      UTCTime 2024-01-05T05:00:00Z
                      UTCTime 2029-01-04T05:00:00Z
                    SEQUENCE (5 elem)
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.8 stateOrProvinceName (X.520 DN component)
                          PrintableString US-MD
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.6 countryName (X.520 DN component)
                          PrintableString US
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.7 localityName (X.520 DN component)
                          PrintableString Glen Burnie
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.10 organizationName (X.520 DN component)
                          PrintableString Maryland MVA
                      SET (1 elem)
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.4.3 commonName (X.520 DN component)
                          PrintableString Fast Enterprises Root
                    SEQUENCE (2 elem)
                      SEQUENCE (2 elem)
                        OBJECT IDENTIFIER 1.2.840.10045.2.1 Elliptic curve public key cryptography
                        OBJECT IDENTIFIER 1.2.840.10045.3.1.7 NIST Curve P-256
                      BIT STRING (520 bit) 0000010001101001011001110000101000100010101010010100000001011001101110100001010111110111011101000100000001101011100101101001110111010000101100100100000110011111110001011011010011111111001001100011000010110100111011001001010010010111000110111001001111000110100010100011100001001000111110010010100010101100101010011000111011001111011100010010000101001110000100001011111001010011100001011001000110101110110111010111011101101001001010011100001010110000100110110100101011111011000001101000000100010110000010000101011110111011
                    [3] (1 elem)
                      SEQUENCE (6 elem)
                        SEQUENCE (3 elem)
                          OBJECT IDENTIFIER 2.5.29.15 keyUsage (X.509 extension)
                          BOOLEAN true
                          OCTET STRING (4 byte) 03 02 01 06 ("....")
                        SEQUENCE (3 elem)
                          OBJECT IDENTIFIER 2.5.29.19 basicConstraints (X.509 extension)
                          BOOLEAN true
                          OCTET STRING (8 byte) 30 06 01 01 ff 02 01 00 ("0...�...")
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.29.14 subjectKeyIdentifier (X.509 extension)
                          OCTET STRING (22 byte) 04 14 4e 9a d1 cd a1 41 27 54 8b 8c 9b 0e d3 5b 65 2c 24 38 60 5d ("..N��͡A'T���.�[e,${'$'}8`]")
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.29.18 issuerAltName (X.509 extension)
                          OCTET STRING (53 byte) 30 33 81 16 6d 76 61 63 73 40 6d 64 6f 74 2e 73 74 61 74 65 2e 6d 64 2e 75 73 86 19 68 74 74 70 73 3a 2f 2f 6d 76 61 2e 6d 61 72 79 6c 61 6e 64 2e 67 6f 76 2f ("03�.mvacs@mdot.state.md.us�.https://mva.maryland.gov/")
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 2.5.29.31 cRLDistributionPoints (X.509 extension)
                          OCTET STRING (81 byte) 30 4f 30 4d a0 4b a0 49 86 47 68 74 74 70 73 3a 2f 2f 6d 79 6d 76 61 2e 6d 61 72 79 6c 61 6e 64 2e 67 6f 76 3a 35 34 34 33 2f 4d 44 50 2f 57 65 62 53 65 72 76 69 63 65 73 2f 43 52 4c 2f 6d 44 4c 2f 72 65 76 6f 63 61 74 69 6f 6e 73 2e 63 72 6c ("0O0M�K�I�Ghttps://mymva.maryland.gov:5443/MDP/WebServices/CRL/mDL/revocations.crl")
                        SEQUENCE (2 elem)
                          OBJECT IDENTIFIER 1.3.6.1.4.1.58017.1
                          OCTET STRING (3 byte) 4d 44 50 ("MDP")
                  SEQUENCE (1 elem)
                    OBJECT IDENTIFIER 1.2.840.10045.4.3.2 ECDSA coupled with SHA-256
                  BIT STRING (568 bit) 0011000001000101000000100010000100000000100111010111110111111110000100111000000100111001011101010000111110111001000110110101101011100110010010101101001001010011010110111011111101011001000000100010000011010011011010011011110010001011111100100101001001101110011000001011111111100111000011110001001100000010001000000111000111100100000000110010001000001001011100010000100101010000100110001010000011010100010101100100000001101011110111100001011110010100011101111001001101111101011000111100000110010110011101011011010110100111011011000111101110110000011110111011001110100110
            """.trimIndent(),
            ASN1.print(certificate!!).trim()
        )
    }
}

private fun ByteArray.prependPrimitiveTLV(tagNum: Int): ByteArray {
    val bsb = ByteStringBuilder()
    ASN1.appendUniversalTagEncodingLength(bsb, tagNum, ASN1Encoding.PRIMITIVE, this.size)
    bsb.append(this)
    return bsb.toByteString().toByteArray()
}

