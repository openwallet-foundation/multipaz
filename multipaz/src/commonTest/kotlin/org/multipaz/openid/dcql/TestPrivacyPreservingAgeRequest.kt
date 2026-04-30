package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.datetime.formatLocalized
import org.multipaz.documenttype.DocumentAttributeSensitivity
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

        suspend fun addMdl_with_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-no-age-over",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "unknown_data_element" to Tstr("Something"),
                        "given_name" to Tstr("David"),
                        "age_in_years" to 48.toDataItem(),
                        "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate(),
                        "portrait" to byteArrayOf(1, 2, 3).toDataItem()
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
                        "unknown_data_element" to Tstr("Something"),
                        "given_name" to Tstr("David"),
                        "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate(),
                        "portrait" to byteArrayOf(1, 2, 3).toDataItem()
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

        private fun ageAndNameMdlQuery(): DcqlQuery {
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

    private fun ageAndPortraitMdlQuery(): DcqlQuery {
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
                                {"id": "a", "path": ["org.iso.18013.5.1", "portrait"]},
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
                                {"id": "b", "path": ["org.iso.18013.5.1", "age_over_18"]},
                                {"id": "c", "path": ["org.iso.18013.5.1", "age_in_years"]},
                                {"id": "d", "path": ["org.iso.18013.5.1", "birth_date"]}
                              ],
                              "claim_sets": [
                                ["b"],
                                ["c"],
                                ["d"]
                              ]
                            }
                          ]
                        }
                    """
            ).jsonObject
        )
    }

    private fun ageMdlAndUnknownDataElementQuery(): DcqlQuery {
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
                                {"id": "a", "path": ["org.iso.18013.5.1", "unknown_data_element"]},
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
            ageAndNameMdlQuery().execute(
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
            ageAndNameMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun mdlWithBirthDate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_BirthDate(harness)
        val result = ageAndNameMdlQuery().execute(
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
            ageAndNameMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matches for credential query with id my_credential", e.message)
    }

    @Test
    fun testGetMaxSensitivity_AgeInformation() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_AgeInYears_BirthDate(harness)

        val request = ageMdlQuery().execute(
            presentmentSource = harness.presentmentSource
        ).select(preselectedDocuments = emptyList())

        // This should return AGE_INFORMATION b/c age_in_years is classified as AGE_IN_YEARS
        assertEquals(DocumentAttributeSensitivity.AGE_INFORMATION, request.getMaxSensitivity())
    }

    @Test
    fun testGetMaxSensitivity_PortraitImage() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_AgeInYears_BirthDate(harness)

        val request = ageAndPortraitMdlQuery().execute(
            presentmentSource = harness.presentmentSource
        ).select(preselectedDocuments = emptyList())

        // This should return PORTRAIT_IMAGE b/c portrait is classified as PORTRAIT_IMAGE
        assertEquals(DocumentAttributeSensitivity.PORTRAIT_IMAGE, request.getMaxSensitivity())
    }

    @Test
    fun testGetMaxSensitivity_PII() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_BirthDate(harness)

        val request = ageMdlQuery().execute(
            presentmentSource = harness.presentmentSource
        ).select(preselectedDocuments = emptyList())

        // This should return PII b/c birth_date is classified as PII
        assertEquals(DocumentAttributeSensitivity.PII, request.getMaxSensitivity())
    }

    @Test
    fun testGetMaxSensitivity_Unknown() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_BirthDate(harness)

        val request = ageMdlAndUnknownDataElementQuery().execute(
            presentmentSource = harness.presentmentSource
        ).select(preselectedDocuments = emptyList())

        // This should return `null` b/c there is no DocumentAttribute for unknown_data_element and thus no sensitivity.
        assertEquals(null, request.getMaxSensitivity())
    }
}
