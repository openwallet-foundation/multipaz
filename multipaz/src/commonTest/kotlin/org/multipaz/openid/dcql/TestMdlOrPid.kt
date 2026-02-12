package org.multipaz.openid.dcql


import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestMdlOrPid {

    companion object {
        private suspend fun addMdlErika(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Erika",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_address" to Tstr("Sample Street 123"),
                    )
                )
            )
        }

        private suspend fun addPidErika(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-PID-Erika",
                vct = EUPersonalID.EUPID_VCT,
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

        private fun mdlOrPidQuery(): DcqlQuery {
            return DcqlQuery.fromJson(
                Json.parseToJsonElement(
                    """
                        {
                          "credentials": [
                            {
                              "id": "mdl",
                              "format": "mso_mdoc",
                              "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                              "claims": [
                                { "path": [ "org.iso.18013.5.1", "given_name" ] },
                                { "path": [ "org.iso.18013.5.1", "family_name" ] }
                              ]
                            },
                            {
                              "id": "pid",
                              "format": "dc+sd-jwt",
                              "meta": { "vct_values": [ "urn:eudi:pid:1" ] },
                              "claims": [
                                { "path": [ "family_name" ] },
                                { "path": [ "given_name" ] }
                              ]
                            }
                          ],
                          "credential_sets": [
                            {
                              "options": [
                                [ "mdl" ],
                                [ "pid" ]
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
    fun mdlOrPidNoCredentials() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        // Fails if we have no credentials
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            mdlOrPidQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No credentials match required credential_set query", e.message)
    }

    @Test
    fun mdlOrPidWithMdl() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErika(harness)

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
            """.trimIndent().trim(),
            mdlOrPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun mdlOrPidWithPid() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
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
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-PID-Erika
                                  claims:
                                    claim:
                                      path: ["family_name"]
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given Names
                                      value: Erika
            """.trimIndent().trim(),
            mdlOrPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun mdlOrPidWithMdlAndPid() = runTest {
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
                                      dataElement: family_name
                                      displayName: Family Name
                                      value: Mustermann
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
                                      path: ["family_name"]
                                      displayName: Family Name
                                      value: Mustermann
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given Names
                                      value: Erika
            """.trimIndent().trim(),
            mdlOrPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

}