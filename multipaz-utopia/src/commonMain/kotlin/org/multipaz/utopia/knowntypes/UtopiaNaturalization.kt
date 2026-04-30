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
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_FAMILY_NAME),
                icon = Icon.PERSON,
                sampleValue = JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name",
                displayName = getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_GIVEN_NAMES),
                description = getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_GIVEN_NAMES),
                icon = Icon.PERSON,
                sampleValue = JsonPrimitive(SampleData.GIVEN_NAME)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Date,
                identifier = "birth_date",
                displayName = getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_DATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_DATE_OF_BIRTH),
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Date,
                identifier = "naturalization_date",
                displayName = getLocalizedString(GeneratedStringKeys.NATURALIZATION_ATTRIBUTE_DATE_OF_NATURALIZATION),
                description = getLocalizedString(GeneratedStringKeys.NATURALIZATION_DESCRIPTION_DATE_OF_NATURALIZATION),
                icon = Icon.DATE_RANGE,
                sampleValue = JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.NATURALIZATION_REQUEST_ALL_DATA_ELEMENTS),
                jsonClaims = listOf()
            )
            .build()
    }
}
