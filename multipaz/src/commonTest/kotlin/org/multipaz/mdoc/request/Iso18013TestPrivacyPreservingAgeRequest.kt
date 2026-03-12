package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.multipaz.cbor.buildCborArray
import org.multipaz.datetime.formatLocalized
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Iso18013TestPrivacyPreservingAgeRequest {

    companion object {
        suspend fun addMdl_with_AgeOver_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to Tstr("David"),
                        "age_over_18" to true.toDataItem(),
                        "age_in_years" to 48.toDataItem(),
                        "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate()
                    )
                )
            )
        }

        suspend fun addMdl_with_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-no-age-over",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to Tstr("David"),
                        "age_in_years" to 48.toDataItem(),
                        "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate()
                    )
                )
            )
        }

        suspend fun addMdl_with_BirthDate(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-only-birth-date",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to Tstr("David"),
                        "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate()
                    )
                )
            )
        }

        suspend fun addMdl_with_OnlyName(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-only-name",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to Tstr("David"),
                    )
                )
            )
        }

        private fun ageMdlQuery(): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = buildCborArray { add("doesn't"); add("matter") }
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf(
                            "given_name" to false,
                            "age_over_18" to false
                        )
                    ),
                    docRequestInfo = DocRequestInfo(
                        alternativeDataElements = listOf(AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                namespace = DrivingLicense.MDL_NAMESPACE,
                                dataElement = "age_over_18",
                            ),
                            alternativeElementSets = listOf(
                                listOf(ElementReference(
                                    namespace = DrivingLicense.MDL_NAMESPACE,
                                    dataElement = "age_in_years",
                                )),
                                listOf(ElementReference(
                                    namespace = DrivingLicense.MDL_NAMESPACE,
                                    dataElement = "birth_date",
                                ))
                            )
                        ))
                    )
                )
            }
        }
    }

    @Test
    fun testToDqclQuery() = runTest {
        @OptIn(ExperimentalSerializationApi::class)
        val prettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        assertEquals(
            """
                {
                  "credentials": [
                    {
                      "id": "cred0",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.iso.18013.5.1.mDL"
                      },
                      "claims": [
                        {
                          "id": "claim0",
                          "path": [
                            "org.iso.18013.5.1",
                            "given_name"
                          ],
                          "intent_to_retain": false
                        },
                        {
                          "id": "claim1",
                          "path": [
                            "org.iso.18013.5.1",
                            "age_over_18"
                          ],
                          "intent_to_retain": false
                        },
                        {
                          "id": "claim2",
                          "path": [
                            "org.iso.18013.5.1",
                            "age_in_years"
                          ],
                          "intent_to_retain": false
                        },
                        {
                          "id": "claim3",
                          "path": [
                            "org.iso.18013.5.1",
                            "birth_date"
                          ],
                          "intent_to_retain": false
                        }
                      ],
                      "claim_sets": [
                        [
                          "claim0",
                          "claim1"
                        ],
                        [
                          "claim0",
                          "claim2"
                        ],
                        [
                          "claim0",
                          "claim3"
                        ]
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            prettyJson.encodeToString(ageMdlQuery().toDcql())
        )
    }

    @Test
    fun mdlWithAgeOver() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_AgeOver_AgeInYears_BirthDate(harness)
        assertEquals(
            """
                credentialSets:
                  credentialSet:
                    optional: false
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: David
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: age_over_18
                                      displayName: Older than 18 years
                                      value: True
            """.trimIndent().trim(),
            ageMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun mdlWithAgeInYears() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_AgeInYears_BirthDate(harness)
        assertEquals(
            """
                credentialSets:
                  credentialSet:
                    optional: false
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-no-age-over
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: David
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: age_in_years
                                      displayName: Age in years
                                      value: 48
            """.trimIndent().trim(),
            ageMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun mdlWithBirthDate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_BirthDate(harness)
        val result = ageMdlQuery().execute(
            presentmentSource = harness.presentmentSource
        ).prettyPrint().trim()

        val dateValue = LocalDate.parse("1976-03-02").formatLocalized()

        assertEquals(
            """
                credentialSets:
                  credentialSet:
                    optional: false
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-only-birth-date
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: David
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: birth_date
                                      displayName: Date of birth
                                      value: $dateValue
            """.trimIndent().trim(),
            result
        )
    }

    @Test
    fun mdlWithNoAgeInfo() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_OnlyName(harness)
        val e = assertFailsWith(Iso18015ResponseException::class) {
            ageMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

}
