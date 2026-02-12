package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Iso18013TestMdlOrPid {

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
            harness.provisionMdoc(
                displayName = "my-PID-Erika",
                docType = EUPersonalID.EUPID_DOCTYPE,
                data = mapOf(
                    EUPersonalID.EUPID_NAMESPACE to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_address" to Tstr("Sample Street 123"),
                    )
                )
            )
        }

        private fun mdlOrPidQuery(): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
                deviceRequestInfo = DeviceRequestInfo(
                    useCases = listOf(
                        UseCase(
                            mandatory = true,
                            documentSets = listOf(
                                DocumentSet(listOf(0)),
                                DocumentSet(listOf(1)),
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
                addDocRequest(
                    docType = EUPersonalID.EUPID_DOCTYPE,
                    nameSpaces = mapOf(
                        EUPersonalID.EUPID_NAMESPACE to mapOf(
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
                    },
                    {
                      "id": "cred1",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "eu.europa.ec.eudi.pid.1"
                      },
                      "claims": [
                        {
                          "id": "claim0",
                          "path": [
                            "eu.europa.ec.eudi.pid.1",
                            "given_name"
                          ],
                          "intent_to_retain": false
                        },
                        {
                          "id": "claim1",
                          "path": [
                            "eu.europa.ec.eudi.pid.1",
                            "resident_address"
                          ],
                          "intent_to_retain": false
                        }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "required": true,
                      "options": [
                        [
                          "cred0"
                        ],
                        [
                          "cred1"
                        ]
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            prettyJson.encodeToString(mdlOrPidQuery().toDcql())
        )
    }

    @Test
    fun mdlOrPidNoCredentials() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        // Fails if we have no credentials
        val e = assertFailsWith(Iso18015ResponseException::class) {
            mdlOrPidQuery().execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No credentials match required UseCase", e.message)
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
                                  nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                  dataElement: given_name
                                  displayName: Given Names
                                  value: Erika
                                claim:
                                  nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                  dataElement: resident_address
                                  displayName: Resident Address
                                  value: Sample Street 123
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
                              type: MdocCredential
                              docId: my-PID-Erika
                              claims:
                                claim:
                                  nameSpace: ${EUPersonalID.EUPID_NAMESPACE}
                                  dataElement: given_name
                                  displayName: Given Names
                                  value: Erika
                                claim:
                                  nameSpace: ${EUPersonalID.EUPID_NAMESPACE}
                                  dataElement: resident_address
                                  displayName: Resident Address
                                  value: Sample Street 123
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
                                  nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                  dataElement: given_name
                                  displayName: Given Names
                                  value: Erika
                                claim:
                                  nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                  dataElement: resident_address
                                  displayName: Resident Address
                                  value: Sample Street 123
                  option:
                    members:
                      member:
                        matches:
                          match:
                            credential:
                              type: MdocCredential
                              docId: my-PID-Erika
                              claims:
                                claim:
                                  nameSpace: ${EUPersonalID.EUPID_NAMESPACE}
                                  dataElement: given_name
                                  displayName: Given Names
                                  value: Erika
                                claim:
                                  nameSpace: ${EUPersonalID.EUPID_NAMESPACE}
                                  dataElement: resident_address
                                  displayName: Resident Address
                                  value: Sample Street 123
        """.trimIndent().trim(),
            mdlOrPidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }
}
