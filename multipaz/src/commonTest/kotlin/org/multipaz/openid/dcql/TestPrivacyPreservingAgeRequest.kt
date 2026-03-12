package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.datetime.formatLocalized
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestPrivacyPreservingAgeRequest {

    companion object {
        suspend fun addMdl_with_AgeOver_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
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
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
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
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("David"),
                        "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate()
                    )
                )
            )
        }

        suspend fun addMdl_with_OnlyName(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-only-name",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("David"),
                    )
                )
            )
        }

        private fun ageMdlQuery(): DcqlQuery {
            return DcqlQuery.fromJson(
                Json.parseToJsonElement(
                    """
                        {
                          "credentials": [
                            {
                              "id": "my_credential",
                              "format": "mso_mdoc",
                              "meta": {
                                "doctype_value": "org.iso.18013.5.1.mDL"
                              },
                              "claims": [
                                {"id": "a", "path": ["org.iso.18013.5.1", "given_name"]},
                                {"id": "b", "path": ["org.iso.18013.5.1", "age_over_18"]},
                                {"id": "c", "path": ["org.iso.18013.5.1", "age_in_years"]},
                                {"id": "d", "path": ["org.iso.18013.5.1", "birth_date"]}
                              ],
                              "claim_sets": [
                                ["a", "b"],
                                ["a", "c"],
                                ["a", "d"]
                              ]
                            }
                          ]
                        }
                    """
                ).jsonObject
            )
        }
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
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given names
                                      value: David
                                    claim:
                                      nameSpace: org.iso.18013.5.1
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
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given names
                                      value: David
                                    claim:
                                      nameSpace: org.iso.18013.5.1
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
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given names
                                      value: David
                                    claim:
                                      nameSpace: org.iso.18013.5.1
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
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            ageMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matches for credential query with id my_credential", e.message)
    }

}
