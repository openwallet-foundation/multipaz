package org.multipaz.mdoc.devicesigned

import kotlinx.datetime.LocalDate
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.toDataItemFullDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceNamespacesTest {
    @Test
    fun testBuilder() {
        val deviceNamespaces = buildDeviceNamespaces {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("given_name", Tstr("Erika"))
                addDataElement("family_name", Tstr("Mustermann"))
                addDataElement("portrait", Bstr(byteArrayOf(1, 2, 3)))
                addDataElement("issue_date", LocalDate.parse("2025-02-20").toDataItemFullDate())
            }
            addNamespace("org.iso.18013.5.1.aamva") {
                addDataElement("organ_donor", Uint(1UL))
                addDataElement("DHS_compliance", Tstr("F"))
            }
        }
        assertEquals(
            """
                org.iso.18013.5.1:
                  given_name: "Erika"
                  family_name: "Mustermann"
                  portrait: h'010203'
                  issue_date: 1004("2025-02-20")
                org.iso.18013.5.1.aamva:
                  organ_donor: 1
                  DHS_compliance: "F"
                """.trimIndent().trim(),
            deviceNamespaces.prettyPrint().trim()
        )

        val dataItem = deviceNamespaces.toDataItem()
        assertEquals(
            """
                {
                  "org.iso.18013.5.1": {
                    "given_name": "Erika",
                    "family_name": "Mustermann",
                    "portrait": h'010203',
                    "issue_date": 1004("2025-02-20")
                  },
                  "org.iso.18013.5.1.aamva": {
                    "organ_donor": 1,
                    "DHS_compliance": "F"
                  }
                }
            """.trimIndent(),
            Cbor.toDiagnostics(dataItem, setOf(DiagnosticOption.PRETTY_PRINT))
        )
        val parsedFromDataItem = DeviceNamespaces.fromDataItem(dataItem)
        assertEquals(deviceNamespaces, parsedFromDataItem)
    }

}

private fun DeviceNamespaces.prettyPrint(): String {
    val sb = StringBuilder()
    for ((namespaceName, innerMap) in data) {
        sb.append("$namespaceName:\n")
        for ((deName, deValue) in innerMap) {
            sb.append("  ${deName}: ${Cbor.toDiagnostics(deValue)}\n")
        }
    }
    return sb.toString()
}