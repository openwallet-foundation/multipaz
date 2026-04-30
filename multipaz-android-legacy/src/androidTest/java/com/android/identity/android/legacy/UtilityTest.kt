package com.android.identity.android.legacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.mdoc.issuersigned.IssuerSignedItem
import org.multipaz.mdoc.mso.StaticAuthDataParser
import org.multipaz.util.Logger
import kotlin.collections.listOf

@RunWith(AndroidJUnit4::class)
class UtilityTest {

    @Test
    fun testMergeIssuerSignedPreservesOrder() {
        val item1 = buildCborMap {
            put("digestID", 42)
            put("random", byteArrayOf(1, 2, 3, 4))
            put("elementIdentifier", "dataElement1")
            put("elementValue", Tstr("Value1"))
        }
        val item2 = buildCborMap {
            put("random", byteArrayOf(1, 2, 3, 4, 5))
            put("digestID", 43)
            put("elementIdentifier", "dataElement2")
            put("elementValue", Tstr("Value2"))
        }

        val issuerSignedData = buildCborMap {
            put("issuerAuth", byteArrayOf(1, 2, 3))  // IssuerAuth not used in this test
            putCborMap("nameSpaces") {
                putCborArray("org.example.namespace") {
                    add(Tagged(tagNumber = Tagged.ENCODED_CBOR, taggedItem = Bstr(Cbor.encode(item1))))
                    add(Tagged(tagNumber = Tagged.ENCODED_CBOR, taggedItem = Bstr(Cbor.encode(item2))))
                }
            }
        }

        val issuerSignedMapping = StaticAuthDataParser(Cbor.encode(issuerSignedData)).parse().digestIdMapping

        val issuerSignedResult = SimpleResultData.Builder()
            .addEntry("org.example.namespace", "dataElement1", Cbor.encode(Tstr("Value1x")))
            .addEntry("org.example.namespace", "dataElement2", Cbor.encode(Tstr("Value2x")))
            .build()
        val deviceSignedResult = SimpleResultData.Builder()
            .build()
        val issuerSigned = SimpleCredentialDataResult(deviceSignedResult, issuerSignedResult)

        val issuerSignedMappingWithData = Utility.mergeIssuerSigned(
            issuerSignedMapping,
            issuerSigned.issuerSignedEntries
        )

        val diagOpts = setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
        val ns = issuerSignedMappingWithData["org.example.namespace"]!!

        val rewrittenItem1 = ns.find {
            Logger.dCbor("TAG", "item", it)
            val item = IssuerSignedItem(Cbor.decode(it).asTaggedEncodedCbor)
            item.dataElementIdentifier == "dataElement1"
        }!!
        assertEquals(
            """
            24(<< {
              "digestID": 42,
              "random": h'01020304',
              "elementIdentifier": "dataElement1",
              "elementValue": "Value1x"
            } >>)
            """.trimIndent(),
            Cbor.toDiagnostics(rewrittenItem1, diagOpts)
        )

        val rewrittenItem2 = ns.find {
            Logger.dCbor("TAG", "item", it)
            val item = IssuerSignedItem(Cbor.decode(it).asTaggedEncodedCbor)
            item.dataElementIdentifier == "dataElement2"
        }!!
        assertEquals(
            """
            24(<< {
              "random": h'0102030405',
              "digestID": 43,
              "elementIdentifier": "dataElement2",
              "elementValue": "Value2x"
            } >>)
            """.trimIndent(),
            Cbor.toDiagnostics(rewrittenItem2, diagOpts)
        )

    }
}