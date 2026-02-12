package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals

// This checks matching against booleans and numbers, see TestValueMatchingClaimSet which matches against booleans
class TestValueMatching {

    companion object {

        private suspend fun addMdl_sex_male(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Max-sex",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Max"),
                        "family_name" to Tstr("Mustermann"),
                        "sex" to Uint(1UL)
                    )
                )
            )
        }

        private suspend fun addMdl_sex_female(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Erika-sex",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "sex" to Uint(2UL)
                    )
                )
            )
        }

        private suspend fun addMdl_PostalCode90210_CountryUS(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Erika",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_postal_code" to Tstr("90210"),
                        "resident_country" to Tstr("US"),
                    )
                )
            )
        }

        private suspend fun addMdl_PostalCode94043_CountryUS(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Max",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Max"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_postal_code" to Tstr("94043"),
                        "resident_country" to Tstr("US"),
                    )
                )
            )
        }

        private suspend fun addMdl_PostalCode90210_CountryDE(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-OG",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("OG"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_postal_code" to Tstr("90210"),
                        "resident_country" to Tstr("DE"),
                    )
                )
            )
        }

        private fun mdl_match_sexMale(): DcqlQuery {
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
                                {"path": ["org.iso.18013.5.1", "given_name"]},
                                {"path": ["org.iso.18013.5.1", "family_name"]},
                                {
                                  "path": ["org.iso.18013.5.1", "sex"],
                                  "values": [1]
                                }
                              ]
                            }
                          ]
                        }
                    """
                ).jsonObject
            )
        }

        private fun mdl_match_sexFemale(): DcqlQuery {
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
                                {"path": ["org.iso.18013.5.1", "given_name"]},
                                {"path": ["org.iso.18013.5.1", "family_name"]},
                                {
                                  "path": ["org.iso.18013.5.1", "sex"],
                                  "values": [2]
                                }
                              ]
                            }
                          ]
                        }
                    """
                ).jsonObject
            )
        }

        private fun mdl_match_sexMaleOrFemale(): DcqlQuery {
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
                                {"path": ["org.iso.18013.5.1", "given_name"]},
                                {"path": ["org.iso.18013.5.1", "family_name"]},
                                {
                                  "path": ["org.iso.18013.5.1", "sex"],
                                  "values": [1, 2]
                                }
                              ]
                            }
                          ]
                        }
                    """
                ).jsonObject
            )
        }

        private fun mdl_match_PostalCode90210(): DcqlQuery {
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
                                {"path": ["org.iso.18013.5.1", "given_name"]},
                                {"path": ["org.iso.18013.5.1", "family_name"]},
                                {
                                  "path": ["org.iso.18013.5.1", "resident_postal_code"],
                                  "values": ["90210"]
                                }
                              ]
                            }
                          ]
                        }
                    """
                ).jsonObject
            )
        }

        private fun mdl_match_PostalCode90210And94043_CountryUS(): DcqlQuery {
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
                                {"path": ["org.iso.18013.5.1", "given_name"]},
                                {"path": ["org.iso.18013.5.1", "family_name"]},
                                {
                                  "path": ["org.iso.18013.5.1", "resident_postal_code"],
                                  "values": ["90210", "94043"]
                                },
                                {
                                  "path": ["org.iso.18013.5.1", "resident_country"],
                                  "values": ["US"]
                                }
                              ]
                            }
                          ]
                        }
                    """
                ).jsonObject
            )
        }

        private fun mdl_match_PostalCode90210_CountryUS(): DcqlQuery {
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
                                {"path": ["org.iso.18013.5.1", "given_name"]},
                                {"path": ["org.iso.18013.5.1", "family_name"]},
                                {
                                  "path": ["org.iso.18013.5.1", "resident_postal_code"],
                                  "values": ["90210"]
                                },
                                {
                                  "path": ["org.iso.18013.5.1", "resident_country"],
                                  "values": ["US"]
                                }
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
    fun matchFemale() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_sex_female(harness)
        addMdl_sex_male(harness)
        addMdl_PostalCode90210_CountryUS(harness)
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
                                  docId: my-mDL-Erika-sex
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: sex
                                      displayName: Sex
                                      value: Female
            """.trimIndent().trim(),
            mdl_match_sexFemale().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchMale() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_sex_female(harness)
        addMdl_sex_male(harness)
        addMdl_PostalCode90210_CountryUS(harness)
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
                                  docId: my-mDL-Max-sex
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Max
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: sex
                                      displayName: Sex
                                      value: Male
            """.trimIndent().trim(),
            mdl_match_sexMale().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchMaleOrFemale() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_sex_female(harness)
        addMdl_sex_male(harness)
        addMdl_PostalCode90210_CountryUS(harness)
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
                                  docId: my-mDL-Erika-sex
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: sex
                                      displayName: Sex
                                      value: Female
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-Max-sex
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Max
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: sex
                                      displayName: Sex
                                      value: Male
            """.trimIndent().trim(),
            mdl_match_sexMaleOrFemale().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchPostalCode90210() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_sex_female(harness)
        addMdl_sex_male(harness)
        addMdl_PostalCode94043_CountryUS(harness)
        addMdl_PostalCode90210_CountryUS(harness)
        addMdl_PostalCode90210_CountryDE(harness)
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
                                  docId: my-mDL-Erika
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_postal_code
                                      displayName: Resident Postal Code
                                      value: 90210
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-OG
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: OG
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_postal_code
                                      displayName: Resident Postal Code
                                      value: 90210
            """.trimIndent().trim(),
            mdl_match_PostalCode90210().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchPostalCode90210And94043CountryUS() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_sex_female(harness)
        addMdl_sex_male(harness)
        addMdl_PostalCode94043_CountryUS(harness)
        addMdl_PostalCode90210_CountryUS(harness)
        addMdl_PostalCode90210_CountryDE(harness)
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
                                  docId: my-mDL-Erika
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_postal_code
                                      displayName: Resident Postal Code
                                      value: 90210
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_country
                                      displayName: Resident Country
                                      value: United States of America
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-Max
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Max
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_postal_code
                                      displayName: Resident Postal Code
                                      value: 94043
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_country
                                      displayName: Resident Country
                                      value: United States of America
            """.trimIndent().trim(),
            mdl_match_PostalCode90210And94043_CountryUS().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchPostalCode90210CountryUS() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_sex_female(harness)
        addMdl_sex_male(harness)
        addMdl_PostalCode94043_CountryUS(harness)
        addMdl_PostalCode90210_CountryUS(harness)
        addMdl_PostalCode90210_CountryDE(harness)
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
                                  docId: my-mDL-Erika
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_postal_code
                                      displayName: Resident Postal Code
                                      value: 90210
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_country
                                      displayName: Resident Country
                                      value: United States of America
            """.trimIndent().trim(),
            mdl_match_PostalCode90210_CountryUS().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }
}
