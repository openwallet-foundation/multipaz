package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestSingleMdlQuery {

    companion object {
        private suspend fun addMdlErika(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Erika",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_address" to Tstr("Sample Street 123"),
                    )
                )
            )
        }

        private suspend fun addMdlMax(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Max",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Max"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_address" to Tstr("Sample Street 456"),
                    )
                )
            )
        }

        private suspend fun addMdlErikaNoResidentAddress(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-without-resident-address",
                docType = "org.iso.18013.5.1.mDL",
                data = mapOf(
                    "org.iso.18013.5.1" to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                    )
                )
            )
        }

        private suspend fun addPidMdoc(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-PID-mdoc",
                docType = "eu.europa.ec.eudi.pid.1",
                data = mapOf(
                    "eu.europa.ec.eudi.pid.1" to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_address" to Tstr("Sample Street 123"),
                    )
                )
            )
        }

        private fun singleMdlQuery(): DcqlQuery {
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
                                {"path": ["org.iso.18013.5.1", "resident_address"]}
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
    fun singleMdlQueryNoCredentials() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidMdoc(harness)
        // Fails if we have no credentials
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            singleMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matches for credential query with id my_credential", e.message)
    }

    @Test
    fun singleMdlQueryNoCredentialsWithDoctype() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidMdoc(harness)
        // Fails if we have no credentials with the right docType
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            singleMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matches for credential query with id my_credential", e.message)
    }

    @Test
    fun singleMdlQueryMatchSingleCredential() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErika(harness)
        // Checks we get one match with one matching credential
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
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            singleMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singleMdlQueryMatchTwoCredentials() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErika(harness)
        addMdlMax(harness)
        // Checks we get two matches with two matching credentials
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
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-Max
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Max
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 456
            """.trimIndent().trim(),
            singleMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singleMdlQueryRequireAllClaimsToBePresent() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErika(harness)
        addMdlErikaNoResidentAddress(harness)
        // Checks we get one match with one matching credential if the other mDL lacks the resident_address claim
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
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            singleMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }
}