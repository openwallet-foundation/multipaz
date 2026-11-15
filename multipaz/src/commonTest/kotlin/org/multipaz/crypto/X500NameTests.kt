package org.multipaz.crypto

import org.multipaz.asn1.ASN1String
import org.multipaz.asn1.ASN1StringTag
import org.multipaz.asn1.OID
import kotlin.test.Test
import kotlin.test.assertEquals

class X500NameTests {
    @Test
    fun testX500NameSimple() {
        assertEquals(
            "CN=Foo",
            X500Name.fromName("CN=Foo").name
        )
        assertEquals(
            mapOf(OID.COMMON_NAME.oid to ASN1String("Foo")),
            X500Name.fromName("CN=Foo").components
        )
    }

    @Test
    fun testX500NameComplicated() {
        val str = "CN=David,SN=12345,ST=US-MA,O=Google,OU=Android,C=US"
        assertEquals(str, X500Name.fromName(str).name)
        assertEquals(
            mapOf(
                OID.COUNTRY_NAME.oid to ASN1String("US", ASN1StringTag.PRINTABLE_STRING.tag),
                OID.ORGANIZATIONAL_UNIT_NAME.oid to ASN1String("Android"),
                OID.ORGANIZATION_NAME.oid to ASN1String("Google"),
                OID.STATE_OR_PROVINCE_NAME.oid to ASN1String("US-MA"),
                OID.SERIAL_NUMBER.oid to ASN1String("12345", ASN1StringTag.PRINTABLE_STRING.tag),
                OID.COMMON_NAME.oid to ASN1String("David"),
            ),
            X500Name.fromName(str).components
        )
        // assertEquals() on a map doesn't check the order... make sure that the parsed keys
        // are the reverse order of their appearance in the string as per RFC 2293 section 2.1
        assertEquals(
            listOf(
                OID.COUNTRY_NAME.oid,
                OID.ORGANIZATIONAL_UNIT_NAME.oid,
                OID.ORGANIZATION_NAME.oid,
                OID.STATE_OR_PROVINCE_NAME.oid,
                OID.SERIAL_NUMBER.oid,
                OID.COMMON_NAME.oid,
            ),
            X500Name.fromName(str).components.keys.toList()
        )
    }

    @Test
    fun testX500NameFromOID() {
        assertEquals(
            "CN=Foo",
            X500Name(mapOf(OID.COMMON_NAME.oid to ASN1String("Foo"))).name
        )
    }

    @Test
    fun testX500NameFromUnknownOID() {
        assertEquals(
            "2.5.4.999=#0c03466f6f",
            X500Name(mapOf("2.5.4.999" to ASN1String("Foo"))).name
        )
    }
}