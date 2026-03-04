package org.multipaz.request

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestedClaimTests {

    @Test
    fun testSerializationMdocRequestedClaim() {
        val mdocRequestedClaim = MdocRequestedClaim(
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = "age_over_18",
            intentToRetain = true,
            values = null
        )
        assertEquals(
            RequestedClaim.fromDataItem(mdocRequestedClaim.toDataItem()),
            mdocRequestedClaim
        )
    }

    @Test
    fun testSerializationJsonRequestedClaim() {
        val jsonRequestedClaim = JsonRequestedClaim(
            vctValues = listOf(EUPersonalID.EUPID_VCT),
            claimPath = buildJsonArray { add("given_name") },
            values = buildJsonArray { add("Max") }
        )
        assertEquals(
            RequestedClaim.fromDataItem(jsonRequestedClaim.toDataItem()),
            jsonRequestedClaim
        )
    }
}