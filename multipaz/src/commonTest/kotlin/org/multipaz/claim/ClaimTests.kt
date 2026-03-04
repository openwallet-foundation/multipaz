package org.multipaz.claim

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.cbor.Simple
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ClaimTests {
    @Test
    fun testSerializationMdocClaim() {
        val mdocClaim = MdocClaim(
            displayName = "Age over 18?",
            attribute = null,
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = "age_over_18",
            value = Simple.TRUE
        )
        assertEquals(
            Claim.fromDataItem(mdocClaim.toDataItem()),
            mdocClaim
        )

        val attribute = DrivingLicense.getDocumentType().mdocDocumentType!!
            .namespaces[DrivingLicense.MDL_NAMESPACE]!!.dataElements["age_over_18"]!!.attribute
        assertNotNull(attribute)

        val mdocClaimWithAttribute = MdocClaim(
            displayName = "Age over 18?",
            attribute = attribute,
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = "age_over_18",
            value = Simple.TRUE
        )

        assertNotEquals(
            Claim.fromDataItem(mdocClaim.toDataItem()),
            mdocClaimWithAttribute
        )

        val docTypeRepo = DocumentTypeRepository().apply {
            addDocumentType(DrivingLicense.getDocumentType())
        }
        assertEquals(
            Claim.fromDataItem(mdocClaimWithAttribute.toDataItem(), docTypeRepo),
            mdocClaimWithAttribute
        )
    }

    @Test
    fun testSerializationJsonClaim() {
        val jsonClaim = JsonClaim(
            displayName = "Given name",
            attribute = null,
            vct = EUPersonalID.EUPID_VCT,
            claimPath = buildJsonArray { add("given_name") },
            value = JsonPrimitive("Max")
        )
        assertEquals(
            Claim.fromDataItem(jsonClaim.toDataItem()),
            jsonClaim
        )

        val attribute = EUPersonalID.getDocumentType().jsonDocumentType!!.claims["given_name"]
        assertNotNull(attribute)

        val jsonClaimWithAttribute = JsonClaim(
            displayName = "Given name",
            attribute = attribute,
            vct = EUPersonalID.EUPID_VCT,
            claimPath = buildJsonArray { add("given_name") },
            value = JsonPrimitive("Max")
        )

        assertNotEquals(
            Claim.fromDataItem(jsonClaimWithAttribute.toDataItem()),
            jsonClaimWithAttribute
        )

        val docTypeRepo = DocumentTypeRepository().apply {
            addDocumentType(EUPersonalID.getDocumentType())
        }
        assertEquals(
            Claim.fromDataItem(jsonClaimWithAttribute.toDataItem(), docTypeRepo),
            jsonClaimWithAttribute
        )
    }
}