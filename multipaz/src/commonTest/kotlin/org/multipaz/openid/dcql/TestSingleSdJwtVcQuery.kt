package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestSingleSdJwtVcQuery {
    companion object {
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

        private suspend fun addPidMax(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-PID-Max",
                vct = EUPersonalID.EUPID_VCT,
                data = listOf(
                    "given_name" to JsonPrimitive("Max"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("country", JsonPrimitive("US"))
                        put("state", JsonPrimitive("CA"))
                        put("postal_code", JsonPrimitive("90210"))
                        put("street_address", JsonPrimitive("Sample Street 456"))
                    }
                )
            )
        }

        private suspend fun addPidErikaNoStreetAddress(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-PID-without-resident-address",
                vct = EUPersonalID.EUPID_VCT,
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                )
            )
        }

        private suspend fun addNonPidCredential(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-PID-mdoc",
                vct = "something-else",
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

        private fun singlePidQuery(): DcqlQuery {
            return DcqlQuery.fromJson(
                Json.parseToJsonElement(
                    """
                        {
                          "credentials": [
                            {
                              "id": "my_credential",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["${EUPersonalID.EUPID_VCT}"]
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

        private fun singlePidQueryForEntireAddress(): DcqlQuery {
            return DcqlQuery.fromJson(
                Json.parseToJsonElement(
                    """
                        {
                          "credentials": [
                            {
                              "id": "my_credential",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["${EUPersonalID.EUPID_VCT}"]
                              },
                              "claims": [
                                {"path": ["given_name"]},
                                {"path": ["address"]}
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
    fun singlePidQueryNoCredentials() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        // Fails if we have no credentials
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            singlePidQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matches for credential query with id my_credential", e.message)
    }

    @Test
    fun singlePidQueryNoCredentialsWithVct() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addNonPidCredential(harness)
        // Fails if the credentials we have are of a different VCT
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            singlePidQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matches for credential query with id my_credential", e.message)
    }

    @Test
    fun singlePidQueryMatchSingleCredential() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErika(harness)
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
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-PID-Erika
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: Resident Street
                                      value: Sample Street 123
            """.trimIndent().trim(),
            singlePidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singlePidQueryMatchTwoCredentials() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErika(harness)
        addPidMax(harness)
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
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-PID-Erika
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: Resident Street
                                      value: Sample Street 123
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-PID-Max
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given Names
                                      value: Max
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: Resident Street
                                      value: Sample Street 456
            """.trimIndent().trim(),
            singlePidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singlePidQueryRequireAllClaimsToBePresent() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErika(harness)
        addPidErikaNoStreetAddress(harness)
        // Checks we get one match with one matching credential if the other PID lacks the street_address claim
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
                                      path: ["given_name"]
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: Resident Street
                                      value: Sample Street 123
            """.trimIndent().trim(),
            singlePidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singlePidQueryEntireAddress() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErika(harness)
        // Checks we get one match with one matching credential if the other PID lacks the street_address claim
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
                                      path: ["given_name"]
                                      displayName: Given Names
                                      value: Erika
                                    claim:
                                      path: ["address"]
                                      displayName: Address
                                      value: {"country":"US","state":"CA","postal_code":"90210","street_address":"Sample Street 123"}
            """.trimIndent().trim(),
            singlePidQueryForEntireAddress().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }
}
