package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals

class Iso18013TestSdJwtVc {
    companion object {
        private suspend fun addPidErikaSdJwtVc(harness: DocumentStoreTestHarness) {
            harness.provisionSdJwtVc(
                displayName = "my-PID-Erika",
                vct = EUPersonalID.EUPID_VCT,
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("formatted", JsonPrimitive("Sample Street 123, CA 90210, US"))
                        put("country", JsonPrimitive("US"))
                        put("state", JsonPrimitive("CA"))
                        put("postal_code", JsonPrimitive("90210"))
                        put("street_address", JsonPrimitive("Sample Street 123"))
                    }
                )
            )
        }

        private suspend fun addPidErikaSdJwtVcWithoutFormatted(harness: DocumentStoreTestHarness) {
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

        private fun pidQuery(): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            ) {
                addDocRequest(
                    docType = EUPersonalID.EUPID_VCT,
                    nameSpaces = mapOf(
                        "_" to mapOf(
                            "sdjwtvc_given_name" to false,
                            "sdjwtvc_resident_address" to false
                        )
                    ),
                    docRequestInfo = DocRequestInfo(
                        docFormat = "dc+sd-jwt",
                        dataElementIdentifierMapping = mapOf(
                            "sdjwtvc_given_name" to buildJsonArray { add("given_name") },
                            "sdjwtvc_resident_address" to buildJsonArray { add("address"); add("formatted") },
                        )
                    )
                )
            }
        }

        private fun pidQueryWithAlternativeDataElements(): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            ) {
                addDocRequest(
                    docType = EUPersonalID.EUPID_VCT,
                    nameSpaces = mapOf(
                        "_" to mapOf(
                            "sdjwtvc_given_name" to false,
                            "sdjwtvc_resident_address" to false
                        )
                    ),
                    docRequestInfo = DocRequestInfo(
                        docFormat = "dc+sd-jwt",
                        dataElementIdentifierMapping = mapOf(
                            "sdjwtvc_given_name" to buildJsonArray { add("given_name") },
                            "sdjwtvc_resident_address" to buildJsonArray { add("address"); add("formatted") },
                            "sdjwtvc_state" to buildJsonArray { add("address"); add("state") },
                            "sdjwtvc_postal_code" to buildJsonArray { add("address"); add("postal_code") },
                            "sdjwtvc_street_address" to buildJsonArray { add("address"); add("street_address") },
                        ),
                        alternativeDataElements = listOf(AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                namespace = "_",
                                dataElement = "sdjwtvc_resident_address",
                            ),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(
                                        namespace = "_",
                                        dataElement = "sdjwtvc_state",
                                    ),
                                    ElementReference(
                                        namespace = "_",
                                        dataElement = "sdjwtvc_postal_code",
                                    ),
                                    ElementReference(
                                        namespace = "_",
                                        dataElement = "sdjwtvc_street_address",
                                    )
                                )
                            )
                        ))
                    )
                )
            }
        }
    }

    @Test
    fun testToDqclQueryWithAlternativeDataElements() = runTest {
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
                      "format": "dc+sd-jwt",
                      "meta": {
                        "vct_values": [
                          "urn:eudi:pid:1"
                        ]
                      },
                      "claims": [
                        {
                          "id": "claim0",
                          "path": [
                            "given_name"
                          ]
                        },
                        {
                          "id": "claim1",
                          "path": [
                            "address",
                            "formatted"
                          ]
                        },
                        {
                          "id": "claim2",
                          "path": [
                            "address",
                            "state"
                          ]
                        },
                        {
                          "id": "claim3",
                          "path": [
                            "address",
                            "postal_code"
                          ]
                        },
                        {
                          "id": "claim4",
                          "path": [
                            "address",
                            "street_address"
                          ]
                        }
                      ],
                      "claim_sets": [
                        [
                          "claim0",
                          "claim1"
                        ],
                        [
                          "claim0",
                          "claim2",
                          "claim3",
                          "claim4"
                        ]
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            prettyJson.encodeToString(pidQueryWithAlternativeDataElements().toDcql())
        )
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
                      "format": "dc+sd-jwt",
                      "meta": {
                        "vct_values": [
                          "urn:eudi:pid:1"
                        ]
                      },
                      "claims": [
                        {
                          "id": "claim0",
                          "path": [
                            "given_name"
                          ]
                        },
                        {
                          "id": "claim1",
                          "path": [
                            "address",
                            "formatted"
                          ]
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            prettyJson.encodeToString(pidQuery().toDcql())
        )
    }

    @Test
    fun requestPid() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErikaSdJwtVc(harness)
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
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      path: ["address","formatted"]
                                      displayName: Resident address
                                      value: Sample Street 123, CA 90210, US
            """.trimIndent().trim(),
            pidQuery().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun requestPidWithAlternativeDataElements_HaveFormatted() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErikaSdJwtVc(harness)
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
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      path: ["address","formatted"]
                                      displayName: Resident address
                                      value: Sample Street 123, CA 90210, US
            """.trimIndent().trim(),
            pidQueryWithAlternativeDataElements().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }

    @Test
    fun requestPidWithAlternativeDataElements_DoNotHaveFormatted() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidErikaSdJwtVcWithoutFormatted(harness)
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
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      path: ["address","state"]
                                      displayName: address.state
                                      value: CA
                                    claim:
                                      path: ["address","postal_code"]
                                      displayName: Resident postal code
                                      value: 90210
                                    claim:
                                      path: ["address","street_address"]
                                      displayName: Resident street
                                      value: Sample Street 123
            """.trimIndent().trim(),
            pidQueryWithAlternativeDataElements().execute(
                presentmentSource = harness.presentmentSource
            ).prettyPrint().trim()
        )
    }
}