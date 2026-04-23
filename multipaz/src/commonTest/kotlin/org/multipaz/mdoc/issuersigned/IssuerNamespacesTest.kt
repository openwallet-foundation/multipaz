package org.multipaz.mdoc.issuersigned

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.mdoc.TestVectors
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlinx.datetime.LocalDate
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IssuerNamespacesTest {

    @Test
    fun testParsingAndEncoding() {
        val encodedDeviceResponse = TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex()
        val deviceResponse = Cbor.decode(encodedDeviceResponse)
        val documents = deviceResponse["documents"][0]
        val issuerSigned = documents["issuerSigned"]
        val namespaces = issuerSigned["nameSpaces"]
        val parsed = IssuerNamespaces.fromDataItem(namespaces)
        assertEquals(
            """
                org.iso.18013.5.1:
                  family_name:
                    digestId: 0
                    random: 8798645b20ea200e19ffabac92624bee6aec63aceedecfb1b80077d22bfc20e9
                    value: "Doe"
                  issue_date:
                    digestId: 3
                    random: b23f627e8999c706df0c0a4ed98ad74af988af619b4bb078b89058553f44615d
                    value: 1004("2019-10-20")
                  expiry_date:
                    digestId: 4
                    random: c7ffa307e5de921e67ba5878094787e8807ac8e7b5b3932d2ce80f00f3e9abaf
                    value: 1004("2024-10-20")
                  document_number:
                    digestId: 7
                    random: 26052a42e5880557a806c1459af3fb7eb505d3781566329d0b604b845b5f9e68
                    value: "123456789"
                  portrait:
                    digestId: 8
                    random: d094dad764a2eb9deb5210e9d899643efbd1d069cc311d3295516ca0b024412d
                    value: h'ffd8ffe000104a46494600010101009000900000ffdb004300130d0e110e0c13110f11151413171d301f1d1a1a1d3a2a2c2330453d4947443d43414c566d5d4c51685241435f82606871757b7c7b4a5c869085778f6d787b76ffdb0043011415151d191d381f1f38764f434f7676767676767676767676767676767676767676767676767676767676767676767676767676767676767676767676767676ffc00011080018006403012200021101031101ffc4001b00000301000301000000000000000000000005060401020307ffc400321000010303030205020309000000000000010203040005110612211331141551617122410781a1163542527391b2c1f1ffc4001501010100000000000000000000000000000001ffc4001a110101010003010000000000000000000000014111213161ffda000c03010002110311003f00a5bbde22da2329c7d692bc7d0d03f52cfb0ff75e7a7ef3e7709723a1d0dae146ddfbb3c039ce07ad2bd47a7e32dbb8dd1d52d6ef4b284f64a480067dfb51f87ffb95ff00eb9ff14d215de66af089ce44b7dbde9cb6890a2838eddf18078f7add62d411ef4db9b10a65d6b95a147381ea0d495b933275fe6bba75c114104a8ba410413e983dff004f5af5d34b4b4cde632d0bf1fd1592bdd91c6411f3934c2fa6af6b54975d106dcf4a65ae56e856001ebc03c7ce29dd9eef1ef10fc447dc9da76ad2aee93537a1ba7e4f70dd8eff0057c6dffb5e1a19854a83758e54528750946ec6704850cd037bceb08b6d7d2cc76d3317fc7b5cc04fb6707269c5c6e0c5b60ae549242123b0e493f602a075559e359970d98db89525456b51c951c8afa13ea8e98e3c596836783d5c63f5a61a99fdb7290875db4be88ab384bbbbbfc7183fdeaa633e8951db7da396dc48524fb1a8bd611a5aa2a2432f30ab420a7a6d3240c718cf031fa9ef4c9ad550205aa02951df4a1d6c8421b015b769db8c9229837ea2be8b1b0d39d0eba9c51484efdb8c0efd8d258daf3c449699f2edbd4584e7af9c64e3f96b9beb28d4ac40931e6478c8e76a24a825449501d867d2b1dcdebae99b9c752ae4ecd6dde4a179c1c1e460938f9149ef655e515c03919a289cb3dca278fb7bf177f4faa829dd8ce3f2ac9a7ecde490971fafd7dce15eed9b71c018c64fa514514b24e8e4f8c5c9b75c1e82579dc1233dfec08238f6add62d391acc1c5256a79e706d52d431c7a0145140b9fd149eb3a60dc5e88cbbc2da092411e9dc71f39a7766b447b344e847dcac9dcb5abba8d145061d43a6fcf1e65cf15d0e90231d3dd9cfe62995c6dcc5ca12a2c904a15f71dd27d451453e09d1a21450961cbb3ea8a956433b781f1ce33dfed54f0e2b50a2b71d84ed6db18028a28175f74fc6bda105c529a791c25c4f3c7a11f71586268f4a66b726e33de9ea6f1b52b181c760724e47b514520a5a28a283ffd9'
                  driving_privileges:
                    digestId: 9
                    random: 4599f81beaa2b20bd0ffcc9aa03a6f985befab3f6beaffa41e6354cdb2ab2ce4
                    value: [{"vehicle_category_code": "A", "issue_date": 1004("2018-08-09"), "expiry_date": 1004("2024-10-20")}, {"vehicle_category_code": "B", "issue_date": 1004("2017-02-23"), "expiry_date": 1004("2024-10-20")}]
            """.trimIndent().trim(),
            parsed.prettyPrint().trim())
        assertEquals(
            Cbor.encode(parsed.toDataItem()).toHex(),
            Cbor.encode(namespaces).toHex()
        )
    }

    @Test
    fun testBuilder() {
        val issuerNamespaces = buildIssuerNamespaces(
            dataElementRandomSize = 16,
            randomProvider = Random(42)
        ) {
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
                  given_name:
                    digestId: 1
                    random: 20aabd26420972f8d80c48fa7ba9002f
                    value: "Erika"
                  family_name:
                    digestId: 4
                    random: 1902b697c6132dacdfbf5a4b1fb79dba
                    value: "Mustermann"
                  portrait:
                    digestId: 2
                    random: 743b5af1e1f2d3976124091db519e215
                    value: h'010203'
                  issue_date:
                    digestId: 3
                    random: 3266a9a296f81c7b45ffdecd0f720149
                    value: 1004("2025-02-20")
                org.iso.18013.5.1.aamva:
                  organ_donor:
                    digestId: 0
                    random: 5f23ec7b62554c512dc211b3c80d385d
                    value: 1
                  DHS_compliance:
                    digestId: 5
                    random: 50b49571122a92747e187ef4b18c4204
                    value: "F"
                """.trimIndent().trim(),
            issuerNamespaces.prettyPrint().trim()
        )
    }

    private fun checkOrder(issuerSignedItemCbor: DataItem) {
        val decodedItem = IssuerNamespaces.fromDataItem(issuerSignedItemCbor)
        assertEquals(issuerSignedItemCbor, decodedItem.toDataItem())
    }

    // This checks that IssuerSignedItem preserves the order
    @Test
    fun orderIsPreservedInIssuerSignedItem() {
        // The order as per the standard ...
        checkOrder(buildCborMap {
            putCborArray("ns1") {
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(
                        Cbor.encode(buildCborMap {
                        put("digestID", 1)
                        put("random", "1234".fromHex())
                        put("elementIdentifier", "age_over_21")
                        put("elementValue", Simple.TRUE)
                    })
                )))
            }
        })

        // ... different order than as in the standard
        checkOrder(buildCborMap {
            putCborArray("ns1") {
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(
                    Cbor.encode(buildCborMap {
                        put("random", "1234".fromHex())
                        put("digestID", 1)
                        put("elementIdentifier", "age_over_21")
                        put("elementValue", Simple.TRUE)
                    })
                )))
            }
        })

        // ... yet another different order than as in the standard
        checkOrder(buildCborMap {
            putCborArray("ns1") {
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(
                    Cbor.encode(buildCborMap {
                        put("elementValue", Simple.TRUE)
                        put("elementIdentifier", "age_over_21")
                        put("random", "1234".fromHex())
                        put("digestID", 1)
                    })
                )))
            }
        })
    }
}

private fun IssuerNamespaces.prettyPrint(): String {
    val sb = StringBuilder()
    for ((namespaceName, innerMap) in data) {
        sb.append("$namespaceName:\n")
        for ((_, issuerSignedItem) in innerMap) {
            sb.append("  ${issuerSignedItem.dataElementIdentifier}:\n")
            sb.append("    digestId: ${issuerSignedItem.digestId}\n")
            sb.append("    random: ${issuerSignedItem.random.toByteArray().toHex()}\n")
            sb.append("    value: ${Cbor.toDiagnostics(issuerSignedItem.dataElementValue)}\n")
        }
    }
    return sb.toString()
}