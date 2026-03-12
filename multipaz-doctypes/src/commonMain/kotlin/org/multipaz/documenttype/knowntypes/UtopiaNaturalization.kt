package org.multipaz.documenttype.knowntypes

import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.serialization.json.JsonPrimitive

/**
 * Naturalization Certificate of the fictional State of Utopia.
 */
object UtopiaNaturalization {
    const val VCT = "http://utopia.example.com/vct/naturalization"

    /**
     * Build the Utopia Naturalization Certificate Document Type.
     */
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Naturalization certificate")
            .addJsonDocumentType(type = VCT, keyBound = true)
            .addJsonAttribute(
                DocumentAttributeType.String,
                "family_name",
                "Family name",
                "Current last name(s), surname(s), or primary identifier of the naturalized person",
                Icon.PERSON,
                JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "given_name",
                "Given names",
                "Current first name(s), other name(s), or secondary identifier of the naturalized person",
                Icon.PERSON,
                JsonPrimitive(SampleData.GIVEN_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "birth_date",
                "Date of birth",
                "Day, month, and year on which the naturalized person was born. If unknown, approximate date of birth.",
                Icon.TODAY,
                JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "naturalization_date",
                "Date of naturalization",
                "Date (and possibly time) when the person was naturalized.",
                Icon.DATE_RANGE,
                JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addSampleRequest(
                id = "full",
                displayName = "All data elements",
                jsonClaims = listOf()
            )
            .build()
    }
}