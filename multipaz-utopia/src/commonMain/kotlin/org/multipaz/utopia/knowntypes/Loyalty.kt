package org.multipaz.utopia.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.util.fromBase64Url
import kotlinx.datetime.LocalDate
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.knowntypes.SampleData

object Loyalty {
    const val LOYALTY_DOCTYPE = "org.multipaz.loyalty.1"
    const val LOYALTY_NAMESPACE = "org.multipaz.loyalty.1"

    /**
     * Build the Loyalty ID Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_LOYALTY_CARD))
            .addMdocDocumentType(LOYALTY_DOCTYPE)
            // Core holder data relevant for a loyalty card
            //
            .addMdocAttribute(
                DocumentAttributeType.String,
                "family_name",
                getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_FAMILY_NAME),
                getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_FAMILY_NAME),
                true,
                LOYALTY_NAMESPACE,
                Icon.PERSON,
                SampleData.FAMILY_NAME.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "given_name",
                getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_GIVEN_NAMES),
                getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_GIVEN_NAMES),
                true,
                LOYALTY_NAMESPACE,
                Icon.PERSON,
                SampleData.GIVEN_NAME.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "portrait",
                getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_PHOTO_OF_HOLDER),
                getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_PHOTO_OF_HOLDER),
                true,
                LOYALTY_NAMESPACE,
                Icon.ACCOUNT_BOX,
                SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
            )
            // Then the LoyaltyID specific data elements.
            //
            .addMdocAttribute(
                DocumentAttributeType.String,
                "membership_number",
                getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_MEMBERSHIP_ID),
                getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_MEMBERSHIP_ID),
                false,
                LOYALTY_NAMESPACE,
                Icon.NUMBERS,
                SampleData.PERSON_ID.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "tier",
                getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_TIER),
                getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_TIER),
                false,
                LOYALTY_NAMESPACE,
                Icon.STARS,
                "basic".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "issue_date",
                getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_DATE_OF_ISSUE),
                getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_DATE_OF_ISSUE),
                true,
                LOYALTY_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_DATE_OF_EXPIRY),
                getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_DATE_OF_EXPIRY),
                true,
                LOYALTY_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            // Finally for the sample requests.
            //
            .addSampleRequest(
                id = "mandatory",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_REQUEST_MANDATORY_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    LOYALTY_NAMESPACE to mapOf(
                        "family_name" to false,
                        "given_name" to false,
                        "portrait" to false,
                        "membership_number" to false,
                        "tier" to false,
                        "issue_date" to false,
                        "expiry_date" to false,
                    )
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_REQUEST_ALL_DATA_ELEMENTS),
                mdocDataElements = mapOf(
                    LOYALTY_NAMESPACE to mapOf()
                )
            )
            .build()
    }
}
