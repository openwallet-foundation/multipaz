package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestMdlAndPid {
    companion object {
        private suspend fun addPidErika(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-PID-Erika",
                vct = "https://credentials.example.com/identity_credential",
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("country", JsonPrimitive("US"))
                        put("state", JsonPrimitive("CA"))
                        put("postal_code", JsonPrimitive("90210"))
                        put("street_address", JsonPrimitive("Sample Street 123"))
                    }
                )
            )
        }

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

        private fun mdlAndPidQuery(): DcqlQuery {
            return DcqlQuery.fromJson(
                Json.parseToJsonElement(
                    """
                        {
                          "credentials": [
                            {
                              "id": "my_mdl",
                              "format": "mso_mdoc",
                              "meta": {
                                "doctype_value": "org.iso.18013.5.1.mDL"
                              },
                              "claims": [
                                {"path": ["org.iso.18013.5.1", "given_name"]},
                                {"path": ["org.iso.18013.5.1", "resident_address"]}
                              ]
                            },
                            {
                              "id": "my_pid",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["https://credentials.example.com/identity_credential"]
                              },
                              "claims": [
                                {"path": ["given_name"]},
                                {"path": ["address", "street_address"]}
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
    fun requestMdlAndPid_HaveNone() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            mdlAndPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        }
        assertEquals("No matches for credential query with id my_mdl", e.message)
    }

    @Test
    fun requestMdlAndPid_HaveMdl() = runTest {
        val harness = DocumentStoreTestHarness()
        addMdlErika(harness)
        harness.initialize()
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            mdlAndPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        }
        assertEquals("No matches for credential query with id my_pid", e.message)
    }

    @Test
    fun requestMdlAndPid_HavePid() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErika(harness)
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            mdlAndPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        }
        assertEquals("No matches for credential query with id my_mdl", e.message)
    }

    @Test
    fun requestMdlAndPid_HaveBoth() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErika(harness)
        addPidErika(harness)
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
                                      dataElement: resident_address
                                      displayName: Resident Address
                                      value: Sample Street 123
                  credentialSet:
                    optional: false
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-PID-Erika
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            mdlAndPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }
}
