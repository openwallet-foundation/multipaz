package org.multipaz.utopia.knowntypes

import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.documenttype.knowntypes.Options
import org.multipaz.documenttype.knowntypes.SampleData
import org.multipaz.documenttype.DocumentAttributeSensitivity

/**
 * Object containing the metadata of the German ID Document Type.
 *
 * For now, this is a copy of EUPersonaID.
 *
 * TODO: read this (and other) VCTs for their URLs.
 */
object GermanPersonalID {
    const val EUPID_VCT = "https://example.bmi.bund.de/credential/pid/1.0"

    /**
     * Build the EU Personal ID Document Type.
     */
    fun getDocumentType(locale: String = LocalizedStrings.getCurrentLocale()): DocumentType {
        fun getLocalizedString(key: String) = LocalizedStrings.getString(key, locale)

        return DocumentType.Builder(getLocalizedString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_GERMAN_PERSONAL_ID))
            .addJsonDocumentType(type = EUPID_VCT, keyBound = true)
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_FAMILY_NAME),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_FAMILY_NAME),
                icon = Icon.PERSON,
                sampleValue = JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "given_name",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_GIVEN_NAMES),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_GIVEN_NAMES),
                icon = Icon.PERSON,
                sampleValue = JsonPrimitive(SampleData.GIVEN_NAME)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Date,
                identifier = "birthdate",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DATE_OF_BIRTH),
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_in_years",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_AGE_IN_YEARS),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_AGE_IN_YEARS),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_IN_YEARS)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Number,
                identifier = "age_birth_year",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_YEAR_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_YEAR_OF_BIRTH),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_BIRTH_YEAR)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "12",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_12),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_12),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_OVER)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "14",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_14),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_14),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_OVER)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "16",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_16),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_16),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_OVER_16)
            )
            // TODO: nest in age_equal_or_over object
            .addJsonAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "18",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_18),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_18),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_OVER_18)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "21",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_21),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_21),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_OVER_21)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "65",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_65),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_65),
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = JsonPrimitive(SampleData.AGE_OVER_65)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_family_name",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_FAMILY_NAME_AT_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_FAMILY_NAME_AT_BIRTH),
                icon = Icon.PERSON,
                sampleValue = JsonPrimitive(SampleData.FAMILY_NAME_BIRTH)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_place",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_PLACE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_PLACE_OF_BIRTH),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.BIRTH_PLACE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "birth_country",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_COUNTRY_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_COUNTRY_OF_BIRTH),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.BIRTH_COUNTRY)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_state",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_STATE_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_STATE_OF_BIRTH),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.BIRTH_STATE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "birth_city",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_CITY_OF_BIRTH),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_CITY_OF_BIRTH),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.BIRTH_CITY)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "street_address",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_ADDRESS),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_ADDRESS),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.RESIDENT_ADDRESS)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "locality",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_CITY),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_CITY),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.RESIDENT_CITY)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "country",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_COUNTRY),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.RESIDENT_COUNTRY)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "postal_code",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_POSTAL_CODE),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_POSTAL_CODE),
                icon = Icon.PLACE,
                sampleValue = JsonPrimitive(SampleData.RESIDENT_POSTAL_CODE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                identifier = "gender",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_GENDER),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_GENDER),
                icon = Icon.EMERGENCY,
                sampleValue = JsonPrimitive(SampleData.SEX_ISO_5218)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.ComplexType,
                identifier = "nationalities",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_NATIONALITY),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_NATIONALITY),
                icon = Icon.LANGUAGE,
                sampleValue = buildJsonArray {
                    add(JsonPrimitive(SampleData.NATIONALITY))
                }
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Date,
                identifier = "issuance_date",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DATE_OF_ISSUE),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DATE_OF_ISSUE),
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.DATE_RANGE,
                sampleValue = JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.Date,
                identifier = "expiry_date",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DATE_OF_EXPIRY),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DATE_OF_EXPIRY),
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = JsonPrimitive(SampleData.EXPIRY_DATE)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_authority",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ISSUING_AUTHORITY),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ISSUING_AUTHORITY),
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = JsonPrimitive(SampleData.ISSUING_AUTHORITY_EU_PID)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "document_number",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DOCUMENT_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DOCUMENT_NUMBER),
                icon = Icon.NUMBERS,
                sampleValue = JsonPrimitive(SampleData.DOCUMENT_NUMBER)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "administrative_number",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ADMINISTRATIVE_NUMBER),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ADMINISTRATIVE_NUMBER),
                icon = Icon.NUMBERS,
                sampleValue = JsonPrimitive(SampleData.ADMINISTRATIVE_NUMBER)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.String,
                identifier = "issuing_jurisdiction",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ISSUING_JURISDICTION),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ISSUING_JURISDICTION),
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = JsonPrimitive(SampleData.ISSUING_JURISDICTION)
            )
            .addJsonAttribute(
                type = DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                identifier = "issuing_country",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ISSUING_COUNTRY),
                description = getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ISSUING_COUNTRY),
                sensitivity = DocumentAttributeSensitivity.ISSUER,
                icon = Icon.ACCOUNT_BALANCE,
                sampleValue = JsonPrimitive(SampleData.ISSUING_COUNTRY)
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_REQUEST_AGE_OVER_18),
                jsonClaims = listOf("18")
            )
            .addSampleRequest(
                id = "mandatory",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_REQUEST_MANDATORY_DATA_ELEMENTS),
                jsonClaims = listOf(
                    "family_name",
                    "given_name",
                    "birthdate",
                    "18",
                    "issuance_date",
                    "expiry_date",
                    "issuing_authority",
                    "issuing_country"
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = getLocalizedString(GeneratedStringKeys.GERMAN_ID_REQUEST_ALL_DATA_ELEMENTS),
                jsonClaims = listOf()
            )
            .build()
    }
}
