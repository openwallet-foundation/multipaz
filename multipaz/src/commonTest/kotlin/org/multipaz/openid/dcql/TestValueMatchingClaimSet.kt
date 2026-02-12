package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// This matches against booleans, see TestValueMatching which matches against strings and numbers
class TestValueMatchingClaimSet {

    companion object {

        private suspend fun addMdl_US_organ_donor(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Erika-donor",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                    ),
                    "org.iso.18013.5.1.us" to listOf(
                        "organ_donor" to true.toDataItem(),
                    )
                )
            )
        }

        private suspend fun addMdl_US_not_organ_donor(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Erika-not-donor",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                    ),
                    "org.iso.18013.5.1.us" to listOf(
                        "organ_donor" to false.toDataItem(),
                    )
                )
            )
        }

        private suspend fun addMdl_EU_organ_donor(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Max-donor",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Max"),
                        "family_name" to Tstr("Mustermann"),
                    ),
                    "org.iso.18013.5.1.eu" to listOf(
                        "organ_donor" to true.toDataItem(),
                    )
                )
            )
        }

        private suspend fun addMdl_EU_not_organ_donor(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Max-not-donor",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Max"),
                        "family_name" to Tstr("Mustermann"),
                    ),
                    "org.iso.18013.5.1.eu" to listOf(
                        "organ_donor" to false.toDataItem(),
                    )
                )
            )
        }

        private fun mdl_match_USOrganDonor_or_EUOrganDonor(): DcqlQuery {
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
                                {"id": "b", "path": ["org.iso.18013.5.1", "family_name"]},
                                {
                                  "id": "c",
                                  "path": ["org.iso.18013.5.1.us", "organ_donor"],
                                  "values": [true]
                                },
                                {
                                  "id": "d",
                                  "path": ["org.iso.18013.5.1.eu", "organ_donor"],
                                  "values": [true]
                                }
                              ],
                              "claim_sets": [
                                ["a", "b", "c"],
                                ["a", "b", "d"]
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
    fun matchOrganDonorsEUorUS_with_US_and_EU_donors() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_US_organ_donor(harness)
        addMdl_EU_organ_donor(harness)
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
                                  docId: my-mDL-Erika-donor
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
                                      nameSpace: org.iso.18013.5.1.us
                                      dataElement: organ_donor
                                      displayName: organ_donor
                                      value: true
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-Max-donor
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
                                      nameSpace: org.iso.18013.5.1.eu
                                      dataElement: organ_donor
                                      displayName: organ_donor
                                      value: true
            """.trimIndent().trim(),
            mdl_match_USOrganDonor_or_EUOrganDonor().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchOrganDonorsEUorUS_with_US_donor() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_US_organ_donor(harness)
        addMdl_EU_not_organ_donor(harness)
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
                                  docId: my-mDL-Erika-donor
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
                                      nameSpace: org.iso.18013.5.1.us
                                      dataElement: organ_donor
                                      displayName: organ_donor
                                      value: true
            """.trimIndent().trim(),
            mdl_match_USOrganDonor_or_EUOrganDonor().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchOrganDonorsEUorUS_with_EU_donor() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_US_not_organ_donor(harness)
        addMdl_EU_organ_donor(harness)
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
                                  docId: my-mDL-Max-donor
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
                                      nameSpace: org.iso.18013.5.1.eu
                                      dataElement: organ_donor
                                      displayName: organ_donor
                                      value: true
            """.trimIndent().trim(),
            mdl_match_USOrganDonor_or_EUOrganDonor().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun matchOrganDonorsEUorUS_with_no_donors() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_US_not_organ_donor(harness)
        addMdl_EU_not_organ_donor(harness)
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            mdl_match_USOrganDonor_or_EUOrganDonor().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matches for credential query with id my_credential", e.message)
    }
}
