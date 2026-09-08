package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Iso18013TestSingleMdlQuery {

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

        private suspend fun addMdlMax(harness: DocumentStoreTestHarness) {
            harness.provisionMdoc(
                displayName = "my-mDL-Max",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
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
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
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

        private fun singleMdlQuery(version: String? = null): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
                version = version,
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf(
                            "given_name" to false,
                            "resident_address" to false
                        )
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
                            "resident_address"
                          ],
                          "intent_to_retain": false
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            prettyJson.encodeToString(singleMdlQuery().toDcql())
        )
    }

    @Test
    fun singleMdlQueryNoCredentials() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidMdoc(harness)
        // Fails if we have no credentials
        val e = assertFailsWith(Iso18015ResponseException::class) {
            singleMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun singleMdlQueryNoCredentialsWithDoctype() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidMdoc(harness)
        // Fails if we have no credentials with the right docType
        val e = assertFailsWith(Iso18015ResponseException::class) {
            singleMdlQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
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
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
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
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-Max
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Max
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
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
        // In version 1.1, all requested claims must be present, so the credential lacking resident_address is excluded
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
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            singleMdlQuery(version = "1.1").execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singleMdlQueryV10PartialMatchSucceeds() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErikaNoResidentAddress(harness)
        // In version 1.0, having at least one of the requested data elements is sufficient
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
                                  docId: my-mDL-without-resident-address
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
            """.trimIndent().trim(),
            singleMdlQuery(version = "1.0").execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singleMdlQueryV10MultipleCandidatesPartialMatch() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErika(harness)
        addMdlErikaNoResidentAddress(harness)
        // In version 1.0, both credentials match since each has at least one requested element
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
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: my-mDL-without-resident-address
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
            """.trimIndent().trim(),
            singleMdlQuery(version = "1.0").execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun singleMdlQueryV10ZeroMatchFails() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "my-mDL-only-family-name",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "family_name" to Tstr("Mustermann"),
                )
            )
        )
        // Fails in version 1.0 if none of the requested data elements are present
        val e = assertFailsWith(Iso18015ResponseException::class) {
            singleMdlQuery(version = "1.0").execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals(
            "No matching credentials for first DocRequest: missing data elements: 'given_name' in namespace 'org.iso.18013.5.1', 'resident_address' in namespace 'org.iso.18013.5.1'",
            e.message
        )
    }

    @Test
    fun singleMdlQueryMissingSingleClaim() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErikaNoResidentAddress(harness)
        val e = assertFailsWith(Iso18015ResponseException::class) {
            singleMdlQuery(version = "1.1").execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals(
            "No matching credentials for first DocRequest: missing data element 'resident_address' in namespace 'org.iso.18013.5.1'",
            e.message
        )
    }

    @Test
    fun singleMdlQueryMissingMultipleClaims() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErikaNoResidentAddress(harness)
        val query = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            version = "1.1",
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to false,
                        "resident_address" to false,
                        "driving_privileges" to false
                    )
                )
            )
        }
        val e = assertFailsWith(Iso18015ResponseException::class) {
            query.execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals(
            "No matching credentials for first DocRequest: missing data elements: 'resident_address' in namespace 'org.iso.18013.5.1', 'driving_privileges' in namespace 'org.iso.18013.5.1'",
            e.message
        )
    }

    @Test
    fun singleMdlQueryMissingCustomNamespaceClaim() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErika(harness)
        val query = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            version = "1.1",
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    "com.example.custom" to mapOf(
                        "custom_field" to false
                    )
                )
            )
        }
        val e = assertFailsWith(Iso18015ResponseException::class) {
            query.execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals(
            "No matching credentials for first DocRequest: missing data element 'custom_field' in namespace 'com.example.custom'",
            e.message
        )
    }

    @Test
    fun singleMdlQueryUseCaseMissingClaim() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdlErikaNoResidentAddress(harness)
        val query = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(
                            DocumentSet(listOf(0)),
                        ),
                        purposeHints = mapOf()
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to false,
                        "resident_address" to false
                    )
                )
            )
        }
        val e = assertFailsWith(Iso18015ResponseException::class) {
            query.execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals(
            "No credentials match required UseCase: missing data element 'resident_address' in namespace 'org.iso.18013.5.1'",
            e.message
        )
    }
}