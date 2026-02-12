package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.claim.findMatchingClaim
import org.multipaz.document.Document
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestPathSelection {

    companion object {
        private suspend fun addMdlErika(harness: DocumentStoreTestHarness): Document {
            return harness.provisionMdoc(
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

        private suspend fun addPidErika(harness: DocumentStoreTestHarness): Document {
            return harness.provisionSdJwtVc(
                displayName = "my-PID-Erika",
                vct = EUPersonalID.EUPID_VCT,
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("country", JsonPrimitive("US"))
                        put("state", JsonPrimitive("CA"))
                        put("postal_code", JsonPrimitive(90210))
                        put("street_address", JsonPrimitive("Sample Street 123"))
                        put("house_number", JsonPrimitive(123))
                    },
                    "nationalities" to buildJsonArray { add("German"); add("American") },
                    "degrees" to buildJsonArray {
                        addJsonObject {
                            put("type", JsonPrimitive("Bachelor of Science"))
                            put("university", JsonPrimitive("University of Betelgeuse"))
                        }
                        addJsonObject {
                            put("type", JsonPrimitive("Master of Science"))
                            put("university", JsonPrimitive("University of Betelgeuse"))
                        }
                    }
                )
            )
        }
    }

    @Test
    fun pathSelectionMdoc() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val mdlErika = addMdlErika(harness)
        val mdlErikaClaims = mdlErika.getCertifiedCredentials()[0].getClaims(null)

        assertEquals(
            "Erika",
            (mdlErikaClaims.findMatchingClaim(
                requestedClaim = MdocRequestedClaim(
                    namespaceName = "org.iso.18013.5.1",
                    dataElementName = "given_name",
                    intentToRetain = false,
                    values = null
                )
            )!! as MdocClaim).value.asTstr
        )

        assertEquals(
            "Erika",
            (mdlErikaClaims.findMatchingClaim(
                requestedClaim = MdocRequestedClaim(
                    namespaceName = "org.iso.18013.5.1",
                    dataElementName = "given_name",
                    intentToRetain = false,
                    values = buildJsonArray { add("Erika") }
                )
            )!! as MdocClaim).value.asTstr
        )

        assertNull(
            mdlErikaClaims.findMatchingClaim(
                requestedClaim = MdocRequestedClaim(
                    namespaceName = "org.iso.18013.5.1",
                    dataElementName = "given_name",
                    intentToRetain = false,
                    values = buildJsonArray { add("Max") }
                )
            )
        )
    }

    @Test
    fun pathSelectionVc() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val pidErika = addPidErika(harness)
        val pidErikaClaims = pidErika.getCertifiedCredentials()[0].getClaims(null)

        assertEquals(
            JsonPrimitive("Erika"),
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("given_name") },
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            null,
            pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("does-not-exist") },
                )
            )
        )

        assertEquals(
            JsonPrimitive("US"),
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("address"); add("country") },
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            JsonPrimitive("CA"),
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("address"); add("state") },
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            JsonPrimitive(123),
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("address"); add("house_number") },
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            null,
            pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("address"); add("does-not-exist") },
                )
            )
        )

        assertEquals(
            buildJsonObject {
                put("country", JsonPrimitive("US"))
                put("state", JsonPrimitive("CA"))
                put("postal_code", JsonPrimitive(90210))
                put("street_address", JsonPrimitive("Sample Street 123"))
                put("house_number", JsonPrimitive(123))
            },
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("address") },
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            JsonPrimitive("American"),
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("nationalities"); add(1) },
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            buildJsonArray {
                add("Bachelor of Science")
                add("Master of Science")
            },
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("degrees"); add("type") },
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            JsonPrimitive("Erika"),
            (pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("given_name") },
                    values = buildJsonArray { add("Erika") }
                )
            )!! as JsonClaim).value
        )

        assertEquals(
            null,
            pidErikaClaims.findMatchingClaim(
                requestedClaim = JsonRequestedClaim(
                    claimPath = buildJsonArray { add("given_name") },
                    values = buildJsonArray { add("Max") }
                )
            )
        )
    }
}
