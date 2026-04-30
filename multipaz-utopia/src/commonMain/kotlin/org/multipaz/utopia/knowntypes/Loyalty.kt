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
import org.multipaz.documenttype.DocumentAttributeSensitivity

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
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_FAMILY_NAME),
                mandatory = true,
                mdocNamespace = LOYALTY_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.FAMILY_NAME.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_GIVEN_NAMES),
                description = getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_GIVEN_NAMES),
                mandatory = true,
                mdocNamespace = LOYALTY_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.GIVEN_NAME.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "portrait",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_PHOTO_OF_HOLDER),
                description = getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_PHOTO_OF_HOLDER),
                mandatory = true,
                mdocNamespace = LOYALTY_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.PORTRAIT_IMAGE,
                icon = Icon.ACCOUNT_BOX,
                sampleValue = SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
            )
            // Then the LoyaltyID specific data elements.
            //
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "membership_number",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_MEMBERSHIP_ID),
                description = getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_MEMBERSHIP_ID),
                mandatory = false,
                mdocNamespace = LOYALTY_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValue = SampleData.PERSON_ID.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "tier",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_TIER),
                description = getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_TIER),
                mandatory = false,
                mdocNamespace = LOYALTY_NAMESPACE,
                icon = Icon.STARS,
                sampleValue = "basic".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "issue_date",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_DATE_OF_ISSUE),
                description = getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_DATE_OF_ISSUE),
                mandatory = true,
                mdocNamespace = LOYALTY_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "expiry_date",
                displayName = getLocalizedString(GeneratedStringKeys.LOYALTY_ATTRIBUTE_DATE_OF_EXPIRY),
                description = getLocalizedString(GeneratedStringKeys.LOYALTY_DESCRIPTION_DATE_OF_EXPIRY),
                mandatory = true,
                mdocNamespace = LOYALTY_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
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
