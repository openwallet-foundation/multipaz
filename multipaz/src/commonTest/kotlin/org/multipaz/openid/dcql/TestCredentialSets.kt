package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestCredentialSets {
    companion object {

        private suspend fun addCredPid(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-pid",
                vct = "https://credentials.example.com/identity_credential",
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("street_address", JsonPrimitive("Sample Street 123"))
                    }
                )
            )
        }

        private suspend fun addCredPidMax(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-pid-max",
                vct = "https://credentials.example.com/identity_credential",
                data = listOf(
                    "given_name" to JsonPrimitive("Max"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("street_address", JsonPrimitive("Sample Street 456"))
                    }
                )
            )
        }

        private suspend fun addCredOtherPid(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-other-pid",
                vct = "https://othercredentials.example/pid",
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("street_address", JsonPrimitive("Sample Street 123"))
                    }
                )
            )
        }

        private suspend fun addCredPidReduced1(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-pid-reduced1",
                vct = "https://credentials.example.com/reduced_identity_credential",
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                )
            )
        }

        private suspend fun addCredPidReduced2(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-pid-reduced2",
                vct = "https://cred.example/residence_credential",
                data = listOf(
                    "postal_code" to JsonPrimitive(90210),
                    "locality" to JsonPrimitive("Beverly Hills"),
                    "region" to JsonPrimitive("Los Angeles Basin"),
                )
            )
        }

        private suspend fun addCredCompanyRewards(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-reward-card",
                vct = "https://company.example/company_rewards",
                data = listOf(
                    "rewards_number" to JsonPrimitive(24601),
                )
            )
        }

        private fun complexQuery(): DcqlQuery {
            return DcqlQuery.fromJson(
                Json.parseToJsonElement(
                    """
                        {
                          "credentials": [
                            {
                              "id": "pid",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["https://credentials.example.com/identity_credential"]
                              },
                              "claims": [
                                {"path": ["given_name"]},
                                {"path": ["family_name"]},
                                {"path": ["address", "street_address"]}
                              ]
                            },
                            {
                              "id": "other_pid",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["https://othercredentials.example/pid"]
                              },
                              "claims": [
                                {"path": ["given_name"]},
                                {"path": ["family_name"]},
                                {"path": ["address", "street_address"]}
                              ]
                            },
                            {
                              "id": "pid_reduced_cred_1",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["https://credentials.example.com/reduced_identity_credential"]
                              },
                              "claims": [
                                {"path": ["family_name"]},
                                {"path": ["given_name"]}
                              ]
                            },
                            {
                              "id": "pid_reduced_cred_2",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["https://cred.example/residence_credential"]
                              },
                              "claims": [
                                {"path": ["postal_code"]},
                                {"path": ["locality"]},
                                {"path": ["region"]}
                              ]
                            },
                            {
                              "id": "nice_to_have",
                              "format": "dc+sd-jwt",
                              "meta": {
                                "vct_values": ["https://company.example/company_rewards"]
                              },
                              "claims": [
                                {"path": ["rewards_number"]}
                              ]
                            }
                          ],
                          "credential_sets": [
                            {
                              "options": [
                                [ "pid" ],
                                [ "other_pid" ],
                                [ "pid_reduced_cred_1", "pid_reduced_cred_2" ]
                              ]
                            },
                            {
                              "required": false,
                              "options": [
                                [ "nice_to_have" ]
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
    fun complex_HaveAll() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPid(harness)
        addCredOtherPid(harness)
        addCredPidReduced1(harness)
        addCredPidReduced2(harness)
        addCredCompanyRewards(harness)

        val queryResult = complexQuery().execute(
            presentmentSource = harness.presentmentSource
        )
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
                                  docId: my-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-other-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced1
                                  claims:
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced2
                                  claims:
                                    claim:
                                      path: ["postal_code"]
                                      displayName: postal_code
                                      value: 90210
                                    claim:
                                      path: ["locality"]
                                      displayName: locality
                                      value: Beverly Hills
                                    claim:
                                      path: ["region"]
                                      displayName: region
                                      value: Los Angeles Basin
                  credentialSet:
                    optional: true
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-reward-card
                                  claims:
                                    claim:
                                      path: ["rewards_number"]
                                      displayName: rewards_number
                                      value: 24601
            """.trimIndent().trim(),
            queryResult.prettyPrint().trim()
        )

        // Also check that getAllSelections() returns all possible selections
        assertEquals(
            """
                selections:
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-reward-card
                        claims:
                          claim:
                            path: ["rewards_number"]
                            displayName: rewards_number
                            value: 24601
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-other-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-other-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-reward-card
                        claims:
                          claim:
                            path: ["rewards_number"]
                            displayName: rewards_number
                            value: 24601
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced1
                        claims:
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced2
                        claims:
                          claim:
                            path: ["postal_code"]
                            displayName: postal_code
                            value: 90210
                          claim:
                            path: ["locality"]
                            displayName: locality
                            value: Beverly Hills
                          claim:
                            path: ["region"]
                            displayName: region
                            value: Los Angeles Basin
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced1
                        claims:
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced2
                        claims:
                          claim:
                            path: ["postal_code"]
                            displayName: postal_code
                            value: 90210
                          claim:
                            path: ["locality"]
                            displayName: locality
                            value: Beverly Hills
                          claim:
                            path: ["region"]
                            displayName: region
                            value: Los Angeles Basin
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-reward-card
                        claims:
                          claim:
                            path: ["rewards_number"]
                            displayName: rewards_number
                            value: 24601
            """.trimIndent().trim(),
            queryResult.getAllSelections().prettyPrint().trim()
        )
    }

    @Test
    fun complex_HaveAllWithMax() = runTest {
        // Like complex_HaveAll() but where there are two PIDs of the same type (Erika and Max)
        // This should be the same result as complex_HaveAll() except for two matches on the
        // 'pid' credential query...
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPid(harness)
        addCredPidMax(harness)
        addCredOtherPid(harness)
        addCredPidReduced1(harness)
        addCredPidReduced2(harness)
        addCredCompanyRewards(harness)

        val queryResult = complexQuery().execute(
            presentmentSource = harness.presentmentSource
        )
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
                                  docId: my-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-max
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Max
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 456
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-other-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced1
                                  claims:
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced2
                                  claims:
                                    claim:
                                      path: ["postal_code"]
                                      displayName: postal_code
                                      value: 90210
                                    claim:
                                      path: ["locality"]
                                      displayName: locality
                                      value: Beverly Hills
                                    claim:
                                      path: ["region"]
                                      displayName: region
                                      value: Los Angeles Basin
                  credentialSet:
                    optional: true
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-reward-card
                                  claims:
                                    claim:
                                      path: ["rewards_number"]
                                      displayName: rewards_number
                                      value: 24601
            """.trimIndent().trim(),
            queryResult.prettyPrint().trim()
        )

        // Also check that getAllSelections() returns all possible selections
        assertEquals(
            """
                selections:
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-reward-card
                        claims:
                          claim:
                            path: ["rewards_number"]
                            displayName: rewards_number
                            value: 24601
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-max
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Max
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 456
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-max
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Max
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 456
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-reward-card
                        claims:
                          claim:
                            path: ["rewards_number"]
                            displayName: rewards_number
                            value: 24601
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-other-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-other-pid
                        claims:
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["address","street_address"]
                            displayName: address.street_address
                            value: Sample Street 123
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-reward-card
                        claims:
                          claim:
                            path: ["rewards_number"]
                            displayName: rewards_number
                            value: 24601
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced1
                        claims:
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced2
                        claims:
                          claim:
                            path: ["postal_code"]
                            displayName: postal_code
                            value: 90210
                          claim:
                            path: ["locality"]
                            displayName: locality
                            value: Beverly Hills
                          claim:
                            path: ["region"]
                            displayName: region
                            value: Los Angeles Basin
                  matches:
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced1
                        claims:
                          claim:
                            path: ["family_name"]
                            displayName: family_name
                            value: Mustermann
                          claim:
                            path: ["given_name"]
                            displayName: given_name
                            value: Erika
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-pid-reduced2
                        claims:
                          claim:
                            path: ["postal_code"]
                            displayName: postal_code
                            value: 90210
                          claim:
                            path: ["locality"]
                            displayName: locality
                            value: Beverly Hills
                          claim:
                            path: ["region"]
                            displayName: region
                            value: Los Angeles Basin
                    match:
                      credential:
                        type: KeyBoundSdJwtVcCredential
                        docId: my-reward-card
                        claims:
                          claim:
                            path: ["rewards_number"]
                            displayName: rewards_number
                            value: 24601
            """.trimIndent().trim(),
            queryResult.getAllSelections().prettyPrint().trim()
        )
    }

    @Test
    fun complex_AllPidsNoRewards() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPid(harness)
        addCredOtherPid(harness)
        addCredPidReduced1(harness)
        addCredPidReduced2(harness)
        // Reward card is optional
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
                                  docId: my-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-other-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced1
                                  claims:
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced2
                                  claims:
                                    claim:
                                      path: ["postal_code"]
                                      displayName: postal_code
                                      value: 90210
                                    claim:
                                      path: ["locality"]
                                      displayName: locality
                                      value: Beverly Hills
                                    claim:
                                      path: ["region"]
                                      displayName: region
                                      value: Los Angeles Basin
            """.trimIndent().trim(),
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun complex_OnlyPid() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPid(harness)
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
                                  docId: my-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun complex_OnlyOtherPid() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredOtherPid(harness)
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
                                  docId: my-other-pid
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: address.street_address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun complex_OnlyPidReduced1And2() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPidReduced1(harness)
        addCredPidReduced2(harness)
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
                                  docId: my-pid-reduced1
                                  claims:
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced2
                                  claims:
                                    claim:
                                      path: ["postal_code"]
                                      displayName: postal_code
                                      value: 90210
                                    claim:
                                      path: ["locality"]
                                      displayName: locality
                                      value: Beverly Hills
                                    claim:
                                      path: ["region"]
                                      displayName: region
                                      value: Los Angeles Basin
            """.trimIndent().trim(),
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun complex_OnlyPidReduced1And2AndRewards() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPidReduced1(harness)
        addCredPidReduced2(harness)
        addCredCompanyRewards(harness)
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
                                  docId: my-pid-reduced1
                                  claims:
                                    claim:
                                      path: ["family_name"]
                                      displayName: family_name
                                      value: Mustermann
                                    claim:
                                      path: ["given_name"]
                                      displayName: given_name
                                      value: Erika
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-pid-reduced2
                                  claims:
                                    claim:
                                      path: ["postal_code"]
                                      displayName: postal_code
                                      value: 90210
                                    claim:
                                      path: ["locality"]
                                      displayName: locality
                                      value: Beverly Hills
                                    claim:
                                      path: ["region"]
                                      displayName: region
                                      value: Los Angeles Basin
                  credentialSet:
                    optional: true
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: my-reward-card
                                  claims:
                                    claim:
                                      path: ["rewards_number"]
                                      displayName: rewards_number
                                      value: 24601
            """.trimIndent().trim(),
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun complex_OnlyPidReduced1() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPidReduced1(harness)
        // Fails b/c PidReduced2 isn't available.
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No credentials match required credential_set query", e.message)
    }

    @Test
    fun complex_OnlyPidReduced2() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredPidReduced2(harness)
        // Fails b/c PidReduced1 isn't available.
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No credentials match required credential_set query", e.message)
    }

    @Test
    fun complex_OnlyRewardsCard() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addCredCompanyRewards(harness)
        // PID isn't optional so this fails if we only have the rewards card.
        val e = assertFailsWith(DcqlCredentialQueryException::class) {
            complexQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No credentials match required credential_set query", e.message)
    }
}
