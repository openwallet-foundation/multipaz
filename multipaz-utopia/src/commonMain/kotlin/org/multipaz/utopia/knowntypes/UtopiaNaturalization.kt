package org.multipaz.utopia.knowntypes

import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.knowntypes.SampleData

/**
 * Naturalization Certificate of the fictional State of Utopia.
 */
object UtopiaNaturalization {
    const val VCT = "http://utopia.example.com/vct/naturalization"

    /**
     * Build the Utopia Naturalization Certificate Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_NATURALIZATION_CERTIFICATE))
            .addJsonDocumentType(type = VCT, keyBound = true)
            .addJsonAttribute(
                DocumentAttributeType.String,
                "family_name",
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_FAMILY_NAME),
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_FAMILY_NAME),
                Icon.PERSON,
                JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "given_name",
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_GIVEN_NAMES),
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_GIVEN_NAMES),
                Icon.PERSON,
                JsonPrimitive(SampleData.GIVEN_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "birth_date",
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_DATE_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_DATE_OF_BIRTH),
                Icon.TODAY,
                JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "naturalization_date",
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_DATE_OF_NATURALIZATION),
                getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_DATE_OF_NATURALIZATION),
                Icon.DATE_RANGE,
                JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.NATURALIZATION_REQUEST_ALL_DATA_ELEMENTS),
                jsonClaims = listOf()
            )
            .build()
    }
}
