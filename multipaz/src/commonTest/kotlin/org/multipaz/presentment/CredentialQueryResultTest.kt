package org.multipaz.presentment

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.request.TransactionsInfo
import org.multipaz.mdoc.request.buildDeviceRequestFromDcql
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.utopia.knowntypes.PingTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CredentialQueryResultTest {

    suspend fun addMdl_with_AgeOver_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "unknown_data_element" to Tstr("Something"),
                    "given_name" to Tstr("David"),
                    "age_over_18" to true.toDataItem(),
                    "age_in_years" to 48.toDataItem(),
                    "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate(),
                    "portrait" to byteArrayOf(1, 2, 3).toDataItem()
                )
            )
        )
    }

    suspend fun addPhotoID_with_AgeOver_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-photoID",
            docType = PhotoID.PHOTO_ID_DOCTYPE,
            data = mapOf(
                PhotoID.ISO_23220_2_NAMESPACE to listOf(
                    "unknown_data_element" to Tstr("Something"),
                    "given_name" to Tstr("David"),
                    "age_over_18" to true.toDataItem(),
                    "age_in_years" to 48.toDataItem(),
                    "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate(),
                    "portrait" to byteArrayOf(1, 2, 3).toDataItem()
                )
            )
        )
    }

    private fun ageAndPortraitQuery(): DcqlQuery {
        return DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                        {
                          "credentials": [
                            {
                              "id": "mdl",
                              "format": "mso_mdoc",
                              "meta": {
                                "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                              },
                              "claims": [
                                {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"]},
                                {"id": "b", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"]},
                                {"id": "c", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_in_years"]},
                                {"id": "d", "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"]}
                              ],
                              "claim_sets": [
                                ["a", "b"],
                                ["a", "c"],
                                ["a", "d"]
                              ]
                            },
                            {
                              "id": "photoid",
                              "format": "mso_mdoc",
                              "meta": {
                                "doctype_value": "${PhotoID.PHOTO_ID_DOCTYPE}"
                              },
                              "claims": [
                                {"id": "a", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "portrait"]},
                                {"id": "b", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "age_over_18"]},
                                {"id": "c", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "age_in_years"]},
                                {"id": "d", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "birth_date"]}
                              ],
                              "claim_sets": [
                                ["a", "b"],
                                ["a", "c"],
                                ["a", "d"]
                              ]
                            }
                          ],
                          "credential_sets": [
                            {
                              "options": [
                                [ "mdl" ],
                                [ "photoid" ]
                              ]
                            }
                          ]
                        }
                    """
            ).jsonObject
        )
    }

    @Test
    fun testGetAllSelections() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_AgeOver_AgeInYears_BirthDate(harness)
        addPhotoID_with_AgeOver_AgeInYears_BirthDate(harness)

        val data = ageAndPortraitQuery().execute(presentmentSource = harness.presentmentSource)
        val selections = data.getAllSelections()
        assertEquals(
            """
                selections:
                  matches:
                    match:
                      credential:
                        type: MdocCredential
                        docId: my-mDL
                        claims:
                          claim:
                            nameSpace: org.iso.18013.5.1
                            dataElement: portrait
                            displayName: Photo of holder
                            value: Image (3 bytes)
                          claim:
                            nameSpace: org.iso.18013.5.1
                            dataElement: age_over_18
                            displayName: Older than 18 years
                            value: True
                  matches:
                    match:
                      credential:
                        type: MdocCredential
                        docId: my-photoID
                        claims:
                          claim:
                            nameSpace: org.iso.23220.1
                            dataElement: portrait
                            displayName: Photo of holder
                            value: Image (3 bytes)
                          claim:
                            nameSpace: org.iso.23220.1
                            dataElement: age_over_18
                            displayName: Older than 18 years
                            value: True
            """.trimIndent() + "\n",
            selections.prettyPrint()
        )
    }

    @Test
    fun testTransactionApplicabilityFailure() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        // Provision mdoc without keyAuthorizedNamespaces
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedNamespaces = emptyList()
        )

        val pingTransactionData = PingTransaction.serializeIso18013Request(PingTransaction.Payload("hello", null))
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            dcqlString = """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                      },
                      "claims": [
                        {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            transactions = mapOf(
                "mdl" to TransactionsInfo(mapOf(PingTransaction.iso18013RequestInfoIdentifier to pingTransactionData))
            )
        )

        val exception = assertFailsWith(Iso18015ResponseException::class) {
            deviceRequest.execute(presentmentSource = harness.presentmentSource)
        }
        assertEquals(
            "No credentials match required UseCase: transaction org.multipaz.transaction.ping is not applicable",
            exception.message
        )
    }

    @Test
    fun testTransactionApplicabilitySuccess() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        // Provision mdoc with PingTransaction authorized
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedNamespaces = listOf(ISO_18013_TRANSACTION_DATA_NAMESPACE)
        )

        val pingTransactionData = PingTransaction.serializeIso18013Request(PingTransaction.Payload("hello", null))
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            dcqlString = """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                      },
                      "claims": [
                        {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            transactions = mapOf(
                "mdl" to TransactionsInfo(mapOf(PingTransaction.iso18013RequestInfoIdentifier to pingTransactionData))
            )
        )

        val result = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        assertEquals(1, result.credentialSets.size)
        assertEquals(1, result.credentialSets[0].options.size)
        assertEquals(1, result.credentialSets[0].options[0].members.size)
        val member = result.credentialSets[0].options[0].members[0]
        assertEquals(1, member.matches.size)
        assertEquals(1, member.matches[0].transactionData.size)
        assertEquals(PingTransaction, member.matches[0].transactionData[0].type)
    }

    private object DummyPaymentTransaction: TransactionType<String>(
        displayName = "Payment",
        identifier = "payment_transaction",
    ) {
        override fun serializeOpenId4VpRequest(payload: String, credentialIds: List<String>, hashAlgorithms: List<Algorithm>?): String = ""
        override fun serializeIso18013Request(payload: String): DataItem = Tstr(payload)
        override fun parseOpenId4VpRequest(jsonString: String): String = throw NotImplementedError()
        override fun parseIso18013Request(dataItem: DataItem): String = dataItem.asTstr
    }

    @Test
    fun testTransactionApplicabilityFailurePaymentTransactionIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addTransactionType(DummyPaymentTransaction)
        // Provision mdoc without keyAuthorizedNamespaces
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedNamespaces = emptyList()
        )

        val transactionData = DummyPaymentTransaction.serializeIso18013Request("hello")
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            dcqlString = """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                      },
                      "claims": [
                        {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            transactions = mapOf(
                "mdl" to TransactionsInfo(mapOf(DummyPaymentTransaction.iso18013RequestInfoIdentifier to transactionData))
            )
        )

        val exception = assertFailsWith(Iso18015ResponseException::class) {
            deviceRequest.execute(presentmentSource = harness.presentmentSource)
        }
        assertEquals(
            "No credentials match required UseCase: transaction payment_transaction is not applicable",
            exception.message
        )
    }

    @Test
    fun testPaymentTransactionApplicabilityFailure() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addDocumentType(DigitalPaymentCredential.getDocumentType())
        harness.documentTypeRepository.addTransactionType(PaymentTransaction)

        // Provision payment card mdoc without keyAuthorizedNamespaces
        harness.provisionMdoc(
            displayName = "Erika's Payment Card Credential",
            docType = DigitalPaymentCredential.CARD_DOCTYPE,
            data = mapOf(
                DigitalPaymentCredential.CARD_NAMESPACE to listOf(
                    "card_number" to Tstr("1234567812345678")
                )
            ),
            keyAuthorizedNamespaces = emptyList()
        )

        val paymentTransactionData = PaymentTransaction.serializeIso18013Request(
            PaymentTransaction.sampleData.payload
        )
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            dcqlString = """
                {
                  "credentials": [
                    {
                      "id": "card",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DigitalPaymentCredential.CARD_DOCTYPE}"
                      },
                      "claims": [
                        {"id": "a", "path": ["${DigitalPaymentCredential.CARD_NAMESPACE}", "card_number"]}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            transactions = mapOf(
                "card" to TransactionsInfo(mapOf(PaymentTransaction.iso18013RequestInfoIdentifier to paymentTransactionData))
            )
        )

        val exception = assertFailsWith(Iso18015ResponseException::class) {
            deviceRequest.execute(presentmentSource = harness.presentmentSource)
        }
        assertEquals(
            "No credentials match required UseCase: transaction urn:eudi:sca:payment:1 is not applicable",
            exception.message
        )
    }

    @Test
    fun testPaymentTransactionApplicabilitySuccess() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addDocumentType(DigitalPaymentCredential.getDocumentType())
        harness.documentTypeRepository.addTransactionType(PaymentTransaction)

        harness.provisionMdoc(
            displayName = "Erika's Payment Card Credential",
            docType = DigitalPaymentCredential.CARD_DOCTYPE,
            data = mapOf(
                DigitalPaymentCredential.CARD_NAMESPACE to listOf(
                    "card_number" to Tstr("1234567812345678")
                )
            ),
            keyAuthorizedNamespaces = listOf(ISO_18013_TRANSACTION_DATA_NAMESPACE)
        )

        val paymentTransactionData = PaymentTransaction.serializeIso18013Request(
            PaymentTransaction.sampleData.payload
        )
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            dcqlString = """
                {
                  "credentials": [
                    {
                      "id": "card",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DigitalPaymentCredential.CARD_DOCTYPE}"
                      },
                      "claims": [
                        {"id": "a", "path": ["${DigitalPaymentCredential.CARD_NAMESPACE}", "card_number"]}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            transactions = mapOf(
                "card" to TransactionsInfo(mapOf(PaymentTransaction.iso18013RequestInfoIdentifier to paymentTransactionData))
            )
        )

        val result = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        assertEquals(1, result.credentialSets.size)
        val member = result.credentialSets[0].options[0].members[0]
        assertEquals(1, member.matches.size)
        assertEquals(1, member.matches[0].transactionData.size)
        assertEquals(PaymentTransaction, member.matches[0].transactionData[0].type)
    }
}