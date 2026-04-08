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
                DocumentAttributeType.String,
                "family_name",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_FAMILY_NAME),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_FAMILY_NAME),
                Icon.PERSON,
                JsonPrimitive(SampleData.FAMILY_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "given_name",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_GIVEN_NAMES),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_GIVEN_NAMES),
                Icon.PERSON,
                JsonPrimitive(SampleData.GIVEN_NAME)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "birthdate",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DATE_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DATE_OF_BIRTH),
                Icon.TODAY,
                JsonPrimitive(SampleData.BIRTH_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.Number,
                "age_in_years",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_AGE_IN_YEARS),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_AGE_IN_YEARS),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_IN_YEARS)
            )
            .addJsonAttribute(
                DocumentAttributeType.Number,
                "age_birth_year",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_YEAR_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_YEAR_OF_BIRTH),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_BIRTH_YEAR)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "12",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_12),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_12),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "14",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_14),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_14),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "16",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_16),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_16),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_16)
            )
            // TODO: nest in age_equal_or_over object
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "18",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_18),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_18),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_18)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "21",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_21),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_21),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_21)
            )
            .addJsonAttribute(
                DocumentAttributeType.Boolean,
                "65",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_OLDER_THAN_65),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_OLDER_THAN_65),
                Icon.TODAY,
                JsonPrimitive(SampleData.AGE_OVER_65)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_family_name",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_FAMILY_NAME_AT_BIRTH),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_FAMILY_NAME_AT_BIRTH),
                Icon.PERSON,
                JsonPrimitive(SampleData.FAMILY_NAME_BIRTH)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_place",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_PLACE_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_PLACE_OF_BIRTH),
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_PLACE)
            )
            .addJsonAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "birth_country",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_COUNTRY_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_COUNTRY_OF_BIRTH),
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_COUNTRY)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_state",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_STATE_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_STATE_OF_BIRTH),
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_STATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "birth_city",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_CITY_OF_BIRTH),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_CITY_OF_BIRTH),
                Icon.PLACE,
                JsonPrimitive(SampleData.BIRTH_CITY)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "street_address",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_ADDRESS),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_ADDRESS),
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_ADDRESS)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "locality",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_CITY),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_CITY),
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_CITY)
            )
            .addJsonAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "country",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_COUNTRY),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_COUNTRY),
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_COUNTRY)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "postal_code",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_RESIDENT_POSTAL_CODE),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_RESIDENT_POSTAL_CODE),
                Icon.PLACE,
                JsonPrimitive(SampleData.RESIDENT_POSTAL_CODE)
            )
            .addJsonAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "gender",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_GENDER),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_GENDER),
                Icon.EMERGENCY,
                JsonPrimitive(SampleData.SEX_ISO_5218)
            )
            .addJsonAttribute(
                DocumentAttributeType.ComplexType,
                "nationalities",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_NATIONALITY),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_NATIONALITY),
                Icon.LANGUAGE,
                buildJsonArray {
                    add(JsonPrimitive(SampleData.NATIONALITY))
                }
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "issuance_date",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DATE_OF_ISSUE),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DATE_OF_ISSUE),
                Icon.DATE_RANGE,
                JsonPrimitive(SampleData.ISSUE_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DATE_OF_EXPIRY),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DATE_OF_EXPIRY),
                Icon.CALENDAR_CLOCK,
                JsonPrimitive(SampleData.EXPIRY_DATE)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "issuing_authority",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ISSUING_AUTHORITY),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ISSUING_AUTHORITY),
                Icon.ACCOUNT_BALANCE,
                JsonPrimitive(SampleData.ISSUING_AUTHORITY_EU_PID)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "document_number",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_DOCUMENT_NUMBER),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_DOCUMENT_NUMBER),
                Icon.NUMBERS,
                JsonPrimitive(SampleData.DOCUMENT_NUMBER)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "administrative_number",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ADMINISTRATIVE_NUMBER),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ADMINISTRATIVE_NUMBER),
                Icon.NUMBERS,
                JsonPrimitive(SampleData.ADMINISTRATIVE_NUMBER)
            )
            .addJsonAttribute(
                DocumentAttributeType.String,
                "issuing_jurisdiction",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ISSUING_JURISDICTION),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ISSUING_JURISDICTION),
                Icon.ACCOUNT_BALANCE,
                JsonPrimitive(SampleData.ISSUING_JURISDICTION)
            )
            .addJsonAttribute(
                DocumentAttributeType.StringOptions(Options.COUNTRY_ISO_3166_1_ALPHA_2),
                "issuing_country",
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_ATTRIBUTE_ISSUING_COUNTRY),
                getLocalizedString(GeneratedStringKeys.GERMAN_ID_DESCRIPTION_ISSUING_COUNTRY),
                Icon.ACCOUNT_BALANCE,
                JsonPrimitive(SampleData.ISSUING_COUNTRY)
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
